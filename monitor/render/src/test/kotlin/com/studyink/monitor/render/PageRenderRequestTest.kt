package com.studyink.monitor.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageRenderRequestTest {
    @Test
    fun `current page may represent a page with no attempt yet`() {
        val request = PageRenderRequest.currentPage(
            bookId = "book",
            pageNumber = 6,
            attemptNo = null,
            requestId = "telegram-update-10",
            requestedAtEpochMillis = 100L,
        )

        assertEquals(PageRenderPurpose.TELEGRAM_CURRENT_PAGE, request.purpose)
        assertNull(request.attemptNo)
        assertNull(request.expectedLockedAtEpochMillis)
    }

    @Test
    fun `submission factory binds exact attempt and lock timestamp`() {
        val request = PageRenderRequest.lockedSubmission(
            bookId = "book",
            pageNumber = 6,
            attemptNo = 4,
            lockedAtEpochMillis = 999L,
            requestId = "submission-book-6-4-999",
            requestedAtEpochMillis = 1_000L,
        )

        assertEquals(PageRenderPurpose.LOCKED_SUBMISSION, request.purpose)
        assertEquals(4, request.attemptNo)
        assertEquals(999L, request.expectedLockedAtEpochMillis)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `submission cannot be constructed without an exact lock`() {
        PageRenderRequest(
            purpose = PageRenderPurpose.LOCKED_SUBMISSION,
            bookId = "book",
            pageNumber = 0,
            attemptNo = 1,
        )
    }
}
