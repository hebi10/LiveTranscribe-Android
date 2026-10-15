package com.charles.livecaptionn.speech

import java.util.ArrayDeque
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureAudioBoundaryTest {
    @Test fun typedCaptureHintNeverClearsOrReplacesFatalError() {
        var error: String? = null
        var hint: String? = null
        val signals = CaptureSignalState({ error = it }, {}, { hint = it })
        signals.hint("play supported audio")
        assertNull(error)
        assertEquals("play supported audio", hint)
        signals.hint(null)
        assertNull(hint)
        signals.fail("capture failed")
        signals.hint("late hint")
        signals.hint(null)
        signals.ready()
        assertEquals("capture failed", error)
        assertNull(hint)
    }

    @Test fun pauseAndResumeAfterRecorderStartStillDrainBeforeTheFirstRead() {
        val signals = CaptureSignalState({}, {})
        val resumeGenerationAtStart = signals.resumeGeneration
        signals.pause()
        signals.resume()
        // The capture loop is only initialized after AudioRecord.startRecording.
        val boundary = CaptureAudioBoundary(signals, resumeGenerationAtStart) {}
        var drained = false
        assertTrue(boundary.prepareRead { drained = true; true })
        assertTrue(drained)
    }

    @Test fun pauseAndResumeDrainUnreadAudioBeforeAnyLanguageCanAcceptNewPcm() {
        val signals = CaptureSignalState({}, {})
        var utteranceBoundaries = 0
        val boundary = CaptureAudioBoundary(signals) { utteranceBoundaries++ }
        val unread = ArrayDeque<Int>()
        val discarded = mutableListOf<Int>()
        val drain = { while (unread.isNotEmpty()) discarded += unread.removeFirst(); true }
        signals.pause()
        unread += 1
        assertFalse(boundary.prepareRead(drain))
        // More audio arrives during the capture loop's paused delay.
        unread += 2
        signals.resume()
        assertTrue(boundary.prepareRead(drain))
        assertTrue(boundary.acceptRead())
        assertEquals(listOf(1, 2), discarded)
        assertEquals(1, utteranceBoundaries)
    }

    @Test fun pauseAndResumeDuringBlockingReadRejectsPcmAndSeparatesUtterances() {
        val signals = CaptureSignalState({}, {})
        var utteranceBoundaries = 0
        val boundary = CaptureAudioBoundary(signals) { utteranceBoundaries++ }
        assertTrue(boundary.prepareRead { error("No stale PCM before pause") })
        signals.pause()
        signals.resume()
        assertFalse(boundary.acceptRead())
        assertEquals(1, utteranceBoundaries)
        var drained = false
        assertTrue(boundary.prepareRead { drained = true; true })
        assertTrue(drained)
        assertTrue(boundary.acceptRead())
        assertEquals(1, utteranceBoundaries)
    }

    @Test fun incompleteResumeDrainCannotFeedStaleAudio() {
        val signals = CaptureSignalState({}, {})
        val boundary = CaptureAudioBoundary(signals) {}
        signals.pause()
        signals.resume()
        assertFalse(boundary.prepareRead { false })
        assertFalse(boundary.acceptRead())
        assertTrue(boundary.prepareRead { true })
        assertTrue(boundary.acceptRead())
    }

    @Test fun pauseDuringBlockingReadRejectsPcmBeforeTheNextPausedLoop() {
        val signals = CaptureSignalState({}, {})
        var utteranceBoundaries = 0
        val boundary = CaptureAudioBoundary(signals) { utteranceBoundaries++ }
        assertTrue(boundary.prepareRead { true })
        signals.pause()
        assertFalse(boundary.acceptRead())
        assertEquals(1, utteranceBoundaries)
        assertFalse(boundary.prepareRead { true })
        assertEquals(1, utteranceBoundaries)
    }

    @Test fun pausedInitializationWaitsForResumeBeforeStartingAudio() = runTest {
        val signals = CaptureSignalState({}, {})
        val lifecycle = CaptureLifecycle(this)
        var starts = 0
        signals.pause()
        try {
            lifecycle.start { owner ->
                signals.startWhenResumed { owner.whenActive { starts++ } }
                awaitCancellation()
            }
            runCurrent()
            assertEquals(0, starts)
            signals.resume()
            testScheduler.advanceTimeBy(120)
            runCurrent()
            assertEquals(1, starts)
        } finally {
            lifecycle.stop()
        }
    }

    @Test fun stoppingPausedInitializationCannotStartAudioAfterResume() = runTest {
        val signals = CaptureSignalState({}, {})
        val lifecycle = CaptureLifecycle(this)
        var starts = 0
        var released = false
        signals.pause()
        lifecycle.start { owner ->
            try {
                signals.startWhenResumed { owner.whenActive { starts++ } }
            } finally { released = true }
        }
        runCurrent()
        lifecycle.stop()
        signals.resume()
        testScheduler.advanceTimeBy(120)
        runCurrent()
        assertEquals(0, starts)
        assertTrue(released)
    }
}
