package com.charles.livecaptionn.ui

import com.charles.livecaptionn.data.TranscriptEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HistoryPresentationTest {
    private val first = TranscriptEntry(
        id = "one", originalText = "Hello World", translatedText = "안녕하세요",
        sourceLanguage = "en", targetLanguage = "ko"
    )
    private val second = TranscriptEntry(
        id = "two", originalText = "Good morning", translatedText = "좋은 아침",
        sourceLanguage = "en", targetLanguage = "ko"
    )
    private val entries = listOf(first, second)

    @Test fun blankQueryPreservesListIdentityAndOrder() {
        assertSame(entries, filterHistoryEntries(entries, "   "))
    }

    @Test fun caseInsensitiveOriginalAndTranslatedSearch() {
        assertEquals(listOf(first), filterHistoryEntries(entries, " hELLo "))
        assertEquals(listOf(second), filterHistoryEntries(entries, "좋은"))
        assertEquals(emptyList<TranscriptEntry>(), filterHistoryEntries(entries, "없는 기록"))
    }

    @Test fun countLabelsDistinguishFullListAndSearch() {
        assertEquals("저장된 기록 2개", historyResultLabel(2, 2, ""))
        assertEquals("검색 결과 1개 / 전체 2개", historyResultLabel(2, 1, "hi"))
        assertEquals("검색 결과 0개 / 전체 2개", historyResultLabel(2, 0, "none"))
    }
}
