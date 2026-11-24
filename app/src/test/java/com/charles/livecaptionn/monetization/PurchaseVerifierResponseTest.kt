package com.charles.livecaptionn.monetization

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PurchaseVerifierResponseTest {
    @Test fun acceptsExactLimitButRejectsOversizedResponse() {
        assertEquals("1234", readBoundedVerifierResponse(ByteArrayInputStream("1234".toByteArray()), 4))
        assertNull(readBoundedVerifierResponse(ByteArrayInputStream("12345".toByteArray()), 4))
    }

    @Test fun limitCountsBytesNotCharacters() {
        val korean = "한글".toByteArray(Charsets.UTF_8)
        assertEquals("한글", readBoundedVerifierResponse(ByteArrayInputStream(korean), 6))
        assertNull(readBoundedVerifierResponse(ByteArrayInputStream(korean), 5))
    }
}
