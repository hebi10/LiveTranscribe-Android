package com.charles.livecaptionn.translation

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ModelSetupStage { CHECKING, NOT_DOWNLOADED, DOWNLOADING, READY, ERROR }

data class TranslationModelState(
    val sourceCode: String = "en",
    val stage: ModelSetupStage = ModelSetupStage.CHECKING,
    val message: String? = null
)

/** No automatic downloads. The user explicitly starts ML Kit model preparation. */
class TranslationModelSetup(private val translator: MlKitTranslationRepository) {
    private val mutex = Mutex()
    private val manager = RemoteModelManager.getInstance()
    private val mutable = MutableStateFlow(TranslationModelState())
    val state: StateFlow<TranslationModelState> = mutable.asStateFlow()

    private val prepared = mutableSetOf<String>()

    fun isReady(sourceCode: String): Boolean =
        sourceCode in prepared || (
            mutable.value.sourceCode == sourceCode &&
                mutable.value.stage == ModelSetupStage.READY
        )

    suspend fun refresh(sourceCode: String) = mutex.withLock {
        if (sourceCode in prepared) {
            mutable.value = TranslationModelState(sourceCode, ModelSetupStage.READY)
            return@withLock
        }
        mutable.value = TranslationModelState(sourceCode, ModelSetupStage.CHECKING)
        try {
            val downloaded = withTimeoutOrNull(6_000L) {
                isDownloaded(sourceCode) && isDownloaded("ko")
            } ?: false
            if (downloaded) {
                prepared.add(sourceCode)
                mutable.value = TranslationModelState(sourceCode, ModelSetupStage.READY)
            } else {
                mutable.value = TranslationModelState(sourceCode, ModelSetupStage.NOT_DOWNLOADED)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutable.value = TranslationModelState(sourceCode, ModelSetupStage.NOT_DOWNLOADED)
        }
    }

    suspend fun prepare(sourceCode: String) = mutex.withLock {
        if (sourceCode in prepared) {
            mutable.value = TranslationModelState(sourceCode, ModelSetupStage.READY)
            return@withLock
        }
        mutable.value = TranslationModelState(sourceCode, ModelSetupStage.DOWNLOADING)
        try {
            withTimeout(90_000L) { translator.requireModel(sourceCode, "ko", wifiOnly = false) }
            prepared.add(sourceCode)
            mutable.value = TranslationModelState(sourceCode, ModelSetupStage.READY)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            mutable.value = TranslationModelState(sourceCode, ModelSetupStage.ERROR,
                "모델 준비 시간이 초과되었습니다. Wi-Fi 또는 모바일 데이터 연결을 확인한 뒤 다시 시도해 주세요.")
        } catch (e: CancellationException) {
            mutable.value = TranslationModelState(sourceCode, ModelSetupStage.NOT_DOWNLOADED)
            throw e
        } catch (_: Exception) {
            mutable.value = TranslationModelState(
                sourceCode,
                ModelSetupStage.ERROR,
                "모델 준비에 실패했습니다. Wi-Fi 또는 모바일 데이터 연결과 저장 공간을 확인한 뒤 다시 시도해 주세요."
            )
        }
    }

    private suspend fun isDownloaded(code: String): Boolean {
        val language = TranslateLanguage.fromLanguageTag(code) ?: return false
        val remote = TranslateRemoteModel.Builder(language).build()
        return await(manager.isModelDownloaded(remote))
    }

    private suspend fun <T> await(task: Task<T>): T =
        suspendCancellableCoroutine { cont ->
            task.addOnSuccessListener { result -> if (cont.isActive) cont.resume(result) }
                .addOnFailureListener { error ->
                    if (cont.isActive) cont.resumeWithException(error)
                }
        }
}

