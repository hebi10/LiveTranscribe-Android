import io
import tarfile
import tempfile
import unittest
from hashlib import sha256
from pathlib import Path
from unittest.mock import patch

import prepare_language_id as preparation


class LanguageIdPreparationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.destination = self.root / "models"
        self.destination.mkdir()
        self.work = self.root / "work"
        self.work.mkdir()
        self.payloads = {name: bytes([index + 1]) * 2048
                         for index, name in enumerate(preparation.MODEL_FILES)}
        manifest = {name: (len(data), sha256(data).hexdigest())
                    for name, data in self.payloads.items()}
        for key, value in (("MODEL_DIR", self.destination), ("MODEL_MANIFEST", manifest)):
            change = patch.object(preparation, key, value, create=True)
            change.start()
            self.addCleanup(change.stop)
        # Any unexpected network access fails locally instead of using the internet.
        network = patch.object(preparation, "urlopen", side_effect=AssertionError("unexpected network"))
        self.network = network.start()
        self.addCleanup(network.stop)

    def archive(self, payloads=None):
        output = io.BytesIO()
        with tarfile.open(fileobj=output, mode="w:bz2") as bundle:
            for name, payload in (self.payloads if payloads is None else payloads).items():
                member = tarfile.TarInfo(f"{preparation.MODEL_NAME}/{name}")
                member.size = len(payload)
                bundle.addfile(member, io.BytesIO(payload))
        return output.getvalue()

    def install(self, payloads):
        for name, payload in payloads.items():
            (self.destination / name).write_bytes(payload)

    def assert_models(self, payloads):
        self.assertEqual(set(payloads), {p.name for p in self.destination.iterdir()})
        for name, payload in payloads.items():
            self.assertEqual(payload, (self.destination / name).read_bytes())

    def test_arbitrary_large_cached_files_are_replaced_with_verified_models(self):
        self.install({name: b"x" * 2048 for name in self.payloads})
        self.network.side_effect = None
        self.network.return_value = io.BytesIO(self.archive())

        preparation.ensure_models(self.work)

        self.assert_models(self.payloads)

    def test_truncated_cached_files_over_one_kilobyte_are_repaired(self):
        self.install({name: data[:-1] for name, data in self.payloads.items()})
        self.network.side_effect = None
        self.network.return_value = io.BytesIO(self.archive())

        preparation.ensure_models(self.work)

        self.assert_models(self.payloads)

    def test_verified_cached_models_are_reused_without_network(self):
        self.install(self.payloads)
        preparation.ensure_models(self.work)
        self.assert_models(self.payloads)

    def test_wrong_hash_in_download_cannot_replace_any_existing_model(self):
        previous = {name: b"previous model" for name in self.payloads}
        self.install(previous)
        corrupted = dict(self.payloads)
        corrupted[list(corrupted)[-1]] = b"x" * 2048
        self.network.side_effect = None
        self.network.return_value = io.BytesIO(self.archive(corrupted))

        with self.assertRaisesRegex(RuntimeError, "SHA-256 mismatch"):
            preparation.ensure_models(self.work)

        self.assert_models(previous)

    def test_missing_second_member_cannot_publish_the_first_model(self):
        previous = {name: b"previous model" for name in self.payloads}
        self.install(previous)
        first = next(iter(self.payloads))
        self.network.side_effect = None
        self.network.return_value = io.BytesIO(self.archive({first: self.payloads[first]}))

        with self.assertRaises(RuntimeError):
            preparation.ensure_models(self.work)

        self.assert_models(previous)

    def test_wrong_member_size_cannot_replace_existing_models(self):
        previous = {name: b"previous model" for name in self.payloads}
        self.install(previous)
        self.network.side_effect = None
        self.network.return_value = io.BytesIO(self.archive({name: data[:-1] for name, data in self.payloads.items()}))

        with self.assertRaisesRegex(RuntimeError, "size mismatch"):
            preparation.ensure_models(self.work)

        self.assert_models(previous)

    def test_interrupted_download_preserves_cache_and_next_attempt_recovers(self):
        previous = {name: b"previous model" for name in self.payloads}
        self.install(previous)

        class Interrupted(io.BytesIO):
            def read(self, size=-1):
                if self.tell():
                    raise OSError("interrupted")
                return super().read(20)

        self.network.side_effect = None
        self.network.return_value = Interrupted(self.archive())
        with self.assertRaisesRegex(OSError, "interrupted"):
            preparation.ensure_models(self.work)
        self.assert_models(previous)
        self.network.return_value = io.BytesIO(self.archive())
        preparation.ensure_models(self.work)
        self.assert_models(self.payloads)

    def test_previous_files_stay_visible_until_whole_download_is_verified(self):
        previous = {name: b"previous model" for name in self.payloads}
        self.install(previous)
        test = self

        class Observed(io.BytesIO):
            def read(self, size=-1):
                test.assert_models(previous)
                return super().read(size)

        self.network.side_effect = None
        self.network.return_value = Observed(self.archive())
        preparation.ensure_models(self.work)
        self.assert_models(self.payloads)


class AarPreparationTest(unittest.TestCase):
    def test_bad_download_cannot_replace_the_cached_aar(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            target = root / "runtime.aar"
            target.write_bytes(b"previous runtime")
            with patch.object(preparation, "AAR_PATH", target), \
                    patch.object(preparation, "urlopen", return_value=io.BytesIO(b"bad runtime")):
                with self.assertRaisesRegex(RuntimeError, "SHA-256 mismatch"):
                    preparation.ensure_aar(root)
            self.assertEqual(b"previous runtime", target.read_bytes())


if __name__ == "__main__":
    unittest.main()
