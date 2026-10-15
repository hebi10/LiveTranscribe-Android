package com.charles.livecaptionn.translation

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class LocalTranslationRepositoryTest {
    @Test fun blankAndIdentityDoNotLoadModel() = runBlocking {
        val backend = FakeBackend()
        val repository = repository(backend)
        assertEquals("", repository.translate("  ", "ja", "ko", false))
        assertEquals("안녕", repository.translate("안녕", "ko", "ko", false))
        assertEquals(0, backend.loads)
    }

    @Test fun translatesWithKoreanOnlyPromptAndBoundedNativeRequest() = runBlocking {
        val backend = FakeBackend()
        val repository = repository(backend)
        assertEquals("안녕하세요.", repository.translate("こんにちは。", "ja", "ko", false))
        assertTrue(backend.prompt.startsWith("Translate the following text into Korean."))
        assertTrue(backend.prompt.endsWith("\n\nこんにちは。"))
        assertEquals(192, backend.maxTokens)
        assertEquals(15_000, backend.timeoutMs)
        assertEquals(2, backend.threads)
        assertEquals(1024, backend.contextTokens)
        repository.close()
    }

    @Test fun nativeFailureNeverReturnsOriginalOrPartialText() = runBlocking {
        val backend = FakeBackend().apply { failure = IllegalStateException("Translation output limit exceeded") }
        val repository = repository(backend)
        try {
            repository.translate("こんにちは。", "ja", "ko", false)
            fail("Expected failure")
        } catch (failure: IllegalStateException) {
            assertTrue(failure.message!!.contains("기기 내 번역"))
        }
        repository.close()
    }

    @Test fun unsupportedPairFailsBeforeModelLoad() = runBlocking {
        val backend = FakeBackend()
        try {
            repository(backend).translate("hello", "en", "fr", false)
            fail("Expected unsupported language")
        } catch (_: IllegalArgumentException) {}
        assertEquals(0, backend.loads)
    }

    @Test fun cancellationSignalsNativeAndDoesNotDestroyDuringDecode() = runBlocking {
        val backend = FakeBackend().apply { block = true }
        val repository = repository(backend)
        val operation = async { repository.translate("こんにちは。", "ja", "ko", false) }
        yield()
        assertTrue(backend.entered.await(5, TimeUnit.SECONDS))
        operation.cancelAndJoin()
        assertTrue(backend.cancelled.get() > 0)
        repository.close()
        assertFalse(backend.destroyedDuringDecode)
        backend.release.countDown()
        assertTrue(backend.destroyed.await(5, TimeUnit.SECONDS))
        assertFalse(backend.destroyedDuringDecode)
    }

    @Test fun closingUnloadsAndNextSessionCanLoadAgain() = runBlocking {
        val backend = FakeBackend()
        val repository = repository(backend)
        repository.prewarm("ja", "ko")
        repository.close()
        assertEquals("안녕하세요.", repository.translate("hello", "en", "ko", false))
        assertEquals(2, backend.loads)
        assertEquals(1, backend.closes)
        repository.close()
    }

    @Test fun automaticAndUnknownSourcesUseTranslationWithoutAssumingKorean() = runBlocking {
        val backend = FakeBackend()
        val repository = repository(backend)
        assertEquals("안녕하세요.", repository.translate("bonjour", "auto", "ko", true))
        assertEquals("안녕하세요.", repository.translate("한국어인지 미확인", "und", "ko", true))
        assertTrue(backend.prompt.endsWith("한국어인지 미확인"))
        assertEquals(1, backend.loads)
        repository.close()
    }

    @Test fun detectedSupportedLanguagesAndAliasesTranslateIntoKorean() = runBlocking {
        val backend = FakeBackend()
        val repository = repository(backend)
        listOf("fr", "yue", "Filipino", "tl", "Persian", "de-DE").forEach {
            assertEquals("안녕하세요.", repository.translate("source text", it, "ko", true))
        }
        repository.close()
    }

    @Test fun automaticKoreanDetectionStillTranslatesMixedOrMislabelledForeignText() = runBlocking {
        val backend = FakeBackend()
        val repository = repository(backend)
        listOf("안녕하세요. Please send the report.", "Please send the report.").forEach { text ->
            assertEquals("안녕하세요.", repository.translate(text, "Korean", "ko", true))
            assertTrue(backend.prompt.endsWith(text))
        }
        assertEquals(1, backend.loads)
        repository.close()
    }

    @Test fun automaticPrewarmLoadsOneSharedTranslationModel() = runBlocking {
        val backend = FakeBackend()
        val repository = repository(backend)
        repository.prewarm("auto", "ko")
        repository.prewarm("ja", "ko")
        assertEquals(1, backend.loads)
        repository.close()
    }

    @Test fun unsupportedSourcesNeverBypassValidationThroughAutoDetect() = runBlocking {
        val backend = FakeBackend()
        val repository = repository(backend)
        listOf("sv", "made-up", "", "fr-FakeScript").forEach { source ->
            try { repository.translate("text", source, "ko", true); fail("Unsupported source accepted: $source") }
            catch (_: IllegalArgumentException) {}
        }
        try { repository.translate("text", "auto", "ko", false); fail("Automatic mode requires autoDetect") }
        catch (_: IllegalArgumentException) {}
        assertEquals(0, backend.loads)
    }

    private fun repository(backend: FakeBackend) = LocalTranslationRepository(
        modelFile = { File("/verified/model.gguf") },
        backend = backend,
        executor = Executors.newSingleThreadExecutor { task -> Thread(task).apply { isDaemon = true } }
    )

    private class FakeBackend : NativeTranslationBackend {
        var loads = 0
        var closes = 0
        var prompt = ""
        var maxTokens = 0
        var timeoutMs = 0
        var threads = 0
        var contextTokens = 0
        var failure: Exception? = null
        var block = false
        @Volatile var decoding = false
        @Volatile var destroyedDuringDecode = false
        val cancelled = AtomicLong()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        override fun create(path: String, threads: Int, contextTokens: Int): Long {
            this.threads = threads
            this.contextTokens = contextTokens
            return (++loads).toLong()
        }
        override fun translate(handle: Long, requestId: Long, prompt: ByteArray, maxTokens: Int, timeoutMs: Int): ByteArray {
            this.prompt = prompt.toString(Charsets.UTF_8)
            this.maxTokens = maxTokens
            this.timeoutMs = timeoutMs
            decoding = true
            entered.countDown()
            try {
                if (block) check(release.await(5, TimeUnit.SECONDS))
                failure?.let { throw it }
                return "안녕하세요.".toByteArray()
            } finally { decoding = false }
        }
        override fun cancel(handle: Long, requestId: Long) { cancelled.accumulateAndGet(requestId, ::maxOf) }
        override fun destroy(handle: Long) {
            closes++
            destroyedDuringDecode = decoding
            destroyed.countDown()
        }
    }
}
