import hashlib
import io
import pathlib
import tarfile
import tempfile
import unittest
from unittest import mock

import prepare_native_translation as native


class NativeSourcePreparationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = pathlib.Path(self.temporary.name)
        self.archive = self.root / "source.tar.gz"
        self.destination = self.root / "prepared/llama.cpp"

    def tearDown(self):
        self.temporary.cleanup()

    def archive_with(self, name=None, link=False):
        with tarfile.open(self.archive, "w:gz") as archive:
            entry = tarfile.TarInfo(name or f"llama.cpp-{native.COMMIT}/include/llama.h")
            if link:
                entry.type = tarfile.SYMTYPE
                entry.linkname = "/etc/passwd"
                archive.addfile(entry)
            else:
                data = b"pinned source fixture"
                entry.size = len(data)
                archive.addfile(entry, io.BytesIO(data))
        return hashlib.sha256(self.archive.read_bytes()).hexdigest()

    def test_valid_archive_and_unchanged_prepared_cache(self):
        digest = self.archive_with()
        with mock.patch.object(native, "ARCHIVE_SHA256", digest):
            native.prepare(self.archive, self.destination)
            native.prepare(None, self.destination)
        self.assertEqual(b"pinned source fixture", (self.destination / "include/llama.h").read_bytes())

    def test_wrong_hash_does_not_publish_source(self):
        self.archive_with()
        with self.assertRaisesRegex(RuntimeError, "checksum"):
            native.prepare(self.archive, self.destination)
        self.assertFalse(self.destination.exists())

    def test_modified_cached_source_is_rejected(self):
        digest = self.archive_with()
        with mock.patch.object(native, "ARCHIVE_SHA256", digest):
            native.prepare(self.archive, self.destination)
            (self.destination / "include/llama.h").write_text("changed", encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "not the pinned"):
                native.prepare(None, self.destination)

    def test_traversal_is_rejected(self):
        digest = self.archive_with(f"llama.cpp-{native.COMMIT}/../../outside")
        with mock.patch.object(native, "ARCHIVE_SHA256", digest):
            with self.assertRaisesRegex(RuntimeError, "Unexpected path"):
                native.prepare(self.archive, self.destination)
        self.assertFalse(self.destination.exists())

    def test_symbolic_link_is_rejected(self):
        digest = self.archive_with(link=True)
        with mock.patch.object(native, "ARCHIVE_SHA256", digest):
            with self.assertRaisesRegex(RuntimeError, "Unexpected link"):
                native.prepare(self.archive, self.destination)
        self.assertFalse(self.destination.exists())


if __name__ == "__main__":
    unittest.main()
