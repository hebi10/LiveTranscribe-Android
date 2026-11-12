package com.charles.livecaptionn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.charles.livecaptionn.data.CaptionProfile
import com.charles.livecaptionn.data.CaptionProfileRepository
import com.charles.livecaptionn.data.GlossaryEntry
import com.charles.livecaptionn.data.GlossaryRepository
import com.charles.livecaptionn.settings.CaptionSettings
import com.charles.livecaptionn.settings.RecognitionLanguages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CaptionToolsScreen(
    glossaryRepository: GlossaryRepository,
    profileRepository: CaptionProfileRepository,
    currentSettings: CaptionSettings,
    canChangeSettings: Boolean,
    onApplyProfile: suspend (CaptionProfile) -> Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var entries by remember(glossaryRepository) { mutableStateOf<List<GlossaryEntry>>(emptyList()) }
    var profiles by remember(profileRepository) { mutableStateOf<List<CaptionProfile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var readFailed by remember { mutableStateOf(false) }
    var retryRead by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeIsError by remember { mutableStateOf(false) }
    var glossaryEditorOpen by remember { mutableStateOf(false) }
    var editingGlossary by remember { mutableStateOf<GlossaryEntry?>(null) }
    var glossaryToDelete by remember { mutableStateOf<GlossaryEntry?>(null) }
    var profileEditorOpen by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<CaptionProfile?>(null) }
    var profileToDelete by remember { mutableStateOf<CaptionProfile?>(null) }

    LaunchedEffect(glossaryRepository, profileRepository, retryRead) {
        loading = true
        readFailed = false
        try {
            entries = glossaryRepository.list()
            profiles = profileRepository.list()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            readFailed = true
            notice = "저장된 항목을 불러오지 못했습니다. 원본을 보존하고 다시 읽어 주세요."
            noticeIsError = true
        } finally {
            loading = false
        }
    }

    fun execute(success: String, operation: suspend () -> Boolean) {
        if (loading || busy || readFailed || !canChangeSettings) return
        busy = true
        notice = null
        scope.launch {
            try {
                val completed = operation()
                notice = if (completed) success else "요청을 적용하지 못했습니다. 현재 번역 상태를 확인해 주세요."
                noticeIsError = !completed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice = "작업을 완료하지 못했습니다. 내용을 확인하고 다시 시도해 주세요."
                noticeIsError = true
            } finally {
                busy = false
            }
        }
    }

    val editEnabled = !loading && !busy && !readFailed && canChangeSettings

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("용어집·자막 프리셋") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "뒤로")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("기기에만 저장되는 사용자 설정입니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!canChangeSettings) {
                item {
                    Text("번역 중에는 용어집 편집과 프리셋 적용·변경을 할 수 없습니다. 번역을 종료한 뒤 진행해 주세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (loading) item {
                AppStatusMessage(message = "사용자 용어집과 자막 프리셋을 불러오는 중입니다.")
            }
            notice?.let { message ->
                item {
                    if (readFailed) {
                        AppStatusMessage(
                            message = message,
                            isError = true,
                            actionLabel = "다시 읽기",
                            onAction = { notice = null; retryRead += 1 }
                        )
                    } else {
                        AppStatusMessage(message = message, isError = noticeIsError)
                    }
                }
            }
            item {
                Text("사용자 용어집", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                if (!loading && !readFailed) Text("등록된 용어 ${entries.size}개",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("번역된 자막의 일치하는 표현을 지정한 용어로 바꿉니다. 원문은 그대로 유지됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                OutlinedButton(
                    onClick = { editingGlossary = null; glossaryEditorOpen = true },
                    enabled = editEnabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RectangleShape
                ) { Text("용어 추가") }
            }
            if (!loading && !readFailed && entries.isEmpty()) {
                item { Text("등록한 용어가 없습니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(entries, key = { "glossary:" + it.id }) { entry ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RectangleShape, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(entry.phrase, fontWeight = FontWeight.SemiBold)
                        Text("변경할 표현: " + entry.replacement)
                        Text(if (entry.caseSensitive) "대소문자 구별" else "대소문자 구별 안 함",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { editingGlossary = entry; glossaryEditorOpen = true },
                                enabled = editEnabled) { Text("수정") }
                            TextButton(onClick = { glossaryToDelete = entry },
                                enabled = editEnabled) { Text("삭제") }
                        }
                    }
                }
            }
            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            item {
                Text("자막 프리셋", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                if (!loading && !readFailed) Text("저장된 프리셋 ${profiles.size}개",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("인식 언어, 글자 크기, 원문 표시 설정을 저장합니다. 적용 시 현재 설정만 변경합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Button(onClick = { editingProfile = null; profileEditorOpen = true },
                    enabled = editEnabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RectangleShape) {
                    Text("현재 설정으로 프리셋 추가")
                }
            }
            if (!loading && !readFailed && profiles.isEmpty()) {
                item { Text("저장된 자막 프리셋이 없습니다.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(profiles, key = { "profile:" + it.id }) { profile ->
                val supported = RecognitionLanguages.isValidMode(profile.sourceLanguage) &&
                    profile.targetLanguage == "ko" && profile.textSizeSp.isFinite()
                Surface(color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RectangleShape, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(profile.name, fontWeight = FontWeight.SemiBold)
                        Text(RecognitionLanguages.koreanLabel(profile.sourceLanguage) + " → 한국어 · " +
                            profile.textSizeSp.toInt() + "sp · " +
                            (if (profile.showOriginal) "원문 표시" else "원문 숨김"),
                            style = MaterialTheme.typography.bodyMedium)
                        if (!supported) Text("이 프리셋은 현재 지원하는 언어 설정과 일치하지 않습니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilledTonalButton(onClick = {
                                execute("프리셋을 적용했습니다.") { onApplyProfile(profile) }
                            }, enabled = editEnabled && supported,
                                shape = RectangleShape) { Text("적용") }
                            TextButton(onClick = { editingProfile = profile; profileEditorOpen = true },
                                enabled = editEnabled) { Text("수정") }
                            TextButton(onClick = { profileToDelete = profile },
                                enabled = editEnabled) { Text("삭제") }
                        }
                    }
                }
            }
        }
    }

    if (glossaryEditorOpen) GlossaryEditorDialog(
        initial = editingGlossary,
        busy = busy,
        canSave = editEnabled,
        onDismiss = { glossaryEditorOpen = false },
        onSave = { entry ->
            execute("용어를 저장했습니다.") {
                glossaryRepository.save(entry)
                entries = glossaryRepository.list()
                glossaryEditorOpen = false
                true
            }
        }
    )
    if (profileEditorOpen) ProfileEditorDialog(
        initial = editingProfile,
        currentSettings = currentSettings,
        busy = busy,
        canSave = editEnabled,
        onDismiss = { profileEditorOpen = false },
        onSave = { profile ->
            execute("자막 프리셋을 저장했습니다.") {
                profileRepository.save(profile)
                profiles = profileRepository.list()
                profileEditorOpen = false
                true
            }
        }
    )
    glossaryToDelete?.let { target ->
        ConfirmToolDeletionDialog(
            name = target.phrase,
            onDismiss = { glossaryToDelete = null },
            busy = busy,
            canDelete = editEnabled,
            onDelete = {
                execute("용어를 삭제했습니다.") {
                    val deleted = glossaryRepository.delete(target.id)
                    entries = glossaryRepository.list()
                    glossaryToDelete = null
                    deleted
                }
            }
        )
    }
    profileToDelete?.let { target ->
        ConfirmToolDeletionDialog(
            name = target.name,
            onDismiss = { profileToDelete = null },
            busy = busy,
            canDelete = editEnabled,
            onDelete = {
                execute("프리셋을 삭제했습니다.") {
                    val deleted = profileRepository.delete(target.id)
                    profiles = profileRepository.list()
                    profileToDelete = null
                    deleted
                }
            }
        )
    }
}

@Composable
private fun GlossaryEditorDialog(
    initial: GlossaryEntry?,
    busy: Boolean,
    canSave: Boolean,
    onDismiss: () -> Unit,
    onSave: (GlossaryEntry) -> Unit
) {
    var phrase by remember(initial?.id) { mutableStateOf(initial?.phrase.orEmpty()) }
    var replacement by remember(initial?.id) { mutableStateOf(initial?.replacement.orEmpty()) }
    var caseSensitive by remember(initial?.id) { mutableStateOf(initial?.caseSensitive ?: false) }
    val valid = phrase.trim().isNotEmpty() && replacement.trim().isNotEmpty() &&
        phrase.trim().length <= 120 && replacement.trim().length <= 160

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = RectangleShape,
        title = { Text(if (initial == null) "용어 추가" else "용어 수정") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = phrase, onValueChange = { phrase = it },
                    label = { Text("찾을 표현") }, placeholder = { Text("예: ChatGPT") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = replacement, onValueChange = { replacement = it },
                    label = { Text("변경할 표현") }, placeholder = { Text("예: 챗지피티") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = caseSensitive, role = Role.Switch,
                        onValueChange = { caseSensitive = it }),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("대소문자 구별", modifier = Modifier.weight(1f))
                    Switch(checked = caseSensitive, onCheckedChange = null)
                }
                Text("찾을 표현은 120자, 변경할 표현은 160자 이내로 입력해 주세요. 이후 번역 결과에 적용되며 원문은 그대로 유지됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !busy && canSave,
                onClick = {
                    val saved = if (initial == null) GlossaryEntry(
                        phrase = phrase.trim(), replacement = replacement.trim(),
                        caseSensitive = caseSensitive)
                    else initial.copy(phrase = phrase.trim(),
                        replacement = replacement.trim(), caseSensitive = caseSensitive)
                    onSave(saved)
                }
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("취소") } }
    )
}

@Composable
private fun ProfileEditorDialog(
    initial: CaptionProfile?,
    currentSettings: CaptionSettings,
    busy: Boolean,
    canSave: Boolean,
    onDismiss: () -> Unit,
    onSave: (CaptionProfile) -> Unit
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var language by remember(initial?.id) {
        mutableStateOf(RecognitionLanguages.normalizeMode(initial?.sourceLanguage ?: currentSettings.sourceLanguageCode))
    }
    var textSize by remember(initial?.id) {
        mutableStateOf((initial?.textSizeSp ?: currentSettings.textSizeSp).coerceIn(14f, 40f))
    }
    var showOriginal by remember(initial?.id) {
        mutableStateOf(initial?.showOriginal ?: currentSettings.showOriginal)
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = RectangleShape,
        title = { Text(if (initial == null) "프리셋 추가" else "프리셋 수정") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("프리셋 이름") }, placeholder = { Text("예: 영화 자막") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Text("음성 인식 언어", style = MaterialTheme.typography.labelLarge)
                for ((code, label) in listOf(
                    RecognitionLanguages.AUTO to "자동 감지", "en" to "영어", "ja" to "일본어"
                )) {
                    val selected = language == code
                    if (selected) FilledTonalButton(
                        onClick = { language = code }, shape = RectangleShape,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text(label) }
                    else OutlinedButton(
                        onClick = { language = code }, shape = RectangleShape,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) { Text(label) }
                }
                Text("글자 크기: " + textSize.toInt() + "sp")
                Slider(value = textSize, onValueChange = { textSize = it },
                    valueRange = 14f..40f)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .toggleable(value = showOriginal, role = Role.Switch,
                        onValueChange = { showOriginal = it }),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("원문 함께 표시", modifier = Modifier.weight(1f))
                    Switch(checked = showOriginal, onCheckedChange = null)
                }
                Text("프리셋 저장은 현재 실행 중인 자막에 영향을 주지 않습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = name.trim().isNotEmpty() && name.trim().length <= 60 && !busy && canSave,
                onClick = {
                    val profile = if (initial == null) CaptionProfile(
                        name = name.trim(), sourceLanguage = language, targetLanguage = "ko",
                        textSizeSp = textSize, showOriginal = showOriginal
                    ) else initial.copy(
                        name = name.trim(), sourceLanguage = language, targetLanguage = "ko",
                        textSizeSp = textSize, showOriginal = showOriginal
                    )
                    onSave(profile)
                }
            ) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("취소") } }
    )
}

@Composable
private fun ConfirmToolDeletionDialog(
    name: String,
    busy: Boolean,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = RectangleShape,
        title = { Text("항목 삭제") },
        text = { Text("‘" + name + "’ 항목을 삭제하시겠습니까? 삭제한 항목은 복구할 수 없습니다.") },
        confirmButton = {
            TextButton(onClick = onDelete, enabled = !busy && canDelete) {
                Text("삭제", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("취소") } }
    )
}
