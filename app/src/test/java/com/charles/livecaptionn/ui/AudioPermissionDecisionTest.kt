package com.charles.livecaptionn.ui

import org.junit.Assert.*
import org.junit.Test

class AudioPermissionDecisionTest {
    @Test fun firstRequestUsesAndroidDialogEvenWithoutRationale() {
        assertEquals(AudioPermissionAction.REQUEST, audioPermissionAction(
            granted = false, completedRequest = false, showRationale = false))
    }

    @Test fun deniedButRequestablePermissionCanBeRequestedAgain() {
        assertEquals(AudioPermissionAction.REQUEST, audioPermissionAction(
            granted = false, completedRequest = true, showRationale = true))
    }

    @Test fun completedRequestWithNoRationaleOffersAppSettingsAfterRecreation() {
        // completedRequest is persisted, so a new Activity makes the same decision.
        repeat(2) {
            assertEquals(AudioPermissionAction.OPEN_SETTINGS, audioPermissionAction(
                granted = false, completedRequest = true, showRationale = false))
        }
    }

    @Test fun grantingInSettingsOnlyUpdatesPermissionAndDoesNotResumeStart() {
        val gate = CaptionStartGate()
        gate.begin()
        gate.cancel() // Opening settings ends the pending start.
        assertEquals(AudioPermissionAction.GRANTED, audioPermissionAction(
            granted = true, completedRequest = true, showRationale = false))
        assertFalse(gate.busy)
        assertFalse(gate.audioPermissionResult(granted = true))
    }
}
