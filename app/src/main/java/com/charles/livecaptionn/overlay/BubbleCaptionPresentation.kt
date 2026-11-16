package com.charles.livecaptionn.overlay

import com.charles.livecaptionn.speech.RecognitionStatus

internal data class BubbleCaptionPresentation(
    val visible: Boolean,
    val message: OverlayCaptionMessage?,
    val expiresAtMs: Long?
)

/** Monotonic-clock presentation state: repeated flow emissions never restart a caption timer. */
internal class BubbleCaptionPresentationState {
    private var lastSeenKey: Pair<Long, String>? = null
    private var retained: OverlayCaptionMessage? = null
    private var deadline: Long? = null

    fun clear() {
        lastSeenKey = null
        retained = null
        deadline = null
    }

    fun reduce(ui: OverlayUiState, durationMs: Int, nowMs: Long): BubbleCaptionPresentation {
        val unavailable = ui.stopping || ui.status == RecognitionStatus.PAUSED ||
            ui.status == RecognitionStatus.ERROR || ui.status == RecognitionStatus.IDLE
        if (unavailable) {
            retained = null
            deadline = null
            return BubbleCaptionPresentation(false, null, null)
        }

        val newest = ui.messages.lastOrNull {
            it.translatedText.isNotBlank() && (!ui.stableCaptions || it.isFinal)
        }
        val key = newest?.let { it.id to it.translatedText }
        if (key != null && key != lastSeenKey) {
            lastSeenKey = key
            retained = newest
            deadline = nowMs + durationMs.coerceIn(3000, 8000)
        } else if (newest != null && retained != null) {
            retained = newest
        }

        val busy = ui.recognizing || ui.translating
        if (!busy && deadline != null && nowMs >= deadline!!) {
            retained = null
            deadline = null
        }
        return BubbleCaptionPresentation(
            visible = retained != null,
            message = retained,
            expiresAtMs = if (busy) null else deadline
        )
    }
}
