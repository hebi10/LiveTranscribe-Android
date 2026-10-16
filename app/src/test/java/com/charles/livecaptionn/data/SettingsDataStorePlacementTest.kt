package com.charles.livecaptionn.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.charles.livecaptionn.settings.CaptionDisplayMode
import com.charles.livecaptionn.settings.CaptionPlacement
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsDataStorePlacementTest {
    private val placement = stringPreferencesKey("caption_placement")
    private val mode = stringPreferencesKey("caption_display_mode")

    @Test fun absentOrUnrecognizedPlacementKeepsExistingPopupBehavior() {
        assertEquals(CaptionPlacement.POPUP, SettingsDataStore.readSettings(mutablePreferencesOf()).captionPlacement)
        for (invalid in listOf("", "SPLIT", "unknown")) {
            assertEquals(CaptionPlacement.POPUP,
                SettingsDataStore.readSettings(mutablePreferencesOf(placement to invalid)).captionPlacement)
        }
    }

    @Test fun placementRoundTripsIndependentlyOfStableReadingModeNames() {
        for (displayMode in CaptionDisplayMode.entries) {
            val preferences = mutablePreferencesOf(mode to displayMode.name)
            SettingsDataStore.updatePreferences(preferences) { it.copy(captionPlacement = CaptionPlacement.SPLIT_SCREEN) }
            assertEquals("SPLIT_SCREEN", preferences[placement])
            assertEquals(displayMode.name, preferences[mode])
            val settings = SettingsDataStore.readSettings(preferences)
            assertEquals(CaptionPlacement.SPLIT_SCREEN, settings.captionPlacement)
            assertEquals(displayMode, settings.captionDisplayMode)
            SettingsDataStore.updatePreferences(preferences) { it.copy(showOriginal = true) }
            assertEquals(CaptionPlacement.SPLIT_SCREEN, SettingsDataStore.readSettings(preferences).captionPlacement)
        }
    }
}
