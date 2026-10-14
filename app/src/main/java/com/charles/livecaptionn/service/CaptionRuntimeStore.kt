package com.charles.livecaptionn.service

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
    val finalTranslationCommitted: Boolean = isFinal && translatedText.isNotBlank()
)

data class CaptionRuntimeState(
    val originalText: String = "",
    val translatedText: String = "",
    val transcriptLines: List<CaptionRuntimeLine> = emptyList(),
    val status: RecognitionStatus = RecognitionStatus.IDLE,
    val running: Boolean = false,
    val paused: Boolean = false,
    val lastError: String? = null,
    /** Number of pending translations skipped to keep up during this session. */
    val skippedTranslations: Int = 0,
    val suggestedLanguageCode: String? = null
)

class CaptionRuntimeStore {
    private val mutable = MutableStateFlow(CaptionRuntimeState())
    val state: StateFlow<CaptionRuntimeState> = mutable.asStateFlow()

    fun update(transform: (CaptionRuntimeState) -> CaptionRuntimeState) {
        mutable.update(transform)
    }
}


internal fun completedCaptionSession(
    previousFailure: String?,
    terminalFailure: String?,
    drained: Boolean,
    completionMessage: String? = null
): CaptionRuntimeState {
    val error = terminalFailure ?: previousFailure ?: if (!drained)
        "번역 종료 시간이 초과되어 마지막 일부 문장이 저장되지 않았습니다." else null
    return CaptionRuntimeState(
        status = if (error == null) RecognitionStatus.IDLE else RecognitionStatus.ERROR,
        lastError = error ?: completionMessage
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
        skippedTranslations = skipped,
        lastError = runtime.lastError?.takeUnless {
            it == translationBacklogMessage(runtime.skippedTranslations)
        } ?: translationBacklogMessage(skipped)
    )
}

internal fun withRuntimeError(runtime: CaptionRuntimeState, message: String?): CaptionRuntimeState =
    runtime.copy(lastError = message ?: translationBacklogMessage(runtime.skippedTranslations))
