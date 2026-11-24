package com.charles.livecaptionn.monetization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BillingIntegrityHashTest {
    @Test fun requestHashMatchesServerVectors() {
        val body = """{"productId":"remove_ads","purchaseToken":"Token_1234567890"}""".toByteArray(Charsets.UTF_8)
        assertEquals("5dc10413933372f58ee7591b602bb07ea2a9bf05923bf11a70336e307b39253a",
            billingRequestHash("GET", byteArrayOf()))
        assertEquals("92db4bf7048158201a89a2b34ca6a54eec3663c59569dae12225e0df1673f0aa",
            billingRequestHash("POST", body))
        assertNotEquals(billingRequestHash("POST", body), billingRequestHash("POST", body + byteArrayOf(32)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedMethodIsRejected() {
        billingRequestHash("DELETE", byteArrayOf())
    }
}
