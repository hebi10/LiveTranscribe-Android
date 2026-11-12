package com.charles.livecaptionn.settings

import android.graphics.Color

/** Fixed accessible swatches keep the selected hue visible on light and dark captions. */
object CorrectionHighlightPalette {
    data class Swatch(val id: String, val label: String, val lightHex: String)

    const val DEFAULT_COLOR = "#30C46C"
    const val DEFAULT_DURATION_MS = 1000

    val SWATCHES = listOf(
        Swatch(DEFAULT_COLOR, "초록", "#157638"),
        Swatch("#3B82F6", "파랑", "#1E4FC5"),
        Swatch("#E0A526", "노랑", "#855600"),
        Swatch("#F472B6", "분홍", "#A01D5E"),
        Swatch("#A78BFA", "보라", "#6741AD"),
        Swatch("#F97345", "주황", "#A2400B")
    )
    val DURATIONS_MS = listOf(1000, 1500, 2000)

    fun normalizeColor(input: String?): String =
        SWATCHES.firstOrNull { it.id.equals(input, ignoreCase = true) }?.id ?: DEFAULT_COLOR

    fun normalizeDuration(input: Int): Int =
        input.takeIf { it in DURATIONS_MS } ?: DEFAULT_DURATION_MS

    fun colorInt(id: String?, lightBackground: Boolean): Int {
        val swatch = SWATCHES.first { it.id == normalizeColor(id) }
        return Color.parseColor(if (lightBackground) swatch.lightHex else swatch.id)
    }
}
