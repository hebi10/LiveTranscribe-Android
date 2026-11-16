package com.charles.livecaptionn.monetization

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import com.charles.livecaptionn.BuildConfig
import com.google.android.libraries.ads.mobile.sdk.MobileAds
import com.google.android.libraries.ads.mobile.sdk.banner.AdSize
import com.google.android.libraries.ads.mobile.sdk.banner.AdView
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAd
import com.google.android.libraries.ads.mobile.sdk.banner.BannerAdRequest
import com.google.android.libraries.ads.mobile.sdk.common.AdLoadCallback
import com.google.android.libraries.ads.mobile.sdk.common.LoadAdError
import com.google.android.libraries.ads.mobile.sdk.initialization.InitializationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.util.concurrent.atomic.AtomicBoolean

private val initialized = AtomicBoolean(false)

/** A test or configured production banner is permitted only on idle home. */
@Composable
internal fun HomeAdBanner(visible: Boolean) {
    if (!visible || LocalInspectionMode.current) return
    val context = LocalContext.current
    val view = remember(context) { AdView(context) }
    DisposableEffect(view) {
        onDispose { view.destroy() }
    }
    LaunchedEffect(view) {
        if (!initializeAds(context)) return@LaunchedEffect
        val dm = context.resources.displayMetrics
        val widthDp = ((dm.widthPixels / dm.density).toInt() - 32).coerceAtLeast(1)
        val size = AdSize.getLargeAnchoredAdaptiveBannerAdSize(context, widthDp)
        val request = BannerAdRequest.Builder(BuildConfig.GMA_BANNER_UNIT_ID, size).build()
        view.loadAd(request, object : AdLoadCallback<BannerAd> {
            override fun onAdLoaded(ad: BannerAd) = Unit
            override fun onAdFailedToLoad(adError: LoadAdError) = Unit
        })
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth())
}

private suspend fun initializeAds(context: Context): Boolean {
    if (!BuildConfig.GMA_ADS_ENABLED) return false
    if (initialized.get()) return true
    return try {
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                MobileAds.initialize(context.applicationContext,
                    InitializationConfig.Builder(BuildConfig.GMA_APP_ID).build()
                ) {
                    initialized.set(true)
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
        true
    } catch (_: Exception) {
        false
    }
}
