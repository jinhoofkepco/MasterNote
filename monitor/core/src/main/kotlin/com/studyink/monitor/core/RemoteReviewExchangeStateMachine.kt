package com.studyink.monitor.core

/** Minimum persisted metadata needed to validate later feedback and choose a latest snapshot. */
data class RemoteSnapshotCursor(
    val reference: SnapshotReference,
    val createdAtEpochMs: Long,
    /** Null for legacy snapshots and page-only captures that cannot be graded exactly. */
    val attemptNo: Int? = null,
    /** Null for the legacy v1 snapshot payload without the encrypted digest extension. */
    val studentInkDigest: String? = null,
) {
    init {
        checkProtocol(attemptNo == null || attemptNo > 0, "attemptNo") {
            "must be null or one-based"
        }
        studentInkDigest?.let { digest ->
            checkProtocol(GRADE_DIGEST.matches(digest), "studentInkDigest") {
                "must be exactly ${RemoteReviewLimits.SHA256_HEX_BYTES} lower-case hexadecimal characters"
            }
        }
    }
}

/** Each feedback document replaces the complete teacher layer for one opaque page token. */
data class RemoteFeedbackCursor(
    val transferId: String,
    val pageToken: String,
    val feedbackRevision: Long,
    val sourceSnapshotTransferId: String,
    val createdAtEpochMs: Long,
)

/** Exact student state that may authorize a later [RemoteGradeEnvelope]. */
data class RemoteGradeSourceCursor(
    val reference: SnapshotReference,
    val attemptNo: Int,
    val studentInkDigest: String,
    val createdAtEpochMs: Long,
) {
    init {
        checkProtocol(attemptNo > 0, "attemptNo") { "must be one-based" }
        checkProtocol(GRADE_DIGEST.matches(studentInkDigest), "studentInkDigest") {
            "must be exactly ${RemoteReviewLimits.SHA256_HEX_BYTES} lower-case hexadecimal characters"
        }
        checkProtocol(createdAtEpochMs >= 0L, "createdAtEpochMs") { "must not be negative" }
    }
}

/** Last committed state of one stable grade group. */
data class RemoteGradeCursor(
    val envelope: RemoteGradeEnvelope,
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

    /**
     * Defaults keep existing implementations source-compatible, but a missing cursor deliberately
     * defers rather than trusting a grade that cannot be tied to exact durable student state.
     */
    fun gradeSourceByTransferId(transferId: String): RemoteGradeSourceCursor? {
        val snapshot = snapshotByTransferId(transferId) ?: return null
        val attemptNo = snapshot.attemptNo ?: return null
        val studentInkDigest = snapshot.studentInkDigest ?: return null
        return RemoteGradeSourceCursor(
            reference = snapshot.reference,
            attemptNo = attemptNo,
            studentInkDigest = studentInkDigest,
            createdAtEpochMs = snapshot.createdAtEpochMs,
        )
    }

    /** Latest committed state for this exact page/attempt/stable grade group. */
    fun latestGrade(
        pageToken: String,
        attemptNo: Int,
        gradeGroupId: String,
    ): RemoteGradeCursor? = null
}

enum class RemoteReviewIncomingAction {
    APPLY_PAGE_SNAPSHOT,
    APPLY_TEACHER_FEEDBACK,
    APPLY_CHAT_MESSAGE,
    APPLY_REMOTE_GRADE,
    APPLY_PAGE_SYNC_MANIFEST,
    APPLY_PAGE_SYNC_REQUEST,
    APPLY_PAGE_ANNOTATION,
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

/** A semantic page ACK to enqueue only after the referenced annotation is durably applied. */
data class PageSyncAckDirective(
    val syncGeneration: Long,
    val sourceType: PageSyncAckSourceType,
    val sourceTransferId: String,
    val pageToken: String,
    val pageNumber: Int,
    val sourceRevision: Long,
    val disposition: PageSyncAckDisposition,
    val reasonCode: String? = null,
) {
    fun toEnvelope(transferId: String, createdAtEpochMs: Long): PageSyncAckEnvelope =
        PageSyncAckEnvelope(
            transferId = transferId,
            createdAtEpochMs = createdAtEpochMs,
            syncGeneration = syncGeneration,
            sourceType = sourceType,
            sourceTransferId = sourceTransferId,
            pageToken = pageToken,
            pageNumber = pageNumber,
            sourceRevision = sourceRevision,
            disposition = disposition,
            reasonCode = reasonCode,
        )
}

sealed interface RemoteReviewStateMutation {
    data class RecordCommittedTransfer(val transferId: String) : RemoteReviewStateMutation
    data class SetLatestSnapshot(val cursor: RemoteSnapshotCursor) : RemoteReviewStateMutation
    data class SetLatestFeedback(val cursor: RemoteFeedbackCursor) : RemoteReviewStateMutation
    data class SetLatestGrade(val cursor: RemoteGradeCursor) : RemoteReviewStateMutation

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
    /** Used instead of [ackAfterCommit] for PAGE_ANNOTATION exact-page semantic settlement. */
    val pageSyncAckAfterCommit: PageSyncAckDirective? = null,
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
            is ChatMessageEnvelope -> planPeerArtifact(
                envelope = envelope,
                action = RemoteReviewIncomingAction.APPLY_CHAT_MESSAGE,
            )
            is RemoteGradeEnvelope -> planGrade(envelope, state)
            is PageSyncManifestEnvelope -> planPeerArtifact(
                envelope = envelope,
                action = RemoteReviewIncomingAction.APPLY_PAGE_SYNC_MANIFEST,
            )
            is PageSyncRequestEnvelope -> planPeerArtifact(
                envelope = envelope,
                action = RemoteReviewIncomingAction.APPLY_PAGE_SYNC_REQUEST,
            )
            is PageAnnotationEnvelope -> planPageAnnotation(envelope)
            is PageSyncAckEnvelope -> RemoteReviewIncomingPlan(
                action = RemoteReviewIncomingAction.COMPLETE_OUTBOX,
                commitMutations = listOf(
                    RemoteReviewStateMutation.RecordCommittedTransfer(envelope.transferId),
                    RemoteReviewStateMutation.SettleOutboxTransfer(
                        transferId = envelope.sourceTransferId,
                        disposition = envelope.disposition.asRemoteReviewDisposition(),
                        detailCode = envelope.reasonCode,
                    ),
                ),
            )
        }
    }

    /** A stable key suitable for a durable outbox's optional unique/coalescing column. */
    fun coalesceKey(envelope: RemoteReviewEnvelope): String? = when (envelope) {
        is PageSnapshotEnvelope -> "PAGE:${envelope.pageToken}"
        is TeacherFeedbackEnvelope -> "FEEDBACK:${envelope.sourceSnapshot.pageToken}"
        is RemoteReviewAckEnvelope -> null
        is ChatMessageEnvelope -> null
        is RemoteGradeEnvelope -> "GRADE:${envelope.sourceSnapshot.pageToken}:" +
            "${envelope.attemptNo}:${envelope.gradeGroupId}"
        is PageSyncManifestEnvelope -> null
        is PageSyncRequestEnvelope -> null
        is PageAnnotationEnvelope -> null
        is PageSyncAckEnvelope -> null
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
            candidate is RemoteGradeEnvelope && existing.envelope is RemoteGradeEnvelope ->
                candidate.syncRevision > existing.envelope.syncRevision
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
        val upgradesLegacyDigestAtSameRevision = latest != null &&
            snapshot.revision == latest.reference.revision &&
            latest.studentInkDigest == null && snapshot.studentInkDigest != null &&
            latest.attemptNo == snapshot.attemptNo &&
            latest.reference.dimensions == snapshot.dimensions
        if (latest != null && snapshot.revision <= latest.reference.revision &&
            !upgradesLegacyDigestAtSameRevision
        ) {
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
            attemptNo = snapshot.attemptNo,
            studentInkDigest = snapshot.studentInkDigest,
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

    private fun planPeerArtifact(
        envelope: RemoteReviewEnvelope,
        action: RemoteReviewIncomingAction,
    ): RemoteReviewIncomingPlan = RemoteReviewIncomingPlan(
        action = action,
        commitMutations = listOf(
            RemoteReviewStateMutation.RecordCommittedTransfer(envelope.transferId),
        ),
        ackAfterCommit = RemoteReviewAckDirective(
            acknowledgedTransferId = envelope.transferId,
            disposition = RemoteReviewAckDisposition.APPLIED,
        ),
    )

    private fun planPageAnnotation(
        annotation: PageAnnotationEnvelope,
    ): RemoteReviewIncomingPlan = RemoteReviewIncomingPlan(
        action = RemoteReviewIncomingAction.APPLY_PAGE_ANNOTATION,
        commitMutations = listOf(
            RemoteReviewStateMutation.RecordCommittedTransfer(annotation.transferId),
        ),
        pageSyncAckAfterCommit = annotation.pageSyncAckDirective(
            PageSyncAckDisposition.APPLIED,
        ),
    )

    private fun planGrade(
        grade: RemoteGradeEnvelope,
        state: RemoteReviewStateView,
    ): RemoteReviewIncomingPlan {
        val source = state.gradeSourceByTransferId(grade.sourceSnapshot.transferId)
            ?: return RemoteReviewIncomingPlan(
                action = RemoteReviewIncomingAction.DEFER_MISSING_SOURCE,
            )
        if (!grade.matchesExactly(source)) {
            return rejectedGradePlan(grade, "GRADE_SOURCE_MISMATCH")
        }

        val latest = state.latestGrade(
            pageToken = grade.sourceSnapshot.pageToken,
            attemptNo = grade.attemptNo,
            gradeGroupId = grade.gradeGroupId,
        )?.envelope
        if (latest != null) {
            require(
                latest.sourceSnapshot.pageToken == grade.sourceSnapshot.pageToken &&
                    latest.attemptNo == grade.attemptNo &&
                    latest.gradeGroupId == grade.gradeGroupId,
            ) { "latestGrade returned a cursor for another grade group" }
            if (grade.syncRevision < latest.syncRevision) {
                return supersededGradePlan(grade)
            }
            if (grade.syncRevision == latest.syncRevision) {
                return if (grade.hasSameCommittedState(latest)) {
                    supersededGradePlan(grade)
                } else {
                    rejectedGradePlan(grade, "GRADE_REVISION_CONFLICT")
                }
            }
        }

        return RemoteReviewIncomingPlan(
            action = RemoteReviewIncomingAction.APPLY_REMOTE_GRADE,
            commitMutations = listOf(
                RemoteReviewStateMutation.RecordCommittedTransfer(grade.transferId),
                RemoteReviewStateMutation.SetLatestGrade(RemoteGradeCursor(grade)),
            ),
            ackAfterCommit = RemoteReviewAckDirective(
                acknowledgedTransferId = grade.transferId,
                disposition = RemoteReviewAckDisposition.APPLIED,
            ),
        )
    }

    private fun RemoteGradeEnvelope.matchesExactly(source: RemoteGradeSourceCursor): Boolean =
        sourceSnapshot == source.reference &&
            attemptNo == source.attemptNo &&
            studentInkDigest == source.studentInkDigest

    /** Event IDs/timestamps may change on a safe re-envelope; committed grade state may not. */
    private fun RemoteGradeEnvelope.hasSameCommittedState(other: RemoteGradeEnvelope): Boolean =
        sourceSnapshot == other.sourceSnapshot &&
            attemptNo == other.attemptNo &&
            studentInkDigest == other.studentInkDigest &&
            gradeGroupId == other.gradeGroupId &&
            syncRevision == other.syncRevision &&
            lastModifiedByDeviceId == other.lastModifiedByDeviceId &&
            anchor == other.anchor &&
            score == other.score &&
            maximumScore == other.maximumScore

    private fun supersededGradePlan(grade: RemoteGradeEnvelope): RemoteReviewIncomingPlan =
        RemoteReviewIncomingPlan(
            action = RemoteReviewIncomingAction.IGNORE_SUPERSEDED,
            commitMutations = listOf(
                RemoteReviewStateMutation.RecordCommittedTransfer(grade.transferId),
            ),
            ackAfterCommit = RemoteReviewAckDirective(
                acknowledgedTransferId = grade.transferId,
                disposition = RemoteReviewAckDisposition.SUPERSEDED,
                detailCode = "GRADE_REVISION_NOT_NEWER",
            ),
        )

    private fun rejectedGradePlan(
        grade: RemoteGradeEnvelope,
        detailCode: String,
    ): RemoteReviewIncomingPlan = RemoteReviewIncomingPlan(
        action = RemoteReviewIncomingAction.REJECT,
        commitMutations = listOf(
            RemoteReviewStateMutation.RecordCommittedTransfer(grade.transferId),
        ),
        ackAfterCommit = RemoteReviewAckDirective(
            acknowledgedTransferId = grade.transferId,
            disposition = RemoteReviewAckDisposition.REJECTED,
            detailCode = detailCode,
        ),
    )

    private fun duplicatePlan(envelope: RemoteReviewEnvelope): RemoteReviewIncomingPlan =
        RemoteReviewIncomingPlan(
            action = RemoteReviewIncomingAction.IGNORE_DUPLICATE,
            ackAfterCommit = if (
                envelope is RemoteReviewAckEnvelope ||
                envelope is PageSyncAckEnvelope ||
                envelope is PageAnnotationEnvelope
            ) {
                null
            } else {
                RemoteReviewAckDirective(
                    acknowledgedTransferId = envelope.transferId,
                    disposition = RemoteReviewAckDisposition.DUPLICATE,
                )
            },
            pageSyncAckAfterCommit = (envelope as? PageAnnotationEnvelope)?.pageSyncAckDirective(
                PageSyncAckDisposition.DUPLICATE,
            ),
        )

    private fun PageAnnotationEnvelope.pageSyncAckDirective(
        disposition: PageSyncAckDisposition,
    ): PageSyncAckDirective = PageSyncAckDirective(
        syncGeneration = syncGeneration,
        sourceType = PageSyncAckSourceType.ANNOTATION,
        sourceTransferId = transferId,
        pageToken = pageToken,
        pageNumber = pageNumber,
        sourceRevision = sourceRevision,
        disposition = disposition,
    )

    private fun PageSyncAckDisposition.asRemoteReviewDisposition(): RemoteReviewAckDisposition =
        when (this) {
            PageSyncAckDisposition.APPLIED -> RemoteReviewAckDisposition.APPLIED
            PageSyncAckDisposition.DUPLICATE -> RemoteReviewAckDisposition.DUPLICATE
            PageSyncAckDisposition.REJECTED -> RemoteReviewAckDisposition.REJECTED
        }
}

private val GRADE_DIGEST = Regex("[0-9a-f]{${RemoteReviewLimits.SHA256_HEX_BYTES}}")
