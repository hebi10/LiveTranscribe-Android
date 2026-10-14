package com.charles.livecaptionn.overlay

data class OverlayGeometry(val x: Int, val y: Int, val width: Int, val height: Int)
data class OverlayBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

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
