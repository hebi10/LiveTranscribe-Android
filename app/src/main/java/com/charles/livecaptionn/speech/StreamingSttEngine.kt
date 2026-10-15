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
import kotlin.coroutines.CoroutineContext
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

/** Captures PCM continuously; local recognition runs in a separate bounded worker. */
class StreamingSttEngine(
    private val context: Context,
    private val audioSource: AudioSource,
    private val mediaProjection: MediaProjection?,
    private val languageCode: String,
    private val localSttClient: LocalQwenSttClient,
    private val scope: CoroutineScope,
    private val onResult: (SpeechResult) -> Unit,
    private val onError: (String?) -> Unit = {},
    private val onUtteranceAudio: ((ByteArray) -> Unit)? = null,
    private val onSkippedAudio: () -> Unit = {},
    private val onHint: (String?) -> Unit = {},
    private val onRecognitionProcessing: (Boolean) -> Unit = {},
    private val onRecognitionLimit: () -> Unit = {},
    private val detectLanguage: ((ByteArray) -> String?)? = null
) : SpeechEngine {

    private val statusMutable = MutableStateFlow(RecognitionStatus.IDLE)
    val status: StateFlow<RecognitionStatus> = statusMutable

    private val lifecycle = CaptureLifecycle(scope, Dispatchers.IO)
    private val signals = CaptureSignalState(onError, { statusMutable.value = it }, onHint)

    override fun start() {
        lifecycle.start { owner ->
            signals.begin()
            runCapture(owner)
        }
    }

    override suspend fun stop() {
        lifecycle.stop()
        signals.stopped()?.let { throw IllegalStateException(it) }
    }

    override fun pause() {
        if (!lifecycle.isRunning) return
        signals.pause()
    }

    override fun resume() {
        if (!lifecycle.isRunning) return
        signals.resume()
    }

    private suspend fun runCapture(owner: CaptureLifecycle.Owner) {
        var localSession: OfflineSttSession? = null
        var pipeline: OfflineSpeechPipeline? = null
        val ownerContext = currentCoroutineContext()
        var record: AudioRecord? = null
        try {
            val sourceMode = languageCode.lowercase(java.util.Locale.ROOT).substringBefore('-')
            check(sourceMode in setOf("auto", "en", "ja")) {
                "지원하지 않는 음성 인식 모드입니다. 자동 감지·영어·일본어 중에서 선택해 주세요."
            }
            val openedSession = localSttClient.openSession()
            localSession = openedSession
            pipeline = OfflineSpeechPipeline(
                session = openedSession,
                onResult = onResult,
                onSkippedAudio = onSkippedAudio,
                onProcessing = onRecognitionProcessing,
                onRecognitionLimit = onRecognitionLimit,
                sourceLanguageCode = sourceMode,
                detectLanguage = if (sourceMode == "auto") detectLanguage else null,
                onFailure = { message ->
                    if (ownerContext.isActive) signals.fail(message)
                }
            )
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
            var resumeGenerationAtStart = signals.resumeGeneration
            val started = signals.startWhenResumed {
                owner.whenActive {
                    captureContext.ensureActive()
                    resumeGenerationAtStart = signals.resumeGeneration
                    localRecord.startRecording()
                }
            }
            if (!started) return
            currentCoroutineContext().ensureActive()
            check(localRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "오디오 캡처를 시작하지 못했습니다. 다시 시작해 주세요."
            }
            signals.ready()
            captureLoop(localRecord, pipeline, resumeGenerationAtStart)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            // AudioRecord.stop can make an in-flight blocking read fail during shutdown.
            currentCoroutineContext().ensureActive()
            Log.e(TAG, "Audio capture failed", t)
            signals.fail(t.message ?: "오디오 캡처에 실패했습니다. 다시 시작해 주세요.")
        } finally {
            // Only this owning worker finalizes and releases resources. stop() joins it.
            owner.clearInterrupt()
            try { record?.stop() } catch (_: Throwable) {}
            try {
                try {
                    pipeline?.finish()
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Throwable) {
                    // join() does not propagate an owning launch job's failure to stop().
                    signals.fail(failure.message ?: "마지막 음성 구간을 처리하지 못했습니다.")
                }
            } finally {
                try { record?.release() } catch (_: Throwable) {}
                if (pipeline == null) localSession?.close()
                if (audioSource == AudioSource.SYSTEM) {
                    try { mediaProjection?.stop() } catch (_: Throwable) {}
                }
            }
        }
    }

    private suspend fun captureLoop(
        record: AudioRecord,
        pipeline: OfflineSpeechPipeline,
        resumeGenerationAtStart: Long
    ) {
        val buffer = ByteArray(CHUNK_BYTES)
        var silentChunkStreak = 0
        var sawAnyAudioEver = false
        val intervalPcm = ByteArrayOutputStream()
        var voicedChunks = 0
        val audioBoundary = CaptureAudioBoundary(signals, resumeGenerationAtStart) {
            pipeline.pauseBoundary()
            intervalPcm.reset()
            voicedChunks = 0
            silentChunkStreak = 0
        }
        val captureContext = currentCoroutineContext()

        while (currentCoroutineContext().isActive) {
            signals.fatalError?.let { throw IllegalStateException(it) }
            if (!audioBoundary.prepareRead {
                    discardPendingAudio(record, buffer, captureContext)
                }) {
                delay(if (signals.paused) 120 else 10)
                continue
            }

            val read = record.read(buffer, 0, buffer.size)
            currentCoroutineContext().ensureActive()
            signals.fatalError?.let { throw IllegalStateException(it) }
            if (read < 0) {
                throw IllegalStateException("오디오 읽기에 실패했습니다 ($read). 오디오 소스를 확인하고 다시 시작해 주세요.")
            }
            if (!audioBoundary.acceptRead()) continue
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
                    signals.hint(NO_CAPTURABLE_AUDIO_MESSAGE)
                }
            } else {
                silentChunkStreak = 0
                sawAnyAudioEver = true
                signals.hint(null)
            }

            if (onUtteranceAudio != null) {
                if (intervalPcm.size() + read > MAX_LANGUAGE_AUDIO_BYTES) {
                    intervalPcm.reset()
                    voicedChunks = 0
                }
                intervalPcm.write(buffer, 0, read)
                if (!isSilent) voicedChunks++
            }

            val boundary = pipeline.feed(buffer, read)

            // Acoustic intervals remain independent of recognized text so language
            // recommendations still work when the selected source is incorrect.
            if (boundary || (isSilent && silentChunkStreak == LANGUAGE_SILENCE_CHUNKS)) {
                if (onUtteranceAudio != null &&
                    intervalPcm.size() >= MIN_LANGUAGE_AUDIO_BYTES && voicedChunks >= 8
                ) {
                    onUtteranceAudio.invoke(intervalPcm.toByteArray())
                }
                intervalPcm.reset()
                voicedChunks = 0
            }

            if (currentCoroutineContext().isActive) signals.listening()
        }
    }

    private fun discardPendingAudio(
        record: AudioRecord,
        buffer: ByteArray,
        captureContext: CoroutineContext
    ): Boolean {
        repeat(32) {
            captureContext.ensureActive()
            val read = record.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
            captureContext.ensureActive()
            if (read < 0) throw IllegalStateException("일시정지 오디오를 비우지 못했습니다 ($read). 다시 시작해 주세요.")
            if (read == 0) return true
        }
        return false
    }

    private fun buildAudioRecord(): AudioRecord? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            signals.hint("음성 캡처를 위해 마이크(녹음) 권한을 허용해 주세요.")
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
                    signals.hint("시스템 오디오 캡처는 Android 10 이상에서 사용할 수 있습니다. 마이크 모드로 전환해 주세요.")
                    return null
                }
                val projection = mediaProjection
                    ?: run {
                        signals.hint("화면 캡처 권한이 없습니다. 시스템 오디오를 다시 시작해 주세요.")
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
            "영상 앱을 재생하고 음소거를 해제해 주세요. 캡처가 허용된 앱만 지원합니다."

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

