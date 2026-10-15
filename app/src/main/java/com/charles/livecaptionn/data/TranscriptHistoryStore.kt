package com.charles.livecaptionn.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Persists private history outside Android backup, with a current-snapshot recovery copy. */
class TranscriptHistoryStore private constructor(
    private val file: File,
    private val legacyFile: File?
) {
    constructor(context: Context) : this(
        File(context.noBackupFilesDir, FILE_NAME),
        File(context.filesDir, FILE_NAME)
    )

    internal constructor(file: File, forTesting: Boolean = true, legacyFile: File? = null) : this(file, legacyFile)

    private val mutex = Mutex()
    private var policy = HistoryPolicy()
    private var initialized = false
    private val mutableRevisions = MutableStateFlow(0L)

    /** Changes only after a successful add, delete, clear or policy update. */
    val revisions: StateFlow<Long> = mutableRevisions.asStateFlow()

    suspend fun add(entry: TranscriptEntry): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            initializeStorage()
            if (policy.retainHistory) {
                val arr = readArray()
                arr.put(entry.toJson())
                trim(arr, policy.maxEntries)
                atomicWrite(arr.toString())
            } else {
                deleteCopies(file)
            }
            publishRevision()
        }
    }

    suspend fun getAll(): List<TranscriptEntry> = mutex.withLock {
        withContext(Dispatchers.IO) {
            initializeStorage()
            val arr = readArray()
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                TranscriptEntry(
                    timestamp = obj.optLong("timestamp", 0L),
                    originalText = obj.optString("originalText", ""),
                    translatedText = obj.optString("translatedText", ""),
                    sourceLanguage = obj.optString("sourceLanguage", ""),
                    targetLanguage = obj.optString("targetLanguage", ""),
                    id = obj.optString("id", legacyId(obj))
                )
            }.reversed()
        }
    }

    suspend fun delete(timestamp: Long): Boolean = deleteMatching {
        it.optLong("timestamp", 0L) == timestamp
    }

    suspend fun deleteById(id: String): Boolean = deleteMatching {
        it.optString("id", legacyId(it)) == id
    }

    private suspend fun deleteMatching(matches: (JSONObject) -> Boolean): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            initializeStorage()
            val arr = readArray()
            val result = JSONArray()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (!matches(obj)) result.put(obj)
            }
            if (result.length() == arr.length()) return@withContext false
            atomicWrite(result.toString())
            publishRevision()
            true
        }
    }

    suspend fun setPolicy(newPolicy: HistoryPolicy): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            initializeStorage()
            if (!newPolicy.retainHistory) {
                deleteCopies(file)
            } else {
                val arr = readArray()
                trim(arr, newPolicy.maxEntries)
                atomicWrite(arr.toString())
            }
            // Publish the policy only after its storage change succeeds.
            policy = newPolicy
            publishRevision()
        }
    }

    suspend fun clear(): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            initializeStorage()
            deleteCopies(file)
            publishRevision()
        }
    }

    private fun initializeStorage() {
        if (initialized) return
        // An interrupted write is never a committed snapshot.
        deleteChecked(temp)
        legacyFile?.let { legacy ->
            if (!file.exists()) {
                // Legacy .bak held the PREVIOUS snapshot, which can include deleted text.
                // Never recover or migrate that copy, even when the primary is corrupt.
                readValidArray(legacy)?.let { atomicWrite(it.toString()) }
            }
            deleteCopies(legacy)
        }
        initialized = true
    }

    private fun readArray(): JSONArray {
        readValidArray(file)?.let { return it }
        readValidArray(backup)?.let { return it }
        // An existing but unreadable snapshot is not an empty history. Preserve both
        // copies for recovery and prevent subsequent mutations from replacing them.
        if (file.exists() || backup.exists()) {
            throw IOException("No readable history snapshot")
        }
        return JSONArray()
    }

    private fun readValidArray(candidate: File): JSONArray? {
        if (!candidate.exists()) return null
        return try {
            JSONArray(candidate.readText()).also { array ->
                // Validate entries before choosing this snapshot for recovery or migration.
                for (i in 0 until array.length()) array.getJSONObject(i)
            }
        } catch (_: JSONException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private fun atomicWrite(contents: String) {
        val parent = requireNotNull(file.parentFile)
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create history directory")
        // Remove the old recovery copy BEFORE committing a mutation: a crash after the
        // primary replacement must never make a deletion recoverable from an older copy.
        deleteChecked(backup)
        writeSynced(temp, contents)
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        // Recovery is optional. Its contents always match the committed primary, never
        // a pre-deletion/pre-retention snapshot. A failed copy cannot resurrect old text.
        try {
            writeSynced(backup, contents)
        } catch (_: IOException) {
            backup.delete()
        }
    }

    private fun writeSynced(target: File, contents: String) {
        FileOutputStream(target).use { stream ->
            stream.write(contents.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
    }

    private fun deleteCopies(primary: File) {
        // Remove recoverable copies first; failures must not be reported as deletion success.
        deleteChecked(File(primary.parentFile, "${primary.name}.bak"))
        deleteChecked(File(primary.parentFile, "${primary.name}.tmp"))
        deleteChecked(primary)
    }

    private fun deleteChecked(target: File) {
        if (target.exists() && !target.delete()) throw IOException("Cannot delete history file: ${target.name}")
    }

    private fun trim(array: JSONArray, maxEntries: Int) {
        while (array.length() > maxEntries) array.remove(0)
    }

    private fun publishRevision() { mutableRevisions.value += 1L }
    private fun legacyId(obj: JSONObject): String = "legacy-${obj.optLong("timestamp", 0L)}-${obj.optString("originalText").hashCode()}"
    private val backup get() = File(file.parentFile, "${file.name}.bak")
    private val temp get() = File(file.parentFile, "${file.name}.tmp")

    private fun TranscriptEntry.toJson() = JSONObject().apply {
        put("id", id)
        put("timestamp", timestamp)
        put("originalText", originalText)
        put("translatedText", translatedText)
        put("sourceLanguage", sourceLanguage)
        put("targetLanguage", targetLanguage)
    }

    companion object { private const val FILE_NAME = "transcript_history.json" }
}

data class HistoryPolicy(val maxEntries: Int = 500, val retainHistory: Boolean = true) {
    init { require(maxEntries > 0) }
}
