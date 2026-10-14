package com.charles.livecaptionn.speech

import android.content.Context
import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class LocalJapaneseSttClient internal constructor(
    private val createSession: () -> JapaneseSttSession,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    constructor(context: Context) : this({ createNativeSession(context.applicationContext.assets) })

    suspend fun openSession(): JapaneseSttSession {
        var created: JapaneseSttSession? = null
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
        const val MODEL_ROOT = "models/reazonspeech-k2-v2"
        fun createNativeSession(assets: AssetManager): JapaneseSttSession {
            val recognizer = OfflineRecognizer(
                assetManager = assets,
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = "$MODEL_ROOT/encoder.int8.onnx",
                            decoder = "$MODEL_ROOT/decoder.int8.onnx",
                            joiner = "$MODEL_ROOT/joiner.int8.onnx"
                        ),
                        tokens = "$MODEL_ROOT/tokens.txt",
                        modelType = "zipformer",
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
                            model = "$MODEL_ROOT/silero_vad.onnx",
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
                return JapaneseSttSession(NativeJapaneseBackend(recognizer), JapaneseVadSpeechGate(NativeVadProbabilityModel(vad)))
            } catch (failure: Throwable) {
                try { vad?.release() } finally { recognizer.release() }
                throw failure
            }
        }
    }
}

internal interface JapaneseRecognizerStream {
    fun acceptWaveform(samples: FloatArray, sampleRate: Int)
    fun close()
}

internal interface JapaneseRecognizerBackend {
    fun createStream(): JapaneseRecognizerStream
    fun decode(stream: JapaneseRecognizerStream)
    fun result(stream: JapaneseRecognizerStream): String
    fun close()
}

internal interface JapaneseSpeechGate {
    fun isSpeech(pcm: ByteArray, length: Int): Boolean
    fun reset()
    fun close()
}

internal class JapaneseSttSession(backend: JapaneseRecognizerBackend, gate: JapaneseSpeechGate) {
    private val recognizer = NativeSessionOwner(backend)
    private val speechGate = NativeSessionOwner(gate)

    fun transcribe(pcm: ByteArray): String = recognizer.feed("") { backend ->
        require(pcm.size % 2 == 0)
        if (pcm.isEmpty()) return@feed ""
        val stream = backend.createStream()
        try {
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

private class NativeJapaneseBackend(private val recognizer: OfflineRecognizer) : JapaneseRecognizerBackend {
    override fun createStream(): JapaneseRecognizerStream = NativeJapaneseStream(recognizer.createStream())
    override fun decode(stream: JapaneseRecognizerStream) = recognizer.decode((stream as NativeJapaneseStream).stream)
    override fun result(stream: JapaneseRecognizerStream): String = recognizer.getResult((stream as NativeJapaneseStream).stream).text
    override fun close() = recognizer.release()
}

private class NativeJapaneseStream(val stream: OfflineStream) : JapaneseRecognizerStream {
    override fun acceptWaveform(samples: FloatArray, sampleRate: Int) = stream.acceptWaveform(samples, sampleRate)
    override fun close() = stream.release()
}

internal interface JapaneseSpeechProbabilityModel {
    fun probability(samples: FloatArray): Float
    fun reset()
    fun close()
}

private class NativeVadProbabilityModel(private val vad: Vad) : JapaneseSpeechProbabilityModel {
    override fun probability(samples: FloatArray) = vad.compute(samples)
    override fun reset() = vad.reset()
    override fun close() = vad.release()
}

internal class JapaneseVadSpeechGate(private val model: JapaneseSpeechProbabilityModel) : JapaneseSpeechGate {
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
