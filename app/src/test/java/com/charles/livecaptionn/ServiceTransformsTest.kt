package com.charles.livecaptionn

import com.charles.livecaptionn.service.applyTranscriptResult
import com.charles.livecaptionn.speech.RecognitionStatus
import com.charles.livecaptionn.service.recordDroppedTranslation
import com.charles.livecaptionn.service.withRuntimeError
import com.charles.livecaptionn.service.buildOverlayUiState
import com.charles.livecaptionn.settings.CaptionSettings
import com.charles.livecaptionn.service.CaptionRuntimeLine
import com.charles.livecaptionn.service.CaptionRuntimeState
import com.charles.livecaptionn.service.MAX_TRANSCRIPT_LINES
import com.charles.livecaptionn.service.MAX_PENDING_TRANSCRIPT_LINES
import com.charles.livecaptionn.service.applyTranscriptTranslation
import com.charles.livecaptionn.service.shouldTranslateCaption
import com.charles.livecaptionn.service.overlayTranscriptText
import com.charles.livecaptionn.service.recordTranscriptResult
import com.charles.livecaptionn.service.updateTranscriptTranslation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceTransformsTest {

    // ── recordTranscriptResult ──

    @Test
    fun recordTranscriptResult_blankText_returnsInput() {
        val lines = listOf(CaptionRuntimeLine(id = 1L, originalText = "hello"))
        assertEquals(lines, recordTranscriptResult(lines, "", isFinal = false, newLineId = 2L))
    }

    @Test
    fun recordTranscriptResult_duplicate_skips() {
        val lines = listOf(CaptionRuntimeLine(id = 1L, originalText = "hello"))
        assertEquals(lines, recordTranscriptResult(lines, "hello", isFinal = false, newLineId = 2L))
    }

    @Test
    fun recordTranscriptResult_replacesOpenLine() {
        val lines = listOf(CaptionRuntimeLine(id = 1L, originalText = "hello"))
        val result = recordTranscriptResult(lines, "world", isFinal = false, newLineId = 2L)
        assertEquals(1, result.size)
        assertEquals("world", result.first().originalText)
        assertEquals(2L, result.first().id)
    }

    @Test
    fun recordTranscriptResult_translatedButNotFinal_stillReplaces() {
        // Regression test for GitHub issue #1: a translation landing mid-sentence
        // (e.g. during a natural speech pause that fires the translate debounce
        // but not Vosk's finalizer) must not cause the next partial to be
        // appended as a duplicate line — it must still replace in place.
        val lines = listOf(
            CaptionRuntimeLine(id = 1L, originalText = "hello", translatedText = "xin chao", isFinal = false)
        )
        val result = recordTranscriptResult(lines, "hello world", isFinal = false, newLineId = 2L)
        assertEquals(1, result.size)
        assertEquals("hello world", result.first().originalText)
        assertEquals("", result.first().translatedText)
    }

    @Test
    fun recordTranscriptResult_finalClosesLine_thenNextPartialAppends() {
        val open = listOf(CaptionRuntimeLine(id = 1L, originalText = "hello"))
        val closed = recordTranscriptResult(open, "hello world", isFinal = true, newLineId = 2L)
        assertEquals(1, closed.size)
        assertEquals(true, closed.first().isFinal)

        val next = recordTranscriptResult(closed, "next", isFinal = false, newLineId = 3L)
        assertEquals(2, next.size)
        assertEquals("next", next[1].originalText)
    }

    @Test
    fun recordTranscriptResult_sameTextFinalTransition_waitsForFinalTranslation() {
        val lines = listOf(
            CaptionRuntimeLine(id = 1L, originalText = "hello", translatedText = "xin chao", isFinal = false)
        )
        val result = recordTranscriptResult(lines, "hello", isFinal = true, newLineId = 2L)
        assertEquals(1, result.size)
        assertEquals(1L, result.first().id)
        assertEquals("", result.first().translatedText)
        assertEquals(true, result.first().isFinal)
        assertFalse(result.first().finalTranslationCommitted)
    }

    @Test
    fun recordTranscriptResult_appendsWhenLastLineFinal() {
        val lines = listOf(CaptionRuntimeLine(id = 1L, originalText = "hello", isFinal = true))
        val result = recordTranscriptResult(lines, "world", isFinal = false, newLineId = 2L)
        assertEquals(2, result.size)
        assertEquals("world", result[1].originalText)
    }

    @Test
    fun recordTranscriptResult_boundsPendingFinals() {
        val lines = (1..50).map { CaptionRuntimeLine(id = it.toLong(), originalText = "line $it", isFinal = true) }
        val result = recordTranscriptResult(lines, "line 51", isFinal = true, newLineId = 51L)
        assertEquals(MAX_PENDING_TRANSCRIPT_LINES, result.size)
        assertEquals(40, result.size)
        assertEquals("line 12", result.first().originalText)
        assertEquals("line 51", result.last().originalText)
    }

    // ── updateTranscriptTranslation ──

    @Test
    fun updateTranscriptTranslation_matchesById() {
        val lines = listOf(
            CaptionRuntimeLine(id = 1L, originalText = "hello", isFinal = true),
            CaptionRuntimeLine(id = 2L, originalText = "world")
        )
        val result = updateTranscriptTranslation(lines, lineId = 1L, translatedText = "xin chao", isFinalTranslation = true)
        assertEquals("xin chao", result[0].translatedText)
        assertEquals("", result[1].translatedText)
    }

    @Test
    fun updateTranscriptTranslation_idNotFound_returnsUnchanged() {
        val lines = listOf(CaptionRuntimeLine(id = 1L, originalText = "hello"))
        val result = updateTranscriptTranslation(lines, lineId = 99L, translatedText = "translated", isFinalTranslation = false)
        assertEquals(lines, result)
    }

    @Test
    fun updateTranscriptTranslation_staleIdAfterTextEvolved_isNoOp() {
        val original = listOf(CaptionRuntimeLine(id = 1L, originalText = "hel"))
        val evolved = recordTranscriptResult(original, "hello world", isFinal = false, newLineId = 2L)
        // Translation for the stale id (1L) arrives after the line evolved to id 2L.
        val result = updateTranscriptTranslation(evolved, lineId = 1L, translatedText = "stale", isFinalTranslation = false)
        assertEquals(evolved, result)
    }

    @Test
    fun updateTranscriptTranslation_distinctIdsDisambiguateSameText() {
        val lines = listOf(
            CaptionRuntimeLine(id = 1L, originalText = "hello", isFinal = true),
            CaptionRuntimeLine(id = 2L, originalText = "world", isFinal = true),
            CaptionRuntimeLine(id = 3L, originalText = "hello", isFinal = true)
        )
        val result = updateTranscriptTranslation(lines, lineId = 3L, translatedText = "translated", isFinalTranslation = true)
        assertEquals("", result[0].translatedText) // first "hello" unchanged
        assertEquals("translated", result[2].translatedText) // matched by id, not text
    }

    // ── overlayTranscriptText ──

    @Test
    fun overlayTranscriptText_empty() {
        val state = CaptionRuntimeState()
        assertEquals("", overlayTranscriptText(state))
    }

    @Test
    fun overlayTranscriptText_orphanTranslation_isNeverRendered() {
        val state = CaptionRuntimeState(translatedText = "xin chao")
        assertEquals("", overlayTranscriptText(state))
        assertEquals("", overlayTranscriptText(state, stableCaptions = false))
    }

    @Test
    fun overlayTranscriptText_showsHistory() {
        val state = CaptionRuntimeState(
            transcriptLines = listOf(
                CaptionRuntimeLine(id = 1L, originalText = "hello", translatedText = "xin chao", isFinal = true),
                CaptionRuntimeLine(id = 2L, originalText = "world", translatedText = "the gioi", isFinal = true)
            ),
            translatedText = "the gioi"
        )
        val result = overlayTranscriptText(state)
        assertEquals("xin chao\nthe gioi", result)
    }

    @Test
    fun overlayTranscriptText_ignoresLegacyCurrentTranslationInsteadOfAppending() {
        val state = CaptionRuntimeState(
            transcriptLines = listOf(
                CaptionRuntimeLine(id = 1L, originalText = "hello", translatedText = "xin chao", isFinal = true)
            ),
            translatedText = "xin chao"
        )
        assertEquals("xin chao", overlayTranscriptText(state))
    }

    @Test
    fun overlayTranscriptText_skipsUntranslatedLines() {
        val state = CaptionRuntimeState(
            transcriptLines = listOf(
                CaptionRuntimeLine(id = 1L, originalText = "hello"),
                CaptionRuntimeLine(id = 2L, originalText = "world", translatedText = "the gioi", isFinal = true)
            )
        )
        assertEquals("the gioi", overlayTranscriptText(state))
    }

    @Test
    fun recordTranscriptResult_openSegmentPreservesFortyFinals() {
        val finals = (1..40).map { CaptionRuntimeLine(it.toLong(), "source $it", "translation $it", isFinal = true) }
        val result = recordTranscriptResult(finals, "new speech", isFinal = false, newLineId = 41L)
        assertEquals(41, result.size)
        assertEquals(finals, result.dropLast(1))
        assertFalse(result.last().isFinal)
    }

    @Test
    fun recordTranscriptResult_repeatedLegitimateUtterancesRemainDistinct() {
        val first = listOf(CaptionRuntimeLine(1L, "hello", "안녕", isFinal = true))
        val next = recordTranscriptResult(first, "hello", isFinal = true, newLineId = 2L)
        assertEquals(listOf(1L, 2L), next.map { it.id })
        assertEquals(listOf("hello", "hello"), next.map { it.originalText })
    }

    @Test
    fun updateTranscriptTranslation_latePartialCannotFillFinalizedSameId() {
        val preview = listOf(CaptionRuntimeLine(1L, "hello", "미리 보기"))
        val finalized = recordTranscriptResult(preview, "hello", isFinal = true, newLineId = 2L)
        val late = updateTranscriptTranslation(finalized, 1L, "늦은 미리 보기", isFinalTranslation = false)
        assertEquals(finalized, late)
        assertEquals("", late.single().translatedText)
        val committed = updateTranscriptTranslation(late, 1L, "안녕", isFinalTranslation = true)
        assertEquals("안녕", committed.single().translatedText)
        assertTrue(committed.single().finalTranslationCommitted)
    }

    @Test
    fun updateTranscriptTranslation_committedFinalNeverChanges() {
        val lines = listOf(CaptionRuntimeLine(1L, "hello", "안녕", isFinal = true))
        assertEquals(lines, updateTranscriptTranslation(lines, 1L, "늦은 부분", isFinalTranslation = false))
        assertEquals(lines, updateTranscriptTranslation(lines, 1L, "다른 결과", isFinalTranslation = true))
    }

    @Test
    fun applyTranscriptTranslation_outOfOrderFinalKeepsNewestCurrentTranslation() {
        val runtime = CaptionRuntimeState(transcriptLines = listOf(
            CaptionRuntimeLine(1L, "first", isFinal = true),
            CaptionRuntimeLine(2L, "second", isFinal = true)
        ))
        val newerFirst = applyTranscriptTranslation(runtime, 2L, "둘째", isFinalTranslation = true)
        val olderLater = applyTranscriptTranslation(newerFirst, 1L, "첫째", isFinalTranslation = true)
        assertEquals("둘째", olderLater.translatedText)
        assertEquals(listOf("첫째", "둘째"), olderLater.transcriptLines.map { it.translatedText })
        assertEquals("첫째\n둘째", overlayTranscriptText(olderLater))
    }

    @Test
    fun applyTranscriptTranslation_rejectedStaleCompletionDoesNotChangeRuntime() {
        val runtime = CaptionRuntimeState(
            translatedText = "안녕",
            transcriptLines = listOf(CaptionRuntimeLine(2L, "hello", "안녕", isFinal = true))
        )
        assertEquals(runtime, applyTranscriptTranslation(runtime, 1L, "evicted", isFinalTranslation = true))
        assertEquals(runtime, applyTranscriptTranslation(runtime, 2L, "late partial", isFinalTranslation = false))
        assertEquals(runtime, applyTranscriptTranslation(runtime, 2L, "replacement final", isFinalTranslation = true))
    }

    @Test
    fun shouldTranslateCaption_stabilitySkipsOnlyPartials() {
        assertFalse(shouldTranslateCaption(isFinal = false, stableCaptions = true))
        assertTrue(shouldTranslateCaption(isFinal = true, stableCaptions = true))
        assertTrue(shouldTranslateCaption(isFinal = false, stableCaptions = false))
        assertTrue(shouldTranslateCaption(isFinal = true, stableCaptions = false))
    }

    @Test
    fun recordTranscriptResult_translationBacklogPreservesStableCaptionsAndHasHardBound() {
        val committed = (1..40).map { CaptionRuntimeLine(it.toLong(), "stable $it", "확정 $it", isFinal = true) }
        var lines = committed
        for (id in 41L..100L) lines = recordTranscriptResult(lines, "pending $id", true, id)
        lines = recordTranscriptResult(lines, "open partial", false, 101L)
        assertEquals(MAX_TRANSCRIPT_LINES, lines.size)
        assertEquals(81, lines.size)
        assertEquals(committed, lines.take(40))
        assertEquals((61L..101L).toList(), lines.drop(40).map { it.id })
        assertEquals("확정 39\n확정 40", overlayTranscriptText(CaptionRuntimeState(transcriptLines = lines)))
        assertEquals(lines, updateTranscriptTranslation(lines, 41L, "evicted pending final", true))
    }

    @Test
    fun updateTranscriptTranslation_newCommitTrimsOldestCommittedAndKeepsPendingOrder() {
        val committed = (1..40).map { CaptionRuntimeLine(it.toLong(), "stable $it", "확정 $it", isFinal = true) }
        val pending = (41..80).map { CaptionRuntimeLine(it.toLong(), "pending $it", isFinal = true) }
        val updated = updateTranscriptTranslation(committed + pending, 41L, "새 확정", true)
        assertEquals((2L..80L).toList(), updated.map { it.id })
        assertEquals(40, updated.count { it.finalTranslationCommitted })
        assertEquals("새 확정", updated.first { it.id == 41L }.translatedText)
        assertEquals(updated, updateTranscriptTranslation(updated, 1L, "evicted committed final", true))
    }

    @Test
    fun emptyFinalBoundary_discardsOnlyUncommittedOpenPreview() {
        val committed = CaptionRuntimeLine(1L, "hello", "안녕", isFinal = true)
        val waiting = CaptionRuntimeLine(2L, "waiting", isFinal = true)
        val open = CaptionRuntimeLine(3L, "ghost partial", "임시 번역")
        val result = recordTranscriptResult(listOf(committed, waiting, open), "", true, 4L)
        assertEquals(listOf(committed, waiting), result)
        assertEquals(listOf(committed, waiting, open),
            recordTranscriptResult(listOf(committed, waiting, open), "", false, 4L))
    }

    @Test
    fun droppedPendingTranslation_keepsCommittedCaptionsAndRejectsLateCompletion() {
        val committed = CaptionRuntimeLine(1L, "hello", "안녕", isFinal = true)
        val waiting = CaptionRuntimeLine(2L, "old pending", isFinal = true)
        val latest = CaptionRuntimeLine(3L, "new pending", isFinal = true)
        val runtime = CaptionRuntimeState(transcriptLines = listOf(committed, waiting, latest))
        val result = recordDroppedTranslation(runtime, 2L)
        assertEquals(listOf(committed, latest), result.transcriptLines)
        assertEquals(1, result.skippedTranslations)
        assertTrue(result.lastError?.isNotBlank() == true)
        assertEquals(result, applyTranscriptTranslation(result, 2L, "late stale result", true))
        assertEquals(result, recordDroppedTranslation(result, 1L))
    }

    @Test
    fun successfulCaptureUpdate_preservesVisibleBacklogNotice() {
        val waiting = CaptionRuntimeLine(1L, "old pending", isFinal = true)
        val runtime = recordDroppedTranslation(CaptionRuntimeState(transcriptLines = listOf(waiting)), 1L)
        val failed = withRuntimeError(runtime, "capture failure")
        assertEquals("capture failure", failed.lastError)
        val recovered = withRuntimeError(failed, null)
        assertTrue(recovered.lastError?.isNotBlank() == true)
        assertEquals(1, recovered.skippedTranslations)
        assertEquals(recovered.lastError, buildOverlayUiState(recovered, CaptionSettings()).statusDetail)
    }

    @Test
    fun finalCleanup_doesNotEraseCaptureFailure() {
        val committed = CaptionRuntimeLine(1L, "hello", "안녕", isFinal = true)
        val preview = CaptionRuntimeLine(2L, "ghost partial", "임시 번역")
        val failure = CaptionRuntimeState(
            transcriptLines = listOf(committed, preview),
            status = RecognitionStatus.ERROR,
            lastError = "specific capture failure"
        )
        val emptyTail = applyTranscriptResult(failure, "", true, 3L)
        assertEquals(listOf(committed), emptyTail.transcriptLines)
        assertEquals(RecognitionStatus.ERROR, emptyTail.status)
        assertEquals("specific capture failure", emptyTail.lastError)
        val recognizedTail = applyTranscriptResult(failure, "recognized tail", true, 3L)
        assertEquals(RecognitionStatus.ERROR, recognizedTail.status)
        assertEquals("specific capture failure", recognizedTail.lastError)
        assertEquals("recognized tail", recognizedTail.transcriptLines.last().originalText)
    }

    @Test
    fun terminalTail_keepsFailureBeforeMainObservesErrorStatus() {
        val failure = CaptionRuntimeState(status = RecognitionStatus.LISTENING, lastError = "specific capture failure")
        val recorded = applyTranscriptResult(failure, "recognized tail", true, 1L)
        assertEquals("specific capture failure", recorded.lastError)
        assertEquals("recognized tail", recorded.transcriptLines.single().originalText)
    }

    @Test
    fun backlogEviction_doesNotOverwriteCaptureFailure() {
        val runtime = CaptionRuntimeState(
            transcriptLines = listOf(CaptionRuntimeLine(1L, "old pending", isFinal = true)),
            lastError = "specific capture failure"
        )
        val recorded = recordDroppedTranslation(runtime, 1L)
        assertEquals(1, recorded.skippedTranslations)
        assertEquals("specific capture failure", recorded.lastError)
    }
}
