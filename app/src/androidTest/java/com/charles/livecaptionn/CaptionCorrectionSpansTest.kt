package com.charles.livecaptionn

import android.graphics.Color
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.charles.livecaptionn.overlay.OverlayCaptionMessage
import com.charles.livecaptionn.overlay.correctionStyledCaptions
import com.charles.livecaptionn.service.CaptionCorrectionRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptionCorrectionSpansTest {
    @Test fun spansAppearOnlyBeforeExpirationAndRespectMessageOffsets() {
        val first = OverlayCaptionMessage(1, "first", "변경 없음", true)
        val second = OverlayCaptionMessage(2, "second", "오후 3시에 시작합니다.", true,
            correctionRanges = listOf(CaptionCorrectionRange(3, 6)),
            correctionHighlightUntilMs = 2000L)
        val text = correctionStyledCaptions(listOf(first, second), Color.GREEN, nowMs = 1000L)
        assertTrue(text is Spanned)
        val spans = (text as Spanned).getSpans(0, text.length, ForegroundColorSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(first.translatedText.length + 1 + 3, text.getSpanStart(spans.single()))
        assertEquals(first.translatedText.length + 1 + 6, text.getSpanEnd(spans.single()))
        val expired = correctionStyledCaptions(listOf(first, second), Color.GREEN, nowMs = 2000L)
        assertFalse(expired is Spanned)
        assertEquals(text.toString(), expired.toString())
    }
}
