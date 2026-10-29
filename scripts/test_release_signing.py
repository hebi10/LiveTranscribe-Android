"""Signing tests use synthetic bytes and fake tools, never a real private key."""
import base64
import hashlib
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest

import release_signing as signing


class ReleaseSigningTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.keystore = Path(self.directory.name) / "release.jks"
        self.certificate = b"synthetic public certificate fixture"
        self.fingerprint = hashlib.sha256(self.certificate).hexdigest()
        self.environment = {
            "ANDROID_RELEASE_KEYSTORE_BASE64": base64.b64encode(b"synthetic keystore fixture").decode(),
            "ANDROID_RELEASE_STORE_PASSWORD": "fixture-store-password",
            "ANDROID_RELEASE_KEY_ALIAS": "fixture-alias",
            "ANDROID_RELEASE_KEY_PASSWORD": "fixture-key-password",
            "ANDROID_RELEASE_CERT_SHA256": self.fingerprint,
        }

    def tool(self, arguments, **kwargs):
        self.assertNotIn(self.environment["ANDROID_RELEASE_STORE_PASSWORD"], arguments)
        self.assertNotIn(self.environment["ANDROID_RELEASE_KEY_PASSWORD"], arguments)
        self.assertIn("-storepass:env", arguments)
        return subprocess.CompletedProcess(arguments, 0, self.certificate, b"")

    def test_missing_secret_fails_before_creating_keystore(self):
        for name in signing.REQUIRED_SECRETS:
            with self.subTest(name=name):
                environment = self.environment.copy()
                del environment[name]
                with self.assertRaisesRegex(signing.SigningError, name):
                    signing.prepare(self.keystore, environment, self.tool)
                self.assertFalse(self.keystore.exists())

    def test_keystore_is_private_and_fingerprint_is_checked(self):
        signing.prepare(self.keystore, self.environment, self.tool)
        self.assertEqual(b"synthetic keystore fixture", self.keystore.read_bytes())
        self.assertEqual(0o600, stat.S_IMODE(self.keystore.stat().st_mode))

    def test_existing_keystore_is_never_replaced(self):
        self.keystore.write_bytes(b"existing key must survive")
        with self.assertRaises(signing.SigningError):
            signing.prepare(self.keystore, self.environment, self.tool)
        self.assertEqual(b"existing key must survive", self.keystore.read_bytes())

    def test_invalid_base64_is_rejected(self):
        self.environment["ANDROID_RELEASE_KEYSTORE_BASE64"] = "%%%not-base64%%%"
        with self.assertRaisesRegex(signing.SigningError, "base64"):
            signing.prepare(self.keystore, self.environment, self.tool)
        self.assertFalse(self.keystore.exists())

    def test_wrong_certificate_removes_only_new_temporary_keystore(self):
        self.environment["ANDROID_RELEASE_CERT_SHA256"] = "0" * 64
        with self.assertRaisesRegex(signing.SigningError, "certificate"):
            signing.prepare(self.keystore, self.environment, self.tool)
        self.assertFalse(self.keystore.exists())

    def test_tool_errors_do_not_disclose_tool_output(self):
        def failure(arguments, **kwargs):
            return subprocess.CompletedProcess(arguments, 1, b"", b"secret output must not escape")
        with self.assertRaises(signing.SigningError) as error:
            signing.prepare(self.keystore, self.environment, failure)
        self.assertNotIn("secret output", str(error.exception))
        self.assertFalse(self.keystore.exists())

    def test_apk_requires_successful_verification_and_expected_single_signer(self):
        apk = Path(self.directory.name) / "release.apk"
        apk.write_bytes(b"synthetic APK fixture")
        cases = (
            (0, f"Signer #1 certificate SHA-256 digest: {self.fingerprint}\n", True),
            (0, "Signer #1 certificate SHA-256 digest: " + "0" * 64 + "\n", False),
            (0, "", False),
            (0, f"Signer #1 certificate SHA-256 digest: {self.fingerprint}\n"
                f"Signer #2 certificate SHA-256 digest: {self.fingerprint}\n", False),
            (1, f"Signer #1 certificate SHA-256 digest: {self.fingerprint}\n", False),
        )
        for status, output, allowed in cases:
            with self.subTest(status=status, output=output):
                def tool(arguments, **kwargs):
                    self.assertEqual(["apksigner", "verify", "--verbose", "--print-certs", str(apk)], arguments)
                    return subprocess.CompletedProcess(arguments, status, output, "")
                if allowed:
                    signing.verify_apk(apk, "apksigner", self.environment, tool)
                else:
                    with self.assertRaises(signing.SigningError):
                        signing.verify_apk(apk, "apksigner", self.environment, tool)

    def test_fingerprint_allows_conventional_colon_format_and_rejects_malformed_value(self):
        value = ":".join(self.fingerprint[index:index + 2] for index in range(0, 64, 2)).upper()
        self.assertEqual(self.fingerprint, signing.normalize_fingerprint(value))
        with self.assertRaisesRegex(signing.SigningError, "SHA-256"):
            signing.normalize_fingerprint("not-a-certificate-fingerprint")


if __name__ == "__main__":
    unittest.main()
