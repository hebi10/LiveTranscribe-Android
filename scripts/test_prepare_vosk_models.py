import io
import tempfile
import unittest
from hashlib import sha256
from pathlib import Path
from unittest.mock import patch
from zipfile import ZipFile

import prepare_vosk_models as preparation


class VoskPreparationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.name = preparation.MODELS[0]
        self.destination = self.root / self.name
        self.destination.mkdir()
        self.payloads = {"conf/model.conf": b"correct config", "am/final.mdl": b"correct acoustic model"}
        self.archive_data = self.archive(self.payloads)
        manifest = {name: (len(data), sha256(data).hexdigest()) for name, data in self.payloads.items()}
        for key, value in (("DEST", self.root), ("MODEL_MANIFEST", manifest),
                           ("ARCHIVE_SHA256", sha256(self.archive_data).hexdigest()),
                           ("ARCHIVE_SIZE_BYTES", len(self.archive_data))):
            change = patch.object(preparation, key, value, create=True)
            change.start()
            self.addCleanup(change.stop)
        network = patch.object(preparation, "urlopen", side_effect=AssertionError("unexpected network"))
        self.network = network.start()
        self.addCleanup(network.stop)

    def archive(self, payloads):
        output = io.BytesIO()
        with ZipFile(output, "w") as bundle:
            for name, payload in payloads.items():
                bundle.writestr(f"{self.name}/{name}", payload)
        return output.getvalue()

    def install(self, payloads):
        for name, payload in payloads.items():
            target = self.destination / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(payload)

    def assert_models(self, payloads):
        actual = {str(p.relative_to(self.destination)) for p in self.destination.rglob("*") if p.is_file()}
        self.assertEqual(set(payloads), actual)
        self.assertEqual([self.destination], list(self.root.iterdir()), "staging files must be cleaned up")
        for name, payload in payloads.items():
            self.assertEqual(payload, (self.destination / name).read_bytes())

    def use_archive(self, data=None, trust_archive=False):
        data = self.archive_data if data is None else data
        self.network.side_effect = None
        self.network.return_value = io.BytesIO(data)
        if trust_archive:
            preparation.ARCHIVE_SHA256 = sha256(data).hexdigest()
            preparation.ARCHIVE_SIZE_BYTES = len(data)

    def test_config_only_cache_is_repaired(self):
        self.install({"conf/model.conf": self.payloads["conf/model.conf"]})
        self.use_archive()
        preparation.prepare(self.name)
        self.assert_models(self.payloads)

    def test_same_size_corrupt_acoustic_model_is_repaired(self):
        previous = dict(self.payloads)
        previous["am/final.mdl"] = b"x" * len(previous["am/final.mdl"])
        self.install(previous)
        self.use_archive()
        preparation.prepare(self.name)
        self.assert_models(self.payloads)

    def test_verified_cache_is_reused_without_network(self):
        self.install(self.payloads)
        preparation.prepare(self.name)
        self.assert_models(self.payloads)

    def test_bad_archive_checksum_cannot_publish_files(self):
        self.use_archive(self.archive({"conf/model.conf": b"attacker model"}))
        with self.assertRaisesRegex(RuntimeError, "(size|SHA-256) mismatch"):
            preparation.prepare(self.name)
        self.assert_models({})

    def test_missing_required_file_cannot_publish_partial_bundle(self):
        self.use_archive(self.archive({"conf/model.conf": self.payloads["conf/model.conf"]}), trust_archive=True)
        with self.assertRaisesRegex(RuntimeError, "Missing"):
            preparation.prepare(self.name)
        self.assert_models({})

    def test_bad_member_hash_cannot_overwrite_existing_cache(self):
        previous = {"conf/model.conf": b"previous config"}
        self.install(previous)
        corrupt = dict(self.payloads)
        corrupt["am/final.mdl"] = b"x" * len(corrupt["am/final.mdl"])
        self.use_archive(self.archive(corrupt), trust_archive=True)
        with self.assertRaisesRegex(RuntimeError, "SHA-256 mismatch"):
            preparation.prepare(self.name)
        self.assert_models(previous)

    def test_interrupted_download_preserves_cache_then_recovers(self):
        previous = {"conf/model.conf": b"previous config"}
        self.install(previous)

        class Interrupted(io.BytesIO):
            def read(self, size=-1):
                if self.tell():
                    raise OSError("interrupted")
                return super().read(20)

        self.network.side_effect = None
        self.network.return_value = Interrupted(self.archive_data)
        with self.assertRaisesRegex(OSError, "interrupted"):
            preparation.prepare(self.name)
        self.assert_models(previous)
        self.use_archive()
        preparation.prepare(self.name)
        self.assert_models(self.payloads)

    def test_parent_traversal_is_rejected_without_writing_outside_model(self):
        self.use_archive(self.archive({"../escape": b"bad"}), trust_archive=True)
        with self.assertRaises(ValueError):
            preparation.prepare(self.name)
        self.assert_models({})


if __name__ == "__main__":
    unittest.main()
