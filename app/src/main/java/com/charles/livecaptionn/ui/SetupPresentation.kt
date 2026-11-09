package com.charles.livecaptionn.ui

import com.charles.livecaptionn.translation.ModelSetupStage

internal fun MainSetupState.modelStatusLabel(): String = when (modelStage) {
    ModelSetupStage.READY -> "준비 완료"
    ModelSetupStage.CHECKING -> "다운로드한 파일 확인 중"
    ModelSetupStage.DOWNLOADING -> "다운로드 진행 중"
    ModelSetupStage.ERROR -> "다운로드 오류 · 재시도 필요"
    ModelSetupStage.NOT_DOWNLOADED, null ->
        if (canResumeModelPreparation) "이어받기 가능" else "다운로드 필요 · 약 2.1GB"
}

internal fun MainSetupState.modelActionLabel(): String? {
    if (!canPrepareModels) return null
    return when {
        canResumeModelPreparation -> "모델 다운로드 이어받기"
        modelStage == ModelSetupStage.ERROR -> "모델 다운로드 다시 시도"
        else -> "모델 다운로드 (약 2.1GB)"
    }
}

internal fun MainSetupState.modelProgressSummary(): String {
    val bytes = downloadedBytes.coerceAtLeast(0)
    if (totalBytes <= 0) {
        val downloadedMb = bytes / 1_000_000
        return if (bytes > 0) "${downloadedMb}MB 다운로드됨"
            else "다운로드 용량 확인 중"
    }
    val progressPercent = ((downloadProgress ?: 0f) * 100).toInt().coerceIn(0, 100)
    val shownBytes = bytes.coerceAtMost(totalBytes)
    return if (totalBytes >= 1_000_000) {
        "${shownBytes / 1_000_000} / ${totalBytes / 1_000_000} MB · ${progressPercent}%"
    } else {
        "$shownBytes / $totalBytes B · ${progressPercent}%"
    }
}

internal fun MainSetupState.activeModelFileLabel(): String? =
    if (modelStage == ModelSetupStage.DOWNLOADING)
        currentFile?.takeIf { it.isNotBlank() }?.substringAfterLast('/')?.take(72)
    else null
