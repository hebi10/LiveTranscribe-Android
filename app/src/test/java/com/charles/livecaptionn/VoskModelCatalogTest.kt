package com.charles.livecaptionn

import com.charles.livecaptionn.speech.SpeechModelEngine
import com.charles.livecaptionn.speech.VoskModelCatalog
import com.charles.livecaptionn.speech.VoskModelInfo
import com.charles.livecaptionn.speech.bundledSpeechModelAvailable
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoskModelCatalogTest {
    @get:Rule val assets = TemporaryFolder()

    @Test fun languageLookupIsCaseInsensitive() {
        assertEquals("en", VoskModelCatalog.findByLanguage("EN")?.languageCode)
    }

    @Test fun unknownLanguageIsUnavailable() {
        assertNull(VoskModelCatalog.findByLanguage("xx"))
    }

    @Test fun catalogRoutesJapaneseToReazonAndKeepsEnglishOnVosk() {
        assertEquals(SpeechModelEngine.REAZON, model("ja").engine)
        assertEquals(SpeechModelEngine.VOSK, model("en").engine)
    }

    @Test fun completeJapaneseModelIsReadyWithoutVoskGraphDirectories() {
        japaneseAssets.forEach { write(model("ja"), it, byteArrayOf(1)) }
        assertTrue(available(model("ja")))
    }

    @Test fun eachMissingJapaneseAssetPreventsReadiness() {
        japaneseAssets.forEach { missing ->
            val japanese = model("ja")
            japaneseAssets.filterNot { it == missing }.forEach { write(japanese, it, byteArrayOf(1)) }
            assertFalse("Missing $missing must prevent captions from starting", available(japanese))
            assetDirectory(japanese).deleteRecursively()
        }
    }

    @Test fun eachEmptyJapaneseAssetPreventsReadiness() {
        japaneseAssets.forEach { empty ->
            val japanese = model("ja")
            japaneseAssets.forEach { write(japanese, it, if (it == empty) byteArrayOf() else byteArrayOf(1)) }
            assertFalse("Empty $empty must prevent captions from starting", available(japanese))
            assetDirectory(japanese).deleteRecursively()
        }
    }

    @Test fun leftoverVoskJapaneseAssetsDoNotSatisfyReazonReadiness() {
        val japanese = model("ja")
        write(japanese, "conf/model.conf", byteArrayOf(1))
        write(japanese, "am/final.mdl", byteArrayOf(1))
        assertFalse(available(japanese))
    }

    @Test fun absentEnglishAssetsAreUnavailableAndBundledVoskDirectoryIsReady() {
        val english = model("en")
        assertFalse(available(english))
        write(english, "am/final.mdl", byteArrayOf(1))
        assertTrue(available(english))
    }

    @Test fun unreadableRequiredJapaneseAssetIsUnavailable() {
        val japanese = model("ja")
        japaneseAssets.forEach { write(japanese, it, byteArrayOf(1)) }
        assertFalse(bundledSpeechModelAvailable(
            japanese,
            listAssets = { File(assets.root, it).list() },
            assetIsNonEmpty = { throw java.io.IOException("unreadable APK asset") }
        ))
    }

    private fun model(language: String): VoskModelInfo = VoskModelCatalog.findByLanguage(language)!!
    private fun assetDirectory(model: VoskModelInfo) = File(assets.root, "models/${model.modelName}")
    private fun write(model: VoskModelInfo, name: String, bytes: ByteArray) {
        File(assetDirectory(model), name).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
    }
    private fun available(model: VoskModelInfo) = bundledSpeechModelAvailable(
        model,
        listAssets = { File(assets.root, it).list() },
        assetIsNonEmpty = { File(assets.root, it).let { file -> file.isFile && file.length() > 0 } }
    )

    companion object {
        private val japaneseAssets = listOf(
            "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt", "silero_vad.onnx"
        )
    }
}
