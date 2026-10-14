package com.charles.livecaptionn.ui

import com.charles.livecaptionn.service.CaptionRuntimeState
import com.charles.livecaptionn.settings.AudioSource
import com.charles.livecaptionn.settings.CaptionSettings
import com.charles.livecaptionn.speech.VoskModelInfo

data class MainUiState(
    val settings: CaptionSettings = CaptionSettings(),
    val runtime: CaptionRuntimeState = CaptionRuntimeState(),
    val micPermissionGranted: Boolean = false,
    val overlayPermissionGranted: Boolean = false,
    val voskModels: List<VoskModelInfo> = emptyList()
) {
    val sourceModels: List<VoskModelInfo> get() = voskModels.filter { it.installed }
    val modelReady: Boolean get() =
        sourceModels.any { it.languageCode == settings.sourceLanguageCode }
    val canStart: Boolean get() =
        modelReady && overlayPermissionGranted && micPermissionGranted
}
