package com.charles.livecaptionn.speech

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Wrapper around the Vosk Kotlin API with two modes:
 *
 * 1. Legacy batch [transcribe] — creates a one-shot Recognizer per call. Kept
 *    only as a fallback for the old system-audio batch path.
 *
 * 2. [openSession] — returns a [VoskStreamingSession] that keeps ONE Recognizer
 *    alive across an entire caption session. Callers feed PCM as it arrives
 *    and pull partial/final results. This is how Vosk is actually designed to
 *    be used and is what makes live captions feel live.
 *
 * Models are cached by language code so back-to-back sessions in the same
 * language don't pay the disk-load cost. Model access is guarded by a mutex
 * to prevent races between concurrent openSession/transcribe calls.
 */
class LocalVoskSttClient(private val registry: VoskModelRegistry) {
    // App-lifetime models stay alive while any recognizer can still reference them.
    // The bundled catalog bounds this cache to the supported input languages.
    private val cachedModels = mutableMapOf<String, Model>()
    private val modelMutex = Mutex()

    /** Open a streaming session for [languageCode] at [sampleRate]. Returns null if no model. */
    suspend fun openSession(
        languageCode: String,
        sampleRate: Int
    ): VoskStreamingSession? {
        var created: VoskStreamingSession? = null
        try {
            return withContext(Dispatchers.IO) {
                val model = modelFor(languageCode) ?: return@withContext null
                currentCoroutineContext().ensureActive()
                val recognizer = Recognizer(model, sampleRate.toFloat())
                try {
                    recognizer.setWords(false)
                    recognizer.setMaxAlternatives(0)
                    VoskStreamingSession(recognizer).also { created = it }
                } catch (t: Throwable) {
                    recognizer.close()
                    throw t
                }
            }
        } catch (t: Throwable) {
            // withContext may discard a successfully created native session when the
            // caller is cancelled before dispatching the return value back to it.
            created?.close()
            if (t is CancellationException) throw t
            Log.w("LocalVoskSttClient", "openSession failed for $languageCode", t)
            return null
        }
    }

    /** Legacy batch API, kept for fallbacks. Prefer [openSession]. */
    suspend fun transcribe(
        pcmData: ByteArray,
        sampleRate: Int,
        languageCode: String
    ): TranscribeOutcome = withContext(Dispatchers.IO) {
        try {
            val model = modelFor(languageCode)
                ?: return@withContext TranscribeOutcome(
                    "",
                    "No local Vosk model installed for '$languageCode'. Install a build with packaged models."
                )
            Recognizer(model, sampleRate.toFloat()).use { recognizer ->
                val accepted = recognizer.acceptWaveForm(pcmData, pcmData.size)
                val text = when {
                    accepted -> extractText(recognizer.result)
                    else -> extractText(recognizer.partialResult)
                }.ifBlank {
                    extractText(recognizer.finalResult)
                }
                TranscribeOutcome(text)
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w("LocalVoskSttClient", "Local transcription failed", t)
            TranscribeOutcome("", localFailureMessage(languageCode, t))
        }
    }

    private suspend fun modelFor(languageCode: String): Model? = modelMutex.withLock {
        val key = languageCode.lowercase(java.util.Locale.ROOT)
        cachedModels[key]?.let { return@withLock it }
        val modelDir = registry.resolveModelDir(languageCode) ?: return@withLock null
        Model(modelDir.absolutePath).also { cachedModels[key] = it }
    }

    private fun localFailureMessage(languageCode: String, t: Throwable): String {
        val detail = t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName
        return "Local '$languageCode' transcription failed: $detail"
    }

    companion object {
        internal fun extractText(resultJson: String): String {
            return try {
                val json = JSONObject(resultJson)
                json.optString("text")
                    .ifBlank { json.optString("partial") }
                    .trim()
            } catch (_: Throwable) {
                ""
            }
        }
    }
}

/**
 * Live streaming Vosk session. Feed PCM chunks as they arrive; pull
 * partial/final text after each feed. Native feed, finalization and close are
 * serialized; finalization is terminal and each resource closes once.
 */
class VoskStreamingSession internal constructor(
    recognizer: Recognizer
) {
    private val owner = NativeSessionOwner(recognizer)

    /**
     * Feed a chunk of 16-bit LE PCM. Returns [FeedResult] indicating whether a
     * final result is available (`accepted == true` means Vosk detected a
     * silence/segment boundary) plus the current text — if `accepted`, [text]
     * is the finalized segment; otherwise it's the latest partial.
     */
    fun feed(pcm: ByteArray, length: Int = pcm.size): FeedResult =
        owner.feed(FeedResult("", accepted = false)) { recognizer ->
            // Let native feed failures reach the capture owner as terminal errors.
            val accepted = recognizer.acceptWaveForm(pcm, length)
            val text = LocalVoskSttClient.extractText(
                if (accepted) recognizer.result else recognizer.partialResult
            )
            FeedResult(text, accepted)
        }

    /** Flush buffered audio exactly once; no feed is accepted after this call. */
    fun finish(): String = owner.finish("") { recognizer ->
        try {
            LocalVoskSttClient.extractText(recognizer.finalResult)
        } catch (t: Throwable) {
            Log.w("VoskStreamingSession", "finalResult failed", t)
            ""
        }
    }

    fun close() = owner.close { recognizer ->
        try { recognizer.close() } catch (_: Throwable) {}
    }

    data class FeedResult(
        /** Current text — segment if [accepted], partial otherwise. */
        val text: String,
        /** True when Vosk hit a silence boundary and emitted a finalized segment. */
        val accepted: Boolean
    )
}

