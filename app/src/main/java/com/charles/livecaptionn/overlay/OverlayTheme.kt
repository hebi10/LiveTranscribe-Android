package com.charles.livecaptionn.overlay

import android.graphics.Color
import android.graphics.Typeface

data class OverlayTheme(val id: String, val label: String, val backgroundRgb: Int, val textRgb: Int)

object OverlayThemeCatalog {
    const val FREE_THEME_ID = "default"
    val THEMES: List<OverlayTheme> = listOf(
        OverlayTheme("default", "기본", Color.rgb(17, 17, 17), Color.WHITE),
        OverlayTheme("highContrast", "고대비", Color.BLACK, Color.WHITE)
    )
    fun find(id: String): OverlayTheme = THEMES.firstOrNull { it.id == id } ?: THEMES.first()
}

data class OverlayFont(val id: String, val label: String, val typeface: Typeface)

object OverlayFontCatalog {
    const val FREE_FONT_ID = "default"
    val FONTS: List<OverlayFont> = listOf(
        OverlayFont("default", "기본", Typeface.DEFAULT),
        OverlayFont("rounded", "굵게", Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD))
    )
    fun find(id: String): OverlayFont = FONTS.firstOrNull { it.id == id } ?: FONTS.first()
}
