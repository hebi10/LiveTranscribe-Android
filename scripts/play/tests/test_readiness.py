import contextlib
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


class ReadinessTests(unittest.TestCase):
    def cli(self, server, output, require_binary=True):
        args = ['publisher.py', 'inspect'] + (['--require-binary'] if require_binary else [])
        with mock.patch.dict(os.environ, {'PLAY_ACCESS_TOKEN': 'test-only-token', 'GITHUB_OUTPUT': str(output), 'PLAY_CONSOLE_INITIALIZED': ''}), mock.patch.object(publisher, 'HttpTransport', return_value=server), mock.patch('sys.argv', args), contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return publisher.main()

    def test_empty_and_track_only_inventory_do_not_emit_initialization_output(self):
        for server in (PlayServer(), PlayServer(tracks=[track('internal', 14, 'draft')])):
            with self.subTest(server=server), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / 'output'
                self.assertEqual(self.cli(server, output), 1)
                self.assertFalse(output.exists())
                self.assertEqual(server.calls[-1][0], 'DELETE')
                self.assertFalse(server.committed)

    def test_registered_bundle_or_apk_emits_true_only_after_cleanup(self):
        for server in (PlayServer(bundles=[14]), PlayServer(apks=[14])):
            with self.subTest(server=server), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / 'output'
                def request(method, path, body=None, content_type=None):
                    self.assertFalse(output.exists())
                    return server(method, path, body, content_type)
                self.assertEqual(self.cli(request, output), 0)
                self.assertEqual(output.read_text(), 'initialized=true\n')
                self.assertEqual(server.calls[-1][0], 'DELETE')
                self.assertFalse(server.committed)
                self.assertFalse(any('uploadType' in path or method == 'PUT' for method, path, _ in server.calls))

    def test_read_or_cleanup_failure_never_emits_initialization_output(self):
        for failed_method in ('GET', 'DELETE'):
            server = PlayServer(bundles=[14])
            def request(method, path, body=None, content_type=None):
                if method == failed_method:
                    raise publisher.PlayApiError(method, 403)
                return server(method, path, body, content_type)
            with self.subTest(failed_method=failed_method), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / 'output'
                self.assertEqual(self.cli(request, output), 1)
                self.assertFalse(output.exists())

    def test_plain_inspect_keeps_existing_behavior_and_does_not_emit_guard_output(self):
        for server in (PlayServer(), PlayServer(bundles=[14])):
            with self.subTest(server=server), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / 'output'
                self.assertEqual(self.cli(server, output, require_binary=False), 0)
                self.assertFalse(output.exists())

    def test_mutating_commands_still_require_explicit_verified_runtime_input(self):
        for args in (['allocate'], ['deploy', '--bundle', 'unused.aab', '--version-code', '15'], ['promote', '--version-code', '14']):
            with self.subTest(args=args), mock.patch.dict(os.environ, {'PLAY_CONSOLE_INITIALIZED': ''}), mock.patch.object(publisher, 'HttpTransport') as transport, mock.patch('sys.argv', ['publisher.py'] + args), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(publisher.main(), 1)
                transport.assert_not_called()


class ReadinessWorkflowTests(unittest.TestCase):
    def test_authenticated_binary_readiness_feeds_all_three_mutating_workflows(self):
        root = Path(__file__).parents[3]
        for name, mutate in (('play-internal.yml', ' allocate\n'), ('play-listing.yml', 'listing.py apply'), ('play-promote.yml', ' promote --version-code')):
            with self.subTest(workflow=name):
                workflow = (root / '.github/workflows' / name).read_text()
                self.assertNotIn('${{ vars.PLAY_CONSOLE_INITIALIZED }}', workflow)
                self.assertIn('id: readiness', workflow)
                self.assertIn('inspect --require-binary', workflow)
                self.assertIn('PLAY_CONSOLE_INITIALIZED: ${{ steps.readiness.outputs.initialized }}', workflow)
                self.assertLess(workflow.index('google-github-actions/auth@v3'), workflow.index('inspect --require-binary'))
                self.assertLess(workflow.index('inspect --require-binary'), workflow.index(mutate))
                self.assertNotIn('gh variable set', workflow)
                self.assertIn('group: play-com-hebi-livetranslate', workflow)
        internal = (root / '.github/workflows/play-internal.yml').read_text()
        readiness = internal.split('      - name: Verify registered binary before normal API deployment', 1)[1].split('      - name:', 1)[0]
        self.assertIn("if: needs.source.outputs.mode == 'deploy'", readiness)
        self.assertNotIn("mode == 'initialize'", readiness)

