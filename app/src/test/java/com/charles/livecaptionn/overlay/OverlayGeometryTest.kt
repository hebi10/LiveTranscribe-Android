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
}
