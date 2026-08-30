package com.studyink.app

import com.studyink.memo.core.MemoTarget
import com.studyink.monitor.core.StudentMemoEnvelope
import com.studyink.monitor.core.RemoteReviewExchangeStateMachine
import com.studyink.monitor.core.studentMemoPayloadSha256Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentMemoTelegramRoutingTest {
    @Test
    fun studentPublicationRequiresExactGenerationPageWorkbookContentAndAttempt() {
        val page = studentPage()
        val target = MemoTarget("student-book", 93, 4)

        assertTrue(page.authorizesStudentMemo(target, 7L, PAGE_TOKEN, WORKBOOK_TOKEN, CONTENT_SHA))
        assertFalse(page.authorizesStudentMemo(target.copy(attemptNo = 5), 7L, PAGE_TOKEN, WORKBOOK_TOKEN, CONTENT_SHA))
        assertFalse(page.authorizesStudentMemo(target, 8L, PAGE_TOKEN, WORKBOOK_TOKEN, CONTENT_SHA))
        assertFalse(page.authorizesStudentMemo(target, 7L, "other-page", WORKBOOK_TOKEN, CONTENT_SHA))
        assertFalse(page.authorizesStudentMemo(target, 7L, PAGE_TOKEN, "other-workbook", CONTENT_SHA))
        assertFalse(page.authorizesStudentMemo(target, 7L, PAGE_TOKEN, WORKBOOK_TOKEN, "f".repeat(64)))
    }

    @Test
    fun teacherRemapsOnlyAnExactCurrentManifestIdentity() {
        val page = teacherPage()
        val envelope = envelope()

        assertEquals(
            MemoTarget("teacher-book", 93, 4),
            page.studentMemoLocalTarget(envelope, 7L, CONTENT_SHA),
        )
        assertNull(page.studentMemoLocalTarget(envelope, 8L, CONTENT_SHA))
        assertNull(page.studentMemoLocalTarget(envelope(copyAttemptNo = 5), 7L, CONTENT_SHA))
        assertNull(page.studentMemoLocalTarget(envelope(copyPageNumber = 95), 7L, CONTENT_SHA))
        assertNull(page.studentMemoLocalTarget(envelope(copyContentSha = "f".repeat(64)), 7L, CONTENT_SHA))
    }

    @Test
    fun replayUsesSameTransferIdButAnyIdentityRevisionOrDigestChangeRotatesIt() {
        val first = transferId()

        assertEquals(first, transferId())
        assertNotEquals(first, transferId(generation = 8L))
        assertNotEquals(first, transferId(pageToken = "other-page-token"))
        assertNotEquals(first, transferId(revision = 3L))
        assertNotEquals(first, transferId(digest = "e".repeat(64)))
        assertNotEquals(first, transferId(pairId = "other-pair"))
        assertNotEquals(first, transferId(deliveryAttempt = 1))
    }

    @Test
    fun memoOutboxUsesExactPageAttemptAndMemoSemanticKey() {
        assertEquals(
            "STUDENT_MEMO:$PAGE_TOKEN:4:$MEMO_ID",
            RemoteReviewExchangeStateMachine.coalesceKey(envelope()),
        )
    }

    @Test
    fun memoChangesWaitForFiveQuietSecondsButContinuousInkIsBoundedAtThirtySeconds() {
        assertFalse(studentMemoSendIsDue(1_000L, 4_000L, 0L, 8_999L))
        assertTrue(studentMemoSendIsDue(1_000L, 4_000L, 0L, 9_000L))
        assertFalse(studentMemoSendIsDue(1_000L, 29_000L, 0L, 30_999L))
        assertTrue(studentMemoSendIsDue(1_000L, 29_000L, 0L, 31_000L))
        assertFalse(studentMemoSendIsDue(1_000L, 29_000L, 35_000L, 31_000L))
        assertTrue(studentMemoSendIsDue(1_000L, 29_000L, 35_000L, 35_000L))
    }

    @Test
    fun memoRetryBackoffIsBoundedAndDailyAttemptsCycleAcrossSevenDurableIds() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (1..6).map(::studentMemoRetryDelayMs))
        val day = 24L * 60L * 60L * 1_000L
        assertEquals((0 until 7).toList(), (0 until 7).map { studentMemoDeliveryAttemptSlot(it * day) })
        assertEquals(0, studentMemoDeliveryAttemptSlot(7L * day))
        assertEquals(7, (0 until 7).map { transferId(deliveryAttempt = it) }.toSet().size)
    }

    private fun studentPage() = StudentPageSyncRecord(
        syncGeneration = 7L,
        pageToken = PAGE_TOKEN,
        workbookToken = WORKBOOK_TOKEN,
        bookId = "student-book",
        contentSha256 = CONTENT_SHA,
        studentLayerSha256 = "b".repeat(64),
        stateFingerprint = "c".repeat(64),
        workbookLabel = "교재",
        pageNumber = 93,
        attemptNos = listOf(3, 4),
        submittedAttemptNos = listOf(3),
        sourceRevision = 10L,
        acknowledgedRevision = 10L,
        acknowledgedStateFingerprint = "c".repeat(64),
        originDeviceHighWater = 1L,
        acknowledgedOriginCursor = 1L,
        lastChangedAtEpochMs = 1L,
        approximateBytes = 1L,
    )

    private fun teacherPage() = TeacherPageSyncRecord(
        syncGeneration = 7L,
        pageToken = PAGE_TOKEN,
        workbookToken = WORKBOOK_TOKEN,
        contentSha256 = CONTENT_SHA,
        studentLayerSha256 = "b".repeat(64),
        workbookLabel = "교재",
        localBookId = "teacher-book",
        pageNumber = 93,
        attemptNos = listOf(3, 4),
        submittedAttemptNos = listOf(3),
        sourceRevision = 10L,
        appliedRevision = 10L,
        appliedStudentLayerSha256 = "b".repeat(64),
        lastChangedAtEpochMs = 1L,
        approximateBytes = 1L,
    )

    private fun envelope(
        copyAttemptNo: Int = 4,
        copyPageNumber: Int = 94,
        copyContentSha: String = CONTENT_SHA,
    ): StudentMemoEnvelope {
        val payload = byteArrayOf(1, 2, 3)
        return StudentMemoEnvelope(
            transferId = "memo-transfer",
            createdAtEpochMs = 1L,
            syncGeneration = 7L,
            pageToken = PAGE_TOKEN,
            workbookToken = WORKBOOK_TOKEN,
            contentSha256 = copyContentSha,
            pageNumber = copyPageNumber,
            attemptNo = copyAttemptNo,
            memoId = MEMO_ID,
            memoRevision = 2L,
            memoDigestSha256 = "d".repeat(64),
            payloadSha256 = studentMemoPayloadSha256Hex(payload),
            payloadBytes = payload,
        )
    }

    private fun transferId(
        pairId: String = "pair",
        generation: Long = 7L,
        pageToken: String = PAGE_TOKEN,
        revision: Long = 2L,
        digest: String = "d".repeat(64),
        deliveryAttempt: Int = 0,
    ) = studentMemoTelegramTransferId(
        pairId,
        generation,
        pageToken,
        MEMO_ID,
        revision,
        digest,
        deliveryAttempt,
    )

    private companion object {
        const val PAGE_TOKEN = "page-token"
        const val WORKBOOK_TOKEN = "workbook-token"
        const val MEMO_ID = "123e4567-e89b-12d3-a456-426614174000"
        val CONTENT_SHA = "a".repeat(64)
    }
}
