package com.charles.livecaptionn.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.charles.livecaptionn.settings.AudioSource
import com.charles.livecaptionn.speech.RecognitionStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onRequestAudioPermission: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermissionState()
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
                    Text(
                        "영상 음성과 번역 문장을 외부 서버로 전송하지 않습니다. " +
                            "캡처를 허용하는 영상 앱에서만 시스템 오디오를 인식할 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val statusText = when {
                        ui.runtime.running && ui.runtime.paused -> "일시정지"
                        ui.runtime.running -> "실시간 자막 실행 중"
                        ui.runtime.status == RecognitionStatus.ERROR -> "실행 오류"
                        ui.canStart -> "번역 준비 완료"
                        else -> "설정 확인 필요"
                    }
                    Text(statusText, fontWeight = FontWeight.SemiBold)
                    ui.runtime.lastError?.takeIf { it.isNotBlank() }?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onStart, enabled = !ui.runtime.running && ui.canStart,
                            modifier = Modifier.weight(1f)) { Text("번역 시작") }
                        OutlinedButton(onClick = onStop, enabled = ui.runtime.running,
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
                            modifier = Modifier.weight(1f)) { Text("동영상 음성") }
                        else OutlinedButton(onClick = { viewModel.selectAudioSource(AudioSource.SYSTEM) },
                            modifier = Modifier.weight(1f)) { Text("동영상 음성") }
                        if (!system) Button(onClick = { viewModel.selectAudioSource(AudioSource.MIC) },
                            modifier = Modifier.weight(1f)) { Text("마이크") }
                        else OutlinedButton(onClick = { viewModel.selectAudioSource(AudioSource.MIC) },
                            modifier = Modifier.weight(1f)) { Text("마이크") }
                    }
                    Text("번역 시작 시 Android에서 화면 캡처 권한을 요청합니다.",
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
                                modifier = Modifier.weight(1f)) { Text(label) }
                            else OutlinedButton(onClick = { viewModel.selectSourceLanguage(code) },
                                modifier = Modifier.weight(1f)) { Text(label) }
                        }
                    }
                    Text("번역 결과: 한국어", fontWeight = FontWeight.Medium)
                    Text(
                        if (ui.modelReady) "음성 인식 모델 준비 완료"
                        else "선택한 음성 모델이 없습니다. 모델 포함 APK가 필요합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (ui.modelReady) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error
                    )
                    Text(
                        "Google ML Kit 번역 모델은 최초 사용 시 Google Play 서비스가 다운로드할 수 있습니다. " +
                            "다운로드 후 문장 번역은 휴대전화 내에서 처리합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("자막 설정", style = MaterialTheme.typography.titleMedium)
                    Text("글자 크기: " + ui.settings.textSizeSp.toInt() + "sp")
                    Slider(value = ui.settings.textSizeSp, onValueChange = viewModel::updateTextSize,
                        valueRange = 14f..40f)
                    Text("배경 불투명도: " + (ui.settings.overlayOpacity * 100).toInt() + "%")
                    Slider(value = ui.settings.overlayOpacity, onValueChange = viewModel::updateOpacity,
                        valueRange = 0.2f..1f)
                    SettingToggle("원문도 함께 표시", ui.settings.showOriginal,
                        viewModel::updateShowOriginal)
                    Text("영상 위 자막 창은 드래그로 이동하고 크기를 조절할 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("권한 및 개인정보", style = MaterialTheme.typography.titleMedium)
                    Text(if (ui.overlayPermissionGranted) "화면 표시: 허용됨" else "화면 표시: 권한 필요")
                    if (!ui.overlayPermissionGranted) {
                        FilledTonalButton(onClick = onOpenOverlaySettings) {
                            Text("화면 표시 권한 허용")
                        }
                    }
                    Text(if (ui.micPermissionGranted) "음성 캡처: 허용됨" else "음성 캡처: 권한 필요")
                    if (!ui.micPermissionGranted) {
                        Text(
                            "동영상 내부 음성을 인식할 때도 Android의 마이크(녹음) 권한이 필요합니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FilledTonalButton(onClick = onRequestAudioPermission) {
                            Text("음성 캡처 권한 허용")
                        }
                    }
                    SettingToggle("번역 기록 저장 (기본 꺼짐)", ui.settings.saveHistory,
                        viewModel::updateSaveHistory)
                    Text(
                        "이 앱의 광고·별도 분석·오류 제보·원격 음성 인식은 제거했습니다. " +
                            "단, Google ML Kit은 기기·앱 정보와 번역 언어 등의 진단 데이터를 수집할 수 있습니다. " +
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
private fun SettingToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
