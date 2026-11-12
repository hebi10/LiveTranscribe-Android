package com.charles.livecaptionn.data

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaptionToolsPersistenceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun glossaryCrudPreservesEntryIdsAndLiteralReplacements() = runBlocking {
        val path = File(tmp.root, "glossary.json")
        val store = FileGlossaryRepository(path, forTesting = true)
        val entry = GlossaryEntry(id = "qa-glossary", phrase = "API",
            replacement = "한국어 표기", caseSensitive = false)

        store.save(entry)
        assertEquals(listOf(entry), store.list())
        store.save(entry.copy(replacement = "수정 표기", caseSensitive = true))
        assertEquals(1, store.list().size)
        assertEquals("수정 표기", store.list().single().replacement)
        assertEquals("api 사용", applyGlossary("api 사용", store.list()))

        val dollarValue = "값 " + 36.toChar() + "1"
        store.save(entry.copy(replacement = dollarValue, caseSensitive = false))
        assertEquals(dollarValue + " 사용", applyGlossary("api 사용", store.list()))
        assertTrue(store.delete(entry.id))
        assertTrue(store.list().isEmpty())
    }

    @Test fun profileCrudKeepsLanguageSizeAndOriginalVisibility() = runBlocking {
        val file = File(tmp.root, "profiles.json")
        val repo = FileCaptionProfileRepository(file, forTesting = true)
        val item = CaptionProfile(id = "qa-profile", name = "영화", sourceLanguage = "ja",
            targetLanguage = "ko", textSizeSp = 26f, showOriginal = false)
        repo.save(item)
        assertEquals(listOf(item), repo.list())
        repo.save(item.copy(name = "영화 자막", textSizeSp = 30f, showOriginal = true))
        val loaded = repo.list().single()
        assertEquals("qa-profile", loaded.id)
        assertEquals("영화 자막", loaded.name)
        assertEquals(30f, loaded.textSizeSp)
        assertTrue(loaded.showOriginal)
        assertTrue(repo.delete(item.id))
        assertFalse(repo.delete(item.id))
        assertTrue(repo.list().isEmpty())
    }

    @Test fun damagedGlossaryAndPresetFilesAreNeverOverwritten() = runBlocking {
        val glossaryFile = tmp.newFile("damaged-glossary.json").apply {
            writeText("{broken", Charsets.UTF_8)
        }
        val profileFile = tmp.newFile("damaged-profile.json").apply {
            writeText("{broken", Charsets.UTF_8)
        }
        val originalGlossary = glossaryFile.readBytes()
        val originalProfiles = profileFile.readBytes()
        val glossary = FileGlossaryRepository(glossaryFile, forTesting = true)
        val profiles = FileCaptionProfileRepository(profileFile, forTesting = true)
        try {
            glossary.save(GlossaryEntry(id = "new", phrase = "A", replacement = "B"))
            throw AssertionError("A damaged glossary must reject mutations")
        } catch (_: IOException) { }
        try {
            profiles.save(CaptionProfile(id = "new", name = "New",
                sourceLanguage = "auto", targetLanguage = "ko"))
            throw AssertionError("A damaged profile must reject mutations")
        } catch (_: IOException) { }
        assertTrue(originalGlossary.contentEquals(glossaryFile.readBytes()))
        assertTrue(originalProfiles.contentEquals(profileFile.readBytes()))
    }
}
