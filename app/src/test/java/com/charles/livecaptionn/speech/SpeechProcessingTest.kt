package com.charles.livecaptionn.speech

import org.junit.Assert.*
import org.junit.Test

class SpeechProcessingTest {
    @Test fun nativeResultIsReturnedOnlyAfterBusyIsCleared() {
        val events = mutableListOf<Boolean>()
        val result = withRecognitionProgress(events::add) {
            assertEquals(listOf(true), events)
            "final speech"
        }
        assertEquals("final speech", result)
        assertEquals(listOf(true, false), events)
    }

    @Test fun nativeFailureCannotLeaveProcessingBusy() {
        val events = mutableListOf<Boolean>()
        val failure = IllegalStateException("native failed")
        val thrown = assertThrows(IllegalStateException::class.java) {
            withRecognitionProgress(events::add) {
                assertEquals(listOf(true), events)
                throw failure
            }
        }
        assertSame(failure, thrown)
        assertEquals(listOf(true, false), events)
    }
}
