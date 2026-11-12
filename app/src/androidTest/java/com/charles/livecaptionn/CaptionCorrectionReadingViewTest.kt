package com.charles.livecaptionn

import android.graphics.Color
import android.os.SystemClock
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.charles.livecaptionn.overlay.OverlayCaptionMessage
import com.charles.livecaptionn.service.CaptionCorrectionRange
import com.charles.livecaptionn.settings.CorrectionHighlightPalette
import com.charles.livecaptionn.ui.CaptionReadingState
import com.charles.livecaptionn.ui.CaptionReadingView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses test-only caption strings; no fake audio, translations or Play store evidence. */
@RunWith(AndroidJUnit4::class)
class CaptionCorrectionReadingViewTest {
    @Test fun changedTokenUsesSpanAndExpiryRestoresThemeColorWithoutReplacingRow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val reader = CaptionReadingState(sessionId = 8L)
            val view = CaptionReadingView(instrumentation.targetContext, reader)
            val revised = "오늘 회의는 오후 3시에 시작합니다."
            val first = revised.indexOf("3시에")
            val message = OverlayCaptionMessage(
                id = 29L,
                originalText = "The meeting begins at three.",
                translatedText = revised,
                isFinal = true,
                correctionRanges = listOf(CaptionCorrectionRange(first, first + "3시에".length)),
                correctionHighlightUntilMs = SystemClock.elapsedRealtime() + 10_000L
            )
            view.render(listOf(message), false, 24f, Color.WHITE, Color.LTGRAY, Color.GREEN)
            val column = view.getChildAt(0) as LinearLayout
            val row = column.getChildAt(0) as LinearLayout
            val translated = row.getChildAt(0) as TextView

            assertEquals(Color.WHITE, translated.currentTextColor)
            assertEquals(revised, translated.text.toString())
            assertTrue(translated.text is Spanned)
            val styled = translated.text as Spanned
            val spans = styled.getSpans(0, styled.length, ForegroundColorSpan::class.java)
            assertEquals(1, spans.size)
            assertEquals(Color.GREEN, spans.single().foregroundColor)
            assertEquals(first, styled.getSpanStart(spans.single()))
            assertEquals(first + "3시에".length, styled.getSpanEnd(spans.single()))

            // Same id + cleared correction metadata: no scroll-row replacement or residual tint.
            view.render(listOf(message.copy(correctionRanges = emptyList(), correctionHighlightUntilMs = 0L)),
                false, 24f, Color.WHITE, Color.LTGRAY, Color.GREEN)
            val secondRow = (view.getChildAt(0) as LinearLayout).getChildAt(0) as LinearLayout
            val after = secondRow.getChildAt(0) as TextView
            assertSame(translated, after)
            assertEquals(Color.WHITE, after.currentTextColor)
            assertEquals(revised, after.text.toString())
            assertTrue((after.text as Spanned).getSpans(0, after.text.length,
                ForegroundColorSpan::class.java).isEmpty())
            assertEquals(8L, reader.sessionId)
        }
    }

    @Test fun alreadyExpiredChangeNeverAppliesTint() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = CaptionReadingView(instrumentation.targetContext, CaptionReadingState())
            val message = OverlayCaptionMessage(30L, "", "바뀐 문장", true,
                correctionRanges = listOf(CaptionCorrectionRange(0, 2)),
                correctionHighlightUntilMs = SystemClock.elapsedRealtime() - 1L)
            view.render(listOf(message), false, 18f, Color.BLACK, Color.GRAY, Color.RED)
            val row = (view.getChildAt(0) as LinearLayout).getChildAt(0) as LinearLayout
            val text = (row.getChildAt(0) as TextView).text
            assertEquals("바뀐 문장", text.toString())
            assertFalse(text is Spanned && text.getSpans(0, text.length,
                ForegroundColorSpan::class.java).isNotEmpty())
        }
    }

    @Test fun everyUserSwatchHasReadableContrastOnItsActualTheme() {
        for (swatch in CorrectionHighlightPalette.SWATCHES) {
            val dark = CorrectionHighlightPalette.colorInt(swatch.id, lightBackground = false)
            val light = CorrectionHighlightPalette.colorInt(swatch.id, lightBackground = true)
            assertTrue("dark theme contrast: ${swatch.id}", contrast(dark, Color.rgb(16, 16, 16)) >= 4.5)
            assertTrue("light theme contrast: ${swatch.id}", contrast(light, Color.WHITE) >= 4.5)
        }
    }

    private fun contrast(first: Int, second: Int): Double {
        fun gamma(x: Int): Double {
            val value = x / 255.0
            return if (value <= 0.04045) value / 12.92
                else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        fun luminance(color: Int): Double =
            0.2126 * gamma(Color.red(color)) +
                0.7152 * gamma(Color.green(color)) +
                0.0722 * gamma(Color.blue(color))
        val a = luminance(first)
        val b = luminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
}
