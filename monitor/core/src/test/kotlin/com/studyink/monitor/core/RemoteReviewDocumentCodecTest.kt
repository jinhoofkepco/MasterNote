package com.studyink.monitor.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteReviewDocumentCodecTest {
    @Test fun pageSnapshotRoundTripsWithPrivateWorkbookMetadataAndSha256() {
        val originalImage = jpegBytes(512)
        val envelope = pageSnapshot(renderedPageBytes = originalImage)
        originalImage[10] = 99 // construction must have taken an immutable copy

        val encoded = RemoteReviewDocumentCodec.encode(envelope)
        val decodedDocument = RemoteReviewDocumentCodec.decode(encoded.copyBytes())
        val decoded = decodedDocument.envelope as PageSnapshotEnvelope

        assertEquals(RemoteReviewEnvelopeType.PAGE_SNAPSHOT, decoded.type)
        assertEquals("transfer_snapshot_0001", decoded.transferId)
        assertEquals("page_token_00000001", decoded.pageToken)
        assertEquals("수학 문제집", decoded.workbookLabel)
        assertEquals(37, decoded.pageNumber)
        assertEquals(2, decoded.attemptNo)
        assertEquals("학생 A", decoded.studentLabel)
        assertEquals(41L, decoded.revision)
        assertEquals(1_600, decoded.dimensions.widthPx)
        assertArrayEquals(jpegBytes(512), decoded.copyRenderedPageBytes())
        assertEquals(64, encoded.payloadSha256Hex.length)
        assertEquals(encoded.payloadSha256Hex, decodedDocument.payloadSha256Hex)
        assertFalse(decodedDocument.exceedsOperationalLimit)
        assertTrue(encoded.sizeBytes < RemoteReviewLimits.OPERATIONAL_DOCUMENT_BYTES)
    }

    @Test fun teacherFeedbackRoundTripsNormalizedFullLayer() {
        val source = snapshotReference(revision = 41L)
        val feedback = TeacherFeedbackEnvelope(
            transferId = "feedback_transfer_0001",
            createdAtEpochMs = 2_000L,
            sourceSnapshot = source,
            feedbackRevision = 3L,
            strokes = listOf(
                NormalizedTeacherStroke(
                    strokeId = "teacher_stroke_0001",
                    tool = TeacherInkTool.HIGHLIGHTER,
                    argb = 0x66ffcc00,
                    widthNormalized = 0.025f,
                    points = listOf(
                        NormalizedTeacherPoint(0.1f, 0.2f, 0.4f),
                        NormalizedTeacherPoint(0.8f, 0.9f, 0.7f),
                    ),
                ),
            ),
            note = "이 식을 다시 확인해 봐.",
        )

        val decoded = RemoteReviewDocumentCodec.decode(
            RemoteReviewDocumentCodec.encode(feedback).copyBytes(),
        ).envelope as TeacherFeedbackEnvelope

        assertEquals(source, decoded.sourceSnapshot)
        assertEquals(3L, decoded.feedbackRevision)
        assertEquals("이 식을 다시 확인해 봐.", decoded.note)
        assertEquals(1, decoded.strokes.size)
        assertEquals(TeacherInkTool.HIGHLIGHTER, decoded.strokes.single().tool)
        assertEquals(2, decoded.strokes.single().points.size)
        assertEquals(0.8f, decoded.strokes.single().points.last().x)
    }

    @Test fun ackRoundTripsWithoutPageOrGradingState() {
        val ack = RemoteReviewAckEnvelope(
            transferId = "ack_transfer_00000001",
            createdAtEpochMs = 3_000L,
            acknowledgedTransferId = "feedback_transfer_0001",
            disposition = RemoteReviewAckDisposition.SUPERSEDED,
            detailCode = "FEEDBACK_REVISION_NOT_NEWER",
        )

        val decoded = RemoteReviewDocumentCodec.decode(
            RemoteReviewDocumentCodec.encode(ack).copyBytes(),
        ).envelope as RemoteReviewAckEnvelope

        assertEquals(ack, decoded)
    }

    @Test fun tamperingAnyPayloadByteFailsSha256Validation() {
        val bytes = RemoteReviewDocumentCodec.encode(pageSnapshot()).copyBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()

        val failure = assertThrows(RemoteReviewCodecException::class.java) {
            RemoteReviewDocumentCodec.decode(bytes)
        }

        assertEquals(RemoteReviewCodecError.CHECKSUM_MISMATCH, failure.error)
    }

    @Test fun hardLimitIsCheckedBeforeFrameParsing() {
        val bytes = ByteArray(RemoteReviewLimits.HARD_DOCUMENT_BYTES + 1)

        val failure = assertThrows(RemoteReviewCodecException::class.java) {
            RemoteReviewDocumentCodec.decode(bytes)
        }

        assertEquals(RemoteReviewCodecError.TOO_LARGE, failure.error)
    }

    @Test fun maximumOperationalImageStillProducesDocumentBelowTwoMiB() {
        val encoded = RemoteReviewDocumentCodec.encode(
            pageSnapshot(
                renderedPageBytes = jpegBytes(RemoteReviewLimits.MAX_SNAPSHOT_IMAGE_BYTES),
            ),
        )

        assertTrue(encoded.sizeBytes <= RemoteReviewLimits.OPERATIONAL_DOCUMENT_BYTES)
    }

    @Test fun imageOverOperationalBoundIsRejectedAtConstruction() {
        val failure = assertThrows(RemoteReviewValidationException::class.java) {
            pageSnapshot(
                renderedPageBytes = jpegBytes(RemoteReviewLimits.MAX_SNAPSHOT_IMAGE_BYTES + 1),
            )
        }

        assertEquals("renderedPageBytes", failure.field)
    }

    @Test fun invalidNormalizedPointAndFeedbackRevisionAreRejected() {
        assertThrows(RemoteReviewValidationException::class.java) {
            NormalizedTeacherPoint(Float.NaN, 0.2f)
        }
        assertThrows(RemoteReviewValidationException::class.java) {
            NormalizedTeacherPoint(1.01f, 0.2f)
        }
        assertThrows(RemoteReviewValidationException::class.java) {
            TeacherFeedbackEnvelope(
                transferId = "feedback_transfer_0001",
                createdAtEpochMs = 2_000L,
                sourceSnapshot = snapshotReference(),
                feedbackRevision = 0L,
                strokes = emptyList(),
            )
        }
    }

    @Test fun labelsAndPageIdentityAreBoundedAndValidated() {
        assertThrows(RemoteReviewValidationException::class.java) {
            pageSnapshot(workbookLabel = "\n")
        }
        assertThrows(RemoteReviewValidationException::class.java) {
            pageSnapshot(pageNumber = 0)
        }
        assertThrows(RemoteReviewValidationException::class.java) {
            pageSnapshot(attemptNo = 0)
        }
    }

    private fun pageSnapshot(
        renderedPageBytes: ByteArray = jpegBytes(128),
        workbookLabel: String = "수학 문제집",
        pageNumber: Int = 37,
        attemptNo: Int? = 2,
    ) = PageSnapshotEnvelope(
        transferId = "transfer_snapshot_0001",
        createdAtEpochMs = 1_000L,
        pageToken = "page_token_00000001",
        workbookLabel = workbookLabel,
        pageNumber = pageNumber,
        attemptNo = attemptNo,
        studentLabel = "학생 A",
        revision = 41L,
        dimensions = ReviewCanvasDimensions(1_600, 2_000),
        imageFormat = SnapshotImageFormat.JPEG,
        renderedPageBytes = renderedPageBytes,
    )

    private fun snapshotReference(revision: Long = 41L) = SnapshotReference(
        transferId = "transfer_snapshot_0001",
        pageToken = "page_token_00000001",
        revision = revision,
        dimensions = ReviewCanvasDimensions(1_600, 2_000),
    )

    private fun jpegBytes(size: Int): ByteArray = ByteArray(size.coerceAtLeast(3)).also {
        it[0] = 0xff.toByte()
        it[1] = 0xd8.toByte()
        it[2] = 0xff.toByte()
    }
}
