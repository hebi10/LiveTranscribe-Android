package com.charles.livecaptionn.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class JapaneseTranscriptNormalizerTest {
    @Test fun removesRecognizerTokenSpacesBetweenJapaneseCharacters() {
        assertEquals(
            "私は日本語を話します",
            normalizeRecognizedTranscript("  私 は 日本 語 を 話し ます  ", "ja")
        )
    }

    @Test fun handlesCaseInsensitiveJapaneseLanguageTags() {
        for (languageCode in listOf("ja", "JA", "ja-JP", "JA-jp")) {
            assertEquals("日本語", normalizeRecognizedTranscript("日 本 語", languageCode))
        }
    }

    @Test fun joinsKanjiKanaIterationMarksAndProlongedSoundMarks() {
        assertEquals(
            "時々コーヒーを飲む",
            normalizeRecognizedTranscript("時 々 コ ー ヒ ー を 飲 む", "ja")
        )
    }

    @Test fun removesHorizontalWhitespaceRunsBetweenJapaneseCodePoints() {
        assertEquals(
            "東京で日本語",
            normalizeRecognizedTranscript("東\t\t京\u3000で\u00A0日\u2009本\u202F語", "ja")
        )
    }

    @Test fun supportsSupplementaryHanCharacters() {
        assertEquals("𠮷野家", normalizeRecognizedTranscript("𠮷 野 家", "ja"))
    }

    @Test fun preservesLatinDigitBoundariesAndFullWidthCharacterForms() {
        assertEquals(
            "私は New York で 1 2 個の Ｎｅｗ Ｙｏｒｋ を買う",
            normalizeRecognizedTranscript("私 は New York で 1 2 個 の Ｎｅｗ Ｙｏｒｋ を 買う", "ja")
        )
    }

    @Test fun preservesLineBreaksAndAdjacentHorizontalWhitespace() {
        assertEquals(
            "東京\n日本\r\n語を話す",
            normalizeRecognizedTranscript("  東 京\n日 本\r\n語 を\t話 す  ", "ja")
        )
        assertEquals("東 \n 京", normalizeRecognizedTranscript("東 \n 京", "ja"))
    }

    @Test fun preservesPunctuationAndSpacingAroundIt() {
        assertEquals(
            "今日は 、 良い天気 。",
            normalizeRecognizedTranscript("今日 は 、 良い 天気 。", "ja")
        )
    }

    @Test fun otherLanguagesOnlyTrimOuterWhitespace() {
        for (languageCode in listOf("en", "en-US", "zh", "ko", "japanese", "")) {
            assertEquals(
                "日 本 語 and New York 1 2\nnext line",
                normalizeRecognizedTranscript(" \t日 本 語 and New York 1 2\nnext line\r\n ", languageCode)
            )
        }
    }

    @Test fun handlesBlankAndAlreadyNormalizedText() {
        assertEquals("", normalizeRecognizedTranscript(" \t\r\n ", "ja"))
        assertEquals("日本語", normalizeRecognizedTranscript("日本語", "ja"))
    }
}
