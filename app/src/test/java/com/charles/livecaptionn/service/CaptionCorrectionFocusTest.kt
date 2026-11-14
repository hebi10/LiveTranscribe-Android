package com.charles.livecaptionn.service

import com.charles.livecaptionn.settings.CaptionSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionCorrectionFocusTest {
    @Test fun normalViewingKeepsExistingCorrectionPreference() {
        assertTrue(shouldRefineCaptionDuringReading(CaptionSettings()))
        assertFalse(shouldRefineCaptionDuringReading(CaptionSettings(aiCorrectionEnabled = false)))
    }

    @Test fun focusedViewingDisablesSecondPassWithoutMutatingSavedSetting() {
        val settings = CaptionSettings(readingFocusMode = true, aiCorrectionEnabled = true)
        assertFalse(shouldRefineCaptionDuringReading(settings))
        assertTrue(settings.aiCorrectionEnabled)
        assertTrue(shouldRefineCaptionDuringReading(settings.copy(readingFocusMode = false)))
    }
}
