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
import com.charles.livecaptionn.settings.AudioSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainViewModel(
    private val container: AppContainer,
    application: Application
) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = mutable.asStateFlow()

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
            container.voskRegistry.models.collect { models ->
                mutable.value = mutable.value.copy(voskModels = models)
            }
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
        if (code !in setOf("en", "ja")) return
        viewModelScope.launch {
            container.settingsRepository.update { it.copy(sourceLanguageCode = code) }
        }
    }

    fun selectAudioSource(source: AudioSource) {
        viewModelScope.launch {
            container.settingsRepository.update { it.copy(audioSource = source) }
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

    fun updateSaveHistory(enabled: Boolean) {
        viewModelScope.launch { container.settingsRepository.update { it.copy(saveHistory = enabled) } }
    }

    fun openOverlayPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
        )
    }
}
