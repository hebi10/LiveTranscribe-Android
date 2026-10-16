package com.charles.livecaptionn.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.charles.livecaptionn.speech.LanguageRecommendationTracker
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.charles.livecaptionn.LiveCaptionApp
import com.charles.livecaptionn.MainActivity
import com.charles.livecaptionn.R
import com.charles.livecaptionn.data.applyGlossary
import com.charles.livecaptionn.overlay.OverlayController
import com.charles.livecaptionn.ui.l10n.KoreanUiStrings
import com.charles.livecaptionn.settings.CaptionPlacement
import com.charles.livecaptionn.settings.AudioSource
import com.charles.livecaptionn.settings.RecognitionLanguages
import com.charles.livecaptionn.speech.RecognitionStatus
import com.charles.livecaptionn.speech.SpeechEngine
import com.charles.livecaptionn.speech.SpeechResult
import com.charles.livecaptionn.speech.StreamingSttEngine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class CaptionForegroundService : Service() {
    private var serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: LiveCaptionApp

    private val captionSessionActive = AtomicBoolean(false)

    /** Bumps when the overlay view is attached so combine() re-runs (was skipping while overlay was null). */
    private val overlayReady = MutableStateFlow(0)

    private var speechEngine: SpeechEngine? = null
    /** Owned here until an engine actually starts and takes responsibility for projection cleanup. */
    private var preparedMediaProjection: MediaProjection? = null
    private var overlayController: OverlayController? = null
    private val pauseController = CaptionPauseController()
    private val paused: Boolean get() = pauseController.paused
    @Volatile private var failureMessage: String? = null
    private val lineIdCounter = java.util.concurrent.atomic.AtomicLong(0)
    private var translationQueue: CaptionTranslationQueue<CaptionTranslationRequest>? = null
    private var setupJob: Job? = null
    private var stopping = false
    private var translationReleased = false
    /** Read by capture callbacks; initialized before speech starts. */
    @Volatile private var stableCaptions = true

    private var sessionPlacement = CaptionPlacement.POPUP
    private var currentAudioSource = AudioSource.SYSTEM
    private var languageSamples: Channel<ByteArray>? = null
    private var languageTracker: LanguageRecommendationTracker? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Must happen here, not in startFlow(): if the process was killed while
        // the notification was still showing, Android can deliver ACTION_STOP
        // (from the notification's "Stop" action) as the very first intent to a
        // freshly-created service instance, going straight to stopFlow() without
        // ACTION_START ever running. Initializing here guarantees `app` is set
        // no matter which action arrives first.
        app = application as LiveCaptionApp
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // System restarted our sticky service with a null intent
            // (e.g., after process death). We cannot proceed without an
            // action, so stop cleanly to avoid foreground crash.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START -> {
                // CRITICAL: call startForeground synchronously here, BEFORE
                // any conditional / async work. Android's 5-second deadline
                // after startForegroundService() is enforced strictly (and
                // tighter on slower devices like the Fire tablet, which was
                // crashing during cold-start DataStore reads). Any early-exit
                // path below must therefore use stopForeground + stopSelf
                // instead of raw stopSelf, which previously crashed the app.
                if (stopping || captionSessionActive.get()) return START_NOT_STICKY
                val audioSource = intent.getStringExtra(EXTRA_AUDIO_SOURCE)
                    ?.let { runCatching { AudioSource.valueOf(it) }.getOrNull() }
                    ?: AudioSource.SYSTEM
                currentAudioSource = audioSource
                if (enterForeground(audioSource)) startFlow()
            }
            ACTION_STOP -> {
                if (captionSessionActive.get() || stopping) stopFlow()
                else {
                    // Repeated Stop must not erase a completed split session retained by the app.
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_PAUSE_RESUME -> togglePause()
            ACTION_TOGGLE_MINIMIZE -> toggleMinimized()
            ACTION_ACCEPT_LANGUAGE -> intent.getStringExtra(EXTRA_LANGUAGE_CODE)?.let(::acceptLanguageSuggestion)
            ACTION_DISMISS_LANGUAGE -> {
                val code = intent.getStringExtra(EXTRA_LANGUAGE_CODE)
                    ?: app.container.runtimeStore.state.value.suggestedLanguageCode
                code?.let(::dismissLanguageSuggestion)
            }
        }
        // A stale pane/notification action may recreate the service after capture already ended.
        if (!captionSessionActive.get() && !stopping) stopSelf()
        return START_NOT_STICKY
    }

    private fun enterForeground(audioSource: AudioSource): Boolean {
        val wantsMicrophone = audioSource != AudioSource.SYSTEM
        val hasMicPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        // Android throws a SecurityException from startForeground() itself if
        // FOREGROUND_SERVICE_TYPE_MICROPHONE is requested without RECORD_AUDIO
        // actually granted. MainActivity checks this before the initial launch,
        // but startFlow()'s audioSource-mismatch re-entry can call this again
        // mid-session — fail closed instead of crashing with the OS exception.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when {
                audioSource == AudioSource.SYSTEM -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                wantsMicrophone && hasMicPermission -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                else -> 0
            }
        } else {
            0
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification(), type)
            } else {
                startForeground(NOTIF_ID, buildNotification())
            }
            if (wantsMicrophone && !hasMicPermission) {
                failAndStop("음성 녹음 권한을 허용한 뒤 다시 시작해 주세요.")
                false
            } else true
        } catch (t: Throwable) {
            Log.e("CaptionService", "Unable to enter foreground", t)
            failAndStop("자막 서비스를 시작하지 못했습니다. 권한을 확인한 뒤 다시 시도해 주세요.")
            false
        }
    }

    private fun startFlow() {
        if (stopping || !captionSessionActive.compareAndSet(false, true)) return
        translationReleased = false
        failureMessage = null
        app.container.runtimeStore.update {
            startedCaptionSession(it).copy(running = true, paused = paused, stopping = false,
                recognizing = false, translating = false, captureHint = null, infoMessage = null,
                suggestedLanguageCode = null, changingLanguageCode = null, restartLanguageCode = null,
                status = if (paused) RecognitionStatus.PAUSED else RecognitionStatus.PROCESSING,
                lastError = null, skippedTranslations = 0, skippedSpeechSegments = 0)
        }
        setupJob = serviceScope.launch {
            val settings = app.container.settingsRepository.settingsFlow.first()
            // Placement is owned by this capture session, independent of later settings emissions.
            sessionPlacement = settings.captionPlacement
            app.container.runtimeStore.update { it.copy(sessionPlacement = sessionPlacement) }
            if (sessionPlacement == CaptionPlacement.SPLIT_SCREEN) {
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification())
            }
            if (sessionPlacement == CaptionPlacement.POPUP) {
                if (!Settings.canDrawOverlays(this@CaptionForegroundService)) {
                    Log.e("CaptionService", "Overlay permission missing.")
                    failAndStop("다른 앱 위에 표시 권한을 허용한 뒤 다시 시작해 주세요.")
                    return@launch
                }
                // A new popup always begins with its caption body visible.
                app.container.settingsRepository.update { it.copy(overlayMinimized = false) }
            }
            stableCaptions = settings.stableCaptions
            observeCaptionUpdates()
            app.container.historyRecordingGate.initialize()

            // If the activity sent the wrong audio source in the intent extra
            // (legacy intent, settings changed between launch and service start),
            // re-enter foreground with the correct service type.
            if (settings.audioSource != currentAudioSource) {
                currentAudioSource = settings.audioSource
                if (!enterForeground(currentAudioSource)) return@launch
            }
            val sttLanguageCode = RecognitionLanguages.normalizeMode(settings.sourceLanguageCode)
            val autoDetect = sttLanguageCode == RecognitionLanguages.AUTO

            if (!app.container.offlineModels.isReady()) {
                failAndStop("음성 인식·번역 모델을 먼저 준비해 주세요.")
                return@launch
            }

            app.container.runtimeStore.update {
                it.copy(running = true, paused = paused,
                    status = if (paused) RecognitionStatus.PAUSED else RecognitionStatus.PROCESSING)
            }

            if (!app.container.translationModels.isReady(sttLanguageCode)) {
                failAndStop("음성 인식·번역 모델을 먼저 준비해 주세요.")
                return@launch
            }
            try {
                app.container.translationRepository.prewarm(sttLanguageCode, "ko")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failAndStop("번역 모델을 불러오지 못했습니다. 저장 공간과 모델 준비 상태를 확인해 주세요.")
                return@launch
            }

            if (!autoDetect) {
                val samples = Channel<ByteArray>(
                    capacity = 2,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST
                )
                languageSamples = samples
                languageTracker = LanguageRecommendationTracker(sttLanguageCode)
                serviceScope.launch(Dispatchers.Default) {
                    for (audio in samples) {
                        if (!captionSessionActive.get()) break
                        val detected = app.container.languageIdentifier.detect(audio)
                        val suggestion = languageTracker?.observe(detected)
                        if (suggestion != null && captionSessionActive.get()) {
                            app.container.runtimeStore.update {
                                if (it.stopping || it.changingLanguageCode != null) it
                                else it.copy(suggestedLanguageCode = suggestion)
                            }
                        }
                    }
                }
            }

            translationQueue = CaptionTranslationQueue(
                CoroutineScope(serviceScope.coroutineContext + Dispatchers.IO),
                onFinalDropped = { request ->
                    app.container.runtimeStore.update { recordDroppedTranslation(it, request.lineId) }
                },
                translate = { request ->
                    app.container.runtimeStore.update { it.copy(translating = true) }
                    try {
                        translateSnapshot(request)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        val message = "번역 기록 처리에 실패했습니다. 저장 공간을 확인해 주세요."
                        failureMessage = failureMessage ?: message
                        app.container.runtimeStore.update { it.copy(lastError = message) }
                    } finally {
                        app.container.runtimeStore.update { it.copy(translating = false) }
                    }
                }
            )

            val onSpeechResult: (SpeechResult) -> Unit = onSpeechResult@{ result ->
                val snapshot = resolveCaptionSpeech(result, sttLanguageCode)
                val transcript = snapshot.text
                if (transcript.isBlank() && !result.isFinal) return@onSpeechResult

                val candidateId = lineIdCounter.incrementAndGet()
                var effectiveLineId = candidateId
                app.container.runtimeStore.update {
                    applyTranscriptResult(it, transcript, result.isFinal, candidateId,
                        snapshot.sourceLanguageCode).also { recorded ->
                        effectiveLineId = recorded.transcriptLines.lastOrNull()?.id ?: candidateId
                    }
                }
                queueTranslation(snapshot, effectiveLineId, result.isFinal)
            }

            val mediaProjection = if (currentAudioSource == AudioSource.SYSTEM) {
                val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val data = MediaProjectionHolder.data
                if (data == null) {
                    Log.e("CaptionService", "MediaProjection data missing.")
                    failAndStop("동영상 음성 캡처 권한이 없습니다. 번역 시작을 다시 눌러 주세요.")
                    return@launch
                }
                try {
                    mpManager.getMediaProjection(MediaProjectionHolder.resultCode, data)
                        ?: run {
                            failAndStop("시스템 오디오 캡처를 시작하지 못했습니다. 다시 시도해 주세요.")
                            return@launch
                        }
                } catch (_: SecurityException) {
                    failAndStop("오디오 캡처 권한이 만료되었습니다. 다시 시작해 주세요.")
                    return@launch
                }
            } else {
                null
            }
            preparedMediaProjection = mediaProjection

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (!captionSessionActive.get()) return
                    serviceScope.launch(Dispatchers.Main.immediate) {
                        if (captionSessionActive.get()) {
                            failAndStop("Android에서 음성 캡처가 종료되었습니다. 번역을 다시 시작해 주세요.")
                        }
                    }
                }
            }, Handler(Looper.getMainLooper()))

            val errorSink: (String?) -> Unit = { msg ->
                app.container.runtimeStore.update { withRuntimeError(it, msg) }
            }

            val engine = StreamingSttEngine(
                context = this@CaptionForegroundService,
                audioSource = currentAudioSource,
                mediaProjection = mediaProjection,
                languageCode = sttLanguageCode,
                onSkippedAudio = {
                    app.container.runtimeStore.update { recordSkippedSpeechSegment(it) }
                },
                localSttClient = app.container.localQwenClient,
                scope = serviceScope,
                onResult = onSpeechResult,
                onError = errorSink,
                onHint = { hint -> app.container.runtimeStore.update { it.copy(captureHint = hint) } },
                onRecognitionProcessing = { busy ->
                    app.container.runtimeStore.update { it.copy(recognizing = busy) }
                },
                onUtteranceAudio = if (autoDetect) null else { audio -> languageSamples?.trySend(audio); Unit },
                detectLanguage = if (autoDetect) app.container.languageIdentifier::detect else null,
                onRecognitionLimit = {
                    app.container.runtimeStore.update {
                        recordSkippedSpeechSegment(it).copy(
                            infoMessage = "인식 결과가 처리 길이 한도에 가까워 해당 구간을 생략했습니다."
                        )
                    }
                }
            )
            speechEngine = engine
            observeEngineStatus(engine.status)
            pauseController.attach(engine)

            if (sessionPlacement == CaptionPlacement.POPUP) showOverlay()
        }
    }

    private fun observeEngineStatus(statusFlow: StateFlow<RecognitionStatus>) {
        serviceScope.launch {
            statusFlow.collectLatest { status ->
                if (!stopping) {
                    app.container.runtimeStore.update {
                        it.copy(status = if (paused && status != RecognitionStatus.ERROR) RecognitionStatus.PAUSED else status)
                    }
                    if (status == RecognitionStatus.ERROR) {
                        failAndStop(app.container.runtimeStore.state.value.lastError
                            ?: "음성 인식을 시작하지 못했습니다. 권한과 음성 모델을 확인해 주세요.")
                    }
                }
            }
        }
    }

    /**
     * Keep live capture settings observed for every placement. When present, the popup
     * uses the same stream so rapid runtime/settings changes cannot render out of order.
     */
    private fun observeCaptionUpdates() {
        serviceScope.launch {
            combine(
                app.container.runtimeStore.state,
                app.container.settingsRepository.settingsFlow,
                overlayReady
            ) { runtime, settings, _ -> Pair(runtime, settings) }
                .collectLatest { (runtime, settings) ->
                    stableCaptions = settings.stableCaptions
                    val overlay = overlayController ?: return@collectLatest
                    val ui = buildOverlayUiState(runtime, settings)
                    overlay.update(ui)
                }
        }
    }

    private fun queueTranslation(snapshot: CaptionSpeechSnapshot, lineId: Long, isFinal: Boolean = false) {
        if (snapshot.text.isBlank() || !shouldTranslateCaption(isFinal, stableCaptions)) return
        val request = snapshot.request(lineId, isFinal, app.container.historyRecordingGate.captureConsent())
        if (isFinal) translationQueue?.submitFinal(request)
        else translationQueue?.submitPartial(request)
    }

    private suspend fun translateSnapshot(request: CaptionTranslationRequest) {
        val captionSettings = app.container.settingsRepository.settingsFlow.first()
        // A pending preview may have been queued before the user enabled stability.
        if (!shouldTranslateCaption(request.isFinal, captionSettings.stableCaptions)) return
        val translated = try {
            translateCaptionRequest(request, app.container.translationRepository)
        } catch (_: TimeoutCancellationException) {
            val message = "번역 시간이 초과되어 일부 문장을 번역하거나 저장하지 못했습니다."
            if (request.isFinal) failureMessage = failureMessage ?: message
            app.container.runtimeStore.update { it.copy(lastError = message) }
            return
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val message = "일부 문장을 번역하거나 저장하지 못했습니다. 번역 모델을 확인해 주세요."
            if (request.isFinal) failureMessage = failureMessage ?: message
            app.container.runtimeStore.update { it.copy(lastError = message) }
            return
        }
        if (translated.isBlank()) return
        val translatedWithGlossary = applyGlossary(translated, app.container.glossary.list())
        app.container.runtimeStore.update {
            applyTranscriptTranslation(
                runtime = it,
                lineId = request.lineId,
                translatedText = translatedWithGlossary,
                isFinalTranslation = request.isFinal
            )
        }
        if (request.isFinal) {
            app.container.historyRecordingGate.recordIfAllowed(request.historyConsent) {
                app.container.transcriptHistory.add(
                    captionHistoryEntry(request, translatedWithGlossary)
                )
            }
        }
    }

    private fun showOverlay() {
        if (sessionPlacement != CaptionPlacement.POPUP || overlayController != null ||
            stopping || !captionSessionActive.get()) return
        serviceScope.launch {
            val settings = app.container.settingsRepository.settingsFlow.first()
            if (stopping || !captionSessionActive.get() || sessionPlacement != CaptionPlacement.POPUP) return@launch
            try {
                overlayController = OverlayController(
                    context = this@CaptionForegroundService,
                    scope = serviceScope,
                    settingsRepository = app.container.settingsRepository,
                    onPauseResume = { togglePause() },
                    onClose = { stopFlow() },
                    onToggleMinimize = { toggleMinimized() },
                    onAcceptLanguageSuggestion = ::acceptLanguageSuggestion,
                    onDismissLanguageSuggestion = ::dismissLanguageSuggestion,
                    uiStrings = { KoreanUiStrings.values }
                ).apply {
                    show(settings.overlayX, settings.overlayY, settings.overlayWidthDp, settings.overlayHeightDp)
                }
            } catch (_: RuntimeException) {
                failAndStop("자막 창을 표시할 수 없습니다. 다른 앱 위에 표시 권한을 확인해 주세요.")
                return@launch
            }
            val runtime = app.container.runtimeStore.state.value
            overlayController?.update(buildOverlayUiState(runtime, settings))
            overlayReady.value = overlayReady.value + 1
        }
    }

    private fun togglePause() {
        if (!captionSessionActive.get() || stopping) return
        pauseController.toggle()
        app.container.runtimeStore.update {
            it.copy(
                paused = paused,
                status = when {
                    paused -> RecognitionStatus.PAUSED
                    pauseController.hasStarted -> RecognitionStatus.LISTENING
                    else -> RecognitionStatus.PROCESSING
                }
            )
        }
    }

    private fun toggleMinimized() {
        if (sessionPlacement != CaptionPlacement.POPUP || !captionSessionActive.get() || stopping) return
        serviceScope.launch {
            app.container.settingsRepository.update { it.copy(overlayMinimized = !it.overlayMinimized) }
        }
    }

    private fun dismissLanguageSuggestion(code: String) {
        if (!captionSessionActive.get() || stopping) return
        languageTracker?.dismiss(code)
        app.container.runtimeStore.update {
            if (it.suggestedLanguageCode == code) it.copy(suggestedLanguageCode = null) else it
        }
    }

    private fun acceptLanguageSuggestion(code: String) {
        if (stopping || !captionSessionActive.get()) return
        val store = app.container.runtimeStore
        if (!store.claimLanguageSuggestion(code)) return
        serviceScope.launch {
            applyClaimedLanguageSuggestion(
                store = store,
                code = code,
                writeLanguage = { language ->
                    app.container.settingsRepository.update { it.copy(sourceLanguageCode = language) }
                },
                stopSession = { stopFlow() },
                openMain = {
                    // Split readers stay in their pane and use its explicit restart action.
                    if (sessionPlacement == CaptionPlacement.POPUP) startActivity(Intent(this@CaptionForegroundService, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra(MainActivity.EXTRA_OPEN_MAIN, true)
                    })
                },
                onNavigationFailure = { message ->
                    failureMessage = failureMessage ?: message
                    Toast.makeText(this@CaptionForegroundService, message, Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    private fun stopFlow(completionMessage: String? = null) {
        finishSession(completionMessage = completionMessage)
    }

    private fun failAndStop(message: String) {
        finishSession(error = message)
    }

    private fun finishSession(error: String? = null, completionMessage: String? = null) {
        if (stopping) return
        stopping = true
        app.container.runtimeStore.update { stoppingCaptionSession(it, error) }
        if (error != null) failureMessage = error
        captionSessionActive.set(false)
        serviceScope.launch {
            setupJob?.cancelAndJoin()
            setupJob = null
            languageSamples?.close()
            languageSamples = null
            languageTracker = null
            try {
                speechEngine?.stop()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failureMessage = failureMessage ?: "음성 인식 종료 중 오류가 발생했습니다."
            }
            speechEngine = null
            releaseUnstartedProjection()
            val drained = translationQueue?.finish() ?: true
            translationQueue = null
            releaseTranslation()
            overlayController?.hide()
            overlayController = null
            overlayReady.value = 0
            MediaProjectionHolder.clear()
            app.container.runtimeStore.update {
                completedCaptionSession(failureMessage, error, drained, completionMessage,
                    skippedTranslations = it.skippedTranslations,
                    skippedSpeechSegments = it.skippedSpeechSegments,
                    restartLanguageCode = it.restartLanguageCode,
                    sessionPlacement = sessionPlacement, retainedLines = it.transcriptLines, sessionId = it.sessionId)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        captionSessionActive.set(false)
        languageSamples?.close()
        languageSamples = null
        translationQueue?.cancel()
        translationQueue = null
        releaseTranslation()
        overlayController?.hide()
        overlayController = null
        val engine = speechEngine
        speechEngine = null
        releaseUnstartedProjection()
        if (engine != null) {
            serviceScope.launch(NonCancellable) {
                stopSpeechEngineSafely({ engine.stop() }) {
                    app.container.runtimeStore.update { runtime ->
                        runtime.copy(status = RecognitionStatus.ERROR,
                            lastError = runtime.lastError ?: "음성 인식 종료 중 오류가 발생했습니다.")
                    }
                }
            }
        }
        serviceScope.cancel()
        MediaProjectionHolder.clear()
        app.container.runtimeStore.update {
            if (it.running) completedCaptionSession(failureMessage ?: it.lastError, null, true,
                skippedTranslations = it.skippedTranslations,
                skippedSpeechSegments = it.skippedSpeechSegments,
                restartLanguageCode = it.restartLanguageCode,
                sessionPlacement = sessionPlacement, retainedLines = it.transcriptLines, sessionId = it.sessionId) else it
        }
        super.onDestroy()
    }

    private fun releaseUnstartedProjection() {
        val projection = preparedMediaProjection
        preparedMediaProjection = null
        // An engine that never started has no owning capture worker/finally block.
        if (!pauseController.hasStarted) {
            try { projection?.stop() } catch (_: RuntimeException) { }
        }
    }

    private fun releaseTranslation() {
        if (translationReleased) return
        translationReleased = true
        app.container.translationRepository.close()
    }

    private fun buildNotification(): Notification {
        createNotificationChannel()
        val openIntent = Intent(this, MainActivity::class.java).apply {
            if (sessionPlacement == CaptionPlacement.SPLIT_SCREEN) putExtra(MainActivity.EXTRA_OPEN_CAPTIONS, true)
        }
        val pending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pauseIntent = PendingIntent.getService(
            this, 2,
            Intent(this, CaptionForegroundService::class.java).apply { action = ACTION_PAUSE_RESUME },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 3,
            Intent(this, CaptionForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val uiStrings = KoreanUiStrings.values
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(uiStrings["Captioning in progress"])
            .setContentText(uiStrings["Listening and translating speech."])
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pending)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, uiStrings["Pause/Resume"], pauseIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, uiStrings["Stop"], stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "caption.action.START"
        const val ACTION_STOP = "caption.action.STOP"
        const val ACTION_PAUSE_RESUME = "caption.action.PAUSE_RESUME"
        const val ACTION_TOGGLE_MINIMIZE = "caption.action.TOGGLE_MINIMIZE"
        const val ACTION_ACCEPT_LANGUAGE = "caption.action.ACCEPT_LANGUAGE"
        const val ACTION_DISMISS_LANGUAGE = "caption.action.DISMISS_LANGUAGE"
        const val EXTRA_LANGUAGE_CODE = "caption.extra.LANGUAGE_CODE"
        const val EXTRA_AUDIO_SOURCE = "caption.extra.AUDIO_SOURCE"

        private const val CHANNEL_ID = "caption_channel"
        private const val NOTIF_ID = 2001
                    }
}

