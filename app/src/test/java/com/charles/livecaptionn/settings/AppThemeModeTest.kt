package com.charles.livecaptionn.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeModeTest {
    @Test fun legacyAndUnknownNamesStayDark() {
        assertEquals(AppThemeMode.DARK, AppThemeMode.fromName(null))
        assertEquals(AppThemeMode.DARK, AppThemeMode.fromName(""))
        assertEquals(AppThemeMode.DARK, AppThemeMode.fromName("UNKNOWN"))
    }

    @Test fun systemModeTracksTheDeviceWhileExplicitModesDoNot() {
        assertFalse(AppThemeMode.SYSTEM.useDarkColors(false))
        assertTrue(AppThemeMode.SYSTEM.useDarkColors(true))
        assertFalse(AppThemeMode.LIGHT.useDarkColors(false))
        assertFalse(AppThemeMode.LIGHT.useDarkColors(true))
        assertTrue(AppThemeMode.DARK.useDarkColors(false))
        assertTrue(AppThemeMode.DARK.useDarkColors(true))
    }

    @Test fun allModesReadBack() {
        for (mode in AppThemeMode.entries) assertEquals(mode, AppThemeMode.fromName(mode.name))
    }
}
