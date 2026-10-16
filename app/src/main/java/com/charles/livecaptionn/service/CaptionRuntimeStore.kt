package com.charles.livecaptionn.service

import com.charles.livecaptionn.settings.CaptionPlacement
import com.charles.livecaptionn.overlay.MAX_OVERLAY_MESSAGES
import com.charles.livecaptionn.settings.RecognitionLanguages
import com.charles.livecaptionn.speech.RecognitionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CaptionRuntimeLine(
    val id: Long,
    val originalText: String,
    val translatedText: String = "",
    val isFinal: Boolean = false,
    /** Only the translation requested at the recognizer's final boundary is permanent. */
    val finalTranslationCommitted: Boolean = isFinal && translatedText.isNotBlank(),
    val sourceLanguageCode: String = RecognitionLanguages.UNKNOWN
)

data class CaptionRuntimeState(
    val originalText: String = "",
    val translatedText: String = "",
    val transcriptLines: List<CaptionRuntimeLine> = emptyList(),
    val status: RecognitionStatus = RecognitionStatus.IDLE,
    val running: Boolean = false,
    val paused: Boolean = false,
    /** Remains running while the owning native worker and final translations finish. */
    val stopping: Boolean = false,
    val recognizing: Boolean = false,
    val translating: Boolean = false,
    val captureHint: String? = null,
    val infoMessage: String? = null,
    val lastError: String? = null,
    /** Number of pending translations skipped to keep up during this session. */
    val skippedTranslations: Int = 0,
    /** Audio segments skipped before recognition during a slow-device backlog. */
    val skippedSpeechSegments: Int = 0,
    val suggestedLanguageCode: String? = null,
    val changingLanguageCode: String? = null,
    val restartLanguageCode: String? = null,
    /** Fixed by the service at session start; retained with completed split captions. */
    val sessionPlacement: CaptionPlacement? = null,
    /** Process-local generation, changed only when the service starts a new capture session. */
    val sessionId: Long = 0L
) {
    val phase: CaptionPhase get() = when {
        stopping -> CaptionPhase.STOPPING
        status == RecognitionStatus.ERROR || !lastError.isNullOrBlank() -> CaptionPhase.ERROR
        paused || status == RecognitionStatus.PAUSED -> CaptionPhase.PAUSED
        recognizing || translating -> CaptionPhase.PROCESSING
        running && status != RecognitionStatus.LISTENING -> CaptionPhase.PREPARING
        running -> CaptionPhase.WAITING
        else -> CaptionPhase.IDLE
    }

    /** Only actionable capture hints/errors need space in the collapsed overlay. */
    val actionableNotice: String? get() = lastError?.takeIf { it.isNotBlank() }
        ?: captureHint?.takeIf { !stopping && !paused && it.isNotBlank() }

    val restartNotice: String? get() = restartLanguageCode?.let { code ->
        val label = RecognitionLanguages.koreanLabel(code)
        "$label 음성으로 변경했습니다. " +
            if (stopping) "종료 후 번역 다시 시작을 눌러 주세요." else "번역 다시 시작을 눌러 주세요."
    }

    val information: String? get() = listOfNotNull(infoMessage,
        captionBacklogMessage(skippedTranslations, skippedSpeechSegments))
        .takeIf { it.isNotEmpty() }?.joinToString("\n")
}

enum class CaptionPhase(val label: String) {
    IDLE("대기"), PREPARING("음성 인식 준비 중"), WAITING("음성 대기 중"),
    PROCESSING("자막 처리 중"), PAUSED("일시정지"), STOPPING("종료 중 · 남은 자막 처리 중"), ERROR("실행 오류")
}

internal fun stoppingCaptionSession(runtime: CaptionRuntimeState, error: String? = null): CaptionRuntimeState =
    runtime.copy(running = true, stopping = true, lastError = error ?: runtime.lastError, captureHint = null,
        suggestedLanguageCode = null)


class CaptionRuntimeStore {
    private val mutable = MutableStateFlow(CaptionRuntimeState())
    val state: StateFlow<CaptionRuntimeState> = mutable.asStateFlow()

    fun update(transform: (CaptionRuntimeState) -> CaptionRuntimeState) {
        mutable.update(transform)
    }

    /** Claim before launching settings I/O; detection cannot turn a second tap into another write. */
    internal fun claimLanguageSuggestion(code: String): Boolean {
        if (code !in setOf("en", "ja")) return false
        while (true) {
            val current = mutable.value
            if (!current.running || current.stopping || current.changingLanguageCode != null ||
                current.suggestedLanguageCode != code) return false
            if (mutable.compareAndSet(current, current.copy(suggestedLanguageCode = null,
                    changingLanguageCode = code))) return true
        }
    }
}


internal fun completedCaptionSession(
    previousFailure: String?,
    terminalFailure: String?,
    drained: Boolean,
    completionMessage: String? = null,
    skippedTranslations: Int = 0,
    skippedSpeechSegments: Int = 0,
    restartLanguageCode: String? = null,
    sessionPlacement: CaptionPlacement? = null,
    retainedLines: List<CaptionRuntimeLine> = emptyList(),
    sessionId: Long = 0L
): CaptionRuntimeState {
    val error = terminalFailure ?: previousFailure ?: if (!drained)
        "번역 종료 시간이 초과되어 마지막 일부 문장이 저장되지 않았습니다." else null
    val completedLines = if (sessionPlacement == CaptionPlacement.SPLIT_SCREEN)
        retainedLines.filter { it.isFinal && it.finalTranslationCommitted &&
            it.originalText.isNotBlank() && it.translatedText.isNotBlank() }.takeLast(MAX_OVERLAY_MESSAGES)
        else emptyList()
    return CaptionRuntimeState(
        originalText = completedLines.lastOrNull()?.originalText.orEmpty(),
        translatedText = completedLines.lastOrNull()?.translatedText.orEmpty(),
        transcriptLines = completedLines,
        sessionPlacement = sessionPlacement,
        sessionId = sessionId,
        status = if (error == null) RecognitionStatus.IDLE else RecognitionStatus.ERROR,
        lastError = error,
        restartLanguageCode = restartLanguageCode,
        infoMessage = listOfNotNull(completionMessage,
            captionBacklogMessage(skippedTranslations, skippedSpeechSegments))
            .takeIf { it.isNotEmpty() }?.joinToString("\n")
    )
}

internal fun translationBacklogMessage(skipped: Int): String? = if (skipped > 0)
    "번역이 밀려 대기 중인 자막 ${skipped}개를 생략했습니다. 최신 음성부터 이어갑니다." else null

internal fun recordDroppedTranslation(runtime: CaptionRuntimeState, lineId: Long): CaptionRuntimeState {
    val pending = runtime.transcriptLines.firstOrNull { it.id == lineId }
    if (pending == null || !pending.isFinal || pending.finalTranslationCommitted) return runtime
    val skipped = (runtime.skippedTranslations.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return runtime.copy(
        transcriptLines = runtime.transcriptLines.filterNot { it.id == lineId },
        skippedTranslations = skipped
    )
}

internal fun withRuntimeError(runtime: CaptionRuntimeState, message: String?): CaptionRuntimeState =
    if (runtime.stopping && message == null) runtime else runtime.copy(lastError = message)

internal fun captionBacklogMessage(translations: Int, speech: Int): String? = listOfNotNull(
    if (speech > 0) "음성 인식이 밀려 대기 중인 음성 구간 ${speech}개를 생략했습니다." else null,
    translationBacklogMessage(translations)
).takeIf { it.isNotEmpty() }?.joinToString("\n")

internal fun recordSkippedSpeechSegment(runtime: CaptionRuntimeState): CaptionRuntimeState {
    val skipped = (runtime.skippedSpeechSegments.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return runtime.copy(
        skippedSpeechSegments = skipped
    )
}

/** An explicit start owns clearing the one-time restart prompt; permission handling stays in Activity. */
internal fun preparedCaptionStart(runtime: CaptionRuntimeState): CaptionRuntimeState =
    runtime.copy(status = RecognitionStatus.IDLE, lastError = null, restartLanguageCode = null, infoMessage = null)

/** Clear retained content only after a service start, so cancelled permission dialogs keep it readable. */
internal fun startedCaptionSession(previous: CaptionRuntimeState = CaptionRuntimeState()): CaptionRuntimeState =
    CaptionRuntimeState(running = true, status = RecognitionStatus.PROCESSING, sessionId = previous.sessionId + 1L)

internal fun manuallySelectedCaptionLanguage(runtime: CaptionRuntimeState, code: String): CaptionRuntimeState =
    if (!runtime.running && runtime.restartLanguageCode != code) runtime.copy(restartLanguageCode = null) else runtime
