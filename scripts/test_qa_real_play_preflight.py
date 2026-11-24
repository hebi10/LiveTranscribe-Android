import json
import unittest
from unittest.mock import patch
import qa_real_play_preflight as p

PROP = {"ro.build.version.sdk": "35", "ro.product.cpu.abilist": "arm64-v8a,armeabi-v7a",
        "ro.hardware": "qcom", "ro.kernel.qemu": "0"}
SHA = "a" * 40
CODE = 32
GOOD = "package:com.hebi.livetranslate versionCode:32 installer=com.android.vending"


class PlayDevicePreflightTests(unittest.TestCase):
    def test_exact_release_version_only_and_no_false_inference(self):
        report = p.release_report(PROP, 1000, GOOD, CODE, SHA)
        self.assertTrue(report["candidateReadyForManualQA"])
        self.assertFalse(report["initialModelSpaceSufficient"])
        self.assertFalse(report["sourceShaIndependentlyVerified"])
        self.assertFalse(report["actualPhysicalDeviceConfirmed"])
        self.assertFalse(report["actualAiInferenceVerified"])
        self.assertFalse(report["purchaseVerified"])
        self.assertFalse(report["playReleaseApproved"])
        self.assertNotIn("serial", json.dumps(report))

    def test_debug_wrong_version_wrong_installer_or_duplicate_rows_are_not_candidates(self):
        for bad in (GOOD.replace("livetranslate ", "livetranslate.debug "),
                    GOOD.replace("versionCode:32", "versionCode:31"),
                    GOOD.replace("com.android.vending", "com.android.shell"),
                    GOOD + "\n" + GOOD, "package:com.hebi.livetranslate installer=null"):
            self.assertFalse(p.release_report(PROP, 999999, bad, CODE, SHA)["candidateReadyForManualQA"])

    def test_emulator_and_unsupported_abi_are_not_candidates(self):
        for props in (dict(PROP, **{"ro.kernel.qemu": "1"}),
                      dict(PROP, **{"ro.product.cpu.abilist": "armeabi-v7a"}),
                      dict(PROP, **{"ro.build.version.sdk": "28"})):
            self.assertFalse(p.release_report(props, 999999, GOOD, CODE, SHA)["candidateReadyForManualQA"])

    def test_collector_uses_read_only_device_metadata_and_keeps_serial_private(self):
        with patch.object(p, "adb", side_effect=[
            "List of devices attached\nTEST_SERIAL device",
            "35", "arm64-v8a", "0", "qcom",
            "Filesystem 1K-blocks Used Available Use% Mounted on\n/dev/test 100 10 90 10% /data",
            GOOD,
        ]) as adb:
            report = p.collect("adb", CODE, SHA)
            self.assertTrue(report["candidateReadyForManualQA"])
            self.assertNotIn("TEST_SERIAL", json.dumps(report))
            self.assertEqual(["-s", "TEST_SERIAL", "shell", "pm", "list", "packages", "-i", "--show-versioncode",
                              p.PACKAGE], adb.call_args.args[1])
            self.assertFalse(any("install" in args.args[1] for args in adb.call_args_list))


if __name__ == "__main__":
    unittest.main()
