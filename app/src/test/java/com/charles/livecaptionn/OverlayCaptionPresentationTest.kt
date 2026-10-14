package com.charles.livecaptionn

import com.charles.livecaptionn.overlay.OverlayCaptionMessage
import com.charles.livecaptionn.overlay.OverlayUiState
import com.charles.livecaptionn.overlay.computeOverlayTextDisplay
import com.charles.livecaptionn.overlay.overlayCaptionMessages
import com.charles.livecaptionn.overlay.visibleOverlayMessages
import com.charles.livecaptionn.service.CaptionRuntimeLine
import com.charles.livecaptionn.service.CaptionRuntimeState
import com.charles.livecaptionn.service.applyTranscriptTranslation
import com.charles.livecaptionn.service.buildOverlayUiState
import com.charles.livecaptionn.service.recordTranscriptResult
import com.charles.livecaptionn.settings.CaptionDisplayMode
import com.charles.livecaptionn.settings.CaptionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayCaptionPresentationTest {
    @Test
    fun stableCaptionsKeepTranslatedFinalAndItsSourceWhileFreshSpeechArrives() {
        val settings = CaptionSettings(showOriginal = true)
        val previous = CaptionRuntimeLine(1L, "finished sentence", "완성 문장", isFinal = true)
        val runtime = CaptionRuntimeState(
            originalText = "unrelated new partial",
            translatedText = "orphan runtime translation",
            transcriptLines = listOf(previous, CaptionRuntimeLine(2L, "new partial", "부분 번역"))
        )
        val partialDisplay = computeOverlayTextDisplay(buildOverlayUiState(runtime, settings))
        assertEquals("finished sentence", partialDisplay.originalText)
        assertEquals("완성 문장", partialDisplay.translatedText)

        val finalLines = recordTranscriptResult(runtime.transcriptLines, "new partial", true, 3L)
        val awaiting = runtime.copy(transcriptLines = finalLines)
        assertEquals(partialDisplay, computeOverlayTextDisplay(buildOverlayUiState(awaiting, settings)))
        val committed = applyTranscriptTranslation(awaiting, 2L, "새 문장", isFinalTranslation = true)
        val finalDisplay = computeOverlayTextDisplay(buildOverlayUiState(committed, settings))
        assertEquals("finished sentence\nnew partial", finalDisplay.originalText)
        assertEquals("완성 문장\n새 문장", finalDisplay.translatedText)
    }

    @Test
    fun stableCaptionsExcludeUncommittedFinalTranslations() {
        val runtime = CaptionRuntimeState(transcriptLines = listOf(
            CaptionRuntimeLine(1L, "pending final", "provisional", isFinal = true, finalTranslationCommitted = false),
            CaptionRuntimeLine(2L, "translated final", "확정 번역", isFinal = true)
        ))
        assertEquals(listOf(2L), overlayCaptionMessages(runtime).map { it.id })
    }

    @Test
    fun previewsArePairedAndMarkedProvisionalWithoutRawFallback() {
        val runtime = CaptionRuntimeState(
            originalText = "a newer raw snapshot",
            translatedText = "an orphan translation",
            transcriptLines = listOf(
                CaptionRuntimeLine(1L, "finished", "확정", isFinal = true),
                CaptionRuntimeLine(2L, "matching partial", "일치하는 부분")
            )
        )
        val ui = buildOverlayUiState(runtime, CaptionSettings(stableCaptions = false, showOriginal = true))
        assertEquals(listOf(1L, 2L), ui.messages.map { it.id })
        assertTrue(ui.messages.first().isFinal)
        assertFalse(ui.messages.last().isFinal)
        val display = computeOverlayTextDisplay(ui)
        assertEquals("finished\nmatching partial", display.originalText)
        assertEquals("확정\n일치하는 부분", display.translatedText)
    }

    @Test
    fun untranslatedSpeechNeverGetsDuplicatedAsTranslation() {
        val runtime = CaptionRuntimeState(originalText = "raw speech", translatedText = "orphan")
        for (stable in listOf(true, false)) {
            val ui = buildOverlayUiState(runtime, CaptionSettings(stableCaptions = stable, showOriginal = true))
            assertTrue(ui.messages.isEmpty())
            val display = computeOverlayTextDisplay(ui)
            assertFalse(display.showOriginal)
            assertEquals("", display.originalText)
            assertEquals("…", display.translatedText)
        }
    }

    @Test
    fun messagesStayOrderedAndBoundedAndPreserveRepeatedUtterances() {
        val runtime = CaptionRuntimeState(transcriptLines = (1..50).map {
            CaptionRuntimeLine(it.toLong(), "hello", "안녕", isFinal = true)
        })
        val messages = overlayCaptionMessages(runtime)
        assertEquals(40, messages.size)
        assertEquals((11L..50L).toList(), messages.map { it.id })
        assertEquals(40, messages.count { it.translatedText == "안녕" })
    }

    @Test
    fun captionModeShowsLatestTwoAndChatModeKeepsForty() {
        val messages = (1..45).map { OverlayCaptionMessage(it.toLong(), "source $it", "번역 $it", true) }
        val captions = OverlayUiState(messages = messages)
        assertEquals(listOf(44L, 45L), visibleOverlayMessages(captions).map { it.id })
        assertEquals("source 44\nsource 45", computeOverlayTextDisplay(captions).originalText)
        assertEquals("번역 44\n번역 45", computeOverlayTextDisplay(captions).translatedText)
        val chat = captions.copy(displayMode = CaptionDisplayMode.CHAT)
        assertEquals((6L..45L).toList(), visibleOverlayMessages(chat).map { it.id })
    }

    @Test
    fun uiBoundaryAlsoFiltersPartialMessagesInStableMode() {
        val ui = OverlayUiState(messages = listOf(
            OverlayCaptionMessage(1L, "finished", "확정", true),
            OverlayCaptionMessage(2L, "partial", "부분", false)
        ))
        assertEquals(listOf(1L), visibleOverlayMessages(ui).map { it.id })
        assertEquals(listOf(1L, 2L), visibleOverlayMessages(ui.copy(stableCaptions = false)).map { it.id })
    }

    @Test
    fun chatMemoryIsIndependentOfDiskHistoryPreference() {
        val runtime = CaptionRuntimeState(transcriptLines = (1..40).map {
            CaptionRuntimeLine(it.toLong(), "source $it", "번역 $it", isFinal = true)
        })
        val settings = CaptionSettings(captionDisplayMode = CaptionDisplayMode.CHAT, overlayControlsExpanded = true)
        val memoryOnly = buildOverlayUiState(runtime, settings.copy(saveHistory = false))
        val saved = buildOverlayUiState(runtime, settings.copy(saveHistory = true))
        assertEquals(memoryOnly.messages, saved.messages)
        assertEquals(40, memoryOnly.messages.size)
        assertEquals(CaptionDisplayMode.CHAT, memoryOnly.displayMode)
        assertTrue(memoryOnly.controlsExpanded)
    }
}
