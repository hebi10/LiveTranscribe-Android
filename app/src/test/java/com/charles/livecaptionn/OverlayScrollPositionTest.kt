package com.charles.livecaptionn

import com.charles.livecaptionn.overlay.OverlayRowBounds
import com.charles.livecaptionn.overlay.captureOverlayScrollPosition
import com.charles.livecaptionn.overlay.isOverlayAtBottom
import com.charles.livecaptionn.overlay.restoreOverlayScrollY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayScrollPositionTest {
    @Test
    fun followsOnlyWhenViewportIsAtBottom() {
        assertFalse(isOverlayAtBottom(80, 200, 600, 8))
        assertTrue(isOverlayAtBottom(392, 200, 600, 8))
        assertTrue(isOverlayAtBottom(0, 200, 100, 8))
    }

    @Test
    fun retainedMessageKeepsItsOffsetWhenOldHistoryIsRemoved() {
        val before = listOf(OverlayRowBounds(1, 0, 100), OverlayRowBounds(2, 100, 240))
        val position = captureOverlayScrollPosition(125, before)
        val after = listOf(OverlayRowBounds(2, 0, 140), OverlayRowBounds(3, 140, 280))
        assertEquals(25, restoreOverlayScrollY(position, after, 200))
    }

    @Test
    fun sourceTextWrappingAboveViewportKeepsVisibleMessageInPlace() {
        val before = listOf(OverlayRowBounds(1, 0, 100), OverlayRowBounds(2, 100, 240))
        val position = captureOverlayScrollPosition(125, before)
        val after = listOf(OverlayRowBounds(1, 0, 180), OverlayRowBounds(2, 180, 320))
        assertEquals(205, restoreOverlayScrollY(position, after, 300))
    }

    @Test
    fun removedAnchorFallsBackToOldestRetainedMessage() {
        val position = captureOverlayScrollPosition(25, listOf(OverlayRowBounds(1, 0, 100)))
        assertEquals(0, restoreOverlayScrollY(position, listOf(OverlayRowBounds(2, 0, 100)), 50))
    }

    @Test
    fun shorterCaptionContentClampsWithoutNegativeScroll() {
        val position = captureOverlayScrollPosition(120, emptyList())
        assertEquals(0, restoreOverlayScrollY(position, emptyList(), -20))
        assertEquals(60, restoreOverlayScrollY(position, emptyList(), 60))
    }
}
