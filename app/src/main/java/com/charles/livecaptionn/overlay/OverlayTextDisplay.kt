package com.charles.livecaptionn.overlay

/**
 * Resolved visual display properties for the overlay text views.
 *
 * Ensures original speech and translated captions are distinct, cleanly
 * formatted, and do not overlap.
 */
data class OverlayTextDisplay(
    val showOriginal: Boolean,
    val originalText: String,
    val originalTextSizeSp: Float,
    val translatedText: String,
    val translatedTextSizeSp: Float
)

/**
 * Determines text visibility, contents, and proportional scaling for the overlay.
 */
fun computeOverlayTextDisplay(ui: OverlayUiState): OverlayTextDisplay {
    val messages = visibleOverlayMessages(ui)
    val original = messages.joinToString("\n") { it.originalText }
    val translated = messages.joinToString("\n") { it.translatedText }
    val isIdentical = original.isNotBlank() && original.trim().equals(translated.trim(), ignoreCase = true)
    val showOriginal = ui.showOriginal && original.isNotBlank() && !isIdentical
    val scaledOriginalSize = (ui.textSizeSp * 0.82f).coerceIn(12f, 22f)
    val displayTranslated = translated.ifBlank { "…" }
    return OverlayTextDisplay(
        showOriginal = showOriginal,
        originalText = original,
        originalTextSizeSp = scaledOriginalSize,
        translatedText = displayTranslated,
        translatedTextSizeSp = ui.textSizeSp
    )
}

