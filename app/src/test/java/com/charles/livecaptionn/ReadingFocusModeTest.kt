package com.charles.livecaptionn

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.charles.livecaptionn.data.SettingsDataStore
import com.charles.livecaptionn.overlay.OverlayUiState
import com.charles.livecaptionn.overlay.computeOverlayTextDisplay
import com.charles.livecaptionn.overlay.visibleOverlayMessages
import com.charles.livecaptionn.overlay.OverlayCaptionMessage
import com.charles.livecaptionn.service.CaptionRuntimeLine
import com.charles.livecaptionn.service.CaptionRuntimeState
import com.charles.livecaptionn.service.buildOverlayUiState
import com.charles.livecaptionn.settings.CaptionDisplayMode
import com.charles.livecaptionn.settings.CaptionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingFocusModeTest {
    private val lines = (1..3).map {
        CaptionRuntimeLine(it.toLong(), "Source $it", "한국어 자막 $it", isFinal = true)
    }

    @Test fun optInFocusPreferencePersistsIndependently() {
        val prefs = mutablePreferencesOf()
        assertFalse(SettingsDataStore.readSettings(prefs).readingFocusMode)
        SettingsDataStore.updatePreferences(prefs) { it.copy(readingFocusMode = true) }
        assertTrue(SettingsDataStore.readSettings(prefs).readingFocusMode)
        SettingsDataStore.updatePreferences(prefs) { it.copy(readingFocusMode = false) }
        assertFalse(SettingsDataStore.readSettings(prefs).readingFocusMode)
    }

    @Test fun focusShowsOnlyLatestWithoutTruncatingAndTemporarilyHidesOriginal() {
        val full = "긴 문장입니다. 자막은 자르지 않고 전체 내용을 읽을 수 있습니다."
        val ui = buildOverlayUiState(CaptionRuntimeState(transcriptLines =
            lines + CaptionRuntimeLine(4L, "source four", full, isFinal = true)),
            CaptionSettings(readingFocusMode = true, showOriginal = true))
        assertEquals(listOf(4L), visibleOverlayMessages(ui).map { it.id })
        assertEquals(full, computeOverlayTextDisplay(ui).translatedText)
        assertFalse(computeOverlayTextDisplay(ui).showOriginal)
        assertTrue(ui.showOriginal)
    }

    @Test fun turningFocusOffRestoresTwoCaptionsAndOriginal() {
        val ui = buildOverlayUiState(CaptionRuntimeState(transcriptLines = lines),
            CaptionSettings(showOriginal = true, readingFocusMode = false))
        assertEquals(listOf(2L, 3L), visibleOverlayMessages(ui).map { it.id })
        assertTrue(computeOverlayTextDisplay(ui).showOriginal)
    }

    @Test fun focusDoesNotHideHistoryWhenChatSelected() {
        val ui = buildOverlayUiState(CaptionRuntimeState(transcriptLines = lines),
            CaptionSettings(readingFocusMode = true, showOriginal = true,
                captionDisplayMode = CaptionDisplayMode.CHAT))
        assertEquals(listOf(1L, 2L, 3L), visibleOverlayMessages(ui).map { it.id })
        assertTrue(computeOverlayTextDisplay(ui).showOriginal)
    }

    @Test fun focusStillExcludesUncommittedAndEmptyCaptions() {
        val ui = OverlayUiState(readingFocusMode = true, messages = listOf(
            OverlayCaptionMessage(1L, "one", "하나", true),
            OverlayCaptionMessage(2L, "two", "둘", false),
            OverlayCaptionMessage(3L, "three", "", true)))
        assertEquals(listOf(1L), visibleOverlayMessages(ui).map { it.id })
    }
}
