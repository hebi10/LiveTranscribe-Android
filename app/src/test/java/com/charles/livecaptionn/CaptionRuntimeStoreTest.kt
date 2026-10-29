package com.charles.livecaptionn

import com.charles.livecaptionn.service.CaptionRuntimeState
import com.charles.livecaptionn.service.CaptionRuntimeStore
import com.charles.livecaptionn.service.CaptionRuntimeLine
import com.charles.livecaptionn.service.applyTranscriptResult
import com.charles.livecaptionn.service.updateCaptionWindowLanguage
import com.charles.livecaptionn.speech.RecognitionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionRuntimeStoreTest {
    @Test fun lateJapaneseDetectionAlsoNormalizesExistingCaptionText() {
        val state = CaptionRuntimeState(originalText = "東 京", transcriptLines = listOf(
            CaptionRuntimeLine(1, "東 京", "도쿄", true, audioWindowId = 12)))
        val updated = updateCaptionWindowLanguage(state, 12, "ja")
        assertEquals("東京", updated.originalText)
        assertEquals("東京", updated.transcriptLines.single().originalText)
        assertEquals("도쿄", updated.transcriptLines.single().translatedText)
    }

    @Test
    fun initialState_isDefault() {
        val store = CaptionRuntimeStore()
        val state = store.state.value
        assertEquals("", state.originalText)
        assertEquals("", state.translatedText)
        assertEquals(emptyList<Any>(), state.transcriptLines)
        assertEquals(RecognitionStatus.IDLE, state.status)
        assertFalse(state.running)
        assertFalse(state.paused)
        assertNull(state.lastError)
    }

    @Test
    fun update_changesState() {
        val store = CaptionRuntimeStore()
        store.update { it.copy(running = true, status = RecognitionStatus.LISTENING) }
        assertTrue(store.state.value.running)
        assertEquals(RecognitionStatus.LISTENING, store.state.value.status)
    }

    @Test
    fun update_preservesOtherFields() {
        val store = CaptionRuntimeStore()
        store.update { it.copy(originalText = "hello") }
        store.update { it.copy(translatedText = "xin chao") }
        assertEquals("hello", store.state.value.originalText)
        assertEquals("xin chao", store.state.value.translatedText)
    }

    @Test
    fun update_toDefault_resetsState() {
        val store = CaptionRuntimeStore()
        store.update { it.copy(running = true, originalText = "test") }
        store.update { CaptionRuntimeState() }
        assertFalse(store.state.value.running)
        assertEquals("", store.state.value.originalText)
    }

    @Test
    fun speechWindowIdStaysWithTheRecordedLine() {
        val state = applyTranscriptResult(CaptionRuntimeState(), "hello", true, 7, audioWindowId = 12)
        assertEquals(12L, state.transcriptLines.single().audioWindowId)
    }

    @Test
    fun lateDetectionUpdatesEveryMatchingSentenceWithoutDuplicatingCaptions() {
        val matching = CaptionRuntimeLine(1, "first", "첫째", true, audioWindowId = 12)
        val state = CaptionRuntimeState(transcriptLines = listOf(matching,
            CaptionRuntimeLine(2, "second", "둘째", true, audioWindowId = 12),
            CaptionRuntimeLine(3, "third", "셋째", true, sourceLanguageCode = "en", audioWindowId = 13)))
        val updated = updateCaptionWindowLanguage(state, 12, "Japanese")
        assertEquals(listOf(1L, 2L, 3L), updated.transcriptLines.map { it.id })
        assertEquals(listOf("ja", "ja", "en"), updated.transcriptLines.map { it.sourceLanguageCode })
        assertEquals(listOf("첫째", "둘째", "셋째"), updated.transcriptLines.map { it.translatedText })
        assertTrue(updated.transcriptLines.all { it.finalTranslationCommitted })
        assertEquals(state, updateCaptionWindowLanguage(state, 12, null))
        assertEquals(state, updateCaptionWindowLanguage(state, 99, "ja"))
    }
}
