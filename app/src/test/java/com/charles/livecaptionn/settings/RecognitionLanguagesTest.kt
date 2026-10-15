package com.charles.livecaptionn.settings

import org.junit.Assert.*
import org.junit.Test

class RecognitionLanguagesTest {
    @Test fun selectableModesAreAutomaticEnglishAndJapanese() {
        assertEquals(setOf("auto", "en", "ja"), RecognitionLanguages.modes)
        listOf("auto", "en", "ja").forEach { assertTrue(RecognitionLanguages.isValidMode(it)) }
        listOf("fr", "ko", "und", "unsupported", "").forEach { assertFalse(RecognitionLanguages.isValidMode(it)) }
    }

    @Test fun supportedSourcesMatchTheDocumentedModelIntersection() {
        assertEquals(setOf("zh", "en", "yue", "ar", "de", "fr", "es", "pt", "id", "it", "ko", "ru", "th", "vi", "ja", "tr", "hi", "ms", "nl", "pl", "cs", "fil", "fa"), RecognitionLanguages.supportedSourceCodes)
        listOf("sv", "da", "fi", "el", "hu", "mk", "ro", "km", "uk").forEach {
            assertEquals("Only languages supported by both models are labelled", "und", RecognitionLanguages.normalizeDetected(it))
        }
    }

    @Test fun detectedNamesTagsAndLanguageAliasesNormalizeWithoutInventingALanguage() {
        mapOf(" English " to "en", "en-US" to "en", "JAPANESE" to "ja", "ja_JP" to "ja", "Korean" to "ko", "ko-KR" to "ko", "zh-Hant" to "zh", "Mandarin Chinese" to "zh", "Cantonese" to "yue", "Filipino" to "fil", "tl" to "fil", "Tagalog" to "fil", "French" to "fr", "Persian" to "fa").forEach { (raw, expected) ->
            assertEquals(raw, expected, RecognitionLanguages.normalizeDetected(raw))
        }
        listOf(null, "", "unknown", "und", "auto", "Swedish", "not-a-language", "English and Japanese").forEach {
            assertEquals("und", RecognitionLanguages.normalizeDetected(it))
        }
    }

    @Test fun persistedModeNormalizationKeepsManualChoicesAndFallsBackToAutomatic() {
        assertEquals("en", RecognitionLanguages.normalizeMode("en"))
        assertEquals("ja", RecognitionLanguages.normalizeMode("ja"))
        assertEquals("ja", RecognitionLanguages.normalizeMode("JA"))
        listOf(null, "", "fr", "ko", "und", "invalid").forEach {
            assertEquals("auto", RecognitionLanguages.normalizeMode(it))
        }
    }

    @Test fun koreanLabelsDistinguishAutomaticSelectionAndUnknownDetection() {
        assertEquals("자동", RecognitionLanguages.koreanLabel("auto"))
        assertEquals("언어 미확인", RecognitionLanguages.koreanLabel("und"))
        assertEquals("언어 미확인", RecognitionLanguages.koreanLabel("unsupported"))
        assertEquals("영어", RecognitionLanguages.koreanLabel("en"))
        assertEquals("일본어", RecognitionLanguages.koreanLabel("ja"))
        assertEquals("한국어", RecognitionLanguages.koreanLabel("ko"))
        assertEquals("광둥어", RecognitionLanguages.koreanLabel("yue"))
        RecognitionLanguages.supportedSourceCodes.forEach { assertNotEquals("언어 미확인", RecognitionLanguages.koreanLabel(it)) }
    }
}
