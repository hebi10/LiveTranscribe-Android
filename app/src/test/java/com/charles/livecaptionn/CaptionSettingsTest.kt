package com.charles.livecaptionn

import com.charles.livecaptionn.settings.AudioSource
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

