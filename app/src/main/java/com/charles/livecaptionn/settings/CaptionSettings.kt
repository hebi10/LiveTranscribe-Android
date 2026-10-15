package com.charles.livecaptionn.settings

data class CaptionSettings(
    val sourceLanguageCode: String = RecognitionLanguages.AUTO,
    val targetLanguageCode: String = "ko",
    val textSizeSp: Float = 22f,
    val overlayOpacity: Float = 0.85f,
    val showOriginal: Boolean = false,
    val captionDisplayMode: CaptionDisplayMode = CaptionDisplayMode.CAPTIONS,
    val stableCaptions: Boolean = true,
    val overlayControlsExpanded: Boolean = false,
    val overlayX: Int = 24,
    val overlayY: Int = 260,
    val overlayMinimized: Boolean = false,
    val audioSource: AudioSource = AudioSource.SYSTEM,
    val overlayWidthDp: Int = DEFAULT_OVERLAY_WIDTH_DP,
    val overlayHeightDp: Int = DEFAULT_OVERLAY_HEIGHT_DP,
    val overlayThemeId: String = "default",
    val overlayFontId: String = "default",
    val saveHistory: Boolean = false
) {
    companion object {
        const val DEFAULT_OVERLAY_WIDTH_DP = 340
        const val DEFAULT_OVERLAY_HEIGHT_DP = 160
        const val MIN_OVERLAY_WIDTH_DP = 180
        const val MIN_OVERLAY_HEIGHT_DP = 80
    }
}

