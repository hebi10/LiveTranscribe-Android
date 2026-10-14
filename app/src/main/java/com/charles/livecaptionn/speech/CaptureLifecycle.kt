package com.charles.livecaptionn.speech

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One coroutine owns model startup, capture and cleanup; stop waits for that owner. */
internal class CaptureLifecycle(
    private val scope: CoroutineScope,
    private val context: CoroutineContext = EmptyCoroutineContext
) {
    private val lock = Any()
    private val stopMutex = Mutex()
    private var job: Job? = null
    private var owner: Owner? = null
    private var stopping = false

    val isRunning: Boolean
        get() = synchronized(lock) { !stopping && job?.isCompleted == false }

    fun start(work: suspend (Owner) -> Unit): Boolean = synchronized(lock) {
        if (stopping || job?.isCompleted == false) return false
        val nextOwner = Owner()
        val nextJob = scope.launch(context, start = CoroutineStart.LAZY) { work(nextOwner) }
        owner = nextOwner
        job = nextJob
        nextJob.start()
        true
    }

    suspend fun stop() = withContext(NonCancellable) {
        stopMutex.withLock {
            val current = synchronized(lock) {
                stopping = true
                job to owner
            }
            try {
                try {
                    current.second?.requestStop { current.first?.cancel() }
                } finally {
                    current.first?.join()
                }
            } finally {
                synchronized(lock) {
                    job = null
                    owner = null
                    stopping = false
                }
            }
        }
    }

    internal class Owner {
        private val lock = Any()
        private var stopped = false
        private var interrupt: (() -> Unit)? = null

        /** Publication and startRecording share the stop lock to prohibit a late start. */
        fun whenActive(action: () -> Unit): Boolean = synchronized(lock) {
            if (stopped) false else { action(); true }
        }

        fun setInterrupt(action: () -> Unit): Boolean = synchronized(lock) {
            if (stopped) false else { interrupt = action; true }
        }

        /** Clear before release so a concurrent stop cannot touch a released recorder. */
        fun clearInterrupt() = synchronized(lock) { interrupt = null }

        fun requestStop(cancel: () -> Unit) = synchronized(lock) {
            stopped = true
            cancel()
            interrupt?.invoke()
        }
    }
}
