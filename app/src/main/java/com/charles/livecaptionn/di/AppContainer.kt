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
import com.charles.livecaptionn.service.CaptionRuntimeStore
import com.charles.livecaptionn.speech.LocalVoskSttClient
import com.charles.livecaptionn.speech.VoskModelRegistry
import com.charles.livecaptionn.translation.MlKitTranslationRepository
import com.charles.livecaptionn.translation.TranslationRepository
import com.charles.livecaptionn.translation.TranslationModelSetup
import com.charles.livecaptionn.speech.OfflineSpokenLanguageIdentifier

class AppContainer(context: Context) {
    val settingsRepository: SettingsRepository = SettingsDataStore(context.applicationContext)
    val runtimeStore = CaptionRuntimeStore()
    private val mlKitTranslator = MlKitTranslationRepository()
    val translationRepository: TranslationRepository = mlKitTranslator
    val translationModels = TranslationModelSetup(mlKitTranslator)
    val languageIdentifier = OfflineSpokenLanguageIdentifier(context.applicationContext)
    val transcriptHistory = TranscriptHistoryStore(context.applicationContext)
    val captionProfiles: CaptionProfileRepository = FileCaptionProfileRepository(context.applicationContext)
    val glossary: GlossaryRepository = FileGlossaryRepository(context.applicationContext)
    val voskRegistry = VoskModelRegistry(context.applicationContext)
    val localVoskClient = LocalVoskSttClient(voskRegistry)

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

