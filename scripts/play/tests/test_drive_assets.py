import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import drive_assets


class DriveAssetsTests(unittest.TestCase):
    def make_zip(self, *, missing=None, extra=None):
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as archive:
            for item in drive_assets.EXPECTED_FILES:
                if item != missing:
                    archive.writestr(item, b"test")
            if extra:
                archive.writestr(extra, b"test")
        return buf.getvalue()

    def test_requires_exact_filename_set(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(ValueError, "names differ"):
                drive_assets.extract(self.make_zip(missing="assets/play-store/ko-KR/icon.png"),
                                    Path(tmp, "out"))
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(ValueError, "names differ"):
                drive_assets.extract(self.make_zip(extra="assets/play-store/ko-KR/unknown.png"),
                                    Path(tmp, "out"))

    def test_rejects_path_traversal(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaisesRegex(ValueError, "names differ|Unsafe"):
                drive_assets.extract(self.make_zip(extra="../other.txt"), Path(tmp, "out"))

    def test_rejects_preexisting_content(self):
        with tempfile.TemporaryDirectory() as tmp:
            dest = Path(tmp)
            (dest / "existing").write_text("test")
            with self.assertRaisesRegex(ValueError, "empty"):
                drive_assets.extract(self.make_zip(), dest)

    def test_download_requires_token(self):
        with self.assertRaisesRegex(ValueError, "token"):
            drive_assets.download("")

    def test_rejects_changed_archive_bytes(self):
        class FakeResponse:
            def __enter__(self): return self
            def __exit__(self, *unused): return None
            def read(self, _): return b"not approved"
        with patch.object(drive_assets, "EXPECTED_ARCHIVE_SHA256", hashlib.sha256(b"approved").hexdigest()):
            with self.assertRaisesRegex(ValueError, "digest mismatch"):
                drive_assets.download("placeholder", opener=lambda *a, **k: FakeResponse())


if __name__ == "__main__":
    unittest.main()
