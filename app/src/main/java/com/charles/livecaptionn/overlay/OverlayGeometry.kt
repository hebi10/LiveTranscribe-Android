package com.charles.livecaptionn.overlay

import kotlin.math.roundToInt

data class OverlayGeometry(val x: Int, val y: Int, val width: Int, val height: Int)
data class OverlayBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Positions use screen pixels; the normal window size is retained in the settings' dp units. */
data class OverlayGeometryPreference(val x: Int, val y: Int, val widthDp: Int, val heightDp: Int) {
    fun atDensity(density: Float) = OverlayGeometry(
        x, y, (widthDp * density).roundToInt(), (heightDp * density).roundToInt())
}

/** User preference is distinct from the geometry fitted to the current display and mode. */
class OverlayGeometryState(initialPreference: OverlayGeometryPreference, private var density: Float) {
    constructor(preferred: OverlayGeometry) : this(
        OverlayGeometryPreference(preferred.x, preferred.y, preferred.width, preferred.height), 1f)

    var preference = initialPreference
        private set
    val preferred get() = preference.atDensity(density)

    fun updatePreference(value: OverlayGeometryPreference) {
        preference = value
    }

    fun updateDensity(value: Float) {
        density = value
    }

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
        preference = preference.copy(x = moved.x, y = moved.y)
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
        val resized = clampOverlayGeometry(normal.copy(width = width, height = height), bounds, minWidth, minHeight)
        preference = OverlayGeometryPreference(resized.x, resized.y,
            (resized.width / density).roundToInt(), (resized.height / density).roundToInt())
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
