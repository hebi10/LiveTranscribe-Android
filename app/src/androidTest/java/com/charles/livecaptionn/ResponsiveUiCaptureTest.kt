package com.charles.livecaptionn

import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real idle Android layouts at fixed screen/font configurations. No model inference. */
@RunWith(AndroidJUnit4::class)
class ResponsiveUiCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private data class Variant(val width: Int, val height: Int, val fontScale: Float)
    private val variants = mapOf(
        "compact-font160" to Variant(840, 1480, 1.6f),
        "landscape" to Variant(1920, 1080, 1f),
        "tablet" to Variant(1600, 2560, 1f)
    )

    @Test fun captureHomeAndExpandedSettings() {
        val variant = requireNotNull(instrumentation.arguments.getString("qaVariant"))
        val expected = requireNotNull(variants[variant]) { "Unsupported QA variant: $variant" }
        val context = instrumentation.targetContext
        assertEquals("com.hebi.livetranslate.debug", context.packageName)
        assertEquals(35, Build.VERSION.SDK_INT)
        val actualScale = compose.activity.resources.configuration.fontScale
        assertTrue("Expected fontScale ${expected.fontScale}, actual $actualScale",
            abs(actualScale - expected.fontScale) < 0.08f)
        val output = File(context.getExternalFilesDir(null), "responsive-qa")
        assertTrue(output.isDirectory || output.mkdirs())
        compose.onNodeWithText("실시간 영상 번역").assertIsDisplayed()
        val home = "qa-20-${variant}-home.png"
        val settings = "qa-21-${variant}-settings.png"
        capture(output, home, expected)

        compose.onNodeWithText("자막 및 앱 설정 펼치기").performScrollTo().performClick()
        compose.onNodeWithText("개인정보처리방침 (외부 브라우저)").performScrollTo().assertIsDisplayed()
        capture(output, settings, expected)

        val files = listOf(home, settings)
        val manifest = JSONObject().apply {
            put("variant", variant)
            put("sourceRevision", instrumentation.arguments.getString("qaRevision", "local"))
            put("applicationId", context.packageName)
            put("fontScale", actualScale.toDouble())
            put("width", expected.width)
            put("height", expected.height)
            put("screenshots", JSONArray(files))
            put("scope", "Actual idle Android UI responsive QA; models, audio capture and inference not exercised.")
        }
        File(output, "qa-manifest-${variant}.json").writeText(manifest.toString(2), Charsets.UTF_8)
        readShell("mkdir -p /sdcard/Download/hebi-responsive-qa-captures")
        readShell(
            "cp -R /sdcard/Android/data/${context.packageName}/files/responsive-qa/. " +
                "/sdcard/Download/hebi-responsive-qa-captures/"
        )
        val exported = readShell("ls -1 /sdcard/Download/hebi-responsive-qa-captures")
            .lineSequence().filter { it.isNotBlank() }.toSet()
        assertTrue(exported.containsAll(files + "qa-manifest-${variant}.json"))
    }

    private fun readShell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }

    private fun capture(folder: File, name: String, expected: Variant) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(1_000, 10_000)
        Thread.sleep(350)
        val shot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertEquals(expected.width, shot.width)
            assertEquals(expected.height, shot.height)
            File(folder, name).outputStream().use {
                assertTrue(shot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            shot.recycle()
        }
    }
}
