package com.charles.livecaptionn.speech

import android.content.Context
import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

class LocalQwenSttClient internal constructor(
    private val createSession: () -> OfflineSttSession,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    constructor(context: Context, modelDirectory: File) : this({
        createNativeSession(context.applicationContext.assets, modelDirectory)
    })

    internal suspend fun openSession(): OfflineSttSession {
        var created: OfflineSttSession? = null
        try {
            return withContext(dispatcher) {
                currentCoroutineContext().ensureActive()
                createSession().also { created = it }
            }
        } catch (failure: Throwable) {
            created?.close()
            throw failure
        }
    }

    private companion object {
        fun createNativeSession(assets: AssetManager, modelDirectory: File): OfflineSttSession {
            requireQwenModelFiles(modelDirectory)
            val recognizer = OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 128),
                    modelConfig = OfflineModelConfig(
                        qwen3Asr = OfflineQwen3AsrModelConfig(
                            convFrontend = File(modelDirectory, "conv_frontend.onnx").absolutePath,
                            encoder = File(modelDirectory, "encoder.int8.onnx").absolutePath,
                            decoder = File(modelDirectory, "decoder.int8.onnx").absolutePath,
                            tokenizer = File(modelDirectory, "tokenizer").absolutePath,
                            maxTotalLen = 512,
                            maxNewTokens = QWEN_MAX_NEW_TOKENS,
                            temperature = 1e-6f,
                            topP = .8f,
                            seed = 42
                        ),
                        numThreads = 2,
                        provider = "cpu",
                        debug = false
                    ),
                    decodingMethod = "greedy_search"
                )
            )
            var vad: Vad? = null
            try {
                vad = Vad(
                    assetManager = assets,
                    config = VadModelConfig(
                        sileroVadModelConfig = SileroVadModelConfig(
                            model = "speech/silero_vad.onnx",
                            threshold = .5f,
                            minSilenceDuration = 1f,
                            minSpeechDuration = .25f,
                            maxSpeechDuration = 8f,
                            windowSize = 512
                        ),
                        sampleRate = 16000,
                        numThreads = 1,
                        provider = "cpu",
                        debug = false
                    )
                )
                return OfflineSttSession(NativeOfflineBackend(recognizer), VadSpeechGate(NativeVadProbabilityModel(vad)))
            } catch (failure: Throwable) {
                try { vad?.release() } finally { recognizer.release() }
                throw failure
            }
        }
    }
}

internal const val QWEN_MAX_NEW_TOKENS = 128
private const val QWEN_MAX_STRIPPED_PREFIX_TOKENS = 16

internal class RecognitionOutputLimitException : IllegalStateException()

internal fun validateQwenResult(text: String, tokenCount: Int): String {
    // sherpa strips up to 16 scaffold tokens and does not expose a finish reason.
    // Reject results near the generation cap rather than publishing a cut sentence.
    if (tokenCount >= QWEN_MAX_NEW_TOKENS - QWEN_MAX_STRIPPED_PREFIX_TOKENS) {
        throw RecognitionOutputLimitException()
    }
    return text
}

internal fun requireQwenModelFiles(directory: File) {
    val paths = listOf(
        "conv_frontend.onnx", "encoder.int8.onnx", "decoder.int8.onnx",
        "tokenizer/merges.txt", "tokenizer/tokenizer_config.json", "tokenizer/vocab.json"
    )
    check(paths.all { File(directory, it).let { file -> file.isFile && file.length() > 0 } }) {
        "음성 인식 모델이 준비되지 않았습니다. 앱에서 AI 모델을 준비해 주세요."
    }
}

internal interface OfflineRecognizerStream {
    fun setOption(name: String, value: String)
    fun acceptWaveform(samples: FloatArray, sampleRate: Int)
    fun close()
}

internal interface OfflineRecognizerBackend {
    fun createStream(): OfflineRecognizerStream
    fun decode(stream: OfflineRecognizerStream)
    fun result(stream: OfflineRecognizerStream): String
    fun close()
}

internal interface SpeechGate {
    fun isSpeech(pcm: ByteArray, length: Int): Boolean
    fun reset()
    fun close()
}

internal class OfflineSttSession(backend: OfflineRecognizerBackend, gate: SpeechGate) {
    private val recognizer = NativeSessionOwner(backend)
    private val speechGate = NativeSessionOwner(gate)

    fun transcribe(pcm: ByteArray, sourceLanguageCode: String = "auto"): String = recognizer.feed("") { backend ->
        require(pcm.size % 2 == 0)
        if (pcm.isEmpty()) return@feed ""
        val forcedLanguage = when (sourceLanguageCode) {
            "auto" -> null
            "en" -> "English"
            "ja" -> "Japanese"
            else -> throw IllegalArgumentException("지원하지 않는 음성 인식 모드입니다.")
        }
        val stream = backend.createStream()
        try {
            forcedLanguage?.let { stream.setOption("language", it) }
            stream.acceptWaveform(pcmSamples(pcm, pcm.size, trailingSamples = 8000), 16000)
            backend.decode(stream)
            backend.result(stream).trim()
        } finally {
            stream.close()
        }
    }

    fun isSpeech(pcm: ByteArray, length: Int): Boolean = speechGate.feed(false) {
        require(length in 0..pcm.size && length % 2 == 0)
        if (length == 0) false else it.isSpeech(pcm, length)
    }

    fun resetSpeechGate() = speechGate.feed(Unit) { it.reset() }

    fun close() {
        try { recognizer.close { it.close() } } finally { speechGate.close { it.close() } }
    }
}

internal fun pcmSamples(pcm: ByteArray, length: Int, trailingSamples: Int = 0): FloatArray = FloatArray(length / 2 + trailingSamples) { index ->
    if (index >= length / 2) return@FloatArray 0f
    val sample = (pcm[index * 2 + 1].toInt() shl 8) or (pcm[index * 2].toInt() and 255)
    sample / 32768f
}

private class NativeOfflineBackend(private val recognizer: OfflineRecognizer) : OfflineRecognizerBackend {
    override fun createStream(): OfflineRecognizerStream = NativeOfflineStream(recognizer.createStream())
    override fun decode(stream: OfflineRecognizerStream) = recognizer.decode((stream as NativeOfflineStream).stream)
    override fun result(stream: OfflineRecognizerStream): String {
        val result = recognizer.getResult((stream as NativeOfflineStream).stream)
        return validateQwenResult(result.text, result.tokens.size)
    }
    override fun close() = recognizer.release()
}

private class NativeOfflineStream(val stream: OfflineStream) : OfflineRecognizerStream {
    override fun setOption(name: String, value: String) = stream.setOption(name, value)
    override fun acceptWaveform(samples: FloatArray, sampleRate: Int) = stream.acceptWaveform(samples, sampleRate)
    override fun close() = stream.release()
}

internal interface SpeechProbabilityModel {
    fun probability(samples: FloatArray): Float
    fun reset()
    fun close()
}

private class NativeVadProbabilityModel(private val vad: Vad) : SpeechProbabilityModel {
    override fun probability(samples: FloatArray) = vad.compute(samples)
    override fun reset() = vad.reset()
    override fun close() = vad.release()
}

internal class VadSpeechGate(private val model: SpeechProbabilityModel) : SpeechGate {
    private val window = FloatArray(512)
    private var count = 0
    private var lastSpeech = false

    override fun isSpeech(pcm: ByteArray, length: Int): Boolean {
        var speech = false
        var computed = false
        for (index in 0 until length step 2) {
            val sample = (pcm[index + 1].toInt() shl 8) or (pcm[index].toInt() and 255)
            window[count++] = sample / 32768f
            if (count == window.size) {
                lastSpeech = model.probability(window) >= .5f
                speech = speech || lastSpeech
                count = 0
                computed = true
            }
        }
        return if (computed) speech else lastSpeech
    }

    override fun reset() {
        count = 0
        lastSpeech = false
        window.fill(0f)
        model.reset()
    }

    override fun close() = model.close()
}
