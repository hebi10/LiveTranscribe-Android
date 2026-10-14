package com.charles.livecaptionn

import com.charles.livecaptionn.service.stopSpeechEngineSafely
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SpeechEngineCleanupTest {
    @Test fun nativeDrainFailureAfterReleaseIsReportedWithoutEscapingDestroyJob() = runTest {
        var released = false
        var reported: Exception? = null
        val failure = IllegalStateException("final recognition failed")
        stopSpeechEngineSafely({ released = true; throw failure }) { reported = it }
        assertTrue(released)
        assertSame(failure, reported)
    }

    @Test fun successfulStopDoesNotReportFailure() = runTest {
        var stopped = false
        var reported = false
        stopSpeechEngineSafely({ stopped = true }) { reported = true }
        assertTrue(stopped)
        assertFalse(reported)
    }

    @Test fun coroutineCancellationRemainsCancellation() = runTest {
        var reported = false
        var cancelled = false
        try {
            stopSpeechEngineSafely({ throw CancellationException() }) { reported = true }
        } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertFalse(reported)
    }
}
