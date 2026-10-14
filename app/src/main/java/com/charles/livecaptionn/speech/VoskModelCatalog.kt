package com.charles.livecaptionn.speech

enum class VoskSource { BUNDLED }
enum class ModelQuality { SMALL, STANDARD }
enum class SpeechModelEngine { VOSK, REAZON }

data class VoskModelInfo(
    val languageCode: String,
    val languageName: String,
    val modelName: String,
    val sizeMb: Int,
    val installed: Boolean = false,
    val source: VoskSource = VoskSource.BUNDLED,
    val isBundled: Boolean = true,
    val quality: ModelQuality = ModelQuality.SMALL,
    val engine: SpeechModelEngine = SpeechModelEngine.VOSK
)

object VoskModelCatalog {
    val BUNDLED_MODELS: List<VoskModelInfo> = listOf(
        VoskModelInfo("en", "영어", "vosk-model-small-en-us-0.15", 40),
        VoskModelInfo("ja", "일본어", "reazonspeech-k2-v2", 153,
            quality = ModelQuality.STANDARD, engine = SpeechModelEngine.REAZON)
    )
    fun findByLanguage(code: String): VoskModelInfo? =
        BUNDLED_MODELS.firstOrNull { it.languageCode.equals(code, ignoreCase = true) }
}

