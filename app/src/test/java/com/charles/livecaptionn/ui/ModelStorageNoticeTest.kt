package com.charles.livecaptionn.ui

import com.charles.livecaptionn.translation.ModelSetupStage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelStorageNoticeTest {
    private val preparing = MainSetupState(
        isVisible = true,
        modelStage = ModelSetupStage.NOT_DOWNLOADED,
        totalBytes = 2_120_095_795L
    )

    @Test fun requiresRemainingDownloadPlusReserve() {
        val lack = preparing.modelStorageNotice(2_000_000_000L)!!
        assertTrue(lack.insufficient)
        assertTrue(lack.message.contains("공간 부족"))
        val enough = preparing.modelStorageNotice(3_000_000_000L)!!
        assertFalse(enough.insufficient)
        assertTrue(enough.message.contains("현재 3000MB"))
    }

    @Test fun partialDownloadsReduceTheNeededBytes() {
        val resumed = preparing.copy(downloadedBytes = 2_000_000_000L)
        val enough = resumed.modelStorageNotice(300_000_000L)!!
        assertFalse(enough.insufficient)
        val low = resumed.modelStorageNotice(100_000_000L)!!
        assertTrue(low.insufficient)
    }

    @Test fun readyOrCheckingModelsDoNotGenerateMisleadingWarnings() {
        assertNull(preparing.copy(modelStage = ModelSetupStage.READY).modelStorageNotice(0L))
        assertNull(preparing.copy(modelStage = ModelSetupStage.CHECKING).modelStorageNotice(0L))
        assertNull(preparing.copy(downloadedBytes = preparing.totalBytes).modelStorageNotice(0L))
        val unknown = preparing.modelStorageNotice(0L)!!
        assertFalse(unknown.insufficient)
        assertTrue(unknown.message.contains("확인할 수 없습니다"))
    }
}
