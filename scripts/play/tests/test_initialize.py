import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).parents[1]))
import publisher
from test_publisher import PlayServer, track

REQUEST_ENV = {'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REF': 'refs/heads/main',
               'GITHUB_REPOSITORY': 'hebi10/LiveTranscribe-Android', 'PLAY_RELEASE_MODE': 'initialize',
               'GITHUB_SHA': 'a' * 40, 'SOURCE_SHA': 'a' * 40,
               'PLAY_ACCESS_TOKEN': 'test-only-token', 'PLAY_CONSOLE_INITIALIZED': ''}


class FirstUploadServer(PlayServer):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.uploaded = None

    def __call__(self, method, path, body=None, content_type=None):
        if method == 'POST' and path.endswith('/bundles?uploadType=media'):
            self.calls.append((method, path, body))
            self.uploaded = {'versionCode': 14, 'sha256': hashlib.sha256(body).hexdigest()}
            return dict(self.uploaded)
        if self.committed and self.uploaded and method == 'GET' and path.endswith('/bundles'):
            self.calls.append((method, path, body))
            return {'bundles': [dict(self.uploaded)]}
        return super().__call__(method, path, body, content_type)


class InitializeTests(unittest.TestCase):
    def test_allocate_first_checks_every_inventory_and_allocates_only_14(self):
        server = FirstUploadServer()
        self.assertEqual(publisher.Publisher(server).allocate_first(), 14)
        self.assertEqual([path.rsplit('/', 1)[-1] for method, path, _ in server.calls if method == 'GET'], ['bundles', 'apks', 'tracks'])
        self.assertEqual(server.calls[-1][0], 'DELETE')
        self.assertFalse(server.committed)

    def test_existing_bundle_apk_or_track_code_blocks_first_allocation_and_upload(self):
        for kwargs in ({'bundles': [14]}, {'apks': [1]}, {'tracks': [track('alpha', 2, 'draft')]}):
            for command in ('allocate_first', 'initialize'):
                server = FirstUploadServer(**kwargs)
                with self.subTest(kwargs=kwargs, command=command), self.assertRaisesRegex(publisher.ReleaseError, 'empty'):
                    method = getattr(publisher.Publisher(server), command)
                    method() if command == 'allocate_first' else method('unused.aab')
                self.assertFalse(any('uploadType' in path or method == 'PUT' for method, path, _ in server.calls))
                self.assertEqual(server.calls[-1][0], 'DELETE')

    def test_version_registered_during_build_blocks_initialize_before_upload(self):
        server = FirstUploadServer()
        client = publisher.Publisher(server)
        self.assertEqual(client.allocate_first(), 14)
        server.bundles.append({'versionCode': 14})
        with self.assertRaisesRegex(publisher.ReleaseError, 'empty'):
            client.initialize('unused.aab')
        self.assertFalse(any('uploadType' in path for _, path, _ in server.calls))

    def test_initialize_uploads_only_internal_draft_and_verifies_fresh_bundle_hash(self):
        server = FirstUploadServer()
        with tempfile.NamedTemporaryFile() as bundle:
            bundle.write(b'first signed AAB')
            bundle.flush()
            result = publisher.Publisher(server).initialize(bundle.name)
        release = server.pending['releases'][0]
        self.assertEqual((server.pending['track'], release['versionCodes'], release['status']), ('internal', ['14'], 'draft'))
        self.assertEqual(result['status'], 'initial_draft_committed_and_verified')
        self.assertEqual(result['uploadedBundleSHA256'], hashlib.sha256(b'first signed AAB').hexdigest())
        self.assertEqual(result['availability'], 'draft_not_published')
        commits = [path for method, path, _ in server.calls if method == 'POST' and ':commit' in path]
        self.assertEqual(len(commits), 1)
        self.assertIn('changesNotSentForReview=true', commits[0])
        self.assertIn('changesInReviewBehavior=ERROR_IF_IN_REVIEW', commits[0])
        self.assertEqual(sum(method == 'POST' and path == '/edits' for method, path, _ in server.calls), 2)
        self.assertTrue(any(method == 'GET' and path == '/edits/9/bundles' for method, path, _ in server.calls) or sum(method == 'GET' and path.endswith('/bundles') for method, path, _ in server.calls) == 2)

    def test_uploaded_code_or_hash_mismatch_blocks_track_and_commit(self):
        for field, value in (('versionCode', 15), ('sha256', 'a' * 64), ('sha256', None)):
            server = FirstUploadServer()
            def request(method, path, body=None, content_type=None):
                response = server(method, path, body, content_type)
                if 'uploadType' in path:
                    response[field] = value
                return response
            with self.subTest(field=field, value=value), tempfile.NamedTemporaryFile() as bundle, self.assertRaises(publisher.ReleaseError):
                publisher.Publisher(request).initialize(bundle.name)
            self.assertFalse(server.committed)
            self.assertFalse(any(method == 'PUT' for method, _, _ in server.calls))

    def test_fresh_bundle_or_track_mismatch_is_failure_after_commit(self):
        for mismatch in ('hash', 'missing_bundle', 'track'):
            server = FirstUploadServer(readback=mismatch != 'track')
            def request(method, path, body=None, content_type=None):
                response = server(method, path, body, content_type)
                if server.committed and method == 'GET' and path.endswith('/bundles'):
                    if mismatch == 'hash':
                        response['bundles'][0]['sha256'] = 'a' * 64
                    elif mismatch == 'missing_bundle':
                        response['bundles'] = []
                return response
            with self.subTest(mismatch=mismatch), tempfile.NamedTemporaryFile() as bundle, self.assertRaisesRegex(publisher.ReleaseError, 'committed.*readback') as caught:
                publisher.Publisher(request).initialize(bundle.name)
            self.assertTrue(server.committed)
            self.assertTrue(caught.exception.committed)

    def test_initial_commands_require_manual_main_initialize_context_before_api(self):
        for field, value in (('GITHUB_EVENT_NAME', 'pull_request'), ('GITHUB_REF', 'refs/heads/other'), ('GITHUB_REPOSITORY', 'hebi10/other'), ('PLAY_RELEASE_MODE', 'deploy')):
            for command in (['allocate-first'], ['initialize', '--bundle', 'unused.aab']):
                env = dict(REQUEST_ENV, **{field: value})
                with self.subTest(field=field, command=command), mock.patch.dict(os.environ, env), mock.patch.object(publisher, 'HttpTransport') as transport, mock.patch('sys.argv', ['publisher.py'] + command), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                    self.assertEqual(publisher.main(), 1)
                    transport.assert_not_called()

    def test_initialize_marker_context_requires_matching_verified_sha_and_producer(self):
        import subprocess
        run = {'name': 'Private Android APK', 'path': '.github/workflows/build.yml', 'event': 'push', 'conclusion': 'success', 'head_branch': 'main', 'head_sha': 'a' * 40, 'head_repository': {'full_name': REQUEST_ENV['GITHUB_REPOSITORY']}}
        with tempfile.TemporaryDirectory() as directory:
            event_path = Path(directory) / 'event.json'
            event_path.write_text(json.dumps({'workflow_run': run}))
            env = dict(REQUEST_ENV, GITHUB_EVENT_NAME='workflow_run', GITHUB_EVENT_PATH=str(event_path))
            git = subprocess.CompletedProcess([], 0, '[initialize-play]\n', '')
            with mock.patch.object(publisher.subprocess, 'run', return_value=git):
                publisher.require_initialization_request(env)
                for invalid in (dict(env, SOURCE_SHA='b' * 40), dict(env, GITHUB_REF='refs/tags/v1')):
                    with self.assertRaises(publisher.ReleaseError):
                        publisher.require_initialization_request(invalid)
            with mock.patch.object(publisher.subprocess, 'run', return_value=subprocess.CompletedProcess([], 0, 'ordinary\n', '')):
                with self.assertRaises(publisher.ReleaseError):
                    publisher.require_initialization_request(env)

    def test_initialize_cli_never_changes_initialization_flag(self):
        server = FirstUploadServer()
        with tempfile.NamedTemporaryFile() as bundle, mock.patch.dict(os.environ, REQUEST_ENV), mock.patch.object(publisher, 'HttpTransport', return_value=server), mock.patch('sys.argv', ['publisher.py', 'initialize', '--bundle', bundle.name]), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(publisher.main(), 0)
            self.assertEqual(os.environ['PLAY_CONSOLE_INITIALIZED'], '')

    def test_initial_upload_http_failure_reports_safe_stage_status_without_raw_body(self):
        server = FirstUploadServer()
        def request(method, path, body=None, content_type=None):
            if 'uploadType' in path:
                raise publisher.PlayApiError(method, 400, 'FAILED_PRECONDITION')
            return server(method, path, body, content_type)
        with tempfile.TemporaryDirectory() as directory, tempfile.NamedTemporaryFile() as bundle:
            report = Path(directory) / 'result.json'
            output, errors = io.StringIO(), io.StringIO()
            with mock.patch.dict(os.environ, REQUEST_ENV), mock.patch.object(publisher, 'HttpTransport', return_value=request), mock.patch('sys.argv', ['publisher.py', '--report', str(report), 'initialize', '--bundle', bundle.name]), contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
                self.assertEqual(publisher.main(), 1)
            result = json.loads(report.read_text())
            self.assertEqual((result['status'], result['stage'], result['httpStatus'], result['apiStatus']), ('initialization_blocked', 'upload', 400, 'FAILED_PRECONDITION'))
            self.assertEqual(result['deployment'], 'not_published')
            self.assertNotIn('test-only-token', report.read_text() + output.getvalue() + errors.getvalue())
            self.assertEqual(server.calls[-1][0], 'DELETE')
            self.assertFalse(server.committed)

    def test_malformed_upload_response_keeps_upload_stage_without_echoing_fields(self):
        server = FirstUploadServer()
        def request(method, path, body=None, content_type=None):
            return {'private': 'must not appear'} if 'uploadType' in path else server(method, path, body, content_type)
        with tempfile.NamedTemporaryFile() as bundle, self.assertRaises(publisher.ReleaseError) as error:
            publisher.Publisher(request).initialize(bundle.name)
        self.assertEqual(error.exception.stage, 'upload')
        self.assertNotIn('must not appear', str(error.exception))
        self.assertFalse(server.committed)

    def test_transport_allows_only_canonical_error_status_and_closes_body(self):
        import urllib.error
        for status in ('FAILED_PRECONDITION', 'PRIVATE_TOKEN_SHOULD_NOT_APPEAR'):
            raw = io.BytesIO(json.dumps({'error': {'status': status, 'message': 'private server body'}}).encode())
            def fail(request, **kwargs):
                raise urllib.error.HTTPError(request.full_url, 400, 'private message', {}, raw)
            with self.subTest(status=status), self.assertRaises(publisher.PlayApiError) as error:
                publisher.HttpTransport('private token', fail)('POST', '/edits', {})
            self.assertEqual(error.exception.api_status, status if status == 'FAILED_PRECONDITION' else None)
            self.assertNotIn('private', str(error.exception))
            self.assertNotIn('PRIVATE_TOKEN', str(error.exception))
            self.assertTrue(raw.closed)


class InitializeWorkflowTests(unittest.TestCase):
    def test_initializer_is_explicit_main_request_and_reuses_release_verification(self):
        workflow = (Path(__file__).parents[3] / '.github/workflows/play-internal.yml').read_text()
        release = workflow.split('  release:', 1)[1].split('  checked:', 1)[0]
        condition = release.split('    runs-on:', 1)[0]
        self.assertIn("needs.source.outputs.mode == 'initialize'", condition)
        self.assertIn("github.event_name == 'workflow_dispatch'", condition)
        self.assertIn("github.ref == 'refs/heads/main'", condition)
        self.assertIn('mode: ${{ steps.source.outputs.release_mode }}', workflow)
        self.assertLess(release.index('allocate-first'), release.index('lintRelease bundleRelease assembleRelease'))
        self.assertLess(release.index('verify_native.py'), release.index(' initialize --bundle'))
        self.assertIn('PLAY_RELEASE_MODE: ${{ needs.source.outputs.mode }}', release)
        self.assertNotIn('PLAY_CONSOLE_INITIALIZED=true', workflow)
        self.assertNotIn('gh variable set', workflow)

