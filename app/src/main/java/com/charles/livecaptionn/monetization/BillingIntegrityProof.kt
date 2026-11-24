package com.charles.livecaptionn.monetization

import android.content.Context
import android.os.SystemClock
import com.charles.livecaptionn.BuildConfig
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Integrity is used only by the optional purchase path. No speech or caption data enters it. */
internal class BillingIntegrityProof(context: Context) {
    private val manager by lazy { IntegrityManagerFactory.createStandard(context) }
    private val mutex = Mutex()
    private var provider: StandardIntegrityManager.StandardIntegrityTokenProvider? = null
    private var lastPrepareAt: Long? = null

    suspend fun tokenFor(method: String, body: ByteArray): String? {
        if (BuildConfig.DEBUG || BuildConfig.PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER <= 0L) return null
        return mutex.withLock {
            try {
                withTimeoutOrNull(55_000L) {
                    val current = provider ?: run {
                        val now = SystemClock.elapsedRealtime()
                        // Bound warm-up retries; no automatic loop on missing Play services.
                        if (lastPrepareAt?.let { now - it < 15_000L } == true) return@withTimeoutOrNull null
                        lastPrepareAt = now
                        prepare().also { provider = it }
                    }
                    request(current, billingRequestHash(method, body))
                        .takeIf { it.length in 32..16_384 && it.all { ch -> ch in '!'..'~' } }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A provider can expire. A later explicit refresh prepares it again.
                provider = null
                null
            }
        }
    }

    private suspend fun prepare(): StandardIntegrityManager.StandardIntegrityTokenProvider =
        suspendCancellableCoroutine { continuation ->
            manager.prepareIntegrityToken(
                StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                    .setCloudProjectNumber(BuildConfig.PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER)
                    .build()
            ).addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
                .addOnCanceledListener { continuation.cancel() }
        }

    private suspend fun request(
        current: StandardIntegrityManager.StandardIntegrityTokenProvider,
        hash: String
    ): String = suspendCancellableCoroutine { continuation ->
        current.request(
            StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                .setRequestHash(hash).build()
        ).addOnSuccessListener { if (continuation.isActive) continuation.resume(it.token()) }
            .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
            .addOnCanceledListener { continuation.cancel() }
    }
}

internal fun billingRequestHash(method: String, body: ByteArray): String {
    require(method == "GET" || method == "POST")
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("livetranscribe-billing-v1\n$method\n/verify\n".toByteArray(Charsets.UTF_8))
    digest.update(body)
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
