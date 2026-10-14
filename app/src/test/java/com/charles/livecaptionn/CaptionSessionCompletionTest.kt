package com.charles.livecaptionn

import com.charles.livecaptionn.service.CaptionTranslationQueue
import com.charles.livecaptionn.service.completedCaptionSession
import com.charles.livecaptionn.speech.RecognitionStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptionSessionCompletionTest {
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
