package com.charles.livecaptionn

import com.charles.livecaptionn.speech.VoskModelInfo
import com.charles.livecaptionn.ui.MainUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainUiStateFilterTest {
    @Test fun modelIsAvailableOnlyIfBundled() {
        val s = MainUiState(voskModels = listOf(
            VoskModelInfo("en", "영어", "model-en", 40, installed = true),
            VoskModelInfo("ja", "일본어", "model-ja", 48, installed = false)
        ))
        assertTrue(s.modelReady)
        assertTrue(s.sourceModels.any { it.languageCode == "en" })
        assertFalse(s.sourceModels.any { it.languageCode == "ja" })
    }

    @Test fun noModelDisablesStart() {
        assertFalse(MainUiState().canStart)
    }
}
