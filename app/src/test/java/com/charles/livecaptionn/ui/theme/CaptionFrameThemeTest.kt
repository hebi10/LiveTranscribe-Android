package com.charles.livecaptionn.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptionFrameThemeTest {
    @Test fun lightUsesNeutralColorsAndSemanticError() {
        assertEquals(Color(0xFFFFFFFF), CaptionFrameLightColors.background)
        assertEquals(Color(0xFF171717), CaptionFrameLightColors.onBackground)
        assertEquals(Color(0xFFF5F5F5), CaptionFrameLightColors.surfaceContainer)
        assertEquals(Color(0xFFB42318), CaptionFrameLightColors.error)
    }

    @Test fun darkUsesStudioBackgroundAndReadableText() {
        assertEquals(Color(0xFF101010), CaptionFrameDarkColors.background)
        assertEquals(Color(0xFFF5F5F5), CaptionFrameDarkColors.onBackground)
        assertEquals(Color(0xFF202020), CaptionFrameDarkColors.surfaceContainer)
        assertEquals(Color(0xFFFF8D82), CaptionFrameDarkColors.error)
    }
}
