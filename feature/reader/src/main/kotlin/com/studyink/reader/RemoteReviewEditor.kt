package com.studyink.reader

import java.util.UUID

/**
 * Small in-memory editor exclusively for remote teacher feedback.
 *
 * This class has no repository, AnnotationDocument, attempt, or LAN dependency. A transport must
 * durably enqueue [buildFeedback] before calling [acknowledgePublished].
 */
class RemoteReviewEditor {
    private var snapshot: RemotePageSnapshotRef? = null
    private var strokes: List<RemoteFeedbackStroke> = emptyList()
    private var publishedStrokes: List<RemoteFeedbackStroke> = emptyList()
    private val undoStack = ArrayDeque<List<RemoteFeedbackStroke>>()
    private var editRevision = 0L
    private var publishedRevision = 0L

    val state: RemoteReviewState
        get() = RemoteReviewState(
            snapshot = snapshot,
            strokes = strokes,
            editRevision = editRevision,
            publishedRevision = publishedRevision,
            canUndo = undoStack.isNotEmpty(),
            hasUnpublishedChanges = strokes != publishedStrokes,
        )

    fun openSnapshot(
        next: RemotePageSnapshotRef,
        initialFeedback: RemoteTeacherFeedback? = null,
        discardUnpublishedChanges: Boolean = false,
    ): RemoteSnapshotOpenResult {
        if (snapshot?.transferId == next.transferId) return RemoteSnapshotOpenResult.ALREADY_OPEN
        if (state.hasUnpublishedChanges && !discardUnpublishedChanges) {
            return RemoteSnapshotOpenResult.REJECTED_UNPUBLISHED_CHANGES
        }
        val matchingFeedback = initialFeedback?.takeIf { feedback -> feedback.matches(next) }
        val initialStrokes = matchingFeedback
            ?.strokes
            .orEmpty()
            .map(::sanitizedStroke)
        snapshot = next
        strokes = initialStrokes
        publishedStrokes = initialStrokes
        undoStack.clear()
        editRevision = matchingFeedback?.feedbackRevision?.coerceAtLeast(0L) ?: 0L
        publishedRevision = editRevision
        return RemoteSnapshotOpenResult.OPENED
    }

    fun clearSnapshot(discardUnpublishedChanges: Boolean = false): Boolean {
        if (state.hasUnpublishedChanges && !discardUnpublishedChanges) return false
        snapshot = null
        strokes = emptyList()
        publishedStrokes = emptyList()
        undoStack.clear()
        editRevision = 0L
        publishedRevision = 0L
        return true
    }

    fun addStroke(
        tool: RemoteFeedbackStrokeTool,
        colorArgb: Int,
        widthFraction: Float,
        points: List<RemoteNormalizedPoint>,
        id: String = UUID.randomUUID().toString(),
    ): RemoteFeedbackStroke? {
        if (snapshot == null || id.isBlank() || !widthFraction.isFinite() || widthFraction <= 0f) return null
        if (strokes.size >= MAX_STROKES) return null
        val existingPointCount = strokes.sumOf { stroke -> stroke.points.size }
        val remainingPointCapacity = (MAX_TOTAL_POINTS - existingPointCount).coerceAtLeast(0)
        if (remainingPointCapacity == 0) return null
        val cleanPoints = sanitizePoints(points, remainingPointCapacity)
        if (cleanPoints.isEmpty()) return null
        val stroke = RemoteFeedbackStroke(
            id = id,
            tool = tool,
            colorArgb = colorArgb,
            widthFraction = widthFraction.coerceIn(MIN_WIDTH_FRACTION, MAX_WIDTH_FRACTION),
            points = cleanPoints,
        )
        rememberForUndo()
        strokes = strokes + stroke
        advanceRevision()
        return stroke
    }

    /** Whole-trace erase. It examines and mutates only this editor's teacher layer. */
    fun erase(
        path: List<RemoteNormalizedPoint>,
        radiusFraction: Float,
    ): Set<String> {
        val currentSnapshot = snapshot ?: return emptySet()
        if (!radiusFraction.isFinite() || radiusFraction <= 0f) return emptySet()
        val cleanPath = sanitizePoints(path)
        if (cleanPath.isEmpty()) return emptySet()
        val removed = strokes.asSequence()
            .filter { stroke ->
                RemoteReviewGeometry.intersectsEraser(
                    stroke = stroke,
                    eraserPath = cleanPath,
                    eraserRadiusFraction = radiusFraction.coerceAtMost(MAX_ERASER_RADIUS_FRACTION),
                    pageWidthPx = currentSnapshot.imageWidthPx,
                    pageHeightPx = currentSnapshot.imageHeightPx,
                )
            }
            .map(RemoteFeedbackStroke::id)
            .toSet()
        if (removed.isEmpty()) return emptySet()
        rememberForUndo()
        strokes = strokes.filterNot { it.id in removed }
        advanceRevision()
        return removed
    }

    fun undo(): Boolean {
        if (undoStack.isEmpty()) return false
        strokes = undoStack.removeLast()
        advanceRevision()
        return true
    }

    /** Returns null when there is no new teacher edit to send. An empty stroke list is intentional. */
    fun buildFeedback(
        feedbackId: String = UUID.randomUUID().toString(),
        createdAtEpochMillis: Long = System.currentTimeMillis(),
    ): RemoteTeacherFeedback? {
        val currentSnapshot = snapshot ?: return null
        if (!state.hasUnpublishedChanges || feedbackId.isBlank()) return null
        return RemoteTeacherFeedback(
            feedbackId = feedbackId,
            sourceTransferId = currentSnapshot.transferId,
            pageToken = currentSnapshot.pageToken,
            bookFingerprint = currentSnapshot.bookFingerprint,
            pageNumber = currentSnapshot.pageNumber,
            basedOnStudentRevision = currentSnapshot.studentRevision,
            feedbackRevision = editRevision,
            strokes = strokes,
            createdAtEpochMillis = createdAtEpochMillis,
        )
    }

    /**
     * Marks exactly the payload accepted by durable transport. Later edits remain dirty even if an
     * older enqueue callback completes after them.
     */
    fun acknowledgePublished(feedback: RemoteTeacherFeedback): Boolean {
        val currentSnapshot = snapshot ?: return false
        if (!feedback.matches(currentSnapshot) || feedback.feedbackRevision < publishedRevision) return false
        publishedStrokes = feedback.strokes.map(::sanitizedStroke)
        publishedRevision = feedback.feedbackRevision
        return true
    }

    private fun rememberForUndo() {
        if (undoStack.size >= MAX_UNDO_DEPTH) undoStack.removeFirst()
        undoStack.addLast(strokes)
    }

    private fun advanceRevision() {
        editRevision = if (editRevision == Long.MAX_VALUE) 1L else editRevision + 1L
    }

    private fun sanitizedStroke(stroke: RemoteFeedbackStroke): RemoteFeedbackStroke = stroke.copy(
        widthFraction = stroke.widthFraction
            .takeIf(Float::isFinite)
            ?.coerceIn(MIN_WIDTH_FRACTION, MAX_WIDTH_FRACTION)
            ?: DEFAULT_PEN_WIDTH_FRACTION,
        points = sanitizePoints(stroke.points),
    )

    private fun sanitizePoints(
        points: List<RemoteNormalizedPoint>,
        limit: Int = MAX_POINTS_PER_STROKE,
    ): List<RemoteNormalizedPoint> = buildList {
        val boundedLimit = limit.coerceIn(0, MAX_POINTS_PER_STROKE)
        for (point in points) {
            if (size >= boundedLimit) break
            if (!point.x.isFinite() || !point.y.isFinite()) continue
            val clean = RemoteNormalizedPoint(
                x = point.x.coerceIn(0f, 1f),
                y = point.y.coerceIn(0f, 1f),
                pressure = point.pressure.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 1f,
            )
            val previous = lastOrNull()
            if (previous == null || kotlin.math.abs(previous.x - clean.x) + kotlin.math.abs(previous.y - clean.y) > POINT_EPSILON) {
                add(clean)
            }
        }
    }

    private fun RemoteTeacherFeedback.matches(snapshot: RemotePageSnapshotRef): Boolean =
        sourceTransferId == snapshot.transferId &&
            pageToken == snapshot.pageToken &&
            bookFingerprint == snapshot.bookFingerprint &&
            pageNumber == snapshot.pageNumber &&
            basedOnStudentRevision == snapshot.studentRevision

    companion object {
        const val DEFAULT_PEN_WIDTH_FRACTION = 0.0032f
        const val DEFAULT_HIGHLIGHTER_WIDTH_FRACTION = 0.018f
        const val DEFAULT_ERASER_RADIUS_FRACTION = 0.018f
        private const val MIN_WIDTH_FRACTION = 0.0005f
        private const val MAX_WIDTH_FRACTION = 0.08f
        private const val MAX_ERASER_RADIUS_FRACTION = 0.12f
        private const val POINT_EPSILON = 0.00005f
        private const val MAX_UNDO_DEPTH = 100
        private const val MAX_STROKES = 4_096
        private const val MAX_POINTS_PER_STROKE = 8_192
        private const val MAX_TOTAL_POINTS = 120_000
    }
}
