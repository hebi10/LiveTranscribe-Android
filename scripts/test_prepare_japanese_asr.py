import io
import tempfile
import unittest
from hashlib import sha256
from pathlib import Path
from unittest.mock import patch

import prepare_japanese_asr as preparation


class JapaneseAsrPreparationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.destination = Path(self.temporary.name) / "reazonspeech-k2-v2"
        self.destination.mkdir()
        self.payload = b"verified Japanese recognizer model"
        self.model = preparation.ModelFile(
            "encoder-epoch-99-avg-1.int8.onnx", "encoder.int8.onnx",
            len(self.payload), sha256(self.payload).hexdigest(),
        )
        self.target = self.destination / self.model.filename
        destination_patch = patch.object(preparation, "DEST", self.destination)
        destination_patch.start()
        self.addCleanup(destination_patch.stop)

    def assert_no_temporary_files(self):
        self.assertEqual([self.target], list(self.destination.iterdir()))

    def test_verified_existing_file_is_reused_without_network(self):
        self.target.write_bytes(self.payload)
        with patch.object(preparation, "urlopen") as download:
            preparation.prepare(self.model)

        download.assert_not_called()
        self.assertEqual(self.payload, self.target.read_bytes())
        self.assert_no_temporary_files()

    def test_same_size_corrupted_existing_file_is_downloaded_and_replaced(self):
        previous = b"x" * len(self.payload)
        self.target.write_bytes(previous)
        test = self

        class DownloadKeepingPreviousVisible(io.BytesIO):
            def read(self, size=-1):
                test.assertEqual(previous, test.target.read_bytes())
                return super().read(size)

        with patch.object(preparation, "urlopen", return_value=DownloadKeepingPreviousVisible(self.payload)) as download:
            preparation.prepare(self.model)

        download.assert_called_once_with(self.model.source_url, timeout=180)
        self.assertEqual(self.payload, self.target.read_bytes())
        self.assert_no_temporary_files()

    def test_hash_mismatch_does_not_overwrite_existing_file(self):
        previous = b"previous local model"
        self.target.write_bytes(previous)
        with patch.object(preparation, "urlopen", return_value=io.BytesIO(b"x" * len(self.payload))):
            with self.assertRaisesRegex(RuntimeError, "SHA-256 mismatch"):
                preparation.prepare(self.model)

        self.assertEqual(previous, self.target.read_bytes())
        self.assert_no_temporary_files()

    def test_wrong_download_size_does_not_create_a_model(self):
        with patch.object(preparation, "urlopen", return_value=io.BytesIO(self.payload[:-1])):
            with self.assertRaisesRegex(RuntimeError, "size mismatch"):
                preparation.prepare(self.model)

        self.assertEqual([], list(self.destination.iterdir()))

    def test_interrupted_download_cleans_temporary_file_and_preserves_existing(self):
        previous = b"previous local model"
        self.target.write_bytes(previous)

        class InterruptedDownload(io.BytesIO):
            def read(self, size=-1):
                if self.tell() > 0:
                    raise OSError("download interrupted")
                return super().read(2)

        with patch.object(preparation, "urlopen", return_value=InterruptedDownload(self.payload)):
            with self.assertRaisesRegex(OSError, "download interrupted"):
                preparation.prepare(self.model)

        self.assertEqual(previous, self.target.read_bytes())
        self.assert_no_temporary_files()

    def test_download_is_streamed_in_bounded_reads(self):
        payload = b"model" * (500_000)
        model = preparation.ModelFile(
            self.model.remote_filename, self.model.filename, len(payload), sha256(payload).hexdigest(),
        )
        test = self

        class BoundedDownload(io.BytesIO):
            def read(self, size=-1):
                test.assertGreater(size, 0, "Model downloads must not read the whole response at once")
                test.assertLessEqual(size, 1024 * 1024)
                return super().read(size)

        with patch.object(preparation, "urlopen", return_value=BoundedDownload(payload)):
            preparation.prepare(model)

        self.assertTrue(self.target.is_file(), "A verified download must be installed")
        self.assertEqual(payload, self.target.read_bytes())
        self.assert_no_temporary_files()

    def test_separately_verified_vad_uses_its_official_release_url(self):
        source_url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
        model = preparation.ModelFile(
            "silero_vad.onnx", "silero_vad.onnx", len(self.payload), sha256(self.payload).hexdigest(),
            source_url_override=source_url,
        )
        with patch.object(preparation, "urlopen", return_value=io.BytesIO(self.payload)) as download:
            preparation.prepare(model)

        download.assert_called_once_with(source_url, timeout=180)
        self.assertEqual(self.payload, (self.destination / "silero_vad.onnx").read_bytes())

    def test_bundle_is_pinned_to_verified_official_artifacts(self):
        self.assertEqual("291488c8151be24d7da4bf7af26e533fad96e407", preparation.REVISION)
        self.assertEqual(
            [
                ("encoder-epoch-99-avg-1.int8.onnx", "encoder.int8.onnx", 154670139,
                 "2c7bd08a8a99f9ddd0d9e458456577b1f6279214e51426f114f9eced44c54e1d"),
                ("decoder-epoch-99-avg-1.int8.onnx", "decoder.int8.onnx", 2959337,
                 "8f0bff94d38797b03b762634ed03211a8e303d06cc4603cdd0cf4199d6eb1485"),
                ("joiner-epoch-99-avg-1.int8.onnx", "joiner.int8.onnx", 2696970,
                 "49cc7ea1d3d35a40a27442db5e89996da64bf0e683a903dce76e99e57a12e4de"),
                ("tokens.txt", "tokens.txt", 45754,
                 "2c3ac659818a48a0c04010e0593bbc4d7c8a24a054340b01131499c05fd52def"),
                ("silero_vad.onnx", "silero_vad.onnx", 643854,
                 "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6"),
            ],
            [(model.remote_filename, model.filename, model.size_bytes, model.sha256)
             for model in preparation.MODEL_FILES],
        )
        for model in preparation.MODEL_FILES:
            if model.filename == "silero_vad.onnx":
                expected_url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
            else:
                expected_url = "https://huggingface.co/reazon-research/reazonspeech-k2-v2/resolve/" \
                    + preparation.REVISION + "/" + model.remote_filename
            self.assertEqual(expected_url, model.source_url)


if __name__ == "__main__":
    unittest.main()
