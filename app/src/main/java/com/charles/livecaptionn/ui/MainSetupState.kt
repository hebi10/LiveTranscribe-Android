package com.charles.livecaptionn.ui

import com.charles.livecaptionn.translation.ModelSetupStage

internal enum class SetupStep { AUDIO_PERMISSION, OVERLAY_PERMISSION, MODELS }

/** Completed requirements remain reviewable; permission actions never request a caption session. */
internal data class MainSetupState(
    val isVisible: Boolean = false,
    val modelStage: ModelSetupStage? = null,
    val modelMessage: String? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val currentFile: String? = null,
    val audioPermissionAction: AudioPermissionAction = AudioPermissionAction.GRANTED,
    val overlayPermissionNeeded: Boolean = false,
    val overlayPermissionRequired: Boolean = true
) {
    val totalSteps: Int get() = if (overlayPermissionRequired) 3 else 2
    val completedSteps: Int get() =
        (if (audioPermissionAction == AudioPermissionAction.GRANTED) 1 else 0) +
        (if (overlayPermissionRequired && !overlayPermissionNeeded) 1 else 0) +
        (if (modelStage == ModelSetupStage.READY) 1 else 0)
    val remainingSteps: Int get() = totalSteps - completedSteps
    val isComplete: Boolean get() = remainingSteps == 0
    val nextStep: SetupStep? get() = when {
        !isVisible -> null
        audioPermissionAction != AudioPermissionAction.GRANTED -> SetupStep.AUDIO_PERMISSION
        overlayPermissionRequired && overlayPermissionNeeded -> SetupStep.OVERLAY_PERMISSION
        modelStage != ModelSetupStage.READY -> SetupStep.MODELS
        else -> null
    }

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
    val stage = translationModel.stage
    return MainSetupState(
        isVisible = true,
        modelStage = stage,
        modelMessage = translationModel.message.takeUnless { stage == ModelSetupStage.READY },
        downloadedBytes = translationModel.downloadedBytes.coerceAtLeast(0),
        totalBytes = translationModel.totalBytes.coerceAtLeast(0),
        currentFile = translationModel.currentFile.takeIf { stage == ModelSetupStage.DOWNLOADING },
        audioPermissionAction = when {
            micPermissionGranted -> AudioPermissionAction.GRANTED
            audioPermissionSettingsRequired -> AudioPermissionAction.OPEN_SETTINGS
            else -> AudioPermissionAction.REQUEST
        },
        overlayPermissionNeeded = overlayPermissionRequired && !overlayPermissionGranted,
        overlayPermissionRequired = overlayPermissionRequired
    )
}
