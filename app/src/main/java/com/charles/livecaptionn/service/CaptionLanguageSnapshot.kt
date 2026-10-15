package com.charles.livecaptionn.service

import com.charles.livecaptionn.data.HistoryRecordingGate
import com.charles.livecaptionn.data.TranscriptEntry
import com.charles.livecaptionn.settings.RecognitionLanguages
import com.charles.livecaptionn.speech.SpeechResult
import com.charles.livecaptionn.speech.normalizeRecognizedTranscript
import com.charles.livecaptionn.translation.TranslationRepository

internal data class CaptionSpeechSnapshot(
    val text: String,
    val sourceLanguageCode: String,
    val autoDetect: Boolean
) {
    fun request(lineId: Long, isFinal: Boolean, consent: HistoryRecordingGate.Consent?) =
        CaptionTranslationRequest(text, lineId, isFinal, sourceLanguageCode, autoDetect, consent)
}

internal fun resolveCaptionSpeech(result: SpeechResult, sessionMode: String): CaptionSpeechSnapshot {
    val mode = RecognitionLanguages.normalizeMode(sessionMode)
    val automatic = mode == RecognitionLanguages.AUTO
    val source = if (automatic) RecognitionLanguages.normalizeDetected(result.detectedLanguageCode) else mode
    return CaptionSpeechSnapshot(normalizeRecognizedTranscript(result.text, source), source, automatic)
}

internal data class CaptionTranslationRequest(
    val text: String,
    val lineId: Long,
    val isFinal: Boolean,
    val sourceLanguageCode: String,
    val autoDetect: Boolean,
    val historyConsent: HistoryRecordingGate.Consent?
)

internal suspend fun translateCaptionRequest(
    request: CaptionTranslationRequest,
    repository: TranslationRepository
): String = repository.translate(request.text, request.sourceLanguageCode, "ko", request.autoDetect)

internal fun captionHistoryEntry(request: CaptionTranslationRequest, translation: String): TranscriptEntry =
    TranscriptEntry(originalText = request.text, translatedText = translation,
        sourceLanguage = request.sourceLanguageCode, targetLanguage = "ko")
