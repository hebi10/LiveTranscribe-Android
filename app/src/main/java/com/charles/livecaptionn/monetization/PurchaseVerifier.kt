package com.charles.livecaptionn.monetization

import com.charles.livecaptionn.BuildConfig
import java.net.URL
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
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode != 200) return@withContext null
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8)
                .use { reader -> reader.readText().take(2048) }
            val data = JSONObject(response)
            if (data.optString("productId") != AdPlacementPolicy.PRODUCT_ID ||
                !data.has("active")) return@withContext null
            data.getBoolean("active")
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
