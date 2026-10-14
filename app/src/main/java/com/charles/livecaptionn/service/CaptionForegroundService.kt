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
import kotlinx.coroutines.channels.Channel
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.charles.livecaptionn.LiveCaptionApp
import com.charles.livecaptionn.MainActivity
import com.charles.livecaptionn.R
import com.charles.livecaptionn.data.TranscriptEntry
import com.charles.livecaptionn.data.applyGlossary
import com.charles.livecaptionn.overlay.OverlayController
import com.charles.livecaptionn.ui.l10n.KoreanUiStrings
import com.charles.livecaptionn.overlay.OverlayUiState
import com.charles.livecaptionn.settings.AudioSource
import com.charles.livecaptionn.settings.CaptionSettings
import com.charles.livecaptionn.speech.RecognitionStatus
import com.charles.livecaptionn.speech.SpeechEngine
import com.charles.livecaptionn.speech.SpeechResult
import com.charles.livecaptionn.speech.StreamingSttEngine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentLinkedQueue

@OptIn(FlowPreview::class)
class CaptionForegroundService : Service() {
    private var serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: LiveCaptionApp

    private val captionSessionActive = AtomicBoolean(false)

    /** Bumps when the overlay view is attached so combine() re-runs (was skipping while overlay was null). */
    private val overlayReady = MutableStateFlow(0)

    private var speechEngine: SpeechEngine? = null
    private var overlayController: OverlayController? = null
    private var paused = false
    private var failureMessage: String? = null
    private var bufferedText = ""
    private val lineIdCounter = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile
    private var bufferedLineId: Long = NO_LINE_ID
    @Volatile
    private var historyOnNextTranslate = false
    private val pendingFinalTranslations = ConcurrentLinkedQueue<TranslationRequest>()

    /** Mic partials arrive faster than 400ms; cancel+restart jobs never finish. Debounce coalesces to one translate. */
    private val translateRequests = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

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
                val audioSource = intent.getStringExtra(EXTRA_AUDIO_SOURCE)
                    ?.let { runCatching { AudioSource.valueOf(it) }.getOrNull() }
                    ?: AudioSource.SYSTEM
                currentAudioSource = audioSource
                if (enterForeground(audioSource)) startFlow()
            }
            ACTION_STOP -> stopFlow()
            ACTION_PAUSE_RESUME -> togglePause()
            ACTION_TOGGLE_MINIMIZE -> toggleMinimized()
        }
        return START_STICKY
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
                failAndStop("Microphone permission not granted. Allow microphone access and try again.")
                false
            } else true
        } catch (t: Throwable) {
            Log.e("CaptionService", "Unable to enter foreground", t)
            failAndStop("Captioning could not start: ${t.message ?: "foreground service permission denied"}")
            false
        }
    }

    private fun startFlow() {
        if (!captionSessionActive.compareAndSet(false, true)) return
        failureMessage = null
        if (!Settings.canDrawOverlays(this)) {
            Log.e("CaptionService", "Overlay permission missing.")
            failAndStop("Overlay permission is required. Enable it in system settings and try again.")
            return
        }

        serviceScope.launch {
            // Always start a fresh session un-minimized; stale overlayMinimized=true from a
            // prior run was hiding the caption body entirely.
            app.container.settingsRepository.update { it.copy(overlayMinimized = false) }
            val settings = app.container.settingsRepository.settingsFlow.first()

            // If the activity sent the wrong audio source in the intent extra
            // (legacy intent, settings changed between launch and service start),
            // re-enter foreground with the correct service type.
            if (settings.audioSource != currentAudioSource) {
                currentAudioSource = settings.audioSource
                if (!enterForeground(currentAudioSource)) return@launch
            }
            val sttLanguageCode = settings.sourceLanguageCode.ifBlank { "en" }

            val model = app.container.voskRegistry.installedModelFor(sttLanguageCode)
            if (model == null) {
                failAndStop("음성 모델을 찾을 수 없습니다. 모델이 포함된 APK를 설치해 주세요.")
                return@launch
            }

            app.container.runtimeStore.update {
                it.copy(running = true, paused = false, status = RecognitionStatus.LISTENING)
            }

            if (!app.container.translationModels.isReady(sttLanguageCode)) {
                failAndStop("한국어 번역 모델을 먼저 준비해 주세요.")
                return@launch
            }

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
                            it.copy(suggestedLanguageCode = suggestion)
                        }
                    }
                }
            }

            launch(Dispatchers.IO) {
                translateRequests
                    .debounce(TRANSLATE_DEBOUNCE_MS)
                    .collect {
                        while (true) {
                            val final = pendingFinalTranslations.poll() ?: break
                            translateSnapshot(final)
                        }
                        val partial = synchronized(this@CaptionForegroundService) {
                            TranslationRequest(bufferedText, bufferedLineId, historyOnNextTranslate)
                                .also { historyOnNextTranslate = false }
                        }
                        if (partial.text.isNotBlank()) translateSnapshot(partial)
                    }
            }

            val onSpeechResult = { result: SpeechResult ->
                val transcript = result.text.trim()

                val candidateId = lineIdCounter.incrementAndGet()
                var effectiveLineId = candidateId
                app.container.runtimeStore.update {
                    val updatedLines = recordTranscriptResult(
                        lines = it.transcriptLines,
                        originalText = transcript,
                        isFinal = result.isFinal,
                        newLineId = candidateId
                    )
                    effectiveLineId = updatedLines.lastOrNull()?.id ?: candidateId
                    it.copy(
                        originalText = transcript,
                        transcriptLines = updatedLines,
                        status = RecognitionStatus.PROCESSING,
                        lastError = null
                    )
                }
                queueTranslation(transcript, effectiveLineId, result.isFinal)
            }

            val mediaProjection = if (currentAudioSource == AudioSource.SYSTEM) {
                val mpManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val data = MediaProjectionHolder.data
                if (data == null) {
                    Log.e("CaptionService", "MediaProjection data missing.")
                    failAndStop("System-audio capture permission is missing. Start again and allow capture.")
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
                app.container.runtimeStore.update { it.copy(lastError = msg) }
            }

            val engine = StreamingSttEngine(
                context = this@CaptionForegroundService,
                audioSource = currentAudioSource,
                mediaProjection = mediaProjection,
                languageCode = sttLanguageCode,
                localSttClient = app.container.localVoskClient,
                scope = serviceScope,
                onResult = onSpeechResult,
                onError = errorSink,
                onUtteranceAudio = { audio -> languageSamples?.trySend(audio) }
            )
            speechEngine = engine
            observeEngineStatus(engine.status)
            engine.start()

            showOverlay()
            observeOverlayUpdates()
        }
    }

    private fun observeEngineStatus(statusFlow: StateFlow<RecognitionStatus>) {
        serviceScope.launch {
            statusFlow.collectLatest { status ->
                app.container.runtimeStore.update { it.copy(status = status) }
            }
        }
    }

    /**
     * Drive the overlay from a single combined stream so rapid runtime + settings changes
     * cannot apply out-of-order (stale coroutines overwriting newer translation text).
     */
    private fun observeOverlayUpdates() {
        serviceScope.launch {
            combine(
                app.container.runtimeStore.state,
                app.container.settingsRepository.settingsFlow,
                overlayReady
            ) { runtime, settings, _ -> Pair(runtime, settings) }
                .collectLatest { (runtime, settings) ->
                    val overlay = overlayController ?: return@collectLatest
                    val ui = buildOverlayUi(runtime, settings)
                    overlay.update(ui)
                }
        }
    }

    private fun buildOverlayUi(
        runtime: CaptionRuntimeState,
        settings: CaptionSettings,
    ) = OverlayUiState(
        originalText = runtime.originalText,
        translatedText = runtime.translatedText.ifBlank { runtime.originalText },
        transcriptText = overlayTranscriptText(runtime),
        status = runtime.status,
        statusDetail = runtime.lastError,
        textSizeSp = settings.textSizeSp,
        opacity = settings.overlayOpacity,
        showOriginal = settings.showOriginal,
        minimized = settings.overlayMinimized,
        suggestedLanguageCode = runtime.suggestedLanguageCode,
        themeId = settings.overlayThemeId,
        fontId = settings.overlayFontId
    )

    private fun queueTranslation(text: String, lineId: Long, isFinal: Boolean = false) {
        synchronized(this) {
            bufferedText = text
            bufferedLineId = lineId
            if (isFinal) {
                pendingFinalTranslations.add(TranslationRequest(text, lineId, true))
                // Finals are retained in the queue; do not translate the same
                // immutable snapshot again through the mutable partial slot.
                bufferedText = ""
                historyOnNextTranslate = false
            }
        }
        translateRequests.tryEmit(Unit)
    }

    private suspend fun translateSnapshot(request: TranslationRequest) {
        val captionSettings = app.container.settingsRepository.settingsFlow.first()
        val translated = app.container.translationRepository.translate(
            text = request.text,
            sourceCode = captionSettings.sourceLanguageCode,
            targetCode = captionSettings.targetLanguageCode,
            autoDetect = false
        )
        if (translated.isBlank()) return
        val translatedWithGlossary = applyGlossary(translated, app.container.glossary.list())
        app.container.runtimeStore.update {
            val current = it.transcriptLines.any { line -> line.id == request.lineId }
            it.copy(
                translatedText = if (current) translatedWithGlossary else it.translatedText,
                transcriptLines = updateTranscriptTranslation(it.transcriptLines, request.lineId, translatedWithGlossary),
                status = if (paused) RecognitionStatus.PAUSED else RecognitionStatus.LISTENING
            )
        }
        if (request.saveHistory && captionSettings.saveHistory) {
            app.container.transcriptHistory.add(
                TranscriptEntry(
                    originalText = request.text,
                    translatedText = translatedWithGlossary,
                    sourceLanguage = if (currentAudioSource == AudioSource.SYSTEM) "auto" else captionSettings.sourceLanguageCode,
                    targetLanguage = captionSettings.targetLanguageCode
                )
            )
        }
    }

    private data class TranslationRequest(val text: String, val lineId: Long, val saveHistory: Boolean)

    private fun showOverlay() {
        if (overlayController != null) return
        serviceScope.launch {
            val settings = app.container.settingsRepository.settingsFlow.first()
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
            val runtime = app.container.runtimeStore.state.value
            overlayController?.update(buildOverlayUi(runtime, settings))
            overlayReady.value = overlayReady.value + 1
        }
    }

    private fun togglePause() {
        paused = !paused
        if (paused) speechEngine?.pause() else speechEngine?.resume()
        app.container.runtimeStore.update {
            it.copy(
                paused = paused,
                status = if (paused) RecognitionStatus.PAUSED else RecognitionStatus.LISTENING
            )
        }
    }

    private fun toggleMinimized() {
        serviceScope.launch {
            app.container.settingsRepository.update { it.copy(overlayMinimized = !it.overlayMinimized) }
        }
    }

    private fun dismissLanguageSuggestion(code: String) {
        languageTracker?.dismiss(code)
        app.container.runtimeStore.update {
            if (it.suggestedLanguageCode == code) it.copy(suggestedLanguageCode = null) else it
        }
    }

    private fun acceptLanguageSuggestion(code: String) {
        if (code !in LanguageRecommendationTracker.SUPPORTED) return
        if (app.container.runtimeStore.state.value.suggestedLanguageCode != code) return
        serviceScope.launch {
            app.container.settingsRepository.update { it.copy(sourceLanguageCode = code) }
            val label = LanguageRecommendationTracker.label(code)
            stopFlow()
            app.container.runtimeStore.update {
                it.copy(
                    status = RecognitionStatus.IDLE,
                    lastError = "$label 음성으로 변경했습니다. 앱에서 번역 시작을 다시 눌러 주세요."
                )
            }
            Toast.makeText(
                this@CaptionForegroundService,
                "$label 음성으로 변경했습니다. 앱에서 다시 시작해 주세요.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun stopFlow() {
        captionSessionActive.set(false)
        languageSamples?.close()
        languageSamples = null
        languageTracker = null
        try { speechEngine?.stop() } catch (_: Throwable) {}
        speechEngine = null
        overlayController?.hide()
        overlayController = null
        overlayReady.value = 0
        MediaProjectionHolder.clear()
        val error = failureMessage
        failureMessage = null
        app.container.runtimeStore.update {
            if (error == null) CaptionRuntimeState()
            else CaptionRuntimeState(status = RecognitionStatus.ERROR, lastError = error)
        }
        serviceScope.cancel()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun failAndStop(message: String) {
        captionSessionActive.set(false)
        languageSamples?.close()
        languageSamples = null
        languageTracker = null
        failureMessage = message
        try { speechEngine?.stop() } catch (_: Throwable) {}
        speechEngine = null
        overlayController?.hide()
        overlayController = null
        MediaProjectionHolder.clear()
        app.container.runtimeStore.update {
            it.copy(running = false, paused = false, status = RecognitionStatus.ERROR, lastError = message)
        }
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { stopFlow() } catch (_: Throwable) {}
    }

    private fun buildNotification(): Notification {
        createNotificationChannel()
        val openIntent = Intent(this, MainActivity::class.java)
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
        const val EXTRA_AUDIO_SOURCE = "caption.extra.AUDIO_SOURCE"

        private const val CHANNEL_ID = "caption_channel"
        private const val NOTIF_ID = 2001
        private const val TRANSLATE_DEBOUNCE_MS = 300L
        private const val NO_LINE_ID = -1L
    }
}
