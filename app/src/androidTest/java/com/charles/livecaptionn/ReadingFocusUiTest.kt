package com.charles.livecaptionn

import android.graphics.Color
import android.graphics.text.LineBreaker
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.charles.livecaptionn.overlay.OverlayCaptionMessage
import com.charles.livecaptionn.ui.CaptionReadingState
import com.charles.livecaptionn.ui.CaptionReadingView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic captions only; not evidence of physical-device translation. */
@RunWith(AndroidJUnit4::class)
class ReadingFocusUiTest {
    @Test fun focusedReadingKeepsLongTranslationAndCanRestoreOriginal() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = CaptionReadingView(instrumentation.targetContext, CaptionReadingState())
            val full = "영상을 보면서 자막을 읽을 때에는 긴 문장의 정보가 사라지지 않아야 합니다. " +
                "시청자는 지나간 내용을 다시 확인할 수 있어야 합니다."
            val message = OverlayCaptionMessage(1L, "Original spoken text", full, true)
            view.render(listOf(message), false, 24f, Color.WHITE, Color.LTGRAY, Color.GREEN, focusReading = true)
            val row = (view.getChildAt(0) as LinearLayout).getChildAt(0) as LinearLayout
            val translated = row.getChildAt(0) as TextView
            val original = row.getChildAt(1) as TextView
            assertEquals(full, translated.text.toString())
            assertEquals(LineBreaker.BREAK_STRATEGY_BALANCED, translated.breakStrategy)
            assertEquals(View.GONE, original.visibility)

            view.render(listOf(message), true, 24f, Color.WHITE, Color.LTGRAY, Color.GREEN, focusReading = false)
            val next = (view.getChildAt(0) as LinearLayout).getChildAt(0) as LinearLayout
            assertSame(translated, next.getChildAt(0))
            assertEquals(full, translated.text.toString())
            assertEquals(LineBreaker.BREAK_STRATEGY_HIGH_QUALITY, translated.breakStrategy)
            assertEquals(View.VISIBLE, (next.getChildAt(1) as TextView).visibility)
        }
    }

    @Test fun focusToggleWithNoCaptionsDoesNotInventHistory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val view = CaptionReadingView(instrumentation.targetContext, CaptionReadingState())
            view.render(emptyList(), false, 18f, Color.WHITE, Color.LTGRAY, Color.GREEN, focusReading = true)
            assertEquals(0, (view.getChildAt(0) as LinearLayout).childCount)
            view.render(emptyList(), false, 18f, Color.WHITE, Color.LTGRAY, Color.GREEN, focusReading = false)
            assertEquals(0, (view.getChildAt(0) as LinearLayout).childCount)
        }
    }
}
