"""Exercise real HTTP serialization against a local fixture, with no paid API calls."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import sys
import threading
import unittest
import urllib.request

sys.path.insert(0, str(Path(__file__).parents[1]))
import publisher


class HttpFixtureTests(unittest.TestCase):
    def test_authorization_json_upload_and_error_redaction_through_actual_http(self):
        received = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def do_POST(self):
                raw = self.rfile.read(int(self.headers.get('Content-Length', '0')))
                received.append((self.path, self.headers.get('Authorization'), self.headers.get('Content-Type'), raw))
                if self.path.endswith('/blocked'):
                    self.send_response(403)
                    self.end_headers()
                    self.wfile.write(b'private-server-data')
                else:
                    self.send_response(200)
                    self.send_header('Content-Type', 'application/json')
                    self.end_headers()
                    self.wfile.write(b'{"id":"fixture"}')
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        def opener(request, **kwargs):
            # Transport must address this app's official package before fixture rerouting.
            self.assertTrue(request.full_url.startswith(publisher.API))
            self.assertIn('/applications/com.hebi.livetranslate/edits', request.full_url)
            url = f'http://127.0.0.1:{server.server_port}' + request.full_url[len(publisher.API):]
            local = urllib.request.Request(url, data=request.data, headers=dict(request.header_items()), method=request.method)
            return urllib.request.urlopen(local, **kwargs)
        try:
            transport = publisher.HttpTransport('fixture-token', opener)
            self.assertEqual(transport('POST', '/edits', {'test': 1}), {'id': 'fixture'})
            transport('POST', '/edits/fixture/bundles?uploadType=media', b'AAB fixture bytes', 'application/octet-stream')
            with self.assertRaises(publisher.ReleaseError) as error:
                transport('POST', '/edits/blocked', {})
            self.assertNotIn('private-server-data', str(error.exception))
            self.assertEqual(received[0][1], 'Bearer fixture-token')
            self.assertEqual(json.loads(received[0][3]), {'test': 1})
            self.assertTrue(received[1][0].startswith('/upload/'))
            self.assertEqual(received[1][2:], ('application/octet-stream', b'AAB fixture bytes'))
        finally:
            server.shutdown()
            server.server_close()
            worker.join()
