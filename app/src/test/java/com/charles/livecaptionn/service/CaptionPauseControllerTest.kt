package com.charles.livecaptionn.service

import com.charles.livecaptionn.speech.SpeechEngine
import org.junit.Assert.*
import org.junit.Test

class CaptionPauseControllerTest {
    @Test fun pauseWhilePreparingDefersCaptureUntilResume() {
        val controller = CaptionPauseController()
        val engine = RecordingEngine()

        controller.toggle()
        controller.attach(engine)

        assertTrue(controller.paused)
        assertFalse(controller.hasStarted)
        assertEquals(emptyList<String>(), engine.events)

        controller.toggle()
        assertFalse(controller.paused)
        assertTrue(controller.hasStarted)
        assertEquals(listOf("start"), engine.events)
    }

    @Test fun resumeBeforePreparationCompletesStartsEngineOnceWhenReady() {
        val controller = CaptionPauseController()
        val engine = RecordingEngine()
        controller.toggle()
        controller.toggle()
        assertFalse(controller.hasStarted)

        controller.attach(engine)
        controller.toggle()
        controller.toggle()

        assertEquals(listOf("start", "pause", "resume"), engine.events)
    }

    @Test fun repeatedPreparationPauseRequestsUseTheLatestRequest() {
        val controller = CaptionPauseController()
        val engine = RecordingEngine()
        repeat(3) { controller.toggle() }
        controller.attach(engine)
        assertEquals(emptyList<String>(), engine.events)
        assertTrue(controller.paused)
    }

    private class RecordingEngine : SpeechEngine {
        val events = mutableListOf<String>()
        override fun start() { events += "start" }
        override suspend fun stop() { events += "stop" }
        override fun pause() { events += "pause" }
        override fun resume() { events += "resume" }
    }
}
