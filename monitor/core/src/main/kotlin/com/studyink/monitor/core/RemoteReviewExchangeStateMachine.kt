package com.studyink.monitor.core

/** Minimum persisted metadata needed to validate later feedback and choose a latest snapshot. */
data class RemoteSnapshotCursor(
    val reference: SnapshotReference,
    val createdAtEpochMs: Long,
)

/** Each feedback document replaces the complete teacher layer for one opaque page token. */
data class RemoteFeedbackCursor(
    val transferId: String,
    val pageToken: String,
    val feedbackRevision: Long,
    val sourceSnapshotTransferId: String,
    val createdAtEpochMs: Long,
)

/**
 * Read-only durable state queried while planning an inbox transaction. Implement this over the
 * inbox/receipt database rather than process memory.
 */
interface RemoteReviewStateView {
    fun isTransferCommitted(transferId: String): Boolean
    fun snapshotByTransferId(transferId: String): RemoteSnapshotCursor?
    fun latestSnapshot(pageToken: String): RemoteSnapshotCursor?
    fun latestFeedback(pageToken: String): RemoteFeedbackCursor?
}

enum class RemoteReviewIncomingAction {
    APPLY_PAGE_SNAPSHOT,
    APPLY_TEACHER_FEEDBACK,
    COMPLETE_OUTBOX,
    IGNORE_DUPLICATE,
    IGNORE_SUPERSEDED,
    DEFER_MISSING_SOURCE,
    REJECT,
}

/**
 * Instructions for an ACK to enqueue only after the incoming artifact and [commitMutations] have
 * committed successfully. ACK envelopes never produce another ACK.
 */
data class RemoteReviewAckDirective(
    val acknowledgedTransferId: String,
    val disposition: RemoteReviewAckDisposition,
    val detailCode: String? = null,
) {
    fun toEnvelope(transferId: String, createdAtEpochMs: Long): RemoteReviewAckEnvelope =
        RemoteReviewAckEnvelope(
            transferId = transferId,
            createdAtEpochMs = createdAtEpochMs,
            acknowledgedTransferId = acknowledgedTransferId,
            disposition = disposition,
            detailCode = detailCode,
        )
}

sealed interface RemoteReviewStateMutation {
    data class RecordCommittedTransfer(val transferId: String) : RemoteReviewStateMutation
    data class SetLatestSnapshot(val cursor: RemoteSnapshotCursor) : RemoteReviewStateMutation
    data class SetLatestFeedback(val cursor: RemoteFeedbackCursor) : RemoteReviewStateMutation

    /** The outbox decides whether a rejected record is deleted or retained for diagnostics. */
    data class SettleOutboxTransfer(
        val transferId: String,
        val disposition: RemoteReviewAckDisposition,
        val detailCode: String?,
    ) : RemoteReviewStateMutation
}

data class RemoteReviewIncomingPlan(
    val action: RemoteReviewIncomingAction,
    /** Apply these atomically with importing the page/teacher layer, never before it. */
    val commitMutations: List<RemoteReviewStateMutation> = emptyList(),
    val ackAfterCommit: RemoteReviewAckDirective? = null,
    /** Feedback is still applicable, but its student background is no longer the latest one. */
    val feedbackUsesOlderSnapshot: Boolean = false,
)

enum class RemoteReviewOutboxStatus {
    PENDING,
    IN_FLIGHT,
    AWAITING_ACK,
}

data class RemoteReviewOutboxEntryView(
    val envelope: RemoteReviewEnvelope,
    val status: RemoteReviewOutboxStatus,
)

enum class RemoteReviewOutboxAction {
    APPEND,
    REPLACE_PENDING,
    KEEP_EXISTING,
}

data class RemoteReviewOutboxPlan(
    val action: RemoteReviewOutboxAction,
    val coalesceKey: String?,
    val replacedTransferId: String? = null,
)

/**
 * Pure policy layer for a durable inbox/outbox.
 *
 * Recommended inbox order: persist encrypted Telegram bytes -> decode/verify -> call [planIncoming]
 * in a DB transaction -> import the artifact and commit returned mutations -> enqueue its ACK ->
 * advance Telegram offset. A crash before the commit therefore replays safely.
 */
object RemoteReviewExchangeStateMachine {
    fun planIncoming(
        envelope: RemoteReviewEnvelope,
        state: RemoteReviewStateView,
    ): RemoteReviewIncomingPlan {
        if (state.isTransferCommitted(envelope.transferId)) {
            return duplicatePlan(envelope)
        }

        return when (envelope) {
            is PageSnapshotEnvelope -> planSnapshot(envelope, state)
            is TeacherFeedbackEnvelope -> planFeedback(envelope, state)
            is RemoteReviewAckEnvelope -> RemoteReviewIncomingPlan(
                action = RemoteReviewIncomingAction.COMPLETE_OUTBOX,
                commitMutations = listOf(
                    RemoteReviewStateMutation.RecordCommittedTransfer(envelope.transferId),
                    RemoteReviewStateMutation.SettleOutboxTransfer(
                        transferId = envelope.acknowledgedTransferId,
                        disposition = envelope.disposition,
                        detailCode = envelope.detailCode,
                    ),
                ),
                ackAfterCommit = null,
            )
        }
    }

    /** A stable key suitable for a durable outbox's optional unique/coalescing column. */
    fun coalesceKey(envelope: RemoteReviewEnvelope): String? = when (envelope) {
        is PageSnapshotEnvelope -> "PAGE:${envelope.pageToken}"
        is TeacherFeedbackEnvelope -> "FEEDBACK:${envelope.sourceSnapshot.pageToken}"
        is RemoteReviewAckEnvelope -> null
    }

    /**
     * Coalesces only an unsent full-layer replacement. In-flight entries remain immutable and the
     * candidate is appended, which prevents a Telegram response from acknowledging the wrong bytes.
     */
    fun planOutbound(
        candidate: RemoteReviewEnvelope,
        existingForCoalesceKey: RemoteReviewOutboxEntryView?,
    ): RemoteReviewOutboxPlan {
        val key = coalesceKey(candidate)
            ?: return RemoteReviewOutboxPlan(RemoteReviewOutboxAction.APPEND, null)
        val existing = existingForCoalesceKey
            ?: return RemoteReviewOutboxPlan(RemoteReviewOutboxAction.APPEND, key)
        val existingKey = coalesceKey(existing.envelope)
        require(existingKey == key) {
            "existingForCoalesceKey does not match candidate key"
        }
        if (existing.envelope.transferId == candidate.transferId) {
            return RemoteReviewOutboxPlan(RemoteReviewOutboxAction.KEEP_EXISTING, key)
        }
        if (existing.status != RemoteReviewOutboxStatus.PENDING) {
            return RemoteReviewOutboxPlan(RemoteReviewOutboxAction.APPEND, key)
        }

        val candidateIsNewer = when {
            candidate is PageSnapshotEnvelope && existing.envelope is PageSnapshotEnvelope ->
                candidate.revision > existing.envelope.revision
            candidate is TeacherFeedbackEnvelope && existing.envelope is TeacherFeedbackEnvelope ->
                candidate.feedbackRevision > existing.envelope.feedbackRevision
            else -> false
        }
        return if (candidateIsNewer) {
            RemoteReviewOutboxPlan(
                action = RemoteReviewOutboxAction.REPLACE_PENDING,
                coalesceKey = key,
                replacedTransferId = existing.envelope.transferId,
            )
        } else {
            RemoteReviewOutboxPlan(RemoteReviewOutboxAction.KEEP_EXISTING, key)
        }
    }

    private fun planSnapshot(
        snapshot: PageSnapshotEnvelope,
        state: RemoteReviewStateView,
    ): RemoteReviewIncomingPlan {
        val latest = state.latestSnapshot(snapshot.pageToken)
        if (latest != null && snapshot.revision <= latest.reference.revision) {
            return RemoteReviewIncomingPlan(
                action = RemoteReviewIncomingAction.IGNORE_SUPERSEDED,
                commitMutations = listOf(
                    RemoteReviewStateMutation.RecordCommittedTransfer(snapshot.transferId),
                ),
                ackAfterCommit = RemoteReviewAckDirective(
                    acknowledgedTransferId = snapshot.transferId,
                    disposition = RemoteReviewAckDisposition.SUPERSEDED,
                    detailCode = "SNAPSHOT_REVISION_NOT_NEWER",
                ),
            )
        }

        val cursor = RemoteSnapshotCursor(
            reference = SnapshotReference(
                transferId = snapshot.transferId,
                pageToken = snapshot.pageToken,
                revision = snapshot.revision,
                dimensions = snapshot.dimensions,
            ),
            createdAtEpochMs = snapshot.createdAtEpochMs,
        )
        return RemoteReviewIncomingPlan(
            action = RemoteReviewIncomingAction.APPLY_PAGE_SNAPSHOT,
            commitMutations = listOf(
                RemoteReviewStateMutation.RecordCommittedTransfer(snapshot.transferId),
                RemoteReviewStateMutation.SetLatestSnapshot(cursor),
            ),
            ackAfterCommit = RemoteReviewAckDirective(
                acknowledgedTransferId = snapshot.transferId,
                disposition = RemoteReviewAckDisposition.APPLIED,
            ),
        )
    }

    private fun planFeedback(
        feedback: TeacherFeedbackEnvelope,
        state: RemoteReviewStateView,
    ): RemoteReviewIncomingPlan {
        val source = state.snapshotByTransferId(feedback.sourceSnapshot.transferId)
            ?: return RemoteReviewIncomingPlan(
                action = RemoteReviewIncomingAction.DEFER_MISSING_SOURCE,
            )
        if (source.reference != feedback.sourceSnapshot) {
            return RemoteReviewIncomingPlan(
                action = RemoteReviewIncomingAction.REJECT,
                commitMutations = listOf(
                    RemoteReviewStateMutation.RecordCommittedTransfer(feedback.transferId),
                ),
                ackAfterCommit = RemoteReviewAckDirective(
                    acknowledgedTransferId = feedback.transferId,
                    disposition = RemoteReviewAckDisposition.REJECTED,
                    detailCode = "SOURCE_SNAPSHOT_MISMATCH",
                ),
            )
        }

        val latestFeedback = state.latestFeedback(feedback.sourceSnapshot.pageToken)
        if (latestFeedback != null && feedback.feedbackRevision <= latestFeedback.feedbackRevision) {
            return RemoteReviewIncomingPlan(
                action = RemoteReviewIncomingAction.IGNORE_SUPERSEDED,
                commitMutations = listOf(
                    RemoteReviewStateMutation.RecordCommittedTransfer(feedback.transferId),
                ),
                ackAfterCommit = RemoteReviewAckDirective(
                    acknowledgedTransferId = feedback.transferId,
                    disposition = RemoteReviewAckDisposition.SUPERSEDED,
                    detailCode = "FEEDBACK_REVISION_NOT_NEWER",
                ),
            )
        }

        val latestSnapshot = state.latestSnapshot(feedback.sourceSnapshot.pageToken)
        val basedOnOlderSnapshot = latestSnapshot != null &&
            latestSnapshot.reference.transferId != feedback.sourceSnapshot.transferId
        val cursor = RemoteFeedbackCursor(
            transferId = feedback.transferId,
            pageToken = feedback.sourceSnapshot.pageToken,
            feedbackRevision = feedback.feedbackRevision,
            sourceSnapshotTransferId = feedback.sourceSnapshot.transferId,
            createdAtEpochMs = feedback.createdAtEpochMs,
        )
        return RemoteReviewIncomingPlan(
            action = RemoteReviewIncomingAction.APPLY_TEACHER_FEEDBACK,
            commitMutations = listOf(
                RemoteReviewStateMutation.RecordCommittedTransfer(feedback.transferId),
                RemoteReviewStateMutation.SetLatestFeedback(cursor),
            ),
            ackAfterCommit = RemoteReviewAckDirective(
                acknowledgedTransferId = feedback.transferId,
                disposition = RemoteReviewAckDisposition.APPLIED,
            ),
            feedbackUsesOlderSnapshot = basedOnOlderSnapshot,
        )
    }

    private fun duplicatePlan(envelope: RemoteReviewEnvelope): RemoteReviewIncomingPlan =
        RemoteReviewIncomingPlan(
            action = RemoteReviewIncomingAction.IGNORE_DUPLICATE,
            ackAfterCommit = if (envelope is RemoteReviewAckEnvelope) {
                null
            } else {
                RemoteReviewAckDirective(
                    acknowledgedTransferId = envelope.transferId,
                    disposition = RemoteReviewAckDisposition.DUPLICATE,
                )
            },
        )
}
