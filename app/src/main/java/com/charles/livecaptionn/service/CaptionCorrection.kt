package com.charles.livecaptionn.service

/** UTF-16 offsets into the corrected subtitle, end exclusive. */
data class CaptionCorrectionRange(val start: Int, val endExclusive: Int)

/**
 * Bounded token LCS: underline/color only changed words, not whole sentences.
 * Word boundaries are deliberate: individual Hangul syllables must stay intact.
 */
internal fun correctionChangedRanges(previous: String, revised: String): List<CaptionCorrectionRange> {
    if (previous == revised || revised.isBlank()) return emptyList()
    val pattern = Regex("\\S+|\\s+")
    val oldTokens = pattern.findAll(previous).map { it.value }.toList()
    val fresh = pattern.findAll(revised).toList()
    if (oldTokens.size > 160 || fresh.size > 160) {
        val commonStart = previous.commonPrefixWith(revised).length
        val commonEnd = previous.substring(commonStart).commonSuffixWith(revised.substring(commonStart)).length
        val end = revised.length - commonEnd
        return if (end > commonStart) listOf(CaptionCorrectionRange(commonStart, end)) else emptyList()
    }
    val dp = Array(oldTokens.size + 1) { IntArray(fresh.size + 1) }
    for (i in oldTokens.indices.reversed()) {
        for (j in fresh.indices.reversed()) {
            dp[i][j] = if (oldTokens[i] == fresh[j].value) dp[i + 1][j + 1] + 1
            else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
    }
    val unchanged = BooleanArray(fresh.size)
    var i = 0
    var j = 0
    while (i < oldTokens.size && j < fresh.size) {
        when {
            oldTokens[i] == fresh[j].value -> {
                unchanged[j] = true
                i++
                j++
            }
            dp[i + 1][j] >= dp[i][j + 1] -> i++
            else -> j++
        }
    }
    val ranges = mutableListOf<CaptionCorrectionRange>()
    for (index in fresh.indices) {
        if (unchanged[index] || fresh[index].value.isBlank()) continue
        val token = fresh[index]
        val start = token.range.first
        val end = token.range.last + 1
        if (ranges.isNotEmpty() && revised.substring(ranges.last().endExclusive, start).isBlank()) {
            val last = ranges.removeAt(ranges.lastIndex)
            ranges += CaptionCorrectionRange(last.start, end)
        } else ranges += CaptionCorrectionRange(start, end)
    }
    return ranges
}

internal fun acceptableCaptionCorrection(original: String, revised: String): Boolean {
    val candidate = revised.trim()
    if (original.isBlank() || candidate.isBlank() || candidate == original.trim()) return false
    if (candidate.length > 600 || candidate.contains('\n') || candidate.contains('\r')) return false
    val length = original.trim().length.coerceAtLeast(1)
    if (candidate.length > length * 1.45 + 12 || candidate.length * 2 + 12 < length) return false
    return !candidate.startsWith("번역:") && !candidate.startsWith("교정:") &&
        !candidate.startsWith("수정된 문장:") && !candidate.startsWith("Here is")
}

/** Guard against a late polish from an earlier utterance/session. */
internal fun applyCaptionCorrection(
    runtime: CaptionRuntimeState,
    sessionId: Long,
    lineId: Long,
    firstTranslation: String,
    revised: String,
    untilElapsedMs: Long,
    highlight: Boolean
): CaptionRuntimeState {
    if (!runtime.running || runtime.stopping || runtime.sessionId != sessionId ||
        !acceptableCaptionCorrection(firstTranslation, revised)) return runtime
    val index = runtime.transcriptLines.indexOfFirst { it.id == lineId }
    if (index < 0) return runtime
    val line = runtime.transcriptLines[index]
    if (!line.isFinal || !line.finalTranslationCommitted || line.translatedText != firstTranslation) return runtime
    val newText = revised.trim()
    val changed = if (highlight) correctionChangedRanges(firstTranslation, newText) else emptyList()
    val updated = runtime.transcriptLines.toMutableList()
    updated[index] = line.copy(
        translatedText = newText,
        correctionRanges = changed,
        correctionHighlightUntilMs = if (changed.isEmpty()) 0L else untilElapsedMs
    )
    return runtime.copy(
        transcriptLines = updated,
        translatedText = updated.lastOrNull { it.translatedText.isNotBlank() }?.translatedText.orEmpty()
    )
}

internal fun expireCaptionCorrection(runtime: CaptionRuntimeState, sessionId: Long, lineId: Long, untilElapsedMs: Long): CaptionRuntimeState {
    if (runtime.sessionId != sessionId || untilElapsedMs <= 0L) return runtime
    val index = runtime.transcriptLines.indexOfFirst { it.id == lineId &&
        it.correctionHighlightUntilMs == untilElapsedMs }
    if (index < 0) return runtime
    val updated = runtime.transcriptLines.toMutableList()
    updated[index] = updated[index].copy(correctionRanges = emptyList(), correctionHighlightUntilMs = 0L)
    return runtime.copy(transcriptLines = updated)
}
