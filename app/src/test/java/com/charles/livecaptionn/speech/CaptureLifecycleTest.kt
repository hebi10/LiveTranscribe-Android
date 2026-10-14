package com.charles.livecaptionn.speech

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureLifecycleTest {
    @Test fun stopDuringStartupWaitsForLateResourceCleanup() = runTest {
        val loading = CompletableDeferred<Unit>()
        val resource = FakeResource()
        val lifecycle = CaptureLifecycle(this)
        var captureStarted = false
        lifecycle.start { owner ->
            try {
                // Native model constructors cannot be interrupted by coroutine cancellation.
                withContext(NonCancellable) {
                    loading.await()
                    owner.whenActive { captureStarted = true }
                }
            } finally {
                resource.close()
            }
        }
        runCurrent()
        val stopping = launch { lifecycle.stop() }
        runCurrent()
        assertFalse(stopping.isCompleted)
        assertFalse(lifecycle.start { error("restart during shutdown") })
        loading.complete(Unit)
        stopping.join()
        assertFalse(captureStarted)
        assertTrue(resource.closed)
    }

    @Test fun stopInterruptsReadThenWaitsForTailAndRelease() = runTest {
        val order = mutableListOf<String>()
        val lifecycle = CaptureLifecycle(this)
        lifecycle.start { owner ->
            owner.setInterrupt { order += "interrupt" }
            try {
                awaitCancellation()
            } finally {
                order += "tail final"
                order += "release"
            }
        }
        runCurrent()
        lifecycle.stop()
        order += "stop returned"
        assertEquals(listOf("interrupt", "tail final", "release", "stop returned"), order)
        lifecycle.stop()
        assertEquals(4, order.size)
    }

    @Test fun failedStartupReleasesResourceAndAllowsRestart() = runTest {
        val resource = FakeResource()
        val lifecycle = CaptureLifecycle(this)
        lifecycle.start {
            try { throw IllegalStateException("recorder initialization failed") }
            catch (_: IllegalStateException) { /* Engine reports its terminal error. */ }
            finally { resource.close() }
        }
        runCurrent()
        assertTrue(resource.closed)
        assertTrue(lifecycle.start { awaitCancellation() })
        runCurrent()
        lifecycle.stop()
    }

    private class FakeResource : AutoCloseable {
        var closed = false
        override fun close() { check(!closed); closed = true }
    }
}
