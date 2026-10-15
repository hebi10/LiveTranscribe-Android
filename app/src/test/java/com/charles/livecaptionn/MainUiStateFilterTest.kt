package com.charles.livecaptionn

import com.charles.livecaptionn.ui.MainUiState
import com.charles.livecaptionn.translation.ModelSetupStage
import com.charles.livecaptionn.translation.TranslationModelState
import com.charles.livecaptionn.settings.AudioSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainUiStateFilterTest {
    private val ready = MainUiState(
        micPermissionGranted = true,
        overlayPermissionGranted = true,
        translationModel = TranslationModelState("en", ModelSetupStage.READY)
    )

    @Test fun oneCompletedDownloadEnablesBothSupportedLanguages() {
        for (selected in listOf("en", "ja")) {
            for (lastRequested in listOf("en", "ja")) {
                val state = ready.copy(
                    settings = ready.settings.copy(sourceLanguageCode = selected),
                    translationModel = TranslationModelState(lastRequested, ModelSetupStage.READY)
                )
                assertTrue(state.modelReady)
                assertTrue(state.translationReady)
                assertTrue(state.canStart)
            }
        }
    }

    @Test fun downloadedModelsDoNotEnableAnUnsupportedLanguage() {
        val state = ready.copy(settings = ready.settings.copy(sourceLanguageCode = "fr"))
        assertFalse(state.modelReady)
        assertFalse(state.canStart)
    }

    @Test fun noModelDisablesStart() {
        assertFalse(MainUiState().canStart)
    }

    @Test fun everyIncompleteStateDisablesStartEvenAfterModelsWereReady() {
        for (stage in ModelSetupStage.entries.filter { it != ModelSetupStage.READY }) {
            val state = ready.copy(translationModel = TranslationModelState("en", stage,
                downloadedBytes = 100, totalBytes = 100))
            assertFalse("Start must wait for verified READY, including a full partial download: $stage", state.canStart)
            assertFalse(state.modelReady)
            assertFalse(state.translationReady)
        }
    }

    @Test fun bothAudioSourcesRequireRecordAudioAndOverlayPermissions() {
        for (audioSource in AudioSource.entries) {
            val state = ready.copy(settings = ready.settings.copy(audioSource = audioSource))
            assertTrue(state.canStart)
            assertFalse(state.copy(micPermissionGranted = false).canStart)
            assertFalse(state.copy(overlayPermissionGranted = false).canStart)
        }
    }
}
