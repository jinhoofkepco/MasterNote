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
                    val image = envelope.renderedPageBytesForCodec()
                    output.writeInt(image.size)
                    output.write(image)
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

    private fun RemoteReviewEnvelopeType.wireCode(): Int = when (this) {
        RemoteReviewEnvelopeType.PAGE_SNAPSHOT -> 1
        RemoteReviewEnvelopeType.TEACHER_FEEDBACK -> 2
        RemoteReviewEnvelopeType.ACK -> 3
    }

    private fun envelopeTypeFromWire(code: Int): RemoteReviewEnvelopeType = when (code) {
        1 -> RemoteReviewEnvelopeType.PAGE_SNAPSHOT
        2 -> RemoteReviewEnvelopeType.TEACHER_FEEDBACK
        3 -> RemoteReviewEnvelopeType.ACK
        else -> fail(RemoteReviewCodecError.UNKNOWN_TYPE) { "Unknown envelope type $code." }
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
}
