package com.studyink.monitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteReviewExchangeStateMachineTest {
    @Test fun newerSnapshotAppliesAndCommitsReceiptAndLatestCursorBeforeAck() {
        val snapshot = pageSnapshot(revision = 8L)

        val plan = RemoteReviewExchangeStateMachine.planIncoming(snapshot, FakeState())

        assertEquals(RemoteReviewIncomingAction.APPLY_PAGE_SNAPSHOT, plan.action)
        assertEquals(RemoteReviewAckDisposition.APPLIED, plan.ackAfterCommit?.disposition)
        assertEquals(snapshot.transferId, plan.ackAfterCommit?.acknowledgedTransferId)
        assertTrue(
            plan.commitMutations.any {
                it == RemoteReviewStateMutation.RecordCommittedTransfer(snapshot.transferId)
            },
        )
        val cursor = plan.commitMutations.filterIsInstance<
            RemoteReviewStateMutation.SetLatestSnapshot
            >().single().cursor
        assertEquals(8L, cursor.reference.revision)
    }

    @Test fun duplicateTransferDoesNotImportAgainButReturnsDuplicateAck() {
        val snapshot = pageSnapshot()
        val state = FakeState(committed = setOf(snapshot.transferId))

        val plan = RemoteReviewExchangeStateMachine.planIncoming(snapshot, state)

        assertEquals(RemoteReviewIncomingAction.IGNORE_DUPLICATE, plan.action)
        assertTrue(plan.commitMutations.isEmpty())
        assertEquals(RemoteReviewAckDisposition.DUPLICATE, plan.ackAfterCommit?.disposition)
    }

    @Test fun olderSnapshotIsRecordedAsSupersededAndCannotReplaceLatest() {
        val incoming = pageSnapshot(transferId = "snapshot_transfer_0007", revision = 7L)
        val latest = snapshotCursor(transferId = "snapshot_transfer_0008", revision = 8L)
        val state = FakeState(latestSnapshots = mapOf(PAGE_TOKEN to latest))

        val plan = RemoteReviewExchangeStateMachine.planIncoming(incoming, state)

        assertEquals(RemoteReviewIncomingAction.IGNORE_SUPERSEDED, plan.action)
        assertEquals(RemoteReviewAckDisposition.SUPERSEDED, plan.ackAfterCommit?.disposition)
        assertEquals("SNAPSHOT_REVISION_NOT_NEWER", plan.ackAfterCommit?.detailCode)
        assertFalse(plan.commitMutations.any { it is RemoteReviewStateMutation.SetLatestSnapshot })
    }

    @Test fun feedbackWaitsWithoutReceiptOrAckUntilSourceSnapshotExists() {
        val feedback = feedback(source = snapshotReference())

        val plan = RemoteReviewExchangeStateMachine.planIncoming(feedback, FakeState())

        assertEquals(RemoteReviewIncomingAction.DEFER_MISSING_SOURCE, plan.action)
        assertTrue(plan.commitMutations.isEmpty())
        assertNull(plan.ackAfterCommit)
    }

    @Test fun feedbackForKnownOlderSnapshotAppliesAsSeparateLayerWithWarning() {
        val source = snapshotReference(transferId = "snapshot_transfer_0007", revision = 7L)
        val latest = snapshotCursor(transferId = "snapshot_transfer_0008", revision = 8L)
        val state = FakeState(
            snapshotsById = mapOf(source.transferId to RemoteSnapshotCursor(source, 700L)),
            latestSnapshots = mapOf(PAGE_TOKEN to latest),
        )

        val plan = RemoteReviewExchangeStateMachine.planIncoming(
            feedback(source = source, feedbackRevision = 2L),
            state,
        )

        assertEquals(RemoteReviewIncomingAction.APPLY_TEACHER_FEEDBACK, plan.action)
        assertTrue(plan.feedbackUsesOlderSnapshot)
        assertEquals(RemoteReviewAckDisposition.APPLIED, plan.ackAfterCommit?.disposition)
        val cursor = plan.commitMutations.filterIsInstance<
            RemoteReviewStateMutation.SetLatestFeedback
            >().single().cursor
        assertEquals(2L, cursor.feedbackRevision)
    }

    @Test fun staleFullFeedbackLayerIsSuperseded() {
        val source = snapshotReference()
        val state = FakeState(
            snapshotsById = mapOf(source.transferId to RemoteSnapshotCursor(source, 800L)),
            latestSnapshots = mapOf(PAGE_TOKEN to RemoteSnapshotCursor(source, 800L)),
            latestFeedback = mapOf(
                PAGE_TOKEN to RemoteFeedbackCursor(
                    transferId = "feedback_transfer_0004",
                    pageToken = PAGE_TOKEN,
                    feedbackRevision = 4L,
                    sourceSnapshotTransferId = source.transferId,
                    createdAtEpochMs = 900L,
                ),
            ),
        )

        val plan = RemoteReviewExchangeStateMachine.planIncoming(
            feedback(source = source, feedbackRevision = 3L),
            state,
        )

        assertEquals(RemoteReviewIncomingAction.IGNORE_SUPERSEDED, plan.action)
        assertEquals(RemoteReviewAckDisposition.SUPERSEDED, plan.ackAfterCommit?.disposition)
        assertEquals("FEEDBACK_REVISION_NOT_NEWER", plan.ackAfterCommit?.detailCode)
        assertFalse(plan.commitMutations.any { it is RemoteReviewStateMutation.SetLatestFeedback })
    }

    @Test fun sourceMetadataMismatchIsRejectedInsteadOfMisaligned() {
        val stored = snapshotReference(revision = 8L)
        val forged = stored.copy(revision = 9L)
        val state = FakeState(
            snapshotsById = mapOf(stored.transferId to RemoteSnapshotCursor(stored, 800L)),
        )

        val plan = RemoteReviewExchangeStateMachine.planIncoming(feedback(source = forged), state)

        assertEquals(RemoteReviewIncomingAction.REJECT, plan.action)
        assertEquals(RemoteReviewAckDisposition.REJECTED, plan.ackAfterCommit?.disposition)
        assertEquals("SOURCE_SNAPSHOT_MISMATCH", plan.ackAfterCommit?.detailCode)
    }

    @Test fun ackSettlesOutboxAndNeverCreatesAnAckLoop() {
        val ack = RemoteReviewAckEnvelope(
            transferId = "ack_transfer_00000001",
            createdAtEpochMs = 2_000L,
            acknowledgedTransferId = "snapshot_transfer_0008",
            disposition = RemoteReviewAckDisposition.APPLIED,
        )

        val plan = RemoteReviewExchangeStateMachine.planIncoming(ack, FakeState())

        assertEquals(RemoteReviewIncomingAction.COMPLETE_OUTBOX, plan.action)
        assertNull(plan.ackAfterCommit)
        val settlement = plan.commitMutations.filterIsInstance<
            RemoteReviewStateMutation.SettleOutboxTransfer
            >().single()
        assertEquals("snapshot_transfer_0008", settlement.transferId)
    }

    @Test fun pendingSnapshotsAndFeedbackUseLatestRevisionWhileInflightBytesStayImmutable() {
        val oldSnapshot = pageSnapshot(transferId = "snapshot_transfer_0007", revision = 7L)
        val newSnapshot = pageSnapshot(transferId = "snapshot_transfer_0008", revision = 8L)
        val replacement = RemoteReviewExchangeStateMachine.planOutbound(
            candidate = newSnapshot,
            existingForCoalesceKey = RemoteReviewOutboxEntryView(
                oldSnapshot,
                RemoteReviewOutboxStatus.PENDING,
            ),
        )
        assertEquals(RemoteReviewOutboxAction.REPLACE_PENDING, replacement.action)
        assertEquals(oldSnapshot.transferId, replacement.replacedTransferId)

        val append = RemoteReviewExchangeStateMachine.planOutbound(
            candidate = newSnapshot,
            existingForCoalesceKey = RemoteReviewOutboxEntryView(
                oldSnapshot,
                RemoteReviewOutboxStatus.IN_FLIGHT,
            ),
        )
        assertEquals(RemoteReviewOutboxAction.APPEND, append.action)

        val source = snapshotReference()
        val oldFeedback = feedback(
            transferId = "feedback_transfer_0002",
            source = source,
            feedbackRevision = 2L,
        )
        val newFeedback = feedback(
            transferId = "feedback_transfer_0003",
            source = source,
            feedbackRevision = 3L,
        )
        val feedbackPlan = RemoteReviewExchangeStateMachine.planOutbound(
            candidate = newFeedback,
            existingForCoalesceKey = RemoteReviewOutboxEntryView(
                oldFeedback,
                RemoteReviewOutboxStatus.PENDING,
            ),
        )
        assertEquals(RemoteReviewOutboxAction.REPLACE_PENDING, feedbackPlan.action)
    }

    private fun pageSnapshot(
        transferId: String = "snapshot_transfer_0008",
        revision: Long = 8L,
    ) = PageSnapshotEnvelope(
        transferId = transferId,
        createdAtEpochMs = 800L,
        pageToken = PAGE_TOKEN,
        workbookLabel = "수학 문제집",
        pageNumber = 37,
        attemptNo = 1,
        studentLabel = "학생 A",
        revision = revision,
        dimensions = DIMENSIONS,
        imageFormat = SnapshotImageFormat.JPEG,
        renderedPageBytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0),
    )

    private fun snapshotReference(
        transferId: String = "snapshot_transfer_0008",
        revision: Long = 8L,
    ) = SnapshotReference(transferId, PAGE_TOKEN, revision, DIMENSIONS)

    private fun snapshotCursor(
        transferId: String,
        revision: Long,
    ) = RemoteSnapshotCursor(snapshotReference(transferId, revision), createdAtEpochMs = revision * 100L)

    private fun feedback(
        transferId: String = "feedback_transfer_0003",
        source: SnapshotReference,
        feedbackRevision: Long = 3L,
    ) = TeacherFeedbackEnvelope(
        transferId = transferId,
        createdAtEpochMs = 1_000L,
        sourceSnapshot = source,
        feedbackRevision = feedbackRevision,
        strokes = listOf(
            NormalizedTeacherStroke(
                strokeId = "teacher_stroke_0001",
                tool = TeacherInkTool.PEN,
                argb = 0xffd32f2f.toInt(),
                widthNormalized = 0.004f,
                points = listOf(NormalizedTeacherPoint(0.2f, 0.3f)),
            ),
        ),
    )

    private class FakeState(
        private val committed: Set<String> = emptySet(),
        private val snapshotsById: Map<String, RemoteSnapshotCursor> = emptyMap(),
        private val latestSnapshots: Map<String, RemoteSnapshotCursor> = emptyMap(),
        private val latestFeedback: Map<String, RemoteFeedbackCursor> = emptyMap(),
    ) : RemoteReviewStateView {
        override fun isTransferCommitted(transferId: String): Boolean = transferId in committed
        override fun snapshotByTransferId(transferId: String): RemoteSnapshotCursor? =
            snapshotsById[transferId]
        override fun latestSnapshot(pageToken: String): RemoteSnapshotCursor? =
            latestSnapshots[pageToken]
        override fun latestFeedback(pageToken: String): RemoteFeedbackCursor? =
            latestFeedback[pageToken]
    }

    private companion object {
        const val PAGE_TOKEN = "page_token_00000001"
        val DIMENSIONS = ReviewCanvasDimensions(1_600, 2_000)
    }
}
