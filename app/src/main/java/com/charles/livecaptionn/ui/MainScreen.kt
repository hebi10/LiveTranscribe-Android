package com.charles.livecaptionn.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.VerticalSplit
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.charles.livecaptionn.settings.AudioSource
import com.charles.livecaptionn.settings.AppThemeMode
import com.charles.livecaptionn.settings.CaptionPlacement
import com.charles.livecaptionn.settings.CaptionDisplayMode
import com.charles.livecaptionn.settings.RecognitionLanguages
import com.charles.livecaptionn.speech.RecognitionStatus
import com.charles.livecaptionn.translation.ModelSetupStage

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestAudioPermission: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onHistory: () -> Unit,
    onOpenCaptions: () -> Unit,
    onOpenCaptionTools: () -> Unit,
    onCancelModelPreparation: () -> Unit,
    startPending: Boolean = false,
    audioPermissionSettingsRequired: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val sessionBusy = ui.runtime.running || ui.runtime.stopping || startPending
    val setup = ui.setupState(startPending, audioPermissionSettingsRequired)
    val languageLabel = if (ui.settings.sourceLanguageCode == RecognitionLanguages.AUTO)
        "언어 자동 감지" else "${RecognitionLanguages.koreanLabel(ui.settings.sourceLanguageCode)} 고정"
    val inputLabel = if (ui.settings.audioSource == AudioSource.SYSTEM) "동영상 음성" else "마이크"
    val placementLabel = if (ui.settings.captionPlacement == CaptionPlacement.SPLIT_SCREEN)
        "분할 화면" else "팝업"
    val selectionLabel = "$placementLabel · $inputLabel · $languageLabel → 한국어"
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissionState()
                viewModel.refreshModelPreparationState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val action = ui.homeAction(startPending)
    var showAdvancedSettings by rememberSaveable { mutableStateOf(false) }
    val statusLabel = when {
        ui.runtime.stopping -> ui.runtime.phase.label
        startPending -> "권한 확인 및 시작 준비 중"
        ui.runtime.running || ui.runtime.status == RecognitionStatus.ERROR -> ui.runtime.phase.label
        ui.canStart -> "시작 가능"
        else -> "시작 준비 필요"
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Subtitles, contentDescription = null)
                        Text("실시간 영상 번역", fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    IconButton(onClick = onHistory) {
                        Icon(Icons.Outlined.History, contentDescription = "번역 기록")
                    }
                }
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RectangleShape
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (action.kind == HomeActionKind.START &&
                            ui.settings.audioSource == AudioSource.SYSTEM) {
                            Text("동영상 음성은 시작할 때마다 화면 공유 확인이 필요합니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (action.kind == HomeActionKind.STOP) {
                            OutlinedButton(
                                shape = RectangleShape,
                                onClick = onStop,
                                enabled = action.enabled,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            ) { Text(action.label) }
                        } else {
                            Button(
                                shape = RectangleShape,
                                onClick = onStart,
                                enabled = action.enabled,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            ) { Text(action.label) }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RectangleShape
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (!setup.isVisible) {
                        Text("기기 내 실시간 자막", style = MaterialTheme.typography.titleMedium)
                        Text(selectionLabel, fontWeight = FontWeight.Medium)
                        Text(statusLabel, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("선택한 번역 방식", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(selectionLabel, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium)
                        if (setup.modelStage == ModelSetupStage.NOT_DOWNLOADED &&
                            setup.downloadedBytes == 0L) {
                            Text("첫 사용 시 음성·번역 모델 약 2.1GB를 내려받아야 합니다. 준비 후에는 인터넷 없이 번역할 수 있습니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    ui.runtime.lastError?.takeIf { it.isNotBlank() }?.let {
                        AppStatusMessage(message = it, isError = true)
                    }
                    ui.runtime.captureHint?.takeIf {
                        ui.runtime.lastError.isNullOrBlank() && !ui.runtime.stopping &&
                            !ui.runtime.paused && it.isNotBlank()
                    }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    ui.runtime.restartNotice?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ui.runtime.information?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (setup.isVisible) {
                        StartSetupGuidance(
                            setup = setup,
                            onRequestAudioPermission = onRequestAudioPermission,
                            onOpenOverlaySettings = onOpenOverlaySettings,
                            onPrepareModels = viewModel::prepareSelectedTranslationModel,
                            onCancelModelPreparation = onCancelModelPreparation
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("화면 배치", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((placement, label) in listOf(
                        CaptionPlacement.SPLIT_SCREEN to "분할 화면",
                        CaptionPlacement.POPUP to "팝업"
                    )) {
                        val selected = ui.settings.captionPlacement == placement
                        val icon = if (placement == CaptionPlacement.SPLIT_SCREEN)
                            Icons.Outlined.VerticalSplit else Icons.Outlined.PictureInPictureAlt
                        val content: @Composable () -> Unit = {
                            Icon(icon, contentDescription = null)
                            Text(label, modifier = Modifier.padding(start = 8.dp))
                        }
                        if (selected) FilledTonalButton(
                            shape = RectangleShape,
                            onClick = { viewModel.updateCaptionPlacement(placement) },
                            enabled = !sessionBusy,
                            modifier = Modifier.heightIn(min = 48.dp).semantics { this.selected = true }
                        ) { content() }
                        else OutlinedButton(
                            shape = RectangleShape,
                            onClick = { viewModel.updateCaptionPlacement(placement) },
                            enabled = !sessionBusy,
                            modifier = Modifier.heightIn(min = 48.dp).semantics { this.selected = false }
                        ) { content() }
                    }
                }
                Text(if (ui.settings.captionPlacement == CaptionPlacement.SPLIT_SCREEN)
                    "영상 옆에 자막을 표시합니다. 화면 비율은 Android 구분선으로 조절합니다."
                    else "영상 위에 자막창을 표시하며 위치·크기를 조절할 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (sessionBusy) Text("화면 배치는 번역 종료 후 변경할 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (ui.settings.captionPlacement == CaptionPlacement.SPLIT_SCREEN) {
                    OutlinedButton(shape = RectangleShape, onClick = onOpenCaptions, enabled = !startPending,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (ui.runtime.running || ui.runtime.transcriptLines.isNotEmpty())
                            "자막 화면으로" else "자막 화면 미리 보기")
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("음성 입력", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val system = ui.settings.audioSource == AudioSource.SYSTEM
                    if (system) FilledTonalButton(
                        shape = RectangleShape,
                        onClick = { viewModel.selectAudioSource(AudioSource.SYSTEM) },
                        enabled = !sessionBusy,
                        modifier = Modifier.heightIn(min = 48.dp).semantics { selected = true }
                    ) { Text("동영상 음성") }
                    else OutlinedButton(
                        shape = RectangleShape,
                        onClick = { viewModel.selectAudioSource(AudioSource.SYSTEM) },
                        enabled = !sessionBusy,
                        modifier = Modifier.heightIn(min = 48.dp).semantics { selected = false }
                    ) { Text("동영상 음성") }
                    if (!system) FilledTonalButton(
                        shape = RectangleShape,
                        onClick = { viewModel.selectAudioSource(AudioSource.MIC) },
                        enabled = !sessionBusy,
                        modifier = Modifier.heightIn(min = 48.dp).semantics { selected = true }
                    ) { Text("마이크") }
                    else OutlinedButton(
                        shape = RectangleShape,
                        onClick = { viewModel.selectAudioSource(AudioSource.MIC) },
                        enabled = !sessionBusy,
                        modifier = Modifier.heightIn(min = 48.dp).semantics { selected = false }
                    ) { Text("마이크") }
                }
                Text(if (ui.settings.audioSource == AudioSource.SYSTEM)
                    "앱에서 캡처를 허용한 동영상 소리만 인식합니다."
                    else "마이크로 들어오는 주변 음성을 번역합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("음성 언어", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((code, label) in listOf(
                        RecognitionLanguages.AUTO to "자동 감지",
                        "ja" to "일본어", "en" to "영어"
                    )) {
                        val selected = ui.settings.sourceLanguageCode == code
                        if (selected) FilledTonalButton(
                            shape = RectangleShape,
                            onClick = { viewModel.selectSourceLanguage(code) },
                            enabled = !sessionBusy,
                            modifier = Modifier.heightIn(min = 48.dp).semantics { this.selected = true }
                        ) { Text(label) }
                        else OutlinedButton(
                            shape = RectangleShape,
                            onClick = { viewModel.selectSourceLanguage(code) },
                            enabled = !sessionBusy,
                            modifier = Modifier.heightIn(min = 48.dp).semantics { this.selected = false }
                        ) { Text(label) }
                    }
                }
                Text("번역 결과: 한국어", fontWeight = FontWeight.Medium)
                Text(
                    if (ui.settings.sourceLanguageCode == RecognitionLanguages.AUTO)
                        "음성을 구간별로 감지합니다. 짧거나 여러 언어가 섞인 발화는 정확하지 않을 수 있습니다."
                    else "선택한 언어로 인식합니다. 여러 언어가 섞이면 자동 감지를 선택하세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onOpenCaptionTools,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RectangleShape
                ) { Text("사용자 용어집 · 자막 프리셋") }
                Text("번역 용어를 바꾸거나 자막 글자 크기·언어 설정을 저장할 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("자막 및 앱 설정", style = MaterialTheme.typography.titleMedium)
                Text(
                    "자막 크기 · 원문 표시 · 팝업 · 화면 테마 · 기록 저장",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = { showAdvancedSettings = !showAdvancedSettings },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Text(if (showAdvancedSettings) "설정 접기" else "세부 설정 열기")
                    Icon(if (showAdvancedSettings) Icons.Outlined.ExpandLess
                        else Icons.Outlined.ExpandMore, contentDescription = null)
                }
            }

            if (showAdvancedSettings) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("자막 표시", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for ((mode, label) in listOf(
                            CaptionDisplayMode.CAPTIONS to "최근 자막",
                            CaptionDisplayMode.CHAT to "자막 목록"
                        )) {
                            val selected = ui.settings.captionDisplayMode == mode
                            if (selected) FilledTonalButton(
                                shape = RectangleShape,
                                onClick = { viewModel.updateCaptionDisplayMode(mode) },
                                modifier = Modifier.heightIn(min = 48.dp).semantics { this.selected = true }
                            ) { Text(label) }
                            else OutlinedButton(
                                shape = RectangleShape,
                                onClick = { viewModel.updateCaptionDisplayMode(mode) },
                                modifier = Modifier.heightIn(min = 48.dp).semantics { this.selected = false }
                            ) { Text(label) }
                        }
                    }
                    Text(if (ui.settings.captionDisplayMode == CaptionDisplayMode.CHAT)
                        "최근 40개 자막 구간을 모아 봅니다. 읽는 동안 자동 이동이 멈춥니다."
                        else "최근 2개 자막 구간을 표시합니다. 창 너비에 따라 줄바꿈됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("확정된 문장부터 자막을 표시합니다. 인식이 지연되면 일부 음성 구간이 생략될 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("글자 크기: " + ui.settings.textSizeSp.toInt() + "sp")
                    Slider(value = ui.settings.textSizeSp, onValueChange = viewModel::updateTextSize,
                        valueRange = 14f..40f)
                    SettingToggle("원문도 함께 표시", ui.settings.showOriginal,
                        viewModel::updateShowOriginal)
                    if (ui.settings.captionPlacement == CaptionPlacement.POPUP) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text("팝업 자막창", style = MaterialTheme.typography.titleMedium)
                        Text("팝업의 투명도를 조절하거나 저장된 위치와 크기를 초기화합니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("팝업 불투명도: " + (ui.settings.overlayOpacity * 100).toInt() + "%")
                        Slider(value = ui.settings.overlayOpacity, onValueChange = viewModel::updateOpacity,
                            valueRange = 0.2f..1f)
                        OutlinedButton(
                            shape = RectangleShape,
                            onClick = viewModel::resetOverlayPosition,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) { Text("팝업 위치·크기 초기화") }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("화면 테마", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for ((mode, label) in listOf(
                            AppThemeMode.SYSTEM to "시스템",
                            AppThemeMode.LIGHT to "라이트",
                            AppThemeMode.DARK to "다크"
                        )) {
                            val selected = ui.settings.appThemeMode == mode
                            if (selected) FilledTonalButton(
                                shape = RectangleShape,
                                onClick = { viewModel.updateAppThemeMode(mode) },
                                modifier = Modifier.heightIn(min = 48.dp).semantics { this.selected = true }
                            ) { Text(label) }
                            else OutlinedButton(
                                shape = RectangleShape,
                                onClick = { viewModel.updateAppThemeMode(mode) },
                                modifier = Modifier.heightIn(min = 48.dp).semantics { this.selected = false }
                            ) { Text(label) }
                        }
                    }
                    Text("시스템 모드는 기기의 밝기 설정에 따라 변경됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("기록 및 개인정보", style = MaterialTheme.typography.titleMedium)
                    SettingToggle("번역 기록 저장", ui.settings.saveHistory,
                        viewModel::updateSaveHistory)
                    Text("기록 저장 기본값은 꺼짐입니다. 저장을 꺼도 기존 기록은 남으며 기록 화면에서 삭제할 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(
                        onClick = onHistory,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RectangleShape
                    ) { Text("저장된 번역 기록 관리") }
                    Text("음성 인식과 번역은 기기 안에서 처리합니다. 광고·분석·오류 수집 SDK 및 Android 백업을 사용하지 않습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://hebi10.github.io/LiveTranscribe-Android/privacy-policy.html")))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, "웹 브라우저를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("개인정보처리방침 (외부 브라우저)") }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun StartSetupGuidance(
    setup: MainSetupState,
    onRequestAudioPermission: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onPrepareModels: () -> Unit,
    onCancelModelPreparation: () -> Unit
) {
    var showAllDetails by rememberSaveable(setup.isComplete) { mutableStateOf(false) }
    val visibleSteps = setup.visibleChecklistSteps(showAllDetails)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("시작 준비", style = MaterialTheme.typography.titleMedium)
            Text("${setup.completedSteps}/${setup.totalSteps} 완료",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!setup.isComplete) LinearProgressIndicator(
            progress = { setup.completedSteps.toFloat() / setup.totalSteps },
            modifier = Modifier.fillMaxWidth()
        )
        if (setup.isComplete) {
            Text("필수 권한과 모델 준비가 완료되었습니다. 아래에서 번역을 시작할 수 있습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(
                onClick = { showAllDetails = !showAllDetails },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(if (showAllDetails) "준비 항목 접기" else "준비 항목 확인")
                Icon(if (showAllDetails) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null)
            }
        } else {
            Text("${setup.remainingSteps}개 항목 남음 · 다음 준비 항목부터 진행하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (visibleSteps.isNotEmpty()) {
            val audioReady = setup.audioPermissionAction == AudioPermissionAction.GRANTED
            val audioNext = setup.nextStep == SetupStep.AUDIO_PERMISSION
            if (SetupStep.AUDIO_PERMISSION in visibleSteps) SetupChecklistItem(
                title = "1. 음성 캡처 권한",
                status = when {
                    audioReady -> "허용 완료"
                    setup.audioPermissionAction == AudioPermissionAction.OPEN_SETTINGS -> "앱 설정에서 재허용 필요"
                    else -> "허용 필요"
                },
                completed = audioReady,
                next = audioNext
            ) {
                if (audioNext) NextSetupAction(
                    setup, onRequestAudioPermission, onOpenOverlaySettings, onPrepareModels)
            }
            if (SetupStep.OVERLAY_PERMISSION in visibleSteps) {
                val overlayNext = setup.nextStep == SetupStep.OVERLAY_PERMISSION
                SetupChecklistItem(
                    title = "2. 다른 앱 위에 팝업 표시",
                    status = if (setup.overlayPermissionNeeded) "허용 필요" else "허용 완료",
                    completed = !setup.overlayPermissionNeeded,
                    next = overlayNext
                ) {
                    if (overlayNext) NextSetupAction(
                        setup, onRequestAudioPermission, onOpenOverlaySettings, onPrepareModels)
                }
            }
            val modelNext = setup.nextStep == SetupStep.MODELS
            if (SetupStep.MODELS in visibleSteps) SetupChecklistItem(
                title = "${setup.totalSteps}. 음성·번역 모델",
                status = setup.modelStatusLabel(),
                completed = setup.modelStage == ModelSetupStage.READY,
                next = modelNext,
                warning = setup.modelStage == ModelSetupStage.ERROR
            ) {
                if (setup.modelStage == ModelSetupStage.ERROR) {
                    Text(setup.modelMessage?.takeIf { it.isNotBlank() }
                        ?: "모델 준비에 실패했습니다. 연결과 저장 공간을 확인해 주세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                if (setup.modelStage == ModelSetupStage.CHECKING) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(requireNotNull(setup.modelStageGuidance()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (setup.modelStage != ModelSetupStage.READY &&
                    (setup.downloadedBytes > 0 || setup.canCancelModelPreparation)) {
                    ModelDownloadProgress(setup)
                }
                setup.activeModelFileLabel()?.let { name ->
                    Text("현재 파일: $name",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (modelNext) {
                    NextSetupAction(
                        setup, onRequestAudioPermission, onOpenOverlaySettings, onPrepareModels)
                }
                when (setup.modelStage) {
                    ModelSetupStage.READY, ModelSetupStage.DOWNLOADING -> {
                        Text(requireNotNull(setup.modelStageGuidance()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (setup.modelStage == ModelSetupStage.DOWNLOADING) OutlinedButton(
                            onClick = onCancelModelPreparation,
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = RectangleShape
                        ) { Text("다운로드 일시 중지") }
                    }
                    else -> Unit
                }
            }
        }
        if (!setup.isComplete) {
            TextButton(
                onClick = { showAllDetails = !showAllDetails },
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text(if (showAllDetails) "전체 준비 항목 접기" else "전체 준비 항목 보기")
                Icon(if (showAllDetails) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null)
            }
        }
    }
}

@Composable
private fun NextSetupAction(
    setup: MainSetupState,
    onRequestAudioPermission: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onPrepareModels: () -> Unit
) {
    when (setup.nextStep) {
        SetupStep.AUDIO_PERMISSION -> {
            Text(setup.audioPermissionGuidance(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = onRequestAudioPermission,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RectangleShape
            ) {
                Text(if (setup.audioPermissionAction == AudioPermissionAction.OPEN_SETTINGS)
                    "앱 설정에서 마이크 허용" else "음성 캡처 권한 허용")
            }
        }
        SetupStep.OVERLAY_PERMISSION -> {
            Text(setup.overlayPermissionGuidance(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = onOpenOverlaySettings,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RectangleShape
            ) { Text("자막 표시 권한 허용") }
        }
        SetupStep.MODELS -> {
            if (setup.canPrepareModels) {
                Text(setup.modelPreparationGuidance(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = onPrepareModels,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RectangleShape
                ) { Text(setup.modelActionLabel() ?: "모델 다운로드 (약 2.1GB)") }
            }
        }
        null -> Unit
    }
}

@Composable
private fun SetupChecklistItem(
    title: String,
    status: String,
    completed: Boolean,
    next: Boolean,
    warning: Boolean = false,
    content: @Composable () -> Unit = {}
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RectangleShape,
        color = if (next) MaterialTheme.colorScheme.surfaceContainerHigh
            else MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(if (completed) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null, modifier = Modifier.padding(top = 2.dp),
                tint = when {
                    warning -> MaterialTheme.colorScheme.error
                    completed || next -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                })
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
                    Text(title, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (next) FontWeight.SemiBold else FontWeight.Normal)
                    Text(status, style = MaterialTheme.typography.bodySmall,
                        color = if (warning) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                content()
            }
        }
    }
}

@Composable
private fun ModelDownloadProgress(setup: MainSetupState) {
    Text(setup.modelProgressSummary(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    val progress = setup.downloadProgress
    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth()
        )
        if (progress >= 1f && setup.modelStage != ModelSetupStage.READY) {
            Text("전송량이 100%여도 파일 검증이 끝나야 준비가 완료됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else if (setup.modelStage == ModelSetupStage.DOWNLOADING) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = null)
    }
}

