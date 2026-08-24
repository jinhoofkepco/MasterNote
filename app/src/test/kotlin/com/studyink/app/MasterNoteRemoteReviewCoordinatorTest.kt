package com.studyink.app

import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.PagePoint
import com.studyink.core.model.MarkColor
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeId
import com.studyink.core.model.StrokeTool
import com.studyink.monitor.core.NormalizedTeacherPoint
import com.studyink.monitor.core.NormalizedTeacherStroke
import com.studyink.monitor.core.NormalizedGradeAnchor
import com.studyink.monitor.core.HybridLinkMode
import com.studyink.monitor.core.PageSnapshotEnvelope
import com.studyink.monitor.core.RemoteGradeEnvelope
import com.studyink.monitor.core.RemoteReviewDocumentCodec
import com.studyink.monitor.core.ReviewCanvasDimensions
import com.studyink.monitor.core.SnapshotImageFormat
import com.studyink.monitor.core.SnapshotReference
import com.studyink.monitor.core.StudentWorkHeartbeat
import com.studyink.monitor.core.StudentWorkKind
import com.studyink.monitor.core.TeacherFeedbackEnvelope
import com.studyink.monitor.core.TeacherInkTool
import com.studyink.sync.lan.LanConnectionState
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MasterNoteRemoteReviewCoordinatorTest {
    @Test fun undecodableInboxDocumentIsRetainedWithoutTransportAcknowledgement() {
        val valid = RemoteReviewDocumentCodec.encode(
            PageSnapshotEnvelope(
                transferId = "snapshot_decode_0001",
                createdAtEpochMs = 1L,
                pageToken = PAGE_TOKEN,
                workbookLabel = "수학",
                pageNumber = 1,
                attemptNo = 1,
                revision = 1L,
                dimensions = ReviewCanvasDimensions(100, 200),
                imageFormat = SnapshotImageFormat.JPEG,
                renderedPageBytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()),
            ),
        ).copyBytes()
        assertTrue(decodeRemoteReviewInboxDocument(valid) is RemoteReviewInboxDecodeResult.Decoded)

        val unsupportedVersion = valid.copyOf().also { it[4] = 99 }
        assertEquals(
            RemoteReviewInboxDecodeResult.RetainWithoutAcknowledgement,
            decodeRemoteReviewInboxDocument(unsupportedVersion),
        )
        val corrupted = valid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertEquals(
            RemoteReviewInboxDecodeResult.RetainWithoutAcknowledgement,
            decodeRemoteReviewInboxDocument(corrupted),
        )
    }

    @Test fun retainedUndecodableInboxEntryDoesNotStarveLaterDocuments() {
        assertEquals(
            listOf(102L, 103L),
            selectRemoteReviewInboxUpdateIds(
                pendingUpdateIds = listOf(101L, 102L, 103L),
                retainedUpdateIds = setOf(101L),
                limit = 2,
            ),
        )
        assertEquals(
            listOf(104L),
            selectRemoteReviewInboxUpdateIds(
                pendingUpdateIds = listOf(101L, 102L, 103L, 104L),
                retainedUpdateIds = setOf(101L, 102L, 103L),
                limit = 2,
            ),
        )
    }

    @Test fun telegramOnlyTeacherCanSendWithoutEverOpeningALanSession() {
        assertTrue(shouldAllowTelegramUserAction(hasActiveLanSession = false, hybridMode = null))
        // A decision retained from a closed LAN session must not suppress Telegram-only actions.
        assertTrue(
            shouldAllowTelegramUserAction(
                hasActiveLanSession = false,
                hybridMode = HybridLinkMode.LAN_LIVE,
            ),
        )
    }

    @Test fun activeLanSessionRequiresAnExplicitTelegramOwningMode() {
        assertFalse(shouldAllowTelegramUserAction(true, null))
        assertFalse(shouldAllowTelegramUserAction(true, HybridLinkMode.LAN_LIVE))
        assertFalse(shouldAllowTelegramUserAction(true, HybridLinkMode.LAN_GRACE))
        assertTrue(shouldAllowTelegramUserAction(true, HybridLinkMode.TELEGRAM_FALLBACK))
        assertTrue(shouldAllowTelegramUserAction(true, HybridLinkMode.OFFLINE_QUEUEING))
    }

    @Test fun onlyConnectedLanTransportMayUseCatchUpGrace() {
        assertFalse(isLanTransportDefinitelyDisconnected(LanConnectionState.CONNECTED))
        assertTrue(isLanTransportDefinitelyDisconnected(LanConnectionState.IDLE))
        assertTrue(isLanTransportDefinitelyDisconnected(LanConnectionState.CONNECTING))
        assertTrue(isLanTransportDefinitelyDisconnected(LanConnectionState.DISCONNECTED))
    }

    @Test fun enteringTelegramFallbackForcesTheCurrentPageImmediately() {
        val state = RemoteReviewCaptureState(settleMs = 30_000L)
        state.onPresence(PAGE_ONE, observedRevision = 7L, nowElapsedMs = 0L)

        state.forceCurrent(nowElapsedMs = 12L)

        val forced = requireNotNull(state.nextDue(12L) { RemoteReviewOutboundState.SENT })
        assertEquals(PAGE_ONE, forced.target)
        assertTrue(forced.forceSend)
        assertTrue(state.shouldTransmit(forced, observedRevision = 7L, imageSha256 = "same"))
    }

    @Test fun contactIsOnlySentAfterDurableRevisionActuallyChanges() {
        val state = RemoteReviewCaptureState(
            intervalMs = 60_000L,
            settleMs = 0L,
            unchangedRetryMs = 10L,
            failureRetryMs = 100L,
        )
        state.onPresence(PAGE_ONE, observedRevision = 5L, nowElapsedMs = 0L)
        state.onHeartbeat(heartbeat(StudentWorkKind.PEN_CONTACT), nowElapsedMs = 0L)

        val early = requireNotNull(state.nextDue(0L) { RemoteReviewOutboundState.SENT })
        assertFalse(state.shouldTransmit(early, observedRevision = 5L, imageSha256 = "same"))
        state.completeUnchanged(early, observedRevision = 5L, nowElapsedMs = 0L)
        assertNull(state.nextDue(9L) { RemoteReviewOutboundState.SENT })

        val afterCommit = requireNotNull(state.nextDue(10L) { RemoteReviewOutboundState.SENT })
        assertTrue(state.shouldTransmit(afterCommit, observedRevision = 6L, imageSha256 = "changed"))
    }

    @Test fun activePageUpdatesAreRateLimitedAndUnsentEntryCoalesces() {
        val state = RemoteReviewCaptureState(settleMs = 0L)
        state.onPresence(PAGE_ONE, observedRevision = 1L, nowElapsedMs = 0L)
        state.onHeartbeat(heartbeat(StudentWorkKind.SUBMIT), nowElapsedMs = 0L)
        val first = requireNotNull(state.nextDue(0L) { RemoteReviewOutboundState.SENT })
        assertTrue(first.forceSend)
        state.completeSent(first, 1L, "image-1", "snapshot_0001", 0L)

        state.onHeartbeat(heartbeat(StudentWorkKind.PEN_CONTACT), nowElapsedMs = 1_000L)
        assertNull(state.nextDue(59_999L) { RemoteReviewOutboundState.PENDING })
        // At the minute boundary the older Telegram document is still unsent, so the latest dirty
        // page stays coalesced in memory instead of creating another outbox document.
        assertNull(state.nextDue(60_000L) { RemoteReviewOutboundState.PENDING })

        val latest = requireNotNull(state.nextDue(60_001L) { RemoteReviewOutboundState.SENT })
        assertEquals(PAGE_ONE, latest.target)
        assertTrue(state.shouldTransmit(latest, 2L, "image-2"))
    }

    @Test fun restartRebindsOutstandingPageAndDoesNotRenderDuplicateWhilePending() {
        val state = RemoteReviewCaptureState(intervalMs = 60_000L, settleMs = 0L)
        state.onPresence(PAGE_ONE, observedRevision = 4L, nowElapsedMs = 0L)
        assertTrue(state.restoreOutstanding(PAGE_ONE, "snapshot_pending_0001", 4L, 0L))
        state.onHeartbeat(heartbeat(StudentWorkKind.PEN_CONTACT), nowElapsedMs = 1L)

        assertNull(state.nextDue(60_000L) { RemoteReviewOutboundState.PENDING })
        val afterAck = requireNotNull(state.nextDue(60_000L) { RemoteReviewOutboundState.SENT })
        assertEquals(PAGE_ONE, afterAck.target)
    }

    @Test fun fallbackForceDoesNotResendUnchangedRestoredOutboxPageAfterAck() {
        val state = RemoteReviewCaptureState(intervalMs = 60_000L, settleMs = 0L)
        state.onPresence(PAGE_ONE, observedRevision = 4L, nowElapsedMs = 0L)
        assertTrue(state.restoreOutstanding(PAGE_ONE, "snapshot_pending_0002", 4L, 0L))

        state.forceCurrent(nowElapsedMs = 1L)

        assertNull(state.nextDue(1L) { RemoteReviewOutboundState.SENT })
    }

    @Test fun restartCanRebindPendingPageBeforeItBecomesCurrent() {
        val state = RemoteReviewCaptureState(intervalMs = 60_000L, settleMs = 0L)
        assertTrue(state.restoreOutstanding(PAGE_TWO, "snapshot_pending_0003", 2L, 0L))
        state.onPresence(PAGE_TWO, observedRevision = 2L, nowElapsedMs = 1L)
        state.onHeartbeat(
            StudentWorkHeartbeat(1L, StudentWorkKind.PEN_CONTACT, "book-id", 2),
            1L,
        )

        assertNull(state.nextDue(60_000L) { RemoteReviewOutboundState.PENDING })
    }

    @Test fun leavingDirtyPageOverridesMinuteDeadlineButPageChangeHeartbeatDoesNotDirtyNewPage() {
        val state = RemoteReviewCaptureState(settleMs = 30_000L)
        state.onPresence(PAGE_ONE, 1L, 0L)
        state.onHeartbeat(heartbeat(StudentWorkKind.PEN_CONTACT), 0L)
        assertNull(state.nextDue(5L) { RemoteReviewOutboundState.SENT })

        state.onPresence(PAGE_TWO, 0L, 5L)
        val leaving = requireNotNull(state.nextDue(5L) { RemoteReviewOutboundState.SENT })
        assertEquals(PAGE_ONE, leaving.target)
        state.completeFailure(leaving, 5L)

        state.onHeartbeat(
            StudentWorkHeartbeat(6L, StudentWorkKind.PAGE_CHANGE, "book-id", 2),
            6L,
        )
        assertNull(state.nextDue(6L) { RemoteReviewOutboundState.SENT })
    }

    @Test fun fullTeacherLayerReplacesSamePeerAndAttemptOnly() {
        val samePeerOldToken = stroke(
            id = "same-peer-old",
            author = "teacher",
            attempt = 1,
            device = PEER_DEVICE,
            itemId = "remote-review:old-page-token",
        )
        val samePeerOtherAttempt = stroke(
            id = "same-peer-attempt-2",
            author = "teacher",
            attempt = 2,
            device = PEER_DEVICE,
            itemId = "remote-review:old-page-token",
        )
        val otherPeer = stroke(
            id = "other-peer",
            author = "teacher",
            attempt = 1,
            device = "telegram-teacher-999",
            itemId = "remote-review:old-page-token",
        )
        val student = stroke(
            id = "student-stroke",
            author = "student",
            attempt = 1,
            device = "student-device",
            itemId = null,
        )
        val initial = snapshot(samePeerOldToken, samePeerOtherAttempt, otherPeer, student)

        val change = requireNotNull(
            buildPublishedRemoteTeacherLayerChange(
                snapshot = initial,
                operationClockHighWater = 20L,
                feedback = feedback(),
                attemptNo = 1,
                peerDeviceId = PEER_DEVICE,
            ),
        )

        assertEquals(setOf(samePeerOldToken.id), change.operation.removedStrokeIds)
        assertFalse(student.id in change.operation.removedStrokeIds)
        assertFalse(otherPeer.id in change.operation.removedStrokeIds)
        assertFalse(samePeerOtherAttempt.id in change.operation.removedStrokeIds)
        val added = change.addedAssets.single()
        assertEquals("teacher", added.authorId)
        assertEquals(PEER_DEVICE, added.deviceId)
        assertEquals(1, added.attemptNo)
        assertEquals(21L, added.logicalClock)
        assertEquals(100f, added.points.single().x, 0.0001f)
        assertEquals(400f, added.points.single().y, 0.0001f)
        assertEquals(1_000L, added.publishedAtEpochMillis)
        assertTrue(student.id in change.snapshot.activeStrokeIds)
        assertTrue(otherPeer.id in change.snapshot.activeStrokeIds)
        assertTrue(samePeerOtherAttempt.id in change.snapshot.activeStrokeIds)
    }

    @Test fun deterministicOperationMakesCrashReplayIdempotent() {
        val first = requireNotNull(
            buildPublishedRemoteTeacherLayerChange(
                snapshot = snapshot(),
                operationClockHighWater = 0L,
                feedback = feedback(),
                attemptNo = 1,
                peerDeviceId = PEER_DEVICE,
            ),
        )

        assertNull(
            buildPublishedRemoteTeacherLayerChange(
                snapshot = first.snapshot,
                operationClockHighWater = first.operation.logicalClock,
                feedback = feedback(),
                attemptNo = 1,
                peerDeviceId = PEER_DEVICE,
            ),
        )
    }

    @Test fun emptyPublishedLayerLeavesInvisibleGenerationMarker() {
        val original = feedback()
        val cleared = TeacherFeedbackEnvelope(
            transferId = "feedback_clear_0001",
            sourceSnapshot = original.sourceSnapshot,
            feedbackRevision = 2L,
            strokes = emptyList(),
            createdAtEpochMs = 2_000L,
        )

        val change = requireNotNull(
            buildPublishedRemoteTeacherLayerChange(
                snapshot = snapshot(),
                operationClockHighWater = 4L,
                feedback = cleared,
                attemptNo = 2,
                peerDeviceId = PEER_DEVICE,
            ),
        )

        val marker = change.addedAssets.single()
        assertTrue(marker.points.isEmpty())
        assertEquals(0, marker.colorArgb)
        assertEquals(2, marker.attemptNo)
        assertEquals(2_000L, marker.publishedAtEpochMillis)
        assertTrue(marker.itemId.orEmpty().startsWith("remote-review:"))
    }

    @Test fun feedbackSourceRequiresExactTransferTokenRevisionAndDimensions() {
        val source = OutgoingRemoteSnapshot(
            transferId = "snapshot_transfer_0001",
            pageToken = PAGE_TOKEN,
            bookId = "book-id",
            pageNumber = 0,
            attemptNo = 1,
            studentRevision = 7L,
            widthPx = 1_000,
            heightPx = 2_000,
            createdAtEpochMs = 1L,
        )

        assertTrue(feedback().matches(source))
        assertFalse(feedback(sourceRevision = 8L).matches(source))
        assertFalse(feedback(width = 999).matches(source))
    }

    @Test fun studentInkDigestIsStableAndIgnoresTeacherAndOtherAttempts() {
        val student = stroke(
            id = "student-stroke-a",
            author = "student",
            attempt = 1,
            device = "student-device",
            itemId = null,
        )
        val teacher = stroke(
            id = "teacher-stroke-a",
            author = "teacher",
            attempt = 1,
            device = PEER_DEVICE,
            itemId = "remote-review:$PAGE_TOKEN",
        )
        val otherAttempt = stroke(
            id = "student-stroke-b",
            author = "student",
            attempt = 2,
            device = "student-device",
            itemId = null,
        )

        val base = studentInkDigest(snapshot(student), 1)
        assertEquals(base, studentInkDigest(snapshot(otherAttempt, teacher, student), 1))
        assertFalse(
            base == studentInkDigest(
                snapshot(student.copy(points = listOf(PagePoint(11f, 20f)))),
                1,
            ),
        )
        assertTrue(base.matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun legacySnapshotTransferDigestParserAndGradeRequireExactVisualState() {
        val digest = "a".repeat(64)
        val transferId = "snapshot_${digest}_0123456789abcdef0123456789abcdef"
        assertEquals(digest, snapshotStudentInkDigest(transferId))
        assertNull(snapshotStudentInkDigest("snapshot_0123456789abcdef"))
        val source = OutgoingRemoteSnapshot(
            transferId = transferId,
            pageToken = PAGE_TOKEN,
            bookId = "book-id",
            pageNumber = 0,
            attemptNo = 2,
            studentRevision = 9L,
            widthPx = 1_000,
            heightPx = 2_000,
            createdAtEpochMs = 1L,
            studentInkDigest = digest,
        )
        val grade = RemoteGradeEnvelope(
            transferId = "grade_transfer_0001",
            createdAtEpochMs = 2L,
            actionId = "grade_action_0001",
            sourceSnapshot = SnapshotReference(transferId, PAGE_TOKEN, 9L, ReviewCanvasDimensions(1_000, 2_000)),
            attemptNo = 2,
            studentInkDigest = digest,
            gradeGroupId = "grade_group_0001",
            syncRevision = 1L,
            lastModifiedByDeviceId = "telegrambot_12345678",
            anchor = NormalizedGradeAnchor(0.2f, 0.3f),
            score = 1,
            maximumScore = 1,
        )

        assertTrue(grade.matches(source))
        assertTrue(grade.matches(source, "telegrambot_12345678"))
        assertFalse(grade.matches(source, "telegrambot_87654321"))
        assertFalse(grade.copy(attemptNo = 1).matches(source))
        assertFalse(grade.copy(studentInkDigest = "b".repeat(64)).matches(source))
        assertFalse(grade.copy(sourceSnapshot = grade.sourceSnapshot.copy(revision = 10L)).matches(source))

        val correct = buildRemoteGradeMarkGroup(source, grade)
        assertEquals("grade_group_0001", correct.id)
        assertEquals("book-id", correct.bookId)
        assertEquals(0, correct.pageNumber)
        assertEquals(200f, correct.anchor.x, 0.0001f)
        assertEquals(600f, correct.anchor.y, 0.0001f)
        assertEquals(2, correct.marks.single().attemptNo)
        assertEquals(MarkColor.BLUE, correct.marks.single().color)

        val incorrect = buildRemoteGradeMarkGroup(
            source,
            grade.copy(score = 0, gradeGroupId = "grade_group_0002"),
        )
        assertEquals(MarkColor.RED, incorrect.marks.single().color)
    }

    @Test fun teacherRevisionIsMonotonicAcrossUiResetAndProcessRestart() {
        val root = createTempDirectory("remote-review-revisions").toFile()
        val file = File(root, "revisions")
        try {
            val first = RemoteReviewTeacherRevisionStore(file)
            assertEquals(1L, first.reserve(PAGE_TOKEN, "feedback_transfer_0001", 1L))
            assertEquals(2L, first.reserve(PAGE_TOKEN, "feedback_transfer_0002", 1L))
            assertEquals(2L, first.reserve(PAGE_TOKEN, "feedback_transfer_0002", 1L))

            val restarted = RemoteReviewTeacherRevisionStore(file)
            assertEquals(3L, restarted.reserve(PAGE_TOKEN, "feedback_transfer_0003", 1L))
            assertEquals(1L, restarted.reserve("page_token_00000002", "feedback_transfer_0004", 1L))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun teacherRevisionJournalCompactsWithoutForgettingEvictedPageHighWater() {
        val root = createTempDirectory("remote-review-revision-compaction").toFile()
        val file = File(root, "revisions")
        val otherPage = "page_token_00000002"
        try {
            val first = RemoteReviewTeacherRevisionStore(
                file = file,
                maxReservations = 2,
                compactAfterRecords = 4,
                maximumJournalBytes = 1_000_000L,
            )
            assertEquals(1L, first.reserve(PAGE_TOKEN, "feedback_transfer_0001", 1L))
            repeat(7) { index ->
                assertEquals(
                    index + 1L,
                    first.reserve(otherPage, "feedback_other_${index.toString().padStart(8, '0')}", 1L),
                )
            }

            // Two live reservations plus PAGE_TOKEN's explicit high-water checkpoint.
            assertTrue(file.readLines().size <= 3)
            val restarted = RemoteReviewTeacherRevisionStore(
                file = file,
                maxReservations = 2,
                compactAfterRecords = 4,
                maximumJournalBytes = 1_000_000L,
            )
            assertEquals(2L, restarted.reserve(PAGE_TOKEN, "feedback_transfer_9999", 1L))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun publishedFeedbackStoreKeepsOnlyNewestBoundedPageLayers() {
        val root = createTempDirectory("remote-review-published-layers").toFile()
        try {
            val store = RemoteReviewPublishedFeedbackStore(root, maxPublishedPages = 2)
            val pages = (1..3).map { index -> "page_store_${index.toString().padStart(8, '0')}" }
            pages.forEachIndexed { index, pageToken ->
                val base = feedback()
                store.store(
                    TeacherFeedbackEnvelope(
                        transferId = "feedback_store_${index.toString().padStart(8, '0')}",
                        sourceSnapshot = base.sourceSnapshot.copy(
                            transferId = "snapshot_store_${index.toString().padStart(8, '0')}",
                            pageToken = pageToken,
                        ),
                        createdAtEpochMs = base.createdAtEpochMs,
                        feedbackRevision = base.feedbackRevision,
                        strokes = base.strokes,
                        note = base.note,
                    ),
                )
            }

            assertNull(store.load(pages.first()))
            assertEquals(pages[1], requireNotNull(store.load(pages[1])).sourceSnapshot.pageToken)
            assertEquals(pages[2], requireNotNull(store.load(pages[2])).sourceSnapshot.pageToken)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun heartbeat(kind: StudentWorkKind) =
        StudentWorkHeartbeat(0L, kind, "book-id", 1)

    private fun feedback(
        sourceRevision: Long = 7L,
        width: Int = 1_000,
    ) = TeacherFeedbackEnvelope(
        transferId = "feedback_transfer_0001",
        createdAtEpochMs = 1_000L,
        sourceSnapshot = SnapshotReference(
            transferId = "snapshot_transfer_0001",
            pageToken = PAGE_TOKEN,
            revision = sourceRevision,
            dimensions = ReviewCanvasDimensions(width, 2_000),
        ),
        feedbackRevision = 1L,
        strokes = listOf(
            NormalizedTeacherStroke(
                strokeId = "teacher_stroke_0001",
                tool = TeacherInkTool.PEN,
                argb = 0xffff0000.toInt(),
                widthNormalized = 0.01f,
                points = listOf(NormalizedTeacherPoint(0.1f, 0.2f, 0.8f)),
            ),
        ),
    )

    private fun stroke(
        id: String,
        author: String,
        attempt: Int,
        device: String,
        itemId: String?,
    ) = StrokeAsset(
        id = StrokeId(id),
        pageNumber = 0,
        tool = StrokeTool.PEN,
        colorArgb = 0xff000000.toInt(),
        width = 3f,
        points = listOf(PagePoint(10f, 20f)),
        authorId = author,
        attemptNo = attempt,
        logicalClock = 10L,
        deviceId = device,
        itemId = itemId,
        publishedAtEpochMillis = if (author == "teacher") 1L else null,
    )

    private fun snapshot(vararg strokes: StrokeAsset): AnnotationSnapshot = AnnotationSnapshot(
        bookId = "book-id",
        pageNumber = 0,
        revision = 5L,
        assets = strokes.associateBy(StrokeAsset::id),
        activeStrokeIds = strokes.mapTo(linkedSetOf(), StrokeAsset::id),
    )

    private companion object {
        val PAGE_ONE = RemoteReviewCaptureTarget("book-id", 0, 1)
        val PAGE_TWO = RemoteReviewCaptureTarget("book-id", 1, 1)
        const val PAGE_TOKEN = "page_token_00000001"
        const val PEER_DEVICE = "telegram-teacher-123"
    }
}
