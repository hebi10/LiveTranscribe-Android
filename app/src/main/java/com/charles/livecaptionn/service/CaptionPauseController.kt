package com.charles.livecaptionn.service

import com.charles.livecaptionn.speech.SpeechEngine

/** Main-thread owner of the requested pause state during service preparation. */
internal class CaptionPauseController {
    @Volatile var paused = false
        private set
    var hasStarted = false
        private set
    private var engine: SpeechEngine? = null

    fun attach(engine: SpeechEngine) {
        this.engine = engine
        if (!paused) startIfReady()
    }

    fun toggle() {
        paused = !paused
        if (paused) {
            if (hasStarted) engine?.pause()
        } else if (hasStarted) {
            engine?.resume()
        } else {
            startIfReady()
        }
    }

    private fun startIfReady() {
        val readyEngine = engine ?: return
        readyEngine.start()
        hasStarted = true
    }
}
