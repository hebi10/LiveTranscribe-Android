package com.charles.livecaptionn.monetization

internal enum class AdFreeStatus { UNKNOWN, ACTIVE, INACTIVE }

internal data class SubscriptionPlan(val id: String, val label: String, val price: String)

internal data class SubscriptionUiState(
    val status: AdFreeStatus = AdFreeStatus.UNKNOWN,
    val monthly: SubscriptionPlan? = null,
    val yearly: SubscriptionPlan? = null,
    val purchaseReady: Boolean = false,
    val message: String = "Google Play 구독 정보를 확인 중입니다."
)

internal data class ConsentUiState(
    val adsAllowed: Boolean = false,
    val privacyOptionsRequired: Boolean = false
)

internal object AdPlacementPolicy {
    const val PRODUCT_ID = "remove_ads"
    const val MONTHLY_BASE_PLAN = "monthly"
    const val YEARLY_BASE_PLAN = "yearly"

    fun canShowHomeBanner(
        configured: Boolean, consent: Boolean, status: AdFreeStatus,
        recording: Boolean, mainScreen: Boolean
    ): Boolean = configured && consent && status == AdFreeStatus.INACTIVE &&
        !recording && mainScreen

    fun canStartCheckout(hasBackend: Boolean, offerAvailable: Boolean, recording: Boolean) =
        hasBackend && offerAvailable && !recording
}
