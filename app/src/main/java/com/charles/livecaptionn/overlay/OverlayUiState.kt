package com.charles.livecaptionn.overlay

import com.charles.livecaptionn.speech.RecognitionStatus
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
    /** Details for local capture, recognition, translation, or storage failures. */
    val statusDetail: String? = null,
    val textSizeSp: Float = 20f,
    val opacity: Float = 0.65f,
    val showOriginal: Boolean = true,
    val minimized: Boolean = false,
    val suggestedLanguageCode: String? = null,
    /** Optional local font and theme identifiers; no subscriptions. */
    val themeId: String = OverlayThemeCatalog.FREE_THEME_ID,
    val fontId: String = OverlayFontCatalog.FREE_FONT_ID
)

