package com.charles.livecaptionn.service

import com.charles.livecaptionn.speech.SpeechResult
import com.charles.livecaptionn.translation.TranslationRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptionLanguageSnapshotTest {
    @Test fun automaticLanguageComesFromCurrentResultAndUnknownDoesNotInherit() {
        val japanese = resolveCaptionSpeech(SpeechResult("私 は 学生 です", true, "ja"), "auto")
        val unknown = resolveCaptionSpeech(SpeechResult("私 は 学生 です", true, null), "auto")
        assertEquals("ja", japanese.sourceLanguageCode)
        assertEquals("私は学生です", japanese.text)
        assertEquals("und", unknown.sourceLanguageCode)
        assertEquals("私 は 学生 です", unknown.text)
        assertTrue(unknown.autoDetect)
    }

    @Test fun manualLanguageOverridesDetectedMetadata() {
        val english = resolveCaptionSpeech(SpeechResult("私 は 学生 です", true, "ja"), "en")
        val japanese = resolveCaptionSpeech(SpeechResult("私 は 学生 です", true, "en"), "ja")
        assertEquals("en", english.sourceLanguageCode)
        assertEquals("私 は 学生 です", english.text)
        assertEquals("ja", japanese.sourceLanguageCode)
        assertEquals("私は学生です", japanese.text)
        assertFalse(english.autoDetect)
        assertFalse(japanese.autoDetect)
    }

    @Test fun unsupportedDetectionStaysUnknownAndInvalidModeUsesAutomaticResolution() {
        val unsupported = resolveCaptionSpeech(SpeechResult("text", true, "not-a-language"), "auto")
        val invalidMode = resolveCaptionSpeech(SpeechResult("bonjour", true, "fr"), "legacy-value")
        assertEquals("und", unsupported.sourceLanguageCode)
        assertEquals("fr", invalidMode.sourceLanguageCode)
        assertTrue(invalidMode.autoDetect)
    }

    @Test fun stoppingTailRetainsItsDetectedLanguageAndExistingError() {
        val snapshot = resolveCaptionSpeech(SpeechResult("안녕하세요", true, "ko"), "auto")
        val runtime = applyTranscriptResult(
            CaptionRuntimeState(stopping = true, lastError = "capture ended"),
            snapshot.text, true, 1, snapshot.sourceLanguageCode
        )
        assertEquals("ko", runtime.transcriptLines.single().sourceLanguageCode)
        assertEquals("capture ended", runtime.lastError)
        assertEquals("ko", captionHistoryEntry(snapshot.request(1, true, null), snapshot.text).sourceLanguage)
    }

    @Test fun languageChangeWithSamePartialTextCreatesNewSnapshotAndRejectsOldTranslation() {
        val first = applyTranscriptResult(CaptionRuntimeState(), "hello", false, 1, "en")
        val changed = applyTranscriptResult(first, "hello", false, 2, "ja")
        assertEquals(2L, changed.transcriptLines.single().id)
        assertEquals("ja", changed.transcriptLines.single().sourceLanguageCode)
        val stale = applyTranscriptTranslation(changed, 1, "old", false)
        assertEquals("", stale.transcriptLines.single().translatedText)
    }

    @Test fun finalLanguageRevisionDoesNotPromotePartialTranslation() {
        val partial = applyTranscriptTranslation(
            applyTranscriptResult(CaptionRuntimeState(), "hello", false, 1, "und"), 1, "preview", false
        )
        val final = applyTranscriptResult(partial, "hello", true, 2, "en")
        assertEquals(2L, final.transcriptLines.single().id)
        assertEquals("en", final.transcriptLines.single().sourceLanguageCode)
        assertEquals("", final.transcriptLines.single().translatedText)
        assertEquals("", applyTranscriptTranslation(final, 1, "late preview", false).translatedText)
        assertEquals("complete", applyTranscriptTranslation(final, 2, "complete", true).translatedText)
    }

    @Test fun mixedLanguageQueueKeepsTranslationAndHistorySourcesWithEachSnapshot() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val calls = mutableListOf<Triple<String, String, Boolean>>()
        val historySources = mutableListOf<String>()
        val repository = object : TranslationRepository {
            override suspend fun translate(text: String, sourceCode: String, targetCode: String, autoDetect: Boolean): String {
                calls += Triple(text, sourceCode, autoDetect)
                assertEquals("ko", targetCode)
                if (calls.size == 1) {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
                return "번역: $text"
            }
        }
        val queue = CaptionTranslationQueue<CaptionTranslationRequest>(this, translate = { request ->
            val translated = translateCaptionRequest(request, repository)
            val entry = captionHistoryEntry(request, translated)
            assertEquals(request.text, entry.originalText)
            assertEquals(translated, entry.translatedText)
            historySources += entry.sourceLanguage
        })
        queue.submitFinal(resolveCaptionSpeech(SpeechResult("こんにちは", true, "ja"), "auto").request(1, true, null))
        runCurrent()
        firstStarted.await()
        queue.submitFinal(resolveCaptionSpeech(SpeechResult("hello", true, "en"), "auto").request(2, true, null))
        queue.submitFinal(resolveCaptionSpeech(SpeechResult("bonjour", true, null), "auto").request(3, true, null))
        releaseFirst.complete(Unit)
        assertTrue(queue.finish())
        assertEquals(listOf("ja", "en", "und"), calls.map { it.second })
        assertTrue(calls.all { it.third })
        assertEquals(listOf("ja", "en", "und"), historySources)
    }

    @Test fun manualRequestCarriesManualTranslationFlagAndHistoryLanguage() = runTest {
        val request = resolveCaptionSpeech(SpeechResult("こんにちは", true, "ja"), "en").request(1, true, null)
        val repository = object : TranslationRepository {
            override suspend fun translate(text: String, sourceCode: String, targetCode: String, autoDetect: Boolean): String {
                assertEquals("en", sourceCode)
                assertFalse(autoDetect)
                return "완료"
            }
        }
        val entry = captionHistoryEntry(request, translateCaptionRequest(request, repository))
        assertEquals("en", entry.sourceLanguage)
        assertEquals("ko", entry.targetLanguage)
    }

    @Test fun automaticKoreanMetadataStillRequestsAutomaticTranslation() = runTest {
        val request = resolveCaptionSpeech(SpeechResult("안녕 hello", true, "ko"), "auto").request(1, true, null)
        val repository = object : TranslationRepository {
            override suspend fun translate(text: String, sourceCode: String, targetCode: String, autoDetect: Boolean): String {
                assertEquals("ko", sourceCode)
                assertTrue(autoDetect)
                return "안녕, 안녕하세요"
            }
        }
        assertEquals("안녕, 안녕하세요", translateCaptionRequest(request, repository))
        assertEquals("ko", captionHistoryEntry(request, "완료").sourceLanguage)
    }
}
