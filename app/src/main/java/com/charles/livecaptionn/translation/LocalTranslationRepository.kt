package com.charles.livecaptionn.translation

import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class LocalTranslationRepository internal constructor(
    private val modelFile: () -> File,
    private val backend: NativeTranslationBackend,
    private val executor: Executor
) : TranslationRepository {
    constructor(modelFile: () -> File) : this(
        modelFile,
        LazyNativeTranslationBackend,
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "local-translation").apply { isDaemon = true }
        }
    )

    private val lifecycleLock = Any()
    private val requestIds = AtomicLong()
    @Volatile private var generation = 0L
    @Volatile private var handle = 0L

    override suspend fun translate(text: String, sourceCode: String, targetCode: String, autoDetect: Boolean): String {
        val clean = text.trim()
        if (clean.isEmpty()) return clean
        requirePair(sourceCode, targetCode)
        if (sourceCode == targetCode) return clean
        require(clean.length <= 4_000) { "번역할 문장이 너무 깁니다." }
        val prompt = "Translate the following text into Korean. Note that you should only output " +
            "the translated result without any additional explanation:\n\n$clean"
        return execute { session, request ->
            backend.translate(session, request, prompt.toByteArray(Charsets.UTF_8), 192, 15_000)
                .toString(Charsets.UTF_8).trim()
                .also { check(it.isNotBlank()) { "Translation produced no text" } }
        }
    }

    override suspend fun prewarm(sourceCode: String, targetCode: String) {
        requirePair(sourceCode, targetCode)
        if (sourceCode != targetCode) execute { _, _ -> Unit }
    }

    private fun requirePair(source: String, target: String) {
        require(target == "ko" && source in setOf("ja", "en", "ko")) {
            "현재 기기 내 번역은 일본어·영어에서 한국어로 지원합니다."
        }
    }

    private suspend fun <T> execute(action: (Long, Long) -> T): T = suspendCancellableCoroutine { continuation ->
        synchronized(lifecycleLock) {
            val requestedGeneration = generation
            val request = requestIds.incrementAndGet()
            continuation.invokeOnCancellation {
                val current = handle
                if (current != 0L) backend.cancel(current, request)
            }
            executor.execute {
                if (!continuation.isActive || requestedGeneration != generation) {
                    continuation.cancel(CancellationException("Translation session ended"))
                    return@execute
                }
                try {
                    if (handle == 0L) {
                        handle = backend.create(modelFile().absolutePath, threads = 2, contextTokens = 1024)
                        check(handle != 0L) { "Translation model could not be loaded" }
                    }
                    if (!continuation.isActive || requestedGeneration != generation) {
                        continuation.cancel(CancellationException("Translation session ended"))
                        return@execute
                    }
                    val result = action(handle, request)
                    if (continuation.isActive && requestedGeneration == generation) continuation.resume(result)
                    else continuation.cancel(CancellationException("Translation session ended"))
                } catch (failure: Exception) {
                    if (requestedGeneration != generation) continuation.cancel(CancellationException("Translation session ended"))
                    else if (continuation.isActive) continuation.resumeWithException(
                        if (failure is CancellationException) failure else IllegalStateException(
                            "기기 내 번역에 실패했습니다. 문장을 짧게 나누거나 번역 모델을 확인한 뒤 다시 시작해 주세요.", failure
                        )
                    )
                } catch (failure: LinkageError) {
                    if (continuation.isActive) continuation.resumeWithException(
                        IllegalStateException("이 기기에서 번역 엔진을 불러올 수 없습니다.", failure)
                    )
                }
            }
        }
    }

    override fun close() {
        synchronized(lifecycleLock) {
            generation++
            val current = handle
            if (current != 0L) backend.cancel(current, Long.MAX_VALUE)
            executor.execute {
                val loaded = handle
                handle = 0L
                if (loaded != 0L) backend.destroy(loaded)
            }
        }
    }
}
