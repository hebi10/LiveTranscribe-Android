import base64
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).parents[1]))
import preflight


class PreflightTests(unittest.TestCase):
    def test_bootstrap_requires_explicit_true(self):
        for value in ('', 'false', 'yes', '1'):
            with self.assertRaisesRegex(preflight.PreflightError, 'Nothing was deployed'):
                preflight.require_initialized({'PLAY_CONSOLE_INITIALIZED': value})
        preflight.require_initialized({'PLAY_CONSOLE_INITIALIZED': 'true'})

    def test_auth_prefers_wif_and_partial_wif_never_falls_back(self):
        self.assertEqual(preflight.auth_mode({'PLAY_WORKLOAD_IDENTITY_PROVIDER': 'provider', 'PLAY_SERVICE_ACCOUNT': 'account', 'PLAY_SERVICE_ACCOUNT_JSON': 'json'}), 'wif')
        for env in ({}, {'PLAY_WORKLOAD_IDENTITY_PROVIDER': 'provider', 'PLAY_SERVICE_ACCOUNT_JSON': 'json'}, {'PLAY_SERVICE_ACCOUNT': 'account'}):
            with self.assertRaises(preflight.PreflightError):
                preflight.auth_mode(env)
        self.assertEqual(preflight.auth_mode({'PLAY_SERVICE_ACCOUNT_JSON': 'json'}), 'json')

    def verify(self, code=4, output=b'jar verified, with signer errors.\n', certificates=(b'existing-app-certificate',), expected=None):
        def run(command, **kwargs):
            if command[0] == 'jarsigner':
                return subprocess.CompletedProcess(command, code, stdout=output)
            pem = b'\n'.join(b'-----BEGIN CERTIFICATE-----\n' + base64.b64encode(cert) + b'\n-----END CERTIFICATE-----' for cert in certificates)
            return subprocess.CompletedProcess(command, 0, stdout=pem)
        with tempfile.NamedTemporaryFile(suffix='.aab') as bundle:
            preflight.verify_bundle(bundle.name, expected or hashlib.sha256(b'existing-app-certificate').hexdigest(), run)

    def test_self_signed_existing_certificate_is_allowed_after_signature_verification(self):
        self.verify()

    def test_unsigned_broken_and_partial_signed_bundles_are_rejected(self):
        for code, output in ((0, b'jar is unsigned.'), (16, b'jar verified, with signer errors.'), (4, b'jar verified. unsigned entries'), (1, b'broken signature')):
            with self.assertRaises(preflight.PreflightError):
                self.verify(code, output)

    def test_other_or_multiple_signers_are_rejected(self):
        for certs in ((b'other-app',), (b'existing-app-certificate', b'other-app')):
            with self.assertRaisesRegex(preflight.PreflightError, 'existing release certificate'):
                self.verify(certificates=certs)

    def test_invalid_expected_certificate_blocks_before_running_tools(self):
        with self.assertRaises(preflight.PreflightError):
            self.verify(expected='new-key-please')
