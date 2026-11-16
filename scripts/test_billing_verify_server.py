"""Pure offline tests for the proposed operator-hosted Play token verifier."""
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
            {},
        ):
            with self.subTest(item=item):
                self.assertFalse(service.is_active(item, NOW))

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

    def test_unknown_google_status_must_not_grant_access(self):
        with patch.object(service, "fetch_subscription", side_effect=service.VerificationUnavailable):
            with self.assertRaises(service.VerificationUnavailable):
                service.verification_result({
                    "productId": "remove_ads", "purchaseToken": "Token_1234567890"
                })


if __name__ == "__main__":
    unittest.main()
