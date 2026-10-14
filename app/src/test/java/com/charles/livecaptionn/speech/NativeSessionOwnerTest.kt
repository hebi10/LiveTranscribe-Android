package com.charles.livecaptionn.speech

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class NativeSessionOwnerTest {
    @Test fun closeWaitsForInFlightFeedBeforeReleasingNativeResource() {
        val entered = CountDownLatch(1)
        val continueFeed = CountDownLatch(1)
        val closing = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val resource = FakeResource()
        val owner = NativeSessionOwner(resource)
        val workerFailure = AtomicReference<Throwable?>()
        val feeder = thread {
            try {
                owner.feed(Unit) {
                    entered.countDown()
                    check(continueFeed.await(5, TimeUnit.SECONDS))
                    check(!it.closed)
                }
            } catch (t: Throwable) {
                workerFailure.set(t)
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val closer = thread {
            closing.countDown()
            try { owner.close { it.close() } }
            catch (t: Throwable) { workerFailure.set(t) }
            finally { closeReturned.countDown() }
        }
        assertTrue(closing.await(5, TimeUnit.SECONDS))
        assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS))
        assertFalse(resource.closed)
        continueFeed.countDown()
        feeder.join(5000)
        closer.join(5000)
        assertFalse(feeder.isAlive)
        assertFalse(closer.isAlive)
        assertNull(workerFailure.get())
        assertTrue(resource.closed)
        owner.feed(Unit) { fail("native feed after close") }
        owner.close { fail("native double close") }
    }

    @Test fun finishRunsOnceAndRejectsFurtherFeed() {
        val owner = NativeSessionOwner(FakeResource())
        assertEquals("tail", owner.finish("") { "tail" })
        assertEquals("", owner.finish("") { fail("native second final"); "" })
        owner.feed(Unit) { fail("native feed after final") }
        owner.close { it.close() }
    }

    private class FakeResource : AutoCloseable {
        @Volatile var closed = false
        override fun close() { check(!closed); closed = true }
    }
}
