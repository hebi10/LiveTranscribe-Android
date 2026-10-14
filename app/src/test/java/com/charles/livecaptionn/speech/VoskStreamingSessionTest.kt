package com.charles.livecaptionn.speech

import org.junit.Assert.*
import org.junit.Test

class VoskStreamingSessionTest {
    @Test fun sixtySecondsWithoutSilenceProducesTenIndependentFinalsAndContinues() {
        val session = VoskStreamingSession(FakeRecognizer(), SAMPLE_RATE)
        val finals = mutableListOf<Pair<Int, String>>()
        repeat(600) { index ->
            val result = session.feed(CHUNK)
            if (result.accepted) finals += index + 1 to result.text
        }

        assertEquals((60..600 step 60).toList(), finals.map { it.first })
        assertEquals((1..10).map { "segment $it bytes 192000" }, finals.map { it.second })
        assertEquals("", session.finish())
    }

    @Test fun naturalEndpointStartsANewSixSecondBudget() {
        val session = VoskStreamingSession(FakeRecognizer(setOf(30)), SAMPLE_RATE)
        val boundaries = mutableListOf<Int>()
        repeat(90) { index ->
            if (session.feed(CHUNK).accepted) boundaries += index + 1
        }
        assertEquals(listOf(30, 90), boundaries)
    }

    @Test fun naturalEndpointCoincidingWithDeadlineProducesOneFinal() {
        val session = VoskStreamingSession(FakeRecognizer(setOf(60)), SAMPLE_RATE)
        val results = (1..60).map { session.feed(CHUNK) }
        assertEquals(listOf("segment 1 bytes 192000"), results.filter { it.accepted }.map { it.text })
        assertEquals("", session.finish())
    }

    @Test fun stoppingAfterAForcedBoundaryEmitsOnlyTheNewTailOnce() {
        val session = VoskStreamingSession(FakeRecognizer(), SAMPLE_RATE)
        repeat(60) { session.feed(CHUNK) }
        session.feed(CHUNK)
        assertEquals("segment 2 bytes 3200", session.finish())
        assertEquals("", session.finish())
    }

    @Test fun stoppingImmediatelyAfterANaturalBoundaryDoesNotRepeatIt() {
        val session = VoskStreamingSession(FakeRecognizer(setOf(3)), SAMPLE_RATE)
        repeat(3) { session.feed(CHUNK) }
        assertEquals("", session.finish())
        assertEquals("", session.finish())
    }

    @Test fun lateFeedsAreIgnoredAfterFinishAndClose() {
        val recognizer = FakeRecognizer()
        val session = VoskStreamingSession(recognizer, SAMPLE_RATE)
        session.feed(CHUNK)
        session.finish()
        assertEquals(VoskStreamingSession.FeedResult("", false), session.feed(CHUNK))
        session.close()
        session.close()
        assertEquals(VoskStreamingSession.FeedResult("", false), session.feed(CHUNK))
        assertEquals(CHUNK.size.toLong(), recognizer.totalBytes)
        assertEquals(1, recognizer.closeCount)
    }

    @Test fun aZeroLengthReadDoesNotFeedOrConsumeTheDurationBudget() {
        val recognizer = FakeRecognizer()
        val session = VoskStreamingSession(recognizer, SAMPLE_RATE)
        repeat(59) { assertFalse(session.feed(CHUNK).accepted) }
        assertEquals(VoskStreamingSession.FeedResult("", false), session.feed(CHUNK, 0))
        assertEquals(59 * CHUNK.size.toLong(), recognizer.totalBytes)
        assertTrue(session.feed(CHUNK).accepted)
    }

    @Test fun durationUsesActualReadLengthAndTheSessionSampleRate() {
        val session = VoskStreamingSession(FakeRecognizer(), 8_000)
        repeat(59) { assertFalse(session.feed(CHUNK, 1600).accepted) }
        assertEquals(VoskStreamingSession.FeedResult("segment 1 bytes 96000", true), session.feed(CHUNK, 1600))
    }

    @Test fun invalidPcmLengthsAreRejectedBeforeNativeInput() {
        val recognizer = FakeRecognizer()
        val session = VoskStreamingSession(recognizer, SAMPLE_RATE)
        for (length in listOf(-1, 1, CHUNK.size + 2)) {
            assertThrows(IllegalArgumentException::class.java) { session.feed(CHUNK, length) }
        }
        assertEquals(0L, recognizer.totalBytes)
    }

    @Test fun emptyForcedFinalStillEndsTheSegment() {
        val session = VoskStreamingSession(FakeRecognizer(blankFinals = true), SAMPLE_RATE)
        repeat(59) { assertFalse(session.feed(CHUNK).accepted) }
        assertEquals(VoskStreamingSession.FeedResult("", true), session.feed(CHUNK))
        assertFalse(session.feed(CHUNK).accepted)
    }

    /** Models Vosk's endpoint/final state: a consumed result is empty until more PCM arrives. */
    private class FakeRecognizer(
        private val naturalEndpoints: Set<Int> = emptySet(),
        private val blankFinals: Boolean = false
    ) : StreamingRecognizer {
        var totalBytes = 0L
            private set
        var closeCount = 0
            private set
        private var pendingBytes = 0L
        private var feeds = 0
        private var segment = 1

        override fun acceptWaveForm(pcm: ByteArray, length: Int): Boolean {
            check(closeCount == 0)
            totalBytes += length
            pendingBytes += length
            feeds++
            return feeds in naturalEndpoints
        }

        override val result: String get() = consumeResult()
        override val partialResult: String get() = """{"partial":"segment $segment"}"""
        override val finalResult: String get() = consumeResult()

        private fun consumeResult(): String {
            if (pendingBytes == 0L) return """{"text":""}"""
            val text = if (blankFinals) "" else "segment $segment bytes $pendingBytes"
            pendingBytes = 0L
            segment++
            return """{"text":"$text"}"""
        }

        override fun close() { closeCount++ }
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private val CHUNK = ByteArray(3200)
    }
}
