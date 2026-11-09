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
        assertEquals("다운로드 오류 · 재시도 필요", error.modelStatusLabel())
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
}
