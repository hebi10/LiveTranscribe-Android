package com.charles.livecaptionn.speech

import org.junit.Assert.*
import org.junit.Test

class StableSentenceCommitterTest {
    @Test fun openingQuoteOfTheNextSentenceIsKeptAfterWhitespace() {
        val committer = StableSentenceCommitter()
        committer.accept(1, "Hello.", false)
        committer.accept(1, "Hello. \"Next sentence.\"", false)
        assertEquals(listOf("\"Next sentence.\""), committer.accept(1, "Hello. \"Next sentence.\"", true).sentences)
    }

    @Test fun shorterWordCannotBeCertifiedFromAPreviousLongerWord() {
        val committer = StableSentenceCommitter()
        committer.accept(1, "The carpet is red.", false)
        assertTrue(committer.accept(1, "The car.", false).sentences.isEmpty())
        assertEquals(listOf("The car."), committer.accept(1, "The car. Next", false).sentences)
    }

    @Test fun extendingAnAlreadyCommittedWordIsAConflictRatherThanAMalformedTail() {
        for (changed in listOf("The carpet. Next", "The car's paint. Next")) {
            val committer = StableSentenceCommitter()
            committer.accept(1, "The car.", false)
            committer.accept(1, "The car. Next", false)
            val result = committer.accept(1, changed, true)
            assertTrue(result.revisionConflict)
            assertTrue(result.sentences.isEmpty())
        }
    }

    @Test fun singleKoreanWordAndOpeningQuotesArePreserved() {
        val committer = StableSentenceCommitter()
        committer.accept(1, "\"네.\" 다음", false)
        assertEquals(listOf("\"네.\""), committer.accept(1, "\"네.\" 다음 문장", false).sentences)
    }

    @Test fun stableCompletedSentenceIsCommittedBeforeTheAudioWindowEnds() {
        val committer = StableSentenceCommitter()
        assertTrue(committer.accept(1, "今日は晴れです", false).sentences.isEmpty())
        assertEquals(listOf("今日は晴れです。"), committer.accept(1, "今日は晴れです。散歩に", false).sentences)
        assertEquals(listOf("散歩に行きます。"), committer.accept(1, "今日は晴れです。散歩に行きます。", true).sentences)
        assertTrue(committer.accept(1, "今日は晴れです。散歩に行きます。", true).sentences.isEmpty())
    }

    @Test fun changingHypothesisDoesNotPublishAnUnstableSentence() {
        val committer = StableSentenceCommitter()
        committer.accept(1, "I want tea.", false)
        assertTrue(committer.accept(1, "I want coffee. Next", false).sentences.isEmpty())
        assertEquals(listOf("I want coffee."), committer.accept(1, "I want coffee. Next sentence", false).sentences)
    }

    @Test fun finalResultWithoutPunctuationFlushesTheRemainingTailOnce() {
        val committer = StableSentenceCommitter()
        committer.accept(1, "Hello.", false)
        assertEquals(listOf("Hello."), committer.accept(1, "Hello. A final tail", false).sentences)
        assertEquals(listOf("A final tail"), committer.accept(1, "Hello. A final tail", true).sentences)
    }

    @Test fun decimalAndCommonEnglishAbbreviationsDoNotSplitSentences() {
        val text = "Dr. Smith paid 3.14 dollars. He left."
        val committer = StableSentenceCommitter()
        committer.accept(1, text, false)
        assertEquals(listOf("Dr. Smith paid 3.14 dollars.", "He left."), committer.accept(1, text, true).sentences)
    }

    @Test fun quotedJapaneseAndRepeatedSentencesKeepTheirOwnBoundaries() {
        val text = "「はい。」はい。続き"
        val committer = StableSentenceCommitter()
        committer.accept(1, text, false)
        assertEquals(listOf("「はい。」", "はい。"), committer.accept(1, text, false).sentences)
        assertEquals(listOf("続き"), committer.accept(1, text, true).sentences)
    }

    @Test fun whitespaceAndPunctuationRevisionCannotRepeatCommittedWords() {
        val committer = StableSentenceCommitter()
        committer.accept(1, "Hello world.", false)
        committer.accept(1, "Hello world. Again", false)
        assertEquals(listOf("Again!"), committer.accept(1, "Hello  world! Again!", true).sentences)
    }

    @Test fun revisionBeforeCommittedPrefixReportsConflictWithoutDuplicatingCaptions() {
        val committer = StableSentenceCommitter()
        committer.accept(1, "I want tea.", false)
        committer.accept(1, "I want tea. More", false)
        val result = committer.accept(1, "You want coffee. More", true)
        assertTrue(result.sentences.isEmpty())
        assertTrue(result.revisionConflict)
    }

    @Test fun nextWindowCanRepeatTheSameSpokenSentenceAndOlderWindowsAreIgnored() {
        val committer = StableSentenceCommitter()
        assertEquals(listOf("Hello."), committer.accept(1, "Hello.", true).sentences)
        assertEquals(listOf("Hello."), committer.accept(2, "Hello.", true).sentences)
        assertTrue(committer.accept(1, "Old result.", true).sentences.isEmpty())
    }
}
