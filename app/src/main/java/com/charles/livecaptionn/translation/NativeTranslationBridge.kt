package com.charles.livecaptionn.translation

internal interface NativeTranslationBackend {
    fun create(path: String, threads: Int, contextTokens: Int): Long
    /** @throws java.util.concurrent.TimeoutException when the native processing deadline expires. */
    fun translate(handle: Long, requestId: Long, prompt: ByteArray, maxTokens: Int, timeoutMs: Int): ByteArray
    fun cancel(handle: Long, requestId: Long)
    fun destroy(handle: Long)
}

internal object NativeTranslationBridge : NativeTranslationBackend {
    init { System.loadLibrary("livecaption_translation") }
    external override fun create(path: String, threads: Int, contextTokens: Int): Long
    external override fun translate(handle: Long, requestId: Long, prompt: ByteArray, maxTokens: Int, timeoutMs: Int): ByteArray
    external override fun cancel(handle: Long, requestId: Long)
    external override fun destroy(handle: Long)
}

internal object LazyNativeTranslationBackend : NativeTranslationBackend {
    override fun create(path: String, threads: Int, contextTokens: Int) =
        NativeTranslationBridge.create(path, threads, contextTokens)
    override fun translate(handle: Long, requestId: Long, prompt: ByteArray, maxTokens: Int, timeoutMs: Int) =
        NativeTranslationBridge.translate(handle, requestId, prompt, maxTokens, timeoutMs)
    override fun cancel(handle: Long, requestId: Long) = NativeTranslationBridge.cancel(handle, requestId)
    override fun destroy(handle: Long) = NativeTranslationBridge.destroy(handle)
}
