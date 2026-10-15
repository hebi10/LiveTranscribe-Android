package com.charles.livecaptionn.overlay

/** Coordinates belong to the ScrollView's content, rather than the screen. */
data class OverlayRowBounds(
    val id: Long,
    val top: Int,
    val bottom: Int,
    val lines: List<OverlayLineBounds> = emptyList()
)

/** Offsets are local to one message's source or translation, even in joined caption text. */
data class OverlayLineBounds(
    val textOffset: Int,
    val top: Int,
    val bottom: Int,
    val source: Boolean = false
)

data class OverlayScrollPosition(
    val rowId: Long?,
    val offsetFromRowTop: Int,
    val absoluteY: Int,
    val textOffset: Int? = null,
    val source: Boolean = false,
    val offsetFromLineTop: Int = 0
)

fun shouldFollowOverlayAfterRestore(wasFollowing: Boolean, atBottom: Boolean): Boolean =
    wasFollowing && atBottom

fun isOverlayAtBottom(
    scrollY: Int,
    viewportHeight: Int,
    contentHeight: Int,
    tolerancePx: Int
): Boolean = contentHeight - viewportHeight - scrollY <= tolerancePx.coerceAtLeast(0)

/** A long newest message starts at its first line; a short one still fits at the bottom. */
fun latestOverlayScrollY(rows: List<OverlayRowBounds>, maximumScrollY: Int): Int =
    (rows.lastOrNull()?.top ?: 0).coerceIn(0, maximumScrollY.coerceAtLeast(0))

fun captureOverlayScrollPosition(
    scrollY: Int,
    rows: List<OverlayRowBounds>
): OverlayScrollPosition {
    val readingLine = rows.asSequence()
        .flatMap { row -> row.lines.asSequence().map { row to it } }
        .filter { (_, line) -> line.bottom > scrollY }
        .minByOrNull { (_, line) -> line.top }
    if (readingLine != null) {
        val (row, line) = readingLine
        return OverlayScrollPosition(row.id, scrollY - row.top, scrollY,
            line.textOffset, line.source, scrollY - line.top)
    }
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
    val lines = row?.lines?.filter { it.source == position.source }.orEmpty()
    val line = position.textOffset?.let { offset ->
        lines.lastOrNull { it.textOffset <= offset } ?: lines.firstOrNull()
    }
    val target = when {
        line != null -> line.top + position.offsetFromLineTop.coerceAtMost((line.bottom - line.top - 1).coerceAtLeast(0))
        row != null && position.textOffset != null -> row.top
        row != null -> row.top + position.offsetFromRowTop
        position.rowId != null && rows.isNotEmpty() -> rows.first().top
        else -> position.absoluteY
    }
    return target.coerceIn(0, maximumScrollY.coerceAtLeast(0))
}
