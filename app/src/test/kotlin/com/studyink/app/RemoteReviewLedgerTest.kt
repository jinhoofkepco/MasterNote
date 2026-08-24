package com.studyink.app

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteReviewLedgerTest {
    @Test
    fun outgoingMappingAndAppliedFeedbackSurviveRestart() {
        val root = createTempDirectory("remote-review-ledger").toFile()
        try {
            val outgoing = outgoing("snapshot_0001", revision = 7L)
            RemoteReviewLedger(root).apply {
                recordOutgoing(outgoing)
                assertEquals(RemoteFeedbackDecision.APPLY, feedbackDecision("feedback_0001", outgoing.pageToken, 1L))
                recordFeedbackApplied("feedback_0001", outgoing.pageToken, 1L, 100L)
            }

            RemoteReviewLedger(root).apply {
                assertEquals(outgoing, outgoing(outgoing.transferId))
                assertEquals(
                    RemoteFeedbackDecision.DUPLICATE,
                    feedbackDecision("feedback_0001", outgoing.pageToken, 1L),
                )
                assertEquals(
                    RemoteFeedbackDecision.SUPERSEDED,
                    feedbackDecision("feedback_0002", outgoing.pageToken, 1L),
                )
                assertEquals(
                    RemoteFeedbackDecision.APPLY,
                    feedbackDecision("feedback_0003", outgoing.pageToken, 2L),
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun incomingImageIsCommittedBeforeItBecomesVisible() {
        val root = createTempDirectory("remote-review-ledger").toFile()
        val source = File(root, "source.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        try {
            val ledger = RemoteReviewLedger(File(root, "state"))
            val committed = ledger.storeIncoming(
                IncomingRemoteSnapshot(
                    transferId = "snapshot_0002",
                    pageToken = "page_token_0002",
                    workbookLabel = "수학 문제집",
                    studentLabel = "학생",
                    pageNumber = 12,
                    attemptNo = 1,
                    studentRevision = 9L,
                    widthPx = 1_400,
                    heightPx = 1_980,
                    receivedAtEpochMs = 200L,
                    imagePath = "pending",
                ),
                source,
                maximumBytes = 10L,
            )

            assertTrue(committed.imageFile.isFile)
            assertEquals(source.readBytes().toList(), committed.imageFile.readBytes().toList())
            assertEquals(committed, RemoteReviewLedger(File(root, "state")).incoming(committed.transferId))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun compactionKeepsLatestMappingsAndFeedbackGuard() {
        val root = createTempDirectory("remote-review-ledger-compact").toFile()
        try {
            val ledger = RemoteReviewLedger(root, compactAfterRecords = 4)
            ledger.recordFeedbackApplied("feedback_0001", "page_token_0001", 7L, 100L)
            repeat(12) { revision ->
                ledger.recordOutgoing(outgoing("snapshot_0001", revision = revision.toLong()))
            }

            val journal = File(root, "review-ledger.v1")
            assertTrue(journal.readLines().size < 12)
            RemoteReviewLedger(root, compactAfterRecords = 4).apply {
                assertEquals(11L, outgoing("snapshot_0001")?.studentRevision)
                assertEquals(
                    RemoteFeedbackDecision.DUPLICATE,
                    feedbackDecision("feedback_0001", "page_token_0001", 7L),
                )
                assertEquals(
                    RemoteFeedbackDecision.SUPERSEDED,
                    feedbackDecision("feedback_0002", "page_token_0001", 7L),
                )
                assertEquals(
                    RemoteFeedbackDecision.APPLY,
                    feedbackDecision("feedback_0003", "page_token_0001", 8L),
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun dataRootReplacementDropsStudentBindingsButKeepsTeacherInbox() {
        val root = createTempDirectory("remote-review-ledger-restore").toFile()
        val source = File(root, "source.jpg").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        try {
            val ledger = RemoteReviewLedger(File(root, "state"))
            val outgoing = outgoing("snapshot_0003", revision = 4L)
            ledger.recordOutgoing(outgoing)
            ledger.recordFeedbackApplied("feedback_0003", outgoing.pageToken, 2L, 300L)
            val incoming = ledger.storeIncoming(
                IncomingRemoteSnapshot(
                    transferId = "snapshot_0004",
                    pageToken = "page_token_0004",
                    workbookLabel = "받은 문제집",
                    studentLabel = "학생",
                    pageNumber = 2,
                    attemptNo = 1,
                    studentRevision = 1L,
                    widthPx = 100,
                    heightPx = 200,
                    receivedAtEpochMs = 400L,
                    imagePath = "pending",
                ),
                source,
                maximumBytes = 10L,
            )

            ledger.clearStudentExchangeState()
            RemoteReviewLedger(File(root, "state")).apply {
                assertEquals(null, outgoing(outgoing.transferId))
                assertEquals(
                    RemoteFeedbackDecision.APPLY,
                    feedbackDecision("feedback_0003", outgoing.pageToken, 2L),
                )
                assertEquals(incoming, incoming(incoming.transferId))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun restartDeletesOnlyUnreferencedSnapshotArtifacts() {
        val root = createTempDirectory("remote-review-ledger-orphans").toFile()
        val state = File(root, "state")
        val source = File(root, "source.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            val committed = RemoteReviewLedger(state).storeIncoming(
                IncomingRemoteSnapshot(
                    transferId = "snapshot_keep",
                    pageToken = "page_token_keep",
                    workbookLabel = "문제집",
                    studentLabel = "학생",
                    pageNumber = 1,
                    attemptNo = 1,
                    studentRevision = 1L,
                    widthPx = 100,
                    heightPx = 200,
                    receivedAtEpochMs = 1L,
                    imagePath = "pending",
                ),
                source,
                maximumBytes = 10L,
            )
            val snapshots = File(state, "snapshots")
            val orphan = File(snapshots, "crash-orphan.image").apply { writeBytes(byteArrayOf(9)) }
            val partial = File(snapshots, "crash.image.part").apply { writeBytes(byteArrayOf(9)) }

            val restarted = RemoteReviewLedger(state)

            assertTrue(committed.imageFile.isFile)
            assertEquals(committed, restarted.incoming(committed.transferId))
            assertTrue(!orphan.exists())
            assertTrue(!partial.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun outgoing(id: String, revision: Long) = OutgoingRemoteSnapshot(
        transferId = id,
        pageToken = "page_token_0001",
        bookId = "book-id",
        pageNumber = 3,
        attemptNo = 1,
        studentRevision = revision,
        widthPx = 1_400,
        heightPx = 1_980,
        createdAtEpochMs = 10L,
    )
}
