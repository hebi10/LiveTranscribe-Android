import pathlib
import sys
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).parents[1]))
import publisher
import images_only


def item(data):
    import hashlib
    return {"data": data, "sha256": hashlib.sha256(data).hexdigest()}


class FakePlay:
    def __init__(self, deny_review_hold=True, unknown_error=False):
        self.calls = []
        self.edit_count = 0
        self.uploaded = {key: [] for key in images_only.CATEGORIES}
        self.committed = False
        self.deny_review_hold = deny_review_hold
        self.unknown_error = unknown_error

    def __call__(self, method, path, body=None, content_type=None):
        self.calls.append((method, path))
        if method == "POST" and path == "/edits":
            self.edit_count += 1
            return {"id": str(self.edit_count)}
        if method == "DELETE" and path.endswith(tuple("/" + key for key in images_only.CATEGORIES)):
            self.uploaded[path.rsplit("/", 1)[1]] = []
            return {}
        if method == "DELETE" and path.startswith("/edits/"):
            return {}
        if method == "POST" and path.endswith(":validate"):
            return {}
        if method == "POST" and path.endswith("uploadType=media"):
            kind = path.split("/listings/ko-KR/")[1].split("?")[0]
            import hashlib
            self.uploaded[kind].append({"sha256": hashlib.sha256(body).hexdigest()})
            return {"image": {"id": str(len(self.uploaded[kind]))}}
        if method == "POST" and ":commit" in path:
            if "changesNotSentForReview=true" in path and self.deny_review_hold:
                raise publisher.PlayApiError("POST", 400, "INVALID_ARGUMENT",
                                             publisher.REVIEW_HOLD_FORBIDDEN_REASON if not self.unknown_error else None)
            self.committed = True
            return {}
        if method == "GET" and "/listings/ko-KR/" in path:
            kind = path.rsplit("/", 1)[1]
            return {"images": self.uploaded[kind] if self.committed else []}
        raise AssertionError((method, path))


class ImageOnlyCommitTests(unittest.TestCase):
    def setUp(self):
        self.images = {
            "icon": [item(b"icon")],
            "featureGraphic": [item(b"feature")],
            "phoneScreenshots": [item(b"screen1"), item(b"screen2")],
        }

    def test_known_review_hold_rejection_retries_once_and_reads_back(self):
        server = FakePlay()
        result = images_only.apply_images(publisher.Publisher(server), self.images)
        commits = [path for method, path in server.calls if method == "POST" and ":commit" in path]
        self.assertEqual(len(commits), 2)
        self.assertIn("changesInReviewBehavior=ERROR_IF_IN_REVIEW", commits[1])
        self.assertNotIn("changesNotSentForReview", commits[1])
        self.assertEqual(result["status"], "image_edit_committed_and_verified")
        self.assertTrue(result["reviewHoldParameterOmitted"])
        self.assertFalse(result["changesNotSentForReview"])

    def test_generic_400_never_retries_or_claims_commit(self):
        server = FakePlay(unknown_error=True)
        with self.assertRaisesRegex(publisher.ReleaseError, "edit commit"):
            images_only.apply_images(publisher.Publisher(server), self.images)
        commits = [path for method, path in server.calls if method == "POST" and ":commit" in path]
        self.assertEqual(len(commits), 1)
        self.assertFalse(server.committed)

    def test_supported_review_hold_needs_only_one_commit(self):
        server = FakePlay(deny_review_hold=False)
        result = images_only.apply_images(publisher.Publisher(server), self.images)
        self.assertTrue(result["changesNotSentForReview"])
        self.assertFalse(result["reviewHoldParameterOmitted"])
        commits = [path for method, path in server.calls if method == "POST" and ":commit" in path]
        self.assertEqual(len(commits), 1)


if __name__ == "__main__":
    unittest.main()
