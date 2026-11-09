#!/usr/bin/env python3
"""Restricted Google Play test-track publisher. Uses only Python's standard library."""
import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

PACKAGE = 'com.hebi.livetranslate'
API = 'https://androidpublisher.googleapis.com'
BASE = f'/androidpublisher/v3/applications/{PACKAGE}'
MIN_VERSION = 14
MAX_VERSION = 2100000000
API_STATUSES = {'CANCELLED', 'UNKNOWN', 'INVALID_ARGUMENT', 'DEADLINE_EXCEEDED', 'NOT_FOUND',
                'ALREADY_EXISTS', 'PERMISSION_DENIED', 'RESOURCE_EXHAUSTED', 'FAILED_PRECONDITION',
                'ABORTED', 'OUT_OF_RANGE', 'UNIMPLEMENTED', 'INTERNAL', 'UNAVAILABLE', 'DATA_LOSS', 'UNAUTHENTICATED'}
REVIEW_HOLD_FORBIDDEN_MESSAGE = 'Changes are sent for review automatically. The query parameter changesNotSentForReview must not be set.'
REVIEW_HOLD_FORBIDDEN_REASON = 'CHANGES_NOT_SENT_FOR_REVIEW_FORBIDDEN'


class ReleaseError(RuntimeError):
    pass


class PlayApiError(ReleaseError):
    def __init__(self, method, status, api_status=None, safe_reason=None, diagnostic_reasons=()):
        self.http_status = status
        self.api_status = api_status if api_status in API_STATUSES else None
        self.safe_reason = safe_reason if safe_reason == REVIEW_HOLD_FORBIDDEN_REASON else None
        self.diagnostic_reasons = tuple(reason for reason in diagnostic_reasons
                                        if isinstance(reason, str) and re.fullmatch(r'[A-Z][A-Z0-9_]{1,79}', reason))[:8]
        super().__init__(f'Play API {method} failed with HTTP {status}; inspect Console registration, permissions and validation')


def require_initialization_request(env):
    import source
    try:
        if env.get('PLAY_RELEASE_MODE') != 'initialize' or env.get('GITHUB_REF') != 'refs/heads/main':
            raise ReleaseError('Initial upload requires an explicit trusted main initialize request')
        event = {} if env.get('GITHUB_EVENT_NAME') == 'workflow_dispatch' else json.loads(Path(env['GITHUB_EVENT_PATH']).read_text(encoding='utf-8'))
        sha = source.resolve(env, event)
        if sha != env.get('SOURCE_SHA'):
            raise ReleaseError('Initialization source does not match the verified release source')
        if env.get('GITHUB_EVENT_NAME') == 'workflow_run':
            subject = subprocess.run(['git', 'show', '-s', '--format=%s', sha], capture_output=True, check=False, text=True)
            if subject.returncode or source.release_mode(env, event, subject.stdout) != 'initialize':
                raise ReleaseError('Automatic initialization requires the explicit trusted main subject marker')
    except (source.SourceError, KeyError, OSError, ValueError):
        raise ReleaseError('Initial upload request metadata is missing or untrusted') from None


def version_code(value):
    if isinstance(value, bool) or not str(value).isascii() or not str(value).isdigit():
        raise ReleaseError('versionCode must be a positive integer')
    code = int(value)
    if not 1 <= code <= MAX_VERSION:
        raise ReleaseError('versionCode outside Android supported range')
    return code


class HttpTransport:
    def __init__(self, token, opener=urllib.request.urlopen):
        if not token or not token.strip():
            raise ReleaseError('PLAY_ACCESS_TOKEN is required; deployment was not attempted')
        self.token = token
        self.opener = opener

    def __call__(self, method, path, body=None, content_type=None):
        if not path.startswith('/edits') or '\n' in path:
            raise ReleaseError('Unsupported Play API path')
        upload = content_type is not None
        url = API + ('/upload' if upload else '') + BASE + path
        # Google documents edits.validate and edits.commit as bodyless operations.
        # The edit creation and upload methods retain their existing JSON/media bodies.
        is_bodyless_edit_action = method == 'POST' and re.fullmatch(r'/edits/[A-Za-z0-9]+:(?:validate|commit)(?:\\?.*)?', path) is not None
        data = (b'' if is_bodyless_edit_action else body if isinstance(body, bytes)
                else json.dumps(body).encode() if body is not None else None)
        headers = {'Authorization': 'Bearer ' + self.token}
        if data is not None:
            headers['Content-Type'] = content_type or 'application/json; charset=utf-8'
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with self.opener(request, timeout=300 if upload else 60) as response:
                raw = response.read()
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as error:
            # Response bodies can contain supplied credentials or private listing data.
            api_status = None
            safe_reason = None
            diagnostic_reasons = []
            try:
                raw = error.read(65537)
                if len(raw) <= 65536:
                    payload = json.loads(raw)
                    if isinstance(payload, dict) and isinstance(payload.get('error'), dict):
                        candidate = payload['error'].get('status')
                        if isinstance(candidate, str) and candidate in API_STATUSES:
                            api_status = candidate
                        if error.code == 400 and payload['error'].get('message') == REVIEW_HOLD_FORBIDDEN_MESSAGE:
                            safe_reason = REVIEW_HOLD_FORBIDDEN_REASON
                        for detail in payload['error'].get('details', []):
                            if isinstance(detail, dict) and detail.get('@type') == 'type.googleapis.com/google.rpc.ErrorInfo':
                                reason = detail.get('reason')
                                if isinstance(reason, str) and re.fullmatch(r'[A-Z][A-Z0-9_]{1,79}', reason):
                                    diagnostic_reasons.append(reason)
            except (OSError, ValueError):
                pass
            finally:
                error.close()
            raise PlayApiError(method, error.code, api_status, safe_reason, diagnostic_reasons) from None
        except (urllib.error.URLError, TimeoutError, ValueError):
            raise ReleaseError('Play API request or response failed; no successful deployment can be claimed') from None


class Publisher:
    def __init__(self, transport):
        self.request = transport

    @contextlib.contextmanager
    def edit(self, ignore_delete_error=True):
        edit_id = self.request('POST', '/edits', {})['id']
        if not str(edit_id).isalnum():
            raise ReleaseError('Invalid edit id from Play')
        path = '/edits/' + str(edit_id)
        try:
            yield path
        finally:
            failed = sys.exc_info()[0] is not None
            try:
                self.request('DELETE', path)
            except ReleaseError:
                if not ignore_delete_error and not failed:
                    raise

    def inventory(self, edit):
        bundles = self.request('GET', edit + '/bundles').get('bundles', [])
        apks = self.request('GET', edit + '/apks').get('apks', [])
        tracks = self.request('GET', edit + '/tracks').get('tracks', [])
        return bundles, apks, tracks

    def used_versions(self, edit, inventory=None):
        bundles, apks, tracks = inventory if inventory is not None else self.inventory(edit)
        values = [item['versionCode'] for item in bundles + apks]
        for track in tracks:
            for release in track.get('releases', []):
                values.extend(release.get('versionCodes', []))
        return {version_code(value) for value in values}

    def allocate_version(self):
        with self.edit() as edit:
            code = max(self.used_versions(edit) | {MIN_VERSION - 1}) + 1
            return version_code(code)

    def inspect(self, require_binary=False):
        with self.edit(ignore_delete_error=False) as edit:
            inventory = self.inventory(edit)
            used = self.used_versions(edit, inventory)
        if require_binary and not (inventory[0] or inventory[1]):
            raise ReleaseError('No registered AAB or APK was found; complete the explicit initial upload before normal API changes')
        return {'package': PACKAGE,
                'status': 'api_access_verified' if inventory[0] or inventory[1] else 'requires_initial_upload',
                'highestVersionCode': max(used) if used else None,
                'deployment': 'not_attempted'}

    @contextlib.contextmanager
    def initial_stage(self, stage):
        try:
            yield
        except (ReleaseError, KeyError, OSError, TypeError) as error:
            if not isinstance(error, ReleaseError):
                error = ReleaseError('Initial upload input or API response is invalid')
            if not getattr(error, 'stage', None):
                error.stage = stage
            raise error from None

    def require_empty(self, edit):
        inventory = self.inventory(edit)
        if inventory[0] or inventory[1] or self.used_versions(edit, inventory):
            raise ReleaseError('First upload requires empty bundle, APK and track version inventories')

    def allocate_first(self):
        with self.initial_stage('inventory'), self.edit(ignore_delete_error=False) as edit:
            self.require_empty(edit)
        return MIN_VERSION

    def initialize(self, bundle):
        with self.initial_stage('edit_create'), self.edit() as edit:
            with self.initial_stage('inventory'):
                self.require_empty(edit)
            with self.initial_stage('upload'):
                bundle_bytes = Path(bundle).read_bytes()
                bundle_hash = hashlib.sha256(bundle_bytes).hexdigest()
                uploaded = self.request('POST', edit + '/bundles?uploadType=media', bundle_bytes, 'application/octet-stream')
                if version_code(uploaded['versionCode']) != MIN_VERSION:
                    raise ReleaseError('Initial bundle must have versionCode 14')
                uploaded_hash = uploaded.get('sha256')
                if not isinstance(uploaded_hash, str) or not re.fullmatch(r'[a-fA-F0-9]{64}', uploaded_hash) or uploaded_hash.lower() != bundle_hash:
                    raise ReleaseError('Initial bundle SHA-256 does not match the verified local AAB')
            with self.initial_stage('track_update'):
                self.request('PUT', edit + '/tracks/internal', {'track': 'internal', 'releases': [
                    {'name': '1.3.1 (14)', 'versionCodes': ['14'], 'status': 'draft'}]})
            commit_result = self.commit(edit, changes_not_sent_for_review=True, fail_if_in_review=True)
        try:
            with self.initial_stage('readback'), self.edit(ignore_delete_error=False) as edit:
                bundles = self.request('GET', edit + '/bundles').get('bundles', [])
                matches = [item for item in bundles if str(item.get('versionCode')) == str(MIN_VERSION)]
                if len(matches) != 1 or not isinstance(matches[0].get('sha256'), str) or matches[0]['sha256'].lower() != bundle_hash:
                    raise ReleaseError('Initial bundle readback does not match the uploaded AAB')
                track = self.request('GET', edit + '/tracks/internal')
                if not any(release.get('status') == 'draft' and release.get('versionCodes') == ['14'] for release in track.get('releases', [])):
                    raise ReleaseError('Initial internal draft readback does not match versionCode 14')
        except (ReleaseError, KeyError, TypeError) as error:
            failure = ReleaseError('Initial draft committed but fresh readback failed; inspect Console before retrying')
            failure.committed = True
            failure.stage = 'readback'
            if isinstance(error, PlayApiError):
                failure.http_status, failure.api_status = error.http_status, error.api_status
            if commit_result:
                failure.review_hold_parameter_omitted = True
            raise failure from None
        result = {'package': PACKAGE, 'track': 'internal', 'versionCode': MIN_VERSION,
                'releaseStatus': 'draft', 'status': 'initial_draft_committed_and_verified',
                'uploadedBundleSHA256': bundle_hash, 'availability': 'draft_not_published',
                'appSigning': 'requires_console_check', 'initializationFlagChanged': False}
        if commit_result:
            result.update(commit_result)
        return result

    def commit(self, edit, changes_not_sent_for_review=False, fail_if_in_review=False):
        if fail_if_in_review:
            with self.initial_stage('validate'):
                self.request('POST', edit + ':validate', {})
            with self.initial_stage('commit'):
                try:
                    self.request('POST', edit + ':commit?changesNotSentForReview=true&changesInReviewBehavior=ERROR_IF_IN_REVIEW', {})
                except PlayApiError as error:
                    if error.http_status != 400 or error.safe_reason != REVIEW_HOLD_FORBIDDEN_REASON:
                        raise
                    try:
                        self.request('POST', edit + ':commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW', {})
                    except ReleaseError as retry_error:
                        retry_error.review_hold_parameter_omitted = True
                        raise
                    return {'reviewHoldParameterOmitted': True, 'reason': REVIEW_HOLD_FORBIDDEN_REASON}
            return
        self.request('POST', edit + ':validate', {})
        query = '?changesNotSentForReview=true' if changes_not_sent_for_review else ''
        self.request('POST', edit + ':commit' + query, {})

    def verify_track(self, track, code, status):
        try:
            with self.edit() as edit:
                result = self.request('GET', edit + '/tracks/' + track)
                if not any(release.get('status') == status and str(code) in [str(value) for value in release.get('versionCodes', [])]
                           for release in result.get('releases', [])):
                    raise ReleaseError('release absent')
        except ReleaseError:
            raise ReleaseError('Play edit committed but fresh readback failed; inspect Console before retrying') from None
        return {'package': PACKAGE, 'track': track, 'versionCode': code,
                'releaseStatus': status, 'status': 'api_committed_and_verified',
                'availability': 'requires_console_check'}

    def deploy(self, bundle, code, track='internal', release_status='completed', changes_not_sent_for_review=False):
        if track != 'internal':
            raise ReleaseError('New bundles may only be deployed to internal')
        if release_status not in ('draft', 'completed'):
            raise ReleaseError('Only draft or completed test releases are supported')
        code = version_code(code)
        with self.edit() as edit:
            used = self.used_versions(edit)
            if code in used:
                raise ReleaseError(f'versionCode {code} is already used; allocate a fresh version before building')
            if code <= max(used | {MIN_VERSION - 1}):
                raise ReleaseError('Version allocation is stale; allocate above every current Play artifact and rebuild before uploading')
            bundle_bytes = Path(bundle).read_bytes()
            bundle_hash = hashlib.sha256(bundle_bytes).hexdigest()
            uploaded = self.request('POST', edit + '/bundles?uploadType=media', bundle_bytes, 'application/octet-stream')
            if version_code(uploaded['versionCode']) != code:
                raise ReleaseError('Uploaded bundle versionCode does not match the allocated version')
            uploaded_hash = uploaded.get('sha256')
            if not isinstance(uploaded_hash, str) or not re.fullmatch(r'[a-fA-F0-9]{64}', uploaded_hash) or uploaded_hash.lower() != bundle_hash:
                raise ReleaseError('Uploaded bundle SHA-256 is missing, invalid or does not match the local AAB; release was not committed')
            self.request('PUT', edit + '/tracks/internal', {'track': 'internal', 'releases': [
                {'name': f'1.3.1 ({code})', 'versionCodes': [str(code)], 'status': release_status}]})
            self.commit(edit, changes_not_sent_for_review)
        result = self.verify_track(track, code, release_status)
        result['uploadedBundleSHA256'] = bundle_hash
        return result

    def promote(self, code, track='alpha', changes_not_sent_for_review=False):
        if track != 'alpha':
            raise ReleaseError('Only the alpha closed-testing track is permitted for promotion')
        code = version_code(code)
        with self.edit() as edit:
            source = self.request('GET', edit + '/tracks/internal')
            release = next((release for release in source.get('releases', [])
                            if release.get('status') == 'completed' and str(code) in [str(value) for value in release.get('versionCodes', [])]), None)
            if release is None:
                raise ReleaseError('The exact versionCode must exist in a completed internal release')
            if code not in self.used_versions(edit):
                raise ReleaseError('Requested uploaded versionCode was not found')
            promoted = {'name': release.get('name', f'1.3.1 ({code})'), 'versionCodes': [str(code)], 'status': 'completed'}
            if release.get('releaseNotes'):
                promoted['releaseNotes'] = release['releaseNotes']
            self.request('PUT', edit + '/tracks/alpha', {'track': 'alpha', 'releases': [promoted]})
            self.commit(edit, changes_not_sent_for_review)
        return self.verify_track(track, code, 'completed')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path)
    sub = parser.add_subparsers(dest='command', required=True)
    inspect = sub.add_parser('inspect')
    inspect.add_argument('--require-binary', action='store_true')
    sub.add_parser('allocate-first')
    initialize = sub.add_parser('initialize')
    initialize.add_argument('--bundle', required=True)
    sub.add_parser('allocate')
    deploy = sub.add_parser('deploy')
    deploy.add_argument('--bundle', required=True)
    deploy.add_argument('--version-code', required=True, type=version_code)
    deploy.add_argument('--status', choices=['completed', 'draft'], default='completed')
    deploy.add_argument('--changes-not-sent-for-review', action='store_true')
    promote = sub.add_parser('promote')
    promote.add_argument('--version-code', required=True, type=version_code)
    promote.add_argument('--track', choices=['alpha'], default='alpha')
    promote.add_argument('--changes-not-sent-for-review', action='store_true')
    args = parser.parse_args()
    try:
        from preflight import require_initialized
        if args.command in ('allocate-first', 'initialize'):
            require_initialization_request(os.environ)
        elif args.command != 'inspect':
            require_initialized(os.environ)
        client = Publisher(HttpTransport(os.environ.get('PLAY_ACCESS_TOKEN', '')))
        if args.command == 'inspect':
            result = client.inspect(require_binary=args.require_binary)
            if args.require_binary and os.environ.get('GITHUB_OUTPUT'):
                with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
                    output.write('initialized=true\n')
        elif args.command in ('allocate', 'allocate-first'):
            code = client.allocate_first() if args.command == 'allocate-first' else client.allocate_version()
            result = {'package': PACKAGE, 'versionCode': code, 'status': 'version_allocated_not_uploaded'}
            if os.environ.get('GITHUB_OUTPUT'):
                with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
                    output.write(f'version_code={code}\n')
        elif args.command == 'initialize':
            result = client.initialize(args.bundle)
        elif args.command == 'deploy':
            result = client.deploy(args.bundle, args.version_code, release_status=args.status,
                                   changes_not_sent_for_review=args.changes_not_sent_for_review)
        else:
            result = client.promote(args.version_code, args.track, args.changes_not_sent_for_review)
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(result, ensure_ascii=False))
        return 0
    except (ReleaseError, KeyError, OSError, RuntimeError) as error:
        message = str(error) if isinstance(error, RuntimeError) else 'Release input or API response is invalid'
        if args.command in ('inspect', 'allocate-first', 'initialize'):
            result = {'package': PACKAGE, 'status': 'api_preflight_blocked', 'deployment': 'not_attempted'}
            if args.command != 'inspect':
                result.update(status='initialization_committed_readback_failed' if getattr(error, 'committed', False) else 'initialization_blocked',
                              stage=getattr(error, 'stage', 'request_guard'))
                if args.command == 'initialize':
                    result['deployment'] = 'not_published'
                    if result['stage'] in ('commit', 'readback'):
                        result['commitOutcome'] = 'unconfirmed'
                        result['retry'] = 'inspect_console_before_retry'
                if getattr(error, 'committed', False):
                    result['draftReadback'] = 'not_verified'
            if getattr(error, 'http_status', None) is not None:
                result['httpStatus'] = error.http_status
            if getattr(error, 'api_status', None) is not None:
                result['apiStatus'] = error.api_status
            if getattr(error, 'review_hold_parameter_omitted', False):
                result.update(reviewHoldParameterOmitted=True, reason=REVIEW_HOLD_FORBIDDEN_REASON)
            if args.report:
                args.report.parent.mkdir(parents=True, exist_ok=True)
                args.report.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
            print(json.dumps(result, ensure_ascii=False))
        print('Release failed: ' + message, file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
