package com.charles.livecaptionn.overlay

/** Popup controls stay legible above video, independent of the application's appearance setting. */
internal object OverlayChromeMetrics {
    const val TOUCH_DP = 48
    const val VERTICAL_PADDING_DP = 4
    const val MINIMIZED_HEIGHT_DP = 56
    const val LABELED_CONTROL_MIN_WIDTH_DP = 64
}

internal data class OverlayChromeState(
    val normalHeader: Boolean,
    val minimizedHeader: Boolean,
    val expandedControls: Boolean,
    val captionBody: Boolean,
    val resizeHandle: Boolean,
    val latestAction: Boolean,
    val stopEnabled: Boolean
)

internal fun overlayChromeState(ui: OverlayUiState, followingLatest: Boolean): OverlayChromeState =
    OverlayChromeState(
        normalHeader = !ui.minimized,
        minimizedHeader = ui.minimized,
        expandedControls = ui.controlsExpanded && !ui.minimized,
        captionBody = !ui.minimized,
        resizeHandle = !ui.minimized,
        latestAction = !ui.minimized && !followingLatest,
        stopEnabled = !ui.stopping
    )
