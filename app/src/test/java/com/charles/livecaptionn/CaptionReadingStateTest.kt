package com.charles.livecaptionn

import com.charles.livecaptionn.overlay.OverlayRowBounds
import com.charles.livecaptionn.overlay.OverlayScrollPosition
import com.charles.livecaptionn.ui.CaptionReadingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionReadingStateTest {
    @Test fun returningFromSettingsAfterANewSessionClearsSavedReadingOwnership() {
        val reader = CaptionReadingState(OverlayScrollPosition(8, 20, 90), followingLatest = false, sessionId = 4L)
        assertTrue(reader.bindSession(5L))
        assertTrue(reader.followingLatest)
        assertEquals(null, reader.position)
        assertEquals(5L, reader.sessionId)
    }

    @Test fun canceledStartOrRouteRecreationKeepsTheSameSessionsAnchor() {
        val anchor = OverlayScrollPosition(8, 20, 90)
        val reader = CaptionReadingState(anchor, followingLatest = false, sessionId = 4L)
        assertFalse(reader.bindSession(4L))
        assertFalse(reader.followingLatest)
        assertEquals(anchor, reader.position)
    }

    @Test fun incomingMessagesCannotStealReadingAfterAutomaticLongMessageReveal() {
        val reader = CaptionReadingState()
        reader.contentChanged(newestChanged = true)
        assertEquals(70, reader.restore(listOf(OverlayRowBounds(1, 0, 70), OverlayRowBounds(2, 70, 470)), 378, 92))
        assertFalse(reader.followingLatest)
        reader.contentChanged(newestChanged = true)
        reader.contentChanged(newestChanged = true) // Updates may coalesce before layout.
        assertEquals(0, reader.restore(listOf(OverlayRowBounds(2, 0, 400), OverlayRowBounds(3, 400, 700)), 608, 92))
        assertFalse(reader.followingLatest)
    }

    @Test fun recreationRestoresReaderOwnershipEvenWhenNewLayoutFitsAtBottom() {
        val reader = CaptionReadingState(OverlayScrollPosition(2, 20, 90), followingLatest = false)
        reader.contentChanged(newestChanged = false)
        assertEquals(0, reader.restore(listOf(OverlayRowBounds(2, 0, 40)), 0, 100))
        assertFalse(reader.followingLatest)
        reader.contentChanged(newestChanged = true)
        assertEquals(0, reader.restore(listOf(OverlayRowBounds(2, 0, 40), OverlayRowBounds(3, 40, 600)), 500, 100))
    }

    @Test fun newEmptySessionReleasesThePreviousSessionsReadingAnchor() {
        val reader = CaptionReadingState(OverlayScrollPosition(8, 20, 90), followingLatest = false)
        reader.reset()
        reader.contentChanged(newestChanged = true)
        assertEquals(150, reader.restore(listOf(OverlayRowBounds(1, 0, 200), OverlayRowBounds(2, 200, 250)), 150, 100))
        assertTrue(reader.followingLatest)
    }

    @Test fun explicitLatestResumesFollowOnlyWhenNewestMessageFits() {
        val reader = CaptionReadingState(OverlayScrollPosition(1, 0, 0), followingLatest = false)
        reader.requestLatest()
        assertEquals(100, reader.restore(listOf(OverlayRowBounds(1, 0, 100), OverlayRowBounds(2, 100, 500)), 400, 100))
        assertFalse(reader.followingLatest)
        reader.requestLatest()
        assertEquals(50, reader.restore(listOf(OverlayRowBounds(1, 0, 100), OverlayRowBounds(2, 100, 150)), 50, 100))
        assertTrue(reader.followingLatest)
    }
}
