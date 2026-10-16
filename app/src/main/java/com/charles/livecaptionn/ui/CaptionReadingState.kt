package com.charles.livecaptionn.ui

import com.charles.livecaptionn.overlay.OverlayRowBounds
import com.charles.livecaptionn.overlay.OverlayScrollPosition
import com.charles.livecaptionn.overlay.captureOverlayScrollPosition
import com.charles.livecaptionn.overlay.isOverlayAtBottom
import com.charles.livecaptionn.overlay.latestOverlayScrollY
import com.charles.livecaptionn.overlay.restoreOverlayScrollY
import com.charles.livecaptionn.overlay.shouldFollowOverlayAfterRestore

/** Scroll ownership is independent of whether a resized viewport happens to fit its content. */
internal class CaptionReadingState(
    var position: OverlayScrollPosition? = null,
    var followingLatest: Boolean = true,
    var sessionId: Long = 0L
) {
    private var revealLatest = position == null

    fun bindSession(currentSessionId: Long): Boolean {
        if (sessionId == currentSessionId) return false
        sessionId = currentSessionId
        reset()
        return true
    }

    fun reset() {
        position = null
        followingLatest = true
        revealLatest = true
    }

    fun contentChanged(newestChanged: Boolean) {
        revealLatest = revealLatest || (followingLatest && newestChanged)
    }

    fun requestLatest() {
        followingLatest = true
        revealLatest = true
    }

    fun capture(scrollY: Int, rows: List<OverlayRowBounds>) {
        position = captureOverlayScrollPosition(scrollY, rows)
    }

    fun userScrolled(scrollY: Int, rows: List<OverlayRowBounds>, maximumY: Int) {
        revealLatest = false
        followingLatest = scrollY >= maximumY - 2
        capture(scrollY, rows)
    }

    fun restore(rows: List<OverlayRowBounds>, maximumY: Int, viewportHeight: Int): Int {
        val target = if (revealLatest) latestOverlayScrollY(rows, maximumY)
        else restoreOverlayScrollY(position ?: OverlayScrollPosition(null, 0, 0), rows, maximumY)
        followingLatest = shouldFollowOverlayAfterRestore(followingLatest,
            isOverlayAtBottom(target, viewportHeight, maximumY + viewportHeight, 2))
        revealLatest = false
        capture(target, rows)
        return target
    }
}
