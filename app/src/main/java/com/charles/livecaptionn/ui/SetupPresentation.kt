package com.charles.livecaptionn.ui

import com.charles.livecaptionn.translation.ModelSetupStage

internal fun MainSetupState.modelStatusLabel(): String = when (modelStage) {
    ModelSetupStage.READY -> "준비 완료"
    ModelSetupStage.CHECKING -> "다운로드한 파일 확인 중"
    ModelSetupStage.DOWNLOADING ->
        if ((downloadProgress ?: 0f) >= 1f) "전송 완료 · 모델 파일 확인 중"
        else "모델 다운로드 중"
    ModelSetupStage.ERROR -> "모델 준비 오류 · 복구 필요"
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

/** Permission copy reflects the existing Activity actions; no permission checks are changed. */
internal fun MainSetupState.audioPermissionGuidance(): String = when (audioPermissionAction) {
    AudioPermissionAction.REQUEST ->
        "동영상 음성·마이크 번역에는 Android 마이크 권한이 필요합니다."
    AudioPermissionAction.OPEN_SETTINGS ->
        "마이크 권한을 다시 허용하려면 Android 앱 설정으로 이동한 뒤 이 화면으로 돌아오세요."
    AudioPermissionAction.GRANTED -> "마이크 권한 허용 완료"
}

internal fun MainSetupState.overlayPermissionGuidance(): String =
    "팝업 자막에서만 '다른 앱 위에 표시' 권한이 필요합니다. 설정에서 허용하고 앱으로 돌아오세요."

/** The message never presents a download as installed until integrity verification succeeds. */
internal fun MainSetupState.modelStageGuidance(): String? = when (modelStage) {
    ModelSetupStage.CHECKING ->
        "다운로드한 파일을 검증하고 있습니다. 완료되기 전에는 번역을 시작할 수 없습니다."
    ModelSetupStage.DOWNLOADING ->
        if ((downloadProgress ?: 0f) >= 1f)
            "다운로드된 파일을 확인하고 있습니다. 파일 검증이 끝나야 번역을 시작할 수 있습니다."
        else "다운로드 중에는 앱을 열어 두세요. 중단해도 남은 파일부터 이어받을 수 있습니다."
    ModelSetupStage.READY ->
        "자동 감지·일본어·영어는 검증된 같은 모델을 사용합니다."
    ModelSetupStage.NOT_DOWNLOADED, ModelSetupStage.ERROR, null -> null
}

internal fun MainSetupState.modelPreparationGuidance(): String = when {
    modelStage == ModelSetupStage.ERROR && canResumeModelPreparation ->
        "남아 있는 다운로드에서 이어받을 수 있습니다. 연결·저장 공간을 확인해 주세요."
    modelStage == ModelSetupStage.ERROR ->
        "연결·저장 공간을 확인한 뒤 다시 시도해 주세요."
    canResumeModelPreparation ->
        "이전 다운로드에서 이어받습니다. 네트워크 연결을 확인해 주세요."
    else -> "처음 사용할 때 약 2.1GB를 다운로드합니다. Wi-Fi와 충분한 저장 공간을 확인해 주세요. 모바일 데이터 사용 시 요금이 발생할 수 있습니다."
}

/** Estimates only missing model bytes; the downloader performs the authoritative space check. */
internal data class ModelStorageNotice(val message: String, val insufficient: Boolean)

internal fun MainSetupState.modelStorageNotice(availableBytes: Long): ModelStorageNotice? {
    if (modelStage == ModelSetupStage.READY || modelStage == ModelSetupStage.CHECKING || totalBytes <= 0) return null
    val remaining = (totalBytes - downloadedBytes.coerceIn(0L, totalBytes)).coerceAtLeast(0L)
    if (remaining == 0L) return null
    if (availableBytes <= 0L) return ModelStorageNotice(
        "기기 저장 공간을 확인할 수 없습니다. 다운로드 전 사용 가능한 공간을 확인해 주세요.", false)
    val reserve = com.charles.livecaptionn.compatibility.DeviceCompatibilityChecker.DOWNLOAD_RESERVE_BYTES
    val required = remaining + reserve
    val requiredMb = (required + 999_999L) / 1_000_000L
    val freeMb = availableBytes / 1_000_000L
    return ModelStorageNotice(
        if (availableBytes < required)
            "모델 저장 공간 부족: 약 ${requiredMb}MB 필요 · 현재 ${freeMb}MB 사용 가능. 공간을 확보한 뒤 다시 시도해 주세요."
        else "모델 추가 저장 공간: 약 ${requiredMb}MB 필요 · 현재 ${freeMb}MB 사용 가능.",
        insufficient = availableBytes < required
    )
}
