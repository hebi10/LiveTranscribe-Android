package com.charles.livecaptionn.di

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.charles.livecaptionn.data.FileGlossaryRepository
import com.charles.livecaptionn.data.FileCaptionProfileRepository
import com.charles.livecaptionn.data.GlossaryRepository
import com.charles.livecaptionn.data.CaptionProfileRepository
import com.charles.livecaptionn.data.SettingsDataStore
import com.charles.livecaptionn.data.SettingsRepository
import com.charles.livecaptionn.data.TranscriptHistoryStore
import com.charles.livecaptionn.data.HistoryRecordingGate
import kotlinx.coroutines.flow.first
import com.charles.livecaptionn.service.CaptionRuntimeStore
import com.charles.livecaptionn.speech.LocalQwenSttClient
import com.charles.livecaptionn.models.OfflineModelStore
import com.charles.livecaptionn.translation.LocalTranslationRepository
import com.charles.livecaptionn.translation.TranslationRepository
import com.charles.livecaptionn.translation.TranslationModelSetup
import com.charles.livecaptionn.speech.OfflineSpokenLanguageIdentifier
import java.io.File

class AppContainer(context: Context) {
    val settingsRepository: SettingsRepository = SettingsDataStore(context.applicationContext)
    val runtimeStore = CaptionRuntimeStore()
    val offlineModels = OfflineModelStore(File(context.noBackupFilesDir, "ai-models"))
    val translationRepository: TranslationRepository = LocalTranslationRepository {
        offlineModels.requireReadyFile("hymt_model")
    }
    val translationModels = TranslationModelSetup(offlineModels)
    val languageIdentifier = OfflineSpokenLanguageIdentifier(context.applicationContext)
    val transcriptHistory = TranscriptHistoryStore(context.applicationContext)
    val historyRecordingGate = HistoryRecordingGate(
        readEnabled = { settingsRepository.settingsFlow.first().saveHistory },
        persistEnabled = { enabled -> settingsRepository.update { it.copy(saveHistory = enabled) } }
    )
    val captionProfiles: CaptionProfileRepository = FileCaptionProfileRepository(context.applicationContext)
    val glossary: GlossaryRepository = FileGlossaryRepository(context.applicationContext)
    val localQwenClient = LocalQwenSttClient(context.applicationContext,
        File(offlineModels.rootDirectory, "qwen3-asr"))

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                transcriptHistory.getAll()
            } catch (_: Exception) {
                runtimeStore.update { it.copy(lastError = "기존 번역 기록을 정리하지 못했습니다. 기록 화면에서 다시 확인해 주세요.") }
            }
        }
    }
}

