package com.studyink.library.data

import com.studyink.core.model.Book
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class LibraryAnswerPdfMetadataTest {
    @Test
    fun legacyCatalogBookWithoutAnswerPdfFieldsUsesDefaults() {
        val book = legacyBookJson().toCatalogBook()

        assertEquals("book-1/answers.json", book.answerSourceRelativePath)
        assertNull(book.answerPdfRelativePath)
        assertEquals(0, book.answerPdfPageCount)
        assertEquals(emptyMap<Int, Int>(), book.answerPageMappings)
        assertEquals(0, book.lastViewedAnswerPage)
    }

    @Test
    fun answerPdfMetadataRoundTripsInDeterministicProblemPageOrder() {
        val book = baseBook().copy(
            answerPdfRelativePath = "book-1/answer-00000000-0000-0000-0000-000000000001.pdf",
            answerPdfPageCount = 8,
            answerPageMappings = linkedMapOf(7 to 5, 1 to 2, 4 to 3),
            lastViewedAnswerPage = 6,
        )

        val encoded = book.toCatalogJson()
        val rows = encoded.getJSONArray("answerPageMappings")
        assertEquals(listOf(1, 4, 7), List(rows.length()) { rows.getJSONObject(it).getInt("problemPage") })
        assertEquals(book.copy(answerPageMappings = book.answerPageMappings.toSortedMap()), encoded.toCatalogBook())
    }

    @Test
    fun pageMappingAndLastPositionValidateBothPdfRanges() {
        val book = baseBook().copy(
            answerPdfRelativePath = "book-1/answer-00000000-0000-0000-0000-000000000001.pdf",
            answerPdfPageCount = 3,
        )

        val mapped = book.withAnswerPageMapping(problemPage = 9, answerPage = 2)
        assertEquals(2, mapped.answerPageMappings[9])
        assertEquals(1, mapped.withLastViewedAnswerPage(1).lastViewedAnswerPage)

        assertThrows(IllegalArgumentException::class.java) { book.withAnswerPageMapping(10, 0) }
        assertThrows(IllegalArgumentException::class.java) { book.withAnswerPageMapping(0, 3) }
        assertThrows(IllegalArgumentException::class.java) { book.withLastViewedAnswerPage(3) }
        assertThrows(IllegalArgumentException::class.java) { baseBook().withAnswerPageMapping(0, 0) }
    }

    @Test
    fun decoderRejectsDuplicateOrOutOfRangeMappings() {
        val encoded = baseBook().copy(
            answerPdfRelativePath = "book-1/answer-00000000-0000-0000-0000-000000000001.pdf",
            answerPdfPageCount = 2,
        ).toCatalogJson()
        encoded.put(
            "answerPageMappings",
            JSONArray()
                .put(JSONObject().put("problemPage", 1).put("answerPage", 0))
                .put(JSONObject().put("problemPage", 1).put("answerPage", 1)),
        )
        assertThrows(IllegalArgumentException::class.java) { encoded.toCatalogBook() }

        encoded.put(
            "answerPageMappings",
            JSONArray().put(JSONObject().put("problemPage", 10).put("answerPage", 0)),
        )
        assertThrows(IllegalArgumentException::class.java) { encoded.toCatalogBook() }
    }

    private fun baseBook() = Book(
        id = "book-1",
        studentId = "student-1",
        title = "Workbook",
        pageCount = 10,
        pdfRelativePath = "book-1/document.pdf",
        contentSha256 = "a".repeat(64),
        answerSourceRelativePath = "book-1/answers.json",
        createdAtEpochMillis = 100L,
    )

    private fun legacyBookJson() = JSONObject()
        .put("id", "book-1")
        .put("studentId", "student-1")
        .put("title", "Workbook")
        .put("pageCount", 10)
        .put("pdfPath", "book-1/document.pdf")
        .put("contentSha256", "a".repeat(64))
        .put("answerPath", "book-1/answers.json")
        .put("createdAt", 100L)
        .put("hiddenAt", JSONObject.NULL)
}
