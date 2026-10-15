package com.charles.livecaptionn.settings

import java.util.Locale

/**
 * Recognition modes are user choices; detected languages belong to individual utterances.
 * Automatic captions label only the Qwen3-ASR / Hy-MT2 supported-language intersection.
 * Sources: https://huggingface.co/Qwen/Qwen3-ASR-0.6B#released-models-description-and-download
 *          https://huggingface.co/tencent/Hy-MT2-1.8B#supported-languages
 * Qwen's Filipino `fil` and Hy-MT2's `tl` refer to the same supported language here.
 */
object RecognitionLanguages {
    const val AUTO = "auto"
    const val UNKNOWN = "und"
    val modes: Set<String> = setOf(AUTO, "en", "ja")

    private class Language(val code: String, val korean: String, vararg val aliases: String)
    private val languages = listOf(
        Language("zh", "중국어", "Chinese", "Mandarin", "Mandarin Chinese", "Simplified Chinese", "Traditional Chinese", "cmn", "zho", "chi"),
        Language("en", "영어", "English", "eng"),
        Language("yue", "광둥어", "Cantonese", "Cantonese Chinese", "Yue Chinese"),
        Language("ar", "아랍어", "Arabic", "ara"),
        Language("de", "독일어", "German", "deu", "ger"),
        Language("fr", "프랑스어", "French", "fra", "fre"),
        Language("es", "스페인어", "Spanish", "spa"),
        Language("pt", "포르투갈어", "Portuguese", "por"),
        Language("id", "인도네시아어", "Indonesian", "ind", "in"),
        Language("it", "이탈리아어", "Italian", "ita"),
        Language("ko", "한국어", "Korean", "kor"),
        Language("ru", "러시아어", "Russian", "rus"),
        Language("th", "태국어", "Thai", "tha"),
        Language("vi", "베트남어", "Vietnamese", "vie"),
        Language("ja", "일본어", "Japanese", "jpn"),
        Language("tr", "튀르키예어", "Turkish", "tur"),
        Language("hi", "힌디어", "Hindi", "hin"),
        Language("ms", "말레이어", "Malay", "msa", "may"),
        Language("nl", "네덜란드어", "Dutch", "nld", "dut"),
        Language("pl", "폴란드어", "Polish", "pol"),
        Language("cs", "체코어", "Czech", "ces", "cze"),
        Language("fil", "필리핀어", "Filipino", "Tagalog", "tl", "tgl"),
        Language("fa", "페르시아어", "Persian", "Farsi", "fas", "per")
    )
    val supportedSourceCodes: Set<String> = languages.map { it.code }.toSet()
    private val namesToCodes = buildMap {
        languages.forEach { language ->
            (listOf(language.code, language.korean) + language.aliases).forEach { put(key(it), language.code) }
        }
    }
    private val labels = languages.associate { it.code to it.korean }
    private val languageTag = Regex("[a-z]{2,3}(?:-[a-z0-9]{2,8})+")

    /** Persisted selections never become an unsupported manual language. */
    fun normalizeMode(raw: String?): String = modeOrNull(raw) ?: AUTO
    fun isValidMode(raw: String?): Boolean = modeOrNull(raw) != null

    /** Unknown/unsupported/empty results stay unknown, never English, Japanese, or Korean. */
    fun normalizeDetected(raw: String?): String {
        val value = key(raw)
        namesToCodes[value]?.let { return it }
        if (languageTag.matches(value)) namesToCodes[value.substringBefore('-')]?.let { return it }
        return UNKNOWN
    }

    fun koreanLabel(raw: String?): String = when (key(raw)) {
        AUTO -> "자동"
        else -> labels[normalizeDetected(raw)] ?: "언어 미확인"
    }

    private fun modeOrNull(raw: String?): String? {
        if (key(raw) == AUTO) return AUTO
        return normalizeDetected(raw).takeIf { it == "en" || it == "ja" }
    }
    private fun key(raw: String?): String = raw.orEmpty().trim().lowercase(Locale.ROOT).replace('_', '-')
}
