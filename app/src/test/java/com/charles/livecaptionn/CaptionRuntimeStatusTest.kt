package com.charles.livecaptionn

import com.charles.livecaptionn.service.*
import com.charles.livecaptionn.overlay.showsOverlayNotice
import com.charles.livecaptionn.settings.CaptionSettings
import com.charles.livecaptionn.speech.RecognitionStatus
import org.junit.Assert.*
import org.junit.Test

class CaptionRuntimeStatusTest {
    private val listening = CaptionRuntimeState(running = true, status = RecognitionStatus.LISTENING)

    @Test fun stoppingRetainsSessionAndCaptionUntilCleanupCompletes() {
        val line = CaptionRuntimeLine(1, "hello", "안녕", isFinal = true)
        val ending = stoppingCaptionSession(listening.copy(transcriptLines = listOf(line), paused = true))
        assertTrue(ending.running)
        assertEquals(CaptionPhase.STOPPING, ending.phase)
        val final = applyTranscriptResult(ending, "tail", true, 2)
        val translated = applyTranscriptTranslation(final, 2, "마지막", true)
        assertEquals(CaptionPhase.STOPPING, translated.copy(translating = true, recognizing = true).phase)
        assertEquals(listOf("안녕", "마지막"), translated.transcriptLines.map { it.translatedText })
        val completed = completedCaptionSession(null, null, true)
        assertFalse(completed.stopping)
        assertFalse(completed.running)
    }

    @Test fun earlyStopKeepsStartGateClosedUntilCleanupCompletes() {
        val ending = stoppingCaptionSession(CaptionRuntimeState())
        assertTrue(ending.running)
        assertTrue(ending.stopping)
        assertEquals(CaptionPhase.STOPPING, ending.phase)
        assertFalse(completedCaptionSession(null, null, true).running)
    }

    @Test fun stoppingRetainsErrorAndHidesObsoleteCaptureHintAndLanguageChoice() {
        val ending = stoppingCaptionSession(listening.copy(captureHint = "play audio", suggestedLanguageCode = "ja"), "capture failed")
        assertEquals(CaptionPhase.STOPPING, ending.phase)
        assertEquals("capture failed", ending.actionableNotice)
        assertNull(ending.captureHint)
        assertNull(ending.suggestedLanguageCode)
        assertEquals("capture failed", withRuntimeError(ending, null).lastError)
    }

    @Test fun actualWorkWinsListeningButPauseAndErrorRemainVisible() {
        assertEquals(CaptionPhase.WAITING, listening.phase)
        assertEquals(CaptionPhase.PROCESSING, listening.copy(translating = true).phase)
        assertEquals(CaptionPhase.PROCESSING, listening.copy(recognizing = true).phase)
        assertEquals(CaptionPhase.PAUSED, listening.copy(translating = true, paused = true).phase)
        assertEquals(CaptionPhase.ERROR, listening.copy(translating = true, status = RecognitionStatus.ERROR).phase)
        assertEquals(CaptionPhase.PREPARING, listening.copy(status = RecognitionStatus.IDLE).phase)
    }

    @Test fun failedOrUntranslatedLinesCannotKeepProcessingStatusForever() {
        val pending = applyTranscriptResult(listening, "untranslated", true, 1)
        assertEquals(CaptionPhase.WAITING, pending.phase)
        val failed = pending.copy(translating = false, lastError = "translation failed")
        assertEquals(CaptionPhase.ERROR, failed.phase)
        assertEquals("translation failed", failed.actionableNotice)
    }

    @Test fun collapsedNoticeAppearsOnlyForAnActionableMessage() {
        val settings = CaptionSettings(overlayControlsExpanded = false)
        assertFalse(showsOverlayNotice(buildOverlayUiState(listening, settings)))
        val hint = listening.copy(captureHint = "Unmute and play a supported video app")
        val ui = buildOverlayUiState(hint, settings)
        assertFalse(ui.controlsExpanded)
        assertTrue(showsOverlayNotice(ui))
        assertEquals(hint.captureHint, ui.statusDetail)
        assertFalse(showsOverlayNotice(ui.copy(minimized = true)))
        assertFalse(showsOverlayNotice(buildOverlayUiState(hint.copy(paused = true), settings)))
        val backlog = buildOverlayUiState(listening.copy(skippedSpeechSegments = 2), settings)
        assertFalse(showsOverlayNotice(backlog))
        assertTrue(backlog.information!!.contains("음성 구간 2개"))
        assertEquals("capture failed", buildOverlayUiState(hint.copy(lastError = "capture failed"), settings).statusDetail)
    }

    @Test fun successfulCompletionAndBacklogAreInformationRatherThanErrors() {
        val complete = completedCaptionSession(null, null, true, "language changed", 2, 1)
        assertNull(complete.lastError)
        assertNull(complete.actionableNotice)
        assertEquals(CaptionPhase.IDLE, complete.phase)
        assertTrue(complete.information!!.contains("language changed"))
        assertTrue(complete.information!!.contains("자막 2개"))
        assertEquals(CaptionPhase.ERROR, completedCaptionSession(null, null, false).phase)
    }
}
