package com.charles.livecaptionn

import com.charles.livecaptionn.speech.SpeechPcmSegmenter
import org.junit.Assert.*
import org.junit.Test

class SpeechPcmSegmenterTest {
    @Test fun incrementalSnapshotsKeepTheirWindowAndNeverConsumeFinalAudio() {
        val segmenter = SpeechPcmSegmenter()
        segmenter.feed(pcm(32000, 1000))
        val first = segmenter.previewSegment()!!
        assertEquals(64000, first.pcm.size)
        assertFalse(first.isFinal)
        segmenter.feed(pcm(32000, 2000))
        val second = segmenter.previewSegment()!!
        assertEquals(first.id, second.id)
        assertEquals(128000, second.pcm.size)
        val final = segmenter.pauseBoundarySegment()!!
        assertEquals(first.id, final.id)
        assertTrue(final.isFinal)
        assertArrayEquals(pcm(32000, 1000) + pcm(32000, 2000), final.pcm)
        segmenter.feed(pcm(32000, 3000))
        assertTrue(segmenter.previewSegment()!!.id > final.id)
    }

    @Test fun previewsAreThrottledByNewVoicedAudioAndDoNotRepeatDuringSilence() {
        val segmenter = SpeechPcmSegmenter()
        segmenter.feed(pcm(16000, 1000))
        assertNull(segmenter.previewSegment())
        segmenter.feed(pcm(16000, 1000))
        assertNotNull(segmenter.previewSegment())
        assertNull(segmenter.previewSegment())
        segmenter.feed(pcm(8000, 0))
        assertNull(segmenter.previewSegment())
    }
    @Test fun continuousMinuteProducesBoundedSegmentsWithoutDuplicatedAudio() {
        val segmenter = SpeechPcmSegmenter()
        val outputs = (1..600).flatMap { segmenter.feed(pcm(1600, 1000)) }
        assertEquals(7, outputs.size)
        assertTrue(outputs.all { it.size == 256000 })
        assertEquals(1_920_000, outputs.sumOf { it.size } + segmenter.finish()!!.size)
        assertNull(segmenter.finish())
    }

    @Test fun oneSecondSilenceFlushesVoiceButSilenceAloneDoesNotDecode() {
        val segmenter = SpeechPcmSegmenter()
        repeat(100) { assertTrue(segmenter.feed(pcm(1600, 0)).isEmpty()) }
        repeat(10) { assertTrue(segmenter.feed(pcm(1600, 1000)).isEmpty()) }
        repeat(9) { assertTrue(segmenter.feed(pcm(1600, 0)).isEmpty()) }
        val final = segmenter.feed(pcm(1600, 0)).single()
        assertEquals(16000 + 32000 + 32000, final.size)
        repeat(100) { assertTrue(segmenter.feed(pcm(1600, 0)).isEmpty()) }
        assertNull(segmenter.finish())
    }

    @Test fun actualReadLengthIgnoresStaleBufferTail() {
        val segmenter = SpeechPcmSegmenter()
        val buffer = pcm(1600, 1000)
        buffer.fill(0, 0, 1600)
        repeat(10) { assertTrue(segmenter.feed(buffer, 1600).isEmpty()) }
        assertNull(segmenter.finish())
    }

    @Test fun pauseSeparatesPrecedingAndResumedUtterances() {
        val segmenter = SpeechPcmSegmenter()
        segmenter.feed(pcm(4000, 2000))
        assertArrayEquals(pcm(4000, 2000), segmenter.pauseBoundary())
        assertNull(segmenter.pauseBoundary())
        segmenter.feed(pcm(4500, 3000))
        assertArrayEquals(pcm(4500, 3000), segmenter.finish())
        assertNull(segmenter.finish())
        assertTrue(segmenter.feed(pcm(1600, 4000)).isEmpty())
    }

    @Test fun oversizedReadStillEmitsOnlyBoundedNonOverlappingSegments() {
        val segmenter = SpeechPcmSegmenter()
        val audio = pcm(16000 * 17, 5000)
        val finals = segmenter.feed(audio)
        assertEquals(listOf(256000, 256000), finals.map { it.size })
        assertEquals(32000, segmenter.finish()!!.size)
    }

    @Test fun partialSilenceReadsUseAudioDurationInsteadOfReadCount() {
        val segmenter = SpeechPcmSegmenter()
        segmenter.feed(pcm(4000, 1000))
        repeat(99) { assertTrue(segmenter.feed(pcm(160, 0)).isEmpty()) }
        assertEquals(40000, segmenter.feed(pcm(160, 0)).single().size)
    }

    @Test fun eightSecondBoundaryDoesNotLoseRemainingReadBytes() {
        val segmenter = SpeechPcmSegmenter()
        segmenter.feed(pcm(16000 * 7 + 8000, 1000))
        val final = segmenter.feed(pcm(16000, 2000)).single()
        assertEquals(256000, final.size)
        assertArrayEquals(pcm(8000, 2000), segmenter.finish())
    }

    @Test fun invalidAndZeroLengthReadsCannotAdvanceBoundary() {
        val segmenter = SpeechPcmSegmenter()
        assertTrue(segmenter.feed(pcm(1600, 1000), 0).isEmpty())
        try { segmenter.feed(ByteArray(3)); fail("Incomplete samples must be rejected") } catch (_: IllegalArgumentException) {}
        try { segmenter.feed(ByteArray(2), 4); fail("Stale buffer reads must be rejected") } catch (_: IllegalArgumentException) {}
        assertNull(segmenter.finish())
    }

    @Test fun noisyEnergyWithoutSpeechIsNeverDecoded() {
        val segmenter = SpeechPcmSegmenter()
        repeat(600) { assertTrue(segmenter.feed(pcm(1600, 5000), speechDetected = false).isEmpty()) }
        assertNull(segmenter.finish())
    }

    @Test fun shortSoundWithoutMinimumSpeechDurationIsDiscarded() {
        val segmenter = SpeechPcmSegmenter()
        segmenter.feed(pcm(1600, 1000))
        repeat(10) { assertTrue(segmenter.feed(pcm(1600, 0)).isEmpty()) }
        assertNull(segmenter.finish())
    }

    @Test fun shortSpeechTailsAfterEightSecondsSurviveStopPauseAndSilence() {
        for (tailSamples in listOf(1600, 3200)) {
            for (end in listOf("stop", "pause", "silence")) {
                val segmenter = SpeechPcmSegmenter()
                val opening = pcm(128000, 1000)
                val tail = pcm(tailSamples, 2000)
                val outputs = segmenter.feed(opening + tail).toMutableList()
                when (end) {
                    "stop" -> segmenter.finish()?.let(outputs::add)
                    "pause" -> segmenter.pauseBoundary()?.let(outputs::add)
                    "silence" -> outputs.addAll(segmenter.feed(pcm(16000, 0)))
                }
                val expectedTail = if (end == "silence") tail + pcm(16000, 0) else tail
                assertEquals("$tailSamples samples ending at $end", listOf(256000, expectedTail.size), outputs.map { it.size })
                assertArrayEquals(opening + expectedTail, outputs.fold(ByteArray(0)) { all, next -> all + next })
                assertNull(segmenter.finish())
            }
        }
    }

    @Test fun forcedBoundaryDoesNotLetIndependentShortNoiseBypassMinimumSpeech() {
        for (end in listOf("pause", "silence")) {
            val segmenter = SpeechPcmSegmenter()
            segmenter.feed(pcm(128000, 1000))
            if (end == "pause") segmenter.pauseBoundary() else segmenter.feed(pcm(16000, 0))
            segmenter.feed(pcm(1600, 3000))
            assertNull("Independent noise after $end", segmenter.finish())
        }
    }

    @Test fun silenceAcrossForcedBoundaryStillEndsUtteranceAfterOneSecond() {
        val segmenter = SpeechPcmSegmenter()
        segmenter.feed(pcm(126400, 1000))
        assertEquals(256000, segmenter.feed(pcm(1600, 0)).single().size)
        assertTrue(segmenter.feed(pcm(14400, 0)).isEmpty())
        segmenter.feed(pcm(1600, 3000))
        assertNull(segmenter.finish())
    }

    @Test fun consumedSegmentCannotBeReplayedThroughLeadingBuffer() {
        val segmenter = SpeechPcmSegmenter()
        segmenter.feed(pcm(16000, 1000))
        repeat(10) { segmenter.feed(pcm(1600, 0)) }
        val next = segmenter.feed(pcm(16000, 2000))
        assertTrue(next.isEmpty())
        assertArrayEquals(pcm(16000, 2000), segmenter.finish())
    }

    @Test fun delayedSpeechDetectionRetainsOpeningWithinLeadingWindow() {
        val segmenter = SpeechPcmSegmenter()
        repeat(4) { segmenter.feed(pcm(1600, 1000), speechDetected = false) }
        repeat(4) { segmenter.feed(pcm(1600, 2000), speechDetected = true) }
        val captured = segmenter.finish()!!
        assertArrayEquals(pcm(6400, 1000) + pcm(6400, 2000), captured)
    }

    @Test fun vadFalsePositiveOverZerosCannotDecode() {
        val segmenter = SpeechPcmSegmenter()
        repeat(600) { assertTrue(segmenter.feed(pcm(1600, 0), speechDetected = true).isEmpty()) }
        assertNull(segmenter.finish())
    }

    private fun pcm(samples: Int, amplitude: Int): ByteArray = ByteArray(samples * 2).also { bytes ->
        repeat(samples) { index ->
            bytes[index * 2] = amplitude.toByte()
            bytes[index * 2 + 1] = (amplitude shr 8).toByte()
        }
    }
}
