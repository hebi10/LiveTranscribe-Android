package com.charles.livecaptionn

import com.charles.livecaptionn.speech.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class LocalQwenSttClientTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun resultsNearGenerationCapAreRejectedIncludingStrippedScaffoldAllowance() {
        assertEquals("complete", validateQwenResult("complete", 111))
        for (count in listOf(112, 127, 128, 129)) {
            assertThrows(RecognitionOutputLimitException::class.java) {
                validateQwenResult("potentially cut", count)
            }
        }
    }

    @Test fun incompleteOrEmptyDownloadedModelIsRejectedBeforeNativeInitialization() {
        val directory = temporary.newFolder("qwen")
        val paths = listOf("conv_frontend.onnx", "encoder.int8.onnx", "decoder.int8.onnx",
            "tokenizer/merges.txt", "tokenizer/tokenizer_config.json", "tokenizer/vocab.json")
        for (path in paths) {
            try { requireQwenModelFiles(directory); fail("Partial download must not be opened") }
            catch (_: IllegalStateException) {}
            File(directory, path).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
        }
        requireQwenModelFiles(directory)
        File(directory, "tokenizer/vocab.json").writeBytes(byteArrayOf())
        try { requireQwenModelFiles(directory); fail("Empty model component must not be opened") }
        catch (_: IllegalStateException) {}
    }

    @Test fun modelInitializationFailureIsReturnedToTheCaptureOwner() = runTest {
        val failure = IllegalStateException("model initialization failed")
        val client = LocalQwenSttClient({ throw failure }, StandardTestDispatcher(testScheduler))
        try { client.openSession(); fail("Initialization failure must reach capture") }
        catch (caught: IllegalStateException) {
            assertEquals(failure.message, caught.message)
            assertTrue(generateSequence<Throwable>(caught) { it.cause }.any { it === failure })
        }
    }

    @Test fun pcmConversionUsesSignedLittleEndianAndExactNormalization() {
        val backend = Backend()
        val session = OfflineSttSession(backend, Gate())
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
        val session = OfflineSttSession(backend, Gate())
        try { session.transcribe(ByteArray(32000)); fail("Decode failure must reach pipeline") } catch (_: IllegalStateException) {}
        assertEquals(1, backend.streamClosed)
        session.close()
        assertEquals(1, backend.closed)
    }

    @Test fun closedSessionNeverTouchesNativeAgain() {
        val backend = Backend()
        val gate = Gate()
        val session = OfflineSttSession(backend, gate)
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
        val client = LocalQwenSttClient({
            OfflineSttSession(backend, gate).also { startup.cancel() }
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
        val gate = VadSpeechGate(object : SpeechProbabilityModel {
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
        val session = OfflineSttSession(backend, Gate())
        try { session.transcribe(ByteArray(32000)); fail("Native input failure must propagate") } catch (_: IllegalStateException) {}
        assertEquals(1, backend.streamClosed)
        session.close()
    }

    @Test fun vadRemaindersArePreservedAcrossEveryShortRead() {
        val collectedSamples = mutableListOf<Float>()
        val gate = VadSpeechGate(object : SpeechProbabilityModel {
            override fun probability(samples: FloatArray): Float { collectedSamples.addAll(samples.toList()); return .8f }
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
        assertEquals(2560, collectedSamples.size)
        assertEquals((0 until 2560).map { it / 32768f }, collectedSamples)
    }

    private class Gate : SpeechGate {
        var closed = 0
        var calls = 0
        override fun isSpeech(pcm: ByteArray, length: Int): Boolean { calls++; return true }
        override fun reset() {}
        override fun close() { closed++ }
    }
    private class Backend : OfflineRecognizerBackend {
        var samples = FloatArray(0)
        var sampleRate = 0
        var closed = 0
        var streams = 0
        var streamClosed = 0
        var failure = false
        var acceptFailure = false
        override fun createStream(): OfflineRecognizerStream {
            streams++
            return object : OfflineRecognizerStream {
                override fun acceptWaveform(samples: FloatArray, sampleRate: Int) { this@Backend.samples = samples; this@Backend.sampleRate = sampleRate; if (acceptFailure) error("accept failed") }
                override fun close() { streamClosed++ }
            }
        }
        override fun decode(stream: OfflineRecognizerStream) { if (failure) error("native failed") }
        override fun result(stream: OfflineRecognizerStream) = "日本語"
        override fun close() { closed++ }
    }
}
