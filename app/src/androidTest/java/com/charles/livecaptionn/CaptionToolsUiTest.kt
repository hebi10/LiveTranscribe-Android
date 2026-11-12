package com.charles.livecaptionn

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.charles.livecaptionn.data.CaptionProfile
import com.charles.livecaptionn.data.FileCaptionProfileRepository
import com.charles.livecaptionn.data.FileGlossaryRepository
import com.charles.livecaptionn.settings.CaptionSettings
import com.charles.livecaptionn.ui.CaptionToolsScreen
import com.charles.livecaptionn.ui.theme.CaptionFrameTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic UI actions against separate, test-only files; never a real translation result. */
@RunWith(AndroidJUnit4::class)
class CaptionToolsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun glossaryAddEditDeletePersistsToLocalRepository() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val path = File(cache, "final-qa-01-glossary-ui.json")
        path.delete()
        val profilesPath = File(cache, "final-qa-01-unused-profiles.json")
        profilesPath.delete()
        val glossary = FileGlossaryRepository(path, forTesting = true)
        val profiles = FileCaptionProfileRepository(profilesPath, forTesting = true)
        compose.setContent {
            CaptionFrameTheme(darkTheme = false) {
                CaptionToolsScreen(glossaryRepository = glossary, profileRepository = profiles,
                    currentSettings = CaptionSettings(), canChangeSettings = true,
                    onApplyProfile = { true }, onBack = {})
            }
        }
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("등록한 용어가 없습니다.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("용어 추가").performScrollTo().performClick()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("API")
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("인터페이스")
        compose.onNodeWithText("저장").performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("변경할 표현: 인터페이스").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("인터페이스", runBlocking { glossary.list().single().replacement })

        compose.onNodeWithText("수정").performScrollTo().performClick()
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("응용 인터페이스")
        compose.onNodeWithText("저장").performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("변경할 표현: 응용 인터페이스").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(1, runBlocking { glossary.list().size })
        assertEquals("응용 인터페이스", runBlocking { glossary.list().single().replacement })

        compose.onNodeWithText("삭제").performScrollTo().performClick()
        compose.onNodeWithText("항목 삭제").assertIsDisplayed()
        val deleteButtons = compose.onAllNodesWithText("삭제")
        deleteButtons[deleteButtons.fetchSemanticsNodes().lastIndex].performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("등록한 용어가 없습니다.").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(runBlocking { glossary.list().isEmpty() })
        path.delete()
        profilesPath.delete()
    }

    @Test fun presetSavesAppliesAndBlocksEditingDuringActiveTranslation() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val glossaryPath = File(cache, "final-qa-01-empty-glossary.json")
        val profilePath = File(cache, "final-qa-01-presets-ui.json")
        glossaryPath.delete()
        profilePath.delete()
        val glossary = FileGlossaryRepository(glossaryPath, forTesting = true)
        val profiles = FileCaptionProfileRepository(profilePath, forTesting = true)
        val editable = mutableStateOf(true)
        val applied = mutableStateOf<CaptionProfile?>(null)
        compose.setContent {
            CaptionFrameTheme(darkTheme = true) {
                CaptionToolsScreen(glossaryRepository = glossary, profileRepository = profiles,
                    currentSettings = CaptionSettings(), canChangeSettings = editable.value,
                    onApplyProfile = { profile ->
                        applied.value = profile
                        true
                    }, onBack = {})
            }
        }
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("저장된 자막 프리셋이 없습니다.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("현재 설정으로 프리셋 추가").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("영화 자막")
        compose.onNodeWithText("일본어").performScrollTo().performClick()
        compose.onNodeWithText("저장").performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("영화 자막").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("ja", runBlocking { profiles.list().single().sourceLanguage })
        compose.onNodeWithText("적용").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 15_000) { applied.value != null }
        assertEquals("ja", applied.value?.sourceLanguage)
        compose.runOnUiThread { editable.value = false }
        compose.onNodeWithText("적용").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("수정").performScrollTo().assertIsNotEnabled()
        glossaryPath.delete()
        profilePath.delete()
    }
}
