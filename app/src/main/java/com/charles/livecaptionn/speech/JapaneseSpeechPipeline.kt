package com.charles.livecaptionn.speech

import com.charles.livecaptionn.service.CaptionTranslationQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class JapaneseSpeechPipeline(
    private val session: JapaneseSttSession,
    private val onResult: (SpeechResult) -> Unit,
    private val onSkippedAudio: () -> Unit = {},
    private val onFailure: (String) -> Unit = {},
    workerContext: CoroutineContext = Dispatchers.IO
) {
    private val lock = Any()
    private val finishMutex = Mutex()
    private val segmenter = JapanesePcmSegmenter()
    private val workerScope = CoroutineScope(workerContext + SupervisorJob())
    private var finishing = false
    private var finished = false
    @Volatile var failureMessage: String? = null
        private set
    private val queue = CaptionTranslationQueue<ByteArray>(
        scope = workerScope,
        maxPendingFinals = 2,
        onFinalDropped = { onSkippedAudio() }
    ) { audio -> decode(audio) }

    fun feed(pcm: ByteArray, length: Int = pcm.size): Boolean = synchronized(lock) {
        if (finishing || failureMessage != null) return false
        val speech = try {
            session.isSpeech(pcm, length)
        } catch (failure: Throwable) {
            throw IllegalStateException("일본어 음성 구간 감지에 실패했습니다. 다시 시작해 주세요.", failure)
        }
        val segments = segmenter.feed(pcm, length, speech)
        segments.forEach(queue::submitFinal)
        segments.isNotEmpty()
    }

    fun pauseBoundary() = synchronized(lock) {
        if (finishing || failureMessage != null) return
        segmenter.pauseBoundary()?.let(queue::submitFinal)
        session.resetSpeechGate()
    }

    suspend fun finish() = withContext(NonCancellable) {
        finishMutex.withLock {
            synchronized(lock) {
                if (finished) {
                    failureMessage?.let { throw IllegalStateException(it) }
                    return@withContext
                }
                finishing = true
                segmenter.finish()?.let(queue::submitFinal)
            }
            try {
                queue.finish(timeoutMillis = Long.MAX_VALUE)
            } finally {
                try { session.close() } finally {
                    synchronized(lock) { finished = true }
                    workerScope.cancel()
                }
            }
            failureMessage?.let { throw IllegalStateException(it) }
        }
    }

    private fun decode(audio: ByteArray) {
        try {
            val text = session.transcribe(audio)
            if (text.isNotBlank()) onResult(SpeechResult(text, isFinal = true))
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            val message = "일본어 음성 인식에 실패했습니다. 인식을 중지하고 다시 시작해 주세요."
            val notify = synchronized(lock) {
                val firstFailure = failureMessage == null
                failureMessage = message
                firstFailure && !finishing
            }
            queue.cancel()
            if (notify) onFailure(message)
        }
    }
}

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

internal class JapaneseResumeAudioBoundary(initialGeneration: Long) {
    private var observedGeneration = initialGeneration
    fun isCurrent(generation: Long) = observedGeneration == generation

    fun ensureCurrent(generation: Long, discardUntilEmpty: () -> Boolean): Boolean {
        if (isCurrent(generation)) return true
        if (!discardUntilEmpty()) return false
        observedGeneration = generation
        return true
    }
}
