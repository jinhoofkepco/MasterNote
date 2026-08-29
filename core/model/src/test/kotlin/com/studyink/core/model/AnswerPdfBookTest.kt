package com.studyink.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
        assertEquals(emptyMap<Int, AnswerPdfViewport>(), book.answerViewportMappings)
        assertEquals(emptyMap<Int, AnswerPdfCrop>(), book.answerCropMappings)
        assertNull(book.lastAnswerPdfViewport)
    }

    @Test
    fun cropRequiresOneValidPageAndFinitePositiveArea() {
        assertEquals(
            AnswerPdfCrop(answerPage = 2, left = 10f, top = 20f, right = 30f, bottom = 50f),
            AnswerPdfCrop(answerPage = 2, left = 10f, top = 20f, right = 30f, bottom = 50f),
        )

        assertThrows(IllegalArgumentException::class.java) {
            AnswerPdfCrop(answerPage = -1, left = 0f, top = 0f, right = 1f, bottom = 1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnswerPdfCrop(answerPage = 0, left = Float.NaN, top = 0f, right = 1f, bottom = 1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnswerPdfCrop(answerPage = 0, left = 0f, top = 0f, right = Float.POSITIVE_INFINITY, bottom = 1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnswerPdfCrop(answerPage = 0, left = 4f, top = 0f, right = 4f, bottom = 1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnswerPdfCrop(answerPage = 0, left = 0f, top = 4f, right = 1f, bottom = 3f)
        }
    }
}
