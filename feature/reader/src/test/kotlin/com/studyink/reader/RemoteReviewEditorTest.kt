package com.studyink.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteReviewEditorTest {
    @Test
    fun eraseWorkerResolutionCommitsOnlyNormalUninterruptedResults() {
        assertEquals(
            RemoteEraseResolution.Success(setOf("stroke-1")),
            resolveRemoteEraseWork(resolve = { setOf("stroke-1") }, isInterrupted = { false }),
        )
        assertSame(
            RemoteEraseResolution.Failed,
            resolveRemoteEraseWork(
                resolve = { throw OutOfMemoryError("simulated worker exhaustion") },
                isInterrupted = { false },
            ),
        )
        assertSame(
            RemoteEraseResolution.Failed,
            resolveRemoteEraseWork(resolve = { setOf("stroke-1") }, isInterrupted = { true }),
        )
        var interruptChecks = 0
        assertSame(
            RemoteEraseResolution.Failed,
            resolveRemoteEraseWork(
                resolve = { setOf("stroke-1") },
                isInterrupted = { ++interruptChecks > 1 },
            ),
        )
    }

    @Test
    fun acceptedPublishBecomesCleanWithoutTouchingAnyExternalAnnotationState() {
        val editor = RemoteReviewEditor()
        assertEquals(RemoteSnapshotOpenResult.OPENED, editor.openSnapshot(snapshot()))
        editor.addStroke(
            tool = RemoteFeedbackStrokeTool.PEN,
            colorArgb = 0xFFD94747.toInt(),
            widthFraction = 0.004f,
            points = listOf(point(0.1f, 0.2f), point(0.3f, 0.4f)),
            id = "correction-1",
        )

        val payload = editor.buildFeedback("feedback-1", 123L)
        assertNotNull(payload)
        payload!!
        assertEquals("student-transfer-1", payload.sourceTransferId)
        assertEquals("opaque-book-page", payload.pageToken)
        assertEquals(7L, payload.basedOnStudentRevision)
        assertTrue(editor.state.hasUnpublishedChanges)

        assertTrue(editor.acknowledgePublished(payload))
        assertFalse(editor.state.hasUnpublishedChanges)
        assertNull(editor.buildFeedback("nothing-new", 124L))
    }

    @Test
    fun eraserRunsAgainstTeacherLayerOnlyAndUndoRestoresIt() {
        val editor = RemoteReviewEditor()
        editor.openSnapshot(snapshot())
        editor.addStroke(
            RemoteFeedbackStrokeTool.PEN,
            0xFFD94747.toInt(),
            0.004f,
            listOf(point(0.1f, 0.2f), point(0.9f, 0.2f)),
            "near",
        )
        editor.addStroke(
            RemoteFeedbackStrokeTool.HIGHLIGHTER,
            0x66FFE45C,
            0.018f,
            listOf(point(0.1f, 0.8f), point(0.9f, 0.8f)),
            "far",
        )

        val removed = editor.erase(
            path = listOf(point(0.5f, 0.1f), point(0.5f, 0.3f)),
            radiusFraction = 0.02f,
        )

        assertEquals(setOf("near"), removed)
        assertEquals(listOf("far"), editor.state.strokes.map(RemoteFeedbackStroke::id))
        assertTrue(editor.undo())
        assertEquals(listOf("near", "far"), editor.state.strokes.map(RemoteFeedbackStroke::id))
    }

    @Test
    fun eraseCompletionIsRejectedAfterAnotherEditAndCannotCommitTwice() {
        val editor = RemoteReviewEditor()
        editor.openSnapshot(snapshot())
        editor.addStroke(
            RemoteFeedbackStrokeTool.PEN,
            0xFFD94747.toInt(),
            0.004f,
            listOf(point(0.1f, 0.2f), point(0.9f, 0.2f)),
            "erase-target",
        )
        val stalePlan = requireNotNull(
            editor.prepareErase(
                path = listOf(point(0.5f, 0.1f), point(0.5f, 0.3f)),
                radiusFraction = 0.02f,
            ),
        )
        val staleCandidates = editor.resolveErase(stalePlan)

        editor.addStroke(
            RemoteFeedbackStrokeTool.PEN,
            0xFFD94747.toInt(),
            0.004f,
            listOf(point(0.1f, 0.8f), point(0.9f, 0.8f)),
            "newer-edit",
        )

        assertTrue(editor.commitErase(stalePlan, staleCandidates).isEmpty())
        assertEquals(
            listOf("erase-target", "newer-edit"),
            editor.state.strokes.map(RemoteFeedbackStroke::id),
        )

        val freshPlan = requireNotNull(
            editor.prepareErase(
                path = listOf(point(0.5f, 0.1f), point(0.5f, 0.3f)),
                radiusFraction = 0.02f,
            ),
        )
        val freshCandidates = editor.resolveErase(freshPlan)
        assertEquals(setOf("erase-target"), editor.commitErase(freshPlan, freshCandidates))
        assertTrue(editor.commitErase(freshPlan, freshCandidates).isEmpty())
        assertEquals(listOf("newer-edit"), editor.state.strokes.map(RemoteFeedbackStroke::id))
    }

    @Test
    fun eraseFromEarlierVisitCannotCommitAfterReopeningIdenticalSnapshotAndRevision() {
        val editor = RemoteReviewEditor()
        val initial = feedbackWithSingleStroke()
        editor.openSnapshot(snapshot(), initialFeedback = initial)
        val oldPlan = requireNotNull(
            editor.prepareErase(
                path = listOf(point(0.5f, 0.1f), point(0.5f, 0.3f)),
                radiusFraction = 0.02f,
            ),
        )
        val oldCandidates = editor.resolveErase(oldPlan)

        assertTrue(editor.clearSnapshot(discardUnpublishedChanges = true))
        assertEquals(
            RemoteSnapshotOpenResult.OPENED,
            editor.openSnapshot(snapshot(), initialFeedback = initial),
        )

        assertTrue(editor.commitErase(oldPlan, oldCandidates).isEmpty())
        assertEquals(listOf("same-revision-stroke"), editor.state.strokes.map(RemoteFeedbackStroke::id))
    }

    @Test
    fun differentSnapshotCannotDiscardUnpublishedTeacherWorkSilently() {
        val editor = RemoteReviewEditor()
        editor.openSnapshot(snapshot())
        editor.addStroke(
            RemoteFeedbackStrokeTool.PEN,
            0xFFD94747.toInt(),
            0.004f,
            listOf(point(0.2f, 0.2f)),
            "keep-me",
        )

        val result = editor.openSnapshot(
            snapshot().copy(transferId = "student-transfer-2", pageToken = "other-page", pageNumber = 4),
        )

        assertEquals(RemoteSnapshotOpenResult.REJECTED_UNPUBLISHED_CHANGES, result)
        assertEquals("student-transfer-1", editor.state.snapshot?.transferId)
        assertEquals(listOf("keep-me"), editor.state.strokes.map(RemoteFeedbackStroke::id))
    }

    @Test
    fun erasingAllPreviouslyPublishedInkCreatesIntentionalEmptyPayload() {
        val editor = RemoteReviewEditor()
        editor.openSnapshot(snapshot())
        editor.addStroke(
            RemoteFeedbackStrokeTool.PEN,
            0xFFD94747.toInt(),
            0.004f,
            listOf(point(0.2f, 0.2f), point(0.8f, 0.2f)),
            "remove-later",
        )
        val first = requireNotNull(editor.buildFeedback("first", 100L))
        editor.acknowledgePublished(first)

        editor.erase(
            path = listOf(point(0.1f, 0.2f), point(0.9f, 0.2f)),
            radiusFraction = 0.03f,
        )
        val clear = editor.buildFeedback("clear", 200L)

        assertNotNull(clear)
        assertTrue(clear!!.strokes.isEmpty())
    }

    @Test
    fun acknowledgingOlderPayloadDoesNotHideNewerUnsavedEdit() {
        val editor = RemoteReviewEditor()
        editor.openSnapshot(snapshot())
        editor.addStroke(
            RemoteFeedbackStrokeTool.PEN,
            0xFFD94747.toInt(),
            0.004f,
            listOf(point(0.1f, 0.1f)),
            "first",
        )
        val firstPayload = requireNotNull(editor.buildFeedback("first-payload", 100L))
        editor.addStroke(
            RemoteFeedbackStrokeTool.PEN,
            0xFFD94747.toInt(),
            0.004f,
            listOf(point(0.2f, 0.2f)),
            "second",
        )

        assertTrue(editor.acknowledgePublished(firstPayload))
        assertTrue(editor.state.hasUnpublishedChanges)
        assertEquals(2, editor.buildFeedback("latest", 200L)?.strokes?.size)
    }

    @Test
    fun clearingSnapshotRequiresExplicitDiscardWhenTeacherWorkIsDirty() {
        val editor = RemoteReviewEditor()
        editor.openSnapshot(snapshot())
        editor.addStroke(
            RemoteFeedbackStrokeTool.PEN,
            0xFFD94747.toInt(),
            0.004f,
            listOf(point(0.1f, 0.1f)),
            "draft",
        )

        assertFalse(editor.clearSnapshot())
        assertEquals("student-transfer-1", editor.state.snapshot?.transferId)
        assertTrue(editor.clearSnapshot(discardUnpublishedChanges = true))
        assertNull(editor.state.snapshot)
        assertFalse(editor.state.hasUnpublishedChanges)
    }

    private fun snapshot() = RemotePageSnapshotRef(
        transferId = "student-transfer-1",
        pageToken = "opaque-book-page",
        bookFingerprint = "book-sha256",
        pageNumber = 3,
        studentRevision = 7L,
        imageWidthPx = 900,
        imageHeightPx = 1_200,
        receivedAtEpochMillis = 10L,
    )

    private fun feedbackWithSingleStroke() = RemoteTeacherFeedback(
        feedbackId = "existing-feedback",
        sourceTransferId = "student-transfer-1",
        pageToken = "opaque-book-page",
        bookFingerprint = "book-sha256",
        pageNumber = 3,
        basedOnStudentRevision = 7L,
        feedbackRevision = 11L,
        strokes = listOf(
            RemoteFeedbackStroke(
                id = "same-revision-stroke",
                tool = RemoteFeedbackStrokeTool.PEN,
                colorArgb = 0xFFD94747.toInt(),
                widthFraction = 0.004f,
                points = listOf(point(0.1f, 0.2f), point(0.9f, 0.2f)),
            ),
        ),
        createdAtEpochMillis = 9L,
    )

    private fun point(x: Float, y: Float) = RemoteNormalizedPoint(x, y)
}
