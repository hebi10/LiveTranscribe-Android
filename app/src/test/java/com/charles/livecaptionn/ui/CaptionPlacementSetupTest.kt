package com.charles.livecaptionn.ui

import com.charles.livecaptionn.service.CaptionRuntimeState
import com.charles.livecaptionn.settings.AudioSource
import com.charles.livecaptionn.settings.CaptionPlacement
import com.charles.livecaptionn.translation.ModelSetupStage
import com.charles.livecaptionn.translation.TranslationModelState
import org.junit.Assert.*
import org.junit.Test

class CaptionPlacementSetupTest {
    private val ready = MainUiState(micPermissionGranted = true, overlayPermissionGranted = false,
        translationModel = TranslationModelState("auto", ModelSetupStage.READY))

    @Test fun splitNeedsAudioAndModelsButNeverOverlayPermission() {
        for (source in AudioSource.entries) {
            val split = ready.copy(settings = ready.settings.copy(captionPlacement = CaptionPlacement.SPLIT_SCREEN,
                audioSource = source))
            assertTrue(split.canStart)
            assertFalse(split.overlayPermissionRequired)
            assertFalse(split.setupState().overlayPermissionNeeded)
            assertFalse(split.setupState().overlayPermissionRequired)
            assertEquals(2, split.setupState().totalSteps)
            assertEquals(2, split.setupState().completedSteps)
            assertEquals(0, split.setupState().remainingSteps)
            assertFalse(split.copy(micPermissionGranted = false).canStart)
            assertFalse(split.copy(translationModel = TranslationModelState()).canStart)
        }
    }

    @Test fun popupRetainsAllThreeRequirements() {
        assertFalse(ready.canStart)
        assertTrue(ready.overlayPermissionRequired)
        assertTrue(ready.setupState().overlayPermissionRequired)
        assertEquals(3, ready.setupState().totalSteps)
        assertEquals(2, ready.setupState().completedSteps)
        assertEquals(SetupStep.OVERLAY_PERMISSION, ready.setupState().nextStep)
    }

    @Test fun splitChecklistNeverCountsAnUnneededOverlayStepAsCompleted() {
        val state = ready.copy(settings = ready.settings.copy(captionPlacement = CaptionPlacement.SPLIT_SCREEN),
            micPermissionGranted = false, translationModel = TranslationModelState())
        assertEquals(0, state.setupState().completedSteps)
        assertEquals(2, state.setupState().remainingSteps)
        assertEquals(SetupStep.AUDIO_PERMISSION, state.setupState().nextStep)
        assertEquals(SetupStep.MODELS, state.copy(micPermissionGranted = true).setupState().nextStep)
    }

    @Test fun placementSelectionIsGuardedForStartingRunningAndStoppingSessions() {
        assertTrue(ready.placementSelectionEnabled)
        for (runtime in listOf(CaptionRuntimeState(running = true), CaptionRuntimeState(stopping = true))) {
            assertFalse(ready.copy(runtime = runtime).placementSelectionEnabled)
        }
    }
}
