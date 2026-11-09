package com.charles.livecaptionn.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.charles.livecaptionn.settings.AppThemeMode
import com.charles.livecaptionn.settings.CaptionPlacement
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsDataStoreThemeTest {
    private val theme = stringPreferencesKey("app_theme_mode")

    @Test fun existingSettingsWithoutThemeRetainDarkAppearance() {
        val prefs = mutablePreferencesOf(stringPreferencesKey("source") to "ja")
        assertEquals(AppThemeMode.DARK, SettingsDataStore.readSettings(prefs).appThemeMode)
        SettingsDataStore.updatePreferences(prefs) { it.copy(textSizeSp = 22f) }
        assertEquals("DARK", prefs[theme])
        assertEquals("ja", SettingsDataStore.readSettings(prefs).sourceLanguageCode)
        assertEquals(22f, SettingsDataStore.readSettings(prefs).textSizeSp)
    }

    @Test fun everyModePersistsWithoutChangingCaptionPreferences() {
        for (mode in AppThemeMode.entries) {
            val prefs = mutablePreferencesOf()
            SettingsDataStore.updatePreferences(prefs) {
                it.copy(appThemeMode = mode, captionPlacement = CaptionPlacement.SPLIT_SCREEN, showOriginal = true)
            }
            val updated = SettingsDataStore.readSettings(prefs)
            assertEquals(mode, updated.appThemeMode)
            assertEquals(mode.name, prefs[theme])
            assertEquals(CaptionPlacement.SPLIT_SCREEN, updated.captionPlacement)
            assertEquals(true, updated.showOriginal)
            SettingsDataStore.updatePreferences(prefs) { it.copy(saveHistory = true) }
            assertEquals(mode, SettingsDataStore.readSettings(prefs).appThemeMode)
        }
    }

    @Test fun corruptThemeNameFallsBackToDark() {
        val prefs = mutablePreferencesOf(theme to "INVALID")
        assertEquals(AppThemeMode.DARK, SettingsDataStore.readSettings(prefs).appThemeMode)
        SettingsDataStore.updatePreferences(prefs) { it.copy(showOriginal = true) }
        assertEquals("DARK", prefs[theme])
    }
}
