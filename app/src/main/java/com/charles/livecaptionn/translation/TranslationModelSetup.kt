package com.charles.livecaptionn.translation

import com.charles.livecaptionn.models.OfflineModelStore
import com.charles.livecaptionn.models.OfflineModelState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ModelSetupStage { CHECKING, NOT_DOWNLOADED, DOWNLOADING, READY, ERROR }

data class TranslationModelState(
    val sourceCode: String = "en",
    val stage: ModelSetupStage = ModelSetupStage.CHECKING,
    val message: String? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val currentFile: String? = null
)

/** UI compatibility facade. English and Japanese require the same local speech/translation files. */
class TranslationModelSetup(
    private val models: OfflineModelStore,
    observerScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    @Volatile private var selectedSource = "en"
    private val mutable = MutableStateFlow(asTranslationState(models.state.value))
    val state: StateFlow<TranslationModelState> = mutable.asStateFlow()

    init {
        // AppContainer owns this facade for the process lifetime. Observation performs no I/O.
        observerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            models.state.collect { mutable.value = asTranslationState(it) }
        }
    }

    fun isReady(sourceCode: String): Boolean = sourceCode in SUPPORTED_SOURCES && models.isReady()

    suspend fun refresh(sourceCode: String) {
        if (!selectSource(sourceCode)) return
        try { models.refresh() } finally { mutable.value = asTranslationState(models.state.value) }
    }

    suspend fun prepare(sourceCode: String) {
        if (!selectSource(sourceCode)) return
        try { models.prepare() } finally { mutable.value = asTranslationState(models.state.value) }
    }

    fun cancel() = models.cancel()

    private fun selectSource(sourceCode: String): Boolean {
        selectedSource = sourceCode
        if (sourceCode !in SUPPORTED_SOURCES) {
            mutable.value = TranslationModelState(sourceCode, ModelSetupStage.ERROR, "지원하지 않는 원문 언어입니다.")
            return false
        }
        mutable.value = asTranslationState(models.state.value)
        return true
    }

    private fun asTranslationState(model: OfflineModelState) = TranslationModelState(
        sourceCode = selectedSource,
        stage = ModelSetupStage.valueOf(model.stage.name),
        message = model.message,
        downloadedBytes = model.downloadedBytes,
        totalBytes = model.totalBytes,
        currentFile = model.currentFile
    )

    companion object { private val SUPPORTED_SOURCES = setOf("en", "ja") }
}
