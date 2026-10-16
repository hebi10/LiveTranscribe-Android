package com.charles.livecaptionn.overlay

import com.charles.livecaptionn.speech.RecognitionStatus
import com.charles.livecaptionn.service.CaptionPhase
import com.charles.livecaptionn.settings.CaptionDisplayMode

data class OverlayCaptionMessage(
    val id: Long,
    val originalText: String,
    val translatedText: String,
    val isFinal: Boolean
)

data class OverlayUiState(
    val originalText: String = "",
    val translatedText: String = "",
    val transcriptText: String = "",
    /** Source and translation snapshots are always taken from the same runtime line. */
    val messages: List<OverlayCaptionMessage> = emptyList(),
    val displayMode: CaptionDisplayMode = CaptionDisplayMode.CAPTIONS,
    val stableCaptions: Boolean = true,
    val controlsExpanded: Boolean = false,
    val status: RecognitionStatus = RecognitionStatus.IDLE,
    val phase: CaptionPhase = CaptionPhase.IDLE,
    val stopping: Boolean = false,
    /** Details for local capture, recognition, translation, or storage failures. */
    val statusDetail: String? = null,
    val information: String? = null,
    val skippedTranslations: Int = 0,
    val skippedSpeechSegments: Int = 0,
    val textSizeSp: Float = 18f,
    val opacity: Float = 0.65f,
    val showOriginal: Boolean = true,
    val minimized: Boolean = false,
    val suggestedLanguageCode: String? = null,
    /** Optional local font and theme identifiers; no subscriptions. */
    val themeId: String = OverlayThemeCatalog.FREE_THEME_ID,
    val fontId: String = OverlayFontCatalog.FREE_FONT_ID
)


/** A normal collapsed overlay keeps only its 44dp header; notices reserve space only while present. */
internal fun showsOverlayNotice(ui: OverlayUiState): Boolean =
    !ui.minimized && !ui.statusDetail.isNullOrBlank()

internal fun overlayStatusLabel(ui: OverlayUiState): String {
    val suggestion = when (ui.suggestedLanguageCode) {
        "en" -> "영어 전환 추천"
        "ja" -> "일본어 전환 추천"
        else -> null
    }
    return when {
        ui.stopping -> ui.phase.label
        suggestion != null -> "$suggestion · ${ui.phase.label}"
        ui.skippedTranslations > 0 || ui.skippedSpeechSegments > 0 -> "${ui.phase.label} · 일부 자막 생략"
        else -> ui.phase.label
    }
}

/** Expansion is presentation-only; never save it as the user's preferred popup height. */
internal fun overlayExpandedMinHeightDp(ui: OverlayUiState?): Int? = when {
    ui?.controlsExpanded != true || ui.minimized -> null
    ui.suggestedLanguageCode != null -> 300
    else -> 220
}
