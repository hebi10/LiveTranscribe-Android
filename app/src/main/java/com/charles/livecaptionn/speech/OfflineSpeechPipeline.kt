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
import java.util.concurrent.ConcurrentHashMap

internal class OfflineSpeechPipeline(
    private val session: OfflineSttSession,
    private val onResult: (SpeechResult) -> Unit,
    private val onSkippedAudio: () -> Unit = {},
    private val onFailure: (String) -> Unit = {},
    workerContext: CoroutineContext = Dispatchers.IO,
    private val onProcessing: (Boolean) -> Unit = {},
    private val onRecognitionLimit: () -> Unit = {},
    private val sourceLanguageCode: String = "auto",
    private val detectLanguage: ((ByteArray) -> String?)? = null,
    private val onLanguageIdentified: (Long, String?) -> Unit = { _, _ -> },
    private val onRecognitionLatency: (Long) -> Unit = {},
    private val onRevisionConflict: () -> Unit = {},
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val lock = Any()
    private val finishMutex = Mutex()
    private val segmenter = SpeechPcmSegmenter()
    private val sentences = StableSentenceCommitter()
    private val languages = ConcurrentHashMap<Long, String>()
    private val workerScope = CoroutineScope(workerContext + SupervisorJob())
    private var finishing = false
    private var finished = false
    @Volatile private var previewIntervalBytes = 64_000
    @Volatile var failureMessage: String? = null
        private set
    private data class RecognitionWork(val window: SpeechAudioWindow, val submittedAtMs: Long)
    private val languageQueue = CaptionTranslationQueue<SpeechAudioWindow>(workerScope, nowMs = nowMs) { window ->
        val language = try { detectLanguage?.invoke(window.pcm) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Throwable) { null }
        if (language != null) languages[window.id] = language
        languages.keys.removeAll { it < window.id - 4 }
        onLanguageIdentified(window.id, language)
    }
    private val queue = CaptionTranslationQueue<RecognitionWork>(
        scope = workerScope,
        nowMs = nowMs,
        maxPendingFinals = 2,
        onFinalDropped = { onSkippedAudio() }
    ) { work -> decode(work) }

    fun feed(pcm: ByteArray, length: Int = pcm.size): Boolean = synchronized(lock) {
        if (finishing || failureMessage != null) return false
        val speech = try {
            session.isSpeech(pcm, length)
        } catch (failure: Throwable) {
            throw IllegalStateException("음성 구간 감지에 실패했습니다. 다시 시작해 주세요.", failure)
        }
        val segments = segmenter.feedSegments(pcm, length, speech)
        segments.forEach { queue.submitFinal(RecognitionWork(it, nowMs())) }
        val preview = if (queue.pendingFinalCount == 0) segmenter.previewSegment(previewIntervalBytes) else null
        if (preview != null) queue.submitPartial(RecognitionWork(preview, nowMs()))
        segments.isNotEmpty()
    }

    fun pauseBoundary() = synchronized(lock) {
        if (finishing || failureMessage != null) return
        segmenter.pauseBoundarySegment()?.let { queue.submitFinal(RecognitionWork(it, nowMs())) }
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
                segmenter.finishSegment()?.let { queue.submitFinal(RecognitionWork(it, nowMs())) }
            }
            try {
                queue.finish(timeoutMillis = Long.MAX_VALUE)
                languageQueue.finish(timeoutMillis = Long.MAX_VALUE)
            } finally {
                try { session.close() } finally {
                    synchronized(lock) { finished = true }
                    workerScope.cancel()
                }
            }
            failureMessage?.let { throw IllegalStateException(it) }
        }
    }

    private fun decode(work: RecognitionWork) {
        val window = work.window
        val startedAtMs = nowMs()
        onProcessing(true)
        try {
            val text = session.transcribe(window.pcm, sourceLanguageCode)
            if (text.isNotBlank()) {
                val committed = sentences.accept(window.id, text, window.isFinal)
                if (committed.revisionConflict) onRevisionConflict()
                if (committed.sentences.isNotEmpty()) onResult(SpeechResult(
                    committed.sentences.joinToString("\n"), isFinal = true,
                    detectedLanguageCode = languages[window.id], audioWindowId = window.id))
                if (window.isFinal && sourceLanguageCode == "auto" && detectLanguage != null)
                    languageQueue.submitFinal(window)
            }
        } catch (_: RecognitionOutputLimitException) {
            onRecognitionLimit()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            val message = "음성 인식에 실패했습니다. 인식을 중지하고 다시 시작해 주세요."
            val notify = synchronized(lock) {
                val firstFailure = failureMessage == null
                failureMessage = message
                firstFailure && !finishing
            }
            queue.cancel()
            if (notify) onFailure(message)
        } finally {
            previewIntervalBytes = when {
                nowMs() - startedAtMs > 4_000 -> 256_000
                nowMs() - startedAtMs > 1_500 -> 128_000
                else -> 64_000
            }
            onRecognitionLatency((nowMs() - work.submittedAtMs).coerceAtLeast(0))
            onProcessing(false)
        }
    }
}
