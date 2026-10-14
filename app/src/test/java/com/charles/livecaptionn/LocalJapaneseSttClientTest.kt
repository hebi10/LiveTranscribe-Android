package com.charles.livecaptionn

import com.charles.livecaptionn.speech.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalJapaneseSttClientTest {
    @Test fun pcmConversionUsesSignedLittleEndianAndExactNormalization() {
        val backend = Backend()
        val session = JapaneseSttSession(backend, Gate())
        assertEquals("日本語", session.transcribe(byteArrayOf(0, -128, -1, 127, 0, 0, 0, 64, -1, -1)))
        assertArrayEquals(floatArrayOf(-1f, 32767 / 32768f, 0f, .5f, -1 / 32768f), backend.samples.copyOf(5), 0f)
        assertEquals(16000, backend.sampleRate)
        assertEquals(8005, backend.samples.size)
        assertTrue(backend.samples.drop(5).all { it == 0f })
        assertEquals(1, backend.streamClosed)
        session.close(); session.close()
        assertEquals(1, backend.closed)
    }

    @Test fun failedDecodeReleasesItsStreamBeforeCallerReceivesFailure() {
        val backend = Backend().apply { failure = true }
        val session = JapaneseSttSession(backend, Gate())
        try { session.transcribe(ByteArray(32000)); fail("Decode failure must reach pipeline") } catch (_: IllegalStateException) {}
        assertEquals(1, backend.streamClosed)
        session.close()
        assertEquals(1, backend.closed)
    }

    @Test fun closedSessionNeverTouchesNativeAgain() {
        val backend = Backend()
        val gate = Gate()
        val session = JapaneseSttSession(backend, gate)
        session.close(); session.close()
        assertEquals("", session.transcribe(ByteArray(16000)))
        assertFalse(session.isSpeech(ByteArray(3200), 3200))
        assertEquals(0, backend.streams)
        assertEquals(0, gate.calls)
        assertEquals(1, gate.closed)
    }

    @Test fun cancelledStartupReleasesSessionWhoseReturnWasDiscarded() = runTest {
        val backend = Backend()
        val gate = Gate()
        lateinit var startup: Job
        val client = LocalJapaneseSttClient({
            JapaneseSttSession(backend, gate).also { startup.cancel() }
        }, StandardTestDispatcher(testScheduler))
        startup = launch(start = CoroutineStart.LAZY) { client.openSession() }
        startup.start()
        runCurrent()
        assertTrue(startup.isCancelled)
        assertEquals(1, backend.closed)
        assertEquals(1, gate.closed)
    }

    @Test fun vadFramesRemain512SamplesAcrossShortReadsAndResetClearsTail() {
        val frames = mutableListOf<FloatArray>()
        var resets = 0
        val gate = JapaneseVadSpeechGate(object : JapaneseSpeechProbabilityModel {
            override fun probability(samples: FloatArray): Float { frames.add(samples.copyOf()); return .9f }
            override fun reset() { resets++ }
            override fun close() {}
        })
        val pcm = ByteArray(1600) { if (it % 2 == 0) 0 else 64 }
        assertFalse(gate.isSpeech(pcm, 400))
        assertTrue(gate.isSpeech(pcm, 1200))
        assertEquals(1, frames.size)
        assertEquals(512, frames.single().size)
        assertTrue(frames.single().all { it == .5f })
        gate.reset()
        assertEquals(1, resets)
        assertFalse(gate.isSpeech(pcm, 400))
        assertEquals(1, frames.size)
    }

    @Test fun acceptingWaveformFailureStillClosesTemporaryNativeStream() {
        val backend = Backend().apply { acceptFailure = true }
        val session = JapaneseSttSession(backend, Gate())
        try { session.transcribe(ByteArray(32000)); fail("Native input failure must propagate") } catch (_: IllegalStateException) {}
        assertEquals(1, backend.streamClosed)
        session.close()
    }

    @Test fun vadRemaindersArePreservedAcrossEveryShortRead() {
        val samples = mutableListOf<Float>()
        val gate = JapaneseVadSpeechGate(object : JapaneseSpeechProbabilityModel {
            override fun probability(frame: FloatArray): Float { samples.addAll(frame.toList()); return .8f }
            override fun reset() {}
            override fun close() {}
        })
        val pcm = ByteArray(5120).also { bytes ->
            repeat(2560) { index -> bytes[index * 2] = index.toByte(); bytes[index * 2 + 1] = (index shr 8).toByte() }
        }
        for (offset in pcm.indices step 320) {
            val chunk = pcm.copyOfRange(offset, minOf(pcm.size, offset + 320))
            gate.isSpeech(chunk, chunk.size)
        }
        assertEquals(2560, samples.size)
        assertEquals((0 until 2560).map { it / 32768f }, samples)
    }

    private class Gate : JapaneseSpeechGate {
        var closed = 0
        var calls = 0
        override fun isSpeech(pcm: ByteArray, length: Int): Boolean { calls++; return true }
        override fun reset() {}
        override fun close() { closed++ }
    }
    private class Backend : JapaneseRecognizerBackend {
        var samples = FloatArray(0)
        var sampleRate = 0
        var closed = 0
        var streams = 0
        var streamClosed = 0
        var failure = false
        var acceptFailure = false
        override fun createStream(): JapaneseRecognizerStream {
            streams++
            return object : JapaneseRecognizerStream {
                override fun acceptWaveform(samples: FloatArray, sampleRate: Int) { this@Backend.samples = samples; this@Backend.sampleRate = sampleRate; if (acceptFailure) error("accept failed") }
                override fun close() { streamClosed++ }
            }
        }
        override fun decode(stream: JapaneseRecognizerStream) { if (failure) error("native failed") }
        override fun result(stream: JapaneseRecognizerStream) = "日本語"
        override fun close() { closed++ }
    }
}
