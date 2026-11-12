package com.charles.livecaptionn.overlay

import com.charles.livecaptionn.service.CaptionRuntimeState
import com.charles.livecaptionn.settings.CaptionDisplayMode

/** Transient scroll-back only: independent of the disk history preference. */
const val MAX_OVERLAY_MESSAGES = 40
const val MAX_CAPTION_MESSAGES = 2

/**
 * Only matching transcript lines may become visible captions. Raw runtime text
 * cannot be used as a fallback: it may belong to a different speech snapshot.
 */
fun overlayCaptionMessages(
    runtime: CaptionRuntimeState,
    stableCaptions: Boolean = true
): List<OverlayCaptionMessage> = runtime.transcriptLines.mapNotNull { line ->
    val translated = line.translatedText.trim()
    val committed = line.isFinal && line.finalTranslationCommitted
    if (translated.isBlank() || (stableCaptions && !committed)) null
    else OverlayCaptionMessage(
        id = line.id,
        originalText = line.originalText.trim(),
        translatedText = translated,
        isFinal = committed,
        correctionRanges = line.correctionRanges,
        correctionHighlightUntilMs = line.correctionHighlightUntilMs
    )
}.takeLast(MAX_OVERLAY_MESSAGES)

/** Apply the same bounds and stability rule at the UI boundary as well. */
fun visibleOverlayMessages(ui: OverlayUiState): List<OverlayCaptionMessage> {
    val messages = ui.messages.filter {
        it.translatedText.isNotBlank() && (!ui.stableCaptions || it.isFinal)
    }
    val visible = messages.takeLast(
        if (ui.displayMode == CaptionDisplayMode.CHAT) MAX_OVERLAY_MESSAGES else MAX_CAPTION_MESSAGES
    )
    return if (ui.correctionHighlightEnabled) visible else visible.map {
        it.copy(correctionRanges = emptyList(), correctionHighlightUntilMs = 0L)
    }
}
