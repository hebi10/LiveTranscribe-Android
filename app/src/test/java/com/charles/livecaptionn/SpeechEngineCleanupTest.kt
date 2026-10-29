package com.charles.livecaptionn

import com.charles.livecaptionn.service.stopSpeechEngineSafely
import com.charles.livecaptionn.service.finishSpeechInference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechEngineCleanupTest {
    @Test fun slowInferenceShowsWaitingNoticeWithoutReleasingNativeResources() = runTest {
        val speechStopped = CompletableDeferred<Unit>()
        val detectionJoined = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val finishing = async {
            finishSpeechInference(
                stopSpeech = { speechStopped.await(); events += "speech stopped" },
                joinLanguageDetection = { detectionJoined.await(); events += "detection joined" },
                releaseLanguageIdentifier = { events += "released" },
                onWaiting = { events += "waiting" },
                onFailure = { fail("Unexpected failure: $it") }
            )
        }
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(listOf("waiting"), events)
        assertFalse(finishing.isCompleted)

        speechStopped.complete(Unit)
        runCurrent()
        assertEquals(listOf("waiting", "speech stopped"), events)
        detectionJoined.complete(Unit)
        finishing.await()
        assertEquals(listOf("waiting", "speech stopped", "detection joined", "released"), events)
    }

    @Test fun failedSpeechDrainStillJoinsDetectionBeforeRelease() = runTest {
        val events = mutableListOf<String>()
        finishSpeechInference(
            stopSpeech = { throw IllegalStateException("final recognition failed") },
            joinLanguageDetection = { events += "detection joined" },
            releaseLanguageIdentifier = { events += "released" },
            onWaiting = { fail("Immediate shutdown must not show wait notice") },
            onFailure = { events += "failure reported" }
        )
        assertEquals(listOf("failure reported", "detection joined", "released"), events)
    }

    @Test fun languageReleaseFailureIsReportedWithoutAbandoningCleanup() = runTest {
        val releaseFailure = IllegalStateException("language identifier release failed")
        var reported: Exception? = null
        finishSpeechInference(
            stopSpeech = {},
            joinLanguageDetection = {},
            releaseLanguageIdentifier = { throw releaseFailure },
            onWaiting = { fail("Immediate shutdown must not show wait notice") },
            onFailure = { reported = it }
        )
        assertSame(releaseFailure, reported)
    }

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
