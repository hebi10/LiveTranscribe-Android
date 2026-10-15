package com.charles.livecaptionn.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes history commits with consent changes without deleting previously saved history. */
class HistoryRecordingGate(
    private val readEnabled: suspend () -> Boolean,
    private val persistEnabled: suspend (Boolean) -> Unit
) {
    class Consent internal constructor(internal val generation: Long)
    private val mutex = Mutex()
    private val stateLock = Any()
    private var initialized = false
    private var enabled = false
    private var generation = 0L

    /** Called before the service can enqueue its first caption; unknown consent fails closed. */
    suspend fun initialize(): Unit = mutex.withLock {
        val initialGeneration = synchronized(stateLock) {
            if (initialized) return@withLock
            generation
        }
        val savedEnabled = readEnabled()
        synchronized(stateLock) {
            // A toggle can revoke consent while the first DataStore read is suspended.
            if (generation == initialGeneration) {
                enabled = savedEnabled
                initialized = true
            }
        }
    }

    /** A caption is eligible only within the uninterrupted consent interval it was queued in. */
    fun captureConsent(): Consent? = synchronized(stateLock) {
        if (enabled) Consent(generation) else null
    }

    suspend fun updateEnabled(enabled: Boolean) {
        val requestedGeneration = synchronized(stateLock) {
            generation++
            this.enabled = false
            initialized = true
            generation
        }
        mutex.withLock {
            // A later request supersedes any older setting write that has not started yet.
            if (synchronized(stateLock) { generation != requestedGeneration }) return@withLock
            persistEnabled(enabled)
            synchronized(stateLock) {
                if (generation == requestedGeneration) this.enabled = enabled
            }
        }
    }

    suspend fun recordIfAllowed(consent: Consent?, record: suspend () -> Unit): Boolean = mutex.withLock {
        val allowed = synchronized(stateLock) {
            consent != null && enabled && consent.generation == generation
        }
        if (!allowed) return@withLock false
        // Hold the same mutex through the actual storage commit, not just its eligibility check.
        record()
        true
    }
}
