package com.charles.livecaptionn

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.charles.livecaptionn.data.TranscriptEntry
import com.charles.livecaptionn.data.TranscriptHistoryStore
import com.charles.livecaptionn.ui.HistoryScreen
import com.charles.livecaptionn.ui.l10n.UiStringsProvider
import com.charles.livecaptionn.ui.theme.CaptionFrameTheme
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Local sample transcript fixture for UI QA, never a generated Play listing or ASR result. */
@RunWith(AndroidJUnit4::class)
class HistoryUiCaptureTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun captureHistoryListSearchDeleteAndDarkTheme() {
        val context = instrumentation.targetContext
        assertEquals("com.hebi.livetranslate.debug", context.packageName)
        val qaFile = File(context.cacheDir, "caption-frame-ui-qa.json")
        qaFile.delete()
        File(context.cacheDir, "caption-frame-ui-qa.json.bak").delete()
        val store = TranscriptHistoryStore(qaFile, forTesting = true)
        runBlocking {
            store.add(TranscriptEntry(
                id = "qa-first", originalText = "This is a sample for interface testing.",
                translatedText = "인터페이스 확인을 위한 예시 번역 A",
                sourceLanguage = "en", targetLanguage = "ko"
            ))
            store.add(TranscriptEntry(
                id = "qa-second", originalText = "Another sample caption for the history list.",
                translatedText = "인터페이스 확인을 위한 예시 번역 B",
                sourceLanguage = "en", targetLanguage = "ko"
            ))
        }
        val output = File(context.getExternalFilesDir(null), "history-qa-captures")
        assertTrue(output.isDirectory || output.mkdirs())
        val darkMode = mutableStateOf(false)
        val saving = MutableStateFlow(true)
        compose.setContent {
            UiStringsProvider {
                CaptionFrameTheme(darkTheme = darkMode.value) {
                    HistoryScreen(
                        historyStore = store,
                        historySavingEnabled = saving,
                        onHistorySavingChange = { saving.value = it },
                        onBack = {}
                    )
                }
            }
        }
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("인터페이스 확인을 위한 예시 번역 B")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("저장된 기록 2개").assertIsDisplayed()
        capture(output, "qa-10-history-list-light.png")

        compose.onNode(hasSetTextAction()).performTextInput("없는검색결과")
        compose.onNodeWithText("검색 결과가 없습니다.", substring = true).assertIsDisplayed()
        capture(output, "qa-11-history-no-results.png")

        compose.onNodeWithContentDescription("검색어 지우기").performClick()
        compose.onAllNodesWithContentDescription("삭제")[0].performClick()
        compose.onNodeWithText("기록 삭제").assertIsDisplayed()
        capture(output, "qa-12-history-delete-confirm.png")
        compose.onNodeWithText("취소").performClick()

        compose.runOnUiThread { darkMode.value = true }
        compose.waitForIdle()
        capture(output, "qa-13-history-list-dark.png")

        val names = listOf(
            "qa-10-history-list-light.png", "qa-11-history-no-results.png",
            "qa-12-history-delete-confirm.png", "qa-13-history-list-dark.png"
        )
        val manifest = JSONObject().apply {
            put("applicationId", context.packageName)
            put("scope", "Synthetic local history UI QA only; never use as Play Store screenshots.")
            put("screenshots", JSONArray(names))
        }
        File(output, "qa-history-manifest.json").writeText(manifest.toString(2), Charsets.UTF_8)
        readShell("mkdir -p /sdcard/Download/hebi-history-qa-captures")
        readShell(
            "cp -R /sdcard/Android/data/${context.packageName}/files/history-qa-captures/. " +
                "/sdcard/Download/hebi-history-qa-captures/"
        )
        val exported = readShell("ls -1 /sdcard/Download/hebi-history-qa-captures")
            .lineSequence().filter { it.isNotBlank() }.toSet()
        assertTrue(exported.containsAll(names + "qa-history-manifest.json"))
        qaFile.delete()
        File(context.cacheDir, "caption-frame-ui-qa.json.bak").delete()
    }

    private fun readShell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)
        ).bufferedReader().use { it.readText() }

    private fun capture(folder: File, name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(1_000, 10_000)
        Thread.sleep(400)
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            assertEquals(1080, screenshot.width)
            assertEquals(1920, screenshot.height)
            File(folder, name).outputStream().use {
                assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            screenshot.recycle()
        }
    }
}
