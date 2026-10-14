package com.charles.livecaptionn

import com.charles.livecaptionn.speech.JapanesePcmSegmenter
import org.junit.Assert.*
import org.junit.Test

class JapanesePcmSegmenterTest {
    @Test fun continuousMinuteProducesBoundedSegmentsWithoutDuplicatedAudio() {
        val segmenter = JapanesePcmSegmenter()
        val outputs = (1..600).flatMap { segmenter.feed(pcm(1600, 1000)) }
        assertEquals(7, outputs.size)
        assertTrue(outputs.all { it.size == 256000 })
        assertEquals(1_920_000, outputs.sumOf { it.size } + segmenter.finish()!!.size)
        assertNull(segmenter.finish())
    }

    @Test fun oneSecondSilenceFlushesVoiceButSilenceAloneDoesNotDecode() {
        val segmenter = JapanesePcmSegmenter()
        repeat(100) { assertTrue(segmenter.feed(pcm(1600, 0)).isEmpty()) }
        repeat(10) { assertTrue(segmenter.feed(pcm(1600, 1000)).isEmpty()) }
        repeat(9) { assertTrue(segmenter.feed(pcm(1600, 0)).isEmpty()) }
        val final = segmenter.feed(pcm(1600, 0)).single()
        assertEquals(16000 + 32000 + 32000, final.size)
        repeat(100) { assertTrue(segmenter.feed(pcm(1600, 0)).isEmpty()) }
        assertNull(segmenter.finish())
    }

    @Test fun actualReadLengthIgnoresStaleBufferTail() {
        val segmenter = JapanesePcmSegmenter()
        val buffer = pcm(1600, 1000)
        buffer.fill(0, 0, 1600)
        repeat(10) { assertTrue(segmenter.feed(buffer, 1600).isEmpty()) }
        assertNull(segmenter.finish())
    }

    @Test fun pauseSeparatesPrecedingAndResumedUtterances() {
        val segmenter = JapanesePcmSegmenter()
        segmenter.feed(pcm(4000, 2000))
        assertArrayEquals(pcm(4000, 2000), segmenter.pauseBoundary())
        assertNull(segmenter.pauseBoundary())
        segmenter.feed(pcm(4500, 3000))
        assertArrayEquals(pcm(4500, 3000), segmenter.finish())
        assertNull(segmenter.finish())
        assertTrue(segmenter.feed(pcm(1600, 4000)).isEmpty())
    }

    @Test fun oversizedReadStillEmitsOnlyBoundedNonOverlappingSegments() {
        val segmenter = JapanesePcmSegmenter()
        val audio = pcm(16000 * 17, 5000)
        val finals = segmenter.feed(audio)
        assertEquals(listOf(256000, 256000), finals.map { it.size })
        assertEquals(32000, segmenter.finish()!!.size)
    }

    @Test fun partialSilenceReadsUseAudioDurationInsteadOfReadCount() {
        val segmenter = JapanesePcmSegmenter()
        segmenter.feed(pcm(4000, 1000))
        repeat(99) { assertTrue(segmenter.feed(pcm(160, 0)).isEmpty()) }
        assertEquals(40000, segmenter.feed(pcm(160, 0)).single().size)
    }

    @Test fun eightSecondBoundaryDoesNotLoseRemainingReadBytes() {
        val segmenter = JapanesePcmSegmenter()
        segmenter.feed(pcm(16000 * 7 + 8000, 1000))
        val final = segmenter.feed(pcm(16000, 2000)).single()
        assertEquals(256000, final.size)
        assertArrayEquals(pcm(8000, 2000), segmenter.finish())
    }

    @Test fun invalidAndZeroLengthReadsCannotAdvanceBoundary() {
        val segmenter = JapanesePcmSegmenter()
        assertTrue(segmenter.feed(pcm(1600, 1000), 0).isEmpty())
        try { segmenter.feed(ByteArray(3)); fail("Incomplete samples must be rejected") } catch (_: IllegalArgumentException) {}
        try { segmenter.feed(ByteArray(2), 4); fail("Stale buffer reads must be rejected") } catch (_: IllegalArgumentException) {}
        assertNull(segmenter.finish())
    }

    @Test fun noisyEnergyWithoutSpeechIsNeverDecoded() {
        val segmenter = JapanesePcmSegmenter()
        repeat(600) { assertTrue(segmenter.feed(pcm(1600, 5000), speechDetected = false).isEmpty()) }
        assertNull(segmenter.finish())
    }

    @Test fun shortSoundWithoutMinimumSpeechDurationIsDiscarded() {
        val segmenter = JapanesePcmSegmenter()
        segmenter.feed(pcm(1600, 1000))
        repeat(10) { assertTrue(segmenter.feed(pcm(1600, 0)).isEmpty()) }
        assertNull(segmenter.finish())
    }

    @Test fun consumedSegmentCannotBeReplayedThroughLeadingBuffer() {
        val segmenter = JapanesePcmSegmenter()
        segmenter.feed(pcm(16000, 1000))
        repeat(10) { segmenter.feed(pcm(1600, 0)) }
        val next = segmenter.feed(pcm(16000, 2000))
        assertTrue(next.isEmpty())
        assertArrayEquals(pcm(16000, 2000), segmenter.finish())
    }

    @Test fun delayedSpeechDetectionRetainsOpeningWithinLeadingWindow() {
        val segmenter = JapanesePcmSegmenter()
        repeat(4) { segmenter.feed(pcm(1600, 1000), speechDetected = false) }
        repeat(4) { segmenter.feed(pcm(1600, 2000), speechDetected = true) }
        val captured = segmenter.finish()!!
        assertArrayEquals(pcm(6400, 1000) + pcm(6400, 2000), captured)
    }

    @Test fun vadFalsePositiveOverZerosCannotDecode() {
        val segmenter = JapanesePcmSegmenter()
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
