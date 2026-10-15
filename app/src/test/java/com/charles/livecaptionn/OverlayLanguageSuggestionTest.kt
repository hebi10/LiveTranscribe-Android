package com.charles.livecaptionn

import com.charles.livecaptionn.overlay.*
import com.charles.livecaptionn.service.CaptionPhase
import org.junit.Assert.*
import org.junit.Test

class OverlayLanguageSuggestionTest {
    @Test fun recommendationIsVisibleBeforeBacklogAndOrdinaryStatus() {
        val ui = OverlayUiState(phase = CaptionPhase.WAITING, suggestedLanguageCode = "ja", skippedTranslations = 3)
        assertTrue(overlayStatusLabel(ui).startsWith("일본어 전환 추천"))
        assertEquals(CaptionPhase.STOPPING.label, overlayStatusLabel(ui.copy(stopping = true, phase = CaptionPhase.STOPPING)))
    }

    @Test fun recommendationAddsTemporaryHeightOnlyWhileExpanded() {
        val ui = OverlayUiState(suggestedLanguageCode = "ja")
        assertNull(overlayExpandedMinHeightDp(ui))
        assertNull(overlayExpandedMinHeightDp(ui.copy(controlsExpanded = true, minimized = true)))
        assertEquals(300, overlayExpandedMinHeightDp(ui.copy(controlsExpanded = true)))
        assertEquals(220, overlayExpandedMinHeightDp(ui.copy(controlsExpanded = true, suggestedLanguageCode = null)))
    }

    @Test fun suggestionExpansionDoesNotOverwriteTheUsersCompactHeight() {
        val preferred = OverlayGeometry(0, 0, 220, 160)
        val geometry = OverlayGeometryState(preferred)
        val bounds = OverlayBounds(0, 0, 400, 800)
        val ui = OverlayUiState(controlsExpanded = true, suggestedLanguageCode = "ja")
        assertEquals(300, geometry.display(bounds, 220, 108,
            expandedMinHeight = overlayExpandedMinHeightDp(ui)).height)
        assertEquals(preferred, geometry.preferred)
        assertEquals(160, geometry.display(bounds, 220, 108,
            expandedMinHeight = overlayExpandedMinHeightDp(ui.copy(controlsExpanded = false))).height)
    }
}
