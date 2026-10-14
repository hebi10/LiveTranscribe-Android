package com.charles.livecaptionn

import com.charles.livecaptionn.settings.AudioSource
import com.charles.livecaptionn.settings.CaptionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CaptionSettingsTest {
    @Test fun defaults_sourceIsEnglishAndTargetIsKorean() {
        val s = CaptionSettings()
        assertEquals("en", s.sourceLanguageCode)
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

    @Test fun settingsCopy_doesNotChangeTranslationDirection() {
        val s = CaptionSettings().copy(textSizeSp = 30f)
        assertEquals("ko", s.targetLanguageCode)
        assertEquals(30f, s.textSizeSp)
    }
}
