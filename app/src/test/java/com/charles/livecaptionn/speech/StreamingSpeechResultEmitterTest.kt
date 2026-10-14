package com.charles.livecaptionn.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingSpeechResultEmitterTest {
    @Test fun blankFinalEmitsBoundaryAndAllowsTheSamePartialInTheNextSegment() {
        val emitted = mutableListOf<SpeechResult>()
        val emitter = StreamingSpeechResultEmitter(emitted::add)

        emitter.emit(VoskStreamingSession.FeedResult("hello", accepted = false))
        emitter.emit(VoskStreamingSession.FeedResult("", accepted = true))
        emitter.emit(VoskStreamingSession.FeedResult("hello", accepted = false))

        assertEquals(
            listOf(
                SpeechResult("hello", isFinal = false),
                SpeechResult("", isFinal = true),
                SpeechResult("hello", isFinal = false)
            ),
            emitted
        )
    }

    @Test fun blankAndRepeatedPartialsAreSuppressedWhileChangedPartialsEmit() {
        val emitted = mutableListOf<SpeechResult>()
        val emitter = StreamingSpeechResultEmitter(emitted::add)

        emitter.emit(VoskStreamingSession.FeedResult("", accepted = false))
        emitter.emit(VoskStreamingSession.FeedResult("  ", accepted = false))
        emitter.emit(VoskStreamingSession.FeedResult("hello", accepted = false))
        emitter.emit(VoskStreamingSession.FeedResult("hello", accepted = false))
        emitter.emit(VoskStreamingSession.FeedResult("", accepted = false))
        emitter.emit(VoskStreamingSession.FeedResult("hello", accepted = false))
        emitter.emit(VoskStreamingSession.FeedResult("hello world", accepted = false))
        emitter.emit(VoskStreamingSession.FeedResult("hello world", accepted = false))

        assertEquals(
            listOf(
                SpeechResult("hello", isFinal = false),
                SpeechResult("hello world", isFinal = false)
            ),
            emitted
        )
    }

    @Test fun acceptedRepeatedTextEmitsEachSegmentAndResetsPartialSuppression() {
        val emitted = mutableListOf<SpeechResult>()
        val emitter = StreamingSpeechResultEmitter(emitted::add)

        emitter.emit(VoskStreamingSession.FeedResult("again", accepted = false))
        emitter.emit(VoskStreamingSession.FeedResult("again", accepted = true))
        emitter.emit(VoskStreamingSession.FeedResult("again", accepted = true))
        emitter.emit(VoskStreamingSession.FeedResult("again", accepted = false))

        assertEquals(
            listOf(
                SpeechResult("again", isFinal = false),
                SpeechResult("again", isFinal = true),
                SpeechResult("again", isFinal = true),
                SpeechResult("again", isFinal = false)
            ),
            emitted
        )
    }
}
