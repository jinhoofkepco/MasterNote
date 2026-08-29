package com.studyink.monitor.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.CRC32

enum class RemoteReviewCodecError {
    TOO_LARGE,
    BAD_MAGIC,
    UNSUPPORTED_VERSION,
    UNKNOWN_TYPE,
    INVALID_LENGTH,
    CHECKSUM_MISMATCH,
    MALFORMED_UTF8,
    MALFORMED_PAYLOAD,
    VALIDATION_FAILED,
}

class RemoteReviewCodecException(
    val error: RemoteReviewCodecError,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

class EncodedRemoteReviewDocument internal constructor(
    bytes: ByteArray,
    val payloadSha256Hex: String,
) {
    private val immutableBytes = bytes.copyOf()

    val sizeBytes: Int get() = immutableBytes.size

    fun copyBytes(): ByteArray = immutableBytes.copyOf()
}

data class DecodedRemoteReviewDocument(
    val envelope: RemoteReviewEnvelope,
    val payloadSha256Hex: String,
    /** True only for a compatibility/recovery input above the normal two MiB writer limit. */
    val exceedsOperationalLimit: Boolean,
)

/**
 * Deterministic binary document codec intended to be sent as a Telegram document.
 *
 * The SHA-256 in the frame covers every semantic payload byte, including IDs and metadata. This is
 * corruption detection, not authentication; the integration layer must still authenticate and
 * decrypt the peer document before calling [decode].
 */
object RemoteReviewDocumentCodec {
    const val MIME_TYPE: String = "application/vnd.studyink.remote-review"
    const val FILE_EXTENSION: String = "mnrr"

    fun encode(envelope: RemoteReviewEnvelope): EncodedRemoteReviewDocument {
        val payload = encodePayload(envelope)
        val digest = sha256(payload)
        val totalSize = FRAME_BYTES.toLong() + payload.size.toLong()
        if (totalSize > RemoteReviewLimits.OPERATIONAL_DOCUMENT_BYTES) {
            throw RemoteReviewCodecException(
                RemoteReviewCodecError.TOO_LARGE,
                "Encoded document is $totalSize bytes; operational limit is " +
                    "${RemoteReviewLimits.OPERATIONAL_DOCUMENT_BYTES} bytes.",
            )
        }

        val framed = ByteArrayOutputStream(totalSize.toInt())
        DataOutputStream(framed).use { output ->
            output.writeInt(MAGIC)
            output.writeByte(VERSION)
            output.writeByte(envelope.type.wireCode())
            output.writeInt(payload.size)
            output.write(digest)
            output.write(payload)
        }
        return EncodedRemoteReviewDocument(framed.toByteArray(), digest.toHex())
    }

    fun decode(bytes: ByteArray): DecodedRemoteReviewDocument {
        if (bytes.size > RemoteReviewLimits.HARD_DOCUMENT_BYTES) {
            fail(RemoteReviewCodecError.TOO_LARGE) {
                "Document is ${bytes.size} bytes; hard limit is " +
                    "${RemoteReviewLimits.HARD_DOCUMENT_BYTES} bytes."
            }
        }
        if (bytes.size < FRAME_BYTES) {
            fail(RemoteReviewCodecError.INVALID_LENGTH) { "Document is shorter than the frame." }
        }

        try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (input.readInt() != MAGIC) {
                fail(RemoteReviewCodecError.BAD_MAGIC) { "Not a MasterNote remote-review document." }
            }
            val version = input.readUnsignedByte()
            if (version != VERSION) {
                fail(RemoteReviewCodecError.UNSUPPORTED_VERSION) {
                    "Unsupported remote-review version $version."
                }
            }
            val type = envelopeTypeFromWire(input.readUnsignedByte())
            val payloadLength = input.readInt()
            val actualPayloadLength = bytes.size - FRAME_BYTES
            if (payloadLength < 0 || payloadLength != actualPayloadLength) {
                fail(RemoteReviewCodecError.INVALID_LENGTH) {
                    "Declared payload length $payloadLength does not match $actualPayloadLength."
                }
            }
            val expectedDigest = ByteArray(SHA256_BYTES)
            input.readFully(expectedDigest)
            val payload = ByteArray(payloadLength)
            input.readFully(payload)
            val actualDigest = sha256(payload)
            if (!MessageDigest.isEqual(expectedDigest, actualDigest)) {
                fail(RemoteReviewCodecError.CHECKSUM_MISMATCH) {
                    "Remote-review payload SHA-256 does not match."
                }
            }

            val envelope = decodePayload(type, payload)
            return DecodedRemoteReviewDocument(
                envelope = envelope,
                payloadSha256Hex = actualDigest.toHex(),
                exceedsOperationalLimit = bytes.size > RemoteReviewLimits.OPERATIONAL_DOCUMENT_BYTES,
            )
        } catch (expected: RemoteReviewCodecException) {
            throw expected
        } catch (expected: RemoteReviewValidationException) {
            throw RemoteReviewCodecException(
                RemoteReviewCodecError.VALIDATION_FAILED,
                expected.message ?: "Remote-review payload validation failed.",
                expected,
            )
        } catch (expected: CharacterCodingException) {
            throw RemoteReviewCodecException(
                RemoteReviewCodecError.MALFORMED_UTF8,
                "Remote-review payload contains malformed UTF-8.",
                expected,
            )
        } catch (expected: EOFException) {
            throw RemoteReviewCodecException(
                RemoteReviewCodecError.INVALID_LENGTH,
                "Remote-review payload ended unexpectedly.",
                expected,
            )
        } catch (expected: RuntimeException) {
            throw RemoteReviewCodecException(
                RemoteReviewCodecError.MALFORMED_PAYLOAD,
                "Malformed remote-review payload.",
                expected,
            )
        }
    }

    private fun encodePayload(envelope: RemoteReviewEnvelope): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeBoundedString(envelope.transferId)
            output.writeLong(envelope.createdAtEpochMs)
            when (envelope) {
                is PageSnapshotEnvelope -> {
                    output.writeBoundedString(envelope.pageToken)
                    output.writeBoundedString(envelope.workbookLabel)
                    output.writeInt(envelope.pageNumber)
                    output.writeNullablePositiveInt(envelope.attemptNo)
                    output.writeNullableString(envelope.studentLabel)
                    output.writeLong(envelope.revision)
                    output.writeDimensions(envelope.dimensions)
                    output.writeByte(envelope.imageFormat.wireCode())
                    val image = envelope.studentInkDigest?.let { digest ->
                        embedSnapshotDigest(
                            format = envelope.imageFormat,
                            image = envelope.renderedPageBytesForCodec(),
                            digest = digest,
                        )
                    } ?: envelope.renderedPageBytesForCodec()
                    output.writeInt(image.size)
                    output.write(image)
                    // Keep the v1 payload ending at the image so checkpoint readers can decode it.
                    // The optional digest lives in standard, decoder-ignored image metadata instead.
                }

                is TeacherFeedbackEnvelope -> {
                    output.writeSnapshotReference(envelope.sourceSnapshot)
                    output.writeLong(envelope.feedbackRevision)
                    output.writeNullableString(envelope.note)
                    output.writeInt(envelope.strokes.size)
                    envelope.strokes.forEach { stroke ->
                        output.writeBoundedString(stroke.strokeId)
                        output.writeByte(stroke.tool.wireCode())
                        output.writeInt(stroke.argb)
                        output.writeFloat(stroke.widthNormalized)
                        output.writeInt(stroke.points.size)
                        stroke.points.forEach { point ->
                            output.writeFloat(point.x)
                            output.writeFloat(point.y)
                            output.writeFloat(point.pressure)
                        }
                    }
                }

                is RemoteReviewAckEnvelope -> {
                    output.writeBoundedString(envelope.acknowledgedTransferId)
                    output.writeByte(envelope.disposition.wireCode())
                    output.writeNullableString(envelope.detailCode)
                }

                is ChatMessageEnvelope -> {
                    output.writeBoundedString(envelope.messageId)
                    output.writeBoundedString(envelope.senderDeviceId)
                    output.writeLong(envelope.sentAtEpochMs)
                    output.writeBoundedString(envelope.text)
                }

                is RemoteGradeEnvelope -> {
                    output.writeBoundedString(envelope.actionId)
                    output.writeSnapshotReference(envelope.sourceSnapshot)
                    output.writeInt(envelope.attemptNo)
                    output.writeBoundedString(envelope.studentInkDigest)
                    output.writeBoundedString(envelope.gradeGroupId)
                    output.writeLong(envelope.syncRevision)
                    output.writeBoundedString(envelope.lastModifiedByDeviceId)
                    output.writeFloat(envelope.anchor.x)
                    output.writeFloat(envelope.anchor.y)
                    output.writeInt(envelope.score)
                    output.writeInt(envelope.maximumScore)
                }

                is PageSyncManifestEnvelope -> {
                    output.writeLong(envelope.syncGeneration)
                    output.writeLong(envelope.sequence)
                    output.writeBoolean(envelope.currentCursor != null)
                    envelope.currentCursor?.let { cursor ->
                        output.writeLong(cursor.sequence)
                        output.writeBoundedString(cursor.pageToken)
                        output.writeInt(cursor.pageNumber)
                        output.writeNullablePositiveInt(cursor.currentAttemptNo)
                        output.writeLong(cursor.revision)
                    }
                    output.writeInt(envelope.entries.size)
                    envelope.entries.forEach { entry ->
                        output.writeBoundedString(entry.pageToken)
                        output.writeBoundedString(entry.workbookToken)
                        output.writeBoundedString(entry.contentSha256)
                        output.writeBoundedString(entry.studentLayerSha256)
                        output.writeInt(entry.pageNumber)
                        output.writeAttemptNos(entry.attemptNos)
                        output.writeAttemptNos(entry.submittedAttemptNos)
                        output.writeLong(entry.revision)
                        output.writeLong(entry.lastChangedEpochMs)
                        output.writeLong(entry.approxBytes)
                    }
                    // Optional trailing field keeps new readers compatible with already queued
                    // manifest frames that predate bounded inventory pagination.
                    envelope.inventoryPageCount?.let(output::writeInt)
                }

                is PageSyncRequestEnvelope -> {
                    output.writeLong(envelope.syncGeneration)
                    output.writeBoundedString(envelope.pageToken)
                    output.writeInt(envelope.pageNumber)
                    output.writeNullablePositiveInt(envelope.attemptNo)
                    output.writeLong(envelope.requesterRevision)
                }

                is PageAnnotationEnvelope -> {
                    output.writeLong(envelope.syncGeneration)
                    output.writeByte(envelope.purpose.wireCode())
                    output.writeNullableString(envelope.responseToTransferId)
                    output.writeBoundedString(envelope.pageToken)
                    output.writeInt(envelope.pageNumber)
                    output.writeAttemptNos(envelope.attemptNos)
                    output.writeByte(envelope.kind.wireCode())
                    output.writeLong(envelope.baseRevision)
                    output.writeLong(envelope.sourceRevision)
                    output.writeNullableString(envelope.deltaOriginDeviceId)
                    output.writeLong(envelope.baseOriginCursor)
                    output.writeLong(envelope.sourceOriginCursor)
                    output.writeByte(envelope.compression.wireCode())
                    val annotationPayload = envelope.payloadBytesForCodec()
                    output.writeInt(annotationPayload.size)
                    output.write(annotationPayload)
                    output.writeBoundedString(envelope.payloadSha256)
                    output.writeBoundedString(envelope.resultLayerSha256)
                    // Optional trailing extension: old queued single-part frames end above and
                    // remain byte-for-byte decodable. Only fragmented checkpoints carry it.
                    if (envelope.chunked) {
                        output.writeByte(PAGE_ANNOTATION_CHUNK_EXTENSION_VERSION)
                        output.writeBoundedString(envelope.chunkGroupId)
                        output.writeInt(envelope.chunkIndex)
                        output.writeInt(envelope.chunkCount)
                        output.writeInt(envelope.assembledPayloadSizeBytes)
                        output.writeBoundedString(envelope.chunkSha256)
                    }
                }

                is PageSyncAckEnvelope -> {
                    output.writeLong(envelope.syncGeneration)
                    output.writeByte(envelope.sourceType.wireCode())
                    output.writeBoundedString(envelope.sourceTransferId)
                    output.writeBoundedString(envelope.pageToken)
                    output.writeInt(envelope.pageNumber)
                    output.writeLong(envelope.sourceRevision)
                    output.writeByte(envelope.disposition.wireCode())
                    output.writeNullableString(envelope.reasonCode)
                }

                is GptExplanationLayerEnvelope -> {
                    output.writeBoundedString(envelope.pageToken)
                    output.writeInt(envelope.pageNumber)
                    output.writeInt(envelope.attemptNo)
                    output.writeLong(envelope.layerRevision)
                    output.writeBoundedString(envelope.layerDigestSha256)
                    output.writeInt(envelope.cards.size)
                    envelope.cards.forEach { card ->
                        output.writeBoundedString(card.cardId)
                        output.writeBoundedString(card.sourceResourceId)
                        output.writeBoundedString(card.sourceResourceRevisionId)
                        output.writeBoundedString(card.title)
                        output.writeBoundedString(card.text)
                        output.writeFloat(card.anchor.left)
                        output.writeFloat(card.anchor.top)
                        output.writeFloat(card.anchor.right)
                        output.writeFloat(card.anchor.bottom)
                        output.writeLong(card.createdAtEpochMs)
                        output.writeLong(card.updatedAtEpochMs)
                    }
                    if (envelope.authorityEpoch != REMOTE_LEGACY_EXPLANATION_AUTHORITY) {
                        output.writeByte(GPT_AUTHORITY_EPOCH_EXTENSION_VERSION)
                        output.writeBoundedString(envelope.authorityEpoch)
                    }
                }
            }
        }
        return bytes.toByteArray()
    }

    private fun decodePayload(
        type: RemoteReviewEnvelopeType,
        payload: ByteArray,
    ): RemoteReviewEnvelope {
        val input = DataInputStream(ByteArrayInputStream(payload))
        val transferId = input.readBoundedString(RemoteReviewLimits.MAX_TOKEN_UTF8_BYTES)
        val createdAtEpochMs = input.readLong()
        val envelope = when (type) {
            RemoteReviewEnvelopeType.PAGE_SNAPSHOT -> {
                val pageToken = input.readBoundedString(RemoteReviewLimits.MAX_TOKEN_UTF8_BYTES)
                val workbookLabel = input.readBoundedString(
                    RemoteReviewLimits.MAX_WORKBOOK_LABEL_UTF8_BYTES,
                )
                val pageNumber = input.readInt()
                val attemptNo = input.readNullablePositiveInt()
                val studentLabel = input.readNullableString(
                    RemoteReviewLimits.MAX_STUDENT_LABEL_UTF8_BYTES,
                )
                val revision = input.readLong()
                val dimensions = input.readDimensions()
                val format = imageFormatFromWire(input.readUnsignedByte())
                val image = input.readBoundedBytes(RemoteReviewLimits.MAX_SNAPSHOT_IMAGE_BYTES)
                val embeddedStudentInkDigest = extractSnapshotDigest(format, image)
                // Transitional builds wrote the digest after the image. Continue reading those
                // already-durable documents, but never emit that old extension again: checkpoint
                // readers reject any bytes after the v1 image field.
                val trailingStudentInkDigest = if (input.available() == 0) {
                    null
                } else {
                    input.readBoundedString(RemoteReviewLimits.SHA256_HEX_BYTES)
                }
                if (embeddedStudentInkDigest != null && trailingStudentInkDigest != null &&
                    embeddedStudentInkDigest != trailingStudentInkDigest
                ) {
                    fail(RemoteReviewCodecError.MALFORMED_PAYLOAD) {
                        "Snapshot image metadata and trailing digest disagree."
                    }
                }
                PageSnapshotEnvelope(
                    transferId = transferId,
                    createdAtEpochMs = createdAtEpochMs,
                    pageToken = pageToken,
                    workbookLabel = workbookLabel,
                    pageNumber = pageNumber,
                    attemptNo = attemptNo,
                    studentLabel = studentLabel,
                    revision = revision,
                    dimensions = dimensions,
                    imageFormat = format,
                    renderedPageBytes = image,
                    studentInkDigest = embeddedStudentInkDigest ?: trailingStudentInkDigest,
                )
            }

            RemoteReviewEnvelopeType.TEACHER_FEEDBACK -> {
                val sourceSnapshot = input.readSnapshotReference()
                val feedbackRevision = input.readLong()
                val note = input.readNullableString(RemoteReviewLimits.MAX_NOTE_UTF8_BYTES)
                val strokeCount = input.readBoundedCount(RemoteReviewLimits.MAX_STROKES, "stroke")
                var totalPoints = 0
                val strokes = ArrayList<NormalizedTeacherStroke>(strokeCount)
                repeat(strokeCount) {
                    val strokeId = input.readBoundedString(RemoteReviewLimits.MAX_TOKEN_UTF8_BYTES)
                    val tool = inkToolFromWire(input.readUnsignedByte())
                    val argb = input.readInt()
                    val widthNormalized = input.readFloat()
                    val pointCount = input.readBoundedCount(
                        RemoteReviewLimits.MAX_POINTS_PER_STROKE,
                        "point",
                    )
                    if (totalPoints > RemoteReviewLimits.MAX_TOTAL_POINTS - pointCount) {
                        fail(RemoteReviewCodecError.MALFORMED_PAYLOAD) {
                            "Total point count exceeds ${RemoteReviewLimits.MAX_TOTAL_POINTS}."
                        }
                    }
                    totalPoints += pointCount
                    val points = ArrayList<NormalizedTeacherPoint>(pointCount)
                    repeat(pointCount) {
                        points += NormalizedTeacherPoint(
                            x = input.readFloat(),
                            y = input.readFloat(),
                            pressure = input.readFloat(),
                        )
                    }
                    strokes += NormalizedTeacherStroke(
                        strokeId = strokeId,
                        tool = tool,
                        argb = argb,
                        widthNormalized = widthNormalized,
                        points = points,
                    )
                }
                TeacherFeedbackEnvelope(
                    transferId = transferId,
                    createdAtEpochMs = createdAtEpochMs,
                    sourceSnapshot = sourceSnapshot,
                    feedbackRevision = feedbackRevision,
                    strokes = strokes,
                    note = note,
                )
            }

            RemoteReviewEnvelopeType.ACK -> RemoteReviewAckEnvelope(
                transferId = transferId,
                createdAtEpochMs = createdAtEpochMs,
                acknowledgedTransferId = input.readBoundedString(RemoteReviewLimits.MAX_TOKEN_UTF8_BYTES),
                disposition = ackDispositionFromWire(input.readUnsignedByte()),
                detailCode = input.readNullableString(MAX_DETAIL_CODE_BYTES),
            )

            RemoteReviewEnvelopeType.CHAT_MESSAGE -> ChatMessageEnvelope(
                transferId = transferId,
                createdAtEpochMs = createdAtEpochMs,
                messageId = input.readBoundedString(RemoteReviewLimits.MAX_TOKEN_UTF8_BYTES),
                senderDeviceId = input.readBoundedString(RemoteReviewLimits.MAX_TOKEN_UTF8_BYTES),
                sentAtEpochMs = input.readLong(),
                text = input.readBoundedString(RemoteReviewLimits.MAX_CHAT_TEXT_UTF8_BYTES),
            )

            RemoteReviewEnvelopeType.REMOTE_GRADE -> RemoteGradeEnvelope(
                transferId = transferId,
                createdAtEpochMs = createdAtEpochMs,
                actionId = input.readBoundedString(RemoteReviewLimits.MAX_TOKEN_UTF8_BYTES),
                sourceSnapshot = input.readSnapshotReference(),
                attemptNo = input.readInt(),
                studentInkDigest = input.readBoundedString(RemoteReviewLimits.SHA256_HEX_BYTES),
                gradeGroupId = input.readBoundedString(RemoteReviewLimits.MAX_TOKEN_UTF8_BYTES),
                syncRevision = input.readLong(),
                lastModifiedByDeviceId = input.readBoundedString(
                    RemoteReviewLimits.MAX_TOKEN_UTF8_BYTES,
                ),
                anchor = NormalizedGradeAnchor(
                    x = input.readFloat(),
                    y = input.readFloat(),
                ),
                score = input.readInt(),
                maximumScore = input.readInt(),
            )

            RemoteReviewEnvelopeType.PAGE_SYNC_MANIFEST -> {
                val syncGeneration = input.readLong()
                val sequence = input.readLong()
                val currentCursor = when (input.readUnsignedByte()) {
                    0 -> null
                    1 -> PageSyncCursor(
                        sequence = input.readLong(),
                        pageToken = input.readBoundedString(RemoteReviewLimits.MAX_TOKEN_UTF8_BYTES),
                        pageNumber = input.readInt(),
                        currentAttemptNo = input.readNullablePositiveInt(),
                        revision = input.readLong(),
                    )
                    else -> fail(RemoteReviewCodecError.MALFORMED_PAYLOAD) {
                        "Current-cursor marker must be 0 or 1."
                    }
                }
                val entryCount = input.readBoundedCount(
                    RemoteReviewLimits.MAX_PAGE_SYNC_MANIFEST_ENTRIES,
                    "page sync manifest entry",
                )
                val entries = ArrayList<PageSyncManifestEntry>(entryCount)
                repeat(entryCount) {
                    entries += PageSyncManifestEntry(
                        pageToken = input.readBoundedString(RemoteReviewLimits.MAX_TOKEN_UTF8_BYTES),
                        workbookToken = input.readBoundedString(RemoteReviewLimits.MAX_TOKEN_UTF8_BYTES),
                        contentSha256 = input.readBoundedString(RemoteReviewLimits.SHA256_HEX_BYTES),
                        studentLayerSha256 = input.readBoundedString(RemoteReviewLimits.SHA256_HEX_BYTES),
                        pageNumber = input.readInt(),
                        attemptNos = input.readAttemptNos(),
                        submittedAttemptNos = input.readAttemptNos(),
                        revision = input.readLong(),
                        lastChangedEpochMs = input.readLong(),
                        approxBytes = input.readLong(),
                    )
                }
                val inventoryPageCount = if (input.available() == 0) null else input.readInt()
                PageSyncManifestEnvelope(
                    transferId = transferId,
                    createdAtEpochMs = createdAtEpochMs,
                    syncGeneration = syncGeneration,
                    sequence = sequence,
                    currentCursor = currentCursor,
                    entries = entries,
                    inventoryPageCount = inventoryPageCount,
                )
            }

            RemoteReviewEnvelopeType.PAGE_SYNC_REQUEST -> PageSyncRequestEnvelope(
                transferId = transferId,
                createdAtEpochMs = createdAtEpochMs,
                syncGeneration = input.readLong(),
                pageToken = input.readBoundedString(RemoteReviewLimits.MAX_TOKEN_UTF8_BYTES),
                pageNumber = input.readInt(),
                attemptNo = input.readNullablePositiveInt(),
                requesterRevision = input.readLong(),
            )

            RemoteReviewEnvelopeType.PAGE_ANNOTATION -> {
                val syncGeneration = input.readLong()
                val purpose = pageAnnotationPurposeFromWire(input.readUnsignedByte())
                val responseToTransferId = input.readNullableString(
                    RemoteReviewLimits.MAX_TOKEN_UTF8_BYTES,
                )
                val pageToken = input.readBoundedString(RemoteReviewLimits.MAX_TOKEN_UTF8_BYTES)
                val pageNumber = input.readInt()
                val attemptNos = input.readAttemptNos()
                val kind = pageAnnotationKindFromWire(input.readUnsignedByte())
                val baseRevision = input.readLong()
                val sourceRevision = input.readLong()
                val deltaOriginDeviceId = input.readNullableString(
                    RemoteReviewLimits.MAX_TOKEN_UTF8_BYTES,
                )
                val baseOriginCursor = input.readLong()
                val sourceOriginCursor = input.readLong()
                val compression = pageAnnotationCompressionFromWire(input.readUnsignedByte())
                val annotationPayload = input.readBoundedBytes(kind.maxPayloadBytes())
                val payloadSha256 = input.readBoundedString(RemoteReviewLimits.SHA256_HEX_BYTES)
                val resultLayerSha256 = input.readBoundedString(RemoteReviewLimits.SHA256_HEX_BYTES)
                var chunkGroupId: String? = null
                var chunkIndex = 0
                var chunkCount = 1
                var assembledPayloadSizeBytes: Int? = null
                var chunkSha256: String? = null
                if (input.available() > 0) {
                    val extensionVersion = input.readUnsignedByte()
                    if (extensionVersion != PAGE_ANNOTATION_CHUNK_EXTENSION_VERSION) {
                        fail(RemoteReviewCodecError.UNSUPPORTED_VERSION) {
                            "Unsupported page annotation chunk extension $extensionVersion."
                        }
                    }
                    chunkGroupId = input.readBoundedString(RemoteReviewLimits.MAX_TOKEN_UTF8_BYTES)
                    chunkIndex = input.readInt()
                    chunkCount = input.readInt()
                    assembledPayloadSizeBytes = input.readInt()
                    chunkSha256 = input.readBoundedString(RemoteReviewLimits.SHA256_HEX_BYTES)
                }
                PageAnnotationEnvelope(
                    transferId = transferId,
                    createdAtEpochMs = createdAtEpochMs,
                    syncGeneration = syncGeneration,
                    purpose = purpose,
                    responseToTransferId = responseToTransferId,
                    pageToken = pageToken,
                    pageNumber = pageNumber,
                    attemptNos = attemptNos,
                    kind = kind,
                    baseRevision = baseRevision,
                    sourceRevision = sourceRevision,
                    deltaOriginDeviceId = deltaOriginDeviceId,
                    baseOriginCursor = baseOriginCursor,
                    sourceOriginCursor = sourceOriginCursor,
                    compression = compression,
                    payloadBytes = annotationPayload,
                    payloadSha256 = payloadSha256,
                    resultLayerSha256 = resultLayerSha256,
                    chunkGroupId = chunkGroupId,
                    chunkIndex = chunkIndex,
                    chunkCount = chunkCount,
                    assembledPayloadSizeBytes = assembledPayloadSizeBytes,
                    chunkSha256 = chunkSha256,
                )
            }

            RemoteReviewEnvelopeType.PAGE_SYNC_ACK -> PageSyncAckEnvelope(
                transferId = transferId,
                createdAtEpochMs = createdAtEpochMs,
                syncGeneration = input.readLong(),
                sourceType = pageSyncAckSourceTypeFromWire(input.readUnsignedByte()),
                sourceTransferId = input.readBoundedString(RemoteReviewLimits.MAX_TOKEN_UTF8_BYTES),
                pageToken = input.readBoundedString(RemoteReviewLimits.MAX_TOKEN_UTF8_BYTES),
                pageNumber = input.readInt(),
                sourceRevision = input.readLong(),
                disposition = pageSyncAckDispositionFromWire(input.readUnsignedByte()),
                reasonCode = input.readNullableString(MAX_DETAIL_CODE_BYTES),
            )

            RemoteReviewEnvelopeType.GPT_EXPLANATION_LAYER -> {
                val pageToken = input.readBoundedString(RemoteReviewLimits.MAX_TOKEN_UTF8_BYTES)
                val pageNumber = input.readInt()
                val attemptNo = input.readInt()
                val layerRevision = input.readLong()
                val layerDigest = input.readBoundedString(RemoteReviewLimits.SHA256_HEX_BYTES)
                val cardCount = input.readBoundedCount(
                    RemoteReviewLimits.MAX_GPT_EXPLANATION_CARDS,
                    "GPT explanation card",
                )
                val cards = ArrayList<RemoteExplanationCard>(cardCount)
                repeat(cardCount) {
                    cards += RemoteExplanationCard(
                        cardId = input.readBoundedString(RemoteReviewLimits.MAX_TOKEN_UTF8_BYTES),
                        sourceResourceId = input.readBoundedString(
                            RemoteReviewLimits.MAX_TOKEN_UTF8_BYTES,
                        ),
                        sourceResourceRevisionId = input.readBoundedString(
                            RemoteReviewLimits.MAX_TOKEN_UTF8_BYTES,
                        ),
                        title = input.readBoundedString(
                            RemoteReviewLimits.MAX_GPT_CARD_TITLE_UTF8_BYTES,
                        ),
                        text = input.readBoundedString(
                            RemoteReviewLimits.MAX_GPT_CARD_TEXT_UTF8_BYTES,
                        ),
                        anchor = RemoteExplanationBounds(
                            left = input.readFloat(),
                            top = input.readFloat(),
                            right = input.readFloat(),
                            bottom = input.readFloat(),
                        ),
                        createdAtEpochMs = input.readLong(),
                        updatedAtEpochMs = input.readLong(),
                    )
                }
                val authorityEpoch = if (input.available() == 0) {
                    REMOTE_LEGACY_EXPLANATION_AUTHORITY
                } else {
                    val extensionVersion = input.readUnsignedByte()
                    if (extensionVersion != GPT_AUTHORITY_EPOCH_EXTENSION_VERSION) {
                        fail(RemoteReviewCodecError.MALFORMED_PAYLOAD) {
                            "Unknown GPT authority extension $extensionVersion."
                        }
                    }
                    input.readBoundedString(RemoteReviewLimits.MAX_TOKEN_UTF8_BYTES)
                }
                GptExplanationLayerEnvelope(
                    transferId = transferId,
                    createdAtEpochMs = createdAtEpochMs,
                    pageToken = pageToken,
                    pageNumber = pageNumber,
                    attemptNo = attemptNo,
                    layerRevision = layerRevision,
                    layerDigestSha256 = layerDigest,
                    cards = cards,
                    authorityEpoch = authorityEpoch,
                )
            }
        }
        if (input.available() != 0) {
            fail(RemoteReviewCodecError.INVALID_LENGTH) {
                "Remote-review payload has ${input.available()} trailing bytes."
            }
        }
        return envelope
    }

    private fun DataOutputStream.writeSnapshotReference(reference: SnapshotReference) {
        writeBoundedString(reference.transferId)
        writeBoundedString(reference.pageToken)
        writeLong(reference.revision)
        writeDimensions(reference.dimensions)
    }

    private fun DataInputStream.readSnapshotReference(): SnapshotReference = SnapshotReference(
        transferId = readBoundedString(RemoteReviewLimits.MAX_TOKEN_UTF8_BYTES),
        pageToken = readBoundedString(RemoteReviewLimits.MAX_TOKEN_UTF8_BYTES),
        revision = readLong(),
        dimensions = readDimensions(),
    )

    private fun DataOutputStream.writeDimensions(dimensions: ReviewCanvasDimensions) {
        writeInt(dimensions.widthPx)
        writeInt(dimensions.heightPx)
    }

    private fun DataInputStream.readDimensions(): ReviewCanvasDimensions = ReviewCanvasDimensions(
        widthPx = readInt(),
        heightPx = readInt(),
    )

    private fun DataOutputStream.writeBoundedString(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeBoundedString(value)
    }

    private fun DataOutputStream.writeNullablePositiveInt(value: Int?) {
        writeBoolean(value != null)
        if (value != null) writeInt(value)
    }

    private fun DataOutputStream.writeAttemptNos(attemptNos: List<Int>) {
        writeInt(attemptNos.size)
        attemptNos.forEach(::writeInt)
    }

    private fun DataInputStream.readBoundedString(maxBytes: Int): String {
        val length = readInt()
        if (length < 0 || length > maxBytes || length > available()) {
            fail(RemoteReviewCodecError.INVALID_LENGTH) {
                "String length $length is outside 0..$maxBytes or exceeds remaining input."
            }
        }
        val encoded = ByteArray(length)
        readFully(encoded)
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(encoded))
            .toString()
    }

    private fun DataInputStream.readNullableString(maxBytes: Int): String? = when (readUnsignedByte()) {
        0 -> null
        1 -> readBoundedString(maxBytes)
        else -> fail(RemoteReviewCodecError.MALFORMED_PAYLOAD) {
            "Nullable-string marker must be 0 or 1."
        }
    }

    private fun DataInputStream.readNullablePositiveInt(): Int? = when (readUnsignedByte()) {
        0 -> null
        1 -> readInt()
        else -> fail(RemoteReviewCodecError.MALFORMED_PAYLOAD) {
            "Nullable-integer marker must be 0 or 1."
        }
    }

    private fun DataInputStream.readBoundedBytes(maxBytes: Int): ByteArray {
        val length = readInt()
        if (length < 0 || length > maxBytes || length > available()) {
            fail(RemoteReviewCodecError.INVALID_LENGTH) {
                "Byte payload length $length is outside 0..$maxBytes or exceeds remaining input."
            }
        }
        return ByteArray(length).also(::readFully)
    }

    private fun DataInputStream.readBoundedCount(max: Int, name: String): Int {
        val count = readInt()
        if (count < 0 || count > max) {
            fail(RemoteReviewCodecError.MALFORMED_PAYLOAD) {
                "$name count $count is outside 0..$max."
            }
        }
        return count
    }

    private fun DataInputStream.readAttemptNos(): List<Int> {
        val count = readBoundedCount(
            RemoteReviewLimits.MAX_PAGE_SYNC_ATTEMPTS_PER_PAGE,
            "attempt",
        )
        return List(count) { readInt() }
    }

    private fun RemoteReviewEnvelopeType.wireCode(): Int = when (this) {
        RemoteReviewEnvelopeType.PAGE_SNAPSHOT -> 1
        RemoteReviewEnvelopeType.TEACHER_FEEDBACK -> 2
        RemoteReviewEnvelopeType.ACK -> 3
        RemoteReviewEnvelopeType.CHAT_MESSAGE -> 4
        RemoteReviewEnvelopeType.REMOTE_GRADE -> 5
        RemoteReviewEnvelopeType.PAGE_SYNC_MANIFEST -> 6
        RemoteReviewEnvelopeType.PAGE_SYNC_REQUEST -> 7
        RemoteReviewEnvelopeType.PAGE_ANNOTATION -> 8
        RemoteReviewEnvelopeType.PAGE_SYNC_ACK -> 9
        RemoteReviewEnvelopeType.GPT_EXPLANATION_LAYER -> 10
    }

    private fun envelopeTypeFromWire(code: Int): RemoteReviewEnvelopeType = when (code) {
        1 -> RemoteReviewEnvelopeType.PAGE_SNAPSHOT
        2 -> RemoteReviewEnvelopeType.TEACHER_FEEDBACK
        3 -> RemoteReviewEnvelopeType.ACK
        4 -> RemoteReviewEnvelopeType.CHAT_MESSAGE
        5 -> RemoteReviewEnvelopeType.REMOTE_GRADE
        6 -> RemoteReviewEnvelopeType.PAGE_SYNC_MANIFEST
        7 -> RemoteReviewEnvelopeType.PAGE_SYNC_REQUEST
        8 -> RemoteReviewEnvelopeType.PAGE_ANNOTATION
        9 -> RemoteReviewEnvelopeType.PAGE_SYNC_ACK
        10 -> RemoteReviewEnvelopeType.GPT_EXPLANATION_LAYER
        else -> fail(RemoteReviewCodecError.UNKNOWN_TYPE) { "Unknown envelope type $code." }
    }

    private fun PageAnnotationKind.wireCode(): Int = when (this) {
        PageAnnotationKind.DELTA -> 1
        PageAnnotationKind.CHECKPOINT -> 2
    }

    private fun pageAnnotationKindFromWire(code: Int): PageAnnotationKind = when (code) {
        1 -> PageAnnotationKind.DELTA
        2 -> PageAnnotationKind.CHECKPOINT
        else -> fail(RemoteReviewCodecError.MALFORMED_PAYLOAD) {
            "Unknown page annotation kind $code."
        }
    }

    private fun PageAnnotationPurpose.wireCode(): Int = when (this) {
        PageAnnotationPurpose.STUDENT_PAGE -> 1
        PageAnnotationPurpose.TEACHER_REVIEW -> 2
    }

    private fun pageAnnotationPurposeFromWire(code: Int): PageAnnotationPurpose = when (code) {
        1 -> PageAnnotationPurpose.STUDENT_PAGE
        2 -> PageAnnotationPurpose.TEACHER_REVIEW
        else -> fail(RemoteReviewCodecError.MALFORMED_PAYLOAD) {
            "Unknown page annotation purpose $code."
        }
    }

    private fun PageAnnotationCompression.wireCode(): Int = when (this) {
        PageAnnotationCompression.NONE -> 1
        PageAnnotationCompression.GZIP -> 2
    }

    private fun pageAnnotationCompressionFromWire(code: Int): PageAnnotationCompression = when (code) {
        1 -> PageAnnotationCompression.NONE
        2 -> PageAnnotationCompression.GZIP
        else -> fail(RemoteReviewCodecError.MALFORMED_PAYLOAD) {
            "Unknown page annotation compression $code."
        }
    }

    private fun PageSyncAckDisposition.wireCode(): Int = when (this) {
        PageSyncAckDisposition.APPLIED -> 1
        PageSyncAckDisposition.DUPLICATE -> 2
        PageSyncAckDisposition.REJECTED -> 3
    }

    private fun pageSyncAckDispositionFromWire(code: Int): PageSyncAckDisposition = when (code) {
        1 -> PageSyncAckDisposition.APPLIED
        2 -> PageSyncAckDisposition.DUPLICATE
        3 -> PageSyncAckDisposition.REJECTED
        else -> fail(RemoteReviewCodecError.MALFORMED_PAYLOAD) {
            "Unknown page sync ACK disposition $code."
        }
    }

    private fun PageSyncAckSourceType.wireCode(): Int = when (this) {
        PageSyncAckSourceType.REQUEST -> 1
        PageSyncAckSourceType.ANNOTATION -> 2
    }

    private fun pageSyncAckSourceTypeFromWire(code: Int): PageSyncAckSourceType = when (code) {
        1 -> PageSyncAckSourceType.REQUEST
        2 -> PageSyncAckSourceType.ANNOTATION
        else -> fail(RemoteReviewCodecError.MALFORMED_PAYLOAD) {
            "Unknown page sync ACK source type $code."
        }
    }

    private fun SnapshotImageFormat.wireCode(): Int = when (this) {
        SnapshotImageFormat.PNG -> 1
        SnapshotImageFormat.JPEG -> 2
    }

    private fun imageFormatFromWire(code: Int): SnapshotImageFormat = when (code) {
        1 -> SnapshotImageFormat.PNG
        2 -> SnapshotImageFormat.JPEG
        else -> fail(RemoteReviewCodecError.MALFORMED_PAYLOAD) { "Unknown image format $code." }
    }

    private fun TeacherInkTool.wireCode(): Int = when (this) {
        TeacherInkTool.PEN -> 1
        TeacherInkTool.HIGHLIGHTER -> 2
    }

    private fun inkToolFromWire(code: Int): TeacherInkTool = when (code) {
        1 -> TeacherInkTool.PEN
        2 -> TeacherInkTool.HIGHLIGHTER
        else -> fail(RemoteReviewCodecError.MALFORMED_PAYLOAD) { "Unknown ink tool $code." }
    }

    private fun RemoteReviewAckDisposition.wireCode(): Int = when (this) {
        RemoteReviewAckDisposition.APPLIED -> 1
        RemoteReviewAckDisposition.SUPERSEDED -> 2
        RemoteReviewAckDisposition.DUPLICATE -> 3
        RemoteReviewAckDisposition.REJECTED -> 4
    }

    private fun ackDispositionFromWire(code: Int): RemoteReviewAckDisposition = when (code) {
        1 -> RemoteReviewAckDisposition.APPLIED
        2 -> RemoteReviewAckDisposition.SUPERSEDED
        3 -> RemoteReviewAckDisposition.DUPLICATE
        4 -> RemoteReviewAckDisposition.REJECTED
        else -> fail(RemoteReviewCodecError.MALFORMED_PAYLOAD) { "Unknown ACK disposition $code." }
    }

    /**
     * Carries the exact-ink digest without changing the v1 PAGE_SNAPSHOT field layout. JPEG COM and
     * PNG tEXt are standard ancillary metadata, so a checkpoint reader still sees an ordinary image
     * ending at the legacy image field. The whole image remains covered by the document SHA and the
     * transport AES-GCM envelope.
     */
    private fun embedSnapshotDigest(
        format: SnapshotImageFormat,
        image: ByteArray,
        digest: String,
    ): ByteArray {
        extractSnapshotDigest(format, image)?.let { embedded ->
            if (embedded != digest) {
                fail(RemoteReviewCodecError.VALIDATION_FAILED) {
                    "Snapshot image already contains a different student-ink digest."
                }
            }
            return image
        }
        val embedded = when (format) {
            SnapshotImageFormat.JPEG -> embedJpegSnapshotDigest(image, digest)
            SnapshotImageFormat.PNG -> embedPngSnapshotDigest(image, digest)
        }
        if (embedded.size > RemoteReviewLimits.MAX_SNAPSHOT_IMAGE_BYTES) {
            fail(RemoteReviewCodecError.TOO_LARGE) {
                "Snapshot image plus compatibility metadata exceeds " +
                    "${RemoteReviewLimits.MAX_SNAPSHOT_IMAGE_BYTES} bytes."
            }
        }
        return embedded
    }

    private fun extractSnapshotDigest(
        format: SnapshotImageFormat,
        image: ByteArray,
    ): String? = when (format) {
        SnapshotImageFormat.JPEG -> extractJpegSnapshotDigest(image)
        SnapshotImageFormat.PNG -> extractPngSnapshotDigest(image)
    }

    private fun embedJpegSnapshotDigest(image: ByteArray, digest: String): ByteArray {
        if (image.size < 3 || image[0] != JPEG_SOI_FIRST || image[1] != JPEG_SOI_SECOND) {
            fail(RemoteReviewCodecError.VALIDATION_FAILED) {
                "Digest-bearing JPEG snapshot has no SOI marker."
            }
        }
        val payload = SNAPSHOT_DIGEST_JPEG_PREFIX + digest.toByteArray(StandardCharsets.US_ASCII)
        val segmentLength = payload.size + 2
        return ByteArray(image.size + payload.size + 4).also { result ->
            result[0] = image[0]
            result[1] = image[1]
            result[2] = JPEG_MARKER_PREFIX
            result[3] = JPEG_COMMENT_MARKER
            result[4] = (segmentLength ushr 8).toByte()
            result[5] = segmentLength.toByte()
            payload.copyInto(result, destinationOffset = 6)
            image.copyInto(result, destinationOffset = 6 + payload.size, startIndex = 2)
        }
    }

    private fun extractJpegSnapshotDigest(image: ByteArray): String? {
        if (image.size < 6 || image[0] != JPEG_SOI_FIRST || image[1] != JPEG_SOI_SECOND ||
            image[2] != JPEG_MARKER_PREFIX || image[3] != JPEG_COMMENT_MARKER
        ) return null
        val segmentLength = ((image[4].toInt() and 0xff) shl 8) or
            (image[5].toInt() and 0xff)
        if (segmentLength < 2) return null
        val payloadStart = 6
        val payloadEnd = 4 + segmentLength
        if (payloadEnd > image.size) return null
        return decodeSnapshotDigestMetadata(image, payloadStart, payloadEnd, SNAPSHOT_DIGEST_JPEG_PREFIX)
    }

    private fun embedPngSnapshotDigest(image: ByteArray, digest: String): ByteArray {
        val ihdrEnd = pngFirstChunkEnd(image)
            ?: fail(RemoteReviewCodecError.VALIDATION_FAILED) {
                "Digest-bearing PNG snapshot has no valid first IHDR chunk."
            }
        val payload = SNAPSHOT_DIGEST_PNG_PREFIX + digest.toByteArray(StandardCharsets.US_ASCII)
        val chunk = ByteArray(12 + payload.size)
        writeIntBigEndian(chunk, 0, payload.size)
        PNG_TEXT_CHUNK.copyInto(chunk, destinationOffset = 4)
        payload.copyInto(chunk, destinationOffset = 8)
        val crc = CRC32().apply {
            update(PNG_TEXT_CHUNK)
            update(payload)
        }.value
        writeIntBigEndian(chunk, 8 + payload.size, crc.toInt())
        return ByteArray(image.size + chunk.size).also { result ->
            image.copyInto(result, endIndex = ihdrEnd)
            chunk.copyInto(result, destinationOffset = ihdrEnd)
            image.copyInto(result, destinationOffset = ihdrEnd + chunk.size, startIndex = ihdrEnd)
        }
    }

    private fun extractPngSnapshotDigest(image: ByteArray): String? {
        if (!image.startsWith(PNG_FILE_SIGNATURE)) return null
        var offset = PNG_FILE_SIGNATURE.size
        while (offset <= image.size - PNG_CHUNK_OVERHEAD_BYTES) {
            val length = readUnsignedIntBigEndian(image, offset)
            if (length > Int.MAX_VALUE.toLong()) return null
            val dataStart = offset + 8
            val dataEndLong = dataStart.toLong() + length
            val chunkEndLong = dataEndLong + 4L
            if (chunkEndLong > image.size.toLong()) return null
            val dataEnd = dataEndLong.toInt()
            val chunkEnd = chunkEndLong.toInt()
            if (image.matchesAt(offset + 4, PNG_TEXT_CHUNK)) {
                decodeSnapshotDigestMetadata(
                    image,
                    dataStart,
                    dataEnd,
                    SNAPSHOT_DIGEST_PNG_PREFIX,
                )?.let { return it }
            }
            if (image.matchesAt(offset + 4, PNG_END_CHUNK)) return null
            offset = chunkEnd
        }
        return null
    }

    private fun pngFirstChunkEnd(image: ByteArray): Int? {
        if (!image.startsWith(PNG_FILE_SIGNATURE) ||
            image.size < PNG_FILE_SIGNATURE.size + PNG_CHUNK_OVERHEAD_BYTES
        ) return null
        val offset = PNG_FILE_SIGNATURE.size
        val length = readUnsignedIntBigEndian(image, offset)
        if (length != PNG_IHDR_DATA_BYTES.toLong() || !image.matchesAt(offset + 4, PNG_IHDR_CHUNK)) {
            return null
        }
        val end = offset.toLong() + PNG_CHUNK_OVERHEAD_BYTES + length
        return end.takeIf { it <= image.size.toLong() }?.toInt()
    }

    private fun decodeSnapshotDigestMetadata(
        bytes: ByteArray,
        start: Int,
        end: Int,
        prefix: ByteArray,
    ): String? {
        if (end - start != prefix.size + RemoteReviewLimits.SHA256_HEX_BYTES ||
            !bytes.matchesAt(start, prefix)
        ) return null
        val digest = String(
            bytes,
            start + prefix.size,
            RemoteReviewLimits.SHA256_HEX_BYTES,
            StandardCharsets.US_ASCII,
        )
        return digest.takeIf(SNAPSHOT_DIGEST_HEX::matches)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = matchesAt(0, prefix)

    private fun ByteArray.matchesAt(offset: Int, expected: ByteArray): Boolean {
        if (offset < 0 || offset > size - expected.size) return false
        return expected.indices.all { index -> this[offset + index] == expected[index] }
    }

    private fun readUnsignedIntBigEndian(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset > bytes.size - 4) return Long.MAX_VALUE
        return ((bytes[offset].toLong() and 0xffL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
            (bytes[offset + 3].toLong() and 0xffL)
    }

    private fun writeIntBigEndian(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private inline fun fail(
        error: RemoteReviewCodecError,
        lazyMessage: () -> String,
    ): Nothing = throw RemoteReviewCodecException(error, lazyMessage())

    private const val MAGIC: Int = 0x4d4e5252 // MNRR
    private const val VERSION: Int = 1
    private const val SHA256_BYTES: Int = 32
    private const val FRAME_BYTES: Int = 4 + 1 + 1 + 4 + SHA256_BYTES
    private const val MAX_DETAIL_CODE_BYTES: Int = 64
    private const val PAGE_ANNOTATION_CHUNK_EXTENSION_VERSION: Int = 1
    private const val GPT_AUTHORITY_EPOCH_EXTENSION_VERSION: Int = 1
    private const val PNG_CHUNK_OVERHEAD_BYTES: Int = 12
    private const val PNG_IHDR_DATA_BYTES: Int = 13
    private val SNAPSHOT_DIGEST_HEX = Regex("[0-9a-f]{${RemoteReviewLimits.SHA256_HEX_BYTES}}")
    private val SNAPSHOT_DIGEST_JPEG_PREFIX = "MNRRINK1:".toByteArray(StandardCharsets.US_ASCII)
    private val SNAPSHOT_DIGEST_PNG_PREFIX =
        "MasterNoteInkDigest\u0000".toByteArray(StandardCharsets.US_ASCII)
    private val PNG_FILE_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    )
    private val PNG_IHDR_CHUNK = byteArrayOf(0x49, 0x48, 0x44, 0x52)
    private val PNG_TEXT_CHUNK = byteArrayOf(0x74, 0x45, 0x58, 0x74)
    private val PNG_END_CHUNK = byteArrayOf(0x49, 0x45, 0x4e, 0x44)
    private val JPEG_SOI_FIRST = 0xff.toByte()
    private val JPEG_SOI_SECOND = 0xd8.toByte()
    private val JPEG_MARKER_PREFIX = 0xff.toByte()
    private val JPEG_COMMENT_MARKER = 0xfe.toByte()
}
