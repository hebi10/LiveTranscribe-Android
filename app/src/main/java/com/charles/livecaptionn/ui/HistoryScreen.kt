package com.charles.livecaptionn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charles.livecaptionn.data.HistoryPolicy
import com.charles.livecaptionn.data.TranscriptEntry
import com.charles.livecaptionn.data.TranscriptHistoryStore
import com.charles.livecaptionn.settings.RecognitionLanguages
import com.charles.livecaptionn.ui.l10n.LocalUiStrings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    historyStore: TranscriptHistoryStore,
    historySavingEnabled: Flow<Boolean>,
    onHistorySavingChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val t = LocalUiStrings.current
    val controller = remember(historyStore) {
        HistoryController(
            load = historyStore::getAll,
            delete = { historyStore.deleteById(it) },
            clear = historyStore::clear
        )
    }
    val history by controller.state.collectAsStateWithLifecycle()
    val saveHistory: Boolean? by historySavingEnabled.collectAsStateWithLifecycle(initialValue = null)
    val historyLimit = remember { HistoryPolicy().maxEntries }
    val entries = history.entries
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<TranscriptEntry?>(null) }

    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(historyStore) {
        historyStore.revisions.collect { controller.refresh() }
    }

    val filteredEntries = remember(entries, searchQuery) {
        if (searchQuery.isBlank()) entries
        else entries.filter { entry ->
            entry.originalText.contains(searchQuery, ignoreCase = true) ||
                entry.translatedText.contains(searchQuery, ignoreCase = true)
        }
    }

    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(t["Delete entry"]) },
            text = {
                Text(t.format("Delete this transcript entry?\n\n\"%s\"", deleteTarget?.originalText?.take(60)))
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = deleteTarget ?: return@TextButton
                    scope.launch { controller.delete(target.id) }
                    deleteTarget = null
                }, enabled = !history.busy) {
                    Text(t["Delete"])
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(t["Cancel"])
                }
            }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("전체 기록 삭제") },
            text = { Text("저장된 번역 기록을 모두 삭제하시겠습니까? 삭제한 기록은 복구할 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { controller.clear() }
                }, enabled = !history.busy) { Text("전체 삭제") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("취소") }
            }
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(t["Transcript History"]) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t["Back"])
                    }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        TextButton(onClick = {
                            confirmClear = true
                        }, enabled = !history.busy) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(t["Clear"])
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            item(key = "history-saving") {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        val savingState = when (saveHistory) {
                            true -> "켜짐"
                            false -> "꺼짐"
                            null -> "설정 확인 중"
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .semantics { stateDescription = savingState }
                                .toggleable(
                                    value = saveHistory == true,
                                    enabled = saveHistory != null,
                                    role = Role.Switch,
                                    onValueChange = onHistorySavingChange
                                ),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("번역 기록 저장", style = MaterialTheme.typography.bodyMedium)
                                Text(savingState, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = saveHistory == true,
                                onCheckedChange = null,
                                enabled = saveHistory != null
                            )
                        }
                        Text(
                            "기본 꺼짐 · 최근 ${historyLimit}개를 기기에 저장하며, 초과하면 가장 오래된 기록부터 삭제합니다. " +
                                "끄면 이후 저장을 중단하고 기존 기록은 유지합니다. 기존 기록은 개별 삭제하거나 전체 삭제할 수 있습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            history.error?.let { message ->
                item(key = "history-error") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(message, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        TextButton(onClick = { scope.launch { controller.retry() } }, enabled = !history.busy) {
                            Text("다시 시도")
                        }
                    }
                }
            }
            if (entries.isNotEmpty() || searchQuery.isNotBlank()) {
                item(key = "history-search") {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text(t["Search transcripts…"]) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = t["Clear search"], modifier = Modifier.size(20.dp))
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            }

            if (entries.isEmpty() && (history.error != null || history.busy)) {
                if (history.busy) {
                    item(key = "history-loading") {
                        Text("기록을 불러오는 중…", modifier = Modifier.padding(16.dp))
                    }
                }
            } else if (filteredEntries.isEmpty()) {
                item(key = "history-empty") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            if (entries.isNotEmpty() && searchQuery.isNotBlank()) t["No matching transcripts"]
                            else t["No transcripts yet"],
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (entries.isNotEmpty() && searchQuery.isNotBlank()) t["Try a different search term."]
                            else when (saveHistory) {
                                true -> "이후 확정된 번역이 여기에 저장됩니다."
                                false -> "위의 기록 저장을 켜면 이후 번역이 여기에 저장됩니다."
                                null -> "기록 저장 설정을 확인하고 있습니다."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else {
                items(filteredEntries, key = { "entry:${it.id}" }) { entry ->
                    TranscriptCard(
                        entry = entry,
                        onDelete = { deleteTarget = entry },
                        deleteEnabled = !history.busy,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)
                    )
                }
            }
            item(key = "history-bottom-space") { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun TranscriptCard(
    entry: TranscriptEntry,
    onDelete: () -> Unit,
    deleteEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val t = LocalUiStrings.current
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    var originalOverflow by remember(entry.originalText) { mutableStateOf(false) }
    var translatedOverflow by remember(entry.translatedText) { mutableStateOf(false) }
    val timeStr = remember(entry.timestamp) {
        val fmt = SimpleDateFormat("M월 d일 a h:mm", Locale.KOREAN)
        fmt.format(Date(entry.timestamp))
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = timeStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${historyLanguageLabel(entry.sourceLanguage)} → ${historyLanguageLabel(entry.targetLanguage)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    IconButton(
                        onClick = onDelete,
                        enabled = deleteEnabled,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = t["Delete"],
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            Text(
                text = entry.originalText,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) originalOverflow = it.hasVisualOverflow }
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = entry.translatedText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) translatedOverflow = it.hasVisualOverflow }
            )
            if (expanded || originalOverflow || translatedOverflow) {
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.semantics {
                        stateDescription = if (expanded) "전체 내용 펼쳐짐" else "내용 접힘"
                    }
                ) { Text(if (expanded) "접기" else "전체 내용 펼치기") }
            }
        }
    }
}


internal fun historyLanguageLabel(code: String): String = RecognitionLanguages.koreanLabel(code)
