import importlib.util
import io
import tempfile
import unittest
from hashlib import sha256
from pathlib import Path
from unittest.mock import patch

if importlib.util.find_spec("prepare_speech_support"):
    import prepare_speech_support as preparation
else:
    preparation = None


class SpeechSupportPreparationTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(preparation, "Silero-only speech support preparation is missing")
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.destination = Path(self.temporary.name) / "speech"
        self.destination.mkdir()
        self.payload = b"verified voice activity detection model"
        self.model = preparation.ModelFile(
            "silero_vad.onnx", len(self.payload), sha256(self.payload).hexdigest(),
            "https://example.invalid/silero_vad.onnx",
        )
        self.target = self.destination / self.model.filename
        for name, value in (("DEST", self.destination), ("MODEL", self.model)):
            replacement = patch.object(preparation, name, value)
            replacement.start()
            self.addCleanup(replacement.stop)

    def assert_only_target(self):
        self.assertEqual([self.target], list(self.destination.iterdir()))

    def test_valid_existing_file_is_reused_without_network(self):
        self.target.write_bytes(self.payload)
        with patch.object(preparation, "urlopen", side_effect=AssertionError("unexpected network")):
            preparation.prepare()
        self.assertEqual(self.payload, self.target.read_bytes())
        self.assert_only_target()

    def test_corrupt_existing_file_is_replaced_only_after_verified_download(self):
        previous = b"x" * len(self.payload)
        self.target.write_bytes(previous)
        test = self

        class KeepPreviousVisible(io.BytesIO):
            def read(self, size=-1):
                test.assertEqual(previous, test.target.read_bytes())
                return super().read(size)

        with patch.object(preparation, "urlopen", return_value=KeepPreviousVisible(self.payload)):
            preparation.prepare()
        self.assertEqual(self.payload, self.target.read_bytes())
        self.assert_only_target()

    def test_corrupt_download_preserves_previous_copy(self):
        previous = b"previous verified model revision"
        self.target.write_bytes(previous)
        with patch.object(preparation, "urlopen", return_value=io.BytesIO(b"x" * len(self.payload))):
            with self.assertRaisesRegex(RuntimeError, "SHA-256 mismatch"):
                preparation.prepare()
        self.assertEqual(previous, self.target.read_bytes())
        self.assert_only_target()

    def test_oversize_download_is_rejected_without_overwriting_previous_copy(self):
        previous = b"previous verified model revision"
        self.target.write_bytes(previous)
        with patch.object(preparation, "urlopen", return_value=io.BytesIO(self.payload + b"extra")):
            with self.assertRaisesRegex(RuntimeError, "size mismatch"):
                preparation.prepare()
        self.assertEqual(previous, self.target.read_bytes())
        self.assert_only_target()

    def test_truncated_download_leaves_no_published_or_temporary_file(self):
        with patch.object(preparation, "urlopen", return_value=io.BytesIO(self.payload[:-1])):
            with self.assertRaisesRegex(RuntimeError, "size mismatch"):
                preparation.prepare()
        self.assertEqual([], list(self.destination.iterdir()))

    def test_interrupted_download_preserves_previous_copy_and_cleans_staging(self):
        previous = b"previous verified model revision"
        self.target.write_bytes(previous)

        class Interrupted(io.BytesIO):
            def read(self, size=-1):
                if self.tell():
                    raise OSError("download interrupted")
                return super().read(2)

        with patch.object(preparation, "urlopen", return_value=Interrupted(self.payload)):
            with self.assertRaisesRegex(OSError, "download interrupted"):
                preparation.prepare()
        self.assertEqual(previous, self.target.read_bytes())
        self.assert_only_target()

    def test_download_uses_bounded_reads(self):
        test = self

        class Bounded(io.BytesIO):
            def read(self, size=-1):
                test.assertGreater(size, 0)
                test.assertLessEqual(size, 1024 * 1024)
                return super().read(min(size, 3))

        with patch.object(preparation, "urlopen", return_value=Bounded(self.payload)):
            preparation.prepare()
        self.assertEqual(self.payload, self.target.read_bytes())
        self.assert_only_target()

    def test_verify_only_accepts_valid_file_without_network(self):
        self.target.write_bytes(self.payload)
        with patch.object(preparation, "urlopen", side_effect=AssertionError("unexpected network")):
            preparation.main(["--verify-only"])
        self.assertEqual(self.payload, self.target.read_bytes())

    def test_verify_only_rejects_missing_or_corrupt_file_without_network(self):
        with patch.object(preparation, "urlopen", side_effect=AssertionError("unexpected network")):
            with self.assertRaisesRegex(SystemExit, "integrity verification failed"):
                preparation.main(["--verify-only"])
            self.target.write_bytes(b"x" * len(self.payload))
            with self.assertRaisesRegex(SystemExit, "integrity verification failed"):
                preparation.main(["--verify-only"])
        self.assertEqual(b"x" * len(self.payload), self.target.read_bytes())
        self.assert_only_target()


class SpeechSupportManifestTest(unittest.TestCase):
    def test_only_pinned_silero_vad_is_bundled_under_speech(self):
        self.assertIsNotNone(preparation, "Silero-only speech support preparation is missing")
        model = preparation.MODEL
        self.assertEqual("silero_vad.onnx", model.filename)
        self.assertEqual(643854, model.size_bytes)
        self.assertEqual("9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6", model.sha256)
        self.assertEqual("https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx", model.source_url)
        self.assertEqual(preparation.ROOT / "app/src/main/assets/speech", preparation.DEST)


if __name__ == "__main__":
    unittest.main()
