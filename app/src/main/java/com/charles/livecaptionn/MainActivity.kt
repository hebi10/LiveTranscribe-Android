package com.charles.livecaptionn

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.charles.livecaptionn.service.CaptionForegroundService
import com.charles.livecaptionn.service.MediaProjectionHolder
import com.charles.livecaptionn.settings.AudioSource
import com.charles.livecaptionn.speech.RecognitionStatus
import com.charles.livecaptionn.ui.CaptionStartGate
import com.charles.livecaptionn.ui.HistoryScreen
import com.charles.livecaptionn.ui.MainScreen
import com.charles.livecaptionn.ui.MainViewModel
import com.charles.livecaptionn.ui.MainViewModelFactory
import com.charles.livecaptionn.ui.l10n.UiStringsProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var showHistory by mutableStateOf(false)
    private var startGate = CaptionStartGate()
    private var startPending by mutableStateOf(false)

    private fun syncStartPending() { startPending = startGate.busy }

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val resumeStart = startGate.audioPermissionResult(granted)
            syncStartPending()
            if (resumeStart) continueStartCaptioning()
            else if (!granted) setError("영상 음성과 마이크 인식 모두 음성 녹음 권한이 필요합니다.")
        }

    private val mediaProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val granted = result.resultCode == Activity.RESULT_OK && result.data != null
            val requested = startGate.busy
            val start = startGate.projectionResult(granted)
            syncStartPending()
            if (start) {
                MediaProjectionHolder.set(result.resultCode, result.data!!.clone() as Intent)
                startCaptionService(AudioSource.SYSTEM)
            } else if (requested && !granted) {
                setError("시스템 오디오 캡처 권한이 허용되지 않았습니다.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showHistory = savedInstanceState?.getBoolean("show_history") ?: false
        startGate = CaptionStartGate(savedInstanceState?.getString("start_stage"))
        syncStartPending()
        val app = application as LiveCaptionApp
        lifecycleScope.launch {
            app.container.runtimeStore.state.collect { runtime ->
                if (runtime.running || runtime.status == RecognitionStatus.ERROR) {
                    startGate.cancel()
                    syncStartPending()
                }
            }
        }
        setContent {
            val vm: MainViewModel = viewModel(factory = MainViewModelFactory(app.container, application))
            UiStringsProvider {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        primary = Color(0xFFEAEAEA),
                        primaryContainer = Color(0xFF333333),
                        onPrimaryContainer = Color(0xFFF5F5F5),
                        secondary = Color(0xFFCCCCCC),
                        onSecondary = Color(0xFF111111),
                        secondaryContainer = Color(0xFF333333),
                        onSecondaryContainer = Color(0xFFF5F5F5),
                        tertiary = Color(0xFFCCCCCC),
                        onTertiary = Color(0xFF111111),
                        tertiaryContainer = Color(0xFF333333),
                        onTertiaryContainer = Color(0xFFF5F5F5),
                        error = Color(0xFFE0E0E0),
                        onError = Color(0xFF111111),
                        errorContainer = Color(0xFF3A3A3A),
                        onErrorContainer = Color(0xFFF5F5F5),
                        outline = Color(0xFF999999),
                        outlineVariant = Color(0xFF555555),
                        inverseSurface = Color(0xFFE0E0E0),
                        inverseOnSurface = Color(0xFF111111),
                        inversePrimary = Color(0xFF333333),
                        surfaceTint = Color(0xFFEAEAEA),
                        surfaceDim = Color(0xFF111111),
                        surfaceBright = Color(0xFF3A3A3A),
                        surfaceContainerLowest = Color(0xFF0C0C0C),
                        surfaceContainerLow = Color(0xFF171717),
                        surfaceContainer = Color(0xFF1C1C1C),
                        surfaceContainerHigh = Color(0xFF242424),
                        surfaceContainerHighest = Color(0xFF303030),
                        onPrimary = Color(0xFF111111),
                        background = Color(0xFF111111),
                        onBackground = Color(0xFFF5F5F5),
                        surface = Color(0xFF1C1C1C),
                        onSurface = Color(0xFFF5F5F5),
                        surfaceVariant = Color(0xFF292929),
                        onSurfaceVariant = Color(0xFFCCCCCC)
                    )
                ) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                            BackHandler(enabled = showHistory) { showHistory = false }
                            if (showHistory) {
                                HistoryScreen(
                                    historyStore = app.container.transcriptHistory,
                                    onBack = { showHistory = false },
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                MainScreen(
                                    viewModel = vm,
                                    onRequestAudioPermission = { requestMicrophonePermission() },
                                    onOpenOverlaySettings = { vm.openOverlayPermissionSettings(this@MainActivity) },
                                    onStart = { startCaptioning() },
                                    onStop = { stopCaptionService() },
                                    onHistory = { showHistory = true },
                                    startPending = startPending,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
        if (startGate.checking) continueStartCaptioning()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("show_history", showHistory)
        outState.putString("start_stage", startGate.savedStage)
        super.onSaveInstanceState(outState)
    }

    private fun requestMicrophonePermission(forStart: Boolean = false) {
        if (!startGate.requestAudioPermission(forStart)) return
        syncStartPending()
        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startCaptioning() {
        val container = (application as LiveCaptionApp).container
        if (container.runtimeStore.state.value.running || !startGate.begin()) return
        container.runtimeStore.update { it.copy(status = RecognitionStatus.IDLE, lastError = null) }
        syncStartPending()
        continueStartCaptioning()
    }

    private fun continueStartCaptioning() {
        val app = application as LiveCaptionApp
        lifecycleScope.launch {
            if (!startGate.busy) return@launch
            if (!Settings.canDrawOverlays(this@MainActivity)) {
                setError("다른 앱 위에 표시 권한을 먼저 허용해 주세요.")
                return@launch
            }
            val settings = app.container.settingsRepository.settingsFlow.first()
            if (!startGate.busy) return@launch
            val granted = ContextCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestMicrophonePermission(forStart = true)
                return@launch
            }
            if (app.container.voskRegistry.installedModelFor(settings.sourceLanguageCode) == null) {
                setError("음성 모델이 없습니다. 모델이 포함된 APK를 설치해 주세요.")
                return@launch
            }
            if (!app.container.translationModels.isReady(settings.sourceLanguageCode)) {
                setError("번역 모델 준비가 필요합니다. 앱의 '번역 모델 준비'를 먼저 실행해 주세요.")
                return@launch
            }
            if (!startGate.busy) return@launch
            when (settings.audioSource) {
                AudioSource.SYSTEM -> {
                    val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val capture = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                    } else {
                        manager.createScreenCaptureIntent()
                    }
                    startGate.awaitProjection()
                    syncStartPending()
                    mediaProjectionLauncher.launch(capture)
                }
                AudioSource.MIC -> startCaptionService(AudioSource.MIC)
            }
        }
    }

    private fun setError(message: String) {
        startGate.cancel()
        syncStartPending()
        (application as LiveCaptionApp).container.runtimeStore.update {
            it.copy(running = false, status = RecognitionStatus.ERROR, lastError = message)
        }
    }

    private fun startCaptionService(source: AudioSource) {
        startGate.serviceRequested()
        syncStartPending()
        val intent = Intent(this, CaptionForegroundService::class.java).apply {
            action = CaptionForegroundService.ACTION_START
            putExtra(CaptionForegroundService.EXTRA_AUDIO_SOURCE, source.name)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        } catch (_: RuntimeException) {
            MediaProjectionHolder.clear()
            setError("자막 서비스를 시작할 수 없습니다. 앱을 화면에 연 뒤 다시 시도해 주세요.")
        }
    }

    private fun stopCaptionService() {
        val servicePending = startGate.servicePending
        startGate.cancel()
        syncStartPending()
        if (!servicePending && !(application as LiveCaptionApp).container.runtimeStore.state.value.running) return
        startService(Intent(this, CaptionForegroundService::class.java).apply {
            action = CaptionForegroundService.ACTION_STOP
        })
    }
}

