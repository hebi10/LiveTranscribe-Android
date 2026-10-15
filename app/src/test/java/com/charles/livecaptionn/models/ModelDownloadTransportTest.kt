package com.charles.livecaptionn.models

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URL
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import org.junit.Assert.*
import org.junit.Test

class ModelDownloadTransportTest {
    private val catalogUrl = "https://huggingface.co/official/model/resolve/0123456789abcdef0123456789abcdef01234567/model.bin"

    @Test fun onlyCatalogUrlsCanCreateRequests() {
        var opened = false
        val transport = HttpsModelDownloadTransport(setOf(catalogUrl)) { opened = true; error("No connection expected") }
        assertThrows(IllegalArgumentException::class.java) { transport.newCall("https://other.invalid/model.bin", 0) }
        assertFalse(opened)
    }

    @Test fun httpsRedirectUsesFreshGetWithoutCredentialsAndPreservesRange() {
        val first = Connection(URL(catalogUrl), 302, mapOf("Location" to "https://cdn.huggingface.co/object?download=1"))
        val next = Connection(URL("https://cdn.huggingface.co/object?download=1"), 206,
            mapOf("Content-Range" to "bytes 7-9/10", "Content-Length" to "3"), byteArrayOf(1, 2, 3))
        val opened = mutableListOf<Connection>()
        val transport = HttpsModelDownloadTransport(setOf(catalogUrl)) { url ->
            (if (url.toString() == catalogUrl) first else next).also { opened.add(it) }
        }
        transport.newCall(catalogUrl, 7).execute().use { response ->
            assertEquals(206, response.statusCode)
            assertEquals("bytes 7-9/10", response.contentRange)
            assertEquals(3L, response.contentLength)
            assertArrayEquals(byteArrayOf(1, 2, 3), response.body.readBytes())
        }
        assertEquals(2, opened.size)
        opened.forEach { connection ->
            assertEquals("GET", connection.requestMethod)
            assertFalse(connection.doOutput)
            assertFalse(connection.instanceFollowRedirects)
            assertEquals("bytes=7-", connection.getRequestProperty("Range"))
            assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
            assertNull(connection.getRequestProperty("Authorization"))
            assertNull(connection.getRequestProperty("Cookie"))
            assertTrue(connection.connectTimeout in 1..15_000)
            assertTrue(connection.readTimeout in 1..30_000)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun downgradeAndCredentialBearingRedirectsAreRejectedBeforeOpeningTarget() {
        listOf("http://cdn.invalid/object", "https://dummy:dummy@cdn.invalid/object", "file:///tmp/model").forEach { target ->
            var opened = 0
            val transport = HttpsModelDownloadTransport(setOf(catalogUrl)) { url ->
                opened++
                Connection(url, 302, mapOf("Location" to target))
            }
            val call = transport.newCall(catalogUrl, 0)
            try { assertThrows(IOException::class.java) { call.execute() } } finally { call.cancel() }
            assertEquals(1, opened)
        }
    }

    @Test fun redirectLoopIsBoundedAndCancelledCallCannotOpenConnections() {
        var opened = 0
        val transport = HttpsModelDownloadTransport(setOf(catalogUrl)) { url ->
            opened++
            Connection(url, 307, mapOf("Location" to catalogUrl))
        }
        val call = transport.newCall(catalogUrl, 0)
        try { assertThrows(IOException::class.java) { call.execute() } } finally { call.cancel() }
        assertEquals(6, opened)
        val cancelled = transport.newCall(catalogUrl, 0)
        cancelled.cancel()
        assertThrows(IOException::class.java) { cancelled.execute() }
        assertEquals(6, opened)
    }

    private class Connection(
        url: URL,
        private val response: Int,
        private val headers: Map<String, String>,
        data: ByteArray = byteArrayOf()
    ) : HttpsURLConnection(url) {
        private val stream = ByteArrayInputStream(data)
        var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true; stream.close() }
        override fun usingProxy() = false
        override fun getResponseCode() = response
        override fun getHeaderField(name: String) = headers[name]
        override fun getInputStream() = stream
        override fun getCipherSuite() = "TLS_TEST"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
    }
}
