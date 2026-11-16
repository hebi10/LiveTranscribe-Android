package com.charles.livecaptionn

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.charles.livecaptionn.data.SettingsRepository
import com.charles.livecaptionn.overlay.BubbleOverlayController
import com.charles.livecaptionn.overlay.OverlayCaptionMessage
import com.charles.livecaptionn.overlay.OverlayUiState
import com.charles.livecaptionn.settings.BubbleSide
import com.charles.livecaptionn.settings.CaptionPlacement
import com.charles.livecaptionn.settings.CaptionSettings
import com.charles.livecaptionn.speech.RecognitionStatus
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** WindowManager synthetic UI captures only; no real audio or on-device model inference. */
@RunWith(AndroidJUnit4::class)
class BubbleOverlayUiTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun actualAndroidOverlayShowsAndHidesOneTranslatedBubble() {
        val context = instrumentation.targetContext
        assertEquals("com.hebi.livetranslate.debug", context.packageName)
        val permission = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "appops set " + context.packageName + " SYSTEM_ALERT_WINDOW allow"
            )
        ).bufferedReader().use { it.readText() }
        assertTrue("Overlay permission required: " + permission, Settings.canDrawOverlays(context))
        val initial = CaptionSettings(captionPlacement = CaptionPlacement.BUBBLE, bubbleY = 360,
            bubbleSide = BubbleSide.RIGHT)
        val current = MutableStateFlow(initial)
        val preferences = object : SettingsRepository {
            override val settingsFlow: Flow<CaptionSettings> = current
            override suspend fun update(transform: (CaptionSettings) -> CaptionSettings) {
                current.value = transform(current.value)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var controller: BubbleOverlayController? = null
        val idle = OverlayUiState(status = RecognitionStatus.LISTENING)
        val active = idle.copy(messages = listOf(OverlayCaptionMessage(
            100L, "synthetic English example", "테스트 전용 합성 한국어 번역", true
        )))
        val output = File(context.getExternalFilesDir(null), "bubble-captures")
        assertTrue(output.isDirectory || output.mkdirs())
        try {
            activity.scenario.onActivity { screen ->
                controller = BubbleOverlayController(
                    screen.applicationContext, scope, preferences, {}, {}
                )
                controller!!.show(initial)
                controller!!.update(idle, initial)
            }
            val before = capture(output, "bubble-01-idle.png")
            activity.scenario.onActivity { controller!!.update(active, initial) }
            val during = capture(output, "bubble-02-translation.png")
            assertFalse("Translated synthetic text must change visible pixels", before.sameAs(during))
            before.recycle()
            during.recycle()
            assertTrue(File(output, "bubble-01-idle.png").length() > 0)
            assertTrue(File(output, "bubble-02-translation.png").length() > 0)
            val target = "/sdcard/Download/hebi-bubble-qa-captures"
            readShell("mkdir -p " + target)
            readShell("cp -R /sdcard/Android/data/" + context.packageName +
                "/files/bubble-captures/. " + target + "/")
            val exported = readShell("ls -1 " + target)
            assertTrue(exported.contains("bubble-01-idle.png"))
            assertTrue(exported.contains("bubble-02-translation.png"))
        } finally {
            activity.scenario.onActivity { controller?.hide() }
            scope.cancel()
        }
    }

    private fun readShell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }

    private fun capture(directory: File, name: String): Bitmap {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(1000, 10000)
        SystemClock.sleep(300)
        val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, name).outputStream().use { stream ->
            assertTrue(shot.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        return shot
    }
}
