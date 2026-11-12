import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("qa_real_01_a_preflight", Path(__file__).with_name("qa_real_01_a_preflight.py"))
qa = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(qa)


class DevicePreflightTests(unittest.TestCase):
    def test_pinned_catalog_has_only_valid_paths_and_expected_sizes(self):
        catalog = qa.parse_catalog(qa.CATALOG.read_text(encoding="utf-8"))
        self.assertEqual(7, len(catalog))
        self.assertTrue(all(len(x['sha']) == 64 for x in catalog))
        self.assertGreater(sum(x['size'] for x in catalog), 2_000_000_000)

    def test_unsigned_or_extra_catalog_entries_are_never_silently_accepted(self):
        with self.assertRaisesRegex(qa.PreflightError, 'seven'):
            qa.parse_catalog('')
        with self.assertRaisesRegex(qa.PreflightError, 'Invalid model path'):
            qa.parse_catalog('OfflineModelFile("a", "../bad", "https://x", 1L, "' + 'a'*64 + '")')

    def test_reports_fail_closed_without_asserting_inference_success(self):
        with patch.object(qa, 'read_property', side_effect=['arm64-v8a', '36', '', 'qcom']):
            with patch.object(qa, 'adb', return_value=(0, 'package:/data/app/pkg/base.apk', '')):
                result = qa.inspect_device('not-saved', qa.parse_catalog(qa.CATALOG.read_text(encoding='utf-8')), False)
        self.assertTrue(result['physicalArm64Eligible'])
        self.assertTrue(result['testPackageInstalled'])
        self.assertEqual('NOT RUN', result['modelIntegrity'])
        self.assertEqual('NOT RUN', result['microphoneTranslation'])
        self.assertEqual('NOT RUN', result['secondAiCorrection'])
        self.assertNotIn('not-saved', str(result))

    def test_emulators_and_missing_app_block_eligibility(self):
        models = qa.parse_catalog(qa.CATALOG.read_text(encoding='utf-8'))
        with patch.object(qa, 'read_property', side_effect=['arm64-v8a', '35', '1', 'ranchu']):
            with patch.object(qa, 'adb', return_value=(0, '', '')):
                r = qa.inspect_device('secret', models, True)
        self.assertFalse(r['physicalArm64Eligible'])
        self.assertEqual('NOT RUN', r['modelIntegrity'])

    def test_model_verification_detects_real_hash_mismatch(self):
        model = qa.parse_catalog(qa.CATALOG.read_text(encoding='utf-8'))[0]
        def fake_adb(argv, serial=None, timeout=30):
            if 'stat' in argv:
                return (0, str(model['size']), '')
            return (0, '0'*64 + '  file', '')
        with patch.object(qa, 'adb', side_effect=fake_adb):
            result = qa.read_model_integrity(model, 'temporary')
        self.assertEqual('FAIL', result['status'])
        self.assertFalse(result['sha256Match'])

    def test_never_overwrites_existing_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory, 'device.json')
            qa.save_report(p, {'microphoneTranslation': 'NOT RUN'})
            with self.assertRaises(FileExistsError):
                qa.save_report(p, {'microphoneTranslation': 'PASS'})


if __name__ == '__main__':
    unittest.main()
