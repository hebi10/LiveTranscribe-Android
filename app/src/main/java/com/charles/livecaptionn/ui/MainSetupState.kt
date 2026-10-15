package com.charles.livecaptionn.ui

import com.charles.livecaptionn.translation.ModelSetupStage

/** Only unfinished setup is shown, and permission actions never request a caption session. */
internal data class MainSetupState(
    val speechModelMissing: Boolean = false,
    val translationStage: ModelSetupStage? = null,
    val translationError: String? = null,
    val audioPermissionAction: AudioPermissionAction = AudioPermissionAction.GRANTED,
    val overlayPermissionNeeded: Boolean = false
) {
    val isVisible: Boolean get() = speechModelMissing || translationStage != null ||
        audioPermissionAction != AudioPermissionAction.GRANTED || overlayPermissionNeeded

    val canPrepareTranslationModel: Boolean get() =
        translationStage == ModelSetupStage.NOT_DOWNLOADED || translationStage == ModelSetupStage.ERROR
}

internal fun MainUiState.setupState(
    startPending: Boolean = false,
    audioPermissionSettingsRequired: Boolean = false
): MainSetupState {
    if (runtime.running || startPending) return MainSetupState()
    val stage = when {
        translationReady -> null
        translationModel.sourceCode != settings.sourceLanguageCode -> ModelSetupStage.CHECKING
        else -> translationModel.stage
    }
    return MainSetupState(
        speechModelMissing = !modelReady,
        translationStage = stage,
        translationError = translationModel.message.takeIf { stage == ModelSetupStage.ERROR },
        audioPermissionAction = when {
            micPermissionGranted -> AudioPermissionAction.GRANTED
            audioPermissionSettingsRequired -> AudioPermissionAction.OPEN_SETTINGS
            else -> AudioPermissionAction.REQUEST
        },
        overlayPermissionNeeded = !overlayPermissionGranted
    )
}
