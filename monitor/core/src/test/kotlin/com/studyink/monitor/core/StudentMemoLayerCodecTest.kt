package com.studyink.monitor.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StudentMemoLayerCodecTest {
    @Test
    fun exactAttemptSnapshotRoundTripsDeterministicallyAndOwnsPayload() {
        val mutablePayload = "memo-snapshot-v1".toByteArray()
        val originalPayload = mutablePayload.copyOf()
        val envelope = memoEnvelope(mutablePayload)
        mutablePayload.fill(0)

        val first = RemoteReviewDocumentCodec.encode(envelope)
        val second = RemoteReviewDocumentCodec.encode(envelope)
        val decoded = RemoteReviewDocumentCodec.decode(first.copyBytes())
            .envelope as StudentMemoEnvelope

        assertEquals(11, first.copyBytes()[5].toInt() and 0xff)
        assertArrayEquals(first.copyBytes(), second.copyBytes())
        assertEquals(RemoteReviewEnvelopeType.STUDENT_MEMO, decoded.type)
        assertEquals("memo_transfer_0001", decoded.transferId)
        assertEquals(1_700_000_000_000L, decoded.createdAtEpochMs)
        assertEquals(19L, decoded.syncGeneration)
        assertEquals("memo_page_token_0094", decoded.pageToken)
        assertEquals("memo_workbook_token_01", decoded.workbookToken)
        assertEquals("11".repeat(32), decoded.contentSha256)
        assertEquals(94, decoded.pageNumber)
        assertEquals(4, decoded.attemptNo)
        assertEquals("memo_id_00000001", decoded.memoId)
        assertEquals(7L, decoded.memoRevision)
        assertEquals("22".repeat(32), decoded.memoDigestSha256)
        assertEquals(studentMemoPayloadSha256Hex(originalPayload), decoded.payloadSha256)
        assertArrayEquals(originalPayload, decoded.copyPayloadBytes())

        val leakedCopy = decoded.copyPayloadBytes()
        leakedCopy.fill(1)
        assertArrayEquals(originalPayload, decoded.copyPayloadBytes())
    }

    @Test
    fun rejectsMismatchedHashInvalidIdentityAndEmptyPayload() {
        val mismatch = assertThrows(RemoteReviewValidationException::class.java) {
            memoEnvelope("memo".toByteArray(), payloadSha256 = "00".repeat(32))
        }
        assertEquals("payloadSha256", mismatch.field)

        val badContent = assertThrows(RemoteReviewValidationException::class.java) {
            memoEnvelope("memo".toByteArray(), contentSha256 = "AA".repeat(32))
        }
        assertEquals("contentSha256", badContent.field)

        val badPage = assertThrows(RemoteReviewValidationException::class.java) {
            memoEnvelope("memo".toByteArray(), pageNumber = 0)
        }
        assertEquals("pageNumber", badPage.field)

        val empty = assertThrows(RemoteReviewValidationException::class.java) {
            memoEnvelope(ByteArray(0))
        }
        assertEquals("payloadBytes", empty.field)
    }

    @Test
    fun maximumSnapshotFitsOperationalDocumentAndOneExtraByteIsRejected() {
        val maximum = ByteArray(RemoteReviewLimits.MAX_STUDENT_MEMO_BYTES) { 7 }
        val encoded = RemoteReviewDocumentCodec.encode(memoEnvelope(maximum))
        assertTrue(encoded.sizeBytes <= RemoteReviewLimits.OPERATIONAL_DOCUMENT_BYTES)

        val failure = assertThrows(RemoteReviewValidationException::class.java) {
            memoEnvelope(ByteArray(RemoteReviewLimits.MAX_STUDENT_MEMO_BYTES + 1))
        }
        assertEquals("payloadBytes", failure.field)
    }

    private fun memoEnvelope(
        payload: ByteArray,
        payloadSha256: String = studentMemoPayloadSha256Hex(payload),
        contentSha256: String = "11".repeat(32),
        pageNumber: Int = 94,
    ) = StudentMemoEnvelope(
        transferId = "memo_transfer_0001",
        createdAtEpochMs = 1_700_000_000_000L,
        syncGeneration = 19L,
        pageToken = "memo_page_token_0094",
        workbookToken = "memo_workbook_token_01",
        contentSha256 = contentSha256,
        pageNumber = pageNumber,
        attemptNo = 4,
        memoId = "memo_id_00000001",
        memoRevision = 7L,
        memoDigestSha256 = "22".repeat(32),
        payloadSha256 = payloadSha256,
        payloadBytes = payload,
    )
}
