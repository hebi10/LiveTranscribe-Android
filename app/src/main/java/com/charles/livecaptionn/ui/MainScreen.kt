package com.charles.livecaptionn.ui

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Translate
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
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
import com.charles.livecaptionn.settings.CaptionDisplayMode
import com.charles.livecaptionn.speech.RecognitionStatus
import com.charles.livecaptionn.translation.ModelSetupStage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestAudioPermission: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onHistory: () -> Unit,
    onCancelModelPreparation: () -> Unit,
    startPending: Boolean = false,
    audioPermissionSettingsRequired: Boolean = false,
    modifier: Modifier = Modifier
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val sessionBusy = ui.runtime.running || ui.runtime.stopping || startPending
    val setup = ui.setupState(startPending, audioPermissionSettingsRequired)
    val languageLabel = if (ui.settings.sourceLanguageCode == "ja") "일본어" else "영어"
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
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("기기 내 실시간 자막", style = MaterialTheme.typography.titleMedium)
                    Text("$languageLabel → 한국어 · $inputLabel", fontWeight = FontWeight.Medium)
                    Text(
                        "영상 음성과 번역 문장을 외부 서버로 전송하지 않습니다. " +
                            "캡처를 허용하는 영상 앱에서만 시스템 오디오를 인식할 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val statusText = when {
                        ui.runtime.stopping -> ui.runtime.phase.label
                        startPending -> "권한 확인 및 시작 준비 중"
                        ui.runtime.running || ui.runtime.status == RecognitionStatus.ERROR -> ui.runtime.phase.label
                        ui.canStart -> "번역 준비 완료"
                        else -> "설정 확인 필요"
                    }
                    Text(statusText, fontWeight = FontWeight.SemiBold)
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onStart, enabled = !sessionBusy && ui.canStart,
                            modifier = Modifier.weight(1f)) {
                            Text(if (ui.runtime.restartLanguageCode != null) "번역 다시 시작" else "번역 시작")
                        }
                        OutlinedButton(onClick = onStop, enabled = sessionBusy && !ui.runtime.stopping,
                            modifier = Modifier.weight(1f)) { Text("중지") }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("음성 입력", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val system = ui.settings.audioSource == AudioSource.SYSTEM
                        if (system) Button(onClick = { viewModel.selectAudioSource(AudioSource.SYSTEM) },
                            enabled = !sessionBusy, modifier = Modifier.weight(1f)) { Text("동영상 음성") }
                        else OutlinedButton(onClick = { viewModel.selectAudioSource(AudioSource.SYSTEM) },
                            enabled = !sessionBusy, modifier = Modifier.weight(1f)) { Text("동영상 음성") }
                        if (!system) Button(onClick = { viewModel.selectAudioSource(AudioSource.MIC) },
                            enabled = !sessionBusy, modifier = Modifier.weight(1f)) { Text("마이크") }
                        else OutlinedButton(onClick = { viewModel.selectAudioSource(AudioSource.MIC) },
                            enabled = !sessionBusy, modifier = Modifier.weight(1f)) { Text("마이크") }
                    }
                    Text(if (ui.settings.audioSource == AudioSource.SYSTEM)
                        "번역 시작 시 Android에서 화면 캡처 권한을 요청합니다."
                        else "마이크로 들리는 주변 음성을 번역합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("번역 언어", style = MaterialTheme.typography.titleMedium)
                    Text("음성 언어 (영어·일본어)")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((code, label) in listOf("en" to "영어", "ja" to "일본어")) {
                            val selected = ui.settings.sourceLanguageCode == code
                            if (selected) Button(onClick = { viewModel.selectSourceLanguage(code) },
                                enabled = !sessionBusy, modifier = Modifier.weight(1f)) { Text(label) }
                            else OutlinedButton(onClick = { viewModel.selectSourceLanguage(code) },
                                enabled = !sessionBusy, modifier = Modifier.weight(1f)) { Text(label) }
                        }
                    }
                    Text("번역 결과: 한국어", fontWeight = FontWeight.Medium)
                    Text(
                        if (ui.modelReady) "Qwen3-ASR 0.6B 음성 모델 준비 완료"
                        else "음성·번역 모델 다운로드가 필요합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ui.modelReady) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error
                    )
                    if (ui.translationReady) {
                        Text("HY-MT2-1.8B Q4_K_M 한국어 번역 모델 준비 완료", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("한 번 받은 모델을 영어·일본어에서 함께 사용합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "언어가 다르면 서로 다른 음성 구간 2개를 독립적으로 분석한 뒤 " +
                            "영어·일본어 전환을 추천합니다. 언어는 자동 변경되지 않습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("자막 설정", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((mode, label) in listOf(CaptionDisplayMode.CAPTIONS to "자막형", CaptionDisplayMode.CHAT to "대화형")) {
                            val selected = ui.settings.captionDisplayMode == mode
                            if (selected) {
                                FilledTonalButton(onClick = { viewModel.updateCaptionDisplayMode(mode) },
                                    modifier = Modifier.weight(1f).semantics { this.selected = true }) { Text(label) }
                            } else {
                                OutlinedButton(onClick = { viewModel.updateCaptionDisplayMode(mode) },
                                    modifier = Modifier.weight(1f).semantics { this.selected = false }) { Text(label) }
                            }
                        }
                    }
                    Text(
                        if (ui.settings.captionDisplayMode == CaptionDisplayMode.CHAT)
                            "최근 40개 자막을 대화처럼 모아 봅니다. 이전 자막을 읽을 때 자동 스크롤이 멈춥니다."
                        else "최근 2개 자막을 표시합니다. 자막 창 너비에 맞춰 줄바꿈됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("음성 구간이 끝나거나 최대 8초 단위로 인식한 뒤 번역합니다. " +
                        "인식 중인 미리보기는 제공하지 않으며, 처리 시간은 기기에 따라 달라집니다. " +
                        "인식이나 번역이 밀리면 일부 대기 구간을 생략하고 안내합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("글자 크기: " + ui.settings.textSizeSp.toInt() + "sp")
                    Slider(value = ui.settings.textSizeSp, onValueChange = viewModel::updateTextSize,
                        valueRange = 14f..40f)
                    Text("배경 불투명도: " + (ui.settings.overlayOpacity * 100).toInt() + "%")
                    Slider(value = ui.settings.overlayOpacity, onValueChange = viewModel::updateOpacity,
                        valueRange = 0.2f..1f)
                    SettingToggle("원문도 함께 표시", ui.settings.showOriginal,
                        viewModel::updateShowOriginal)
                    OutlinedButton(onClick = viewModel::resetOverlayPosition) { Text("자막 위치·크기 초기화") }
                    Text("영상 위 자막 창은 드래그로 이동하고 크기를 조절할 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("권한 및 개인정보", style = MaterialTheme.typography.titleMedium)
                    Text(if (ui.overlayPermissionGranted) "화면 표시: 허용됨" else "화면 표시: 권한 필요")
                    Text(if (ui.micPermissionGranted) "음성 캡처: 허용됨" else "음성 캡처: 권한 필요")
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
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("시작 전 준비", fontWeight = FontWeight.SemiBold)
        if (setup.audioPermissionAction != AudioPermissionAction.GRANTED) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("음성 캡처: 권한 필요")
                val settingsRequired = setup.audioPermissionAction == AudioPermissionAction.OPEN_SETTINGS
                Text(
                    if (settingsRequired)
                        "권한 요청이 차단되었습니다. 앱 설정의 권한에서 마이크를 허용해 주세요."
                    else "동영상 음성을 인식할 때도 Android의 마이크(녹음) 권한이 필요합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilledTonalButton(onClick = onRequestAudioPermission) {
                    Text(if (settingsRequired) "앱 설정에서 권한 허용" else "음성 캡처 권한 허용")
                }
            }
        }
        if (setup.overlayPermissionNeeded) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("영상 위 자막 표시: 권한 필요")
                FilledTonalButton(onClick = onOpenOverlaySettings) { Text("화면 표시 권한 허용") }
            }
        }
        setup.modelStage?.let { stage ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val preparationStatus = when (stage) {
                    ModelSetupStage.CHECKING -> "음성·번역 모델 확인 중"
                    ModelSetupStage.NOT_DOWNLOADED -> if (setup.canResumeModelPreparation)
                        "모델 다운로드를 이어서 진행할 수 있습니다." else "음성·번역 모델 준비 필요"
                    ModelSetupStage.DOWNLOADING -> "음성·번역 모델 다운로드 중"
                    ModelSetupStage.READY -> "음성·번역 모델 준비 완료"
                    ModelSetupStage.ERROR -> setup.modelMessage ?: "모델 준비에 실패했습니다. 다시 시도해 주세요."
                }
                Text(preparationStatus, style = MaterialTheme.typography.bodyMedium,
                    color = if (stage == ModelSetupStage.ERROR) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Qwen3-ASR 0.6B (음성 인식) · HY-MT2-1.8B Q4_K_M (한국어 번역)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (setup.canPrepareModels || setup.canCancelModelPreparation) {
                    Text(
                        "전체 다운로드는 약 2.1GB이며 영어·일본어가 같은 모델을 사용합니다. " +
                            "모델 다운로드 버튼을 누르면 Wi-Fi 또는 모바일 데이터로 내려받습니다. " +
                            "모바일 데이터 사용 시 요금이 발생할 수 있습니다. " +
                            "다운로드 중에는 앱을 화면에 열어 두세요. 취소하거나 중단되어도 다음에 이어받을 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (setup.downloadedBytes > 0 || setup.canCancelModelPreparation) {
                    val downloadedMb = setup.downloadedBytes / 1_000_000
                    val totalMb = setup.totalBytes / 1_000_000
                    Text(if (totalMb > 0) "$downloadedMb / $totalMb MB 다운로드"
                        else "$downloadedMb MB 다운로드",
                        style = MaterialTheme.typography.bodySmall)
                    val progress = setup.downloadProgress
                    if (progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    } else if (setup.canCancelModelPreparation) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                if (setup.canCancelModelPreparation) {
                    OutlinedButton(onClick = onCancelModelPreparation) { Text("다운로드 취소") }
                }
                if (setup.canPrepareModels) {
                    FilledTonalButton(onClick = onPrepareModels) {
                        Text(when {
                            setup.canResumeModelPreparation -> "모델 다운로드 이어받기"
                            stage == ModelSetupStage.ERROR -> "모델 다운로드 다시 시도"
                            else -> "모델 다운로드 (약 2.1GB)"
                        })
                    }
                }
            }
        }
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

