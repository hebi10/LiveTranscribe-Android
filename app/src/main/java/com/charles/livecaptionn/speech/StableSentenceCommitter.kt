package com.charles.livecaptionn.speech

internal data class SentenceCommitResult(val sentences: List<String>, val revisionConflict: Boolean = false)

internal class StableSentenceCommitter {
    private var windowId = Long.MIN_VALUE
    private var previousKey = ""
    private var previousText = ""
    private var committedKey = ""
    private var closed = false

    fun accept(id: Long, hypothesis: String, isFinal: Boolean): SentenceCommitResult {
        if (id < windowId || (id == windowId && closed)) return SentenceCommitResult(emptyList())
        if (id != windowId) {
            windowId = id
            previousKey = ""
            previousText = ""
            committedKey = ""
            closed = false
        }
        val text = hypothesis.trim()
        val keyed = key(text)
        val committedEnd = if (committedKey.isEmpty() || keyed.ends.size < committedKey.length) 0
            else keyed.ends[committedKey.length - 1]
        if (!keyed.value.startsWith(committedKey) ||
            (committedKey.isNotEmpty() && extendsCommittedWord(text, committedEnd))) {
            previousKey = keyed.value
            previousText = text
            closed = isFinal
            return SentenceCommitResult(emptyList(), revisionConflict = isFinal)
        }
        var start = if (committedKey.isEmpty()) 0 else keyed.ends[committedKey.length - 1]
        if (committedKey.isNotEmpty()) {
            while (start < text.length && text[start] in ".。！？!?…\"'”’」』") start++
            while (start < text.length && text[start].isWhitespace()) start++
        }
        val output = mutableListOf<String>()
        val ends = sentenceEnds(text).toMutableList()
        if (isFinal && (ends.isEmpty() || ends.last() != text.length)) ends += text.length
        for (end in ends) {
            if (end <= start) continue
            val prefix = key(text.substring(0, end)).value
            if (!isFinal && (!previousKey.startsWith(prefix) ||
                (prefix.isNotEmpty() && extendsCommittedWord(previousText, key(previousText).ends[prefix.length - 1])))) break
            val sentence = text.substring(start, end).trim()
            if (key(sentence).value.isNotEmpty()) output += sentence
            committedKey = prefix
            start = end
        }
        previousKey = keyed.value
        previousText = text
        closed = isFinal
        return SentenceCommitResult(output)
    }

    private data class Key(val value: String, val ends: List<Int>)

    private fun key(text: String): Key {
        val value = StringBuilder()
        val ends = mutableListOf<Int>()
        text.forEachIndexed { index, char ->
            if (char.isLetterOrDigit()) {
                value.append(char.lowercaseChar())
                ends += index + 1
            }
        }
        return Key(value.toString(), ends)
    }

    private fun sentenceEnds(text: String): List<Int> {
        val ends = mutableListOf<Int>()
        var index = 0
        while (index < text.length) {
            val char = text[index]
            val boundary = char in "。！？!?\n" || (char == '.' && isPeriodBoundary(text, index))
            if (boundary) {
                var end = index + 1
                while (end < text.length && text[end] in "。！？!?.”’\"」』") end++
                ends += end
                index = end
            } else index++
        }
        return ends
    }

    private fun isPeriodBoundary(text: String, index: Int): Boolean {
        val next = text.getOrNull(index + 1)
        if (text.getOrNull(index - 1)?.isDigit() == true && next?.isDigit() == true) return false
        if (next != null && !next.isWhitespace() && next !in "\"'”’」』") return false
        val token = text.substring(0, index).takeLastWhile { it.isLetter() || it == '.' }.lowercase()
        if (token in setOf("mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "e.g", "i.e")) return false
        if ((token.length == 1 && token[0] in 'a'..'z') ||
            (token.contains('.') && token.split('.').all { it.length == 1 && it[0] in 'a'..'z' })) return false
        return true
    }

    private fun extendsCommittedWord(text: String, end: Int): Boolean {
        val next = text.getOrNull(end) ?: return false
        if (next.isLetterOrDigit()) return true
        if (next in "'’-" && text.getOrNull(end + 1)?.isLetterOrDigit() == true) return true
        return next == '.' && text.getOrNull(end - 1)?.isDigit() == true &&
            text.getOrNull(end + 1)?.isDigit() == true
    }
}
