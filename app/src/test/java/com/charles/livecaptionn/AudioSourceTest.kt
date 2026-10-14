package com.charles.livecaptionn

import com.charles.livecaptionn.settings.AudioSource
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSourceTest {
    @Test fun fromName_systemWorks() {
        assertEquals(AudioSource.SYSTEM, AudioSource.fromName("SYSTEM"))
    }
    @Test fun fromName_micWorks() {
        assertEquals(AudioSource.MIC, AudioSource.fromName("MIC"))
    }
    @Test fun fromName_unknownDefaultsToVideo() {
        assertEquals(AudioSource.SYSTEM, AudioSource.fromName(""))
        assertEquals(AudioSource.SYSTEM, AudioSource.fromName("remote"))
    }
}
