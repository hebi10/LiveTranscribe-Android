package com.charles.livecaptionn

import com.charles.livecaptionn.monetization.AdFreeStatus
import com.charles.livecaptionn.monetization.AdPlacementPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonetizationPolicyTest {
    @Test fun onlyIdleHomeWithConsentAndVerifiedNoSubscriptionCanShowAds() {
        assertTrue(AdPlacementPolicy.canShowHomeBanner(true, true, AdFreeStatus.INACTIVE, false, true))
        for (status in listOf(AdFreeStatus.UNKNOWN, AdFreeStatus.ACTIVE)) {
            assertFalse(AdPlacementPolicy.canShowHomeBanner(true, true, status, false, true))
        }
        assertFalse(AdPlacementPolicy.canShowHomeBanner(false, true, AdFreeStatus.INACTIVE, false, true))
        assertFalse(AdPlacementPolicy.canShowHomeBanner(true, false, AdFreeStatus.INACTIVE, false, true))
        assertFalse(AdPlacementPolicy.canShowHomeBanner(true, true, AdFreeStatus.INACTIVE, true, true))
        assertFalse(AdPlacementPolicy.canShowHomeBanner(true, true, AdFreeStatus.INACTIVE, false, false))
    }

    @Test fun purchaseFlowCannotStartWithoutVerificationOrWhileRecording() {
        assertTrue(AdPlacementPolicy.canStartCheckout(true, true, false))
        assertFalse(AdPlacementPolicy.canStartCheckout(false, true, false))
        assertFalse(AdPlacementPolicy.canStartCheckout(true, false, false))
        assertFalse(AdPlacementPolicy.canStartCheckout(true, true, true))
    }
}
