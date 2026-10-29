package com.charles.livecaptionn.service

import com.charles.livecaptionn.speech.SpeechResult
import com.charles.livecaptionn.data.HistoryRecordingGate
import com.charles.livecaptionn.translation.TranslationRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptionLanguageSnapshotTest {
    @Test fun pendingAutomaticJapaneseUsesScriptNormalizationWithoutWaitingForMetadata() {
        val snapshot = resolveCaptionSpeech(SpeechResult("私 は New York で 学生 です", true, audioWindowId = 3), "auto")
        assertEquals("und", snapshot.sourceLanguageCode)
        assertEquals("私は New York で学生です", snapshot.text)
        val request = snapshot.request(1, true, null)
        assertEquals(snapshot.text, captionHistoryEntry(request, "번역").originalText)
    }

    @Test fun lateJapaneseDetectionNormalizesThePendingRequestAndHistoryWithoutLosingConsent() {
        val request = resolveCaptionSpeech(SpeechResult("東 京", true, audioWindowId = 3), "auto").request(1, true, null)
        val updated = request.withDetectedWindowLanguage("ja")
        assertEquals("東京", updated.text)
        assertEquals("ja", updated.sourceLanguageCode)
        assertEquals(request.historyConsent, updated.historyConsent)
        assertEquals(request.submittedAtNanos, updated.submittedAtNanos)
        assertEquals("東京", captionHistoryEntry(updated, "도쿄").originalText)
    }

    @Test fun automaticLanguageComesFromCurrentResultAndUnknownDoesNotInherit() {
        val japanese = resolveCaptionSpeech(SpeechResult("私 は 学生 です", true, "ja"), "auto")
        val unknown = resolveCaptionSpeech(SpeechResult("私 は 学生 です", true, null), "auto")
        assertEquals("ja", japanese.sourceLanguageCode)
        assertEquals("私は学生です", japanese.text)
        assertEquals("und", unknown.sourceLanguageCode)
        assertEquals("私は学生です", unknown.text)
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

    @Test fun lateWindowDetectionResolvesOnlyAutomaticRequestMetadata() {
        val consent = HistoryRecordingGate.Consent(3)
        val automatic = resolveCaptionSpeech(SpeechResult("hello", true, null, audioWindowId = 12), "auto")
            .request(1, true, consent).copy(submittedAtNanos = 123)
        val resolved = automatic.withDetectedWindowLanguage("Japanese")
        assertEquals("ja", resolved.sourceLanguageCode)
        assertEquals("ja", captionHistoryEntry(resolved, "완료").sourceLanguage)
        assertEquals(12L, resolved.audioWindowId)
        assertEquals(123L, resolved.submittedAtNanos)
        assertSame(consent, resolved.historyConsent)
        assertTrue(resolved.autoDetect)
        assertEquals(automatic, automatic.withDetectedWindowLanguage(null))
        assertEquals(automatic, automatic.withDetectedWindowLanguage("unsupported"))
        val unscoped = automatic.copy(audioWindowId = null)
        assertEquals(unscoped, unscoped.withDetectedWindowLanguage("ja"))
        val manual = automatic.copy(autoDetect = false, sourceLanguageCode = "en")
        assertEquals(manual, manual.withDetectedWindowLanguage("ja"))
    }

    @Test fun windowLanguageCacheRetainsNewestIdsWhenDetectionFinishesOutOfOrder() {
        val languages = CaptionWindowLanguages(maxWindows = 2)
        assertEquals("ja", languages.record(10, "Japanese"))
        languages.record(30, "en")
        languages.record(20, "fr")
        assertNull(languages[10])
        assertEquals("fr", languages[20])
        assertEquals("en", languages[30])
        languages.record(10, "ko")
        assertNull(languages[10])
        assertNull(languages[21])
        assertNull(languages[null])
        assertEquals("fr", languages[20])
        assertEquals("en", languages[30])
    }

    @Test fun windowLanguageCacheIgnoresUnknownWithoutOverwritingKnownDetection() {
        val languages = CaptionWindowLanguages()
        languages.record(1, "ja")
        assertNull(languages.record(1, null))
        assertNull(languages.record(2, "unsupported"))
        assertEquals("ja", languages[1])
        assertNull(languages[2])
        languages.clear()
        assertNull(languages[1])
    }

    @Test fun defaultWindowLanguageCacheBoundsTheSessionToSixtyFourWindows() {
        val languages = CaptionWindowLanguages()
        for (id in 1L..65L) languages.record(id, "en")
        assertNull(languages[1])
        for (id in 2L..65L) assertEquals("en", languages[id])
    }
}
