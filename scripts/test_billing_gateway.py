"""Offline tests for the purchase gateway; no Google or Cloud Run network calls."""
from collections import deque
import io
import json
from pathlib import Path
import sys
from threading import BoundedSemaphore
import unittest
from unittest.mock import MagicMock, patch
from urllib.error import HTTPError
from urllib.parse import parse_qs, urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "server"))
try:
    import billing_gateway as gateway
finally:
    sys.path.pop(0)

BODY = b'{"productId":"remove_ads","purchaseToken":"Token_1234567890"}'
NOW = 1_800_000_000_000
ATTESTATION = "test." + "x" * 40
ORIGIN = "https://verifier-example.a.run.app"
PROOF = "x" * 32


def verdict(method="POST", body=BODY):
    return {
        "requestDetails": {
            "requestPackageName": gateway.PACKAGE,
            "requestHash": gateway.request_hash(method, body),
            "timestampMillis": str(NOW),
        },
        "appIntegrity": {"appRecognitionVerdict": "PLAY_RECOGNIZED", "packageName": gateway.PACKAGE},
        "accountDetails": {"appLicensingVerdict": "LICENSED"},
        "deviceIntegrity": {"deviceRecognitionVerdict": ["MEETS_DEVICE_INTEGRITY"]},
    }


class BillingGatewayTest(unittest.TestCase):
    def setUp(self):
        for field, value in (("SEEN", deque()), ("FINGERPRINTS", set()),
                             ("ATTEMPTS", deque()), ("SLOTS", BoundedSemaphore(8))):
            patcher = patch.object(gateway, field, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_shared_request_hash_vectors(self):
        self.assertEqual("5dc10413933372f58ee7591b602bb07ea2a9bf05923bf11a70336e307b39253a",
                         gateway.request_hash("GET", b""))
        self.assertEqual("92db4bf7048158201a89a2b34ca6a54eec3663c59569dae12225e0df1673f0aa",
                         gateway.request_hash("POST", BODY))
        self.assertNotEqual(gateway.request_hash("POST", BODY + b" "),
                            gateway.request_hash("POST", BODY))

    def test_only_fresh_package_bound_and_recognized_licensed_device_is_accepted(self):
        expected = gateway.request_hash("POST", BODY)
        self.assertTrue(gateway.valid_verdict(verdict(), expected, NOW))
        mutations = [
            ("requestDetails", "requestPackageName", "other.app"),
            ("requestDetails", "requestHash", "0" * 64),
            ("requestDetails", "timestampMillis", str(NOW - 120_001)),
            ("requestDetails", "timestampMillis", str(NOW + 10_001)),
            ("requestDetails", "timestampMillis", True),
            ("appIntegrity", "packageName", "other.app"),
            ("appIntegrity", "appRecognitionVerdict", "UNEVALUATED"),
            ("accountDetails", "appLicensingVerdict", "UNLICENSED"),
            ("deviceIntegrity", "deviceRecognitionVerdict", []),
            ("deviceIntegrity", "deviceRecognitionVerdict", "MEETS_DEVICE_INTEGRITY"),
        ]
        for section, key, value in mutations:
            data = verdict()
            data[section][key] = value
            with self.subTest(section=section, key=key):
                self.assertFalse(gateway.valid_verdict(data, expected, NOW))
        self.assertFalse(gateway.valid_verdict({}, expected, NOW))
        self.assertFalse(gateway.valid_verdict(verdict(), gateway.request_hash("GET", b""), NOW))

    def test_missing_invalid_and_malformed_body_never_call_google_or_verifier(self):
        with patch.object(gateway, "decode_token") as decode, patch.object(gateway, "forward_verified") as forward:
            for token in (None, "", "x" * 16_385, "bad\n" * 20):
                with self.assertRaises(gateway.Denied):
                    gateway.protected_request("POST", BODY, token)
            for body in (b"", b"{}", b"[]", b'{"productId":"remove_ads","purchaseToken":"Token_1234567890","extra":"x"}',
                         b'{"productId":"remove_ads","productId":"remove_ads","purchaseToken":"Token_1234567890"}',
                         b'{"productId":"other","purchaseToken":"Token_1234567890"}', b"x" * 2049):
                with self.assertRaises(ValueError):
                    gateway.protected_request("POST", body, ATTESTATION)
            with self.assertRaises(ValueError):
                gateway.protected_request("GET", BODY, ATTESTATION)
            decode.assert_not_called()
            forward.assert_not_called()

    def test_configuration_required_before_google(self):
        with patch.dict("os.environ", {}, clear=True), patch.object(gateway, "decode_token") as decode:
            with self.assertRaises(gateway.Unavailable):
                gateway.protected_request("POST", BODY, ATTESTATION)
            decode.assert_not_called()
        for origin in ("https://attacker.example", "http://verifier-example.a.run.app",
                       ORIGIN + "/verify", ORIGIN + "@evil.example"):
            with patch.dict("os.environ", {"BILLING_PRIVATE_VERIFIER_ORIGIN": origin,
                                           "BILLING_GATEWAY_AUTH_TOKEN": PROOF}, clear=True):
                with self.assertRaises(gateway.Unavailable):
                    gateway.configuration()

    def test_replay_rejected_before_second_decode(self):
        with (patch.object(gateway, "configuration", return_value=(ORIGIN, PROOF)),
              patch.object(gateway, "time", return_value=NOW / 1000),
              patch.object(gateway, "decode_token", return_value=verdict()) as decode,
              patch.object(gateway, "forward_verified", return_value={"productId": gateway.PRODUCT, "active": True}) as forward):
            self.assertTrue(gateway.protected_request("POST", BODY, ATTESTATION)["active"])
            with self.assertRaises(gateway.Denied):
                gateway.protected_request("POST", BODY, ATTESTATION)
            decode.assert_called_once()
            forward.assert_called_once_with("POST", json.loads(BODY), ORIGIN, PROOF)

    def test_get_readiness_requires_its_own_verdict(self):
        with (patch.object(gateway, "configuration", return_value=(ORIGIN, PROOF)),
              patch.object(gateway, "time", return_value=NOW / 1000),
              patch.object(gateway, "decode_token", return_value=verdict("GET", b"")),
              patch.object(gateway, "forward_verified", return_value={"productId": gateway.PRODUCT, "ready": True}) as forward):
            self.assertTrue(gateway.protected_request("GET", b"", ATTESTATION)["ready"])
            forward.assert_called_once_with("GET", None, ORIGIN, PROOF)

    def test_invalid_verdict_and_decoder_failure_never_forward(self):
        with (patch.object(gateway, "configuration", return_value=(ORIGIN, PROOF)),
              patch.object(gateway, "time", return_value=NOW / 1000),
              patch.object(gateway, "decode_token", side_effect=[verdict("GET", b""), gateway.Unavailable()]),
              patch.object(gateway, "forward_verified") as forward):
            with self.assertRaises(gateway.Denied):
                gateway.protected_request("POST", BODY, ATTESTATION)
            with self.assertRaises(gateway.Unavailable):
                gateway.protected_request("POST", BODY, ATTESTATION + "a")
            forward.assert_not_called()

    def test_budget_releases_expired_window_and_slots_block_before_google(self):
        with (patch.object(gateway, "REQUESTS_PER_MINUTE", 1),
              patch.object(gateway, "monotonic", side_effect=[100, 101, 160])):
            gateway.reserve_attestation(ATTESTATION)
            with self.assertRaises(gateway.RateLimited):
                gateway.reserve_attestation(ATTESTATION + "a")
            gateway.reserve_attestation(ATTESTATION + "a")
        self.assertEqual(2, len(gateway.FINGERPRINTS))
        slots = BoundedSemaphore(1)
        slots.acquire()
        with (patch.object(gateway, "SLOTS", slots),
              patch.object(gateway, "configuration", return_value=(ORIGIN, PROOF)),
              patch.object(gateway, "decode_token") as decode):
            with self.assertRaises(gateway.RateLimited):
                gateway.protected_request("POST", BODY, ATTESTATION)
            decode.assert_not_called()

    def test_decoder_uses_only_google_endpoint_and_integrity_scope(self):
        def response(body):
            manager = MagicMock()
            manager.__enter__.return_value = io.BytesIO(body)
            return manager
        with patch.object(gateway.OPENER, "open", side_effect=[
            response(b'{"access_token":"test-access"}'),
            response(json.dumps({"tokenPayloadExternal": verdict()}).encode()),
        ]) as fetch:
            self.assertEqual(verdict(), gateway.decode_token(ATTESTATION))
            first, second = [x.args[0] for x in fetch.call_args_list]
            self.assertEqual({"scopes": [gateway.INTEGRITY_SCOPE]}, parse_qs(urlsplit(first.full_url).query))
            self.assertEqual(gateway.DECODE_URL, second.full_url)
            self.assertEqual({"integrity_token": ATTESTATION}, json.loads(second.data))

    def test_forward_constructs_service_identity_and_only_returns_strict_boolean(self):
        def response(body):
            manager = MagicMock()
            manager.__enter__.return_value = io.BytesIO(body)
            return manager
        with patch.object(gateway.OPENER, "open", side_effect=[
            response(b"test.header.signature"),
            response(b'{"productId":"remove_ads","active":false,"ignored":"x"}'),
        ]) as fetch:
            self.assertEqual({"productId": gateway.PRODUCT, "active": False},
                             gateway.forward_verified("POST", json.loads(BODY), ORIGIN, PROOF))
            first, second = [x.args[0] for x in fetch.call_args_list]
            self.assertEqual({"audience": [ORIGIN]}, parse_qs(urlsplit(first.full_url).query))
            self.assertEqual(ORIGIN + "/verify", second.full_url)
            self.assertEqual("Bearer test.header.signature", second.get_header("Authorization"))
            self.assertEqual(PROOF, second.get_header(gateway.GATEWAY_HEADER.capitalize()))
            self.assertIsNone(second.get_header(gateway.INTEGRITY_HEADER.capitalize()))
            self.assertEqual(json.loads(BODY), json.loads(second.data))
        with patch.object(gateway.OPENER, "open", side_effect=[
            response(b"test.header.signature"),
            response(b'{"productId":"remove_ads","active":"true"}'),
        ]):
            with self.assertRaises(gateway.Unavailable):
                gateway.forward_verified("POST", json.loads(BODY), ORIGIN, PROOF)

    def test_upstream_http_failure_never_becomes_active(self):
        for status, error in ((429, gateway.RateLimited), (500, gateway.Unavailable), (302, gateway.Unavailable)):
            with patch.object(gateway.OPENER, "open", side_effect=HTTPError(ORIGIN, status, "test", None, None)):
                with self.assertRaises(error):
                    gateway.forward_verified("GET", None, ORIGIN, PROOF)


if __name__ == "__main__":
    unittest.main()
