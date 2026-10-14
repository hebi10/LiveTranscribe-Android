package com.charles.livecaptionn.speech

enum class VoskSource { BUNDLED }
enum class ModelQuality { SMALL }

data class VoskModelInfo(
    val languageCode: String,
    val languageName: String,
    val modelName: String,
    val sizeMb: Int,
    val installed: Boolean = false,
    val source: VoskSource = VoskSource.BUNDLED,
    val isBundled: Boolean = true,
    val quality: ModelQuality = ModelQuality.SMALL
)

object VoskModelCatalog {
    val BUNDLED_MODELS: List<VoskModelInfo> = listOf(
        VoskModelInfo("en", "영어", "vosk-model-small-en-us-0.15", 40),
        VoskModelInfo("ja", "일본어", "vosk-model-small-ja-0.22", 48)
    )
    fun findByLanguage(code: String): VoskModelInfo? =
        BUNDLED_MODELS.firstOrNull { it.languageCode.equals(code, ignoreCase = true) }
}
