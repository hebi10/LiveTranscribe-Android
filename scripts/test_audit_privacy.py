import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


class PrivacyAuditTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        source = Path(__file__).resolve().parents[1]
        for relative in (
            "scripts/audit_privacy.py", "scripts/prepare_language_id.py",
            "app/build.gradle.kts", "app/src/main/AndroidManifest.xml",
            "app/src/main/res/xml/data_extraction_rules.xml",
            "app/src/main/java/com/charles/livecaptionn/data/TranscriptHistoryStore.kt",
            "app/src/main/java/com/charles/livecaptionn/speech/OfflineSpokenLanguageIdentifier.kt",
            "app/src/main/java/com/charles/livecaptionn/speech/LanguageRecommendationTracker.kt",
            "app/src/main/java/com/charles/livecaptionn/translation/TranslationModelSetup.kt",
        ):
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source / relative, target)

    def audit(self):
        return subprocess.run([sys.executable, str(self.root / "scripts/audit_privacy.py")],
                              text=True, capture_output=True)

    def test_mlkit_dependency_is_rejected(self):
        path = self.root / "app/build.gradle.kts"
        path.write_text(path.read_text() + '\nimplementation("com.google.mlkit:translate:17.0.3")\n')
        result = self.audit()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Forbidden runtime dependency", result.stderr)

    def test_fixed_model_get_transport_is_permitted(self):
        path = self.root / "app/src/main/java/com/charles/livecaptionn/models/ModelDownloadTransport.kt"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('import java.net.URL\nimport javax.net.ssl.HttpsURLConnection\n'
                        'connection.requestMethod = "GET"\nconnection.doOutput = false\n')
        result = self.audit()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_model_transport_cannot_send_request_body(self):
        path = self.root / "app/src/main/java/com/charles/livecaptionn/models/ModelDownloadTransport.kt"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('import java.net.URL\nconnection.requestMethod = "POST"\n'
                        'connection.doOutput = true\nconnection.outputStream.write(text.toByteArray())\n')
        result = self.audit()
        self.assertNotEqual(0, result.returncode)

    def test_cleartext_network_is_rejected(self):
        path = self.root / "app/src/main/AndroidManifest.xml"
        path.write_text(path.read_text().replace('android:usesCleartextTraffic="false"',
                                                'android:usesCleartextTraffic="true"'))
        result = self.audit()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Cleartext", result.stderr)

    def test_missing_file_domain_is_rejected_even_with_root_exclusion(self):
        path = self.root / "app/src/main/res/xml/data_extraction_rules.xml"
        path.write_text('<data-extraction-rules><cloud-backup><exclude domain="root" path="." />'
                        '</cloud-backup><device-transfer><exclude domain="root" path="." />'
                        '</device-transfer></data-extraction-rules>')
        result = self.audit()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("backup domains", result.stderr)

    def test_conflicting_internet_declaration_is_rejected(self):
        path = self.root / "app/src/main/AndroidManifest.xml"
        path.write_text(path.read_text().replace('<application',
                        '<uses-permission android:name="android.permission.INTERNET" /><application', 1))
        result = self.audit()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("INTERNET", result.stderr)

    def test_clean_audit_states_sdk_telemetry_is_not_verified(self):
        result = self.audit()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("SDK internals/telemetry", result.stdout)
        self.assertIn("unverified", result.stdout)

    def test_removed_internet_permission_is_rejected(self):
        path = self.root / "app/src/main/AndroidManifest.xml"
        contents = path.read_text()
        if 'xmlns:tools=' not in contents:
            contents = contents.replace('xmlns:android=',
                'xmlns:tools="http://schemas.android.com/tools" xmlns:android=', 1)
        contents = contents.replace('android:name="android.permission.INTERNET" />',
            'android:name="android.permission.INTERNET" tools:node="remove" />')
        path.write_text(contents)
        result = self.audit()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("INTERNET", result.stderr)

    def test_missing_network_state_permission_is_rejected(self):
        path = self.root / "app/src/main/AndroidManifest.xml"
        contents = path.read_text()
        path.write_text("\n".join(line for line in contents.splitlines()
            if "android.permission.ACCESS_NETWORK_STATE" not in line))
        result = self.audit()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("ACCESS_NETWORK_STATE", result.stderr)

    def test_direct_app_network_code_is_still_rejected(self):
        path = self.root / "app/src/main/java/com/charles/livecaptionn/Upload.kt"
        path.write_text("import java.net.HttpURLConnection\n")
        result = self.audit()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Forbidden behavior", result.stderr)

    def test_device_transfer_shared_preferences_exclusion_is_required(self):
        path = self.root / "app/src/main/res/xml/data_extraction_rules.xml"
        contents = path.read_text()
        start = contents.index("<device-transfer>")
        path.write_text(contents[:start] + contents[start:].replace(
            '<exclude domain="sharedpref" path="." />', '', 1))
        result = self.audit()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("device-transfer", result.stderr)


if __name__ == "__main__":
    unittest.main()
