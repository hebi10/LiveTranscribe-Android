package com.charles.livecaptionn.service

import com.charles.livecaptionn.settings.CorrectionHighlightPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionCorrectionTest {
    @Test fun onlyRevisedWordsAreMarked() {
        val old = "오늘 회의는 오후 세 시에 시작될 것입니다."
        val revised = "오늘 회의는 오후 3시에 시작합니다."
        val ranges = correctionChangedRanges(old, revised)
        val marked = ranges.map { revised.substring(it.start, it.endExclusive) }
        assertEquals(listOf("3시에", "시작합니다."), marked)
        assertFalse(marked.any { it.contains("회의는") || it == "오후" })
    }

    @Test fun noChangesAndEmptyAreNeverHighlighted() {
        assertTrue(correctionChangedRanges("같은 문장", "같은 문장").isEmpty())
        assertTrue(correctionChangedRanges("단어", " ").isEmpty())
        assertFalse(acceptableCaptionCorrection("안녕하세요.", "안녕하세요."))
        assertFalse(acceptableCaptionCorrection("안녕하세요.", "번역: 안녕하세요."))
    }

    @Test fun finalSameSessionIsRevisedAndExpiredWithoutResettingText() {
        val original = "회의가 세 시에 시작됩니다."
        val corrected = "회의는 3시에 시작합니다."
        val line = CaptionRuntimeLine(id = 19L, originalText = "Meeting starts at three.",
            translatedText = original, isFinal = true, finalTranslationCommitted = true)
        val state = CaptionRuntimeState(running = true, sessionId = 5L,
            transcriptLines = listOf(line), translatedText = original)
        val applied = applyCaptionCorrection(state, 5L, 19L, original, corrected, 12345L, true)
        assertEquals(corrected, applied.transcriptLines.single().translatedText)
        assertEquals(corrected, applied.translatedText)
        assertTrue(applied.transcriptLines.single().correctionRanges.isNotEmpty())
        assertEquals(12345L, applied.transcriptLines.single().correctionHighlightUntilMs)
        assertEquals(state, applyCaptionCorrection(state, 4L, 19L, original, corrected, 12345L, true))
        assertEquals(state, applyCaptionCorrection(state, 5L, 20L, original, corrected, 12345L, true))
        assertEquals(applied, expireCaptionCorrection(applied, 5L, 19L, 11111L))
        val expired = expireCaptionCorrection(applied, 5L, 19L, 12345L)
        assertTrue(expired.transcriptLines.single().correctionRanges.isEmpty())
        assertEquals(corrected, expired.transcriptLines.single().translatedText)
        assertEquals(corrected, expired.translatedText)
        assertEquals(expired, applyCaptionCorrection(expired, 5L, 19L, original, "다른 문장", 2L, true))
    }

    @Test fun cancelledOrUncommittedCaptionsCannotBeRefined() {
        val line = CaptionRuntimeLine(id = 2L, originalText = "test", translatedText = "이것은 테스트입니다.",
            isFinal = false, finalTranslationCommitted = false)
        val running = CaptionRuntimeState(running = true, sessionId = 7L, transcriptLines = listOf(line))
        assertEquals(running, applyCaptionCorrection(running, 7L, 2L, line.translatedText,
            "테스트입니다.", 1000L, true))
        val committed = running.copy(transcriptLines = listOf(line.copy(isFinal = true, finalTranslationCommitted = true)))
        assertEquals(committed, applyCaptionCorrection(committed.copy(stopping = true), 7L, 2L,
            line.translatedText, "테스트입니다.", 1000L, true).copy(stopping = false))
    }

    @Test fun colorAndDurationSettingsAreSanitized() {
        assertEquals("#30C46C", CorrectionHighlightPalette.normalizeColor("not-a-color"))
        assertEquals("#3B82F6", CorrectionHighlightPalette.normalizeColor("#3b82f6"))
        assertEquals(1000, CorrectionHighlightPalette.normalizeDuration(10_000))
        assertEquals(1500, CorrectionHighlightPalette.normalizeDuration(1500))
    }
}
