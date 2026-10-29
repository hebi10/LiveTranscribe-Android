package com.charles.livecaptionn

import com.charles.livecaptionn.service.CaptionTranslationQueue
import com.charles.livecaptionn.service.CaptionRuntimeLine
import com.charles.livecaptionn.service.CaptionRuntimeState
import com.charles.livecaptionn.service.completedCaptionSession
import com.charles.livecaptionn.service.failedCaptionStartup
import com.charles.livecaptionn.service.interruptedCaptionSession
import com.charles.livecaptionn.service.recordRejectedTranslation
import com.charles.livecaptionn.settings.CaptionPlacement
import com.charles.livecaptionn.speech.RecognitionStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptionSessionCompletionTest {
    @Test fun forcedDestructionCannotReportSuccessfulCompletionOfCancelledTranslations() {
        val running = CaptionRuntimeState(running = true, sessionId = 7L)
        val interrupted = interruptedCaptionSession(running, 7L)
        assertTrue(interrupted.stopping)
        assertFalse(interrupted.lastError.isNullOrBlank())
        // A cancelled queue can join successfully; that must not erase the interruption.
        val completed = completedCaptionSession(interrupted.lastError, null, drained = true)
        assertEquals(RecognitionStatus.ERROR, completed.status)
        assertEquals(interrupted.lastError, completed.lastError)
        assertFalse(completed.running)
        assertFalse(completed.stopping)
    }

    @Test fun normalCompletedOrUnownedSessionsAreNotMarkedInterrupted() {
        val completed = CaptionRuntimeState(sessionId = 7L)
        assertSame(completed, interruptedCaptionSession(completed, 7L))
        val running = completed.copy(running = true)
        assertSame(running, interruptedCaptionSession(running, null))
        assertSame(running, interruptedCaptionSession(running, 6L))
    }

    @Test fun rejectedFinalIsCountedAndCannotSilentlyBecomeSuccessfulCompletion() {
        val pending = CaptionRuntimeLine(5, "hello", isFinal = true)
        val runtime = CaptionRuntimeState(running = true, stopping = true, transcriptLines = listOf(pending))
        val rejected = recordRejectedTranslation(runtime, 5L)
        assertEquals(1, rejected.skippedTranslations)
        assertTrue(rejected.transcriptLines.isEmpty())
        assertFalse(rejected.lastError.isNullOrBlank())
        assertEquals(RecognitionStatus.ERROR, completedCaptionSession(rejected.lastError, null, true).status)
    }

    @Test fun foregroundFailureBeforeSessionStartIsTerminal() {
        val failed = failedCaptionStartup(CaptionRuntimeState(), 0L, "시작 권한 오류")
        assertFalse(failed.running)
        assertFalse(failed.stopping)
        assertEquals(RecognitionStatus.ERROR, failed.status)
        assertEquals("시작 권한 오류", failed.lastError)
    }

    @Test fun failedStartupKeepsRetainedCaptionsAndTheirSessionIdentity() {
        val line = CaptionRuntimeLine(5, "hello", "안녕하세요", true)
        val retained = completedCaptionSession(null, null, true, sessionPlacement = CaptionPlacement.SPLIT_SCREEN,
            retainedLines = listOf(line), sessionId = 7L)
        val failed = failedCaptionStartup(retained, 7L, "시작 권한 오류")
        assertEquals(retained.transcriptLines, failed.transcriptLines)
        assertEquals("hello", failed.originalText)
        assertEquals("안녕하세요", failed.translatedText)
        assertEquals(CaptionPlacement.SPLIT_SCREEN, failed.sessionPlacement)
        assertEquals(7L, failed.sessionId)
        assertFalse(failed.running)
        assertFalse(failed.stopping)
    }

    @Test fun failedStartupCannotChangeAnUnownedOrNewerSession() {
        val idle = CaptionRuntimeState(sessionId = 8L)
        assertSame(idle, failedCaptionStartup(idle, 7L, "stale attempt"))
        assertSame(idle, failedCaptionStartup(idle, null, "unowned attempt"))
        val running = idle.copy(running = true)
        assertSame(running, failedCaptionStartup(running, 8L, "another active session"))
        val stopping = running.copy(stopping = true)
        assertSame(stopping, failedCaptionStartup(stopping, 8L, "another stopping session"))
    }

    @Test fun failedFinalRetainsErrorAfterDrain() = runTest {
        var failure: String? = null
        val queue = CaptionTranslationQueue<String>(this, nowMs = { testScheduler.currentTime }) {
            try {
                throw IllegalStateException("translator unavailable")
            } catch (_: Exception) {
                failure = "번역 실패"
            }
        }
        queue.submitFinal("confirmed speech")
        val drained = queue.finish()
        val completion = completedCaptionSession(failure, null, drained)
        assertTrue(drained)
        assertFalse(completion.running)
        assertEquals(RecognitionStatus.ERROR, completion.status)
        assertEquals("번역 실패", completion.lastError)
    }

    @Test fun successfulFinalPersistsBeforeSuccessfulCompletion() = runTest {
        val history = mutableListOf<String>()
        val queue = CaptionTranslationQueue<String>(this, nowMs = { testScheduler.currentTime }) {
            delay(100)
            history += "한국어: $it"
        }
        queue.submitFinal("one")
        val drained = queue.finish()
        val completion = completedCaptionSession(null, null, drained)
        assertEquals(listOf("한국어: one"), history)
        assertEquals(RecognitionStatus.IDLE, completion.status)
        assertNull(completion.lastError)
    }

    @Test fun failureBeforeStopIsPreservedAndTerminalFailureTakesPriority() {
        assertEquals("저장 실패", completedCaptionSession("저장 실패", null, true).lastError)
        assertEquals("캡처 종료", completedCaptionSession("저장 실패", "캡처 종료", true).lastError)
    }
}
