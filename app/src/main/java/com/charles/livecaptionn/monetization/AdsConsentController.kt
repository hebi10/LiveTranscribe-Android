package com.charles.livecaptionn.monetization

import android.app.Activity
import android.content.Context
import com.charles.livecaptionn.BuildConfig
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** UMP updates consent each launch; never infer it from saved app preferences. */
internal class AdsConsentController(context: Context) {
    private val information = UserMessagingPlatform.getConsentInformation(context.applicationContext)
    private val mutable = MutableStateFlow(ConsentUiState())
    val state: StateFlow<ConsentUiState> = mutable.asStateFlow()
    private var refreshed = false

    fun refresh(activity: Activity) {
        if (!BuildConfig.GMA_ADS_ENABLED || refreshed) return
        refreshed = true
        information.requestConsentInfoUpdate(
            activity, ConsentRequestParameters.Builder().build(),
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    updateState()
                }
            },
            { updateState() }
        )
    }

    fun showPrivacyOptions(activity: Activity) {
        if (mutable.value.privacyOptionsRequired) {
            UserMessagingPlatform.showPrivacyOptionsForm(activity) { updateState() }
        }
    }

    private fun updateState() {
        mutable.value = ConsentUiState(
            adsAllowed = information.canRequestAds(),
            privacyOptionsRequired = information.privacyOptionsRequirementStatus ==
                ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        )
    }
}
