package com.charles.livecaptionn

import com.charles.livecaptionn.overlay.OverlayRowBounds
import com.charles.livecaptionn.overlay.OverlayLineBounds
import com.charles.livecaptionn.overlay.captureOverlayScrollPosition
import com.charles.livecaptionn.overlay.isOverlayAtBottom
import com.charles.livecaptionn.overlay.latestOverlayScrollY
import com.charles.livecaptionn.overlay.restoreOverlayScrollY
import com.charles.livecaptionn.overlay.shouldFollowOverlayAfterRestore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayScrollPositionTest {
    @Test
    fun longCaptionReadingSurvivesIncomingCaptionUntilBoundedHistoryEvictsIt() {
        val initial = listOf(OverlayRowBounds(1, 0, 70), OverlayRowBounds(2, 70, 470))
        val latestY = latestOverlayScrollY(initial, 378)
        assertFalse(shouldFollowOverlayAfterRestore(true, isOverlayAtBottom(latestY, 92, 470, 8)))
        val reading = captureOverlayScrollPosition(latestY + 40, initial)

        val next = listOf(OverlayRowBounds(2, 0, 400), OverlayRowBounds(3, 400, 600))
        val restoredY = restoreOverlayScrollY(reading, next, 508)
        assertEquals(40, restoredY)
        assertEquals(restoredY, restoreOverlayScrollY(captureOverlayScrollPosition(restoredY, next), next, 400))
        assertEquals(400, latestOverlayScrollY(next, 508))

        val afterEviction = listOf(OverlayRowBounds(3, 0, 200), OverlayRowBounds(4, 200, 600))
        assertEquals(0, restoreOverlayScrollY(reading, afterEviction, 508))
    }

    @Test
    fun revealingLongCaptionPausesFollowingWhileShortCaptionContinues() {
        assertFalse(shouldFollowOverlayAfterRestore(true, false))
        assertTrue(shouldFollowOverlayAfterRestore(true, true))
    }

    @Test
    fun geometryClampCannotResumeFollowingWithoutManualScroll() {
        assertFalse(shouldFollowOverlayAfterRestore(false, true))
        assertFalse(shouldFollowOverlayAfterRestore(false, false))
    }

    @Test
    fun widthChangePreservesTheCharacterAtTheReadingLine() {
        val before = listOf(OverlayRowBounds(7, 100, 180, listOf(
            OverlayLineBounds(0, 100, 120), OverlayLineBounds(20, 120, 140),
            OverlayLineBounds(40, 140, 160), OverlayLineBounds(60, 160, 180))))
        val position = captureOverlayScrollPosition(143, before)
        val after = listOf(OverlayRowBounds(7, 100, 140, listOf(
            OverlayLineBounds(0, 100, 120), OverlayLineBounds(40, 120, 140))))
        assertEquals(123, restoreOverlayScrollY(position, after, 300))
    }

    @Test
    fun fontShrinkKeepsClippedOffsetInsideTheReadingLine() {
        val before = listOf(OverlayRowBounds(7, 100, 140, listOf(
            OverlayLineBounds(0, 100, 120), OverlayLineBounds(20, 120, 140))))
        val position = captureOverlayScrollPosition(138, before)
        val after = listOf(OverlayRowBounds(7, 100, 120, listOf(
            OverlayLineBounds(0, 100, 110), OverlayLineBounds(20, 110, 120))))
        assertEquals(119, restoreOverlayScrollY(position, after, 300))
    }

    @Test
    fun hiddenOriginalFallsBackToSameMessageTranslationBeginning() {
        val before = listOf(OverlayRowBounds(7, 100, 160, listOf(
            OverlayLineBounds(0, 0, 20, source = true),
            OverlayLineBounds(20, 20, 40, source = true),
            OverlayLineBounds(0, 100, 120))))
        val position = captureOverlayScrollPosition(25, before)
        val after = listOf(OverlayRowBounds(7, 2, 62, listOf(OverlayLineBounds(0, 2, 22))))
        assertEquals(2, restoreOverlayScrollY(position, after, 100))
    }

    @Test
    fun sourceAboveJoinedTranslationsRetainsItsOwnMessageWhenOlderCaptionDrops() {
        val before = listOf(
            OverlayRowBounds(1, 100, 140, listOf(OverlayLineBounds(0, 0, 20, source = true))),
            OverlayRowBounds(2, 140, 180, listOf(OverlayLineBounds(0, 20, 40, source = true))))
        val position = captureOverlayScrollPosition(25, before)
        val after = listOf(OverlayRowBounds(2, 60, 100, listOf(
            OverlayLineBounds(0, 0, 20, source = true))))
        assertEquals(5, restoreOverlayScrollY(position, after, 100))
    }

    @Test
    fun latestLongCaptionStartsAtItsFirstLine() {
        val rows = listOf(OverlayRowBounds(1, 0, 70), OverlayRowBounds(2, 70, 470))
        assertEquals(70, latestOverlayScrollY(rows, 378))
    }

    @Test
    fun latestLongChatMessageStartsAtItsFirstLine() {
        val rows = (1L..40L).map { id ->
            val top = ((id - 1) * 100).toInt()
            OverlayRowBounds(id, top, top + if (id == 40L) 500 else 100)
        }
        assertEquals(3900, latestOverlayScrollY(rows, 4308))
    }

    @Test
    fun latestShortCaptionKeepsBottomVisible() {
        val rows = listOf(OverlayRowBounds(1, 0, 200), OverlayRowBounds(2, 200, 240))
        assertEquals(148, latestOverlayScrollY(rows, 148))
    }

    @Test
    fun emptyLatestContentClampsWithoutNegativeScroll() {
        assertEquals(0, latestOverlayScrollY(emptyList(), -20))
    }

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
