package com.charles.livecaptionn.ui

/** Short status in narrow or short split windows; controls retain 48dp touch targets. */
internal fun isSplitCaptionCompact(widthDp: Float, heightDp: Float): Boolean =
    widthDp < 320f || heightDp < 300f
