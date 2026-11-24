package com.charles.livecaptionn.monetization

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A Play purchase alone never grants ad-free status.
 * Verify a PURCHASED token with the operator's subscriptionsv2 backend first.
 */
internal class SubscriptionController(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val verifier = PurchaseVerifier()
    private val mutable = MutableStateFlow(SubscriptionUiState())
    val state: StateFlow<SubscriptionUiState> = mutable.asStateFlow()
    private var product: ProductDetails? = null
    private var connected = false
    private var connecting = false
    private var closed = false
    private var refreshRevision = 0L
    private var plansRevision = 0L

    private val billing = BillingClient.newBuilder(context.applicationContext)
        .setListener(PurchasesUpdatedListener { result, purchases ->
            if (closed) return@PurchasesUpdatedListener
            if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                verifyPurchases(purchases)
            } else if (result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
                refresh()
            } else {
                ++refreshRevision
                mutable.value = mutable.value.copy(status = AdFreeStatus.UNKNOWN,
                    message = "Google Play 결제 상태를 다시 확인해 주세요.")
            }
        })
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    fun start() {
        if (closed) return
        if (connected && billing.isReady) {
            refresh()
            return
        }
        if (connecting) return
        ++refreshRevision
        ++plansRevision
        product = null
        mutable.value = mutable.value.copy(status = AdFreeStatus.UNKNOWN,
            purchaseReady = false, monthly = null, yearly = null, planMessage = "",
            message = "Google Play 구독 연결을 확인 중입니다.")
        connecting = true
        billing.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                connecting = false
                if (closed) return
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    connected = true
                    refresh()
                } else {
                    ++refreshRevision
                    ++plansRevision
                    product = null
                    connected = false
                    mutable.value = mutable.value.copy(status = AdFreeStatus.UNKNOWN,
                        purchaseReady = false, message = "Google Play 구독 연결을 확인할 수 없습니다.")
                }
            }
            override fun onBillingServiceDisconnected() {
                connected = false
                connecting = false
                if (!closed) {
                    ++refreshRevision
                    ++plansRevision
                    product = null
                    mutable.value = mutable.value.copy(status = AdFreeStatus.UNKNOWN,
                        purchaseReady = false, monthly = null, yearly = null, planMessage = "",
                        message = "Google Play 연결이 끊겼습니다.")
                }
            }
        })
    }

    fun refresh() {
        if (closed || !billing.isReady) {
            start()
            return
        }
        val requestRevision = ++refreshRevision
        mutable.value = mutable.value.copy(status = AdFreeStatus.UNKNOWN,
            purchaseReady = false, message = "구독 상태를 다시 확인 중입니다.")
        loadPlans()
        billing.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()
        ) { result, purchases ->
            if (closed || requestRevision != refreshRevision) return@queryPurchasesAsync
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                mutable.value = mutable.value.copy(status = AdFreeStatus.UNKNOWN,
                    message = "구독 상태를 확인하지 못했습니다.")
            } else {
                verifyPurchases(purchases)
            }
        }
    }

    private fun loadPlans() {
        val revision = ++plansRevision
        val params = QueryProductDetailsParams.newBuilder().setProductList(
            listOf(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(AdPlacementPolicy.PRODUCT_ID)
                .setProductType(BillingClient.ProductType.SUBS).build())
        ).build()
        billing.queryProductDetailsAsync(params) { result, details ->
            if (closed || revision != plansRevision || !billing.isReady) return@queryProductDetailsAsync
            product = if (result.responseCode == BillingClient.BillingResponseCode.OK)
                details.productDetailsList.firstOrNull {
                    it.productId == AdPlacementPolicy.PRODUCT_ID
                } else null
            val monthly = plan(AdPlacementPolicy.MONTHLY_BASE_PLAN, "월간")
            val yearly = plan(AdPlacementPolicy.YEARLY_BASE_PLAN, "연간")
            mutable.value = mutable.value.copy(
                monthly = monthly,
                yearly = yearly,
                purchaseReady = false,
                planMessage = if (!verifier.configured)
                    "구매 검증 서버 연결 후 구독을 사용할 수 있습니다."
                else if (result.responseCode != BillingClient.BillingResponseCode.OK)
                    "Google Play 요금제를 확인하지 못했습니다. 다시 확인해 주세요."
                else if (monthly == null && yearly == null)
                    "구매 가능한 광고 제거 요금제가 없습니다."
                else "구매 검증 서버를 확인 중입니다."
            )
            if (verifier.configured && (monthly != null || yearly != null)) {
                scope.launch {
                    val ready = verifier.ready()
                    if (closed || revision != plansRevision || !billing.isReady) return@launch
                    mutable.value = mutable.value.copy(
                        purchaseReady = ready,
                        planMessage = if (ready) "Google Play에 표시된 실제 가격으로 결제합니다."
                        else "구매 검증 서버에 연결할 수 없어 결제를 시작할 수 없습니다."
                    )
                }
            }
        }
    }

    private fun basePlan(id: String): ProductDetails.SubscriptionOfferDetails? =
        product?.subscriptionOfferDetails?.firstOrNull {
            it.basePlanId == id && it.offerId == null
        }

    private fun plan(id: String, label: String): SubscriptionPlan? {
        val offer = basePlan(id) ?: return null
        val price = offer.pricingPhases.pricingPhaseList.lastOrNull()?.formattedPrice ?: return null
        return SubscriptionPlan(id, label, price)
    }

    fun purchase(activity: Activity, basePlanId: String, recording: Boolean) {
        if (closed || !billing.isReady || recording || !verifier.configured ||
            !mutable.value.purchaseReady || mutable.value.status != AdFreeStatus.INACTIVE) return
        val details = product ?: return
        val offer = basePlan(basePlanId) ?: return
        if (basePlanId !in listOf(AdPlacementPolicy.MONTHLY_BASE_PLAN,
                AdPlacementPolicy.YEARLY_BASE_PLAN)) return
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(
            listOf(BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details).setOfferToken(offer.offerToken).build())
        ).build()
        // Disable repeat taps while Play owns the checkout UI; refresh on resume/result.
        mutable.value = mutable.value.copy(purchaseReady = false)
        val response = billing.launchBillingFlow(activity, params)
        if (response.responseCode != BillingClient.BillingResponseCode.OK) {
            mutable.value = mutable.value.copy(purchaseReady = true,
                message = "Google Play 결제창을 열지 못했습니다.")
        }
    }

    private fun verifyPurchases(purchases: List<Purchase>) {
        val current = ++refreshRevision
        val relevant = purchases.filter { AdPlacementPolicy.PRODUCT_ID in it.products }
        val purchased = relevant.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        if (relevant.any { it.purchaseState == Purchase.PurchaseState.PENDING }) {
            mutable.value = mutable.value.copy(status = AdFreeStatus.UNKNOWN,
                message = "결제 처리 중입니다. 완료되면 다시 확인합니다.")
            return
        }
        if (purchased.isEmpty()) {
            mutable.value = mutable.value.copy(status = AdFreeStatus.INACTIVE,
                message = "활성 광고 제거 구독이 없습니다.")
            return
        }
        mutable.value = mutable.value.copy(status = AdFreeStatus.UNKNOWN,
            message = "광고 제거 구독을 확인 중입니다.")
        scope.launch {
            var uncertain = false
            var confirmed: Purchase? = null
            for (purchase in purchased) {
                when (verifier.verify(purchase.purchaseToken)) {
                    true -> { confirmed = purchase; break }
                    null -> uncertain = true
                    false -> Unit
                }
            }
            if (closed || current != refreshRevision) return@launch
            mutable.value = mutable.value.copy(
                status = when {
                    confirmed != null -> AdFreeStatus.ACTIVE
                    uncertain -> AdFreeStatus.UNKNOWN
                    else -> AdFreeStatus.INACTIVE
                },
                message = if (confirmed != null) "광고 제거 구독이 적용되었습니다."
                    else if (uncertain) "구독 검증에 연결할 수 없습니다."
                    else "활성 광고 제거 구독이 없습니다."
            )
            if (confirmed != null && !confirmed.isAcknowledged) {
                val params = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(confirmed.purchaseToken).build()
                acknowledgeVerifiedPurchase(params)
            }
        }
    }

    /** Play requires verified purchases to be acknowledged; retry recoverable failures.
     * The subsequent app resume also checks and acknowledges any unacknowledged receipt. */
    private fun acknowledgeVerifiedPurchase(params: AcknowledgePurchaseParams, attempt: Int = 0) {
        if (closed) return
        billing.acknowledgePurchase(params) { result ->
            if (closed) return@acknowledgePurchase
            if (result.responseCode == BillingClient.BillingResponseCode.OK) return@acknowledgePurchase
            val retryable = result.responseCode in setOf(
                BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
                BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
                BillingClient.BillingResponseCode.NETWORK_ERROR,
                BillingClient.BillingResponseCode.ERROR
            )
            if (retryable && attempt < 5) {
                scope.launch {
                    delay(1000L * (1L shl attempt))
                    acknowledgeVerifiedPurchase(params, attempt + 1)
                }
            } else {
                mutable.value = mutable.value.copy(
                    message = "Google Play 구매 확인이 지연되었습니다. 앱을 다시 열어 확인해 주세요."
                )
            }
        }
    }

    fun close() {
        closed = true
        scope.cancel()
        billing.endConnection()
    }
}
