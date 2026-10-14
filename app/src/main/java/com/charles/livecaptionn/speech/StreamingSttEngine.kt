package com.charles.livecaptionn.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import androidx.core.content.ContextCompat
import com.charles.livecaptionn.settings.AudioSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlin.math.abs

/**
 * Continuous, low-latency speech-to-text engine backed by a live
 * [VoskStreamingSession]. Reads 16kHz mono PCM from either the mic or
 * MediaProjection system-audio capture and feeds ~100ms chunks straight into
 * Vosk, emitting partial results as they evolve and final segments on
 * silence boundaries or after six seconds of uninterrupted audio.
 *
 * This replaces the old batch SystemAudioEngine, which accumulated 2-6s
 * windows and created a fresh Recognizer per chunk, throwing away streaming
 * state and making captions feel laggy and incoherent.
 */
class StreamingSttEngine(
    private val context: Context,
    private val audioSource: AudioSource,
    private val mediaProjection: MediaProjection?,
    private val languageCode: String,
    private val localSttClient: LocalVoskSttClient,
    private val scope: CoroutineScope,
    private val onResult: (SpeechResult) -> Unit,
    private val onError: (String?) -> Unit = {},
    private val onUtteranceAudio: ((ByteArray) -> Unit)? = null
) : SpeechEngine {

    private val statusMutable = MutableStateFlow(RecognitionStatus.IDLE)
    val status: StateFlow<RecognitionStatus> = statusMutable

    private val lifecycle = CaptureLifecycle(scope, Dispatchers.IO)
    @Volatile private var paused = false

    override fun start() {
        lifecycle.start { owner -> runCapture(owner) }
    }

    override suspend fun stop() {
        lifecycle.stop()
        paused = false
        statusMutable.value = RecognitionStatus.IDLE
    }

    override fun pause() {
        if (!lifecycle.isRunning) return
        paused = true
        statusMutable.value = RecognitionStatus.PAUSED
    }

    override fun resume() {
        if (!lifecycle.isRunning) return
        paused = false
        statusMutable.value = RecognitionStatus.LISTENING
    }

    private suspend fun runCapture(owner: CaptureLifecycle.Owner) {
        var localSession: VoskStreamingSession? = null
        var record: AudioRecord? = null
        var captureStarted = false
        try {
            val openedSession = localSttClient.openSession(languageCode, SAMPLE_RATE)
                ?: throw IllegalStateException(
                    "'$languageCode' 오프라인 음성 모델이 없습니다. 모델이 포함된 APK를 설치해 주세요."
                )
            localSession = openedSession
            currentCoroutineContext().ensureActive()
            val localRecord = buildAudioRecord()
                ?: throw IllegalStateException("오디오 캡처를 준비하지 못했습니다. 권한과 오디오 소스를 확인해 주세요.")
            record = localRecord
            currentCoroutineContext().ensureActive()
            check(localRecord.state == AudioRecord.STATE_INITIALIZED) {
                "오디오 캡처를 초기화하지 못했습니다. 오디오 소스를 확인해 주세요."
            }
            owner.setInterrupt { try { localRecord.stop() } catch (_: Throwable) {} }
            val captureContext = currentCoroutineContext()
            if (!owner.whenActive {
                    captureContext.ensureActive()
                    localRecord.startRecording()
                    captureStarted = true
                }) return
            currentCoroutineContext().ensureActive()
            check(localRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "오디오 캡처를 시작하지 못했습니다. 다시 시작해 주세요."
            }
            statusMutable.value = if (paused) RecognitionStatus.PAUSED else RecognitionStatus.LISTENING
            onError(null)
            captureLoop(localRecord, openedSession)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            // AudioRecord.stop can make an in-flight blocking read fail during shutdown.
            currentCoroutineContext().ensureActive()
            Log.e(TAG, "Audio capture failed", t)
            statusMutable.value = RecognitionStatus.ERROR
            onError(t.message ?: "오디오 캡처에 실패했습니다. 다시 시작해 주세요.")
        } finally {
            // Only this owning worker finalizes and releases resources. stop() joins it.
            owner.clearInterrupt()
            try {
                if (captureStarted) {
                    localSession?.finish()?.let {
                        onResult(SpeechResult(it, isFinal = true))
                    }
                }
            } finally {
                try { record?.stop() } catch (_: Throwable) {}
                try { record?.release() } catch (_: Throwable) {}
                localSession?.close()
                if (audioSource == AudioSource.SYSTEM) {
                    try { mediaProjection?.stop() } catch (_: Throwable) {}
                }
                paused = false
            }
        }
    }

    private suspend fun captureLoop(record: AudioRecord, localSession: VoskStreamingSession) {
        val buffer = ByteArray(CHUNK_BYTES)
        var silentChunkStreak = 0
        val resultEmitter = StreamingSpeechResultEmitter(onResult)
        var sawAnyAudioEver = false
        val intervalPcm = ByteArrayOutputStream()
        var voicedChunks = 0

        while (currentCoroutineContext().isActive) {
            if (paused) {
                intervalPcm.reset()
                voicedChunks = 0
                delay(120)
                continue
            }

            val read = record.read(buffer, 0, buffer.size)
            currentCoroutineContext().ensureActive()
            if (read < 0) {
                throw IllegalStateException("오디오 읽기에 실패했습니다 ($read). 오디오 소스를 확인하고 다시 시작해 주세요.")
            }
            if (read == 0) {
                delay(10)
                continue
            }

            val avgAbs = averageAbsAmplitude(buffer, read)
            val isSilent = avgAbs < SILENCE_AVERAGE_ABS_THRESHOLD

            if (isSilent) {
                silentChunkStreak += 1
                // For system audio, flag when we've been silent for a long time so the
                // user knows nothing is actually playing.
                if (audioSource == AudioSource.SYSTEM &&
                    !sawAnyAudioEver &&
                    silentChunkStreak >= SILENT_CHUNKS_BEFORE_SYSTEM_HINT
                ) {
                    onError(NO_CAPTURABLE_AUDIO_MESSAGE)
                }
            } else {
                silentChunkStreak = 0
                sawAnyAudioEver = true
                onError(null)
            }

            if (onUtteranceAudio != null) {
                if (intervalPcm.size() + read > MAX_LANGUAGE_AUDIO_BYTES) {
                    intervalPcm.reset()
                    voicedChunks = 0
                }
                intervalPcm.write(buffer, 0, read)
                if (!isSilent) voicedChunks++
            }

            val result = localSession.feed(buffer, read)

            resultEmitter.emit(result)

            // Independently end acoustic intervals on silence, even when the
            // currently selected Vosk model cannot recognize the language.
            if (result.accepted || (isSilent && silentChunkStreak == LANGUAGE_SILENCE_CHUNKS)) {
                if (onUtteranceAudio != null &&
                    intervalPcm.size() >= MIN_LANGUAGE_AUDIO_BYTES && voicedChunks >= 8
                ) {
                    onUtteranceAudio.invoke(intervalPcm.toByteArray())
                }
                intervalPcm.reset()
                voicedChunks = 0
            }

            if (!paused && currentCoroutineContext().isActive) {
                statusMutable.value = RecognitionStatus.LISTENING
            }
        }
    }

    private fun buildAudioRecord(): AudioRecord? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            onError("음성 캡처를 위해 마이크(녹음) 권한을 허용해 주세요.")
            return null
        }
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(CHUNK_BYTES * 8)

        return when (audioSource) {
            AudioSource.SYSTEM -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    onError("시스템 오디오 캡처는 Android 10 이상에서 사용할 수 있습니다. 마이크 모드로 전환해 주세요.")
                    return null
                }
                val projection = mediaProjection
                    ?: run {
                        onError("화면 캡처 권한이 없습니다. 시스템 오디오를 다시 시작해 주세요.")
                        return null
                    }
                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBuf)
                    .build()
            }
            AudioSource.MIC -> {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBuf)
                    .build()
            }
        }
    }

    companion object {
        private const val TAG = "StreamingSttEngine"
        private const val SAMPLE_RATE = 16_000
        /** ~100ms at 16kHz mono 16-bit = 3200 bytes. Small chunks = live partials. */
        private const val CHUNK_BYTES = 3200
        private const val LANGUAGE_SILENCE_CHUNKS = 7
        private const val MIN_LANGUAGE_AUDIO_BYTES = SAMPLE_RATE * 2
        private const val MAX_LANGUAGE_AUDIO_BYTES = SAMPLE_RATE * 2 * 15
        private const val SILENCE_AVERAGE_ABS_THRESHOLD = 120
        private const val SILENT_CHUNKS_BEFORE_SYSTEM_HINT = 30 // ~3s of silence
        private const val NO_CAPTURABLE_AUDIO_MESSAGE =
            "캡처 가능한 시스템 오디오가 없습니다. 캡처를 허용하는 앱에서 음소거를 해제하고 재생해 주세요."

        private fun averageAbsAmplitude(pcmData: ByteArray, length: Int): Int {
            var sum = 0L
            var samples = 0
            var i = 0
            val end = minOf(length, pcmData.size) - 1
            while (i < end) {
                val low = pcmData[i].toInt() and 0xFF
                val high = pcmData[i + 1].toInt()
                val sample = (high shl 8) or low
                sum += abs(sample)
                samples += 1
                i += 2
            }
            return if (samples == 0) 0 else (sum / samples).toInt()
        }
    }
}

