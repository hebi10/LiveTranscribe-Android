import importlib.util
import contextlib
import hashlib
import io
import json
import os
import pathlib
import sys
import tempfile
import unittest
from unittest import mock

SCRIPT = pathlib.Path(__file__).parents[1] / 'publisher.py'
sys.path.insert(0, str(SCRIPT.parent))
spec = importlib.util.spec_from_file_location('publisher', SCRIPT)
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class PlayServer:
    def __init__(self, bundles=(), apks=(), tracks=None, readback=True):
        self.bundles = [{'versionCode': code} for code in bundles]
        self.apks = [{'versionCode': code} for code in apks]
        self.tracks = tracks or []
        self.calls = []
        self.pending = None
        self.committed = False
        self.readback = readback

    def __call__(self, method, path, body=None, content_type=None):
        self.calls.append((method, path, body))
        if method == 'POST' and path == '/edits':
            return {'id': str(len(self.calls))}
        if method == 'DELETE':
            return {}
        if method == 'GET' and path.endswith('/bundles'):
            return {'bundles': self.bundles}
        if method == 'GET' and path.endswith('/apks'):
            return {'apks': self.apks}
        if method == 'GET' and path.endswith('/tracks'):
            return {'tracks': self.tracks}
        if method == 'GET' and '/tracks/' in path:
            target = path.rsplit('/', 1)[1]
            if self.committed and self.readback and self.pending and self.pending['track'] == target:
                return self.pending
            return next((track for track in self.tracks if track['track'] == target), {'track': target, 'releases': []})
        if method == 'POST' and path.endswith('/bundles?uploadType=media'):
            return {'versionCode': 35, 'sha256': hashlib.sha256(body).hexdigest()}
        if method == 'PUT' and '/tracks/' in path:
            self.pending = body
            return body
        if method == 'POST' and ':validate' in path:
            return {}
        if method == 'POST' and ':commit' in path:
            self.committed = True
            return {}
        raise AssertionError((method, path))


def track(name, code, status='completed'):
    return {'track': name, 'releases': [{'status': status, 'versionCodes': [str(code)]}]}


class PublisherTests(unittest.TestCase):
    def test_inspect_reads_artifacts_and_closes_without_upload_or_commit(self):
        server = PlayServer(bundles=[34], apks=[33], tracks=[track('internal', 34)])
        result = publisher.Publisher(server).inspect()
        self.assertEqual(result['status'], 'api_access_verified')
        self.assertEqual(result['highestVersionCode'], 34)
        self.assertEqual(server.calls, [
            ('POST', '/edits', {}), ('GET', '/edits/1/bundles', None),
            ('GET', '/edits/1/apks', None), ('GET', '/edits/1/tracks', None),
            ('DELETE', '/edits/1', None)])
        self.assertFalse(server.committed)

    def test_inspect_empty_app_requires_initial_upload(self):
        result = publisher.Publisher(PlayServer()).inspect()
        self.assertEqual(result['status'], 'requires_initial_upload')
        self.assertIsNone(result['highestVersionCode'])

    def test_inspect_track_codes_without_artifacts_do_not_confirm_first_upload(self):
        result = publisher.Publisher(PlayServer(tracks=[track('internal', 34)])).inspect()
        self.assertEqual(result['status'], 'requires_initial_upload')

    def test_inspect_read_failure_still_closes_edit(self):
        server = PlayServer()
        def fail(method, path, body=None, content_type=None):
            if method == 'GET':
                raise publisher.PlayApiError(method, 403)
            return server(method, path, body, content_type)
        with self.assertRaises(publisher.ReleaseError):
            publisher.Publisher(fail).inspect()
        self.assertEqual(server.calls[-1], ('DELETE', '/edits/1', None))

    def test_inspect_cleanup_failure_cannot_report_verified_access(self):
        server = PlayServer(bundles=[34])
        def fail(method, path, body=None, content_type=None):
            if method == 'DELETE':
                raise publisher.PlayApiError(method, 403)
            return server(method, path, body, content_type)
        with self.assertRaises(publisher.ReleaseError):
            publisher.Publisher(fail).inspect()

    def test_inspect_cli_does_not_require_initialized_and_preserves_safe_http_status(self):
        for status in (403, 404):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as directory:
                report = pathlib.Path(directory) / 'probe.json'
                def fail(*args):
                    raise publisher.PlayApiError('POST', status)
                output, errors = io.StringIO(), io.StringIO()
                with mock.patch.dict(os.environ, {'PLAY_ACCESS_TOKEN': 'test-only-token', 'PLAY_CONSOLE_INITIALIZED': ''}), mock.patch.object(publisher, 'HttpTransport', return_value=fail), mock.patch('sys.argv', ['publisher.py', '--report', str(report), 'inspect']), contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
                    self.assertEqual(publisher.main(), 1)
                result = json.loads(report.read_text())
                self.assertEqual(result['status'], 'api_preflight_blocked')
                self.assertEqual(result['httpStatus'], status)
                self.assertNotIn('test-only-token', output.getvalue() + errors.getvalue() + report.read_text())
                self.assertNotIn('bootstrap', errors.getvalue())

    def test_mutating_cli_commands_still_require_console_initialization_before_api(self):
        commands = (['allocate'], ['deploy', '--bundle', 'unused.aab', '--version-code', '35'], ['promote', '--version-code', '35'])
        for command in commands:
            with self.subTest(command=command), mock.patch.dict(os.environ, {'PLAY_CONSOLE_INITIALIZED': ''}), mock.patch.object(publisher, 'HttpTransport') as transport, mock.patch('sys.argv', ['publisher.py'] + command), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(publisher.main(), 1)
                transport.assert_not_called()

    def test_uploaded_hash_missing_invalid_or_mismatched_blocks_track_and_commit(self):
        for returned in (None, '', 'x' * 64, 'a' * 64):
            server = PlayServer()
            def request(method, path, body=None, content_type=None):
                response = server(method, path, body, content_type)
                if 'uploadType=media' in path:
                    response['sha256'] = returned
                return response
            with self.subTest(returned=returned), tempfile.NamedTemporaryFile() as bundle:
                bundle.write(b'verified AAB bytes')
                bundle.flush()
                with self.assertRaisesRegex(publisher.ReleaseError, 'SHA-256'):
                    publisher.Publisher(request).deploy(bundle.name, 35)
                self.assertFalse(any(method == 'PUT' for method, _, _ in server.calls))
                self.assertFalse(server.committed)

    def test_uploaded_uppercase_hash_is_accepted_when_bytes_match(self):
        server = PlayServer()
        def request(method, path, body=None, content_type=None):
            response = server(method, path, body, content_type)
            if 'uploadType=media' in path:
                response['sha256'] = response['sha256'].upper()
            return response
        with tempfile.NamedTemporaryFile() as bundle:
            bundle.write(b'verified AAB bytes')
            bundle.flush()
            result = publisher.Publisher(request).deploy(bundle.name, 35)
        self.assertEqual(result['uploadedBundleSHA256'], hashlib.sha256(b'verified AAB bytes').hexdigest())

    def test_allocation_includes_unassigned_bundles_apks_and_draft_tracks(self):
        server = PlayServer(bundles=[9, 32], apks=[34], tracks=[track('internal', 20), track('alpha', 33, 'draft')])
        self.assertEqual(publisher.Publisher(server).allocate_version(), 35)
        self.assertTrue(any(method == 'DELETE' for method, _, _ in server.calls))

    def test_allocation_never_drops_below_release_default(self):
        self.assertEqual(publisher.Publisher(PlayServer()).allocate_version(), 14)

    def test_collision_fails_before_upload_even_when_version_is_only_draft(self):
        server = PlayServer(tracks=[track('alpha', 35, 'draft')])
        with tempfile.NamedTemporaryFile() as bundle:
            with self.assertRaisesRegex(publisher.ReleaseError, 'already used'):
                publisher.Publisher(server).deploy(bundle.name, 35)
        self.assertFalse(any('uploadType' in path for _, path, _ in server.calls))
        self.assertFalse(server.committed)

    def test_upload_commits_and_reads_back_in_new_edit(self):
        server = PlayServer(bundles=[9])
        with tempfile.NamedTemporaryFile() as bundle:
            result = publisher.Publisher(server).deploy(bundle.name, 35)
        self.assertEqual(result['versionCode'], 35)
        self.assertEqual(result['status'], 'api_committed_and_verified')
        self.assertEqual(result['availability'], 'requires_console_check')
        self.assertEqual(sum(method == 'POST' and path == '/edits' for method, path, _ in server.calls), 2)

    def test_higher_version_appearing_during_build_blocks_stale_allocation(self):
        for server in (PlayServer(bundles=[36]), PlayServer(apks=[36]), PlayServer(tracks=[track('alpha', 36, 'draft')])):
            with tempfile.NamedTemporaryFile() as bundle:
                with self.assertRaisesRegex(publisher.ReleaseError, 'stale.*rebuild'):
                    publisher.Publisher(server).deploy(bundle.name, 35)
            self.assertFalse(any('uploadType' in path or method == 'PUT' for method, path, _ in server.calls))
            self.assertFalse(server.committed)

    def test_failed_readback_is_failure_after_commit(self):
        server = PlayServer(readback=False)
        with tempfile.NamedTemporaryFile() as bundle:
            with self.assertRaisesRegex(publisher.ReleaseError, 'committed.*readback'):
                publisher.Publisher(server).deploy(bundle.name, 35)
        self.assertTrue(server.committed)

    def test_closed_promotion_reuses_internal_code_without_upload(self):
        server = PlayServer(bundles=[35], tracks=[track('internal', 35)])
        result = publisher.Publisher(server).promote(35, 'alpha')
        self.assertEqual(result['track'], 'alpha')
        self.assertFalse(any('uploadType' in path for _, path, _ in server.calls))
        self.assertEqual(server.pending['releases'][0]['versionCodes'], ['35'])

    def test_promotion_requires_exact_completed_internal_version(self):
        for tracks in ([track('internal', 34)], [track('internal', 35, 'draft')]):
            server = PlayServer(bundles=[35], tracks=tracks)
            with self.assertRaisesRegex(publisher.ReleaseError, 'completed internal'):
                publisher.Publisher(server).promote(35, 'alpha')
            self.assertFalse(server.committed)

    def test_production_open_and_arbitrary_tracks_fail_without_api_mutation(self):
        for target in ('production', 'beta', 'open', 'internal', 'custom-closed'):
            server = PlayServer()
            with self.assertRaises(publisher.ReleaseError):
                publisher.Publisher(server).promote(35, target)
            self.assertEqual(server.calls, [])

    def test_bundle_upload_cannot_target_closed_track(self):
        server = PlayServer()
        with self.assertRaises(publisher.ReleaseError):
            publisher.Publisher(server).deploy('unused.aab', 35, 'alpha')
        self.assertEqual(server.calls, [])

    def test_invalid_version_codes_rejected_before_api(self):
        for code in (0, -1, 2100000001, True, '10x'):
            server = PlayServer()
            with self.assertRaises(publisher.ReleaseError):
                publisher.Publisher(server).promote(code, 'alpha')
            self.assertEqual(server.calls, [])

    def test_missing_access_token_fails_without_network(self):
        with self.assertRaisesRegex(publisher.ReleaseError, 'PLAY_ACCESS_TOKEN'):
            publisher.HttpTransport('')

    def test_validate_and_commit_have_empty_request_bodies(self):
        class Response:
            def __enter__(self): return self
            def __exit__(self, *_): pass
            def read(self): return b'{}'
        sent = []
        def opener(request, **kwargs):
            sent.append((request.get_method(), request.full_url, request.data))
            return Response()
        client = publisher.HttpTransport('synthetic-token', opener=opener)
        client('POST', '/edits/123:validate', {})
        client('POST', '/edits/123:commit?changesNotSentForReview=true', {})
        client('POST', '/edits', {})
        self.assertEqual(sent[0][2], b'')
        self.assertEqual(sent[1][2], b'')
        self.assertEqual(sent[2][2], b'{}')

    def test_errorinfo_reason_is_filtered_and_never_exposes_metadata(self):
        import urllib.error
        secret = 'private-user-address@example.com'
        body = json.dumps({'error': {'status': 'PERMISSION_DENIED', 'message': secret, 'details': [
            {'@type': 'type.googleapis.com/google.rpc.ErrorInfo', 'reason': 'DRAFT_APP_PERMISSION_REQUIRED',
             'metadata': {'email': secret}},
            {'@type': 'type.googleapis.com/google.rpc.ErrorInfo', 'reason': secret}
        ]}}).encode()
        def fail(request, **kwargs):
            raise urllib.error.HTTPError(request.full_url, 403, 'error', {}, io.BytesIO(body))
        with self.assertRaises(publisher.PlayApiError) as ctx:
            publisher.HttpTransport('synthetic-token', opener=fail)('POST', '/edits/123:validate', {})
        self.assertEqual(ctx.exception.diagnostic_reasons, ('DRAFT_APP_PERMISSION_REQUIRED',))
        self.assertNotIn(secret, str(ctx.exception))

    def test_http_errors_do_not_echo_server_or_credentials(self):
        import io
        import urllib.error
        secret = 'private-token-should-never-appear'
        response = io.BytesIO(secret.encode())
        def fail(request, **kwargs):
            raise urllib.error.HTTPError(request.full_url, 403, secret, {}, response)
        with self.assertRaises(publisher.ReleaseError) as error:
            publisher.HttpTransport(secret, opener=fail)('POST', '/edits', {})
        self.assertNotIn(secret, str(error.exception))
        self.assertIn('403', str(error.exception))
        self.assertEqual(error.exception.http_status, 403)
        self.assertTrue(response.closed)


if __name__ == '__main__':
    unittest.main()
