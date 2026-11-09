package com.charles.livecaptionn

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Captures real idle MainActivity screens; does not prepare models or simulate inference. */
@RunWith(AndroidJUnit4::class)
class PlayStoreCaptureTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val captureNames = mutableListOf<String>()

    @Test
    fun captureCurrentAppPreparationAndSettings() {
        val context = instrumentation.targetContext
        assertEquals("Captures must use the separate test package",
            "com.hebi.livetranslate.debug", context.packageName)
        val directory = File(context.getExternalFilesDir(null), "play-captures")
        assertTrue(directory.isDirectory || directory.mkdirs())

        compose.onNodeWithText("실시간 영상 번역").assertIsDisplayed()
        compose.onNodeWithText("음성 캡처 권한 허용").assertIsDisplayed()
        capture(directory, "01-home-preparation.png")

        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("분할 화면").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("모델 다운로드 (약 2.1GB)")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("모델 다운로드 (약 2.1GB)").performScrollTo().assertIsDisplayed()
        capture(directory, "02-model-preparation.png")

        compose.onNodeWithText("모든 모드는 같은 모델을 사용하므로 추가 다운로드가 없습니다.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("자동 감지").assertIsDisplayed()
        compose.onNodeWithText("일본어").assertIsDisplayed()
        compose.onNodeWithText("영어").assertIsDisplayed()
        capture(directory, "03-language-settings.png")

        compose.onNodeWithText("자막 및 앱 설정 펼치기").performScrollTo().performClick()
        compose.onNodeWithText("개인정보처리방침 (외부 브라우저)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("번역 기록 저장 (기본 꺼짐)").assertIsOff()
        capture(directory, "04-privacy-settings.png")

        compose.onNodeWithContentDescription("번역 기록").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("아직 저장된 기록이 없습니다.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("아직 저장된 기록이 없습니다.").assertIsDisplayed()
        capture(directory, "05-history-empty.png")
        exportBrandGraphics(context, directory)

        val manifest = JSONObject().apply {
            put("sourceRevision", InstrumentationRegistry.getArguments().getString("captureRevision", "local"))
            put("applicationId", context.packageName)
            put("apiLevel", Build.VERSION.SDK_INT)
            put("device", Build.MODEL)
            put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            put("width", 1080)
            put("height", 1920)
            put("screenshots", JSONArray(captureNames))
            put("graphics", JSONArray().apply {
                put(JSONObject().put("file", "icon.png").put("width", 512).put("height", 512)
                    .put("source", "Existing R.mipmap.ic_launcher background/foreground; no launcher mask"))
                put(JSONObject().put("file", "featureGraphic.png").put("width", 1024).put("height", 500)
                    .put("source", "Existing launcher icon, Android system font, factual Korean app description"))
            })
            put("scope", "Actual MainActivity idle UI. Models not downloaded; no audio capture or inference exercised; no fabricated transcripts.")
        }
        File(directory, "capture-manifest.json").writeText(manifest.toString(2), Charsets.UTF_8)
        preserveCapturesBeforeTestAppRemoval()
    }

    private fun capture(directory: File, name: String) {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        val frameCommitted = CountDownLatch(1)
        compose.runOnUiThread {
            val view = compose.activity.window.decorView
            view.viewTreeObserver.registerFrameCommitCallback { frameCommitted.countDown() }
            view.postInvalidateOnAnimation()
        }
        assertTrue("The current Android frame must be committed before capture",
            frameCommitted.await(10, TimeUnit.SECONDS))
        instrumentation.uiAutomation.waitForIdle(1_000, 10_000)
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "Android did not return a device screenshot"
        }
        try {
            assertEquals("Set emulator wm size to 1080x1920", 1080, bitmap.width)
            assertEquals("Set emulator wm size to 1080x1920", 1920, bitmap.height)
            File(directory, name).outputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally {
            bitmap.recycle()
        }
        captureNames.lastOrNull()?.let { previous ->
            assertTrue("Different app screens must not repeat the previous device frame",
                !File(directory, name).readBytes().contentEquals(File(directory, previous).readBytes()))
        }
        captureNames += name
    }

    private fun exportBrandGraphics(context: Context, directory: File) {
        val icon = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        try {
            drawLauncherLayers(context, Canvas(icon), 0, 0, 512)
            saveBitmap(icon, File(directory, "icon.png"))

            val feature = Bitmap.createBitmap(1024, 500, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(feature)
                canvas.drawColor(Color.rgb(17, 17, 17))
                drawLauncherLayers(context, canvas, 64, 142, 216)
                val title = "실시간 영상 번역"
                val description = "기기 안에서 음성 인식 · 번역 자막"
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    textSize = 56f
                }
                assertTrue(title.filterNot { it.isWhitespace() }.all { paint.hasGlyph(it.toString()) })
                assertTrue(paint.measureText(title) <= 612f)
                canvas.drawText(title, 348f, 225f, paint)
                paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                paint.textSize = 28f
                paint.color = Color.rgb(204, 204, 204)
                assertTrue(description.filterNot { it.isWhitespace() }.all { paint.hasGlyph(it.toString()) })
                assertTrue(paint.measureText(description) <= 612f)
                canvas.drawText(description, 348f, 282f, paint)
                saveBitmap(feature, File(directory, "featureGraphic.png"))
            } finally {
                feature.recycle()
            }
        } finally {
            icon.recycle()
        }
    }

    private fun drawLauncherLayers(context: Context, canvas: Canvas, x: Int, y: Int, size: Int) {
        val launcher = requireNotNull(context.getDrawable(R.mipmap.ic_launcher)).mutate()
        if (launcher is AdaptiveIconDrawable) {
            for (layer in listOf(launcher.background, launcher.foreground)) {
                layer.setBounds(x, y, x + size, y + size)
                layer.draw(canvas)
            }
        } else {
            launcher.setBounds(x, y, x + size, y + size)
            launcher.draw(canvas)
        }
    }

    private fun saveBitmap(bitmap: Bitmap, file: File) {
        file.outputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private fun preserveCapturesBeforeTestAppRemoval() {
        readShellOutput("mkdir -p /sdcard/Download/hebi-play-captures")
        readShellOutput("cp -R /sdcard/Android/data/com.hebi.livetranslate.debug/files/play-captures/. /sdcard/Download/hebi-play-captures/")
        val exported = readShellOutput("ls -1 /sdcard/Download/hebi-play-captures")
            .lineSequence().filter { it.isNotBlank() }.toSet()
        val expected = (captureNames + listOf("icon.png", "featureGraphic.png", "capture-manifest.json")).toSet()
        assertEquals("All device captures must survive Gradle's test app cleanup", expected, exported)
    }

    private fun readShellOutput(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
}
