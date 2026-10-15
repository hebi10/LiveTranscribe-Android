package com.charles.livecaptionn.ui

import com.charles.livecaptionn.service.CaptionRuntimeState
import com.charles.livecaptionn.speech.RecognitionStatus
import com.charles.livecaptionn.translation.ModelSetupStage
import com.charles.livecaptionn.translation.TranslationModelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainSetupStateTest {
    private val ready = MainUiState(
        micPermissionGranted = true,
        overlayPermissionGranted = true,
        translationModel = TranslationModelState("en", ModelSetupStage.READY)
    )

    @Test fun firstRunShowsPermissionsAndOneExplicitModelPreparation() {
        val state = ready.copy(
            micPermissionGranted = false,
            overlayPermissionGranted = false,
            translationModel = TranslationModelState("en", ModelSetupStage.NOT_DOWNLOADED)
        )
        val setup = state.setupState()

        assertTrue(setup.isVisible)
        assertTrue(setup.canPrepareModels)
        assertFalse(setup.canCancelModelPreparation)
        assertFalse(setup.canResumeModelPreparation)
        assertEquals(AudioPermissionAction.REQUEST, setup.audioPermissionAction)
        assertTrue(setup.overlayPermissionNeeded)
        assertFalse(state.canStart)
    }

    @Test fun checklistCountsCompletedStepsAndPointsToTheNextMissingRequirement() {
        val firstRun = ready.copy(
            micPermissionGranted = false,
            overlayPermissionGranted = false,
            translationModel = TranslationModelState("auto", ModelSetupStage.NOT_DOWNLOADED)
        )
        val first = firstRun.setupState()
        assertEquals(0, first.completedSteps)
        assertEquals(3, first.remainingSteps)
        assertEquals(SetupStep.AUDIO_PERMISSION, first.nextStep)

        val audioAllowed = firstRun.copy(micPermissionGranted = true).setupState()
        assertEquals(1, audioAllowed.completedSteps)
        assertEquals(2, audioAllowed.remainingSteps)
        assertEquals(SetupStep.OVERLAY_PERMISSION, audioAllowed.nextStep)

        val permissionsAllowed = firstRun.copy(micPermissionGranted = true,
            overlayPermissionGranted = true).setupState()
        assertEquals(2, permissionsAllowed.completedSteps)
        assertEquals(1, permissionsAllowed.remainingSteps)
        assertEquals(SetupStep.MODELS, permissionsAllowed.nextStep)

        val complete = ready.setupState()
        assertEquals(3, complete.completedSteps)
        assertEquals(0, complete.remainingSteps)
        assertTrue(complete.isComplete)
        assertNull(complete.nextStep)
    }

    @Test fun fullyDownloadedButUnverifiedModelsRemainAnUnfinishedStep() {
        for (stage in listOf(ModelSetupStage.CHECKING, ModelSetupStage.DOWNLOADING, ModelSetupStage.ERROR)) {
            val setup = ready.copy(translationModel = TranslationModelState("auto", stage,
                downloadedBytes = 100, totalBytes = 100)).setupState()
            assertEquals(2, setup.completedSteps)
            assertEquals(1, setup.remainingSteps)
            assertFalse(setup.isComplete)
            assertEquals(SetupStep.MODELS, setup.nextStep)
        }
    }

    @Test fun revokedPermissionBecomesTheNextActionWithoutLosingModelCompletion() {
        val setup = ready.copy(micPermissionGranted = false)
            .setupState(audioPermissionSettingsRequired = true)
        assertEquals(2, setup.completedSteps)
        assertEquals(1, setup.remainingSteps)
        assertEquals(SetupStep.AUDIO_PERMISSION, setup.nextStep)
        assertEquals(ModelSetupStage.READY, setup.modelStage)
    }

    @Test fun preparedSharedModelsRemainReadyWhenTheSelectedLanguageChanges() {
        val state = ready.copy(settings = ready.settings.copy(sourceLanguageCode = "ja"))
        assertTrue(state.canStart)
        assertTrue(state.setupState().isVisible)
    }

    @Test fun checkingDoesNotOfferADuplicateDownloadOrCancellation() {
        val setup = ready.copy(translationModel = TranslationModelState("en", ModelSetupStage.CHECKING)).setupState()

        assertTrue(setup.isVisible)
        assertEquals(ModelSetupStage.CHECKING, setup.modelStage)
        assertFalse(setup.canPrepareModels)
        assertFalse(setup.canCancelModelPreparation)
        assertNull(setup.downloadProgress)
    }

    @Test fun activeDownloadKeepsProgressAndCancellationWhenLanguageChanges() {
        val state = ready.copy(
            settings = ready.settings.copy(sourceLanguageCode = "ja"),
            translationModel = TranslationModelState("en", ModelSetupStage.DOWNLOADING,
                downloadedBytes = 500_000_000, totalBytes = 2_000_000_000, currentFile = "model.onnx")
        )
        val setup = state.setupState()

        assertEquals(ModelSetupStage.DOWNLOADING, setup.modelStage)
        assertEquals(500_000_000L, setup.downloadedBytes)
        assertEquals(2_000_000_000L, setup.totalBytes)
        assertEquals(0.25f, setup.downloadProgress)
        assertEquals("model.onnx", setup.currentFile)
        assertTrue(setup.canCancelModelPreparation)
        assertFalse(setup.canPrepareModels)
        assertFalse(state.canStart)
    }

    @Test fun cancellationRetainsProgressAndOffersExplicitResumeWithoutStarting() {
        val state = ready.copy(translationModel = TranslationModelState("en", ModelSetupStage.NOT_DOWNLOADED,
            downloadedBytes = 300_000_000, totalBytes = 2_000_000_000))
        val setup = state.setupState()

        assertTrue(setup.canPrepareModels)
        assertTrue(setup.canResumeModelPreparation)
        assertFalse(setup.canCancelModelPreparation)
        assertEquals(300_000_000L, setup.downloadedBytes)
        assertFalse(state.canStart)
    }

    @Test fun failedPreparationKeepsItsExplanationAndOffersExplicitRetry() {
        val setup = ready.copy(
            translationModel = TranslationModelState("ja", ModelSetupStage.ERROR, "저장 공간을 확인해 주세요.")
        ).setupState()

        assertEquals(ModelSetupStage.ERROR, setup.modelStage)
        assertEquals("저장 공간을 확인해 주세요.", setup.modelMessage)
        assertTrue(setup.canPrepareModels)
        assertFalse(setup.canCancelModelPreparation)
    }

    @Test fun failedPartialDownloadOffersResumeWithTheSameExplanation() {
        val setup = ready.copy(translationModel = TranslationModelState("en", ModelSetupStage.ERROR,
            "연결을 확인해 주세요.", downloadedBytes = 10, totalBytes = 100)).setupState()
        assertTrue(setup.canResumeModelPreparation)
        assertEquals("연결을 확인해 주세요.", setup.modelMessage)
        assertEquals(0.1f, setup.downloadProgress)
    }

    @Test fun progressIsClampedAndUnknownTotalDoesNotPretendToBeComplete() {
        assertNull(MainSetupState(downloadedBytes = 20).downloadProgress)
        assertEquals(1f, MainSetupState(downloadedBytes = 120, totalBytes = 100).downloadProgress)
        assertEquals(0f, MainSetupState(downloadedBytes = -20, totalBytes = 100).downloadProgress)
    }

    @Test fun blockedAudioPermissionDirectsTheUserToAppSettings() {
        val setup = ready.copy(micPermissionGranted = false)
            .setupState(audioPermissionSettingsRequired = true)

        assertEquals(AudioPermissionAction.OPEN_SETTINGS, setup.audioPermissionAction)
        assertFalse(setup.overlayPermissionNeeded)
        assertEquals(SetupStep.AUDIO_PERMISSION, setup.nextStep)
        assertEquals(ModelSetupStage.READY, setup.modelStage)
    }

    @Test fun grantedPermissionsRemainCompleteEvenIfAnOldSettingsFlagRemains() {
        val setup = ready.copy(
            translationModel = TranslationModelState("en", ModelSetupStage.NOT_DOWNLOADED)
        ).setupState(audioPermissionSettingsRequired = true)

        assertEquals(AudioPermissionAction.GRANTED, setup.audioPermissionAction)
        assertFalse(setup.overlayPermissionNeeded)
        assertTrue(setup.canPrepareModels)
    }

    @Test fun completingSetupKeepsCompletedStepsAvailableForReview() {
        val setup = ready.setupState()

        assertTrue(setup.isVisible)
        assertFalse(setup.canPrepareModels)
        assertFalse(setup.canCancelModelPreparation)
        assertEquals(ModelSetupStage.READY, setup.modelStage)
        assertEquals(AudioPermissionAction.GRANTED, setup.audioPermissionAction)
        assertFalse(setup.overlayPermissionNeeded)
    }

    @Test fun pendingStartHidesSetupActionsUntilTheRequestFinishes() {
        val setup = MainUiState().setupState(startPending = true)

        assertFalse(setup.isVisible)
        assertFalse(setup.canPrepareModels)
        assertFalse(setup.canCancelModelPreparation)
        assertEquals(AudioPermissionAction.GRANTED, setup.audioPermissionAction)
        assertFalse(setup.overlayPermissionNeeded)
        assertNull(setup.nextStep)
    }

    @Test fun activePausedAndStoppingSessionsHideSetupActions() {
        for (runtime in listOf(
            CaptionRuntimeState(running = true),
            CaptionRuntimeState(running = true, paused = true),
            CaptionRuntimeState(stopping = true)
        )) {
            val setup = MainUiState(runtime = runtime).setupState()
            assertFalse(setup.isVisible)
            assertFalse(setup.canPrepareModels)
            assertFalse(setup.canCancelModelPreparation)
            assertEquals(AudioPermissionAction.GRANTED, setup.audioPermissionAction)
            assertFalse(setup.overlayPermissionNeeded)
            assertNull(setup.nextStep)
        }
    }

    @Test fun runtimeFailureDoesNotHideTheSetupActionNeededToRecover() {
        val setup = ready.copy(
            runtime = CaptionRuntimeState(status = RecognitionStatus.ERROR, lastError = "음성 권한 필요"),
            micPermissionGranted = false
        ).setupState()

        assertTrue(setup.isVisible)
        assertEquals(AudioPermissionAction.REQUEST, setup.audioPermissionAction)
    }
}
