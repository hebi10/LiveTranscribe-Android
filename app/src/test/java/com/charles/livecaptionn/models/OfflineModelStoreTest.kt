package com.charles.livecaptionn.models

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineModelStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "verified offline model fixture".toByteArray()
    private val spec get() = model("speech/model.bin", bytes)

    @Test fun existingFilesRequireHashValidationBeforeReadinessWithoutAnyNetwork() = runBlocking {
        val transport = FixtureTransport { _, _ -> error("Refresh must not use the network") }
        val store = store(transport)
        write(spec.relativePath, bytes)
        assertFalse(store.isReady())
        assertTrue(store.refresh())
        assertTrue(store.isReady())
        assertEquals(file(spec.relativePath), store.requireReadyFile(spec.id))
        assertEquals(0, transport.calls.get())
    }

    @Test fun explicitPrepareVerifiesAndAtomicallyPromotesEveryRequiredFile() = runBlocking {
        val second = model("translation/model.gguf", "translation".toByteArray())
        val transport = FixtureTransport { url, _ -> response(if (url == spec.url) bytes else "translation".toByteArray()) }
        val store = store(transport, listOf(spec, second))
        assertFalse(store.isReady())
        assertEquals(0, transport.calls.get())
        store.prepare()
        assertTrue(store.isReady())
        assertEquals(OfflineModelStage.READY, store.state.value.stage)
        assertEquals(spec.sizeBytes + second.sizeBytes, store.state.value.downloadedBytes)
        assertFalse(file(spec.relativePath + ".part").exists())
        assertArrayEquals(bytes, file(spec.relativePath).readBytes())
        assertEquals(2, transport.calls.get())
    }

    @Test fun sameLengthCorruptionAndMissingSiblingNeverBecomeReady() = runBlocking {
        val second = model("translation/model.gguf", bytes)
        val store = store(FixtureTransport { _, _ -> error("No network") }, listOf(spec, second))
        write(spec.relativePath, bytes)
        assertFalse(store.refresh())
        write(second.relativePath, ByteArray(bytes.size))
        assertFalse(store.refresh())
        write(second.relativePath, bytes)
        assertTrue(store.refresh())
        write(spec.relativePath, ByteArray(bytes.size))
        assertFalse(store.refresh())
        assertFalse(store.isReady())
    }

    @Test fun partialDownloadResumesOnlyFromMatchingContentRange() = runBlocking {
        write(spec.relativePath + ".part", bytes.copyOf(7))
        val transport = FixtureTransport { _, offset ->
            assertEquals(7L, offset)
            response(bytes.drop(7).toByteArray(), 206, "bytes 7-${bytes.lastIndex}/${bytes.size}")
        }
        val store = store(transport)
        store.prepare()
        assertTrue(store.isReady())
        assertArrayEquals(bytes, file(spec.relativePath).readBytes())
    }

    @Test fun serverIgnoringRangeRestartsFileWithoutAppending() = runBlocking {
        write(spec.relativePath + ".part", bytes.copyOf(7))
        val store = store(FixtureTransport { _, _ -> response(bytes) })
        store.prepare()
        assertTrue(store.isReady())
        assertArrayEquals(bytes, file(spec.relativePath).readBytes())
    }

    @Test fun incorrectOrAbsentContentRangeCannotAppendOrPublish() = runBlocking {
        listOf(null, "bytes 0-${bytes.lastIndex}/${bytes.size}", "bytes 7-${bytes.lastIndex}/${bytes.size + 1}", "bytes 7-999/${bytes.size}").forEach { header ->
            write(spec.relativePath + ".part", bytes.copyOf(7))
            val store = store(FixtureTransport { _, _ -> response(bytes.drop(7).toByteArray(), 206, header) })
            store.prepare()
            assertEquals(OfflineModelStage.ERROR, store.state.value.stage)
            assertFalse(file(spec.relativePath).exists())
            assertArrayEquals(bytes.copyOf(7), file(spec.relativePath + ".part").readBytes())
        }
    }

    @Test fun interruptedReadPreservesBytesAndNextExplicitPrepareResumes() = runBlocking {
        var attempt = 0
        val transport = FixtureTransport { _, offset ->
            if (attempt++ == 0) response(object : InputStream() {
                var index = 0
                override fun read(): Int = if (index == 7) throw IOException("interrupted") else bytes[index++].toInt() and 255
                override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                    if (index == 7) throw IOException("interrupted")
                    val count = minOf(7 - index, len)
                    bytes.copyInto(buffer, off, index, index + count)
                    index += count
                    return count
                }
            }, length = spec.sizeBytes)
            else response(bytes.drop(offset.toInt()).toByteArray(), 206, "bytes $offset-${bytes.lastIndex}/${bytes.size}")
        }
        val store = store(transport)
        store.prepare()
        assertFalse(store.isReady())
        assertEquals(7L, file(spec.relativePath + ".part").length())
        assertFalse(file(spec.relativePath).exists())
        store.prepare()
        assertTrue(store.isReady())
        assertEquals(listOf(0L, 7L), transport.offsets)
    }

    @Test fun sha256MismatchDeletesPartialAndCannotBecomeReady() = runBlocking {
        val store = store(FixtureTransport { _, _ -> response(ByteArray(bytes.size)) })
        store.prepare()
        assertEquals(OfflineModelStage.ERROR, store.state.value.stage)
        assertFalse(store.isReady())
        assertFalse(file(spec.relativePath + ".part").exists())
        assertFalse(file(spec.relativePath).exists())
    }

    @Test fun oversizedResponseIsBoundedAndDeleted() = runBlocking {
        val store = store(FixtureTransport { _, _ -> response(ByteArray(bytes.size + 1), length = null) })
        store.prepare()
        assertEquals(OfflineModelStage.ERROR, store.state.value.stage)
        assertFalse(file(spec.relativePath + ".part").exists())
        assertFalse(file(spec.relativePath).exists())
    }

    @Test fun shortResponseStaysPartialAndNeverReady() = runBlocking {
        val store = store(FixtureTransport { _, _ -> response(bytes.copyOf(7), length = null) })
        store.prepare()
        assertEquals(OfflineModelStage.ERROR, store.state.value.stage)
        assertEquals(7L, file(spec.relativePath + ".part").length())
        assertFalse(file(spec.relativePath).exists())
    }

    @Test fun completeVerifiedPartialIsPromotedWithoutNetwork() = runBlocking {
        write(spec.relativePath + ".part", bytes)
        val transport = FixtureTransport { _, _ -> error("Already complete") }
        val store = store(transport)
        store.prepare()
        assertTrue(store.isReady())
        assertEquals(0, transport.calls.get())
    }

    @Test fun explicitCancellationClosesBlockedInputAndKeepsPartial() = runBlocking {
        val blocking = BlockingInput(bytes.copyOf(7))
        val store = store(FixtureTransport { _, _ -> response(blocking, length = spec.sizeBytes) })
        val job = launch(Dispatchers.IO) { store.prepare() }
        assertTrue("Download entered read", blocking.entered.await(3, TimeUnit.SECONDS))
        store.cancel()
        withTimeout(3_000) { job.join() }
        assertTrue(blocking.closed.await(1, TimeUnit.SECONDS))
        assertFalse(store.isReady())
        assertEquals(OfflineModelStage.NOT_DOWNLOADED, store.state.value.stage)
        assertEquals(7L, file(spec.relativePath + ".part").length())
    }

    @Test fun callerCancellationAlsoClosesBlockedInput() = runBlocking {
        val blocking = BlockingInput(bytes.copyOf(7))
        val store = store(FixtureTransport { _, _ -> response(blocking, length = spec.sizeBytes) })
        val job = launch(Dispatchers.IO) { store.prepare() }
        assertTrue(blocking.entered.await(3, TimeUnit.SECONDS))
        withTimeout(3_000) { job.cancelAndJoin() }
        assertTrue(blocking.closed.await(1, TimeUnit.SECONDS))
        assertFalse(store.isReady())
    }

    @Test fun concurrentPrepareIsDeduplicatedAndRefreshPreservesProgress() = runBlocking {
        val blocking = BlockingInput(bytes)
        val transport = FixtureTransport { _, _ -> response(blocking, length = spec.sizeBytes) }
        val store = store(transport)
        val first = launch(Dispatchers.IO) { store.prepare() }
        assertTrue(blocking.entered.await(3, TimeUnit.SECONDS))
        val second = launch(Dispatchers.IO) { store.prepare() }
        assertFalse(store.refresh())
        assertEquals(OfflineModelStage.DOWNLOADING, store.state.value.stage)
        assertEquals(spec.sizeBytes, store.state.value.downloadedBytes)
        blocking.release.countDown()
        withTimeout(3_000) { first.join(); second.join() }
        assertTrue(store.isReady())
        assertEquals(1, transport.calls.get())
    }

    @Test fun publicCancelReturnsPromptlyWhileNetworkCleanupIsBlocked() = runBlocking {
        val input = BlockingInput(bytes.copyOf(7))
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val cancelReturned = CountDownLatch(1)
        val transport = object : ModelDownloadTransport {
            override fun newCall(url: String, offset: Long) = object : ModelDownloadCall {
                override fun execute() = response(input, length = spec.sizeBytes)
                override fun cancel() {
                    cleanupEntered.countDown()
                    check(releaseCleanup.await(5, TimeUnit.SECONDS))
                    input.close()
                }
            }
        }
        val store = store(transport)
        val download = launch(Dispatchers.IO) { store.prepare() }
        assertTrue(input.entered.await(3, TimeUnit.SECONDS))
        val uiCaller = Thread { store.cancel(); cancelReturned.countDown() }
        try {
            uiCaller.start()
            assertTrue("Cancel must not wait for network shutdown on its caller", cancelReturned.await(500, TimeUnit.MILLISECONDS))
            assertTrue("I/O cancellation handler must close the connection", cleanupEntered.await(3, TimeUnit.SECONDS))
            assertFalse(store.isReady())
        } finally {
            releaseCleanup.countDown()
            uiCaller.join(3_000)
            withTimeout(3_000) { download.join() }
        }
        assertEquals(OfflineModelStage.NOT_DOWNLOADED, store.state.value.stage)
    }

    @Test fun cancellationDoesNotPoisonQueuedOrLaterExplicitPreparation() = runBlocking {
        val blocking = BlockingInput(bytes.copyOf(7))
        var attempt = 0
        val transport = FixtureTransport { _, offset ->
            if (attempt++ == 0) response(blocking, length = spec.sizeBytes)
            else response(bytes.drop(offset.toInt()).toByteArray(), 206, "bytes $offset-${bytes.lastIndex}/${bytes.size}")
        }
        val store = store(transport)
        store.cancel()
        val first = launch(Dispatchers.IO) { store.prepare() }
        assertTrue(blocking.entered.await(3, TimeUnit.SECONDS))
        val next = launch(Dispatchers.IO) { store.prepare() }
        store.cancel()
        withTimeout(3_000) { first.join(); next.join() }
        assertTrue(store.isReady())
        assertEquals(2, transport.calls.get())
    }

    @Test fun insufficientSpaceFailsBeforeNetworkWithActionableMessage() = runBlocking {
        val transport = FixtureTransport { _, _ -> error("Must check available storage first") }
        val store = OfflineModelStore(temporary.root, listOf(spec), transport, availableBytes = { 0L })
        store.prepare()
        assertEquals(OfflineModelStage.ERROR, store.state.value.stage)
        assertTrue(store.state.value.message!!.contains("공간"))
        assertEquals(0, transport.calls.get())
    }

    @Test fun deletedValidatedFileInvalidatesReadinessAndPathAccess() = runBlocking {
        val store = store(FixtureTransport { _, _ -> response(bytes) })
        store.prepare()
        file(spec.relativePath).delete()
        assertFalse(store.isReady())
        assertThrows(IllegalStateException::class.java) { store.requireReadyFile(spec.id) }
        Unit
    }

    @Test fun unsafePathsAndNonHttpsUrlsAreRejectedAtConstruction() {
        listOf("../escape", "/absolute", "speech/../../escape", "speech\\escape", "speech/./model").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { store(FixtureTransport { _, _ -> error("Unused") }, listOf(spec.copy(relativePath = path))) }
        }
        assertThrows(IllegalArgumentException::class.java) { store(FixtureTransport { _, _ -> error("Unused") }, listOf(spec.copy(url = "http://huggingface.co/file"))) }
    }

    @Test fun symlinkedPartialCannotWriteOutsideModelDirectory() = runBlocking {
        val outside = temporary.newFile("outside.bin")
        outside.writeText("untouched")
        val root = temporary.newFolder("inside")
        val partial = File(root, spec.relativePath + ".part")
        partial.parentFile!!.mkdirs()
        java.nio.file.Files.createSymbolicLink(partial.toPath(), outside.toPath())
        val store = OfflineModelStore(root, listOf(spec), FixtureTransport { _, _ -> response(bytes) }, availableBytes = { Long.MAX_VALUE })
        store.prepare()
        assertFalse(store.isReady())
        assertEquals("untouched", outside.readText())
    }

    private fun response(data: ByteArray, code: Int = 200, range: String? = null, length: Long? = data.size.toLong()) =
        response(ByteArrayInputStream(data), code, range, length)
    private fun response(input: InputStream, code: Int = 200, range: String? = null, length: Long? = null): ModelDownloadResponse =
        object : ModelDownloadResponse {
            override val statusCode = code
            override val contentRange = range
            override val contentLength = length
            override val body = input
            override fun close() { input.close() }
        }
    private class BlockingInput(private val prefix: ByteArray) : InputStream() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        private var delivered = false
        override fun read(): Int = error("Buffered reads required")
        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (!delivered) { prefix.copyInto(buffer, off); delivered = true; return prefix.size }
            entered.countDown()
            if (!release.await(5, TimeUnit.SECONDS)) throw IOException("Test download timeout")
            if (closed.count == 0L) throw IOException("Closed")
            return -1
        }
        override fun close() { closed.countDown(); release.countDown() }
    }

    private fun model(path: String, data: ByteArray) = OfflineModelFile(
        path, path, "https://huggingface.co/test/resolve/0123456789abcdef0123456789abcdef01234567/$path",
        data.size.toLong(), MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    )
    private fun store(transport: ModelDownloadTransport, files: List<OfflineModelFile> = listOf(spec)) =
        OfflineModelStore(temporary.root, files, transport, availableBytes = { Long.MAX_VALUE })
    private fun file(path: String) = File(temporary.root, path)
    private fun write(path: String, data: ByteArray) = file(path).apply { parentFile!!.mkdirs(); writeBytes(data) }
    private class FixtureTransport(val handler: (String, Long) -> ModelDownloadResponse) : ModelDownloadTransport {
        val calls = AtomicInteger()
        val offsets = mutableListOf<Long>()
        override fun newCall(url: String, offset: Long): ModelDownloadCall = object : ModelDownloadCall {
            private var response: ModelDownloadResponse? = null
            override fun execute(): ModelDownloadResponse {
                calls.incrementAndGet()
                synchronized(offsets) { offsets.add(offset) }
                return handler(url, offset).also { response = it }
            }
            override fun cancel() { response?.close() }
        }
    }
}
