package com.charles.livecaptionn.speech

/** Preserves segment boundaries while suppressing unchanged partial text. */
internal class StreamingSpeechResultEmitter(
    private val onResult: (SpeechResult) -> Unit
) {
    private var lastPartial = ""

    fun emit(result: VoskStreamingSession.FeedResult) {
        if (result.accepted) {
            lastPartial = ""
            onResult(SpeechResult(result.text, isFinal = true))
        } else if (result.text.isNotBlank() && result.text != lastPartial) {
            lastPartial = result.text
            onResult(SpeechResult(result.text, isFinal = false))
        }
    }
}
