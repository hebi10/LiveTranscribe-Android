package com.charles.livecaptionn.ui

import org.junit.Assert.*
import org.junit.Test

class CaptionStartGateTest {
    @Test fun permissionOnlyGrantNeverStartsCaptioning() {
        val gate = CaptionStartGate()
        assertTrue(gate.requestAudioPermission(forStart = false))
        assertFalse(gate.audioPermissionResult(granted = true))
        assertFalse(gate.busy)
    }

    @Test fun startIsGuardedWhilePermissionOrProjectionIsPending() {
        val gate = CaptionStartGate()
        assertTrue(gate.begin())
        assertFalse(gate.begin())
        assertTrue(gate.requestAudioPermission(forStart = true))
        assertFalse(gate.begin())
        assertTrue(gate.audioPermissionResult(granted = true))
        gate.awaitProjection()
        assertFalse(gate.begin())
        assertTrue(gate.projectionResult(granted = true))
        assertTrue(gate.busy)
    }

    @Test fun deniedOrCanceledRequestsCannotStartLater() {
        val gate = CaptionStartGate()
        gate.begin()
        gate.requestAudioPermission(forStart = true)
        assertFalse(gate.audioPermissionResult(granted = false))
        assertTrue(gate.begin())
        gate.awaitProjection()
        gate.cancel()
        assertFalse(gate.begin()) // Wait for the outstanding dialog result before restarting.
        assertFalse(gate.projectionResult(granted = true))
        assertFalse(gate.busy)
    }

    @Test fun canceledAudioGrantNeverStartsAndGuardsReplacementRequest() {
        val gate = CaptionStartGate()
        gate.begin()
        gate.requestAudioPermission(forStart = true)
        gate.cancel()
        assertFalse(gate.begin())
        assertFalse(gate.audioPermissionResult(granted = true))
        assertTrue(gate.begin())
    }

    @Test fun pendingServiceRemainsBusyUntilRuntimeTakesOwnership() {
        val gate = CaptionStartGate()
        gate.begin()
        gate.serviceRequested()
        assertTrue(gate.servicePending)
        assertFalse(gate.begin())
        gate.cancel()
        assertFalse(gate.busy)
    }

    @Test fun pendingPermissionSurvivesActivityRecreation() {
        val gate = CaptionStartGate()
        gate.begin()
        gate.requestAudioPermission(forStart = true)
        val restored = CaptionStartGate(gate.savedStage)
        assertTrue(restored.busy)
        assertTrue(restored.audioPermissionResult(granted = true))
    }
}
