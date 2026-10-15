package com.charles.livecaptionn

import com.charles.livecaptionn.speech.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class OfflineSpeechPipelineTest {
    @Test fun outputLimitSkipsOnlyThatResultAndNextSegmentStillCompletes() = runTest {
        val backend = FakeBackend().apply { outputLimitOnce = true }
        val results = mutableListOf<SpeechResult>()
        val processing = mutableListOf<Boolean>()
        var limited = 0
        val pipeline = OfflineSpeechPipeline(OfflineSttSession(backend, FakeGate()), results::add,
            workerContext = StandardTestDispatcher(testScheduler), onProcessing = processing::add,
            onRecognitionLimit = { limited++ })
        pipeline.feed(pcm(128000))
        runCurrent()
        pipeline.feed(pcm(128000))
        pipeline.finish()
        assertEquals(1, limited)
        assertEquals(listOf(SpeechResult("日本語2", true)), results)
        assertNull(pipeline.failureMessage)
        assertEquals(listOf(true, false, true, false), processing)
        assertEquals(1, backend.closed)
    }

    @Test fun sharedCaptureBoundaryDropsPausedPcmAndKeepsSeparateUtterances() = runTest {
        val backend = FakeBackend()
        val results = mutableListOf<SpeechResult>()
        val pipeline = OfflineSpeechPipeline(OfflineSttSession(backend, FakeGate()), results::add,
            workerContext = StandardTestDispatcher(testScheduler))
        val signals = CaptureSignalState({}, {})
        val boundary = CaptureAudioBoundary(signals) { pipeline.pauseBoundary() }
        assertTrue(boundary.prepareRead { error("No paused audio yet") })
        assertTrue(boundary.acceptRead())
        pipeline.feed(pcm(6400, 1000))
        assertTrue(boundary.prepareRead { error("No paused audio yet") })
        signals.pause(); signals.resume()
        if (boundary.acceptRead()) pipeline.feed(pcm(3200, 3000))
        var discardedPausedAudio = false
        assertTrue(boundary.prepareRead { discardedPausedAudio = true; true })
        assertTrue(discardedPausedAudio)
        assertTrue(boundary.acceptRead())
        pipeline.feed(pcm(9600, 2000))
        pipeline.finish()
        assertEquals(listOf(1000, 2000), backend.firstSamples)
        assertEquals(listOf(14400, 17600), backend.sampleCounts)
        assertEquals(2, results.size)
        assertTrue(results.all { it.isFinal })
    }

    @Test fun processingTracksNativeDecodeAndAlwaysClearsAfterFailure() = runTest {
        for (fails in listOf(false, true)) {
            val events = mutableListOf<Boolean>()
            val backend = FakeBackend().apply {
                throwOnDecode = fails
                beforeDecode = { assertEquals(listOf(true), events) }
            }
            val pipeline = OfflineSpeechPipeline(OfflineSttSession(backend, FakeGate()), {},
                workerContext = StandardTestDispatcher(testScheduler), onProcessing = events::add)
            pipeline.feed(pcm(128000))
            assertTrue(events.isEmpty())
            try { pipeline.finish() } catch (failure: IllegalStateException) { assertTrue(fails) }
            assertEquals(listOf(true, false), events)
            assertEquals(1, backend.closed)
        }
    }

    @Test fun feedDoesNotDecodeInCallerAndFinishDrainsOnlyFinalResults() = runTest {
        val backend = FakeBackend()
        val results = mutableListOf<SpeechResult>()
        val pipeline = OfflineSpeechPipeline(OfflineSttSession(backend, FakeGate()), results::add,
            workerContext = StandardTestDispatcher(testScheduler))
        assertTrue(pipeline.feed(pcm(128000)))
        assertEquals(0, backend.decoded)
        pipeline.finish()
        assertEquals(listOf(SpeechResult("日本語1", true)), results)
        assertEquals(1, backend.closed)
    }

    @Test fun slowAsrRetainsOnlyTwoNewestPendingSegments() = runTest {
        val backend = FakeBackend()
        val results = mutableListOf<SpeechResult>()
        var skipped = 0
        val pipeline = OfflineSpeechPipeline(OfflineSttSession(backend, FakeGate()), results::add,
            onSkippedAudio = { skipped++ }, workerContext = StandardTestDispatcher(testScheduler))
        repeat(10) { pipeline.feed(pcm(128000, 1000 + it)) }
        assertEquals(8, skipped)
        pipeline.finish()
        assertEquals(listOf(1008, 1009), backend.firstSamples)
        assertEquals(2, results.size)
    }

    @Test fun pauseAndResumeDoNotMixAudio() = runTest {
        val backend = FakeBackend()
        val pipeline = OfflineSpeechPipeline(OfflineSttSession(backend, FakeGate()), {},
            workerContext = StandardTestDispatcher(testScheduler))
        pipeline.feed(pcm(16000, 1000))
        pipeline.pauseBoundary()
        pipeline.feed(pcm(16000, 2000))
        pipeline.finish()
        assertEquals(listOf(1000, 2000), backend.firstSamples)
        assertEquals(listOf(24000, 24000), backend.sampleCounts)
    }

    @Test fun finishDecodesTheShortTailAfterAnEightSecondSegment() = runTest {
        val backend = FakeBackend()
        val pipeline = OfflineSpeechPipeline(OfflineSttSession(backend, FakeGate()), {},
            workerContext = StandardTestDispatcher(testScheduler))
        pipeline.feed(pcm(128000, 1000) + pcm(1600, 2000))
        pipeline.finish()
        assertEquals(listOf(1000, 2000), backend.firstSamples)
        assertEquals(listOf(136000, 9600), backend.sampleCounts)
        assertEquals(1, backend.closed)
    }

    @Test fun silenceAndRejectedSpeechCannotHallucinateCaptions() = runTest {
        val backend = FakeBackend()
        val pipeline = OfflineSpeechPipeline(OfflineSttSession(backend, FakeGate(false)), {},
            workerContext = StandardTestDispatcher(testScheduler))
        repeat(100) { pipeline.feed(pcm(1600, 0)); pipeline.feed(pcm(1600, 5000)) }
        pipeline.finish()
        assertEquals(0, backend.decoded)
    }

    @Test fun decodeFailureStopsMoreWorkAndIsReportedOnceWhileCapturing() = runTest {
        val backend = FakeBackend().apply { throwOnDecode = true }
        val failures = mutableListOf<String>()
        val pipeline = OfflineSpeechPipeline(OfflineSttSession(backend, FakeGate()), {},
            onFailure = failures::add, workerContext = StandardTestDispatcher(testScheduler))
        pipeline.feed(pcm(128000))
        runCurrent()
        pipeline.feed(pcm(128000))
        runCurrent()
        assertNotNull(pipeline.failureMessage)
        assertEquals(1, failures.size)
        try { pipeline.finish(); fail("Stored decode failure must reach stop") } catch (_: IllegalStateException) {}
        assertEquals(1, backend.decoded)
        assertEquals(1, backend.closed)
    }

    @Test fun finishClosesOnceAndNoFeedAfterItStartsIsAccepted() = runTest {
        val backend = FakeBackend()
        val gate = FakeGate()
        val pipeline = OfflineSpeechPipeline(OfflineSttSession(backend, gate), {},
            workerContext = StandardTestDispatcher(testScheduler))
        pipeline.feed(pcm(16000))
        pipeline.finish()
        pipeline.finish()
        assertFalse(pipeline.feed(pcm(128000)))
        assertEquals(1, backend.decoded)
        assertEquals(1, backend.closed)
        assertEquals(1, gate.closed)
    }

    @Test fun cancelDuringFinishStillWaitsForNativeDecodeBeforeClose() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val backend = FakeBackend().apply { beforeDecode = { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        val pipeline = OfflineSpeechPipeline(OfflineSttSession(backend, FakeGate()), {})
        pipeline.feed(pcm(128000))
        assertTrue(started.await(3, TimeUnit.SECONDS))
        val finish = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { pipeline.finish() }
        finish.cancel()
        assertEquals(0, backend.closed)
        release.countDown()
        finish.join()
        assertEquals(1, backend.closed)
        assertEquals(0, backend.closedDuringDecode)
    }

    @Test fun failureWhileDrainingDeliberateStopDoesNotRaiseCaptureError() = runTest {
        val backend = FakeBackend().apply { throwOnDecode = true }
        val failures = mutableListOf<String>()
        val pipeline = OfflineSpeechPipeline(OfflineSttSession(backend, FakeGate()), {},
            onFailure = failures::add, workerContext = StandardTestDispatcher(testScheduler))
        pipeline.feed(pcm(128000))
        try { pipeline.finish(); fail("A failed final decode must reach the stop caller") } catch (_: IllegalStateException) {}
        assertTrue(failures.isEmpty())
        assertEquals(1, backend.closed)
        try { pipeline.finish(); fail("Repeated stop must retain failure") } catch (_: IllegalStateException) {}
        assertEquals(1, backend.closed)
    }

    @Test fun emptyNativeResultDoesNotCreateCaption() = runTest {
        val backend = FakeBackend().apply { blankResult = true }
        val results = mutableListOf<SpeechResult>()
        val pipeline = OfflineSpeechPipeline(OfflineSttSession(backend, FakeGate()), results::add,
            workerContext = StandardTestDispatcher(testScheduler))
        pipeline.feed(pcm(128000))
        pipeline.finish()
        assertTrue(results.isEmpty())
        assertEquals(1, backend.decoded)
    }

    @Test fun concurrentFinishesShareDrainAndCloseExactlyOnce() = runTest {
        val backend = FakeBackend()
        val pipeline = OfflineSpeechPipeline(OfflineSttSession(backend, FakeGate()), {},
            workerContext = StandardTestDispatcher(testScheduler))
        pipeline.feed(pcm(16000))
        coroutineScope { launch { pipeline.finish() }; launch { pipeline.finish() } }
        assertEquals(1, backend.closed)
        assertEquals(1, backend.decoded)
    }

    @Test fun fatalMessageIsPublishedBeforeErrorStatusAndEarlierFailureIsPreserved() {
        var message: String? = null
        val events = mutableListOf<RecognitionStatus>()
        val signals = CaptureSignalState({ message = it }, onStatus = { status ->
            if (status == RecognitionStatus.ERROR) assertEquals("capture failed", message)
            events.add(status)
        })
        signals.fail("capture failed")
        signals.fail("tail failed")
        signals.hint(null)
        signals.listening()
        signals.pause()
        signals.resume()
        assertEquals("capture failed", signals.stopped())
        assertEquals("capture failed", message)
        assertTrue(events.all { it == RecognitionStatus.ERROR })
    }

    @Test fun successClearAndFatalPublicationShareOneLock() {
        val clearEntered = CountDownLatch(1)
        val releaseClear = CountDownLatch(1)
        val failureDone = CountDownLatch(1)
        var message: String? = "initial"
        val signals = CaptureSignalState({ value ->
            if (value == null) { clearEntered.countDown(); check(releaseClear.await(3, TimeUnit.SECONDS)) }
            message = value
        }, {})
        val clearing = Thread { signals.hint(null) }.apply { start() }
        assertTrue(clearEntered.await(2, TimeUnit.SECONDS))
        val failing = Thread { signals.fail("decoder failed"); failureDone.countDown() }.apply { start() }
        val completedBeforeClear = failureDone.await(100, TimeUnit.MILLISECONDS)
        releaseClear.countDown()
        clearing.join(2000); failing.join(2000)
        assertFalse(completedBeforeClear)
        assertEquals("decoder failed", message)
    }

    @Test fun resumeDuringPausedDelayMustDiscardUnreadPcmBeforeCaptureContinues() {
        val signals = CaptureSignalState({}, {})
        val boundary = ResumeAudioBoundary(signals.resumeGeneration)
        signals.pause()
        // Audio continues entering the recorder after a paused-loop drain.
        val unread = java.util.ArrayDeque(listOf(3200, 6400, 0))
        signals.resume()
        assertFalse(boundary.isCurrent(signals.resumeGeneration))
        var discarded = 0
        assertTrue(boundary.ensureCurrent(signals.resumeGeneration) {
            while (true) {
                val read = unread.removeFirst()
                if (read == 0) break
                discarded += read
            }
            true
        })
        assertEquals(9600, discarded)
        assertTrue(boundary.isCurrent(signals.resumeGeneration))
    }

    @Test fun incompleteResumeDrainCannotMarkStaleRecorderCurrent() {
        val boundary = ResumeAudioBoundary(0)
        assertFalse(boundary.ensureCurrent(1) { false })
        assertFalse(boundary.isCurrent(1))
        assertTrue(boundary.ensureCurrent(1) { true })
        assertTrue(boundary.isCurrent(1))
        var redrained = false
        boundary.ensureCurrent(1) { redrained = true; true }
        assertFalse(redrained)
    }

    @Test fun pauseAndResumeDuringInFlightReadInvalidateThatRead() {
        val signals = CaptureSignalState({}, {})
        val boundary = ResumeAudioBoundary(signals.resumeGeneration)
        val generationWhenReadStarted = signals.resumeGeneration
        signals.pause(); signals.resume()
        assertNotEquals(generationWhenReadStarted, signals.resumeGeneration)
        assertFalse(boundary.isCurrent(signals.resumeGeneration))
    }

    private class FakeGate(private val speech: Boolean = true) : SpeechGate {
        var closed = 0
        override fun isSpeech(pcm: ByteArray, length: Int) = speech
        override fun reset() {}
        override fun close() { closed++ }
    }
    private class FakeBackend : OfflineRecognizerBackend {
        var decoded = 0
        var closed = 0
        var inDecode = false
        var closedDuringDecode = 0
        var throwOnDecode = false
        var blankResult = false
        var outputLimitOnce = false
        var beforeDecode: () -> Unit = {}
        val firstSamples = mutableListOf<Int>()
        val sampleCounts = mutableListOf<Int>()
        private var samples = FloatArray(0)
        override fun createStream() = object : OfflineRecognizerStream {
            override fun acceptWaveform(samples: FloatArray, sampleRate: Int) { this@FakeBackend.samples = samples }
            override fun close() {}
        }
        override fun decode(stream: OfflineRecognizerStream) {
            inDecode = true
            try {
                decoded++
                beforeDecode()
                if (throwOnDecode) error("decode failed")
                firstSamples.add((samples.first() * 32768).toInt())
                sampleCounts.add(samples.size)
            } finally { inDecode = false }
        }
        override fun result(stream: OfflineRecognizerStream): String {
            if (outputLimitOnce && decoded == 1) throw RecognitionOutputLimitException()
            return if (blankResult) "" else "日本語$decoded"
        }
        override fun close() { if (inDecode) closedDuringDecode++; closed++ }
    }
    private fun pcm(samples: Int, amplitude: Int = 1000) = ByteArray(samples * 2).also { bytes ->
        repeat(samples) { index -> bytes[index * 2] = amplitude.toByte(); bytes[index * 2 + 1] = (amplitude shr 8).toByte() }
    }
}
