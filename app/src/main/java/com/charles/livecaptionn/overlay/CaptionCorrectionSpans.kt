package com.charles.livecaptionn.overlay

import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import com.charles.livecaptionn.service.CaptionCorrectionRange

/** Color only revised tokens. The rest of the TextView retains the active theme color. */
internal fun correctionStyledCaption(
    text: String,
    ranges: List<CaptionCorrectionRange>,
    expiresAtMs: Long,
    highlightColor: Int,
    nowMs: Long = SystemClock.elapsedRealtime()
): CharSequence {
    if (expiresAtMs <= nowMs || ranges.isEmpty()) return text
    val styled = SpannableString(text)
    for (range in ranges) {
        if (range.start >= 0 && range.endExclusive <= text.length && range.start < range.endExclusive) {
            styled.setSpan(ForegroundColorSpan(highlightColor), range.start, range.endExclusive,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    return styled
}

/** Same change indicator in compact popup and split-screen reader. */
internal fun correctionStyledCaptions(
    messages: List<OverlayCaptionMessage>,
    highlightColor: Int,
    nowMs: Long = SystemClock.elapsedRealtime()
): CharSequence {
    val text = messages.joinToString("\n") { it.translatedText }
    val spans = mutableListOf<CaptionCorrectionRange>()
    var offset = 0
    for ((index, message) in messages.withIndex()) {
        if (message.correctionHighlightUntilMs > nowMs) {
            for (range in message.correctionRanges) {
                spans += CaptionCorrectionRange(offset + range.start, offset + range.endExclusive)
            }
        }
        offset += message.translatedText.length
        if (index != messages.lastIndex) offset++
    }
    return correctionStyledCaption(text, spans, messages.maxOfOrNull { it.correctionHighlightUntilMs } ?: 0L,
        highlightColor, nowMs)
}
