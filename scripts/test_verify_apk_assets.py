from hashlib import sha256
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

import prepare_speech_support as speech
import verify_apk_assets as audit


class ApkAssetsTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.apk = Path(directory.name) / "fixture.apk"
        self.files = {
            "assets/speech/silero_vad.onnx": b"vad",
            "assets/language-id/tiny/encoder.onnx": b"encoder",
            "assets/language-id/tiny/decoder.onnx": b"decoder",
            "lib/arm64-v8a/liblivecaption_translation.so": b"runtime",
        }
        model = speech.ModelFile("silero_vad.onnx", 3, sha256(b"vad").hexdigest(), "fixture")
        self.addCleanup(patch.stopall)
        patch.object(audit.speech, "MODEL", model).start()
        patch.object(audit.language_id, "MODEL_NAME", "tiny").start()
        patch.object(audit.language_id, "MODEL_MANIFEST", {
            "encoder.onnx": (7, sha256(b"encoder").hexdigest()),
            "decoder.onnx": (7, sha256(b"decoder").hexdigest()),
        }).start()

    def verify(self):
        with ZipFile(self.apk, "w") as output:
            for name, contents in self.files.items():
                output.writestr(name, contents)
        audit.verify_apk(self.apk)

    def test_expected_support_models_and_native_runtime_are_allowed(self):
        self.verify()

    def test_large_and_legacy_models_are_rejected(self):
        for name in ("assets/models/legacy.bin", "assets/translation/model.gguf", "assets/asr/encoder.onnx"):
            with self.subTest(name=name):
                self.files[name] = b"unexpected"
                with self.assertRaisesRegex(RuntimeError, "bundled"):
                    self.verify()
                del self.files[name]

    def test_all_bundled_support_hashes_are_checked(self):
        for name in tuple(self.files):
            if not name.endswith(".onnx"):
                continue
            with self.subTest(name=name):
                original = self.files[name]
                self.files[name] = b"corrupt"
                with self.assertRaisesRegex(RuntimeError, "integrity"):
                    self.verify()
                self.files[name] = original

    def test_missing_translation_runtime_is_rejected(self):
        del self.files["lib/arm64-v8a/liblivecaption_translation.so"]
        with self.assertRaisesRegex(RuntimeError, "runtime"):
            self.verify()


if __name__ == "__main__":
    unittest.main()
