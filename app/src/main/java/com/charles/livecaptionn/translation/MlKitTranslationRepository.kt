package com.charles.livecaptionn.translation

import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device translation powered by Google ML Kit.
 *
 * ML Kit ships pre-trained Google Translate models that run entirely on the
 * phone once downloaded. First use of a given language pair triggers a
 * one-time ~30 MB download (handled transparently). After that the pipeline
 * is fully offline — no LibreTranslate server required.
 *
 * Supported languages are whatever `TranslateLanguage.getAllLanguages()`
 * exposes — at the time of writing roughly 59 codes (`en`, `vi`, `es`, `fr`,
 * `de`, `ja`, `ko`, `zh`, etc.).
 *
 * Return-value contract:
 *   - Identity (source == target) or blank input → original text.
 *   - Unsupported pair → original text (graceful degradation so captions
 *     keep flowing; the user can switch backend in settings).
 *   - Runtime failure (download stall, model load error, etc.) → empty
 *     string. The service treats an empty result as "don't overwrite the
 *     last good translation".
 */
class MlKitTranslationRepository : TranslationRepository {

    // One Translator instance per source|target pair, cached for the
    // lifetime of the process so downloads and model loads only happen once.
    private val translators = mutableMapOf<String, Translator>()

    override suspend fun translate(
        text: String,
        sourceCode: String,
        targetCode: String,
        autoDetect: Boolean
    ): String {
        val clean = text.trim()
        if (clean.isEmpty()) return clean
        if (targetCode.isBlank()) return clean

        val src = TranslateLanguage.fromLanguageTag(sourceCode.lowercase())
        val dst = TranslateLanguage.fromLanguageTag(targetCode.lowercase())
        if (src == null || dst == null) {
            Log.w(TAG, "ML Kit does not support pair $sourceCode → $targetCode; passing text through.")
            return clean
        }
        if (src == dst) return clean

        return try {
            val translator = translatorFor(src, dst)
            // Models must have been prepared explicitly by the user.
            val translated = translateBlocking(translator, clean).trim()
            translated
        } catch (t: Throwable) {
            Log.w(TAG, "ML Kit translation failed for $sourceCode→$targetCode", t)
            ""
        }
    }

    /**
     * Runs only after the user explicitly opts in to model download.
     * Does not upload the text that will later be translated.
     */
    suspend fun requireModel(sourceCode: String, targetCode: String, wifiOnly: Boolean = true) {
        val src = TranslateLanguage.fromLanguageTag(sourceCode.lowercase())
            ?: throw IllegalArgumentException("Unsupported source language")
        val dst = TranslateLanguage.fromLanguageTag(targetCode.lowercase())
            ?: throw IllegalArgumentException("Unsupported translation language")
        if (src == dst) return
        ensureModelDownloaded(translatorFor(src, dst), wifiOnly)
    }

    @Synchronized
    private fun translatorFor(src: String, dst: String): Translator {
        val key = "$src|$dst"
        translators[key]?.let { return it }
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(src)
            .setTargetLanguage(dst)
            .build()
        val translator = Translation.getClient(options)
        translators[key] = translator
        return translator
    }

    private suspend fun ensureModelDownloaded(translator: Translator, wifiOnly: Boolean) =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine<Unit> { cont ->
                val builder = DownloadConditions.Builder()
                if (wifiOnly) builder.requireWifi()
                translator.downloadModelIfNeeded(builder.build())
                    .addOnSuccessListener { if (cont.isActive) cont.resume(Unit) }
                    .addOnFailureListener { e ->
                        if (cont.isActive) cont.resumeWithException(e)
                    }
            }
        }

    private suspend fun translateBlocking(translator: Translator, text: String): String =
        suspendCancellableCoroutine { cont ->
            translator.translate(text)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { e -> if (cont.isActive) cont.resumeWithException(e) }
        }

    override fun close() {
        val toClose = synchronized(this) {
            val current = translators.values.toList()
            translators.clear()
            current
        }
        toClose.forEach { translator ->
            try { translator.close() } catch (_: Throwable) {}
        }
    }

    companion object {
        private const val TAG = "MlKitTranslator"
    }
}
