import hashlib
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
import urllib.error

sys.path.insert(0, str(Path(__file__).parents[1]))
import publisher
from test_initialize import FirstUploadServer

MESSAGE = 'Changes are sent for review automatically. The query parameter changesNotSentForReview must not be set.'


def api_error(status=400, message=MESSAGE):
    raw = io.BytesIO(json.dumps({'error': {'status': 'INVALID_ARGUMENT', 'message': message}}).encode())
    def fail(request, **kwargs):
        raise urllib.error.HTTPError(request.full_url, status, 'private status text', {}, raw)
    try:
        publisher.HttpTransport('private token', fail)('POST', '/edits/1:commit', {})
    except publisher.PlayApiError as error:
        return error
    raise AssertionError('HTTP fixture did not fail')


class CommitFallbackTests(unittest.TestCase):
    def test_exact_known_http400_maps_fixed_reason_without_raw_message(self):
        error = api_error()
        self.assertEqual(error.safe_reason, 'CHANGES_NOT_SENT_FOR_REVIEW_FORBIDDEN')
        self.assertNotIn(MESSAGE, str(error))
        self.assertNotIn('private', str(error))

    def test_unknown_partial_or_non400_messages_are_not_classified(self):
        for status, message in ((400, 'unknown error'), (400, 'prefix ' + MESSAGE), (400, MESSAGE + ' private suffix'), (400, MESSAGE[:-1]), (503, MESSAGE)):
            with self.subTest(status=status, message=message):
                self.assertIsNone(api_error(status, message).safe_reason)

    def test_initialize_retries_only_known_commit_once_in_same_edit_without_reupload_or_revalidate(self):
        server = FirstUploadServer()
        commits = []
        def request(method, path, body=None, content_type=None):
            if method == 'POST' and ':commit' in path:
                commits.append(path)
                if len(commits) == 1:
                    server.calls.append((method, path, body))
                    raise api_error()
            return server(method, path, body, content_type)
        with tempfile.NamedTemporaryFile() as bundle:
            bundle.write(b'first AAB')
            bundle.flush()
            result = publisher.Publisher(request).initialize(bundle.name)
        self.assertEqual(len(commits), 2)
        self.assertEqual(commits[0].split('?')[0], commits[1].split('?')[0])
        self.assertIn('changesNotSentForReview=true', commits[0])
        self.assertNotIn('changesNotSentForReview', commits[1])
        self.assertIn('changesInReviewBehavior=ERROR_IF_IN_REVIEW', commits[1])
        self.assertEqual(sum('uploadType' in path for _, path, _ in server.calls), 1)
        self.assertEqual(sum(':validate' in path for _, path, _ in server.calls), 1)
        self.assertEqual(result['reviewHoldParameterOmitted'], True)
        self.assertEqual(result['reason'], 'CHANGES_NOT_SENT_FOR_REVIEW_FORBIDDEN')
        self.assertEqual(result['uploadedBundleSHA256'], hashlib.sha256(b'first AAB').hexdigest())
        self.assertEqual(result['availability'], 'draft_not_published')

    def test_generic400_503_and_transport_failure_never_retry(self):
        for error in (api_error(400, 'unknown'), api_error(503), publisher.ReleaseError('Play API request failed')):
            calls = []
            def request(method, path, body=None, content_type=None):
                calls.append(path)
                if ':commit' in path:
                    raise error
                return {}
            with self.subTest(error=error), self.assertRaises(publisher.ReleaseError):
                publisher.Publisher(request).commit('/edits/1', True, True)
            self.assertEqual(sum(':commit' in path for path in calls), 1)
            self.assertFalse(getattr(error, 'review_hold_parameter_omitted', False))

    def test_second_failure_stops_after_two_commit_attempts_and_records_omission(self):
        calls = []
        def request(method, path, body=None, content_type=None):
            calls.append(path)
            if ':commit' in path:
                raise api_error()
            return {}
        with self.assertRaises(publisher.ReleaseError) as error:
            publisher.Publisher(request).commit('/edits/1', True, True)
        self.assertEqual(sum(':commit' in path for path in calls), 2)
        self.assertTrue(error.exception.review_hold_parameter_omitted)

    def test_normal_commit_does_not_retry_even_known_error(self):
        calls = []
        def request(method, path, body=None, content_type=None):
            calls.append(path)
            if ':commit' in path:
                raise api_error()
            return {}
        with self.assertRaises(publisher.ReleaseError):
            publisher.Publisher(request).commit('/edits/1', True)
        self.assertEqual(sum(':commit' in path for path in calls), 1)

    def test_successful_first_commit_does_not_claim_parameter_omission(self):
        with tempfile.NamedTemporaryFile() as bundle:
            result = publisher.Publisher(FirstUploadServer()).initialize(bundle.name)
        self.assertNotIn('reviewHoldParameterOmitted', result)

