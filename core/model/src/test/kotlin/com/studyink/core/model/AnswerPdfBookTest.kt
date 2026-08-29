package com.studyink.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnswerPdfBookTest {
    @Test
    fun legacyBookConstructionKeepsCompatibleAnswerPdfDefaults() {
        val book = Book(
            "book-1",
            "student-1",
            "Workbook",
            12,
            "book-1/document.pdf",
            "hash",
            "book-1/answers.json",
            100L,
            null,
        )

        assertEquals("book-1/answers.json", book.answerSourceRelativePath)
        assertNull(book.answerPdfRelativePath)
        assertEquals(0, book.answerPdfPageCount)
        assertEquals(emptyMap<Int, Int>(), book.answerPageMappings)
        assertEquals(0, book.lastViewedAnswerPage)
    }
}
