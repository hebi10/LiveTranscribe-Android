package com.charles.livecaptionn

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.charles.livecaptionn.data.SettingsRepository
import com.charles.livecaptionn.overlay.OverlayController
import com.charles.livecaptionn.overlay.OverlayUiState
import com.charles.livecaptionn.service.CaptionPhase
import com.charles.livecaptionn.settings.CaptionSettings
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

/** Real WindowManager overlay with no audio capture or fabricated translated text. */
@RunWith(AndroidJUnit4::class)
class OverlayVisualCaptureTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun captureCompactExpandedAndMinimizedPopupStates() {
        val context = instrumentation.targetContext
        assertEquals("com.hebi.livetranslate.debug", context.packageName)
        val output = File(context.getExternalFilesDir(null), "overlay-captures")
        assertTrue(output.isDirectory || output.mkdirs())
        val permissionOutput = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        ).bufferedReader().use { it.readText() }
        assertTrue("Emulator overlay permission required: $permissionOutput", Settings.canDrawOverlays(context))

        val prefs = MutableStateFlow(CaptionSettings())
        val settings = object : SettingsRepository {
            override val settingsFlow: Flow<CaptionSettings> = prefs
            override suspend fun update(transform: (CaptionSettings) -> CaptionSettings) {
                prefs.value = transform(prefs.value)
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var controller: OverlayController? = null
        val idle = OverlayUiState(phase = CaptionPhase.WAITING)
        try {
            activity.scenario.onActivity { screen ->
                controller = OverlayController(
                    context = screen.applicationContext, scope = scope, settingsRepository = settings,
                    onPauseResume = {}, onClose = {}, onToggleMinimize = {},
                    onAcceptLanguageSuggestion = {}, onDismissLanguageSuggestion = {}
                )
                controller!!.show(24, 420, 340, 160)
                controller!!.update(idle)
            }
            capture(output, "07-popup-compact.png")
            activity.scenario.onActivity { controller!!.update(idle.copy(controlsExpanded = true)) }
            capture(output, "08-popup-expanded.png")
            activity.scenario.onActivity { controller!!.update(idle.copy(controlsExpanded = true, minimized = true)) }
            capture(output, "09-popup-minimized.png")
            assertFalse(File(output, "07-popup-compact.png").readBytes().contentEquals(
                File(output, "08-popup-expanded.png").readBytes()))
            assertFalse(File(output, "08-popup-expanded.png").readBytes().contentEquals(
                File(output, "09-popup-minimized.png").readBytes()))
            readShell("mkdir -p /sdcard/Download/hebi-overlay-captures")
            readShell("cp -R /sdcard/Android/data/${context.packageName}/files/overlay-captures/. " +
                "/sdcard/Download/hebi-overlay-captures/")
            val exported = readShell("ls -1 /sdcard/Download/hebi-overlay-captures")
                .lineSequence().filter { it.isNotBlank() }.toSet()
            assertEquals(setOf("07-popup-compact.png", "08-popup-expanded.png", "09-popup-minimized.png"), exported)
        } finally {
            activity.scenario.onActivity { controller?.hide() }
            scope.cancel()
        }
    }

    private fun readShell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }

    private fun capture(directory: File, name: String) {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(1_000, 10_000)
        val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "Android did not return a screenshot"
        }
        try {
            assertEquals(1080, shot.width)
            assertEquals(1920, shot.height)
            File(directory, name).outputStream().use { out ->
                assertTrue(shot.compress(Bitmap.CompressFormat.PNG, 100, out))
            }
        } finally {
            shot.recycle()
        }
    }
}
