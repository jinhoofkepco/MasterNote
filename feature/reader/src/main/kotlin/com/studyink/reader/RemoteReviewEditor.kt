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
    /**
     * Monotonic identity for the exact in-memory stroke state. Unlike [editRevision], this is not
     * reset when a page is opened, so an erase computed for an earlier visit to the same transfer
     * can never be committed after a page transition.
     */
    private var mutationEpoch = 0L

    val state: RemoteReviewState
        get() = RemoteReviewState(
            snapshot = snapshot,
            // Copy only the small outer list. Point lists were already sanitized into immutable
            // values when strokes entered the editor, so copying 120k points on ACTION_UP is not
            // necessary and would itself risk a UI pause.
            strokes = strokes.toList(),
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
        advanceMutationEpoch()
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
        advanceMutationEpoch()
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

    /**
     * Captures immutable input for a whole-trace erase without doing geometric work. The returned
     * plan is safe to evaluate on a worker thread; [commitErase] must be called on the editor's
     * owning thread afterwards.
     */
    internal fun prepareErase(
        path: List<RemoteNormalizedPoint>,
        radiusFraction: Float,
    ): RemoteReviewErasePlan? {
        val currentSnapshot = snapshot ?: return null
        if (!radiusFraction.isFinite() || radiusFraction <= 0f) return null
        val cleanPath = sanitizePoints(path)
        if (cleanPath.isEmpty()) return null
        return RemoteReviewErasePlan(
            snapshot = currentSnapshot,
            editRevision = editRevision,
            mutationEpoch = mutationEpoch,
            strokes = strokes,
            path = cleanPath,
            radiusFraction = radiusFraction.coerceAtMost(MAX_ERASER_RADIUS_FRACTION),
        )
    }

    /** Pure, potentially expensive phase intended for a background thread. */
    internal fun resolveErase(plan: RemoteReviewErasePlan): Set<String> =
        RemoteReviewGeometry.intersectingStrokeIds(
            strokes = plan.strokes,
            eraserPath = plan.path,
            eraserRadiusFraction = plan.radiusFraction,
            pageWidthPx = plan.snapshot.imageWidthPx,
            pageHeightPx = plan.snapshot.imageHeightPx,
        )

    /**
     * Applies an evaluated erase only if the snapshot and stroke revision are still exactly those
     * captured by [prepareErase]. A stale or duplicate completion is a no-op and creates no undo
     * entry.
     */
    internal fun commitErase(
        plan: RemoteReviewErasePlan,
        candidateStrokeIds: Set<String>,
    ): Set<String> {
        if (
            snapshot != plan.snapshot ||
            editRevision != plan.editRevision ||
            mutationEpoch != plan.mutationEpoch
        ) return emptySet()
        if (candidateStrokeIds.isEmpty()) return emptySet()
        val plannedIds = plan.strokes.asSequence().map(RemoteFeedbackStroke::id).toHashSet()
        val currentIds = strokes.asSequence().map(RemoteFeedbackStroke::id).toHashSet()
        val removed = candidateStrokeIds.asSequence()
            .filterTo(linkedSetOf()) { id -> id in plannedIds && id in currentIds }
        if (removed.isEmpty()) return emptySet()
        rememberForUndo()
        strokes = strokes.filterNot { stroke -> stroke.id in removed }
        advanceRevision()
        return removed
    }

    /** Whole-trace erase. It examines and mutates only this editor's teacher layer. */
    fun erase(
        path: List<RemoteNormalizedPoint>,
        radiusFraction: Float,
    ): Set<String> {
        val plan = prepareErase(path, radiusFraction) ?: return emptySet()
        return commitErase(plan, resolveErase(plan))
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
        advanceMutationEpoch()
    }

    private fun advanceMutationEpoch() {
        mutationEpoch = if (mutationEpoch == Long.MAX_VALUE) 1L else mutationEpoch + 1L
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

/** Immutable erase input that may cross from the UI thread to one worker thread. */
internal data class RemoteReviewErasePlan(
    val snapshot: RemotePageSnapshotRef,
    val editRevision: Long,
    val mutationEpoch: Long,
    val strokes: List<RemoteFeedbackStroke>,
    val path: List<RemoteNormalizedPoint>,
    val radiusFraction: Float,
)
