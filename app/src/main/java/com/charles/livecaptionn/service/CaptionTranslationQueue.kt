package com.charles.livecaptionn.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque

internal class CaptionTranslationQueue<T : Any>(
    scope: CoroutineScope,
    private val partialIntervalMillis: Long = 300,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val translate: suspend (T) -> Unit
) {
    private val lock = Any()
    private val finals = ArrayDeque<T>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private var partial: T? = null
    private var accepting = true
    private val worker: Job

    init {
        require(partialIntervalMillis > 0)
        worker = scope.launch {
            var nextPartialAt = nowMs() + partialIntervalMillis
            while (true) {
                val final = synchronized(lock) { finals.pollFirst() }
                if (final != null) {
                    translate(final)
                    continue
                }
                val active = synchronized(lock) { accepting }
                if (!active) break
                val hasPartial = synchronized(lock) { partial != null }
                val remaining = (nextPartialAt - nowMs()).coerceAtLeast(0)
                if (hasPartial && remaining == 0L) {
                    val snapshot = synchronized(lock) { partial.also { partial = null } }
                    nextPartialAt = nowMs() + partialIntervalMillis
                    if (snapshot != null) translate(snapshot)
                } else if (hasPartial) {
                    withTimeoutOrNull(remaining.coerceAtLeast(1)) { signal.receive() }
                } else {
                    signal.receive()
                    nextPartialAt = nowMs() + partialIntervalMillis
                }
            }
        }
    }

    fun submitPartial(value: T): Boolean = synchronized(lock) {
        if (!accepting) return false
        partial = value
        signal.trySend(Unit)
        true
    }

    fun submitFinal(value: T): Boolean = synchronized(lock) {
        if (!accepting) return false
        finals.addLast(value)
        partial = null
        signal.trySend(Unit)
        true
    }

    suspend fun finish(timeoutMillis: Long = 6_000): Boolean {
        synchronized(lock) { accepting = false; partial = null }
        signal.trySend(Unit)
        val drained = withTimeoutOrNull(timeoutMillis) { worker.join(); true } ?: false
        if (!drained) worker.cancelAndJoin()
        signal.close()
        return drained
    }

    fun cancel() {
        synchronized(lock) { accepting = false; partial = null; finals.clear() }
        worker.cancel()
        signal.close()
    }
}
