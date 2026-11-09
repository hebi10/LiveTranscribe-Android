package com.charles.livecaptionn.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayChromeLayoutTest {
    @Test fun minimizedHeaderContainsAccessibleTouchTargets() {
        assertEquals(48, OverlayChromeMetrics.TOUCH_DP)
        assertTrue(OverlayChromeMetrics.MINIMIZED_HEIGHT_DP >=
            OverlayChromeMetrics.TOUCH_DP + 2 * OverlayChromeMetrics.VERTICAL_PADDING_DP)
        assertTrue(OverlayChromeMetrics.LABELED_CONTROL_MIN_WIDTH_DP >= OverlayChromeMetrics.TOUCH_DP)
    }

    @Test fun defaultPopupRetainsCaptionAndResizeWithCompactControls() {
        val chrome = overlayChromeState(OverlayUiState(), followingLatest = true)
        assertTrue(chrome.normalHeader)
        assertTrue(chrome.captionBody)
        assertTrue(chrome.resizeHandle)
        assertFalse(chrome.minimizedHeader)
        assertFalse(chrome.expandedControls)
        assertFalse(chrome.latestAction)
        assertTrue(chrome.stopEnabled)
    }

    @Test fun expandedViewKeepsContentAndOffersReadingRecovery() {
        val chrome = overlayChromeState(OverlayUiState(controlsExpanded = true), followingLatest = false)
        assertTrue(chrome.normalHeader)
        assertTrue(chrome.captionBody)
        assertTrue(chrome.expandedControls)
        assertTrue(chrome.latestAction)
    }

    @Test fun minimizedViewHidesBodyButRetainsCloseAndRestore() {
        val chrome = overlayChromeState(OverlayUiState(controlsExpanded = true, minimized = true), followingLatest = false)
        assertFalse(chrome.normalHeader)
        assertTrue(chrome.minimizedHeader)
        assertFalse(chrome.captionBody)
        assertFalse(chrome.expandedControls)
        assertFalse(chrome.resizeHandle)
        assertFalse(chrome.latestAction)
    }

    @Test fun stoppingBlocksStopWhileRetainingRestoreInteraction() {
        val chrome = overlayChromeState(OverlayUiState(stopping = true, minimized = true), followingLatest = false)
        assertFalse(chrome.stopEnabled)
        assertTrue(chrome.minimizedHeader)
    }

    @Test fun popupDoesNotChangeStoredGeometryOnVisualStateChanges() {
        val preferred = OverlayGeometryPreference(24, 260, 340, 160)
        val geometry = OverlayGeometryState(preferred, 1f)
        val bounds = OverlayBounds(0, 24, 1080, 1800)
        val ui = OverlayUiState(controlsExpanded = true, minimized = true)
        val chrome = overlayChromeState(ui, followingLatest = false)
        assertTrue(chrome.minimizedHeader)
        geometry.display(bounds, 220, 108, minimizedHeight = OverlayChromeMetrics.MINIMIZED_HEIGHT_DP)
        assertEquals(preferred, geometry.preference)
    }
}
