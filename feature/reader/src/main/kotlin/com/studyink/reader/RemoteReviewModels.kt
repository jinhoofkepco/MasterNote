package com.studyink.reader

import java.util.UUID

const val REMOTE_FEEDBACK_FORMAT_VERSION = 1

/**
 * Transport-neutral identity for one full-page image received from a student.
 *
 * [pageToken] is deliberately opaque to the reader UI. The transport owns pairing, encryption,
 * and serialization; the UI only echoes the token back with the correction so it cannot attach a
 * teacher layer to a different page by accident.
 */
data class RemotePageSnapshotRef(
    val transferId: String,
    val pageToken: String,
    val bookFingerprint: String,
    val pageNumber: Int,
    val studentRevision: Long,
    val imageWidthPx: Int,
    val imageHeightPx: Int,
    val receivedAtEpochMillis: Long,
) {
    init {
        require(transferId.isNotBlank())
        require(pageToken.isNotBlank())
        require(bookFingerprint.isNotBlank())
        require(pageNumber >= 0)
        require(studentRevision >= 0L)
        require(imageWidthPx > 0 && imageHeightPx > 0)
    }
}

/** A point relative to the full page: left/top is 0 and right/bottom is 1. */
data class RemoteNormalizedPoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
)

enum class RemoteFeedbackStrokeTool { PEN, HIGHLIGHTER }

/**
 * One teacher-only trace. It never enters AnnotationSnapshot, so it cannot erase, submit, grade,
 * or change a student's local attempt.
 */
data class RemoteFeedbackStroke(
    val id: String = UUID.randomUUID().toString(),
    val tool: RemoteFeedbackStrokeTool,
    val colorArgb: Int,
    /** Width relative to the shorter side of the page. */
    val widthFraction: Float,
    val points: List<RemoteNormalizedPoint>,
)

/** Immutable payload handed to the transport when the teacher taps publish. */
data class RemoteTeacherFeedback(
    val feedbackId: String,
    val sourceTransferId: String,
    val pageToken: String,
    val bookFingerprint: String,
    val pageNumber: Int,
    val basedOnStudentRevision: Long,
    val feedbackRevision: Long,
    val strokes: List<RemoteFeedbackStroke>,
    val createdAtEpochMillis: Long,
    val formatVersion: Int = REMOTE_FEEDBACK_FORMAT_VERSION,
)

enum class RemoteReviewTool { PEN, HIGHLIGHTER, ERASER, GRADE }

/**
 * Exact immutable target selected by the teacher before the host opens its correct/incorrect UI.
 * Carrying the snapshot reference with the normalized anchor prevents a delayed chooser result
 * from being applied to whichever page happens to be visible later.
 */
data class RemoteReviewGradeTap(
    val snapshot: RemotePageSnapshotRef,
    val anchor: RemoteNormalizedPoint,
)

enum class RemoteSnapshotOpenResult {
    OPENED,
    ALREADY_OPEN,
    /** A different page was offered while this page still has unpublished teacher edits. */
    REJECTED_UNPUBLISHED_CHANGES,
}

data class RemoteReviewState(
    val snapshot: RemotePageSnapshotRef? = null,
    val strokes: List<RemoteFeedbackStroke> = emptyList(),
    val editRevision: Long = 0L,
    val publishedRevision: Long = 0L,
    val canUndo: Boolean = false,
    val hasUnpublishedChanges: Boolean = false,
)
