package com.charles.livecaptionn.speech

interface SpeechEngine {
    fun start()
    /** Returns after capture has stopped, its tail final was emitted and resources were released. */
    suspend fun stop()
    fun pause()
    fun resume()
}

