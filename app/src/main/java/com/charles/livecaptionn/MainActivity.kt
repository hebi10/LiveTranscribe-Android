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
import com.charles.livecaptionn.ui.HistoryScreen
import com.charles.livecaptionn.ui.MainScreen
import com.charles.livecaptionn.ui.MainViewModel
import com.charles.livecaptionn.ui.MainViewModelFactory
import com.charles.livecaptionn.ui.l10n.UiStringsProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var showHistory by mutableStateOf(false)

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCaptioning()
            else setError("영상 음성과 마이크 인식 모두 음성 녹음 권한이 필요합니다.")
        }

    private val mediaProjectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                MediaProjectionHolder.set(result.resultCode, result.data!!.clone() as Intent)
                startCaptionService(AudioSource.SYSTEM)
            } else {
                setError("시스템 오디오 캡처 권한이 허용되지 않았습니다.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as LiveCaptionApp
        setContent {
            val vm: MainViewModel = viewModel(factory = MainViewModelFactory(app.container, application))
            UiStringsProvider {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        primary = Color(0xFFEAEAEA),
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
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun requestMicrophonePermission() {
        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startCaptioning() {
        val app = application as LiveCaptionApp
        lifecycleScope.launch {
            if (!Settings.canDrawOverlays(this@MainActivity)) {
                setError("다른 앱 위에 표시 권한을 먼저 허용해 주세요.")
                return@launch
            }
            val settings = app.container.settingsRepository.settingsFlow.first()
            val granted = ContextCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestMicrophonePermission()
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
            when (settings.audioSource) {
                AudioSource.SYSTEM -> {
                    val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val capture = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                    } else {
                        manager.createScreenCaptureIntent()
                    }
                    mediaProjectionLauncher.launch(capture)
                }
                AudioSource.MIC -> startCaptionService(AudioSource.MIC)
            }
        }
    }

    private fun setError(message: String) {
        (application as LiveCaptionApp).container.runtimeStore.update {
            it.copy(running = false, status = RecognitionStatus.ERROR, lastError = message)
        }
    }

    private fun startCaptionService(source: AudioSource) {
        val intent = Intent(this, CaptionForegroundService::class.java).apply {
            action = CaptionForegroundService.ACTION_START
            putExtra(CaptionForegroundService.EXTRA_AUDIO_SOURCE, source.name)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    private fun stopCaptionService() {
        startService(Intent(this, CaptionForegroundService::class.java).apply {
            action = CaptionForegroundService.ACTION_STOP
        })
    }
}
