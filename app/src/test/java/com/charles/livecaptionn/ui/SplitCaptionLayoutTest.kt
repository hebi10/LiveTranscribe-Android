package com.charles.livecaptionn.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitCaptionLayoutTest {
    @Test fun fullHeightAndWidthShowStatusContext() {
        assertFalse(isSplitCaptionCompact(320f, 300f))
        assertFalse(isSplitCaptionCompact(500f, 700f))
    }

    @Test fun eitherNarrowWidthOrShortHeightUsesCompactHeader() {
        assertTrue(isSplitCaptionCompact(319f, 600f))
        assertTrue(isSplitCaptionCompact(400f, 299f))
        assertTrue(isSplitCaptionCompact(180f, 200f))
    }
}
