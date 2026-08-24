package com.studyink.monitor.render

import java.io.File
import java.util.UUID

/** Why an immutable page image was requested. */
enum class PageRenderPurpose {
    /** The parent asked for the page currently being viewed through the Telegram `/화면` command. */
    TELEGRAM_CURRENT_PAGE,

    /** A student's exact, already-locked attempt is being delivered after submission. */
    LOCKED_SUBMISSION,
}

/**
 * Immutable identity of one rendering job.
 *
 * [pageNumber] is zero-based, matching LibraryRepository and PageOperationLogStore. An on-demand
 * page can have no attempt yet, in which case the result is the untouched PDF page. A submission
 * always carries the catalog lock timestamp as a cheap stale-request guard.
 */
data class PageRenderRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val purpose: PageRenderPurpose,
    val bookId: String,
    val pageNumber: Int,
    val attemptNo: Int?,
    val expectedLockedAtEpochMillis: Long? = null,
    val requestedAtEpochMillis: Long = System.currentTimeMillis(),
) {
    init {
        require(requestId.isNotBlank()) { "Render request id cannot be blank" }
        require(bookId.isNotBlank()) { "Book id cannot be blank" }
        require(pageNumber >= 0) { "Page number must be zero-based and non-negative" }
        require(attemptNo == null || attemptNo > 0) { "Student attempt number must be positive" }
        require(requestedAtEpochMillis >= 0L) { "Request time cannot be negative" }
        when (purpose) {
            PageRenderPurpose.TELEGRAM_CURRENT_PAGE -> require(expectedLockedAtEpochMillis == null) {
                "A current-page request cannot assert a submission lock"
            }

            PageRenderPurpose.LOCKED_SUBMISSION -> {
                requireNotNull(attemptNo) { "A submission render needs an exact attempt" }
                requireNotNull(expectedLockedAtEpochMillis) {
                    "A submission render needs the exact catalog lock timestamp"
                }
                require(expectedLockedAtEpochMillis > 0L) { "Submission lock time must be positive" }
            }
        }
    }

    companion object {
        fun currentPage(
            bookId: String,
            pageNumber: Int,
            attemptNo: Int?,
            requestId: String = UUID.randomUUID().toString(),
            requestedAtEpochMillis: Long = System.currentTimeMillis(),
        ) = PageRenderRequest(
            requestId = requestId,
            purpose = PageRenderPurpose.TELEGRAM_CURRENT_PAGE,
            bookId = bookId,
            pageNumber = pageNumber,
            attemptNo = attemptNo,
            requestedAtEpochMillis = requestedAtEpochMillis,
        )

        fun lockedSubmission(
            bookId: String,
            pageNumber: Int,
            attemptNo: Int,
            lockedAtEpochMillis: Long,
            requestId: String = UUID.randomUUID().toString(),
            requestedAtEpochMillis: Long = System.currentTimeMillis(),
        ) = PageRenderRequest(
            requestId = requestId,
            purpose = PageRenderPurpose.LOCKED_SUBMISSION,
            bookId = bookId,
            pageNumber = pageNumber,
            attemptNo = attemptNo,
            expectedLockedAtEpochMillis = lockedAtEpochMillis,
            requestedAtEpochMillis = requestedAtEpochMillis,
        )
    }
}

enum class PageRenderImageFormat(
    val extension: String,
    val mimeType: String,
) {
    PNG("png", "image/png"),
    JPEG("jpg", "image/jpeg"),
}

/**
 * The default width is intentionally capped at 1600 px. The pixel ceiling is a second guard for
 * abnormally tall PDF pages, so a malformed document cannot force an unbounded ARGB bitmap.
 */
data class PageRenderLimits(
    val targetWidthPixels: Int = 1600,
    val minimumWidthPixels: Int = 1280,
    val maximumWidthPixels: Int = 1600,
    val maximumPixelCount: Long = 4_000_000L,
    val imageFormat: PageRenderImageFormat = PageRenderImageFormat.PNG,
    val jpegQuality: Int = 92,
) {
    init {
        require(minimumWidthPixels > 0) { "Minimum width must be positive" }
        require(maximumWidthPixels >= minimumWidthPixels) { "Maximum width must not be smaller than minimum" }
        require(targetWidthPixels in minimumWidthPixels..maximumWidthPixels) {
            "Target width must stay within the configured 1280-1600 style range"
        }
        require(maximumPixelCount > 0L) { "Maximum pixel count must be positive" }
        require(jpegQuality in 1..100) { "JPEG quality must be between 1 and 100" }
    }
}

/**
 * A closed, complete image file ready to enqueue as a Telegram document.
 *
 * The caller owns [file] after this object is returned and must move it into the durable outbox or
 * delete it. Failed renders always remove their partial file before throwing.
 */
data class RenderedPage(
    val request: PageRenderRequest,
    val file: File,
    val displayFileName: String,
    val mimeType: String,
    val widthPixels: Int,
    val heightPixels: Int,
    val sha256: String,
    val byteCount: Long,
    val annotationRevision: Long,
    val renderedStudentStrokeCount: Int,
    val studentId: String,
    val studentDisplayName: String,
    val bookTitle: String,
    val renderedAtEpochMillis: Long,
)

/** The locked catalog entry changed or disappeared while a queued request was waiting. */
class PageRenderSourceChangedException(message: String) : IllegalStateException(message)
