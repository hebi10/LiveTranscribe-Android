package com.charles.livecaptionn

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.ComponentName
import android.content.res.Configuration
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelProvider
import com.charles.livecaptionn.service.CaptionForegroundService
import com.charles.livecaptionn.service.MediaProjectionHolder
import com.charles.livecaptionn.service.preparedCaptionStart
import com.charles.livecaptionn.settings.AudioSource
import com.charles.livecaptionn.settings.CaptionPlacement
import com.charles.livecaptionn.speech.RecognitionStatus
import com.charles.livecaptionn.ui.AudioPermissionAction
import com.charles.livecaptionn.ui.audioPermissionAction
import com.charles.livecaptionn.ui.CaptionStartGate
import com.charles.livecaptionn.ui.HistoryScreen
import com.charles.livecaptionn.ui.CaptionScreen
import com.charles.livecaptionn.ui.SplitAppChoice
import com.charles.livecaptionn.ui.SplitAppPicker
import com.charles.livecaptionn.ui.SplitScreenHelp
import com.charles.livecaptionn.ui.canRequestAdjacentLaunch
import com.charles.livecaptionn.ui.loadSplitAppChoices
import com.charles.livecaptionn.ui.MainScreen
import com.charles.livecaptionn.ui.MainViewModel
import com.charles.livecaptionn.ui.MainViewModelFactory
import com.charles.livecaptionn.ui.l10n.UiStringsProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var showHistory by mutableStateOf(false)
    private var showCaptions by mutableStateOf(false)
    private var showAppPicker by mutableStateOf(false)
    private var loadingApps by mutableStateOf(false)
    private var appChoices by mutableStateOf<List<SplitAppChoice>>(emptyList())
    private var appPickerError by mutableStateOf<String?>(null)
    private var showSplitHelp by mutableStateOf(false)
    private var splitHelpMessage by mutableStateOf<String?>(null)
    private var companionLaunchPending = false
    private var startGate = CaptionStartGate()
    private var startPending by mutableStateOf(false)
    private var completedAudioPermissionRequest = false
    private var audioPermissionSettingsRequired by mutableStateOf(false)
    private lateinit var mainViewModel: MainViewModel

    private fun syncStartPending() { startPending = startGate.busy }

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            completedAudioPermissionRequest = true
            getPreferences(MODE_PRIVATE).edit().putBoolean("audio_permission_requested", true).apply()
            refreshAudioPermissionAction()
            if (::mainViewModel.isInitialized) mainViewModel.refreshPermissionState()
            val resumeStart = startGate.audioPermissionResult(granted)
            syncStartPending()
            if (resumeStart) continueStartCaptioning()
            else if (!granted) setError(if (audioPermissionSettingsRequired)
                "음성 녹음 권한이 거부되었습니다. 앱 설정에서 마이크 권한을 허용해 주세요."
                else "영상 음성과 마이크 인식 모두 음성 녹음 권한이 필요합니다.")
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
        completedAudioPermissionRequest = savedInstanceState?.getBoolean("audio_permission_requested") == true ||
            getPreferences(MODE_PRIVATE).getBoolean("audio_permission_requested", false)
        refreshAudioPermissionAction()
        showHistory = savedInstanceState?.getBoolean("show_history") ?: false
        showCaptions = savedInstanceState?.getBoolean("show_captions") ?: false
        companionLaunchPending = savedInstanceState?.getBoolean("companion_launch_pending") ?: false
        startGate = CaptionStartGate(savedInstanceState?.getString("start_stage"))
        consumeOpenMainIntent(intent)
        syncStartPending()
        val app = application as LiveCaptionApp
        mainViewModel = ViewModelProvider(this, MainViewModelFactory(app.container, application))[MainViewModel::class.java]
        lifecycleScope.launch {
            app.container.runtimeStore.state.collect { runtime ->
                if (runtime.running || runtime.status == RecognitionStatus.ERROR) {
                    startGate.cancel()
                    syncStartPending()
                }
            }
        }
        setContent {
            val vm = mainViewModel
            val ui by vm.state.collectAsStateWithLifecycle()
            val captionStateHolder = rememberSaveableStateHolder()
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
                            BackHandler(enabled = showHistory || showCaptions) {
                                if (showHistory) showHistory = false else showCaptions = false
                            }
                            if (showCaptions && !showHistory) {
                                captionStateHolder.SaveableStateProvider("captions") {
                                CaptionScreen(
                                    ui = ui,
                                    onPauseResume = { sendCaptionAction(CaptionForegroundService.ACTION_PAUSE_RESUME) },
                                    onStop = { stopCaptionService() },
                                    onSettings = { showCaptions = false },
                                    onChooseApp = { chooseCompanionApp() },
                                    onSplitHelp = { splitHelpMessage = null; showSplitHelp = true },
                                    onShowOriginalChange = vm::updateShowOriginal,
                                    onTextSizeChange = vm::updateTextSize,
                                    onDisplayModeChange = vm::updateCaptionDisplayMode,
                                    onStart = { startCaptioning() },
                                    onAcceptLanguage = { code -> sendCaptionAction(
                                        CaptionForegroundService.ACTION_ACCEPT_LANGUAGE, code) },
                                    onDismissLanguage = { sendCaptionAction(CaptionForegroundService.ACTION_DISMISS_LANGUAGE) },
                                    startPending = startPending,
                                    modifier = Modifier.weight(1f)
                                )
                                }
                            } else if (showHistory) {
                                HistoryScreen(
                                    historyStore = app.container.transcriptHistory,
                                    historySavingEnabled = vm.historySavingEnabled,
                                    onHistorySavingChange = vm::updateSaveHistory,
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
                                    onOpenCaptions = { showHistory = false; showCaptions = true },
                                    onCancelModelPreparation = vm::cancelModelPreparation,
                                    startPending = startPending,
                                    audioPermissionSettingsRequired = audioPermissionSettingsRequired,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        if (showAppPicker) SplitAppPicker(
                            apps = appChoices,
                            loading = loadingApps,
                            error = appPickerError,
                            onSelect = ::launchCompanionApp,
                            onDismiss = { showAppPicker = false }
                        )
                        if (showSplitHelp) SplitScreenHelp(
                            message = splitHelpMessage,
                            onDismiss = { showSplitHelp = false }
                        )
                    }
                }
            }
        }
        if (startGate.checking) continueStartCaptioning()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeOpenMainIntent(intent)
    }

    private fun consumeOpenMainIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_CAPTIONS, false) == true) {
            intent.removeExtra(EXTRA_OPEN_CAPTIONS)
            showCaptions = true
            showHistory = false
        }
        if (intent?.getBooleanExtra(EXTRA_OPEN_MAIN, false) != true) return
        intent.removeExtra(EXTRA_OPEN_MAIN)
        showHistory = false
        showCaptions = false
        // Cancel the existing gate, retaining canceled pending permission results until delivered.
        startGate.cancel()
        syncStartPending()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("show_history", showHistory)
        outState.putBoolean("show_captions", showCaptions)
        outState.putBoolean("companion_launch_pending", companionLaunchPending)
        outState.putString("start_stage", startGate.savedStage)
        outState.putBoolean("audio_permission_requested", completedAudioPermissionRequest)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        refreshAudioPermissionAction()
        if (companionLaunchPending) {
            companionLaunchPending = false
            if (!isInMultiWindowMode) {
                splitHelpMessage = "자동으로 화면이 나뉘지 않았다면 최근 앱에서 분할 화면을 선택해 주세요."
                showSplitHelp = true
            }
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        if (isInMultiWindowMode) {
            companionLaunchPending = false
            showSplitHelp = false
        }
    }

    private fun currentAudioPermissionAction(): AudioPermissionAction = audioPermissionAction(
        granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        completedRequest = completedAudioPermissionRequest,
        showRationale = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
    )

    private fun refreshAudioPermissionAction() {
        audioPermissionSettingsRequired = currentAudioPermissionAction() == AudioPermissionAction.OPEN_SETTINGS
    }

    private fun requestMicrophonePermission(forStart: Boolean = false) {
        if (forStart && !startGate.checking || !forStart && startGate.busy) return
        refreshAudioPermissionAction()
        if (audioPermissionSettingsRequired) {
            // A settings return only refreshes permission state; starting always needs another tap.
            startGate.cancel()
            syncStartPending()
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (_: RuntimeException) {
                setError("앱 설정을 열 수 없습니다. Android 설정에서 마이크 권한을 허용해 주세요.")
            }
            return
        }
        if (!startGate.requestAudioPermission(forStart)) return
        syncStartPending()
        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startCaptioning() {
        val container = (application as LiveCaptionApp).container
        if (container.runtimeStore.state.value.running || !startGate.begin()) return
        container.runtimeStore.update(::preparedCaptionStart)
        syncStartPending()
        continueStartCaptioning()
    }

    private fun continueStartCaptioning() {
        val app = application as LiveCaptionApp
        lifecycleScope.launch {
            if (!startGate.busy) return@launch
            val settings = app.container.settingsRepository.settingsFlow.first()
            if (settings.captionPlacement == CaptionPlacement.POPUP &&
                !Settings.canDrawOverlays(this@MainActivity)) {
                setError("다른 앱 위에 표시 권한을 먼저 허용해 주세요.")
                return@launch
            }
            if (!startGate.busy) return@launch
            val granted = ContextCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestMicrophonePermission(forStart = true)
                return@launch
            }
            if (!app.container.offlineModels.isReady() ||
                !app.container.translationModels.isReady(settings.sourceLanguageCode)) {
                mainViewModel.refreshModelPreparationState()
                setError("음성·번역 모델 준비가 필요합니다. 앱의 '모델 다운로드'를 먼저 완료해 주세요.")
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
        val container = (application as LiveCaptionApp).container
        // Permission results may arrive after files changed; never trust a stale READY screen.
        if (!container.offlineModels.isReady() ||
            !container.translationModels.isReady(mainViewModel.state.value.settings.sourceLanguageCode)) {
            MediaProjectionHolder.clear()
            mainViewModel.refreshModelPreparationState()
            setError("음성·번역 모델 준비가 필요합니다. 앱의 '모델 다운로드'를 먼저 완료해 주세요.")
            return
        }
        startGate.serviceRequested()
        syncStartPending()
        val intent = Intent(this, CaptionForegroundService::class.java).apply {
            action = CaptionForegroundService.ACTION_START
            putExtra(CaptionForegroundService.EXTRA_AUDIO_SOURCE, source.name)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
            if (mainViewModel.state.value.settings.captionPlacement == CaptionPlacement.SPLIT_SCREEN) {
                showCaptions = true
                showHistory = false
            }
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
    private fun sendCaptionAction(action: String, languageCode: String? = null) {
        val runtime = (application as LiveCaptionApp).container.runtimeStore.state.value
        if (!runtime.running || runtime.stopping) return
        startService(Intent(this, CaptionForegroundService::class.java).apply {
            this.action = action
            languageCode?.let { putExtra(CaptionForegroundService.EXTRA_LANGUAGE_CODE, it) }
        })
    }

    private fun chooseCompanionApp() {
        if (!canRequestAdjacentLaunch(Build.VERSION.SDK_INT, isInMultiWindowMode)) {
            splitHelpMessage = "이 Android 버전에서는 최근 앱에서 먼저 분할 화면을 열어 주세요."
            showSplitHelp = true
            return
        }
        showAppPicker = true
        loadingApps = true
        appPickerError = null
        lifecycleScope.launch {
            try {
                appChoices = withContext(Dispatchers.IO) { loadSplitAppChoices(this@MainActivity) }
            } catch (_: RuntimeException) {
                appPickerError = "앱 목록을 불러오지 못했습니다. 최근 앱에서 직접 분할 화면을 열 수 있습니다."
            } finally {
                loadingApps = false
            }
        }
    }

    private fun launchCompanionApp(app: SplitAppChoice) {
        showAppPicker = false
        companionLaunchPending = true
        try {
            startActivity(Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                component = ComponentName(app.packageName, app.activityName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            })
        } catch (_: RuntimeException) {
            companionLaunchPending = false
            splitHelpMessage = "선택한 앱을 열지 못했습니다. 최근 앱에서 직접 분할 화면을 열어 주세요."
            showSplitHelp = true
        }
    }

    companion object {
        const val EXTRA_OPEN_MAIN = "com.charles.livecaptionn.OPEN_MAIN"
        const val EXTRA_OPEN_CAPTIONS = "com.charles.livecaptionn.OPEN_CAPTIONS"
    }

}

