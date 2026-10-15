package com.charles.livecaptionn.models

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

internal interface ModelDownloadTransport { fun newCall(url: String, offset: Long): ModelDownloadCall }
internal interface ModelDownloadCall { fun execute(): ModelDownloadResponse; fun cancel() }
internal interface ModelDownloadResponse : Closeable {
    val statusCode: Int
    val contentRange: String?
    val contentLength: Long?
    val body: InputStream
}

/**
 * The only model-network boundary: fixed catalog GETs and HTTPS CDN redirects.
 * No speech, transcripts, account tokens, cookies, request bodies, or app logs enter it.
 */
internal class HttpsModelDownloadTransport(
    private val allowedUrls: Set<String>,
    private val connect: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection }
) : ModelDownloadTransport {
    override fun newCall(url: String, offset: Long): ModelDownloadCall {
        require(url in allowedUrls) { "URL is not in the pinned model catalog" }
        require(offset >= 0)
        return HttpsCall(url, offset, connect)
    }

    private class HttpsCall(
        private val initialUrl: String,
        private val offset: Long,
        private val connect: (URL) -> HttpsURLConnection
    ) : ModelDownloadCall {
        private val cancelled = AtomicBoolean(false)
        private val connection = AtomicReference<HttpsURLConnection?>()
        private val input = AtomicReference<InputStream?>()

        override fun execute(): ModelDownloadResponse {
            var url = URL(initialUrl)
            repeat(MAX_REDIRECTS + 1) { redirect ->
                checkUrl(url)
                if (cancelled.get()) throw IOException("Download cancelled")
                val current = connect(url)
                connection.set(current)
                current.instanceFollowRedirects = false
                current.requestMethod = "GET"
                current.doOutput = false
                current.useCaches = false
                current.connectTimeout = CONNECT_TIMEOUT_MS
                current.readTimeout = READ_TIMEOUT_MS
                current.setRequestProperty("Accept-Encoding", "identity")
                // These are the only application headers; fresh connections never forward credentials.
                if (offset > 0) current.setRequestProperty("Range", "bytes=$offset-")
                if (cancelled.get()) { current.disconnect(); throw IOException("Download cancelled") }
                val status = current.responseCode
                if (status in REDIRECT_CODES) {
                    val location = current.getHeaderField("Location") ?: throw IOException("Missing download redirect")
                    current.disconnect()
                    connection.compareAndSet(current, null)
                    if (redirect == MAX_REDIRECTS) throw IOException("Too many model redirects")
                    url = URL(url, location)
                } else {
                    val body = if (status == 200 || status == 206) current.inputStream
                        else current.errorStream ?: java.io.ByteArrayInputStream(byteArrayOf())
                    input.set(body)
                    if (cancelled.get()) { cancel(); throw IOException("Download cancelled") }
                    return object : ModelDownloadResponse {
                        override val statusCode = status
                        override val contentRange = current.getHeaderField("Content-Range")
                        override val contentLength = current.getHeaderField("Content-Length")?.toLongOrNull()
                        override val body = body
                        override fun close() { cancel() }
                    }
                }
            }
            throw IOException("Too many model redirects")
        }

        override fun cancel() {
            cancelled.set(true)
            // Disconnect first so closing a blocked stream does not wait for a full response.
            runCatching { connection.getAndSet(null)?.disconnect() }
            runCatching { input.getAndSet(null)?.close() }
        }

        private fun checkUrl(url: URL) {
            if (url.protocol != "https" || url.host.isBlank() || url.userInfo != null || url.ref != null) {
                throw IOException("Unsafe model download redirect")
            }
        }
    }

    companion object {
        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}
