package com.charles.livecaptionn.service

import com.charles.livecaptionn.speech.RecognitionStatus
import kotlinx.coroutines.CancellationException

/** Called only after the explicit overlay action synchronously claims its recommendation. */
internal suspend fun applyClaimedLanguageSuggestion(
    store: CaptionRuntimeStore,
    code: String,
    writeLanguage: suspend (String) -> Unit,
    stopSession: () -> Unit,
    openMain: () -> Unit,
    onNavigationFailure: (String) -> Unit = {}
) {
    if (store.state.value.changingLanguageCode != code) return
    try {
        writeLanguage(code)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        store.update { runtime ->
            runtime.copy(changingLanguageCode = null,
                suggestedLanguageCode = code.takeIf { runtime.running && !runtime.stopping },
                lastError = runtime.lastError ?: "음성 언어를 변경하지 못했습니다. 잠시 후 다시 눌러 주세요.")
        }
        return
    }
    store.update { it.copy(changingLanguageCode = null, restartLanguageCode = code,
        suggestedLanguageCode = null) }
    stopSession()
    try {
        // Navigation only. Permission requests and capture still require the main Start button.
        openMain()
    } catch (_: RuntimeException) {
        val message = "앱을 열지 못했습니다. 홈 화면에서 앱을 열고 번역 다시 시작을 눌러 주세요."
        store.update { it.copy(lastError = it.lastError ?: message,
            status = if (it.running) it.status else RecognitionStatus.ERROR) }
        onNavigationFailure(message)
    }
}
