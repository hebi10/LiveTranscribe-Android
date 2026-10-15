package com.charles.livecaptionn.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class TranscriptHistoryStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun entry(id: String, timestamp: Long = 1) = TranscriptEntry(
        timestamp = timestamp, originalText = "private-$id", translatedText = "번역-$id",
        sourceLanguage = "en", targetLanguage = "ko", id = id
    )

    private fun store(file: File) = TranscriptHistoryStore(file, forTesting = true)
    private fun sidecar(file: File, suffix: String) = File(file.parentFile, file.name + suffix)

    @Test fun absentCommittedCopiesAreAnEmptyHistory() = runBlocking {
        assertTrue(store(File(temporary.root, "history.json")).getAll().isEmpty())
    }

    @Test fun readableBackupRecoversWhenExistingPrimaryCannotBeRead() = runBlocking {
        val file = File(temporary.root, "history.json")
        val history = store(file)
        history.add(entry("saved"))
        assertTrue(file.delete())
        assertTrue(file.mkdir())
        assertEquals(listOf("saved"), history.getAll().map { it.id })
    }

    @Test fun existingCorruptCopiesRaiseInsteadOfBecomingAnEmptySnapshot() = runBlocking {
        val file = File(temporary.root, "history.json")
        file.writeText("broken-primary")
        sidecar(file, ".bak").writeText("broken-backup")
        try {
            store(file).getAll()
            throw AssertionError("Expected recoverable read error")
        } catch (_: IOException) { }
        assertEquals("broken-primary", file.readText())
        assertEquals("broken-backup", sidecar(file, ".bak").readText())
    }

    @Test fun unreadableExistingHistoryCannotBeOverwrittenByANewEntry() = runBlocking {
        val file = File(temporary.root, "history.json")
        val history = store(file)
        history.add(entry("saved"))
        file.writeText("broken-primary")
        sidecar(file, ".bak").writeText("broken-backup")
        try {
            history.add(entry("new"))
            throw AssertionError("Expected recoverable write precondition error")
        } catch (_: IOException) { }
        assertEquals("broken-primary", file.readText())
        assertEquals("broken-backup", sidecar(file, ".bak").readText())
        assertEquals(1L, history.revisions.value)
    }

    @Test fun deletedIdCannotReturnWhenPrimaryIsCorrupted() = runBlocking {
        val file = File(temporary.root, "history.json")
        val history = store(file)
        history.add(entry("deleted"))
        history.add(entry("survivor"))
        assertTrue(history.deleteById("deleted"))
        assertFalse(sidecar(file, ".bak").readText().contains("private-deleted"))
        file.writeText("invalid json")
        assertEquals(listOf("survivor"), store(file).getAll().map { it.id })
    }

    @Test fun timestampDeletionRemovesEveryMatchingEntryFromRecovery() = runBlocking {
        val file = File(temporary.root, "history.json")
        val history = store(file)
        history.add(entry("first", 7))
        history.add(entry("second", 7))
        history.add(entry("survivor", 8))
        assertTrue(history.delete(7))
        file.writeText("broken")
        assertEquals(listOf("survivor"), store(file).getAll().map { it.id })
    }

    @Test fun retentionTrimAlsoTrimsRecovery() = runBlocking {
        val file = File(temporary.root, "history.json")
        val history = store(file)
        history.add(entry("old"))
        history.add(entry("new"))
        history.setPolicy(HistoryPolicy(maxEntries = 1))
        file.writeText("broken")
        assertEquals(listOf("new"), store(file).getAll().map { it.id })
    }

    @Test fun clearRemovesPrimaryBackupAndInterruptedWrite() = runBlocking {
        val file = File(temporary.root, "history.json")
        val history = store(file)
        history.add(entry("saved"))
        sidecar(file, ".tmp").writeText("private-interrupted")
        history.clear()
        assertTrue(temporary.root.listFiles()!!.isEmpty())
        assertTrue(store(file).getAll().isEmpty())
    }

    @Test fun disablingRetentionClearsAllFilesAndRejectsNewPersistence() = runBlocking {
        val file = File(temporary.root, "history.json")
        val history = store(file)
        history.add(entry("saved"))
        sidecar(file, ".tmp").writeText("private-interrupted")
        history.setPolicy(HistoryPolicy(retainHistory = false))
        history.add(entry("unsaved"))
        assertTrue(temporary.root.listFiles()!!.isEmpty())
        assertTrue(history.getAll().isEmpty())
    }

    @Test fun migrationMovesOnlyLegacyPrimaryAndRemovesAllLegacyCopies() = runBlocking {
        val legacy = File(temporary.root, "files/history.json")
        store(legacy).add(entry("migrate"))
        sidecar(legacy, ".bak").writeText("[{\"originalText\":\"previously-deleted\"}]")
        sidecar(legacy, ".tmp").writeText("private-interrupted")
        val privateFile = File(temporary.root, "no_backup/history.json")
        val history = TranscriptHistoryStore(privateFile, forTesting = true, legacyFile = legacy)
        assertEquals(listOf("migrate"), history.getAll().map { it.id })
        assertTrue(legacy.parentFile!!.listFiles()!!.isEmpty())
        privateFile.writeText("broken")
        assertEquals(listOf("migrate"), store(privateFile).getAll().map { it.id })
    }

    @Test fun corruptLegacyPrimaryNeverRestoresLegacyBackup() = runBlocking {
        val legacy = File(temporary.root, "files/history.json")
        store(legacy).add(entry("deleted"))
        legacy.writeText("broken")
        val privateFile = File(temporary.root, "no_backup/history.json")
        val history = TranscriptHistoryStore(privateFile, forTesting = true, legacyFile = legacy)
        assertTrue(history.getAll().isEmpty())
        assertTrue(legacy.parentFile!!.listFiles()!!.isEmpty())
    }

    @Test fun existingPrivateHistoryWinsMigrationAndLegacyCopiesAreRemoved() = runBlocking {
        val legacy = File(temporary.root, "files/history.json")
        store(legacy).add(entry("obsolete"))
        val privateFile = File(temporary.root, "no_backup/history.json")
        store(privateFile).add(entry("current"))
        val history = TranscriptHistoryStore(privateFile, forTesting = true, legacyFile = legacy)
        assertEquals(listOf("current"), history.getAll().map { it.id })
        assertTrue(legacy.parentFile!!.listFiles()!!.isEmpty())
    }

    @Test fun failedBackupRemovalCannotPublishSuccessfulDeletion() = runBlocking {
        val file = File(temporary.root, "history.json")
        val history = store(file)
        history.add(entry("saved"))
        val backup = sidecar(file, ".bak")
        assertTrue(backup.delete())
        assertTrue(backup.mkdir())
        File(backup, "block-deletion").writeText("obstruction")
        try {
            history.deleteById("saved")
            throw AssertionError("Expected failed deletion")
        } catch (_: IOException) { }
        assertEquals(listOf("saved"), history.getAll().map { it.id })
        assertEquals(1L, history.revisions.value)
    }

    @Test fun revisionsPublishSuccessfulMutationsButNotReadsOrMissingDeletes() = runBlocking {
        val history = store(File(temporary.root, "history.json"))
        assertEquals(0L, history.revisions.value)
        history.add(entry("saved"))
        assertEquals(1L, history.revisions.value)
        history.getAll()
        assertFalse(history.deleteById("missing"))
        assertEquals(1L, history.revisions.value)
        assertTrue(history.deleteById("saved"))
        assertEquals(2L, history.revisions.value)
        history.clear()
        assertEquals(3L, history.revisions.value)
        history.setPolicy(HistoryPolicy(retainHistory = false))
        assertEquals(4L, history.revisions.value)
    }
}
