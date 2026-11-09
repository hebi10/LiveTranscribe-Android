package com.charles.livecaptionn.ui

import com.charles.livecaptionn.service.CaptionRuntimeState
import com.charles.livecaptionn.settings.CaptionPlacement
import com.charles.livecaptionn.translation.ModelSetupStage
import com.charles.livecaptionn.translation.TranslationModelState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeActionStateTest {
    private val ready = MainUiState(
        micPermissionGranted = true,
        overlayPermissionGranted = true,
        translationModel = TranslationModelState("auto", ModelSetupStage.READY)
    )

    @Test fun firstLaunchRequiresPreparationAndCannotStart() {
        val action = MainUiState().homeAction(startPending = false)
        assertEquals(HomeActionKind.BLOCKED, action.kind)
        assertEquals("준비 후 번역 시작", action.label)
        assertFalse(action.enabled)
    }

    @Test fun preparedPopupAndSplitUseTheExistingStartLabels() {
        val popup = ready.homeAction(startPending = false)
        assertEquals(HomeActionKind.START, popup.kind)
        assertEquals("팝업 자막 시작", popup.label)
        assertTrue(popup.enabled)
        val split = ready.copy(settings = ready.settings.copy(
            captionPlacement = CaptionPlacement.SPLIT_SCREEN
        )).homeAction(startPending = false)
        assertEquals("분할 자막 시작", split.label)
    }

    @Test fun pendingStartUsesCancelEvenIfTheModelIsReady() {
        val action = ready.homeAction(startPending = true)
        assertEquals(HomeActionKind.STOP, action.kind)
        assertEquals("요청 취소", action.label)
        assertTrue(action.enabled)
    }

    @Test fun runningAndStoppingPreferStopOverAnotherStart() {
        val active = ready.copy(runtime = CaptionRuntimeState(running = true)).homeAction(false)
        assertEquals(HomeActionKind.STOP, active.kind)
        assertEquals("번역 중지", active.label)
        assertTrue(active.enabled)
        val stopping = ready.copy(runtime = CaptionRuntimeState(
            running = true, stopping = true
        )).homeAction(false)
        assertEquals(HomeActionKind.STOP, stopping.kind)
        assertEquals("종료 중", stopping.label)
        assertFalse(stopping.enabled)
    }

    @Test fun restartLanguagePreservesRestartActionWithoutExtraSession() {
        val action = ready.copy(runtime = CaptionRuntimeState(
            restartLanguageCode = "ja"
        )).homeAction(false)
        assertEquals(HomeActionKind.START, action.kind)
        assertEquals("번역 다시 시작", action.label)
    }

    @Test fun revokedPermissionDisablesStartButDoesNotChangeStoredSettings() {
        val original = ready.settings
        val action = ready.copy(micPermissionGranted = false).homeAction(false)
        assertEquals(HomeActionKind.BLOCKED, action.kind)
        assertFalse(action.enabled)
        assertEquals(original, ready.settings)
    }
}
