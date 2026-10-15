package com.charles.livecaptionn

import com.charles.livecaptionn.service.*
import com.charles.livecaptionn.speech.RecognitionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptionLanguageChangeTest {
    private fun store() = CaptionRuntimeStore().apply {
        update { it.copy(running = true, status = RecognitionStatus.LISTENING, suggestedLanguageCode = "ja") }
    }

    @Test fun synchronousClaimRejectsSecondTapEvenIfDetectorRepublishesWhileWriting() = runTest {
        val store = store()
        val releaseWrite = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        assertTrue(store.claimLanguageSuggestion("ja"))
        assertNull(store.state.value.suggestedLanguageCode)
        assertEquals("ja", store.state.value.changingLanguageCode)
        launch {
            applyClaimedLanguageSuggestion(store, "ja",
                writeLanguage = { events += "write"; releaseWrite.await() },
                stopSession = { events += "stop"; store.update { stoppingCaptionSession(it) } },
                openMain = { events += "open" })
        }
        runCurrent()
        store.update { it.copy(suggestedLanguageCode = "ja") }
        assertFalse(store.claimLanguageSuggestion("ja"))
        assertEquals(listOf("write"), events)
        releaseWrite.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("write", "stop", "open"), events)
        assertEquals("ja", store.state.value.restartLanguageCode)
        assertNull(store.state.value.changingLanguageCode)
    }

    @Test fun unclaimedOrInvalidRecommendationCannotWriteOrNavigate() = runTest {
        val store = store()
        assertFalse(store.claimLanguageSuggestion("fr"))
        assertFalse(store.claimLanguageSuggestion("en"))
        store.update { it.copy(stopping = true) }
        assertFalse(store.claimLanguageSuggestion("ja"))
        store.update { it.copy(stopping = false, running = false) }
        assertFalse(store.claimLanguageSuggestion("ja"))
        applyClaimedLanguageSuggestion(store, "ja", { fail("unclaimed write") },
            { fail("unclaimed stop") }, { fail("unclaimed navigation") })
    }

    @Test fun failedSettingsWriteDoesNotClaimSuccessStopOrOpenAndCanBeRetried() = runTest {
        val store = store()
        assertTrue(store.claimLanguageSuggestion("ja"))
        applyClaimedLanguageSuggestion(store, "ja", { throw IllegalStateException("storage unavailable") },
            { fail("cannot stop after failed change") }, { fail("cannot open after failed change") })
        val runtime = store.state.value
        assertTrue(runtime.running)
        assertNull(runtime.restartLanguageCode)
        assertNull(runtime.restartNotice)
        assertNull(runtime.changingLanguageCode)
        assertNotNull(runtime.lastError)
        assertEquals("ja", runtime.suggestedLanguageCode)
        assertTrue(store.claimLanguageSuggestion("ja"))
    }

    @Test fun failedSuspendedWriteCannotReplaceAnEndingCaptureFailure() = runTest {
        val store = store()
        val releaseWrite = CompletableDeferred<Unit>()
        assertTrue(store.claimLanguageSuggestion("ja"))
        launch {
            applyClaimedLanguageSuggestion(store, "ja", {
                releaseWrite.await()
                throw IllegalStateException("settings write failed")
            }, { fail("failed change cannot stop again") }, { fail("failed change cannot navigate") })
        }
        runCurrent()
        store.update { stoppingCaptionSession(it, "capture permission revoked") }
        releaseWrite.complete(Unit)
        advanceUntilIdle()
        assertEquals("capture permission revoked", store.state.value.lastError)
        assertNull(store.state.value.suggestedLanguageCode)
        assertNull(store.state.value.restartLanguageCode)
    }

    @Test fun successfulWritePublishesRestartThenStopsBeforeOpeningMain() = runTest {
        val store = store()
        var selected = "en"
        assertTrue(store.claimLanguageSuggestion("ja"))
        applyClaimedLanguageSuggestion(store, "ja", { selected = it }, {
            assertEquals("ja", selected)
            assertEquals("ja", store.state.value.restartLanguageCode)
            store.update { stoppingCaptionSession(it) }
        }, {
            assertEquals(CaptionPhase.STOPPING, store.state.value.phase)
            assertTrue(store.state.value.running)
        })
        val ending = store.state.value
        assertTrue(ending.restartNotice!!.contains("일본어"))
        assertNull(ending.infoMessage)
        val complete = completedCaptionSession(null, null, true,
            restartLanguageCode = ending.restartLanguageCode)
        assertEquals("ja", complete.restartLanguageCode)
        assertFalse(complete.running)
        assertNull(complete.infoMessage)
        assertNotNull(complete.restartNotice)
    }

    @Test fun navigationFailureAfterImmediateCleanupStillShowsErrorAndTruthfulRestartState() = runTest {
        val store = store()
        var failureReported: String? = null
        assertTrue(store.claimLanguageSuggestion("ja"))
        applyClaimedLanguageSuggestion(store, "ja", {}, {
            store.update { completedCaptionSession(null, null, true, restartLanguageCode = it.restartLanguageCode) }
        }, { throw SecurityException("navigation rejected") }, { failureReported = it })
        assertEquals("ja", store.state.value.restartLanguageCode)
        assertEquals(CaptionPhase.ERROR, store.state.value.phase)
        assertEquals(failureReported, store.state.value.lastError)
        assertTrue(failureReported!!.contains("홈 화면"))
    }

    @Test fun navigationFailureCannotReplaceAnEarlierCaptureFailure() = runTest {
        val store = store()
        var navigationFailure: String? = null
        assertTrue(store.claimLanguageSuggestion("ja"))
        applyClaimedLanguageSuggestion(store, "ja", {
            store.update { stoppingCaptionSession(it, "capture permission revoked") }
        }, {}, { throw SecurityException("navigation rejected") }, { navigationFailure = it })
        assertEquals("capture permission revoked", store.state.value.lastError)
        assertEquals("ja", store.state.value.restartLanguageCode)
        assertTrue(navigationFailure!!.contains("홈 화면"))
    }

    @Test fun restartPromptSurvivesDrainErrorsAndClearsOnlyForExplicitStartOrOtherSelection() {
        val runtime = completedCaptionSession("tail failed", null, true, restartLanguageCode = "ja")
        assertEquals("ja", runtime.restartLanguageCode)
        assertEquals("tail failed", runtime.lastError)
        assertEquals(runtime, manuallySelectedCaptionLanguage(runtime, "ja"))
        assertNull(manuallySelectedCaptionLanguage(runtime, "en").restartLanguageCode)
        assertEquals("ja", manuallySelectedCaptionLanguage(runtime.copy(running = true), "en").restartLanguageCode)
        assertNull(preparedCaptionStart(runtime).restartLanguageCode)
        assertNull(preparedCaptionStart(runtime).restartNotice)
    }
}
