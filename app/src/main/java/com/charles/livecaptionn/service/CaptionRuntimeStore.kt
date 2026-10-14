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
    val isFinal: Boolean = false
)

data class CaptionRuntimeState(
    val originalText: String = "",
    val translatedText: String = "",
    val transcriptLines: List<CaptionRuntimeLine> = emptyList(),
    val status: RecognitionStatus = RecognitionStatus.IDLE,
    val running: Boolean = false,
    val paused: Boolean = false,
    val lastError: String? = null,
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
