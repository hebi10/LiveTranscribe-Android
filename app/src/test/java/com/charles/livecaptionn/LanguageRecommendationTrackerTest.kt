package com.charles.livecaptionn

import com.charles.livecaptionn.speech.LanguageRecommendationTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageRecommendationTrackerTest {
    @Test fun twoConsecutiveJapaneseIntervalsSuggestJapanese() {
        val tracker = LanguageRecommendationTracker("en")
        assertNull(tracker.observe("ja"))
        assertEquals("ja", tracker.observe("ja"))
        assertNull(tracker.observe("ja"))
    }

    @Test fun theCurrentLanguageNeverTriggersSuggestion() {
        val tracker = LanguageRecommendationTracker("en")
        assertNull(tracker.observe("en"))
        assertNull(tracker.observe("en"))
    }

    @Test fun mismatchedSeriesResets() {
        val tracker = LanguageRecommendationTracker("en")
        assertNull(tracker.observe("ja"))
        assertNull(tracker.observe("ko"))
        assertNull(tracker.observe("ja"))
        assertEquals("ja", tracker.observe("ja"))
    }

    @Test fun userDismissalSuppressesRepeatedSuggestions() {
        val tracker = LanguageRecommendationTracker("en")
        tracker.observe("ja")
        assertEquals("ja", tracker.observe("ja"))
        tracker.dismiss("ja")
        assertNull(tracker.observe("ja"))
        assertNull(tracker.observe("ja"))
    }

    @Test fun reverseEnglishSuggestionIsSupported() {
        val tracker = LanguageRecommendationTracker("ja")
        assertNull(tracker.observe("en"))
        assertEquals("en", tracker.observe("en"))
    }
}
