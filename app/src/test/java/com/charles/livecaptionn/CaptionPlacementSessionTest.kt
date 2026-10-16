package com.charles.livecaptionn

import com.charles.livecaptionn.service.*
import com.charles.livecaptionn.settings.CaptionPlacement
import com.charles.livecaptionn.speech.RecognitionStatus
import org.junit.Assert.*
import org.junit.Test

class CaptionPlacementSessionTest {
    private val lines = (1L..45L).map {
        CaptionRuntimeLine(it, "source $it", "translation $it", isFinal = true)
    }

    @Test fun completionKeepsOnlyTheLastFortyFinalCommittedPairsForSplit() {
        val pending = CaptionRuntimeLine(46, "pending", "preview", isFinal = true, finalTranslationCommitted = false)
        val partial = CaptionRuntimeLine(47, "partial", "partial", isFinal = false)
        val completed = completedCaptionSession(null, null, true,
            sessionPlacement = CaptionPlacement.SPLIT_SCREEN, retainedLines = lines + pending + partial)
        assertFalse(completed.running)
        assertFalse(completed.stopping)
        assertEquals(CaptionPlacement.SPLIT_SCREEN, completed.sessionPlacement)
        assertEquals((6L..45L).toList(), completed.transcriptLines.map { it.id })
        assertEquals("source 45", completed.originalText)
        assertEquals("translation 45", completed.translatedText)
        assertEquals(RecognitionStatus.IDLE, completed.status)
    }

    @Test fun failedSplitCompletionKeepsReadablePairsAndTheFailure() {
        val completed = completedCaptionSession(null, "capture ended", false,
            sessionPlacement = CaptionPlacement.SPLIT_SCREEN, retainedLines = lines)
        assertEquals(40, completed.transcriptLines.size)
        assertEquals("capture ended", completed.lastError)
        assertEquals(RecognitionStatus.ERROR, completed.status)
    }

    @Test fun popupCompletionNeverRetainsCaptionContent() {
        val completed = completedCaptionSession(null, null, true,
            sessionPlacement = CaptionPlacement.POPUP, retainedLines = lines)
        assertTrue(completed.transcriptLines.isEmpty())
        assertEquals("", completed.originalText)
        assertEquals("", completed.translatedText)
    }

    @Test fun permissionPreparationKeepsRetainedContentUntilServiceActuallyStarts() {
        val completed = completedCaptionSession(null, null, true,
            sessionPlacement = CaptionPlacement.SPLIT_SCREEN, retainedLines = lines)
        val prepared = preparedCaptionStart(completed)
        assertEquals(completed.transcriptLines, prepared.transcriptLines)
        assertEquals(CaptionPlacement.SPLIT_SCREEN, prepared.sessionPlacement)
    }

    @Test fun sessionGenerationChangesOnlyWhenAnActualNewServiceSessionStarts() {
        val first = startedCaptionSession()
        assertEquals(1L, first.sessionId)
        val prepared = preparedCaptionStart(first.copy(running = false))
        assertEquals(1L, prepared.sessionId)
        val stopping = stoppingCaptionSession(first)
        assertEquals(1L, stopping.sessionId)
        for (failure in listOf(null, "capture failed")) {
            val completed = completedCaptionSession(null, failure, true,
                sessionPlacement = CaptionPlacement.SPLIT_SCREEN, retainedLines = lines,
                sessionId = stopping.sessionId)
            assertEquals(1L, completed.sessionId)
            assertEquals(2L, startedCaptionSession(completed).sessionId)
        }
    }

    @Test fun actualNewSessionClearsRetainedCaptionContentAndOldPlacement() {
        val completed = completedCaptionSession(null, null, true,
            sessionPlacement = CaptionPlacement.SPLIT_SCREEN, retainedLines = lines)
        val store = CaptionRuntimeStore().apply { update { completed } }
        store.update { startedCaptionSession(it) }
        val prepared = store.state.value
        assertTrue(prepared.running)
        assertTrue(prepared.transcriptLines.isEmpty())
        assertEquals("", prepared.originalText)
        assertEquals("", prepared.translatedText)
        assertNull(prepared.sessionPlacement)
    }
}
