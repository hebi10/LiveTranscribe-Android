"""Source-level assertions: advertising must never reach private caption/audio overlays."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/java/com/charles/livecaptionn"
M = APP / "monetization"


class MonetizationContractTests(unittest.TestCase):
    def read(self, name):
        return (ROOT / name).read_text(encoding="utf-8")

    def test_restricted_home_ads_and_no_recording_leak(self):
        policy = (M / "MonetizationPolicy.kt").read_text()
        self.assertIn("!recording && mainScreen", policy)
        self.assertIn("status == AdFreeStatus.INACTIVE", policy)
        home = (APP / "ui/MainScreen.kt").read_text()
        self.assertIn("HomeAdBanner(visible = true)", home)
        self.assertIn("ModelSetupStage.CHECKING", home)
        self.assertIn("ModelSetupStage.DOWNLOADING", home)
        for f in list((APP / "overlay").glob("*.kt")) + list((APP / "speech").glob("*.kt")):
            self.assertNotIn("com.google.android.libraries.ads.mobile.sdk.", f.read_text())
            self.assertNotIn("com.android.billingclient.api.", f.read_text())

    def test_purchase_validation_blocks_unverified_entitlement(self):
        purchase = (M / "SubscriptionController.kt").read_text()
        self.assertIn("verifier.verify(purchase.purchaseToken)", purchase)
        self.assertIn("confirmed != null -> AdFreeStatus.ACTIVE", purchase)
        self.assertIn("acknowledgePurchase(params)", purchase)
        self.assertIn("acknowledgeVerifiedPurchase(params)", purchase)
        self.assertIn("retryable && attempt < 5", purchase)
        self.assertIn("!verifier.configured", purchase)
        self.assertIn("offerId == null", purchase)

    def test_real_ads_require_real_ids_and_privacy_consent(self):
        gradle = self.read("app/build.gradle.kts")
        self.assertIn("liveGmaReady = Regex", gradle)
        self.assertIn("providedGmaAppId != testGmaAppId", gradle)
        self.assertIn("testAdsEnabled = providers.gradleProperty", gradle)
        self.assertIn("enableTestAds", gradle)
        consent = (M / "AdsConsentController.kt").read_text()
        self.assertIn("requestConsentInfoUpdate(", consent)
        self.assertIn("loadAndShowConsentFormIfRequired", consent)
        self.assertIn("canRequestAds()", consent)

    def test_service_does_not_log_purchase_token_or_user_captions(self):
        service = self.read("server/billing_verify.py")
        self.assertIn("metadata.google.internal", service)
        self.assertIn("subscriptionsv2/tokens/", service)
        self.assertIn("do_POST(self)", service)
        self.assertNotIn("print(token)", service)
        self.assertNotIn("self.log_request(", service)
        self.assertNotIn("originalText", service)
        self.assertNotIn("translatedText", service)

    def test_privacy_policy_is_not_silently_auto_published(self):
        workflow = self.read(".github/workflows/privacy-pages.yml")
        self.assertIn("  workflow_dispatch:", workflow)
        self.assertNotIn("  push:", workflow.split("\npermissions:", 1)[0])
        privacy = self.read("docs/privacy-policy.html")
        self.assertIn("Google Mobile Ads SDK", privacy)
        self.assertIn("구매 토큰", privacy)


if __name__ == "__main__":
    unittest.main()
