package com.charles.livecaptionn.speech

data class SpeechResult(
    val text: String,
    val isFinal: Boolean
)

/** Bracket synchronous native work without changing which capture worker owns it. */
internal inline fun <T> withRecognitionProgress(onProcessing: (Boolean) -> Unit, work: () -> T): T {
    onProcessing(true)
    return try { work() } finally { onProcessing(false) }
}
