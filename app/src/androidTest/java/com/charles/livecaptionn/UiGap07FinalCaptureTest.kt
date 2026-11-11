package com.charles.livecaptionn

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.semantics.SemanticsProperties
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

/** Instrumented idle Android UI only. No models, real translation or physical fold tested. */
@RunWith(AndroidJUnit4::class)
class UiGap07FinalCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private data class Variant(
        val width: Int, val height: Int, val density: Int,
        val fontScale: Float, val dark: Boolean
    )

    private val variants = mapOf(
        "phone-light" to Variant(1080, 1920, 420, 1f, false),
        "phone-dark" to Variant(1080, 1920, 420, 1f, true),
        "compact-light160" to Variant(840, 1480, 420, 1.6f, false),
        "compact-dark160" to Variant(840, 1480, 420, 1.6f, true),
        "landscape-light" to Variant(1920, 1080, 420, 1f, false),
        "landscape-dark" to Variant(1920, 1080, 420, 1f, true),
        "tablet-light" to Variant(1600, 2560, 320, 1f, false),
        "tablet-dark" to Variant(1600, 2560, 320, 1f, true),
        "fold-cover-light" to Variant(904, 2268, 420, 1f, false),
        "fold-expanded-dark" to Variant(1768, 2208, 360, 1f, true)
    )

    @Test fun verifyAndCaptureHomeSettingsHistoryAndCaptionPreview() {
        val arguments = InstrumentationRegistry.getArguments()
        val name = requireNotNull(arguments.getString("qaVariant"))
        val expected = requireNotNull(variants[name]) { "Unexpected UI-GAP-07 variant: $name" }
        val requestedApi = requireNotNull(arguments.getString("qaApi")).toInt()
        val context = instrumentation.targetContext

        assertEquals("com.hebi.livetranslate.debug", context.packageName)
        assertEquals(requestedApi, Build.VERSION.SDK_INT)
        val actualConfiguration = compose.activity.resources.configuration
        assertTrue("Font scale mismatch: " + expected.fontScale + " != " + actualConfiguration.fontScale,
            abs(expected.fontScale - actualConfiguration.fontScale) < 0.08f)
        assertEquals("Display density mismatch", expected.density, actualConfiguration.densityDpi)
        val actualDark = (actualConfiguration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        assertEquals("Emulator system theme mismatch", expected.dark, actualDark)

        val folder = File(context.getExternalFilesDir(null), "ui-gap-07")
        assertTrue(folder.isDirectory || folder.mkdirs())
        val captures = mutableListOf<String>()
        fun take(screen: String) {
            val fileName = "qa-07-$name-$screen.png"
            capture(folder, fileName, expected)
            captures.add(fileName)
        }

        compose.onNodeWithText("실시간 영상 번역").assertIsDisplayed()

        // App theme defaults to DARK; toggling Android night mode alone is not a UI theme test.
        compose.onNodeWithText("자막 및 앱 설정 펼치기").performScrollTo().performClick()
        val appThemeLabel = if (expected.dark) "다크" else "라이트"
        compose.onNodeWithText(appThemeLabel).performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText(appThemeLabel).fetchSemanticsNodes().any {
                it.config.getOrNull(SemanticsProperties.Selected) == true
            }
        }
        compose.onNodeWithText(appThemeLabel).assertIsSelected()
        compose.onNodeWithText("자막 및 앱 설정 접기").performScrollTo().performClick()
        take("home")

        compose.onNodeWithText("자막 및 앱 설정 펼치기").performScrollTo().performClick()
        compose.onNodeWithText("개인정보처리방침 (외부 브라우저)")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(appThemeLabel).performScrollTo().assertIsSelected()
        take("settings")

        compose.onNodeWithContentDescription("번역 기록").performClick()
        compose.onNodeWithText("아직 저장된 기록이 없습니다.").assertIsDisplayed()
        take("history")

        compose.activityRule.scenario.onActivity {
            it.onBackPressedDispatcher.onBackPressed()
        }
        compose.onNodeWithText("자막 화면 미리 보기").performScrollTo().performClick()
        compose.onNodeWithText("영상 옆에, 자막만").assertIsDisplayed()
        take("caption-preview")

        val manifest = JSONObject().apply {
            put("variant", name)
            put("sourceRevision", arguments.getString("qaRevision", "local"))
            put("applicationId", context.packageName)
            put("apiLevel", requestedApi)
            put("device", Build.MODEL)
            put("width", expected.width)
            put("height", expected.height)
            put("densityDpi", actualConfiguration.densityDpi)
            put("fontScale", actualConfiguration.fontScale.toDouble())
            put("nightMode", if (actualDark) "dark" else "light")
            put("appThemeMode", if (expected.dark) "DARK" else "LIGHT")
            put("screenshots", JSONArray(captures))
            put("simulatedFoldSizeOnly", name.startsWith("fold-"))
            put("physicalFoldVerified", false)
            put("offlineInferenceVerified", false)
            put("scope", "Real emulator idle UI only; no physical fold, model install, speech recognition or translation.")
        }
        val manifestName = "qa-manifest-$name.json"
        File(folder, manifestName).writeText(manifest.toString(2), Charsets.UTF_8)
        readShell("mkdir -p /sdcard/Download/hebi-ui-gap-07-captures")
        readShell("cp -R /sdcard/Android/data/" + context.packageName + "/files/ui-gap-07/. " +
            "/sdcard/Download/hebi-ui-gap-07-captures/")
        val exported = readShell("ls -1 /sdcard/Download/hebi-ui-gap-07-captures")
            .lineSequence().filter { it.isNotBlank() }.toSet()
        assertTrue("Capture evidence not exported", exported.containsAll(captures + manifestName))
    }

    private fun capture(folder: File, name: String, variant: Variant) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(1_000, 10_000)
        Thread.sleep(350)
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertEquals(variant.width, screenshot.width)
            assertEquals(variant.height, screenshot.height)
            File(folder, name).outputStream().use { stream ->
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
        } finally {
            screenshot.recycle()
        }
    }

    private fun readShell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }
}
