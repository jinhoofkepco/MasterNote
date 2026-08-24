package com.studyink.monitor.core

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePeerEnvelopeCodecTest {
    @Test fun chatMessageRoundTripsUnicodeAndNewlines() {
        val envelope = chat(text = "오늘 풀이 확인했어.\n다음 문제도 해 보자 👍")

        val encoded = RemoteReviewDocumentCodec.encode(envelope)
        val decoded = RemoteReviewDocumentCodec.decode(encoded.copyBytes()).envelope

        assertEquals(envelope, decoded)
        assertEquals(RemoteReviewEnvelopeType.CHAT_MESSAGE, decoded.type)
        assertEquals(4, encoded.wireTypeCode())
        assertEquals(64, encoded.payloadSha256Hex.length)
    }

    @Test fun remoteGradeRoundTripsExactAttemptDigestAndConflictMetadata() {
        val envelope = grade()

        val encoded = RemoteReviewDocumentCodec.encode(envelope)
        val decoded = RemoteReviewDocumentCodec.decode(encoded.copyBytes()).envelope

        assertEquals(envelope, decoded)
        assertEquals(RemoteReviewEnvelopeType.REMOTE_GRADE, decoded.type)
        assertEquals(5, encoded.wireTypeCode())
    }

    @Test fun snapshotStudentInkDigestUsesLegacyCompatibleEncryptedImageMetadata() {
        val digest = "ab".repeat(32)
        val withDigest = snapshot(studentInkDigest = digest)
        val encoded = RemoteReviewDocumentCodec.encode(withDigest)

        val decoded = RemoteReviewDocumentCodec.decode(
            encoded.copyBytes(),
        ).envelope as PageSnapshotEnvelope

        assertEquals(digest, decoded.studentInkDigest)
        val checkpointDecoded = checkpointDecodeSnapshot(encoded.copyBytes())
        assertEquals(withDigest.transferId, checkpointDecoded.transferId)
        assertEquals(0, checkpointDecoded.trailingByteCount)
        assertTrue(checkpointDecoded.image.hasPrefix(JPEG_SIGNATURE))
        assertTrue(decoded.copyRenderedPageBytes().hasPrefix(JPEG_SIGNATURE))
        assertTrue(!withDigest.transferId.contains(digest))
        assertValidationField("studentInkDigest") {
            snapshot(studentInkDigest = "A".repeat(RemoteReviewLimits.SHA256_HEX_BYTES))
        }
        assertValidationField("studentInkDigest") {
            snapshot(studentInkDigest = "a".repeat(RemoteReviewLimits.SHA256_HEX_BYTES - 1))
        }
    }

    @Test fun transitionalTrailingSnapshotDigestStillDecodesAfterCompatibilityChange() {
        val digest = "cd".repeat(32)
        val transitional = appendTransitionalSnapshotDigest(
            RemoteReviewDocumentCodec.encode(snapshot()).copyBytes(),
            digest,
        )

        val decoded = RemoteReviewDocumentCodec.decode(transitional).envelope as PageSnapshotEnvelope

        assertEquals(digest, decoded.studentInkDigest)
    }

    @Test fun pngDigestMetadataAlsoKeepsTheLegacyFieldLayout() {
        val digest = "ef".repeat(32)
        val encoded = RemoteReviewDocumentCodec.encode(
            PageSnapshotEnvelope(
                transferId = "snapshot_png_00000001",
                createdAtEpochMs = 1_000L,
                pageToken = "page_png_000000001",
                workbookLabel = "수학",
                pageNumber = 3,
                attemptNo = 2,
                revision = 7L,
                dimensions = ReviewCanvasDimensions(1, 1),
                imageFormat = SnapshotImageFormat.PNG,
                renderedPageBytes = ONE_PIXEL_PNG,
                studentInkDigest = digest,
            ),
        )

        val decoded = RemoteReviewDocumentCodec.decode(encoded.copyBytes()).envelope as PageSnapshotEnvelope

        assertEquals(digest, decoded.studentInkDigest)
        val checkpointDecoded = checkpointDecodeSnapshot(encoded.copyBytes())
        assertEquals("snapshot_png_00000001", checkpointDecoded.transferId)
        assertEquals(0, checkpointDecoded.trailingByteCount)
        assertTrue(checkpointDecoded.image.hasPrefix(PNG_SIGNATURE))
        assertTrue(decoded.copyRenderedPageBytes().hasPrefix(PNG_SIGNATURE))
    }

    @Test fun existingEnvelopeWireCodesRemainStable() {
        val snapshot = RemoteReviewDocumentCodec.encode(snapshot())
        val feedback = RemoteReviewDocumentCodec.encode(feedback())
        val ack = RemoteReviewDocumentCodec.encode(ack())

        assertEquals(1, snapshot.wireTypeCode())
        assertEquals(2, feedback.wireTypeCode())
        assertEquals(3, ack.wireTypeCode())
        assertNull(
            (RemoteReviewDocumentCodec.decode(snapshot.copyBytes()).envelope as PageSnapshotEnvelope)
                .studentInkDigest,
        )
        assertEquals(
            "6cc97c21d5e1ec0ab7574ae841f2a549f554d6cd09970e6a62a2a31d25cb27aa",
            snapshot.payloadSha256Hex,
        )
        assertEquals(
            "2aca7a344b66b408a2a7464783e2ca1c2a1b034754fe4432e7d891e321e60f93",
            feedback.payloadSha256Hex,
        )
        assertEquals(
            "dcdbd4d02634d495d93fb9331401a9fbe43c5f5e1dd4dc9531a0cc4a1c99dd25",
            ack.payloadSha256Hex,
        )
    }

    @Test fun chatTextUsesUtf8ByteBoundAndRejectsUnsafeValues() {
        val exactLimit = "a".repeat(RemoteReviewLimits.MAX_CHAT_TEXT_UTF8_BYTES)
        assertEquals(exactLimit, roundTrip(chat(text = exactLimit)).text)

        assertValidationField("text") { chat(text = "   \n\t") }
        assertValidationField("text") {
            chat(text = "가".repeat(RemoteReviewLimits.MAX_CHAT_TEXT_UTF8_BYTES / 3 + 1))
        }
        assertValidationField("text") { chat(text = "숨은\u0000문자") }
        assertValidationField("text") { chat(text = "잘못된 유니코드 \uD800") }
        assertValidationField("sentAtEpochMs") { chat(sentAtEpochMs = -1L) }
        assertValidationField("messageId") { chat(messageId = "short") }
        assertValidationField("senderDeviceId") { chat(senderDeviceId = "bad device id") }
    }

    @Test fun remoteGradeStrictlyValidatesIdentityAttemptDigestRevisionAnchorAndScore() {
        assertValidationField("actionId") { grade(actionId = "short") }
        assertValidationField("attemptNo") { grade(attemptNo = 0) }
        assertValidationField("studentInkDigest") {
            grade(studentInkDigest = "A".repeat(RemoteReviewLimits.SHA256_HEX_BYTES))
        }
        assertValidationField("studentInkDigest") {
            grade(studentInkDigest = "a".repeat(RemoteReviewLimits.SHA256_HEX_BYTES - 1))
        }
        assertValidationField("gradeGroupId") { grade(gradeGroupId = "bad group") }
        assertValidationField("syncRevision") { grade(syncRevision = 0L) }
        assertValidationField("lastModifiedByDeviceId") {
            grade(lastModifiedByDeviceId = "short")
        }
        assertValidationField("anchor.x") {
            grade(anchor = NormalizedGradeAnchor(1.001f, 0.5f))
        }
        assertValidationField("anchor.y") {
            grade(anchor = NormalizedGradeAnchor(0.5f, Float.NaN))
        }
        assertValidationField("maximumScore") { grade(maximumScore = 0) }
        assertValidationField("maximumScore") {
            grade(maximumScore = RemoteReviewLimits.MAX_GRADE_SCORE + 1)
        }
        assertValidationField("score") { grade(score = -1) }
        assertValidationField("score") { grade(score = 11, maximumScore = 10) }
    }

    @Test fun peerArtifactsUseCommitThenAckPolicyAndOnlyPendingGradesCoalesce() {
        val state = EmptyState
        val chatPlan = RemoteReviewExchangeStateMachine.planIncoming(chat(), state)
        assertEquals(RemoteReviewIncomingAction.APPLY_CHAT_MESSAGE, chatPlan.action)
        assertEquals(RemoteReviewAckDisposition.APPLIED, chatPlan.ackAfterCommit?.disposition)
        assertTrue(
            chatPlan.commitMutations.contains(
                RemoteReviewStateMutation.RecordCommittedTransfer(chat().transferId),
            ),
        )
        assertEquals(null, RemoteReviewExchangeStateMachine.coalesceKey(chat()))

        val existing = grade(syncRevision = 3L, actionId = "grade_action_0003")
        val newer = grade(
            transferId = "grade_transfer_0004",
            actionId = "grade_action_0004",
            syncRevision = 4L,
        )
        val plan = RemoteReviewExchangeStateMachine.planOutbound(
            candidate = newer,
            existingForCoalesceKey = RemoteReviewOutboxEntryView(
                envelope = existing,
                status = RemoteReviewOutboxStatus.PENDING,
            ),
        )

        assertEquals(RemoteReviewOutboxAction.REPLACE_PENDING, plan.action)
        assertEquals(existing.transferId, plan.replacedTransferId)
        assertNotNull(plan.coalesceKey)

        val inFlightPlan = RemoteReviewExchangeStateMachine.planOutbound(
            candidate = newer,
            existingForCoalesceKey = RemoteReviewOutboxEntryView(
                envelope = existing,
                status = RemoteReviewOutboxStatus.AWAITING_ACK,
            ),
        )
        assertEquals(RemoteReviewOutboxAction.APPEND, inFlightPlan.action)
    }

    @Test fun unknownNewerWireTypeIsStillRejectedInsteadOfGuessed() {
        val bytes = RemoteReviewDocumentCodec.encode(chat()).copyBytes()
        bytes[WIRE_TYPE_OFFSET] = 6

        val failure = assertThrows(RemoteReviewCodecException::class.java) {
            RemoteReviewDocumentCodec.decode(bytes)
        }

        assertEquals(RemoteReviewCodecError.UNKNOWN_TYPE, failure.error)
    }

    private fun chat(
        transferId: String = "chat_transfer_0001",
        messageId: String = "chat_message_0001",
        senderDeviceId: String = "teacher_device_0001",
        sentAtEpochMs: Long = 10_100L,
        text: String = "확인했어.",
    ) = ChatMessageEnvelope(
        transferId = transferId,
        createdAtEpochMs = 10_200L,
        messageId = messageId,
        senderDeviceId = senderDeviceId,
        sentAtEpochMs = sentAtEpochMs,
        text = text,
    )

    private fun grade(
        transferId: String = "grade_transfer_0003",
        actionId: String = "grade_action_0003",
        attemptNo: Int = 2,
        studentInkDigest: String = "ab".repeat(32),
        gradeGroupId: String = "grade_group_0001",
        syncRevision: Long = 3L,
        lastModifiedByDeviceId: String = "teacher_device_0001",
        anchor: NormalizedGradeAnchor = NormalizedGradeAnchor(0.25f, 0.75f),
        score: Int = 8,
        maximumScore: Int = 10,
    ) = RemoteGradeEnvelope(
        transferId = transferId,
        createdAtEpochMs = 20_000L,
        actionId = actionId,
        sourceSnapshot = reference(),
        attemptNo = attemptNo,
        studentInkDigest = studentInkDigest,
        gradeGroupId = gradeGroupId,
        syncRevision = syncRevision,
        lastModifiedByDeviceId = lastModifiedByDeviceId,
        anchor = anchor,
        score = score,
        maximumScore = maximumScore,
    )

    private fun snapshot(
        studentInkDigest: String? = null,
    ) = PageSnapshotEnvelope(
        transferId = "snapshot_transfer_0001",
        createdAtEpochMs = 1_000L,
        pageToken = "page_token_00000001",
        workbookLabel = "수학",
        pageNumber = 3,
        attemptNo = 2,
        revision = 7L,
        dimensions = ReviewCanvasDimensions(1_000, 1_200),
        imageFormat = SnapshotImageFormat.JPEG,
        renderedPageBytes = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()),
        studentInkDigest = studentInkDigest,
    )

    private fun feedback() = TeacherFeedbackEnvelope(
        transferId = "feedback_transfer_0001",
        createdAtEpochMs = 2_000L,
        sourceSnapshot = reference(),
        feedbackRevision = 1L,
        strokes = emptyList(),
    )

    private fun ack() = RemoteReviewAckEnvelope(
        transferId = "ack_transfer_00000001",
        createdAtEpochMs = 3_000L,
        acknowledgedTransferId = "feedback_transfer_0001",
        disposition = RemoteReviewAckDisposition.APPLIED,
    )

    private fun reference() = SnapshotReference(
        transferId = "snapshot_transfer_0001",
        pageToken = "page_token_00000001",
        revision = 7L,
        dimensions = ReviewCanvasDimensions(1_000, 1_200),
    )

    /** A frozen copy of the checkpoint v1 parser: no awareness of studentInkDigest. */
    private fun checkpointDecodeSnapshot(document: ByteArray): CheckpointDecodedSnapshot {
        val frame = DataInputStream(ByteArrayInputStream(document))
        frame.readInt()
        frame.readUnsignedByte()
        assertEquals(1, frame.readUnsignedByte())
        val payloadSize = frame.readInt()
        frame.skipBytes(32)
        val payload = ByteArray(payloadSize).also(frame::readFully)
        assertEquals(0, frame.available())

        val input = DataInputStream(ByteArrayInputStream(payload))
        val transferId = input.readLegacyString()
        input.readLong()
        input.readLegacyString()
        input.readLegacyString()
        input.readInt()
        if (input.readBoolean()) input.readInt()
        if (input.readBoolean()) input.readLegacyString()
        input.readLong()
        input.readInt()
        input.readInt()
        input.readUnsignedByte()
        val imageSize = input.readInt()
        val image = ByteArray(imageSize).also(input::readFully)
        return CheckpointDecodedSnapshot(transferId, image, input.available())
    }

    private fun DataInputStream.readLegacyString(): String =
        ByteArray(readInt()).also(::readFully).toString(Charsets.UTF_8)

    private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

    private fun appendTransitionalSnapshotDigest(document: ByteArray, digest: String): ByteArray {
        val payload = document.copyOfRange(FRAME_BYTES, document.size)
        val extension = ByteBuffer.allocate(4 + digest.length)
            .putInt(digest.length)
            .put(digest.toByteArray(Charsets.US_ASCII))
            .array()
        val newPayload = payload + extension
        return ByteArray(FRAME_BYTES + newPayload.size).also { result ->
            document.copyInto(result, endIndex = FRAME_BYTES)
            ByteBuffer.wrap(result, PAYLOAD_LENGTH_OFFSET, 4).putInt(newPayload.size)
            MessageDigest.getInstance("SHA-256").digest(newPayload)
                .copyInto(result, destinationOffset = PAYLOAD_DIGEST_OFFSET)
            newPayload.copyInto(result, destinationOffset = FRAME_BYTES)
        }
    }

    private fun roundTrip(envelope: ChatMessageEnvelope): ChatMessageEnvelope =
        RemoteReviewDocumentCodec.decode(
            RemoteReviewDocumentCodec.encode(envelope).copyBytes(),
        ).envelope as ChatMessageEnvelope

    private fun EncodedRemoteReviewDocument.wireTypeCode(): Int =
        copyBytes()[WIRE_TYPE_OFFSET].toInt() and 0xff

    private fun assertValidationField(
        expectedField: String,
        block: () -> Unit,
    ) {
        val failure = assertThrows(RemoteReviewValidationException::class.java, block)
        assertEquals(expectedField, failure.field)
    }

    private object EmptyState : RemoteReviewStateView {
        override fun isTransferCommitted(transferId: String): Boolean = false
        override fun snapshotByTransferId(transferId: String): RemoteSnapshotCursor? = null
        override fun latestSnapshot(pageToken: String): RemoteSnapshotCursor? = null
        override fun latestFeedback(pageToken: String): RemoteFeedbackCursor? = null
    }

    private data class CheckpointDecodedSnapshot(
        val transferId: String,
        val image: ByteArray,
        val trailingByteCount: Int,
    )

    private companion object {
        const val WIRE_TYPE_OFFSET = 5
        const val FRAME_BYTES = 42
        const val PAYLOAD_LENGTH_OFFSET = 6
        const val PAYLOAD_DIGEST_OFFSET = 10
        val JPEG_SIGNATURE = byteArrayOf(0xff.toByte(), 0xd8.toByte())
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
        val ONE_PIXEL_PNG: ByteArray = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=",
        )
    }
}
