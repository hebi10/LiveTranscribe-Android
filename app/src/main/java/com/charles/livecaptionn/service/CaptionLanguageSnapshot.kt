package com.charles.livecaptionn.service

import com.charles.livecaptionn.data.HistoryRecordingGate
import com.charles.livecaptionn.data.TranscriptEntry
import com.charles.livecaptionn.settings.RecognitionLanguages
import com.charles.livecaptionn.speech.SpeechResult
import com.charles.livecaptionn.speech.normalizeRecognizedTranscript
import com.charles.livecaptionn.translation.TranslationRepository
import java.util.concurrent.ConcurrentHashMap

/** A slow detection may enrich its own audio window, never the next window's captions. */
internal class CaptionWindowLanguages(private val maxWindows: Int = 64) {
    init { require(maxWindows > 0) }
    private val sources = ConcurrentHashMap<Long, String>()

    @Synchronized
    fun record(windowId: Long, detectedLanguageCode: String?): String? {
        val source = RecognitionLanguages.normalizeDetected(detectedLanguageCode)
        if (source == RecognitionLanguages.UNKNOWN) return null
        sources[windowId] = source
        while (sources.size > maxWindows) {
            val oldest = sources.keys.minOrNull() ?: break
            sources.remove(oldest)
        }
        return source
    }

    operator fun get(windowId: Long?): String? = windowId?.let { sources[it] }

    @Synchronized
    fun clear() { sources.clear() }
}

internal data class CaptionSpeechSnapshot(
    val text: String,
    val sourceLanguageCode: String,
    val autoDetect: Boolean,
    val audioWindowId: Long? = null
) {
    fun request(lineId: Long, isFinal: Boolean, consent: HistoryRecordingGate.Consent?) =
        CaptionTranslationRequest(text, lineId, isFinal, sourceLanguageCode, autoDetect, consent, audioWindowId)
}

internal fun resolveCaptionSpeech(result: SpeechResult, sessionMode: String): CaptionSpeechSnapshot {
    val mode = RecognitionLanguages.normalizeMode(sessionMode)
    val automatic = mode == RecognitionLanguages.AUTO
    val source = if (automatic) RecognitionLanguages.normalizeDetected(result.detectedLanguageCode) else mode
    return CaptionSpeechSnapshot(normalizeRecognizedTranscript(result.text, source), source, automatic, result.audioWindowId)
}

internal data class CaptionTranslationRequest(
    val text: String,
    val lineId: Long,
    val isFinal: Boolean,
    val sourceLanguageCode: String,
    val autoDetect: Boolean,
    val historyConsent: HistoryRecordingGate.Consent?,
    val audioWindowId: Long? = null,
    val submittedAtNanos: Long = System.nanoTime()
)

/** Resolve language immediately before work without changing the queued consent or automatic mode. */
internal fun CaptionTranslationRequest.withDetectedWindowLanguage(detectedLanguageCode: String?): CaptionTranslationRequest {
    if (!autoDetect || audioWindowId == null) return this
    val source = RecognitionLanguages.normalizeDetected(detectedLanguageCode)
    return if (source == RecognitionLanguages.UNKNOWN || source == sourceLanguageCode) this
        else copy(sourceLanguageCode = source)
}

internal suspend fun translateCaptionRequest(
    request: CaptionTranslationRequest,
    repository: TranslationRepository
): String = repository.translate(request.text, request.sourceLanguageCode, "ko", request.autoDetect)

internal fun captionHistoryEntry(request: CaptionTranslationRequest, translation: String): TranscriptEntry =
    TranscriptEntry(originalText = request.text, translatedText = translation,
        sourceLanguage = request.sourceLanguageCode, targetLanguage = "ko")
