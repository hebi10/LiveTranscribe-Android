import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location(
    "qa_real_01_a_preflight", Path(__file__).with_name("qa_real_01_a_preflight.py"))
preflight = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(preflight)


class DevicePreflightTests(unittest.TestCase):
    def test_requires_exactly_one_authorized_device(self):
        self.assertEqual("ABC", preflight.pick_device("List of devices attached\nABC\tdevice\n"))
        for sample in (
            "List of devices attached\n",
            "List of devices attached\nABC unauthorized\n",
            "List of devices attached\nABC device\nXYZ device\n",
        ):
            with self.assertRaises(ValueError):
                preflight.pick_device(sample)

    def test_available_storage_parses_named_column(self):
        output = ("Filesystem 1K-blocks Used Available Use% Mounted on\n"
                  "/dev/block/dm-0 1000 100 900 10% /data\n")
        self.assertEqual(900, preflight.parse_storage_kb(output))
        self.assertIsNone(preflight.parse_storage_kb("unparseable"))

    def test_arm64_candidate_is_never_automatically_certified(self):
        props = {"ro.build.version.sdk": "35", "ro.product.cpu.abilist": "arm64-v8a,armeabi-v7a",
                 "ro.hardware": "qcom", "ro.kernel.qemu": "0"}
        enough = (preflight.MODEL_BYTES + preflight.RESERVE_BYTES) // 1024 + 3
        report = preflight.build_report(props, enough, True, "a" * 64)
        self.assertTrue(report["candidateReadyForManualQA"])
        self.assertFalse(report["actualPhysicalDeviceConfirmed"])
        self.assertIsNone(report["speechRecognitionVerified"])
        self.assertIsNone(report["secondAiCorrectionVerified"])
        self.assertFalse(report["playReleaseApproved"])
        self.assertNotIn("serial", report)
        self.assertFalse(preflight.build_report(props, enough, False)["candidateReadyForManualQA"])

    def test_emulator_low_storage_and_unknown_storage_are_not_approved(self):
        props = {"ro.build.version.sdk": "35", "ro.product.cpu.abilist": "arm64-v8a",
                 "ro.hardware": "ranchu", "ro.kernel.qemu": "1"}
        self.assertFalse(preflight.build_report(props, 99999999, True)["candidateReadyForManualQA"])
        props["ro.hardware"] = "qcom"
        props["ro.kernel.qemu"] = "0"
        self.assertFalse(preflight.build_report(props, 10, True)["candidateReadyForManualQA"])
        self.assertIsNone(preflight.build_report(props, None, True)["initialModelSpaceSufficient"])

    def test_legacy_android_and_32bit_are_not_candidates(self):
        props = {"ro.build.version.sdk": "28", "ro.product.cpu.abilist": "armeabi-v7a",
                 "ro.hardware": "exynos", "ro.kernel.qemu": "0"}
        self.assertFalse(preflight.build_report(props, 999999999, True)["candidateReadyForManualQA"])


if __name__ == "__main__":
    unittest.main()
