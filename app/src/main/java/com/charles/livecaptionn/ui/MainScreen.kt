package com.charles.livecaptionn.ui

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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
    onCancelModelPreparation: () -> Unit,
    startPending: Boolean = false,
    audioPermissionSettingsRequired: Boolean = false,
    modifier: Modifier = Modifier
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val sessionBusy = ui.runtime.running || ui.runtime.stopping || startPending
    val setup = ui.setupState(startPending, audioPermissionSettingsRequired)
    val languageLabel = if (ui.settings.sourceLanguageCode == RecognitionLanguages.AUTO)
        "언어 자동 감지" else "${RecognitionLanguages.koreanLabel(ui.settings.sourceLanguageCode)} 고정"
    val inputLabel = if (ui.settings.audioSource == AudioSource.SYSTEM) "동영상 음성" else "마이크"
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

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.Translate, contentDescription = null)
                        Text("실시간 영상 번역", fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    IconButton(onClick = onHistory) {
                        Icon(Icons.Filled.History, contentDescription = "번역 기록")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("화면 배치", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for ((placement, label) in listOf(
                            CaptionPlacement.SPLIT_SCREEN to "분할 화면",
                            CaptionPlacement.POPUP to "팝업"
                        )) {
                            val selected = ui.settings.captionPlacement == placement
                            val icon = if (placement == CaptionPlacement.SPLIT_SCREEN)
                                Icons.Filled.VerticalSplit else Icons.Filled.PictureInPictureAlt
                            val content: @Composable () -> Unit = {
                                Icon(icon, contentDescription = null)
                                Text(label, modifier = Modifier.padding(start = 8.dp))
                            }
                            if (selected) FilledTonalButton(
                                onClick = { viewModel.updateCaptionPlacement(placement) },
                                enabled = !sessionBusy,
                                modifier = Modifier.semantics { this.selected = true }
                            ) { content() }
                            else OutlinedButton(
                                onClick = { viewModel.updateCaptionPlacement(placement) },
                                enabled = !sessionBusy,
                                modifier = Modifier.semantics { this.selected = false }
                            ) { content() }
                        }
                    }
                    Text(if (ui.settings.captionPlacement == CaptionPlacement.SPLIT_SCREEN)
                        "영상 앱과 화면을 나눠 자막을 봅니다. 화면 비율은 Android의 구분선으로 조절합니다."
                        else "영상 위에 작은 자막창을 띄웁니다. 창을 옮기거나 크기를 조절할 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (sessionBusy) Text("화면 배치는 번역을 종료한 뒤 변경할 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (ui.settings.captionPlacement == CaptionPlacement.SPLIT_SCREEN) {
                        OutlinedButton(onClick = onOpenCaptions,
                            enabled = !startPending, modifier = Modifier.fillMaxWidth()) {
                            Text(if (ui.runtime.running || ui.runtime.transcriptLines.isNotEmpty())
                                "자막 화면으로" else "자막 화면 미리 보기")
                        }
                    }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("기기 내 실시간 자막", style = MaterialTheme.typography.titleMedium)
                    Text("$languageLabel → 한국어 · $inputLabel", fontWeight = FontWeight.Medium)
                    val statusText = when {
                        ui.runtime.stopping -> ui.runtime.phase.label
                        startPending -> "권한 확인 및 시작 준비 중"
                        ui.runtime.running || ui.runtime.status == RecognitionStatus.ERROR -> ui.runtime.phase.label
                        else -> null
                    }
                    statusText?.let { Text(it, fontWeight = FontWeight.SemiBold) }
                    ui.runtime.lastError?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    ui.runtime.captureHint?.takeIf { ui.runtime.lastError.isNullOrBlank() && !ui.runtime.stopping && !ui.runtime.paused && it.isNotBlank() }?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
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
                    if (sessionBusy) {
                        OutlinedButton(onClick = onStop, enabled = !ui.runtime.stopping,
                            modifier = Modifier.fillMaxWidth()) {
                            Text(if (ui.runtime.stopping) "종료 중" else if (startPending) "요청 취소" else "번역 중지")
                        }
                    } else if (ui.canStart) {
                        if (ui.settings.audioSource == AudioSource.SYSTEM) {
                            Text("시작할 때마다 Android의 화면 공유 확인에서 허용해 주세요. " +
                                "캡처를 허용하는 영상 앱의 소리만 인식합니다.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                            Text(if (ui.runtime.restartLanguageCode != null) "번역 다시 시작" else if (ui.settings.captionPlacement == CaptionPlacement.SPLIT_SCREEN) "분할 자막 시작" else "팝업 자막 시작")
                        }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("음성 입력", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val system = ui.settings.audioSource == AudioSource.SYSTEM
                        if (system) Button(onClick = { viewModel.selectAudioSource(AudioSource.SYSTEM) },
                            enabled = !sessionBusy, modifier = Modifier.semantics { selected = true }) { Text("동영상 음성") }
                        else OutlinedButton(onClick = { viewModel.selectAudioSource(AudioSource.SYSTEM) },
                            enabled = !sessionBusy, modifier = Modifier.semantics { selected = false }) { Text("동영상 음성") }
                        if (!system) Button(onClick = { viewModel.selectAudioSource(AudioSource.MIC) },
                            enabled = !sessionBusy, modifier = Modifier.semantics { selected = true }) { Text("마이크") }
                        else OutlinedButton(onClick = { viewModel.selectAudioSource(AudioSource.MIC) },
                            enabled = !sessionBusy, modifier = Modifier.semantics { selected = false }) { Text("마이크") }
                    }
                    Text(if (ui.settings.audioSource == AudioSource.SYSTEM)
                        "동영상 앱의 소리를 번역합니다. 화면 공유는 시작할 때마다 확인합니다."
                        else "마이크로 들리는 주변 음성을 번역합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("음성 언어", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for ((code, label) in listOf(RecognitionLanguages.AUTO to "자동 감지", "ja" to "일본어", "en" to "영어")) {
                            val selected = ui.settings.sourceLanguageCode == code
                            if (selected) Button(onClick = { viewModel.selectSourceLanguage(code) },
                                enabled = !sessionBusy, modifier = Modifier.semantics { this.selected = true }) { Text(label) }
                            else OutlinedButton(onClick = { viewModel.selectSourceLanguage(code) },
                                enabled = !sessionBusy, modifier = Modifier.semantics { this.selected = false }) { Text(label) }
                        }
                    }
                    Text("번역 결과: 한국어", fontWeight = FontWeight.Medium)
                    Text(
                        if (ui.settings.sourceLanguageCode == RecognitionLanguages.AUTO)
                            "영어·일본어 등 지원 언어를 구간마다 감지해 한국어로 번역합니다. " +
                                "짧은 말이나 여러 언어가 섞인 음성은 감지가 어려울 수 있습니다."
                        else "${RecognitionLanguages.koreanLabel(ui.settings.sourceLanguageCode)}로 고정해 인식합니다. " +
                            "여러 언어를 들으려면 자동 감지를 선택하세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("모든 모드는 같은 모델을 사용하므로 추가 다운로드가 없습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("읽기 방식", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for ((mode, label) in listOf(CaptionDisplayMode.CAPTIONS to "최근 자막", CaptionDisplayMode.CHAT to "자막 목록")) {
                            val selected = ui.settings.captionDisplayMode == mode
                            if (selected) {
                                FilledTonalButton(onClick = { viewModel.updateCaptionDisplayMode(mode) },
                                    modifier = Modifier.semantics { this.selected = true }) { Text(label) }
                            } else {
                                OutlinedButton(onClick = { viewModel.updateCaptionDisplayMode(mode) },
                                    modifier = Modifier.semantics { this.selected = false }) { Text(label) }
                            }
                        }
                    }
                    Text(
                        if (ui.settings.captionDisplayMode == CaptionDisplayMode.CHAT)
                            "최근 40개 자막 구간을 모아 봅니다. 이전 자막을 읽을 때 자동 이동이 멈춥니다."
                        else "최근 2개 자막 구간을 표시합니다. 화면 너비에 맞춰 줄바꿈됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("말이 이어져도 확인된 문장부터 번역합니다. " +
                        "문장 끝을 판단하기 어려우면 무음 또는 최대 8초 경계에서 처리하며, 속도는 기기에 따라 달라집니다. " +
                        "인식이나 번역이 밀리면 일부 대기 구간을 생략하고 안내합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("글자 크기: " + ui.settings.textSizeSp.toInt() + "sp")
                    Slider(value = ui.settings.textSizeSp, onValueChange = viewModel::updateTextSize,
                        valueRange = 14f..40f)
                    SettingToggle("원문도 함께 표시", ui.settings.showOriginal,
                        viewModel::updateShowOriginal)
                    if (ui.settings.captionPlacement == CaptionPlacement.POPUP) {
                        Text("팝업 불투명도: " + (ui.settings.overlayOpacity * 100).toInt() + "%")
                        Slider(value = ui.settings.overlayOpacity, onValueChange = viewModel::updateOpacity,
                            valueRange = 0.2f..1f)
                        OutlinedButton(onClick = viewModel::resetOverlayPosition) { Text("팝업 위치·크기 초기화") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("기록 및 개인정보", style = MaterialTheme.typography.titleMedium)
                    SettingToggle("번역 기록 저장 (기본 꺼짐)", ui.settings.saveHistory,
                        viewModel::updateSaveHistory)
                    Text(
                        "음성 인식과 번역은 기기 안에서 처리하며, 음성과 번역 문장을 외부 서버로 전송하지 않습니다. " +
                            "광고·분석·오류 제보용 SDK를 사용하지 않습니다. " +
                            "기록 저장은 기본 꺼짐이며 Android 백업은 사용하지 않습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
    var showCompletedDetails by rememberSaveable(setup.isComplete) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (setup.isComplete) "시작 준비 완료 · ${setup.totalSteps}/${setup.totalSteps}"
            else "시작 준비 · ${setup.completedSteps}/${setup.totalSteps} 완료", fontWeight = FontWeight.SemiBold)
        if (setup.isComplete) {
            TextButton(onClick = { showCompletedDetails = !showCompletedDetails }) {
                Text(if (showCompletedDetails) "준비 항목 접기" else "준비 항목 확인")
                Icon(if (showCompletedDetails) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null)
            }
        } else {
            Text("${setup.remainingSteps}개 남았어요. 다음 항목부터 준비해 주세요.",
                style = MaterialTheme.typography.bodyMedium)
            NextSetupAction(setup, onRequestAudioPermission, onOpenOverlaySettings, onPrepareModels)
        }
        if (!setup.isComplete || showCompletedDetails) {
            val audioReady = setup.audioPermissionAction == AudioPermissionAction.GRANTED
            val settingsRequired = setup.audioPermissionAction == AudioPermissionAction.OPEN_SETTINGS
            SetupChecklistItem(
                title = "1. 음성 캡처 권한",
                status = if (audioReady) "완료" else if (settingsRequired) "앱 설정에서 허용 필요" else "허용 필요",
                completed = audioReady,
                next = setup.nextStep == SetupStep.AUDIO_PERMISSION
            )
            if (setup.overlayPermissionRequired) SetupChecklistItem(
                title = "2. 다른 앱 위에 팝업 표시",
                status = if (setup.overlayPermissionNeeded) "허용 필요" else "완료",
                completed = !setup.overlayPermissionNeeded,
                next = setup.nextStep == SetupStep.OVERLAY_PERMISSION
            )
            val stage = setup.modelStage
            SetupChecklistItem(
                title = "${setup.totalSteps}. 음성·번역 모델",
                status = when (stage) {
                    ModelSetupStage.READY -> "완료"
                    ModelSetupStage.CHECKING -> "다운로드한 파일 확인 중"
                    ModelSetupStage.DOWNLOADING -> "다운로드 중"
                    ModelSetupStage.ERROR -> "다시 시도 필요"
                    else -> if (setup.canResumeModelPreparation) "다운로드 이어받기 필요" else "다운로드 필요 · 약 2.1GB"
                },
                completed = stage == ModelSetupStage.READY,
                next = setup.nextStep == SetupStep.MODELS
            ) {
                if (stage == ModelSetupStage.ERROR) {
                    Text(setup.modelMessage ?: "모델 준비에 실패했습니다. 다시 시도해 주세요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (stage == ModelSetupStage.CHECKING) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else if (stage != ModelSetupStage.READY &&
                    (setup.downloadedBytes > 0 || setup.canCancelModelPreparation)) {
                    ModelDownloadProgress(setup)
                }
                if (stage == ModelSetupStage.READY) {
                    Text("자동 감지·일본어·영어에서 같은 모델을 함께 사용합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (setup.canCancelModelPreparation) {
                    Text("다운로드 중에는 앱을 열어 두세요. 중단해도 이어받을 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (setup.canCancelModelPreparation) {
                    OutlinedButton(onClick = onCancelModelPreparation) { Text("다운로드 일시 중지") }
                }
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
            Text(if (setup.audioPermissionAction == AudioPermissionAction.OPEN_SETTINGS)
                "앱 설정 → 권한 → 마이크에서 허용해 주세요."
                else "동영상 소리 인식에도 Android의 마이크(녹음) 권한이 필요합니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onRequestAudioPermission, modifier = Modifier.fillMaxWidth()) {
                Text(if (setup.audioPermissionAction == AudioPermissionAction.OPEN_SETTINGS)
                    "앱 설정에서 마이크 허용" else "음성 캡처 권한 허용")
            }
        }
        SetupStep.OVERLAY_PERMISSION -> {
            Text("설정 화면에서 이 앱의 ‘다른 앱 위에 표시’를 켜 주세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onOpenOverlaySettings, modifier = Modifier.fillMaxWidth()) {
                Text("자막 표시 권한 허용")
            }
        }
        SetupStep.MODELS -> if (setup.canPrepareModels) {
            Text("최초 1회 약 2.1GB · Wi-Fi·모바일 데이터 모두 가능\n" +
                "모바일 데이터 요금이 발생할 수 있습니다. 다운로드 중에는 앱을 열어 두세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onPrepareModels, modifier = Modifier.fillMaxWidth()) {
                Text(when {
                    setup.canResumeModelPreparation -> "모델 다운로드 이어받기"
                    setup.modelStage == ModelSetupStage.ERROR -> "모델 다운로드 다시 시도"
                    else -> "모델 다운로드 (약 2.1GB)"
                })
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
    content: @Composable () -> Unit = {}
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(if (completed) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null, modifier = Modifier.padding(top = 2.dp),
            tint = if (completed || next) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
                Text(title, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (next) FontWeight.SemiBold else FontWeight.Normal)
                Text(status, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
private fun ModelDownloadProgress(setup: MainSetupState) {
    val downloadedMb = setup.downloadedBytes / 1_000_000
    val totalMb = setup.totalBytes / 1_000_000
    Text(if (totalMb > 0) "$downloadedMb / $totalMb MB 다운로드" else "$downloadedMb MB 다운로드",
        style = MaterialTheme.typography.bodySmall)
    val progress = setup.downloadProgress
    if (progress != null) {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        if (progress >= 1f && setup.canCancelModelPreparation) {
            Text("파일 확인을 마치면 준비가 완료됩니다.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else if (setup.canCancelModelPreparation) {
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

