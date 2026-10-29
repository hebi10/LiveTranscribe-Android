package com.charles.livecaptionn

import com.charles.livecaptionn.service.CaptionTranslationQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptionTranslationQueueTest {
    @Test fun defaultDrainAllowsActiveAndTwoRetainedNativeDeadlines() = runTest {
        val translated = mutableListOf<String>()
        val queue = CaptionTranslationQueue<String>(this) {
            delay(15_000)
            translated += it
        }
        queue.submitFinal("active")
        runCurrent()
        assertEquals(0, queue.pendingFinalCount)
        queue.submitFinal("pending one")
        assertEquals(1, queue.pendingFinalCount)
        queue.submitFinal("pending two")
        assertEquals(2, queue.pendingFinalCount)

        assertTrue(queue.finish())
        assertEquals(0, queue.pendingFinalCount)
        assertEquals(listOf("active", "pending one", "pending two"), translated)
    }

    @Test fun slowWorkerKeepsOnlyLatestTwoPendingFinals() = runTest {
        val translated = mutableListOf<String>()
        val queue = CaptionTranslationQueue<String>(this, nowMs = { testScheduler.currentTime }) {
            delay(100)
            translated += it
        }
        assertTrue(queue.submitFinal("running"))
        runCurrent()
        repeat(8) { assertTrue(queue.submitFinal("pending ${it + 1}")) }

        assertTrue(queue.finish())
        assertEquals(listOf("running", "pending 7", "pending 8"), translated)
    }

    @Test fun overflowReportsEachOldestPendingFinalOutsideQueueLock() = runTest {
        val translated = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        lateinit var queue: CaptionTranslationQueue<String>
        queue = CaptionTranslationQueue(
            this,
            nowMs = { testScheduler.currentTime },
            maxPendingFinals = 2,
            onFinalDropped = {
                val lock = queue.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(queue)
                assertFalse("Eviction callback must not hold the queue lock", Thread.holdsLock(lock))
                dropped += it
            }
        ) {
            delay(100)
            translated += it
        }
        assertTrue(queue.submitFinal("running"))
        runCurrent()
        queue.submitPartial("superseded partial")
        repeat(8) { assertTrue(queue.submitFinal("pending ${it + 1}")) }

        assertEquals((1..6).map { "pending $it" }, dropped)
        assertTrue(queue.finish())
        assertEquals(listOf("running", "pending 7", "pending 8"), translated)
    }

    @Test fun configuredCapacityCanKeepOnePendingFinal() = runTest {
        val translated = mutableListOf<String>()
        val queue = CaptionTranslationQueue<String>(this, maxPendingFinals = 1) {
            delay(100)
            translated += it
        }
        queue.submitFinal("running")
        runCurrent()
        queue.submitFinal("old")
        queue.submitFinal("new")

        assertTrue(queue.finish())
        assertEquals(listOf("running", "new"), translated)
    }

    @Test fun pendingFinalCapacityMustBePositive() = runTest {
        for (capacity in listOf(0, -1)) {
            assertThrows(IllegalArgumentException::class.java) {
                CaptionTranslationQueue<String>(this, maxPendingFinals = capacity) {}
            }
        }
    }

    @Test fun finishRejectsNewWorkWhileDrainingRetainedFinals() = runTest {
        val translated = mutableListOf<String>()
        val queue = CaptionTranslationQueue<String>(this, nowMs = { testScheduler.currentTime }) {
            delay(100)
            translated += it
        }
        queue.submitFinal("running")
        runCurrent()
        queue.submitFinal("retained")
        val finishing = async { queue.finish() }
        runCurrent()

        assertFalse(queue.submitFinal("late final"))
        assertFalse(queue.submitPartial("late partial"))
        assertTrue(finishing.await())
        assertEquals(listOf("running", "retained"), translated)
        assertNoBufferedWork(queue)
    }

    @Test fun cancellingWorkerScopeDoesNotStartPendingTranslations() = runTest {
        val workerJob = Job(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + workerJob)
        val started = mutableListOf<String>()
        val queue = CaptionTranslationQueue<String>(scope, nowMs = { testScheduler.currentTime }) {
            started += it
            if (it == "running") {
                try {
                    delay(10_000)
                } catch (_: CancellationException) {
                    // A translator may swallow cancellation; the queue must still stop.
                }
            }
        }
        queue.submitFinal("running")
        runCurrent()
        queue.submitFinal("pending one")
        queue.submitFinal("pending two")
        queue.submitPartial("pending partial")

        workerJob.cancel()
        runCurrent()

        assertEquals(listOf("running"), started)
        assertNoBufferedWork(queue)
        assertFalse(queue.submitFinal("after cancellation"))
        assertFalse(queue.submitPartial("after cancellation"))
    }

    @Test fun cancellationBeforeWorkerStartsReleasesBufferedWork() = runTest {
        val workerJob = Job(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + workerJob)
        val translated = mutableListOf<String>()
        val queue = CaptionTranslationQueue<String>(scope) { translated += it }
        queue.submitFinal("pending final")
        queue.submitPartial("pending partial")

        workerJob.cancel()
        runCurrent()

        assertTrue(translated.isEmpty())
        assertNoBufferedWork(queue)
        assertFalse(queue.submitFinal("after cancellation"))
    }

    @Test fun cancelImmediatelyReleasesBufferedWorkAndRejectsNewWork() = runTest {
        val started = mutableListOf<String>()
        val queue = CaptionTranslationQueue<String>(this) {
            started += it
            delay(10_000)
        }
        queue.submitFinal("running")
        runCurrent()
        queue.submitFinal("pending final")
        queue.submitPartial("pending partial")

        queue.cancel()
        assertNoBufferedWork(queue)
        runCurrent()

        assertEquals(listOf("running"), started)
        assertFalse(queue.submitFinal("after cancellation"))
        assertFalse(queue.submitPartial("after cancellation"))
    }

    @Test fun cancellingIdleWorkerDoesNotFailItsScope() = runTest {
        val scope = CoroutineScope(coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val queue = CaptionTranslationQueue<String>(scope) {}

        queue.cancel()
        runCurrent()

        assertFalse(queue.submitFinal("after cancellation"))
        assertNoBufferedWork(queue)
    }

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
        runCurrent()
        queue.submitFinal("buffered one")
        queue.submitFinal("buffered two")
        queue.submitPartial("buffered partial")
        assertFalse(queue.finish(timeoutMillis = 500))
        assertTrue(translated.isEmpty())
        assertNoBufferedWork(queue)
        assertFalse(queue.submitFinal("after timeout"))
        assertFalse(queue.submitPartial("after timeout"))
    }

    private fun assertNoBufferedWork(queue: CaptionTranslationQueue<*>) {
        val finals = queue.javaClass.getDeclaredField("finals").apply { isAccessible = true }.get(queue) as Collection<*>
        val partial = queue.javaClass.getDeclaredField("partial").apply { isAccessible = true }.get(queue)
        assertTrue("Stopped queues must release buffered final references", finals.isEmpty())
        assertNull("Stopped queues must release buffered partial references", partial)
    }
}
