package com.charles.livecaptionn.ui

import com.charles.livecaptionn.data.TranscriptEntry

/** Search never mutates stored transcripts or the original ordering. */
internal fun filterHistoryEntries(entries: List<TranscriptEntry>, query: String): List<TranscriptEntry> {
    val term = query.trim()
    if (term.isEmpty()) return entries
    return entries.filter { entry ->
        entry.originalText.contains(term, ignoreCase = true) ||
            entry.translatedText.contains(term, ignoreCase = true)
    }
}

internal fun historyResultLabel(total: Int, matching: Int, query: String): String =
    if (query.isBlank()) "저장된 기록 ${total}개"
    else "검색 결과 ${matching}개 / 전체 ${total}개"
