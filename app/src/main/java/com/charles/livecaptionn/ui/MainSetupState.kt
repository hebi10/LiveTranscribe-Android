package com.charles.livecaptionn.ui

import com.charles.livecaptionn.translation.ModelSetupStage

/** Only unfinished setup is shown, and permission actions never request a caption session. */
internal data class MainSetupState(
    val modelStage: ModelSetupStage? = null,
    val modelMessage: String? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val currentFile: String? = null,
    val audioPermissionAction: AudioPermissionAction = AudioPermissionAction.GRANTED,
    val overlayPermissionNeeded: Boolean = false
) {
    val isVisible: Boolean get() = modelStage != null ||
        audioPermissionAction != AudioPermissionAction.GRANTED || overlayPermissionNeeded

    val canPrepareModels: Boolean get() =
        modelStage == ModelSetupStage.NOT_DOWNLOADED || modelStage == ModelSetupStage.ERROR
    val canCancelModelPreparation: Boolean get() = modelStage == ModelSetupStage.DOWNLOADING
    val canResumeModelPreparation: Boolean get() = canPrepareModels && downloadedBytes > 0
    val downloadProgress: Float? get() = if (totalBytes > 0)
        (downloadedBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0).toFloat() else null
}

internal fun MainUiState.setupState(
    startPending: Boolean = false,
    audioPermissionSettingsRequired: Boolean = false
): MainSetupState {
    if (runtime.running || runtime.stopping || startPending) return MainSetupState()
    val stage = translationModel.stage.takeUnless { it == ModelSetupStage.READY }
    return MainSetupState(
        modelStage = stage,
        modelMessage = translationModel.message.takeIf { stage != null },
        downloadedBytes = translationModel.downloadedBytes.coerceAtLeast(0),
        totalBytes = translationModel.totalBytes.coerceAtLeast(0),
        currentFile = translationModel.currentFile.takeIf { stage == ModelSetupStage.DOWNLOADING },
        audioPermissionAction = when {
            micPermissionGranted -> AudioPermissionAction.GRANTED
            audioPermissionSettingsRequired -> AudioPermissionAction.OPEN_SETTINGS
            else -> AudioPermissionAction.REQUEST
        },
        overlayPermissionNeeded = !overlayPermissionGranted
    )
}
