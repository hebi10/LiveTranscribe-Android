package com.charles.livecaptionn.ui

import com.charles.livecaptionn.service.CaptionRuntimeState
import com.charles.livecaptionn.speech.RecognitionStatus
import com.charles.livecaptionn.speech.VoskModelInfo
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
        voskModels = listOf(VoskModelInfo("en", "영어", "model-en", 40, installed = true)),
        translationModel = TranslationModelState("en", ModelSetupStage.READY)
    )

    @Test fun firstRunShowsTheThreePreparationsThatTheUserCanComplete() {
        val setup = ready.copy(
            micPermissionGranted = false,
            overlayPermissionGranted = false,
            translationModel = TranslationModelState("en", ModelSetupStage.NOT_DOWNLOADED)
        ).setupState()

        assertTrue(setup.isVisible)
        assertFalse(setup.speechModelMissing)
        assertTrue(setup.canPrepareTranslationModel)
        assertEquals(AudioPermissionAction.REQUEST, setup.audioPermissionAction)
        assertTrue(setup.overlayPermissionNeeded)
    }

    @Test fun readyModelsForAnotherLanguageCannotOfferDownloadOrHidePreparation() {
        val setup = ready.copy(
            settings = ready.settings.copy(sourceLanguageCode = "ja")
        ).setupState()

        assertTrue(setup.isVisible)
        assertTrue(setup.speechModelMissing)
        assertEquals(ModelSetupStage.CHECKING, setup.translationStage)
        assertFalse(setup.canPrepareTranslationModel)
    }

    @Test fun checkingAndDownloadingKeepTheProgressVisibleWithoutDuplicateActions() {
        for (stage in listOf(ModelSetupStage.CHECKING, ModelSetupStage.DOWNLOADING)) {
            val setup = ready.copy(translationModel = TranslationModelState("en", stage)).setupState()
            assertTrue(setup.isVisible)
            assertEquals(stage, setup.translationStage)
            assertFalse(setup.canPrepareTranslationModel)
            assertNull(setup.translationError)
        }
    }

    @Test fun failedPreparationKeepsItsExplanationAndOffersExplicitRetry() {
        val setup = ready.copy(
            translationModel = TranslationModelState("en", ModelSetupStage.ERROR, "저장 공간을 확인해 주세요.")
        ).setupState()

        assertEquals(ModelSetupStage.ERROR, setup.translationStage)
        assertEquals("저장 공간을 확인해 주세요.", setup.translationError)
        assertTrue(setup.canPrepareTranslationModel)
    }

    @Test fun switchingLanguageDoesNotShowThePreviousLanguageFailureOrRetry() {
        val setup = ready.copy(
            translationModel = TranslationModelState("ja", ModelSetupStage.ERROR, "이전 언어 오류")
        ).setupState()

        assertEquals(ModelSetupStage.CHECKING, setup.translationStage)
        assertNull(setup.translationError)
        assertFalse(setup.canPrepareTranslationModel)
    }

    @Test fun blockedAudioPermissionDirectsTheUserToAppSettings() {
        val setup = ready.copy(micPermissionGranted = false)
            .setupState(audioPermissionSettingsRequired = true)

        assertEquals(AudioPermissionAction.OPEN_SETTINGS, setup.audioPermissionAction)
        assertFalse(setup.overlayPermissionNeeded)
        assertNull(setup.translationStage)
    }

    @Test fun grantedPermissionsDisappearEvenIfAnOldSettingsFlagRemains() {
        val setup = ready.copy(
            translationModel = TranslationModelState("en", ModelSetupStage.NOT_DOWNLOADED)
        ).setupState(audioPermissionSettingsRequired = true)

        assertEquals(AudioPermissionAction.GRANTED, setup.audioPermissionAction)
        assertFalse(setup.overlayPermissionNeeded)
        assertTrue(setup.canPrepareTranslationModel)
    }

    @Test fun aMissingBundledSpeechModelRemainsVisibleAfterOtherSetupIsComplete() {
        val setup = ready.copy(voskModels = emptyList()).setupState()

        assertTrue(setup.isVisible)
        assertTrue(setup.speechModelMissing)
        assertFalse(setup.canPrepareTranslationModel)
    }

    @Test fun completingSetupCollapsesTheChecklistForRepeatUse() {
        val setup = ready.setupState()

        assertFalse(setup.isVisible)
        assertFalse(setup.speechModelMissing)
        assertNull(setup.translationStage)
        assertEquals(AudioPermissionAction.GRANTED, setup.audioPermissionAction)
        assertFalse(setup.overlayPermissionNeeded)
    }

    @Test fun pendingStartHidesSetupActionsUntilTheRequestFinishes() {
        val setup = MainUiState().setupState(startPending = true)

        assertFalse(setup.isVisible)
        assertFalse(setup.canPrepareTranslationModel)
        assertEquals(AudioPermissionAction.GRANTED, setup.audioPermissionAction)
        assertFalse(setup.overlayPermissionNeeded)
    }

    @Test fun activeAndPausedSessionsHideSetupActions() {
        for (paused in listOf(false, true)) {
            val setup = MainUiState(runtime = CaptionRuntimeState(running = true, paused = paused)).setupState()
            assertFalse(setup.isVisible)
            assertFalse(setup.canPrepareTranslationModel)
            assertEquals(AudioPermissionAction.GRANTED, setup.audioPermissionAction)
            assertFalse(setup.overlayPermissionNeeded)
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
