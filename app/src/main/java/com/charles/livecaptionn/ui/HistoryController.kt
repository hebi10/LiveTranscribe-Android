package com.charles.livecaptionn.ui

import com.charles.livecaptionn.data.TranscriptEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class HistoryUiState(
    val entries: List<TranscriptEntry> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null
)

/** Keeps the last readable snapshot until storage confirms a replacement. */
internal class HistoryController(
    private val load: suspend () -> List<TranscriptEntry>,
    private val delete: suspend (String) -> Unit,
    private val clear: suspend () -> Unit
) {
    private sealed interface Action {
        data object Refresh : Action
        data class Delete(val id: String) : Action
        data object Clear : Action
    }

    private val mutex = Mutex()
    private val mutable = MutableStateFlow(HistoryUiState())
    val state = mutable.asStateFlow()
    private var failedAction: Action? = null

    // Revision reads wait for a mutation, so its resulting snapshot cannot be missed.
    suspend fun refresh() = mutex.withLock { execute(Action.Refresh) }
    suspend fun delete(id: String) = submit(Action.Delete(id))
    suspend fun clear() = submit(Action.Clear)
    suspend fun retry() { failedAction?.let { submit(it) } }

    private suspend fun submit(action: Action) {
        // Ignore repeated taps while another operation is in flight.
        if (!mutex.tryLock()) return
        try { execute(action) } finally { mutex.unlock() }
    }

    private suspend fun execute(action: Action) {
        mutable.value = mutable.value.copy(busy = true, error = null)
        failedAction = null
        var pending = action
        try {
            when (action) {
                is Action.Delete -> delete.invoke(action.id)
                Action.Clear -> clear.invoke()
                Action.Refresh -> Unit
            }
            // After a successful mutation, retry only the read if it fails.
            pending = Action.Refresh
            val entries = load()
            mutable.value = mutable.value.copy(entries = entries)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failedAction = pending
            val message = when (pending) {
                is Action.Delete -> "기록을 삭제하지 못했습니다. 다시 시도해 주세요."
                Action.Clear -> "전체 기록을 삭제하지 못했습니다. 다시 시도해 주세요."
                Action.Refresh -> "기록을 불러오지 못했습니다. 다시 시도해 주세요."
            }
            mutable.value = mutable.value.copy(error = message)
        } finally {
            mutable.value = mutable.value.copy(busy = false)
        }
    }
}
