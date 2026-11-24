package com.charles.livecaptionn

import com.charles.livecaptionn.overlay.BubbleCaptionPresentationState
import com.charles.livecaptionn.overlay.OverlayCaptionMessage
import com.charles.livecaptionn.overlay.OverlayUiState
import com.charles.livecaptionn.speech.RecognitionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePresentationTest {
    private fun caption(id: Long = 1L, text: String = "한국어 번역") =
        OverlayCaptionMessage(id, "source", text, true)

    private fun ui(vararg messages: OverlayCaptionMessage) =
        OverlayUiState(messages = messages.toList(), status = RecognitionStatus.LISTENING)

    @Test fun bubbleStatusShowsSkippedCaptionsAndActionableNotice() {
        val ui = OverlayUiState(phase = com.charles.livecaptionn.service.CaptionPhase.PROCESSING,
            translating = true, skippedSpeechSegments = 1, statusDetail = "음성 입력을 확인해 주세요.")
        val label = com.charles.livecaptionn.overlay.bubbleStatusLabel(ui)
        assertTrue(label.contains("한국어로 번역 중"))
        assertTrue(label.contains("일부 자막 생략"))
        assertTrue(label.contains("음성 입력을 확인해 주세요."))
    }

    @Test fun repeatedFlowEmissionsDoNotRestartTimer() {
        val state = BubbleCaptionPresentationState()
        val row = ui(caption())
        assertEquals(6000L, state.reduce(row, 5000, 1000).expiresAtMs)
        assertEquals(6000L, state.reduce(row, 5000, 4900).expiresAtMs)
        assertFalse(state.reduce(row, 5000, 6001).visible)
        assertFalse(state.reduce(row, 5000, 6200).visible)
    }

    @Test fun newTranslationAndCorrectionRefreshDisplayWithoutDuplicates() {
        val state = BubbleCaptionPresentationState()
        assertTrue(state.reduce(ui(caption()), 3000, 0).visible)
        assertEquals(3000L, state.reduce(ui(caption()), 3000, 1000).expiresAtMs)
        assertEquals(4500L, state.reduce(ui(caption(1L, "수정된 번역")), 3000, 1500).expiresAtMs)
        assertEquals(5800L, state.reduce(ui(caption(2L, "다음 문장")), 3000, 2800).expiresAtMs)
    }

    @Test fun translationInFlightHoldsLastCaptionUntilNewResult() {
        val state = BubbleCaptionPresentationState()
        val first = ui(caption())
        state.reduce(first, 3000, 0)
        val busy = first.copy(recognizing = true, translating = true)
        assertTrue(state.reduce(busy, 3000, 5000).visible)
        assertEquals(null, state.reduce(busy, 3000, 5001).expiresAtMs)
        assertFalse(state.reduce(first, 3000, 5500).visible)
    }

    @Test fun pausedAndErrorHideCaptionButDoNotReshowStaleText() {
        val state = BubbleCaptionPresentationState()
        val first = ui(caption())
        state.reduce(first, 5000, 0)
        assertFalse(state.reduce(first.copy(status = RecognitionStatus.PAUSED), 5000, 500).visible)
        assertFalse(state.reduce(first, 5000, 600).visible)
        assertTrue(state.reduce(ui(caption(2L)), 5000, 700).visible)
        assertFalse(state.reduce(first.copy(status = RecognitionStatus.ERROR), 5000, 800).visible)
    }

    @Test fun stableModeDoesNotShowUncommittedPreviews() {
        val state = BubbleCaptionPresentationState()
        val partial = OverlayCaptionMessage(1L, "source", "draft", false)
        assertFalse(state.reduce(ui(partial).copy(stableCaptions = true), 5000, 0).visible)
        assertTrue(state.reduce(ui(partial).copy(stableCaptions = false), 5000, 1000).visible)
    }
}
