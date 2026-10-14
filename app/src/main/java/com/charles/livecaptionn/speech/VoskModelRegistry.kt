package com.charles.livecaptionn.speech

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Offline-only Vosk model registry. Models are packaged into the APK at build
 * time and are never downloaded by the installed application.
 */
class VoskModelRegistry(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableModels = MutableStateFlow<List<VoskModelInfo>>(emptyList())
    val models: StateFlow<List<VoskModelInfo>> = mutableModels.asStateFlow()

    init {
        scope.launch { refresh() }
    }

    fun refresh() {
        mutableModels.value = VoskModelCatalog.BUNDLED_MODELS.map { info ->
            info.copy(installed = !context.assets.list("models/${info.modelName}").isNullOrEmpty())
        }
    }

    fun installedModelFor(languageCode: String): VoskModelInfo? =
        mutableModels.value.firstOrNull {
            it.installed && it.languageCode.equals(languageCode, ignoreCase = true)
        }

    fun resolveModelDir(languageCode: String): File? {
        val model = installedModelFor(languageCode) ?: return null
        val targetDir = File(context.filesDir, "vosk/bundled/${model.modelName}")
        val marker = File(targetDir, ".copied")
        if (marker.exists()) return targetDir
        return try {
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()
            copyAssets("models/${model.modelName}", targetDir)
            marker.writeText("ready")
            targetDir
        } catch (e: Exception) {
            Log.e("VoskModelRegistry", "Failed to prepare bundled speech model", e)
            targetDir.deleteRecursively()
            null
        }
    }

    private fun copyAssets(assetPath: String, dir: File) {
        val children = context.assets.list(assetPath).orEmpty()
        require(children.isNotEmpty()) { "Bundled speech model is empty" }
        for (child in children) {
            val path = "$assetPath/$child"
            val sub = context.assets.list(path).orEmpty()
            val out = File(dir, child)
            if (sub.isEmpty()) {
                context.assets.open(path).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            } else {
                out.mkdirs()
                copyAssets(path, out)
            }
        }
    }
}
