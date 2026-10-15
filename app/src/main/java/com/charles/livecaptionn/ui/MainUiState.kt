package com.charles.livecaptionn.ui

import com.charles.livecaptionn.service.CaptionRuntimeState
import com.charles.livecaptionn.settings.CaptionSettings
import com.charles.livecaptionn.settings.RecognitionLanguages
import com.charles.livecaptionn.translation.TranslationModelState
import com.charles.livecaptionn.translation.ModelSetupStage

data class MainUiState(
    val settings: CaptionSettings = CaptionSettings(),
    val runtime: CaptionRuntimeState = CaptionRuntimeState(),
    val micPermissionGranted: Boolean = false,
    val overlayPermissionGranted: Boolean = false,
    val translationModel: TranslationModelState = TranslationModelState()
) {
    /** Automatic and fixed language modes share the same speech and translation model files. */
    val modelReady: Boolean get() = RecognitionLanguages.isValidMode(settings.sourceLanguageCode) &&
        translationModel.stage == ModelSetupStage.READY
    val translationReady: Boolean get() = modelReady
    val canStart: Boolean get() =
        modelReady && translationReady && overlayPermissionGranted && micPermissionGranted
}
