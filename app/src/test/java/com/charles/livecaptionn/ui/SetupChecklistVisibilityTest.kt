package com.charles.livecaptionn.ui

import com.charles.livecaptionn.translation.ModelSetupStage
import org.junit.Assert.assertEquals
import org.junit.Test

class SetupChecklistVisibilityTest {
    @Test fun firstLaunchShowsOnlyTheRequiredAudioPermission() {
        val setup = MainSetupState(
            isVisible = true,
            modelStage = ModelSetupStage.NOT_DOWNLOADED,
            audioPermissionAction = AudioPermissionAction.REQUEST,
            overlayPermissionNeeded = true
        )
        assertEquals(3, setup.totalSteps)
        assertEquals(SetupStep.AUDIO_PERMISSION, setup.nextStep)
        assertEquals(listOf(SetupStep.AUDIO_PERMISSION), setup.visibleChecklistSteps(false))
        assertEquals(
            listOf(SetupStep.AUDIO_PERMISSION, SetupStep.OVERLAY_PERMISSION, SetupStep.MODELS),
            setup.visibleChecklistSteps(true)
        )
    }

    @Test fun splitScreenSkipsUnneededOverlayPermission() {
        val setup = MainSetupState(
            isVisible = true,
            modelStage = ModelSetupStage.NOT_DOWNLOADED,
            overlayPermissionRequired = false
        )
        assertEquals(2, setup.totalSteps)
        assertEquals(SetupStep.MODELS, setup.nextStep)
        assertEquals(listOf(SetupStep.MODELS), setup.visibleChecklistSteps(false))
        assertEquals(
            listOf(SetupStep.AUDIO_PERMISSION, SetupStep.MODELS),
            setup.visibleChecklistSteps(true)
        )
    }

    @Test fun partiallyPreparedPopupOnlyShowsTheNextNeededStep() {
        val setup = MainSetupState(
            isVisible = true,
            modelStage = ModelSetupStage.NOT_DOWNLOADED,
            overlayPermissionNeeded = true
        )
        assertEquals(SetupStep.OVERLAY_PERMISSION, setup.nextStep)
        assertEquals(listOf(SetupStep.OVERLAY_PERMISSION), setup.visibleChecklistSteps(false))
        assertEquals(2, setup.remainingSteps)
    }

    @Test fun completedSetupKeepsAllDetailsAvailableOnDemand() {
        val setup = MainSetupState(isVisible = true, modelStage = ModelSetupStage.READY)
        assertEquals(3, setup.completedSteps)
        assertEquals(emptyList<SetupStep>(), setup.visibleChecklistSteps(false))
        assertEquals(3, setup.visibleChecklistSteps(true).size)
        assertEquals(emptyList<SetupStep>(), setup.copy(isVisible = false).visibleChecklistSteps(true))
    }

    @Test fun modelErrorAndDownloadKeepTheCurrentActionVisible() {
        for (stage in listOf(ModelSetupStage.ERROR, ModelSetupStage.DOWNLOADING, ModelSetupStage.CHECKING)) {
            val setup = MainSetupState(isVisible = true, modelStage = stage)
            assertEquals(listOf(SetupStep.MODELS), setup.visibleChecklistSteps(false))
        }
    }
}
