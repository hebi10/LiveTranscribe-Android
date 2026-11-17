"""Pure offline tests for the proposed operator-hosted Play token verifier."""
from collections import deque
import datetime as dt
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

SOURCE = Path(__file__).resolve().parents[1] / "server/billing_verify.py"
spec = importlib.util.spec_from_file_location("live_billing_verify", SOURCE)
service = importlib.util.module_from_spec(spec)
spec.loader.exec_module(service)
NOW = dt.datetime(2026, 11, 16, tzinfo=dt.timezone.utc)


class PurchaseVerificationTests(unittest.TestCase):
    def data(self, state="SUBSCRIPTION_STATE_ACTIVE", expires="2026-12-16T15:00:00Z",
             product="remove_ads"):
        return {"subscriptionState": state,
                "lineItems": [{"productId": product, "expiryTime": expires}]}

    def test_valid_active_and_grace_and_cancelled_unexpired(self):
        for state in ("SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD",
                      "SUBSCRIPTION_STATE_CANCELED"):
            with self.subTest(state=state):
                self.assertTrue(service.is_active(self.data(state=state), NOW))

    def test_expired_hold_paused_pending_wrong_product_are_denied(self):
        for item in (
            self.data(expires="2026-10-16T15:00:00Z"),
            self.data(state="SUBSCRIPTION_STATE_ON_HOLD"),
            self.data(state="SUBSCRIPTION_STATE_PAUSED"),
            self.data(state="SUBSCRIPTION_STATE_PENDING"),
            self.data(product="unrelated"),
            self.data(expires="not-a-date"),
            {"subscriptionState": "SUBSCRIPTION_STATE_ACTIVE", "lineItems": None},
            {"subscriptionState": "SUBSCRIPTION_STATE_ACTIVE", "lineItems": {}},
            {"subscriptionState": "SUBSCRIPTION_STATE_ACTIVE", "lineItems": "not-a-list"},
            {},
        ):
            with self.subTest(item=item):
                self.assertFalse(service.is_active(item, NOW))

    def test_malformed_play_line_items_fail_closed(self):
        payload = {"productId": service.PRODUCT, "purchaseToken": "Token_1234567890"}
        with patch.object(service, "fetch_subscription", return_value={
            "subscriptionState": "SUBSCRIPTION_STATE_ACTIVE", "lineItems": None
        }):
            self.assertEqual({"productId": service.PRODUCT, "active": False},
                             service.verification_result(payload))

    def test_purchase_proof_accepts_only_configured_product(self):
        payload = {"productId": service.PRODUCT, "purchaseToken": "Token_1234567890"}
        with patch.object(service, "fetch_subscription", return_value=self.data(
            expires="2099-01-01T00:00:00Z")) as getter:
            self.assertEqual({"productId": "remove_ads", "active": True},
                             service.verification_result(payload))
            getter.assert_called_once_with("Token_1234567890")
        for bad in ({"productId": "other", "purchaseToken": "Token_1234567890"},
                    {"productId": "remove_ads", "purchaseToken": "x"},
                    {"productId": "remove_ads", "purchaseToken": ""},
                    {"productId": "remove_ads", "purchaseToken": "x" * 2000}):
            with self.assertRaises(ValueError):
                service.verification_result(bad)

    def test_metadata_token_requests_android_publisher_scope(self):
        from urllib.parse import parse_qs, urlsplit

        with patch.object(service, "urlopen") as fetch:
            fetch.return_value.__enter__.return_value.read.return_value = (
                b'{"access_token":"test-access-token"}'
            )
            self.assertEqual(service.access_token(), "test-access-token")

        request = fetch.call_args.args[0]
        self.assertEqual(request.get_header("Metadata-flavor"), "Google")
        self.assertEqual(
            parse_qs(urlsplit(request.full_url).query),
            {"scopes": ["https://www.googleapis.com/auth/androidpublisher"]},
        )

    def test_unknown_google_status_must_not_grant_access(self):
        with patch.object(service, "fetch_subscription", side_effect=service.VerificationUnavailable):
            with self.assertRaises(service.VerificationUnavailable):
                service.verification_result({
                    "productId": "remove_ads", "purchaseToken": "Token_1234567890"
                })

    def test_rate_limit_blocks_google_calls_until_window_expires(self):
        payload = {"productId": service.PRODUCT, "purchaseToken": "Token_1234567890"}
        with (patch.object(service, "verification_requests", deque()),
              patch.object(service, "VERIFICATIONS_PER_WINDOW", 1),
              patch.object(service, "monotonic", side_effect=[100, 101, 160]),
              patch.object(service, "fetch_subscription", return_value={}) as fetch):
            service.verification_result(payload)
            with self.assertRaises(service.VerificationRateLimited):
                service.verification_result(payload)
            fetch.assert_called_once()
            service.verification_result(payload)
            self.assertEqual(2, fetch.call_count)


if __name__ == "__main__":
    unittest.main()
