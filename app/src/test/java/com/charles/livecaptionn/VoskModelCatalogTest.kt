package com.charles.livecaptionn

import com.charles.livecaptionn.speech.VoskModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoskModelCatalogTest {
    @Test fun englishAndJapaneseArePackaged() {
        assertNotNull(VoskModelCatalog.findByLanguage("en"))
        assertNotNull(VoskModelCatalog.findByLanguage("ja"))
    }

    @Test fun languageLookupIsCaseInsensitive() {
        assertEquals("en", VoskModelCatalog.findByLanguage("EN")?.languageCode)
    }

    @Test fun unknownLanguageIsUnavailable() {
        assertNull(VoskModelCatalog.findByLanguage("xx"))
    }

    @Test fun onlySmallModelsArePackaged() {
        assertTrue(VoskModelCatalog.BUNDLED_MODELS.all { it.sizeMb < 100 })
    }
}
