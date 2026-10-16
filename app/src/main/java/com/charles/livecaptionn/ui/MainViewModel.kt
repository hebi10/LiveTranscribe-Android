package com.charles.livecaptionn.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.charles.livecaptionn.di.AppContainer
import com.charles.livecaptionn.service.manuallySelectedCaptionLanguage
import com.charles.livecaptionn.settings.AudioSource
import com.charles.livecaptionn.settings.CaptionPlacement
import com.charles.livecaptionn.settings.CaptionDisplayMode
import com.charles.livecaptionn.settings.RecognitionLanguages
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainViewModel(
    private val container: AppContainer,
    application: Application
) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(MainUiState())
    private var modelPreparationJob: Job? = null
    private var modelRefreshJob: Job? = null
    val state: StateFlow<MainUiState> = mutable.asStateFlow()
    val historySavingEnabled: Flow<Boolean> = container.settingsRepository.settingsFlow
        .map { it.saveHistory }
        .distinctUntilChanged()

    init {
        viewModelScope.launch {
            container.settingsRepository.settingsFlow.collect { settings ->
                mutable.value = mutable.value.copy(settings = settings)
                refreshPermissionState()
            }
        }
        viewModelScope.launch {
            container.runtimeStore.state.collect { runtime ->
                mutable.value = mutable.value.copy(runtime = runtime)
            }
        }
        viewModelScope.launch {
            container.translationModels.state.collect { modelState ->
                mutable.value = mutable.value.copy(translationModel = modelState)
            }
        }
        viewModelScope.launch {
            container.settingsRepository.settingsFlow
                .map { it.sourceLanguageCode }
                .distinctUntilChanged()
                .collect { code -> refreshModelPreparationState(code) }
        }
        refreshPermissionState()
    }

    fun refreshPermissionState() {
        val context: Context = getApplication()
        val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        mutable.value = mutable.value.copy(
            micPermissionGranted = mic,
            overlayPermissionGranted = Settings.canDrawOverlays(context)
        )
    }

    fun selectSourceLanguage(code: String) {
        if (!RecognitionLanguages.isValidMode(code)) return
        if (container.runtimeStore.state.value.running || container.runtimeStore.state.value.stopping) return
        viewModelScope.launch {
            container.settingsRepository.update {
                if (container.runtimeStore.state.value.running || container.runtimeStore.state.value.stopping) it
                else it.copy(sourceLanguageCode = code)
            }
            container.runtimeStore.update {
                manuallySelectedCaptionLanguage(it, code)
            }
        }
    }

    fun prepareSelectedTranslationModel() {
        if (container.runtimeStore.state.value.running || container.runtimeStore.state.value.stopping ||
            modelPreparationJob?.isActive == true || !mutable.value.setupState().canPrepareModels) return
        modelPreparationJob = viewModelScope.launch {
            container.translationModels.prepare(mutable.value.settings.sourceLanguageCode)
        }
    }

    fun cancelModelPreparation() {
        container.translationModels.cancel()
    }

    fun refreshModelPreparationState() {
        refreshModelPreparationState(mutable.value.settings.sourceLanguageCode)
    }

    private fun refreshModelPreparationState(sourceCode: String) {
        // Resume/language changes reuse verified shared files, and initial observers share one check.
        if (container.offlineModels.isReady() || modelRefreshJob?.isActive == true) return
        modelRefreshJob = viewModelScope.launch {
            container.translationModels.refresh(sourceCode)
        }
    }

    fun selectAudioSource(source: AudioSource) {
        if (container.runtimeStore.state.value.running) return
        viewModelScope.launch {
            container.settingsRepository.update {
                if (container.runtimeStore.state.value.running) it else it.copy(audioSource = source)
            }
        }
    }

    fun resetOverlayPosition() {
        viewModelScope.launch {
            val defaults = com.charles.livecaptionn.settings.CaptionSettings()
            container.settingsRepository.update {
                it.copy(overlayX = defaults.overlayX, overlayY = defaults.overlayY,
                    overlayWidthDp = defaults.overlayWidthDp,
                    overlayHeightDp = defaults.overlayHeightDp, overlayMinimized = false)
            }
        }
    }

    fun updateTextSize(size: Float) {
        viewModelScope.launch { container.settingsRepository.update { it.copy(textSizeSp = size) } }
    }

    fun updateOpacity(opacity: Float) {
        viewModelScope.launch { container.settingsRepository.update { it.copy(overlayOpacity = opacity) } }
    }

    fun updateShowOriginal(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.update { it.copy(showOriginal = enabled) } }
    }

    fun updateCaptionPlacement(placement: CaptionPlacement) {
        if (!mutable.value.placementSelectionEnabled) return
        viewModelScope.launch {
            container.settingsRepository.update {
                val runtime = container.runtimeStore.state.value
                if (runtime.running || runtime.stopping) it else it.copy(captionPlacement = placement)
            }
        }
    }

    fun updateCaptionDisplayMode(mode: CaptionDisplayMode) {
        viewModelScope.launch { container.settingsRepository.update { it.copy(captionDisplayMode = mode) } }
    }

    fun updateStableCaptions(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.update { it.copy(stableCaptions = enabled) } }
    }

    fun updateSaveHistory(enabled: Boolean) {
        viewModelScope.launch { container.historyRecordingGate.updateEnabled(enabled) }
    }

    fun openOverlayPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
        )
    }
}

