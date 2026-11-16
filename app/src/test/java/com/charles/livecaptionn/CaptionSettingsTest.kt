package com.charles.livecaptionn

import com.charles.livecaptionn.settings.AudioSource
import com.charles.livecaptionn.settings.CaptionPlacement
import com.charles.livecaptionn.settings.CaptionSettings
import com.charles.livecaptionn.settings.CaptionDisplayMode
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CaptionSettingsTest {
    @Test fun defaults_sourceIsAutomaticAndTargetIsKorean() {
        val s = CaptionSettings()
        assertEquals("auto", s.sourceLanguageCode)
        assertEquals("ko", s.targetLanguageCode)
    }

    @Test fun defaults_keepExistingPopupPlacementAndUnknownValuesFallBack() {
        assertEquals(CaptionPlacement.POPUP, CaptionSettings().captionPlacement)
        assertEquals(CaptionPlacement.POPUP, CaptionPlacement.fromName(null))
        assertEquals(CaptionPlacement.POPUP, CaptionPlacement.fromName("future-mode"))
        assertEquals(CaptionPlacement.SPLIT_SCREEN, CaptionPlacement.fromName("SPLIT_SCREEN"))
        assertEquals(CaptionPlacement.BUBBLE, CaptionPlacement.fromName("BUBBLE"))
    }

    @Test fun bubbleSettings_areSeparateFromExistingPopup() {
        val existing = CaptionSettings()
        val bubble = existing.copy(captionPlacement = CaptionPlacement.BUBBLE, bubbleY = 520,
            bubbleSide = com.charles.livecaptionn.settings.BubbleSide.LEFT)
        assertEquals(CaptionPlacement.POPUP, existing.captionPlacement)
        assertEquals(24, bubble.overlayX)
        assertEquals(260, bubble.overlayY)
        assertEquals(520, bubble.bubbleY)
        assertEquals(com.charles.livecaptionn.settings.BubbleSide.LEFT, bubble.bubbleSide)
    }

    @Test fun bubblePreferencesRejectUnknownValues() {
        val config = com.charles.livecaptionn.settings.BubbleCaptionOptions
        assertEquals(5000, config.duration(999))
        assertEquals(3000, config.duration(3000))
        assertEquals(16f, config.textSize(Float.NaN))
        assertEquals(20f, config.textSize(20f))
        assertEquals(com.charles.livecaptionn.settings.BubbleSide.RIGHT,
            com.charles.livecaptionn.settings.BubbleSide.fromName("unknown"))
    }

    @Test fun defaults_captureVideoAudio() {
        assertEquals(AudioSource.SYSTEM, CaptionSettings().audioSource)
    }

    @Test fun defaults_keepPrivacy() {
        val s = CaptionSettings()
        assertFalse(s.saveHistory)
        assertFalse(s.showOriginal)
    }

    @Test fun defaults_preferStableCaptionsAndCompactControls() {
        val settings = CaptionSettings()
        assertTrue(settings.stableCaptions)
        assertFalse(settings.overlayControlsExpanded)
        assertEquals(CaptionDisplayMode.CAPTIONS, settings.captionDisplayMode)
    }

    @Test fun unknownSavedDisplayModeFallsBackSafely() {
        assertEquals(CaptionDisplayMode.CAPTIONS, CaptionDisplayMode.fromName("UNKNOWN"))
        assertEquals(CaptionDisplayMode.CHAT, CaptionDisplayMode.fromName("CHAT"))
    }

    @Test fun settingsCopy_doesNotChangeTranslationDirection() {
        val s = CaptionSettings().copy(textSizeSp = 30f)
        assertEquals("ko", s.targetLanguageCode)
        assertEquals(30f, s.textSizeSp)
    }
}

