package com.charles.livecaptionn.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsDataStoreCorrectionTest {
    @Test fun defaultsEnableSecondPassAndOneSecondGreenAccent() {
        val settings = SettingsDataStore.readSettings(mutablePreferencesOf())
        assertTrue(settings.aiCorrectionEnabled)
        assertTrue(settings.correctionHighlightEnabled)
        assertEquals("#30C46C", settings.correctionHighlightColor)
        assertEquals(1000, settings.correctionHighlightDurationMs)
    }

    @Test fun preferencesPersistUserChoicesAndClampInvalidValues() {
        val prefs = mutablePreferencesOf()
        SettingsDataStore.updatePreferences(prefs) {
            it.copy(aiCorrectionEnabled = false, correctionHighlightEnabled = false,
                correctionHighlightColor = "#3b82f6", correctionHighlightDurationMs = 1500)
        }
        val loaded = SettingsDataStore.readSettings(prefs)
        assertFalse(loaded.aiCorrectionEnabled)
        assertFalse(loaded.correctionHighlightEnabled)
        assertEquals("#3B82F6", loaded.correctionHighlightColor)
        assertEquals(1500, loaded.correctionHighlightDurationMs)

        val invalid = mutablePreferencesOf(
            stringPreferencesKey("correction_highlight_color") to "bad",
            intPreferencesKey("correction_highlight_duration_ms") to -1,
            booleanPreferencesKey("ai_correction_enabled") to false
        )
        val sanitized = SettingsDataStore.readSettings(invalid)
        assertEquals("#30C46C", sanitized.correctionHighlightColor)
        assertEquals(1000, sanitized.correctionHighlightDurationMs)
        assertFalse(sanitized.aiCorrectionEnabled)
    }
}
