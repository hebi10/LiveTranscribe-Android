package com.charles.livecaptionn.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Report a slow native join without cancelling it or freeing resources still in use. */
internal suspend fun finishSpeechInference(
    stopSpeech: suspend () -> Unit,
    joinLanguageDetection: suspend () -> Unit,
    releaseLanguageIdentifier: suspend () -> Unit,
    onWaiting: () -> Unit,
    onFailure: (Exception) -> Unit,
    waitNoticeMillis: Long = 3_000
) = coroutineScope {
    val waitingNotice = launch {
        delay(waitNoticeMillis)
        onWaiting()
    }
    try {
        stopSpeechEngineSafely(stopSpeech, onFailure)
        joinLanguageDetection()
        stopSpeechEngineSafely(releaseLanguageIdentifier, onFailure)
    } finally {
        waitingNotice.cancelAndJoin()
    }
}

internal suspend fun stopSpeechEngineSafely(stop: suspend () -> Unit, onFailure: (Exception) -> Unit) {
    try {
        stop()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        onFailure(failure)
    }
}
