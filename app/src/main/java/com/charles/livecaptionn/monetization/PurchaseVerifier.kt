package com.charles.livecaptionn.monetization

import com.charles.livecaptionn.BuildConfig
import java.io.InputStream
import java.net.URL
import kotlinx.coroutines.CancellationException
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Only a Play purchase token and fixed product ID leave the device, over configured TLS.
 * No audio, transcripts, language metadata or device identifiers are accepted here.
 * A backend must validate subscriptionsv2, expiry, and refunds before returning active.
 */
internal class PurchaseVerifier {
    val configured: Boolean get() = BuildConfig.BILLING_VERIFIER_URL.matches(
        Regex("""https://[A-Za-z0-9][A-Za-z0-9.-]*(?:/[A-Za-z0-9/_-]*)?""")
    ) && !BuildConfig.BILLING_VERIFIER_URL.contains("localhost", ignoreCase = true)

    /** null = connection or verification unavailable, never treat this as a free subscription. */
    suspend fun verify(token: String): Boolean? = withContext(Dispatchers.IO) {
        if (!configured || token.isBlank() || token.length > 1500) return@withContext null
        var connection: HttpsURLConnection? = null
        try {
            val address = URL(BuildConfig.BILLING_VERIFIER_URL)
            connection = address.openConnection() as HttpsURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 6000
            connection.readTimeout = 9000
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Cache-Control", "no-store")
            val body = JSONObject()
                .put("productId", AdPlacementPolicy.PRODUCT_ID)
                .put("purchaseToken", token)
                .toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode != 200) return@withContext null
            val response = connection.inputStream.use { readBoundedVerifierResponse(it) }
                ?: return@withContext null
            val data = JSONObject(response)
            if (data.optString("productId") != AdPlacementPolicy.PRODUCT_ID) return@withContext null
            data.opt("active") as? Boolean
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}

/** Limit bytes before decoding; reject rather than truncate oversized responses. */
internal fun readBoundedVerifierResponse(input: InputStream, limit: Int = 2048): String? {
    require(limit in 1..8192)
    val bytes = ByteArray(limit + 1)
    var offset = 0
    while (offset < bytes.size) {
        val count = input.read(bytes, offset, bytes.size - offset)
        if (count < 0) break
        if (count == 0) return null
        offset += count
    }
    if (offset > limit) return null
    return bytes.copyOf(offset).toString(Charsets.UTF_8)
}
