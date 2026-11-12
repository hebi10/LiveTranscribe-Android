package com.charles.livecaptionn.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.charles.livecaptionn.overlay.OverlayScrollPosition
import com.charles.livecaptionn.overlay.visibleOverlayMessages
import com.charles.livecaptionn.service.buildOverlayUiState
import com.charles.livecaptionn.settings.CaptionDisplayMode
import com.charles.livecaptionn.settings.RecognitionLanguages

private val CaptionReaderSaver = listSaver<CaptionReadingState, Any>(
    save = { state ->
        val p = state.position
        listOf(state.followingLatest, p != null, p?.rowId ?: -1L, p?.offsetFromRowTop ?: 0,
            p?.absoluteY ?: 0, p?.textOffset ?: -1, p?.source ?: false, p?.offsetFromLineTop ?: 0, state.sessionId)
    },
    restore = { values ->
        CaptionReadingState(if (values[1] as Boolean) OverlayScrollPosition(
            (values[2] as Long).takeIf { it != -1L }, values[3] as Int, values[4] as Int,
            (values[5] as Int).takeIf { it >= 0 }, values[6] as Boolean, values[7] as Int) else null,
            values[0] as Boolean, values[8] as Long)
    }
)

@Composable
fun CaptionScreen(
    ui: MainUiState,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
    onChooseApp: () -> Unit,
    onSplitHelp: () -> Unit,
    onShowOriginalChange: (Boolean) -> Unit,
    onTextSizeChange: (Float) -> Unit,
    onDisplayModeChange: (CaptionDisplayMode) -> Unit,
    onStart: () -> Unit,
    onAcceptLanguage: (String) -> Unit,
    onDismissLanguage: () -> Unit,
    startPending: Boolean = false,
    modifier: Modifier = Modifier
) {
    val presentation = buildOverlayUiState(ui.runtime, ui.settings)
    val messages = visibleOverlayMessages(presentation)
    val reader = rememberSaveable(saver = CaptionReaderSaver) { CaptionReadingState(sessionId = ui.runtime.sessionId) }
    var readingView by remember { mutableStateOf<CaptionReadingView?>(null) }
    var following by remember { mutableStateOf(reader.followingLatest) }
    if (reader.bindSession(ui.runtime.sessionId)) following = true
    var showMore by rememberSaveable { mutableStateOf(false) }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val running = ui.runtime.running
    val stopping = ui.runtime.stopping
    val busy = running || startPending
    val notice = listOfNotNull(ui.runtime.actionableNotice, ui.runtime.restartNotice,
        ui.runtime.information).distinct().joinToString("\n\n")
    val suggestion = ui.runtime.suggestedLanguageCode
    val status = when {
        startPending -> "시작 준비 중"
        !running && messages.isNotEmpty() && ui.runtime.lastError == null -> "번역 완료"
        else -> ui.runtime.phase.label
    }
    val foreground = MaterialTheme.colorScheme.onSurface.toArgb()
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Keep the background edge-to-edge; only content avoids bars/cutouts.
        // Inset padding consumes only what the ancestor has not already handled.
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val compactHeight = maxHeight < 300.dp
            val compact = isSplitCaptionCompact(maxWidth.value, maxHeight.value)
            Column(Modifier.fillMaxSize()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RectangleShape) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .padding(start = if (compact) 8.dp else 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).heightIn(min = 48.dp)
                        .clickable(onClickLabel = "상태와 알림 보기", role = Role.Button) { showDetails = true }
                        .padding(vertical = 8.dp)) {
                        if (!compact) Text(when {
                            suggestion != null -> "언어 전환 추천 · 자세히"
                            notice.isNotBlank() -> "알림 · 자세히 보기"
                            else -> "분할 자막"
                        }, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Text(if (compact && (suggestion != null || notice.isNotBlank())) "알림 · $status" else status,
                            style = MaterialTheme.typography.labelLarge, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = if (busy) onPauseResume else if (ui.canStart) onStart else onSettings,
                        enabled = !stopping && !startPending, modifier = Modifier.size(48.dp)) {
                        val paused = ui.runtime.paused || !busy
                        Icon(if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause,
                            contentDescription = if (!busy) { if (ui.canStart) "번역 시작" else "시작 준비" }
                            else if (paused) "번역 계속" else "번역 일시정지")
                    }
                    if (busy) IconButton(onClick = onStop, enabled = !stopping, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Stop, contentDescription = if (startPending) "시작 취소" else "번역 종료")
                    }
                    IconButton(onClick = { showMore = true }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "자막 설정 및 더 보기")
                    }
                }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (messages.isEmpty()) {
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                .padding(horizontal = if (compact) 12.dp else 24.dp,
                                    vertical = if (compactHeight) 8.dp else 24.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(if (busy) "자막 대기 중" else "영상 옆에, 자막만",
                                style = if (compact) MaterialTheme.typography.titleMedium
                                    else MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold)
                            Text(if (busy) "음성이 인식되면 번역 자막이 여기에 표시됩니다."
                                else if (compact) "번역 후 함께 볼 앱을 선택하세요."
                                else "번역을 시작하고 함께 볼 앱을 선택하세요. 화면 비율은 Android 분할선에서 조절합니다.",
                                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!busy) Button(onClick = if (ui.canStart) onStart else onSettings,
                                modifier = Modifier.heightIn(min = 48.dp), shape = RectangleShape) {
                                Text(if (ui.canStart) "번역 시작" else "시작 준비")
                            }
                            if (compactHeight) {
                                TextButton(onClick = onChooseApp, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text("함께 볼 앱 선택")
                                }
                                TextButton(onClick = onSplitHelp, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text("분할 화면 안내")
                                }
                            }
                        }
                    } else {
                        AndroidView(
                            factory = { context -> CaptionReadingView(context, reader).also { readingView = it } },
                            modifier = Modifier.fillMaxSize(),
                            update = { view ->
                                view.onFollowingChanged = { following = it }
                                view.render(messages, ui.settings.showOriginal, ui.settings.textSizeSp, foreground, secondary)
                            }
                        )
                    }
                }
                if (!following && messages.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RectangleShape) {
                        TextButton(onClick = { readingView?.showLatest() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Text(if (compact) "최신 자막으로 이동"
                                else "이전 자막 읽는 중 · 최신 자막으로 이동")
                        }
                    }
                }
                if (!compactHeight) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RectangleShape) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onChooseApp, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Icon(Icons.Outlined.Apps, contentDescription = null, modifier = Modifier.size(20.dp))
                            Text("함께 볼 앱 선택", Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = onSplitHelp, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Outlined.HelpOutline, contentDescription = "분할 화면 안내")
                        }
                    }
                    }
                }
            }
        }
    }
    if (showMore) AlertDialog(
        onDismissRequest = { showMore = false },
        shape = RectangleShape,
        title = { Text("자막 보기") },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text("분할 화면", style = MaterialTheme.typography.labelLarge)
                if (!following && messages.isNotEmpty()) TextButton(
                    onClick = { readingView?.showLatest(); showMore = false }, modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("최신 자막으로 이동") }
                TextButton(onClick = { showMore = false; onChooseApp() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("함께 볼 앱 선택")
                }
                TextButton(onClick = { showMore = false; onSplitHelp() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("분할 화면 안내")
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("자막 표시", style = MaterialTheme.typography.labelLarge)
                Text("읽기 방식", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                CaptionReadOption("최근 자막", "최근 2개 구간", ui.settings.captionDisplayMode == CaptionDisplayMode.CAPTIONS) {
                    onDisplayModeChange(CaptionDisplayMode.CAPTIONS)
                }
                CaptionReadOption("자막 목록", "최대 40개 구간", ui.settings.captionDisplayMode == CaptionDisplayMode.CHAT) {
                    onDisplayModeChange(CaptionDisplayMode.CHAT)
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(value = ui.settings.showOriginal, role = Role.Switch,
                            onValueChange = onShowOriginalChange),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("원문 함께 보기", Modifier.weight(1f))
                    Switch(checked = ui.settings.showOriginal, onCheckedChange = null)
                }
                Text("글자 크기 · ${ui.settings.textSizeSp.toInt()}", Modifier.padding(top = 16.dp))
                Slider(value = ui.settings.textSizeSp.coerceIn(14f, 40f), onValueChange = onTextSizeChange,
                    valueRange = 14f..40f, modifier = Modifier.heightIn(min = 48.dp))
                TextButton(onClick = { showMore = false; showDetails = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("상태와 알림")
                }
                TextButton(onClick = { showMore = false; onSettings() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("언어 및 전체 설정")
                }
            }
        },
        confirmButton = { TextButton(onClick = { showMore = false }) { Text("닫기") } }
    )
    if (showDetails) AlertDialog(
        onDismissRequest = { showDetails = false }, shape = RectangleShape, title = { Text(status) },
        text = {
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text(notice.ifBlank { "음성 인식과 번역은 기기에서 처리됩니다. 함께 볼 앱을 선택해도 캡처 대상이 그 앱으로 제한되지는 않습니다." })
                if (suggestion != null) {
                    Text("${RecognitionLanguages.koreanLabel(suggestion)} 음성으로 전환할까요?", Modifier.padding(top = 16.dp))
                    TextButton(onClick = { onAcceptLanguage(suggestion); showDetails = false },
                        enabled = !stopping, modifier = Modifier.heightIn(min = 48.dp)) { Text("추천 언어로 전환") }
                    TextButton(onClick = { onDismissLanguage(); showDetails = false },
                        enabled = !stopping, modifier = Modifier.heightIn(min = 48.dp)) { Text("현재 언어 유지") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { showDetails = false }) { Text("닫기") } }
    )
}

@Composable
private fun CaptionReadOption(label: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(label)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
