package com.charles.livecaptionn.models

import com.charles.livecaptionn.translation.ModelSetupStage
import com.charles.livecaptionn.translation.TranslationModelSetup
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranslationModelSetupTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "same models for both languages".toByteArray()
    private val spec = OfflineModelFile("model", "models/file.bin", "https://huggingface.co/test/resolve/0123456789abcdef0123456789abcdef01234567/model.bin", bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

    @Test fun preparingOneLanguageMakesBothSupportedLanguagesReady() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val setup = TranslationModelSetup(store(ByteArrayInputStream(bytes)), scope)
            setup.prepare("en")
            assertTrue(setup.isReady("en"))
            assertTrue(setup.isReady("ja"))
            assertFalse(setup.isReady("xx"))
            assertEquals(ModelSetupStage.READY, setup.state.value.stage)
            assertEquals(spec.sizeBytes, setup.state.value.downloadedBytes)
            setup.refresh("ja")
            assertEquals("ja", setup.state.value.sourceCode)
            assertEquals(ModelSetupStage.READY, setup.state.value.stage)
        } finally { scope.cancel() }
    }

    @Test fun automaticPreparationSharesReadinessWithBothManualModes() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val setup = TranslationModelSetup(store(ByteArrayInputStream(bytes)), scope)
            assertEquals("auto", setup.state.value.sourceCode)
            setup.prepare("auto")
            assertTrue(setup.isReady("auto"))
            assertTrue(setup.isReady("en"))
            assertTrue(setup.isReady("ja"))
            setup.refresh("auto")
            assertEquals(ModelSetupStage.READY, setup.state.value.stage)
            assertEquals("auto", setup.state.value.sourceCode)
        } finally { scope.cancel() }
    }

    @Test fun changingLanguageDuringPreparePreservesActiveProgressAndReadiness() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stream = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (available() == 0) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                return super.read(buffer, offset, length)
            }
            override fun close() { release.countDown(); super.close() }
        }
        try {
            val setup = TranslationModelSetup(store(stream), scope)
            val job = launch(Dispatchers.IO) { setup.prepare("en") }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            setup.refresh("ja")
            assertEquals("ja", setup.state.value.sourceCode)
            assertEquals(ModelSetupStage.DOWNLOADING, setup.state.value.stage)
            assertEquals(spec.sizeBytes, setup.state.value.downloadedBytes)
            release.countDown()
            withTimeout(3_000) { job.join() }
            assertEquals("ja", setup.state.value.sourceCode)
            assertEquals(ModelSetupStage.READY, setup.state.value.stage)
        } finally { release.countDown(); scope.cancel() }
    }

    private fun store(stream: InputStream): OfflineModelStore {
        val transport = object : ModelDownloadTransport {
            override fun newCall(url: String, offset: Long) = object : ModelDownloadCall {
                override fun execute() = object : ModelDownloadResponse {
                    override val statusCode = 200
                    override val contentRange: String? = null
                    override val contentLength = spec.sizeBytes
                    override val body = stream
                    override fun close() { stream.close() }
                }
                override fun cancel() { stream.close() }
            }
        }
        return OfflineModelStore(temporary.root, listOf(spec), transport, availableBytes = { Long.MAX_VALUE })
    }
}
