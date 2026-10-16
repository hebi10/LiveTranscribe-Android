package com.charles.livecaptionn.ui

import com.charles.livecaptionn.service.CaptionRuntimeState
import com.charles.livecaptionn.settings.CaptionPlacement
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
    val overlayPermissionRequired: Boolean get() = settings.captionPlacement == CaptionPlacement.POPUP
    val placementSelectionEnabled: Boolean get() = !runtime.running && !runtime.stopping
    val canStart: Boolean get() =
        modelReady && translationReady && (!overlayPermissionRequired || overlayPermissionGranted) && micPermissionGranted
}
