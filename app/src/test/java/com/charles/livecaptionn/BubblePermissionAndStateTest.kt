package com.charles.livecaptionn

import com.charles.livecaptionn.settings.CaptionPlacement
import com.charles.livecaptionn.settings.CaptionSettings
import com.charles.livecaptionn.ui.MainUiState
import com.charles.livecaptionn.overlay.OverlayUiState
import com.charles.livecaptionn.service.CaptionRuntimeState
import com.charles.livecaptionn.service.buildOverlayUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePermissionAndStateTest {
    @Test fun bubbleNeedsOverlayPermissionButSplitDoesNot() {
        assertTrue(MainUiState(settings = CaptionSettings(
            captionPlacement = CaptionPlacement.BUBBLE)).overlayPermissionRequired)
        assertTrue(MainUiState(settings = CaptionSettings(
            captionPlacement = CaptionPlacement.POPUP)).overlayPermissionRequired)
        assertFalse(MainUiState(settings = CaptionSettings(
            captionPlacement = CaptionPlacement.SPLIT_SCREEN)).overlayPermissionRequired)
    }

    @Test fun bubbleDoesNotInventTranslationFromRawRecognition() {
        val runtime = CaptionRuntimeState(originalText = "raw speech", translating = true)
        val ui = buildOverlayUiState(runtime, CaptionSettings(captionPlacement = CaptionPlacement.BUBBLE))
        assertTrue(ui.translating)
        assertTrue(ui.messages.isEmpty())
        assertEquals("", ui.translatedText)
    }

    @Test fun existingPopupAndSplitDefaultsRemainIndependent() {
        val settings = CaptionSettings()
        assertEquals(CaptionPlacement.POPUP, settings.captionPlacement)
        assertFalse(settings.saveHistory)
        assertFalse(settings.bubbleShowOriginal)
        assertEquals(5000, settings.bubbleHoldMs)
        val split = settings.copy(captionPlacement = CaptionPlacement.SPLIT_SCREEN)
        assertEquals(24, split.overlayX)
        assertEquals(260, split.bubbleY)
        assertEquals(340, split.overlayWidthDp)
    }
}
