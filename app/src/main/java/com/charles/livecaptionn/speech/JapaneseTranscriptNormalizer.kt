package com.charles.livecaptionn.speech

/** Removes recognizer token spacing only when Japanese characters surround it. */
internal fun normalizeRecognizedTranscript(text: String, languageCode: String): String {
    val trimmed = text.trim()
    if (!languageCode.substringBefore('-').equals("ja", ignoreCase = true)) return trimmed

    val normalized = StringBuilder(trimmed.length)
    var index = 0
    while (index < trimmed.length) {
        val codePoint = trimmed.codePointAt(index)
        if (!isHorizontalWhitespace(codePoint)) {
            normalized.appendCodePoint(codePoint)
            index += Character.charCount(codePoint)
            continue
        }

        val whitespaceStart = index
        do {
            index += Character.charCount(trimmed.codePointAt(index))
        } while (index < trimmed.length && isHorizontalWhitespace(trimmed.codePointAt(index)))

        val betweenJapaneseCharacters = whitespaceStart > 0 && index < trimmed.length &&
            isJapaneseCodePoint(trimmed.codePointBefore(whitespaceStart)) &&
            isJapaneseCodePoint(trimmed.codePointAt(index))
        if (!betweenJapaneseCharacters) normalized.append(trimmed, whitespaceStart, index)
    }
    return normalized.toString()
}

private fun isHorizontalWhitespace(codePoint: Int): Boolean =
    codePoint == '\t'.code || Character.getType(codePoint) == Character.SPACE_SEPARATOR.toInt()

private fun isJapaneseCodePoint(codePoint: Int): Boolean =
    codePoint == 0x3005 || codePoint == 0x30FC || when (Character.UnicodeScript.of(codePoint)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA -> true
        else -> false
    }
