package com.charles.livecaptionn.overlay

/** Coordinates belong to the ScrollView's content, rather than the screen. */
data class OverlayRowBounds(val id: Long, val top: Int, val bottom: Int)

data class OverlayScrollPosition(
    val rowId: Long?,
    val offsetFromRowTop: Int,
    val absoluteY: Int
)

fun isOverlayAtBottom(
    scrollY: Int,
    viewportHeight: Int,
    contentHeight: Int,
    tolerancePx: Int
): Boolean = contentHeight - viewportHeight - scrollY <= tolerancePx.coerceAtLeast(0)

fun captureOverlayScrollPosition(
    scrollY: Int,
    rows: List<OverlayRowBounds>
): OverlayScrollPosition {
    val row = rows.firstOrNull { it.bottom > scrollY }
    return OverlayScrollPosition(row?.id, row?.let { scrollY - it.top } ?: 0, scrollY)
}

/** Retains a visible message even when bounded chat history drops preceding rows. */
fun restoreOverlayScrollY(
    position: OverlayScrollPosition,
    rows: List<OverlayRowBounds>,
    maximumScrollY: Int
): Int {
    val row = rows.firstOrNull { it.id == position.rowId }
    val target = when {
        row != null -> row.top + position.offsetFromRowTop
        position.rowId != null && rows.isNotEmpty() -> rows.first().top
        else -> position.absoluteY
    }
    return target.coerceIn(0, maximumScrollY.coerceAtLeast(0))
}
