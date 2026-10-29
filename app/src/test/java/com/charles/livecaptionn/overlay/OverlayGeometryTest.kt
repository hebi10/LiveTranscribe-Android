package com.charles.livecaptionn.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayGeometryTest {
    @Test fun staleCoordinatesAndOversizeWindowFitNewScreen() {
        assertEquals(OverlayGeometry(10, 24, 300, 400),
            clampOverlayGeometry(OverlayGeometry(900, -500, 800, 700),
                OverlayBounds(10, 24, 310, 424), 180, 80))
    }

    @Test fun dragStopsAtUsableEdges() {
        assertEquals(OverlayGeometry(120, 344, 180, 80),
            clampOverlayGeometry(OverlayGeometry(999, 999, 180, 80),
                OverlayBounds(0, 24, 300, 424), 180, 80))
    }

    @Test fun minimumSizeShrinksToFitTinyUsableWindow() {
        assertEquals(OverlayGeometry(0, 0, 100, 50),
            clampOverlayGeometry(OverlayGeometry(20, 20, 1, 1),
                OverlayBounds(0, 0, 100, 50), 180, 80))
    }

    @Test fun collapsedInsetsStillProduceValidGeometry() {
        assertEquals(OverlayGeometry(30, 30, 1, 1),
            clampOverlayGeometry(OverlayGeometry(-1, -1, 100, 100),
                OverlayBounds(30, 30, 20, 20), 180, 80))
    }

    @Test fun rotationClampsRestoredPortraitGeometry() {
        assertEquals(OverlayGeometry(500, 160, 280, 120),
            clampOverlayGeometry(OverlayGeometry(500, 650, 280, 120),
                OverlayBounds(0, 24, 800, 280), 180, 80))
    }

    private val wide = OverlayBounds(0, 24, 1200, 900)
    private val narrow = OverlayBounds(0, 24, 320, 500)
    private val preference = OverlayGeometry(500, 650, 600, 220)

    @Test fun narrowDisplayDoesNotReplaceWidePreference() {
        val state = OverlayGeometryState(preference)
        assertEquals(OverlayGeometry(0, 280, 320, 220), state.display(narrow, 220, 108))
        assertEquals(preference, state.preferred)
        assertEquals(preference, state.display(wide, 220, 108))
    }

    @Test fun presentationModesNeverReplaceNormalSizeOrPosition() {
        val normal = OverlayGeometry(600, 740, 300, 120)
        val state = OverlayGeometryState(normal)
        assertEquals(OverlayGeometry(600, 680, 300, 220),
            state.display(wide, 220, 108, expandedMinHeight = 220))
        assertEquals(OverlayGeometry(20, 444, 300, 56),
            state.display(narrow, 220, 108, minimizedHeight = 56))
        assertEquals(normal, state.preferred)
        assertEquals(normal, state.display(wide, 220, 108))
    }

    @Test fun movingOnNarrowScreenPreservesPreferredNormalDimensions() {
        val state = OverlayGeometryState(preference)
        state.moveTo(-500, 999, narrow, 220, 108, minimizedHeight = 56)
        assertEquals(OverlayGeometry(0, 444, 600, 220), state.preferred)
        assertEquals(OverlayGeometry(0, 444, 600, 220), state.display(wide, 220, 108))
    }

    @Test fun directionalMovementUsesDisplayedPositionAndStopsAtEdges() {
        val state = OverlayGeometryState(OverlayGeometry(900, 900, 220, 108))
        state.moveBy(-24, -24, narrow, 220, 108)
        assertEquals(OverlayGeometry(76, 368, 220, 108), state.preferred)
        state.moveBy(999, 999, narrow, 220, 108)
        assertEquals(OverlayGeometry(100, 392, 220, 108), state.preferred)
        state.moveBy(-999, -999, narrow, 220, 108)
        assertEquals(OverlayGeometry(0, 24, 220, 108), state.preferred)
    }

    @Test fun explicitResizeStartsFromFittedNormalSizeAndRemainsPreferred() {
        val state = OverlayGeometryState(preference)
        val start = state.display(narrow, 220, 108)
        state.resizeTo(start.width - 24, start.height - 24, narrow, 220, 108)
        assertEquals(OverlayGeometry(0, 280, 296, 196), state.preferred)
        assertEquals(state.preferred, state.display(wide, 220, 108))
    }

    @Test fun densityChangesReprojectPreferredDpWithoutRoundingDrift() {
        val state = OverlayGeometryState(OverlayGeometryPreference(40, 60, 341, 161), 1.5f)
        assertEquals(OverlayGeometry(40, 60, 512, 242), state.preferred)
        state.updateDensity(3f)
        assertEquals(OverlayGeometry(40, 60, 1023, 483), state.preferred)
        state.updateDensity(1.5f)
        assertEquals(OverlayGeometry(40, 60, 512, 242), state.preferred)
        assertEquals(OverlayGeometryPreference(40, 60, 341, 161), state.preference)
    }

    @Test fun densityChangeWhileMinimizedPreservesDpSizeThroughViewportClampAndMove() {
        val state = OverlayGeometryState(OverlayGeometryPreference(500, 650, 600, 220), 1f)
        state.updateDensity(2f)
        assertEquals(OverlayGeometry(0, 388, 320, 112),
            state.display(narrow, 440, 216, minimizedHeight = 112))
        assertEquals(OverlayGeometryPreference(500, 650, 600, 220), state.preference)
        state.moveTo(-500, 999, narrow, 440, 216, minimizedHeight = 112)
        assertEquals(OverlayGeometryPreference(0, 388, 600, 220), state.preference)
        state.updateDensity(1f)
        assertEquals(OverlayGeometry(0, 388, 600, 220), state.display(wide, 220, 108))
    }

    @Test fun resizeAfterDensityChangePersistsTheNewDpDimensions() {
        val state = OverlayGeometryState(OverlayGeometryPreference(40, 60, 340, 160), 1f)
        state.updateDensity(2f)
        state.resizeTo(720, 360, wide, 440, 216)
        assertEquals(OverlayGeometryPreference(40, 60, 360, 180), state.preference)
        state.updateDensity(1f)
        assertEquals(OverlayGeometry(40, 60, 360, 180), state.preferred)
    }
}
