package com.charles.livecaptionn

import com.charles.livecaptionn.overlay.bubbleCaptionWidth
import com.charles.livecaptionn.overlay.bubbleSideAt
import com.charles.livecaptionn.overlay.fittedBubbleY
import com.charles.livecaptionn.settings.BubbleSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleGeometryTest {
    @Test fun snapToNearestEdge() {
        assertEquals(BubbleSide.LEFT, bubbleSideAt(120f, 1080))
        assertEquals(BubbleSide.RIGHT, bubbleSideAt(850f, 1080))
    }

    @Test fun clampToStatusAndNavigationBars() {
        assertEquals(50, fittedBubbleY(-100, 1000, 50, 100, 80))
        assertEquals(820, fittedBubbleY(950, 1000, 50, 100, 80))
        assertEquals(300, fittedBubbleY(300, 1000, 50, 100, 80))
    }

    @Test fun menuFitsReducedFoldedAndLandscapeHeights() {
        assertEquals(736, com.charles.livecaptionn.overlay.bubbleMenuHeightLimit(900, 50, 50, 48, 16))
        assertEquals(36, com.charles.livecaptionn.overlay.bubbleMenuHeightLimit(160, 30, 30, 48, 16))
        assertEquals(1, com.charles.livecaptionn.overlay.bubbleMenuHeightLimit(80, 30, 30, 48, 16))
    }

    @Test fun captionWidthNeverOverflowsTinyScreen() {
        assertEquals(1, bubbleCaptionWidth(20, 1f))
        assertTrue(bubbleCaptionWidth(1080, 3f) <= 840)
        assertTrue(bubbleCaptionWidth(360, 1f) <= 280)
    }
}
