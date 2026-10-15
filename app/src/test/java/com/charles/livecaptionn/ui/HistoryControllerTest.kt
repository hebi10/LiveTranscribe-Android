package com.charles.livecaptionn.ui

import com.charles.livecaptionn.data.TranscriptEntry
import com.charles.livecaptionn.data.TranscriptHistoryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class HistoryControllerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun realUnreadableHistoryKeepsVisibleEntriesAndRetriesAfterStorageRecovery() = runTest {
        val file = File(temporary.root, "history.json")
        val backup = File(temporary.root, "history.json.bak")
        val store = TranscriptHistoryStore(file, forTesting = true)
        store.add(entry)
        val saved = file.readText()
        val controller = HistoryController(load = store::getAll,
            delete = { store.deleteById(it) }, clear = store::clear)
        controller.refresh()
        assertTrue(file.delete())
        assertTrue(backup.delete())
        assertTrue(file.mkdir())
        assertTrue(backup.mkdir())

        controller.refresh()

        assertEquals(listOf(entry), controller.state.value.entries)
        assertEquals("기록을 불러오지 못했습니다. 다시 시도해 주세요.", controller.state.value.error)
        assertTrue(file.isDirectory)
        assertTrue(backup.isDirectory)
        assertTrue(file.delete())
        file.writeText(saved)
        controller.retry()
        assertEquals(listOf(entry), controller.state.value.entries)
        assertNull(controller.state.value.error)
    }

    private val entry = TranscriptEntry(id = "saved", originalText = "original", translatedText = "번역",
        sourceLanguage = "en", targetLanguage = "ko")

    @Test fun failedRefreshRetainsVisibleHistoryAndCanRetry() = runTest {
        var fail = false
        val controller = HistoryController(load = {
            if (fail) throw IOException("private details")
            listOf(entry)
        }, delete = {}, clear = {})
        controller.refresh()
        fail = true
        controller.refresh()
        assertEquals(listOf(entry), controller.state.value.entries)
        assertEquals("기록을 불러오지 못했습니다. 다시 시도해 주세요.", controller.state.value.error)
        assertFalse(controller.state.value.busy)
        fail = false
        controller.retry()
        assertNull(controller.state.value.error)
        assertEquals(listOf(entry), controller.state.value.entries)
    }

    @Test fun failedDeleteRetainsEntryAndRetryDeletesOnlyTheRequestedId() = runTest {
        var entries = listOf(entry, entry.copy(id = "keep"))
        var fail = true
        val controller = HistoryController(load = { entries }, delete = { id ->
            if (fail) throw IOException()
            entries = entries.filterNot { it.id == id }
        }, clear = {})
        controller.refresh()
        controller.delete(entry.id)
        assertEquals(2, controller.state.value.entries.size)
        assertEquals("기록을 삭제하지 못했습니다. 다시 시도해 주세요.", controller.state.value.error)
        fail = false
        controller.retry()
        assertEquals(listOf("keep"), controller.state.value.entries.map { it.id })
        assertNull(controller.state.value.error)
    }

    @Test fun failedClearRetainsEntriesUntilSuccessfulRetry() = runTest {
        var entries = listOf(entry)
        var fail = true
        val controller = HistoryController(load = { entries }, delete = {}, clear = {
            if (fail) throw IOException()
            entries = emptyList()
        })
        controller.refresh()
        controller.clear()
        assertEquals(listOf(entry), controller.state.value.entries)
        assertEquals("전체 기록을 삭제하지 못했습니다. 다시 시도해 주세요.", controller.state.value.error)
        fail = false
        controller.retry()
        assertTrue(controller.state.value.entries.isEmpty())
        assertNull(controller.state.value.error)
    }

    @Test fun refreshFailureAfterDeleteRetriesReadingWithoutRepeatingDeletion() = runTest {
        var entries = listOf(entry)
        var failLoad = false
        var deletions = 0
        val controller = HistoryController(load = {
            if (failLoad) throw IOException()
            entries
        }, delete = {
            deletions++
            entries = emptyList()
            failLoad = true
        }, clear = {})
        controller.refresh()
        controller.delete(entry.id)
        assertEquals(listOf(entry), controller.state.value.entries)
        assertEquals("기록을 불러오지 못했습니다. 다시 시도해 주세요.", controller.state.value.error)
        failLoad = false
        controller.retry()
        assertTrue(controller.state.value.entries.isEmpty())
        assertEquals(1, deletions)
    }

    @Test fun inFlightDeletionRejectsDuplicateDestructiveActions() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val controller = HistoryController(load = { listOf(entry) }, delete = {
            entered.complete(Unit)
            release.await()
        }, clear = { fail("clear must be ignored during delete") })
        controller.refresh()
        val deletion = async { controller.delete(entry.id) }
        entered.await()
        assertTrue(controller.state.value.busy)
        controller.delete(entry.id)
        controller.clear()
        release.complete(Unit)
        deletion.await()
        assertFalse(controller.state.value.busy)
    }

    @Test fun cancellationPropagatesWithoutAnErrorOrLostEntries() = runTest {
        val controller = HistoryController(load = { listOf(entry) }, delete = {
            throw CancellationException("leaving screen")
        }, clear = {})
        controller.refresh()
        try {
            controller.delete(entry.id)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals(listOf(entry), controller.state.value.entries)
        assertNull(controller.state.value.error)
        assertFalse(controller.state.value.busy)
    }
}
