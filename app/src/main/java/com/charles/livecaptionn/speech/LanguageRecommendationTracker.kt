package com.charles.livecaptionn.speech

/**
 * One finalized speech interval approximates an utterance, not a grammatical sentence.
 * We require two consecutive distinct intervals before suggesting a switch.
 */
class LanguageRecommendationTracker(
    private val selectedCode: String,
    private val requiredIntervals: Int = 2
) {
    private var lastCode: String? = null
    private var matchingIntervals = 0
    private val suppressed = mutableSetOf<String>()
    private var shown: String? = null

    init {
        require(requiredIntervals >= 2)
    }

    fun observe(detectedCode: String?): String? {
        val code = detectedCode?.lowercase()?.takeIf { it in SUPPORTED }
        if (code == null || code == selectedCode || code in suppressed) {
            lastCode = null
            matchingIntervals = 0
            return null
        }
        matchingIntervals = if (lastCode == code) matchingIntervals + 1 else 1
        lastCode = code
        if (matchingIntervals < requiredIntervals || shown == code) return null
        shown = code
        return code
    }

    fun dismiss(code: String) {
        suppressed.add(code)
        shown = null
        matchingIntervals = 0
        lastCode = null
    }

    companion object {
        val SUPPORTED = setOf("en", "ja")
        fun label(code: String): String = when (code) {
            "en" -> "영어"
            "ja" -> "일본어"
            else -> "알 수 없는 언어"
        }
    }
}
