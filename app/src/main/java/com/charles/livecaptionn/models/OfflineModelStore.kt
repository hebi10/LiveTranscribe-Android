package com.charles.livecaptionn.models

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** All languages use this same complete, revision-pinned set of local files. */
data class OfflineModelFile(
    val id: String,
    val relativePath: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String
)

enum class OfflineModelStage { CHECKING, NOT_DOWNLOADED, DOWNLOADING, READY, ERROR }

data class OfflineModelState(
    val stage: OfflineModelStage = OfflineModelStage.CHECKING,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val currentFile: String? = null,
    val message: String? = null
)

/**
 * The sole owner of model preparation. Construction/refresh never contact the network.
 * AppContainer must supply File(context.noBackupFilesDir, "ai-models").
 * Only complete SHA-256-verified files are renamed from .part into their runtime paths.
 */
class OfflineModelStore internal constructor(
    rootDirectory: File,
    files: List<OfflineModelFile>,
    private val transport: ModelDownloadTransport,
    private val availableBytes: () -> Long = { rootDirectory.usableSpace }
) {
    constructor(rootDirectory: File) : this(
        rootDirectory,
        OfflineModelCatalog.files,
        HttpsModelDownloadTransport(OfflineModelCatalog.files.map { it.url }.toSet())
    )

    val rootDirectory: File = rootDirectory.canonicalFile
    private val files = files.toList()
    val totalBytes: Long = this.files.sumOf { it.sizeBytes }
    private val mutex = Mutex()
    @Volatile private var activePreparation: Preparation? = null
    @Volatile private var verified: Map<String, FileStamp> = emptyMap()
    private val mutable = MutableStateFlow(OfflineModelState(totalBytes = totalBytes))
    val state: StateFlow<OfflineModelState> = mutable.asStateFlow()

    init {
        require(this.files.isNotEmpty()) { "Model catalog is empty" }
        require(this.files.map { it.id }.toSet().size == this.files.size) { "Duplicate model ID" }
        require(this.files.map { it.relativePath }.toSet().size == this.files.size) { "Duplicate model path" }
        this.files.forEach { spec ->
            require(spec.id.isNotBlank() && spec.sizeBytes > 0) { "Invalid model metadata" }
            require(spec.sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid SHA-256" }
            require(spec.relativePath.matches(Regex("[a-zA-Z0-9._/-]+")) &&
                !spec.relativePath.startsWith('/') &&
                spec.relativePath.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
                !spec.relativePath.endsWith(".part")) { "Unsafe model path" }
            val uri = java.net.URI(spec.url)
            require(uri.scheme == "https" && !uri.host.isNullOrEmpty() && uri.userInfo == null && uri.fragment == null) {
                "Model downloads require HTTPS without credentials"
            }
        }
        require(totalBytes > 0) { "Invalid total model size" }
    }

    /** Fast in-process gate, valid only after a hash-checked refresh or prepare. */
    fun isReady(): Boolean = verified.size == files.size && files.all { spec ->
        runCatching { stamp(path(spec)) == verified[spec.id] }.getOrDefault(false)
    }

    fun requireReadyFile(id: String): File {
        check(isReady()) { "Offline models have not been verified" }
        return path(files.firstOrNull { it.id == id } ?: error("Unknown model ID"))
    }

    /** Rechecks installed hashes without starting, cancelling, or resetting an active download. */
    suspend fun refresh(): Boolean {
        if (activePreparation?.job?.isActive == true) return isReady()
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                if (activePreparation?.job?.isActive == true) return@withLock isReady()
                mutable.value = OfflineModelState(OfflineModelStage.CHECKING, totalBytes = totalBytes)
                try {
                    verifyExistingFiles()
                    publish(if (isReady()) OfflineModelStage.READY else OfflineModelStage.NOT_DOWNLOADED)
                    isReady()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    verified = emptyMap()
                    publish(OfflineModelStage.NOT_DOWNLOADED)
                    false
                }
            }
        }
    }

    /** Explicit user action; simultaneous callers share the completed result under the mutex. */
    suspend fun prepare() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (isReady()) {
                publish(OfflineModelStage.READY)
                return@withLock
            }
            coroutineScope {
                val operation = currentCoroutineContext()[Job]!!
                val preparation = Preparation(operation)
                activePreparation = preparation
                // Closing the active HTTP call also wakes blocking reads on caller cancellation.
                val cancellationWatcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { preparation.call.get()?.cancel() }
                }
                try {
                    if (!rootDirectory.isDirectory && !rootDirectory.mkdirs()) throw IOException("Model directory unavailable")
                    publish(OfflineModelStage.DOWNLOADING)
                    verifyExistingFiles()
                    val remaining = files.sumOf { spec ->
                        if (verified.containsKey(spec.id)) 0L
                        else spec.sizeBytes - partialLength(spec)
                    }
                    if (remaining > 0 && availableBytes() < remaining + STORAGE_RESERVE_BYTES) throw InsufficientStorage()
                    for (spec in files) {
                        currentCoroutineContext().ensureActive()
                        if (!verified.containsKey(spec.id)) download(spec, preparation)
                    }
                    currentCoroutineContext().ensureActive()
                    check(isReady()) { "Model verification incomplete" }
                    publish(OfflineModelStage.READY)
                } catch (cancelled: CancellationException) {
                    publish(OfflineModelStage.NOT_DOWNLOADED)
                    throw cancelled
                } catch (failure: Exception) {
                    if (!operation.isActive) {
                        publish(OfflineModelStage.NOT_DOWNLOADED)
                        throw CancellationException("Model preparation cancelled", failure)
                    }
                    val message = when (failure) {
                        is InsufficientStorage -> "모델을 저장할 공간이 부족합니다. 저장 공간을 확보한 뒤 다시 시도해 주세요."
                        is IntegrityFailure -> "모델 파일 검증에 실패했습니다. 다운로드를 다시 시도해 주세요."
                        else -> "모델 준비에 실패했습니다. Wi-Fi 또는 모바일 데이터 연결과 저장 공간을 확인한 뒤 다시 시도해 주세요."
                    }
                    publish(OfflineModelStage.ERROR, message = message)
                } finally {
                    preparation.call.getAndSet(null)?.cancel()
                    if (activePreparation === preparation) activePreparation = null
                    cancellationWatcher.cancel()
                }
            }
        }
    }

    /** Targets this operation only; later explicit retries remain permitted. */
    fun cancel() {
        val preparation = activePreparation ?: return
        // The I/O cancellation watcher closes the socket; never block a UI caller on TLS shutdown.
        preparation.job.cancel(CancellationException("Model preparation cancelled"))
    }

    private suspend fun verifyExistingFiles() {
        val valid = mutableMapOf<String, FileStamp>()
        verified = emptyMap()
        for (spec in files) {
            currentCoroutineContext().ensureActive()
            val file = path(spec)
            if (matches(file, spec)) valid[spec.id] = stamp(file)!!
        }
        verified = valid.toMap()
    }

    private suspend fun download(spec: OfflineModelFile, preparation: Preparation) {
        val target = path(spec)
        val partial = path(spec, partial = true)
        if (!target.parentFile!!.isDirectory && !target.parentFile!!.mkdirs()) throw IOException("Model directory unavailable")
        if (partial.exists() && (!partial.isFile || partial.length() > spec.sizeBytes)) {
            if (!partial.delete()) throw IOException("Invalid partial file")
        }
        if (partial.length() == spec.sizeBytes) {
            if (matches(partial, spec)) { promote(partial, target, spec); return }
            partial.delete()
        }
        val offset = partial.length()
        publish(OfflineModelStage.DOWNLOADING, currentFile = spec.relativePath)
        val call = transport.newCall(spec.url, offset)
        preparation.call.set(call)
        try {
            currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                val start = when (response.statusCode) {
                    200 -> 0L // Range ignored: truncate before writing the complete response.
                    206 -> {
                        val match = CONTENT_RANGE.matchEntire(response.contentRange.orEmpty())
                            ?: throw IOException("Invalid Content-Range")
                        val (first, last, total) = match.destructured
                        if (first.toLongOrNull() != offset || last.toLongOrNull() != spec.sizeBytes - 1 ||
                            total.toLongOrNull() != spec.sizeBytes) throw IOException("Unexpected Content-Range")
                        offset
                    }
                    else -> throw IOException("Model server refused the download")
                }
                if (response.contentLength != null && response.contentLength != spec.sizeBytes - start) {
                    throw IOException("Unexpected model response length")
                }
                var count = start
                FileOutputStream(partial, start > 0).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = response.body.read(buffer)
                        if (read == -1) break
                        if (read == 0) continue
                        if (count + read > spec.sizeBytes) throw IntegrityFailure()
                        output.write(buffer, 0, read)
                        count += read
                        publish(OfflineModelStage.DOWNLOADING, currentFile = spec.relativePath)
                    }
                    output.fd.sync()
                }
                if (count != spec.sizeBytes) throw IOException("Model download interrupted")
            }
            currentCoroutineContext().ensureActive()
            if (!matches(partial, spec)) throw IntegrityFailure()
            promote(partial, target, spec)
        } catch (failure: IntegrityFailure) {
            partial.delete()
            throw failure
        } finally {
            preparation.call.compareAndSet(call, null)
            call.cancel()
        }
    }

    private fun promote(partial: File, target: File, spec: OfflineModelFile) {
        // Both paths have the same parent/filesystem. Never expose an unverified runtime path.
        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        verified = verified + (spec.id to stamp(target)!!)
        publish(OfflineModelStage.DOWNLOADING, currentFile = spec.relativePath)
    }

    private suspend fun matches(file: File, spec: OfflineModelFile): Boolean {
        if (!file.isFile || file.length() != spec.sizeBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == spec.sha256
    }

    private fun path(spec: OfflineModelFile, partial: Boolean = false): File {
        val file = File(rootDirectory, spec.relativePath + if (partial) ".part" else "")
        check(file.canonicalPath.startsWith(rootDirectory.path + File.separator)) { "Model path escaped storage directory" }
        return file
    }

    private fun partialLength(spec: OfflineModelFile): Long = path(spec, partial = true).let {
        if (it.isFile) it.length().coerceIn(0, spec.sizeBytes) else 0L
    }

    private fun publish(stage: OfflineModelStage, currentFile: String? = null, message: String? = null) {
        val completed = files.sumOf { spec ->
            if (verified.containsKey(spec.id)) spec.sizeBytes
            else runCatching { partialLength(spec) }.getOrDefault(0L)
        }
        mutable.value = OfflineModelState(stage, completed, totalBytes, currentFile, message)
    }

    private fun stamp(file: File): FileStamp? = if (file.isFile) FileStamp(file.length(), file.lastModified()) else null
    private class Preparation(val job: Job) { val call = AtomicReference<ModelDownloadCall?>() }
    private data class FileStamp(val size: Long, val modified: Long)
    private class IntegrityFailure : IOException()
    private class InsufficientStorage : IOException()

    companion object {
        private const val BUFFER_BYTES = 256 * 1024
        private const val STORAGE_RESERVE_BYTES = 16L * 1024 * 1024
        private val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
    }
}
