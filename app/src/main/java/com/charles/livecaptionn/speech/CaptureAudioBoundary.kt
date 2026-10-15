package com.charles.livecaptionn.speech

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal class CaptureSignalState(
    private val onError: (String?) -> Unit,
    private val onStatus: (RecognitionStatus) -> Unit
) {
    private val lock = Any()
    @Volatile var paused = false
        private set
    @Volatile var pauseGeneration = 0L
        private set
    @Volatile var resumeGeneration = 0L
        private set
    @Volatile var fatalError: String? = null
        private set

    fun begin() = synchronized(lock) { fatalError = null }

    fun fail(message: String) = synchronized(lock) {
        if (fatalError == null) fatalError = message
        onError(fatalError)
        onStatus(RecognitionStatus.ERROR)
    }

    fun hint(message: String?) = synchronized(lock) {
        if (fatalError == null) onError(message)
    }

    fun ready() = synchronized(lock) {
        if (fatalError == null) {
            onError(null)
            onStatus(if (paused) RecognitionStatus.PAUSED else RecognitionStatus.LISTENING)
        }
    }

    fun listening() = synchronized(lock) {
        if (fatalError == null && !paused) onStatus(RecognitionStatus.LISTENING)
    }

    fun pause() = synchronized(lock) {
        if (fatalError == null) {
            paused = true
            pauseGeneration++
            onStatus(RecognitionStatus.PAUSED)
        }
    }

    fun resume() = synchronized(lock) {
        if (fatalError == null) {
            paused = false
            resumeGeneration++
            onStatus(RecognitionStatus.LISTENING)
        }
    }

    /** Serialize the initial native start with pause; waiting remains cancellable. */
    suspend fun startWhenResumed(start: () -> Boolean): Boolean {
        while (true) {
            currentCoroutineContext().ensureActive()
            val started = synchronized(lock) { if (paused) null else start() }
            if (started != null) return started
            delay(120)
        }
    }

    fun stopped(): String? = synchronized(lock) {
        paused = false
        if (fatalError == null) {
            onStatus(RecognitionStatus.IDLE)
        } else {
            onError(fatalError)
            onStatus(RecognitionStatus.ERROR)
        }
        fatalError
    }
}

internal class ResumeAudioBoundary(initialGeneration: Long) {
    private var observedGeneration = initialGeneration
    fun isCurrent(generation: Long) = observedGeneration == generation

    fun ensureCurrent(generation: Long, discardUntilEmpty: () -> Boolean): Boolean {
        if (isCurrent(generation)) return true
        if (!discardUntilEmpty()) return false
        observedGeneration = generation
        return true
    }
}

/** Shared capture boundary for every language, including blocking reads across pause. */
internal class CaptureAudioBoundary(
    private val signals: CaptureSignalState,
    initialResumeGeneration: Long = signals.resumeGeneration,
    private val onPauseBoundary: () -> Unit
) {
    private var observedPauseGeneration = signals.pauseGeneration
    private val resumeBoundary = ResumeAudioBoundary(initialResumeGeneration)

    fun prepareRead(discardUntilEmpty: () -> Boolean): Boolean {
        observePause()
        if (!resumeBoundary.ensureCurrent(signals.resumeGeneration, discardUntilEmpty)) return false
        if (signals.paused) {
            discardUntilEmpty()
            return false
        }
        return true
    }

    /** Drop a read spanning pause/resume before it can reach ASR or language detection. */
    fun acceptRead(): Boolean {
        val crossedPause = observePause()
        return !crossedPause && !signals.paused && resumeBoundary.isCurrent(signals.resumeGeneration)
    }

    private fun observePause(): Boolean {
        val generation = signals.pauseGeneration
        if (observedPauseGeneration == generation) return false
        onPauseBoundary()
        observedPauseGeneration = generation
        return true
    }
}
