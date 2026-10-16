package com.charles.livecaptionn.ui

import org.junit.Assert.*
import org.junit.Test

class SplitScreenLaunchPolicyTest {
    @Test fun fullScreenAndroid12NeedsManualEntry() {
        assertFalse(canRequestAdjacentLaunch(31, false))
    }

    @Test fun android12LCanRequestAdjacentEntry() {
        assertTrue(canRequestAdjacentLaunch(32, false))
    }

    @Test fun existingMultiWindowCanOpenNeighborOnOlderSupportedAndroid() {
        assertTrue(canRequestAdjacentLaunch(29, true))
    }
}
