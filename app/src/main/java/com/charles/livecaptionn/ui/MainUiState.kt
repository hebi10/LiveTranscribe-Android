package com.charles.livecaptionn.ui

import com.charles.livecaptionn.service.CaptionRuntimeState
import com.charles.livecaptionn.settings.CaptionSettings
import com.charles.livecaptionn.translation.TranslationModelState
import com.charles.livecaptionn.translation.ModelSetupStage

data class MainUiState(
    val settings: CaptionSettings = CaptionSettings(),
    val runtime: CaptionRuntimeState = CaptionRuntimeState(),
    val micPermissionGranted: Boolean = false,
    val overlayPermissionGranted: Boolean = false,
    val translationModel: TranslationModelState = TranslationModelState()
) {
    /** Both supported languages share the same speech and translation model files. */
    val modelReady: Boolean get() = settings.sourceLanguageCode in setOf("en", "ja") &&
        translationModel.stage == ModelSetupStage.READY
    val translationReady: Boolean get() = modelReady
    val canStart: Boolean get() =
        modelReady && translationReady && overlayPermissionGranted && micPermissionGranted
}
