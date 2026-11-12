package com.charles.livecaptionn.data

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TranscriptHistoryCorrectionTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun correctionUpdatesOnlyExistingExpectedTranslation() = runBlocking {
        val store = TranscriptHistoryStore(File(temp.root, "transcripts.json"), forTesting = true)
        val row = TranscriptEntry(id = "correction-1", originalText = "I will go.", translatedText = "나는 갈 것입니다.",
            sourceLanguage = "en", targetLanguage = "ko")
        store.add(row)
        assertFalse(store.updateTranslatedTextById("wrong", row.translatedText, "가겠습니다."))
        assertFalse(store.updateTranslatedTextById(row.id, "예전 문장", "가겠습니다."))
        assertTrue(store.updateTranslatedTextById(row.id, row.translatedText, "가겠습니다."))
        assertEquals("가겠습니다.", store.getAll().single().translatedText)
        assertFalse(store.updateTranslatedTextById(row.id, row.translatedText, "다시 수정"))
        assertTrue(store.deleteById(row.id))
        assertFalse(store.updateTranslatedTextById(row.id, "가겠습니다.", "삭제 복원"))
        assertTrue(store.getAll().isEmpty())
    }
}
