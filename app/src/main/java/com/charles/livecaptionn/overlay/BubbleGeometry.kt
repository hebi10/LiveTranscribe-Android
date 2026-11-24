package com.charles.livecaptionn.overlay

import com.charles.livecaptionn.settings.BubbleSide

internal fun bubbleSideAt(rawX: Float, screenWidth: Int): BubbleSide =
    if (rawX < screenWidth / 2f) BubbleSide.LEFT else BubbleSide.RIGHT

internal fun fittedBubbleY(preferredY: Int, screenHeight: Int, topInset: Int, bottomInset: Int,
                           windowHeight: Int): Int {
    val top = topInset.coerceAtLeast(0)
    val lower = (screenHeight - bottomInset.coerceAtLeast(0) - windowHeight.coerceAtLeast(1))
        .coerceAtLeast(top)
    return preferredY.coerceIn(top, lower)
}

internal fun bubbleCaptionWidth(screenWidth: Int, density: Float): Int {
    val scale = density.coerceAtLeast(0.5f)
    val button = (48f * scale).toInt()
    val padding = (32f * scale).toInt()
    val limit = (280f * scale).toInt()
    return (screenWidth - button - padding).coerceAtLeast(1).coerceAtMost(limit)
}

/** On a short or folded display the scrollable menu must shrink below its normal minimum. */
internal fun bubbleMenuHeightLimit(screenHeight: Int, topInset: Int, bottomInset: Int,
                                   buttonHeight: Int, margin: Int): Int =
    (screenHeight - topInset.coerceAtLeast(0) - bottomInset.coerceAtLeast(0) -
        buttonHeight.coerceAtLeast(1) - margin.coerceAtLeast(0)).coerceAtLeast(1)
