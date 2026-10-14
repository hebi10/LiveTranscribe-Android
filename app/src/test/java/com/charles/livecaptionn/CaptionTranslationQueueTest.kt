package com.charles.livecaptionn

import com.charles.livecaptionn.service.CaptionTranslationQueue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptionTranslationQueueTest {
    @Test fun finalsDoNotWaitForContinuousPartials() = runTest {
        val translated = mutableListOf<String>()
        val queue = CaptionTranslationQueue<String>(this, nowMs = { testScheduler.currentTime }) { translated += it }
        queue.submitPartial("first partial")
        runCurrent()
        advanceTimeBy(100)
        queue.submitPartial("updated partial")
        queue.submitFinal("confirmed sentence")
        runCurrent()
        assertEquals(listOf("confirmed sentence"), translated)
        assertTrue(queue.finish())
    }

    @Test fun partialsAreCoalescedOnFixedInterval() = runTest {
        val translated = mutableListOf<String>()
        val queue = CaptionTranslationQueue<String>(this, nowMs = { testScheduler.currentTime }) { translated += it }
        queue.submitPartial("one")
        runCurrent()
        advanceTimeBy(100)
        queue.submitPartial("two")
        runCurrent()
        advanceTimeBy(100)
        queue.submitPartial("three")
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf("three"), translated)
        assertTrue(queue.finish())
    }

    @Test fun finishWaitsForAcceptedFinalsInOrder() = runTest {
        val translated = mutableListOf<String>()
        val queue = CaptionTranslationQueue<String>(this, nowMs = { testScheduler.currentTime }) {
            delay(100)
            translated += it
        }
        queue.submitFinal("one")
        queue.submitFinal("two")
        assertTrue(queue.finish())
        assertEquals(listOf("one", "two"), translated)
        assertFalse(queue.submitFinal("after stop"))
    }

    @Test fun timedOutDrainCancelsWork() = runTest {
        val translated = mutableListOf<String>()
        val queue = CaptionTranslationQueue<String>(this, nowMs = { testScheduler.currentTime }) {
            delay(10_000)
            translated += it
        }
        queue.submitFinal("slow")
        assertFalse(queue.finish(timeoutMillis = 500))
        assertTrue(translated.isEmpty())
        assertFalse(queue.submitFinal("after timeout"))
    }
}
