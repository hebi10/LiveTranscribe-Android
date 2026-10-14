package com.charles.livecaptionn

import com.charles.livecaptionn.ui.l10n.UiStringCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiStringCatalogTest {
    @Test fun catalogIsKoreanFirst() {
        assertEquals("실시간 영상 번역", UiStringCatalog.appName)
        assertTrue(UiStringCatalog.ALL.contains("Transcript History"))
    }

    @Test fun catalogHasNoDuplicates() {
        assertEquals(UiStringCatalog.ALL.size, UiStringCatalog.ALL.toSet().size)
    }

    @Test fun placeholdersRetainCorrectCounts() {
        assertEquals(2, UiStringCatalog.placeholderCount("Values: %s (%d)"))
        assertEquals(2, UiStringCatalog.placeholderCount("Opacity: %d%%"))
    }

    @Test fun untranslatableSegmentsAreIgnored() {
        assertFalse(UiStringCatalog.isSegmentTranslatable("…"))
        assertFalse(UiStringCatalog.isSegmentTranslatable(""))
        assertTrue(UiStringCatalog.isSegmentTranslatable("History"))
    }
}
