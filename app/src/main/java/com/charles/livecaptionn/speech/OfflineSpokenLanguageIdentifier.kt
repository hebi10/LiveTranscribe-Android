package com.charles.livecaptionn.speech

import android.content.Context
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentification
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationWhisperConfig

/**
 * Independent acoustic language identification.
 * Audio is held in memory, fed to the local Whisper Tiny model and never saved.
 */
class OfflineSpokenLanguageIdentifier(context: Context) {
    private val assets = context.applicationContext.assets
    private var recognizer: SpokenLanguageIdentification? = null

    @Synchronized
    fun detect(pcm16Le: ByteArray): String? {
        if (pcm16Le.size < MIN_PCM_BYTES) return null
        return try {
            val instance = recognizer ?: SpokenLanguageIdentification(
                assetManager = assets,
                config = SpokenLanguageIdentificationConfig(
                    whisper = SpokenLanguageIdentificationWhisperConfig(
                        encoder = "language-id/sherpa-onnx-whisper-tiny/tiny-encoder.int8.onnx",
                        decoder = "language-id/sherpa-onnx-whisper-tiny/tiny-decoder.int8.onnx"
                    ),
                    numThreads = 2,
                    debug = false
                )
            ).also { recognizer = it }
            val samples = FloatArray(pcm16Le.size / 2)
            for (i in samples.indices) {
                val low = pcm16Le[i * 2].toInt() and 0xFF
                val high = pcm16Le[i * 2 + 1].toInt()
                samples[i] = ((high shl 8) or low).toShort().toInt() / 32768f
            }
            val stream = instance.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                instance.compute(stream).lowercase().substringBefore("-").trim().takeIf { it.isNotBlank() }
            } finally {
                stream.release()
            }
        } catch (_: Throwable) {
            null
        }
    }

    @Synchronized
    fun close() {
        recognizer?.release()
        recognizer = null
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val MIN_PCM_BYTES = SAMPLE_RATE * 2
    }
}
