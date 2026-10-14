package com.charles.livecaptionn.service

import kotlinx.coroutines.CancellationException

internal suspend fun stopSpeechEngineSafely(stop: suspend () -> Unit, onFailure: (Exception) -> Unit) {
    try {
        stop()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        onFailure(failure)
    }
}
