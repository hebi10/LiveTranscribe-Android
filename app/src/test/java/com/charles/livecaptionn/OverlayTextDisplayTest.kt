package com.charles.livecaptionn

import com.charles.livecaptionn.overlay.OverlayUiState
import com.charles.livecaptionn.overlay.OverlayCaptionMessage
import com.charles.livecaptionn.overlay.computeOverlayTextDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayTextDisplayTest {

    @Test
    fun computeOverlayTextDisplay_transcribingAndTranslating_showsBothDistinctly() {
        val ui = OverlayUiState(
            messages = listOf(OverlayCaptionMessage(1L, "Good morning everyone", "Chào buổi sáng mọi người", true)),
            showOriginal = true,
            textSizeSp = 20f
        )
        val display = computeOverlayTextDisplay(ui)

        assertTrue(display.showOriginal)
        assertEquals("Good morning everyone", display.originalText)
        assertEquals("Chào buổi sáng mọi người", display.translatedText)
        assertEquals(20f, display.translatedTextSizeSp, 0.01f)
        assertEquals(16.4f, display.originalTextSizeSp, 0.01f)
    }

    @Test
    fun computeOverlayTextDisplay_showOriginalDisabled_hidesOriginal() {
        val ui = OverlayUiState(
            messages = listOf(OverlayCaptionMessage(1L, "Good morning", "Chào buổi sáng", true)),
            showOriginal = false
        )
        val display = computeOverlayTextDisplay(ui)

        assertFalse(display.showOriginal)
        assertEquals("Chào buổi sáng", display.translatedText)
    }

    @Test
    fun computeOverlayTextDisplay_blankOriginal_hidesOriginal() {
        val ui = OverlayUiState(
            messages = listOf(OverlayCaptionMessage(1L, "", "Chào buổi sáng", true)),
            showOriginal = true
        )
        val display = computeOverlayTextDisplay(ui)

        assertFalse(display.showOriginal)
    }

    @Test
    fun computeOverlayTextDisplay_pendingTranslation_keepsOriginalPairedAndHidden() {
        val ui = OverlayUiState(
            originalText = "Listening to audio...",
            transcriptText = "",
            showOriginal = true
        )
        val display = computeOverlayTextDisplay(ui)

        assertFalse(display.showOriginal)
        assertEquals("", display.originalText)
        assertEquals("…", display.translatedText)
    }

    @Test
    fun computeOverlayTextDisplay_identicalText_avoidsDuplication() {
        val ui = OverlayUiState(
            messages = listOf(OverlayCaptionMessage(1L, "Hello world", "Hello world", true)),
            showOriginal = true
        )
        val display = computeOverlayTextDisplay(ui)

        assertFalse("Identical text should not be duplicated on the overlay", display.showOriginal)
        assertEquals("Hello world", display.translatedText)
    }

    @Test
    fun computeOverlayTextDisplay_identicalTextCaseInsensitive_avoidsDuplication() {
        val ui = OverlayUiState(
            messages = listOf(OverlayCaptionMessage(1L, "Hello World  ", "hello world", true)),
            showOriginal = true
        )
        val display = computeOverlayTextDisplay(ui)

        assertFalse(display.showOriginal)
    }

    @Test
    fun computeOverlayTextDisplay_textSizeScaling_clampsProperly() {
        // High text size clamped at 22sp
        val largeUi = OverlayUiState(textSizeSp = 36f)
        assertEquals(22f, computeOverlayTextDisplay(largeUi).originalTextSizeSp, 0.01f)

        // Low text size floored at 12sp
        val smallUi = OverlayUiState(textSizeSp = 10f)
        assertEquals(12f, computeOverlayTextDisplay(smallUi).originalTextSizeSp, 0.01f)
    }
}

