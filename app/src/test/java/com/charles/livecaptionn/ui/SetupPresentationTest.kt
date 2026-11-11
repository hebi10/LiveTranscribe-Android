package com.charles.livecaptionn.ui

import com.charles.livecaptionn.translation.ModelSetupStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupPresentationTest {
    @Test fun unpreparedAndCheckingModelsDoNotOfferDuplicateDownload() {
        val first = MainSetupState(modelStage = ModelSetupStage.NOT_DOWNLOADED)
        assertEquals("다운로드 필요 · 약 2.1GB", first.modelStatusLabel())
        assertEquals("모델 다운로드 (약 2.1GB)", first.modelActionLabel())
        val checking = first.copy(modelStage = ModelSetupStage.CHECKING)
        assertEquals("다운로드한 파일 확인 중", checking.modelStatusLabel())
        assertNull(checking.modelActionLabel())
        val downloading = first.copy(modelStage = ModelSetupStage.DOWNLOADING)
        assertEquals("다운로드 진행 중", downloading.modelStatusLabel())
        assertNull(downloading.modelActionLabel())
    }

    @Test fun partialDownloadShowsResumeAndAccurateProgress() {
        val paused = MainSetupState(modelStage = ModelSetupStage.NOT_DOWNLOADED,
            downloadedBytes = 500_000_000, totalBytes = 2_000_000_000)
        assertEquals("이어받기 가능", paused.modelStatusLabel())
        assertEquals("모델 다운로드 이어받기", paused.modelActionLabel())
        assertEquals("500 / 2000 MB · 25%", paused.modelProgressSummary())
    }

    @Test fun errorAndPartialErrorHaveDistinctRecoveryActions() {
        val error = MainSetupState(modelStage = ModelSetupStage.ERROR)
        assertEquals("모델 준비 오류 · 복구 필요", error.modelStatusLabel())
        assertEquals("모델 다운로드 다시 시도", error.modelActionLabel())
        assertEquals("모델 다운로드 이어받기", error.copy(
            downloadedBytes = 10, totalBytes = 100).modelActionLabel())
    }

    @Test fun fullBytesAreNotMistakenForVerifiedModels() {
        val downloading = MainSetupState(modelStage = ModelSetupStage.DOWNLOADING,
            downloadedBytes = 100, totalBytes = 100)
        assertEquals("100 / 100 B · 100%", downloading.modelProgressSummary())
        assertEquals("다운로드 진행 중", downloading.modelStatusLabel())
        val ready = downloading.copy(modelStage = ModelSetupStage.READY)
        assertEquals("준비 완료", ready.modelStatusLabel())
        assertNull(ready.modelActionLabel())
    }

    @Test fun unknownTotalDoesNotInventProgress() {
        assertEquals("다운로드 용량 확인 중", MainSetupState().modelProgressSummary())
        val unknown = MainSetupState(modelStage = ModelSetupStage.DOWNLOADING,
            downloadedBytes = 3_000_000, totalBytes = 0)
        assertEquals("3MB 다운로드됨", unknown.modelProgressSummary())
        assertEquals("100 / 100 B · 100%", unknown.copy(
            downloadedBytes = 120, totalBytes = 100).modelProgressSummary())
    }

    @Test fun activeFileNameOnlyAppearsForDownloads() {
        val state = MainSetupState(modelStage = ModelSetupStage.DOWNLOADING,
            currentFile = "models/translate/weights.bin")
        assertEquals("weights.bin", state.activeModelFileLabel())
        assertNull(state.copy(modelStage = ModelSetupStage.CHECKING).activeModelFileLabel())
        assertNull(state.copy(currentFile = "").activeModelFileLabel())
        assertTrue(state.copy(currentFile = "x".repeat(100)).activeModelFileLabel()!!.length <= 72)
    }

    @Test fun permissionGuidanceDistinguishesRequestFromSettingsRecovery() {
        val request = MainSetupState(audioPermissionAction = AudioPermissionAction.REQUEST)
        assertEquals("동영상 음성·마이크 번역에는 Android 마이크 권한이 필요합니다.",
            request.audioPermissionGuidance())
        val denied = request.copy(audioPermissionAction = AudioPermissionAction.OPEN_SETTINGS)
        assertTrue(denied.audioPermissionGuidance().contains("Android 앱 설정"))
        assertTrue(denied.audioPermissionGuidance().contains("돌아오세요"))
        assertEquals("마이크 권한 허용 완료",
            request.copy(audioPermissionAction = AudioPermissionAction.GRANTED)
                .audioPermissionGuidance())
    }

    @Test fun overlayPermissionGuidanceRemainsPopupSpecific() {
        val popup = MainSetupState(isVisible = true, overlayPermissionRequired = true,
            overlayPermissionNeeded = true)
        assertEquals(SetupStep.OVERLAY_PERMISSION, popup.nextStep)
        assertTrue(popup.overlayPermissionGuidance().contains("팝업 자막에서만"))
        assertEquals(SetupStep.MODELS, popup.copy(overlayPermissionRequired = false).nextStep)
    }

    @Test fun modelStageGuidanceNeverCallsDownloadCompleteBeforeVerification() {
        val base = MainSetupState(modelStage = ModelSetupStage.CHECKING)
        assertTrue(base.modelStageGuidance()!!.contains("검증"))
        assertNull(base.modelActionLabel())
        val downloading = base.copy(modelStage = ModelSetupStage.DOWNLOADING,
            downloadedBytes = 100, totalBytes = 100)
        assertTrue(downloading.modelStageGuidance()!!.contains("이어받"))
        assertEquals("다운로드 진행 중", downloading.modelStatusLabel())
        assertNull(downloading.modelActionLabel())
        assertTrue(downloading.copy(modelStage = ModelSetupStage.READY)
            .modelStageGuidance()!!.contains("검증된"))
        assertNull(base.copy(modelStage = ModelSetupStage.ERROR).modelStageGuidance())
    }

    @Test fun modelRecoveryGuidanceMatchesTheActualAvailableAction() {
        val initial = MainSetupState(modelStage = ModelSetupStage.NOT_DOWNLOADED)
        assertTrue(initial.modelPreparationGuidance().contains("약 2.1GB"))
        assertEquals("모델 다운로드 (약 2.1GB)", initial.modelActionLabel())

        val paused = initial.copy(downloadedBytes = 10, totalBytes = 100)
        assertTrue(paused.modelPreparationGuidance().contains("이어받"))
        assertEquals("모델 다운로드 이어받기", paused.modelActionLabel())

        val failed = initial.copy(modelStage = ModelSetupStage.ERROR)
        assertTrue(failed.modelPreparationGuidance().contains("다시 시도"))
        assertEquals("모델 다운로드 다시 시도", failed.modelActionLabel())

        val partialFailure = failed.copy(downloadedBytes = 10, totalBytes = 100)
        assertTrue(partialFailure.modelPreparationGuidance().contains("이어받"))
        assertEquals("모델 다운로드 이어받기", partialFailure.modelActionLabel())
    }

}
