package com.studyink.library.data

import com.studyink.core.model.Book
import com.studyink.core.model.AnswerPdfViewport
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
        assertEquals(emptyMap<Int, AnswerPdfViewport>(), book.answerViewportMappings)
    }

    @Test
    fun deployedPageOnlyMappingRemainsFallbackWithoutFabricatedViewport() {
        val encoded = baseBook().copy(
            answerPdfRelativePath = "book-1/answer-00000000-0000-0000-0000-000000000001.pdf",
            answerPdfPageCount = 8,
            answerPageMappings = mapOf(4 to 6),
        ).toCatalogJson().apply {
            // This field did not exist in the already deployed page-only catalog.
            remove("answerViewportMappings")
        }

        val restored = encoded.toCatalogBook()

        assertEquals(6, restored.answerPageMappings[4])
        assertNull(restored.answerViewportMappings[4])
    }

    @Test
    fun answerPdfMetadataRoundTripsInDeterministicProblemPageOrder() {
        val book = baseBook().copy(
            answerPdfRelativePath = "book-1/answer-00000000-0000-0000-0000-000000000001.pdf",
            answerPdfPageCount = 8,
            answerPageMappings = linkedMapOf(7 to 5, 1 to 2, 4 to 3),
            lastViewedAnswerPage = 6,
            answerViewportMappings = linkedMapOf(
                7 to AnswerPdfViewport(answerPage = 5, pdfX = 320.5f, pdfY = 480.25f, zoomScale = 2.5f),
                1 to AnswerPdfViewport(answerPage = 2, pdfX = 100f, pdfY = 200f, zoomScale = 1.25f),
            ),
        )

        val encoded = book.toCatalogJson()
        val rows = encoded.getJSONArray("answerPageMappings")
        assertEquals(listOf(1, 4, 7), List(rows.length()) { rows.getJSONObject(it).getInt("problemPage") })
        val viewportRows = encoded.getJSONArray("answerViewportMappings")
        assertEquals(listOf(1, 7), List(viewportRows.length()) { viewportRows.getJSONObject(it).getInt("problemPage") })
        assertEquals(
            book.copy(
                answerPageMappings = book.answerPageMappings.toSortedMap(),
                answerViewportMappings = book.answerViewportMappings.toSortedMap(),
            ),
            encoded.toCatalogBook(),
        )
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

        val viewport = AnswerPdfViewport(answerPage = 1, pdfX = 120f, pdfY = 240f, zoomScale = 1.75f)
        val viewportMapped = mapped.withAnswerViewportMapping(problemPage = 8, viewport = viewport)
        assertEquals(1, viewportMapped.answerPageMappings[8])
        assertEquals(viewport, viewportMapped.answerViewportMappings[8])
        assertEquals(viewport, viewportMapped.withAnswerPageMapping(8, 1).answerViewportMappings[8])
        assertNull(viewportMapped.withAnswerPageMapping(8, 2).answerViewportMappings[8])

        assertThrows(IllegalArgumentException::class.java) { book.withAnswerPageMapping(10, 0) }
        assertThrows(IllegalArgumentException::class.java) { book.withAnswerPageMapping(0, 3) }
        assertThrows(IllegalArgumentException::class.java) { book.withLastViewedAnswerPage(3) }
        assertThrows(IllegalArgumentException::class.java) { baseBook().withAnswerPageMapping(0, 0) }
        assertThrows(IllegalArgumentException::class.java) {
            book.withAnswerViewportMapping(0, viewport.copy(answerPage = 3))
        }
    }

    @Test
    fun viewportRejectsNonFiniteCoordinatesAndNonPositiveZoom() {
        assertThrows(IllegalArgumentException::class.java) {
            AnswerPdfViewport(0, Float.NaN, 0f, 1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnswerPdfViewport(0, 0f, Float.POSITIVE_INFINITY, 1f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AnswerPdfViewport(0, 0f, 0f, 0f)
        }
    }

    @Test
    fun decoderRejectsInvalidViewportCoordinatesAndZoom() {
        val encoded = baseBook().copy(
            answerPdfRelativePath = "book-1/answer-old.pdf",
            answerPdfPageCount = 2,
            answerPageMappings = mapOf(1 to 0),
            answerViewportMappings = mapOf(1 to AnswerPdfViewport(0, 10f, 20f, 1.5f)),
        ).toCatalogJson()
        val viewport = encoded.getJSONArray("answerViewportMappings").getJSONObject(0)

        viewport.put("pdfX", -1)
        assertThrows(IllegalArgumentException::class.java) { encoded.toCatalogBook() }

        viewport.put("pdfX", 10).put("zoomScale", 0)
        assertThrows(IllegalArgumentException::class.java) { encoded.toCatalogBook() }
    }

    @Test
    fun replacingAnswerPdfClearsPageAndViewportState() {
        val previous = baseBook().copy(
            answerPdfRelativePath = "book-1/answer-old.pdf",
            answerPdfPageCount = 8,
            answerPageMappings = mapOf(4 to 6),
            lastViewedAnswerPage = 6,
            answerViewportMappings = mapOf(4 to AnswerPdfViewport(6, 10f, 20f, 2f)),
        )

        val replaced = previous.withImportedAnswerPdf("book-1/answer-new.pdf", 3)

        assertEquals("book-1/answer-new.pdf", replaced.answerPdfRelativePath)
        assertEquals(3, replaced.answerPdfPageCount)
        assertEquals(emptyMap<Int, Int>(), replaced.answerPageMappings)
        assertEquals(0, replaced.lastViewedAnswerPage)
        assertEquals(emptyMap<Int, AnswerPdfViewport>(), replaced.answerViewportMappings)
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
