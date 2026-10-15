package com.charles.livecaptionn.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryRecordingGateTest {
    @Test fun savedConsentIsLoadedBeforeAcceptingRecordingRequests() = runTest {
        var persisted = true
        val gate = HistoryRecordingGate({ persisted }, { persisted = it })
        assertNull(gate.captureConsent())
        gate.initialize()
        var records = 0
        assertTrue(gate.recordIfAllowed(gate.captureConsent()) { records++ })
        assertEquals(1, records)
    }

    @Test fun initiallyDisabledRequestCannotBeSavedAfterUserEnablesHistory() = runTest {
        var persisted = false
        val gate = HistoryRecordingGate({ persisted }, { persisted = it })
        gate.initialize()
        val beforeOptIn = gate.captureConsent()
        gate.updateEnabled(true)
        var records = 0
        assertFalse(gate.recordIfAllowed(beforeOptIn) { records++ })
        assertTrue(gate.recordIfAllowed(gate.captureConsent()) { records++ })
        assertEquals(1, records)
    }

    @Test fun disablingDuringTranslationPreventsItsLaterCommitAndPreservesExistingHistory() = runTest {
        var persisted = true
        val history = mutableListOf("existing")
        val gate = HistoryRecordingGate({ persisted }, { persisted = it })
        gate.initialize()
        val consent = gate.captureConsent()
        val translated = CompletableDeferred<Unit>()
        val pending = async {
            translated.await()
            gate.recordIfAllowed(consent) { history += "late translation" }
        }
        runCurrent()

        gate.updateEnabled(false)
        translated.complete(Unit)

        assertFalse(pending.await())
        assertFalse(persisted)
        assertEquals(listOf("existing"), history)
    }

    @Test fun offThenOnDoesNotRestoreConsentForQueuedOrTranslatingCaptions() = runTest {
        var persisted = true
        val gate = HistoryRecordingGate({ persisted }, { persisted = it })
        gate.initialize()
        val oldConsent = gate.captureConsent()
        gate.updateEnabled(false)
        val consentWhileOff = gate.captureConsent()
        gate.updateEnabled(true)
        var records = 0

        assertFalse(gate.recordIfAllowed(oldConsent) { records++ })
        assertFalse(gate.recordIfAllowed(consentWhileOff) { records++ })
        assertTrue(gate.recordIfAllowed(gate.captureConsent()) { records++ })
        assertEquals(1, records)
    }

    @Test fun disablingIsSerializedWithAlreadyStartedStorageCommit() = runTest {
        var persisted = true
        val events = mutableListOf<String>()
        val gate = HistoryRecordingGate({ persisted }, {
            persisted = it
            events += "settings $it"
        })
        gate.initialize()
        val consent = gate.captureConsent()
        val releaseStorage = CompletableDeferred<Unit>()
        val commit = async {
            gate.recordIfAllowed(consent) {
                events += "commit started"
                releaseStorage.await()
                events += "commit finished"
            }
        }
        runCurrent()
        val disable = launch { gate.updateEnabled(false) }
        runCurrent()
        assertFalse(disable.isCompleted)
        releaseStorage.complete(Unit)
        assertTrue(commit.await())
        disable.join()

        assertEquals(listOf("commit started", "commit finished", "settings false"), events)
        assertFalse(gate.recordIfAllowed(consent) { error("write after disable") })
    }

    @Test fun pendingSettingWriteDoesNotIssueNewConsent() = runTest {
        var persisted = true
        val releaseSettings = CompletableDeferred<Unit>()
        val gate = HistoryRecordingGate({ persisted }, {
            releaseSettings.await()
            persisted = it
        })
        gate.initialize()
        val disable = launch { gate.updateEnabled(false) }
        runCurrent()
        assertNull(gate.captureConsent())
        releaseSettings.complete(Unit)
        disable.join()
    }

    @Test fun disableRequestRejectsCommitAlreadyWaitingBehindAStorageWrite() = runTest {
        var persisted = true
        val gate = HistoryRecordingGate({ persisted }, { persisted = it })
        gate.initialize()
        val consent = gate.captureConsent()
        val releaseStorage = CompletableDeferred<Unit>()
        val committing = async { gate.recordIfAllowed(consent) { releaseStorage.await() } }
        runCurrent()
        val waiting = async { gate.recordIfAllowed(consent) { error("queued write after revoke") } }
        runCurrent()
        val disable = launch { gate.updateEnabled(false) }
        runCurrent()

        assertNull(gate.captureConsent())
        releaseStorage.complete(Unit)
        assertTrue(committing.await())
        assertFalse(waiting.await())
        disable.join()
    }

    @Test fun finishingInitialSettingsReadCannotRestoreRevokedConsent() = runTest {
        val releaseRead = CompletableDeferred<Unit>()
        val gate = HistoryRecordingGate({ releaseRead.await(); true }, {})
        var consentAtInitializationCompletion: HistoryRecordingGate.Consent? = null
        val initialization = launch { gate.initialize() }
        initialization.invokeOnCompletion { consentAtInitializationCompletion = gate.captureConsent() }
        runCurrent()
        val disable = launch { gate.updateEnabled(false) }
        runCurrent()
        releaseRead.complete(Unit)
        initialization.join()
        disable.join()

        assertNull(consentAtInitializationCompletion)
        assertNull(gate.captureConsent())
    }
}
