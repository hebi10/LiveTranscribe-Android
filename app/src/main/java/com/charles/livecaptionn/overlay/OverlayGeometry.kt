package com.charles.livecaptionn.overlay

data class OverlayGeometry(val x: Int, val y: Int, val width: Int, val height: Int)
data class OverlayBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** User preference is distinct from the geometry fitted to the current display and mode. */
class OverlayGeometryState(var preferred: OverlayGeometry) {
    fun display(
        bounds: OverlayBounds, minWidth: Int, minHeight: Int,
        minimizedHeight: Int? = null, expandedMinHeight: Int? = null
    ): OverlayGeometry {
        val height = minimizedHeight ?: maxOf(preferred.height, expandedMinHeight ?: preferred.height)
        return clampOverlayGeometry(preferred.copy(height = height), bounds, minWidth,
            minimizedHeight ?: minHeight)
    }

    fun moveTo(
        x: Int, y: Int, bounds: OverlayBounds, minWidth: Int, minHeight: Int,
        minimizedHeight: Int? = null, expandedMinHeight: Int? = null
    ) {
        val displayed = display(bounds, minWidth, minHeight, minimizedHeight, expandedMinHeight)
        val moved = clampOverlayGeometry(displayed.copy(x = x, y = y), bounds, minWidth,
            minimizedHeight ?: minHeight)
        preferred = preferred.copy(x = moved.x, y = moved.y)
    }

    fun moveBy(
        dx: Int, dy: Int, bounds: OverlayBounds, minWidth: Int, minHeight: Int,
        minimizedHeight: Int? = null, expandedMinHeight: Int? = null
    ) {
        val displayed = display(bounds, minWidth, minHeight, minimizedHeight, expandedMinHeight)
        moveTo(displayed.x + dx, displayed.y + dy, bounds, minWidth, minHeight,
            minimizedHeight, expandedMinHeight)
    }

    fun resizeTo(width: Int, height: Int, bounds: OverlayBounds, minWidth: Int, minHeight: Int) {
        val normal = display(bounds, minWidth, minHeight)
        preferred = clampOverlayGeometry(normal.copy(width = width, height = height), bounds, minWidth, minHeight)
    }
}

/** Clamp every edge, allowing minimum dimensions to shrink on very small displays. */
fun clampOverlayGeometry(
    geometry: OverlayGeometry,
    bounds: OverlayBounds,
    minWidth: Int,
    minHeight: Int
): OverlayGeometry {
    val availableWidth = (bounds.right - bounds.left).coerceAtLeast(1)
    val availableHeight = (bounds.bottom - bounds.top).coerceAtLeast(1)
    val width = geometry.width.coerceIn(minWidth.coerceIn(1, availableWidth), availableWidth)
    val height = geometry.height.coerceIn(minHeight.coerceIn(1, availableHeight), availableHeight)
    return OverlayGeometry(
        geometry.x.coerceIn(bounds.left, bounds.left + availableWidth - width),
        geometry.y.coerceIn(bounds.top, bounds.top + availableHeight - height), width, height
    )
}
