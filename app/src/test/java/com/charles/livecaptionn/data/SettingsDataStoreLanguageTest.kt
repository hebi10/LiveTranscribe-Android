package com.charles.livecaptionn.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.*
import org.junit.Test

class SettingsDataStoreLanguageTest {
    private val source = stringPreferencesKey("source")

    @Test fun absentPreferenceReadsAutomaticMode() {
        assertEquals("auto", SettingsDataStore.readSettings(mutablePreferencesOf()).sourceLanguageCode)
    }

    @Test fun unrelatedFirstUpdateKeepsTheSameAutomaticDefaultAsRead() {
        val preferences = mutablePreferencesOf()
        SettingsDataStore.updatePreferences(preferences) { current ->
            assertEquals("auto", current.sourceLanguageCode)
            current.copy(textSizeSp = 30f)
        }
        assertEquals("auto", preferences[source])
        assertEquals("auto", SettingsDataStore.readSettings(preferences).sourceLanguageCode)
        assertEquals(30f, SettingsDataStore.readSettings(preferences).textSizeSp)
    }

    @Test fun existingManualChoiceSurvivesReadingAndUnrelatedUpdate() {
        listOf("en", "ja").forEach { mode ->
            val preferences = mutablePreferencesOf(source to mode)
            assertEquals(mode, SettingsDataStore.readSettings(preferences).sourceLanguageCode)
            SettingsDataStore.updatePreferences(preferences) { it.copy(showOriginal = true) }
            assertEquals(mode, preferences[source])
        }
    }

    @Test fun invalidPersistedModesUseTheSameAutomaticFallbackForReadAndUpdate() {
        listOf("", "fr", "ko", "und", "obsolete").forEach { invalid ->
            val preferences = mutablePreferencesOf(source to invalid)
            assertEquals("auto", SettingsDataStore.readSettings(preferences).sourceLanguageCode)
            SettingsDataStore.updatePreferences(preferences) { current ->
                assertEquals("auto", current.sourceLanguageCode)
                current.copy(showOriginal = true)
            }
            assertEquals("auto", preferences[source])
        }
    }

    @Test fun invalidNewSelectionCannotPersistAnUnsupportedManualMode() {
        val preferences = mutablePreferencesOf(source to "en")
        SettingsDataStore.updatePreferences(preferences) { it.copy(sourceLanguageCode = "fr") }
        assertEquals("auto", preferences[source])
        SettingsDataStore.updatePreferences(preferences) { it.copy(sourceLanguageCode = "ja") }
        assertEquals("ja", preferences[source])
    }
}
