package com.studyink.app

import com.studyink.core.model.CANONICAL_PAGE_WIDTH
import com.studyink.core.model.Mark
import com.studyink.core.model.MarkColor
import com.studyink.core.model.MarkGroup
import com.studyink.core.model.PagePoint
import com.studyink.core.model.TEACHER_PAGE_REVIEW_ATTEMPT_NO
import com.studyink.core.model.TeacherReviewPublicationLimits
import com.studyink.monitor.core.RemoteReviewLimits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections

/**
 * Decoded app-level contents of a page-scoped PAGE_ANNOTATION/CHECKPOINT.
 *
 * [pageNumber] is deliberately one-based on the peer wire. Each [MarkGroup.pageNumber] remains the
 * lossless zero-based core-model value and is checked against `pageNumber - 1`.
 */
internal class RemoteTeacherPageReviewPayload internal constructor(
    val version: Int,
    val pageNumber: Int,
    val attemptNo: Int,
    publishedTeacherCheckpoint: ByteArray,
    markGroups: List<MarkGroup>,
) {
    private val immutablePublishedTeacherCheckpoint = publishedTeacherCheckpoint.copyOf()

    val publishedTeacherCheckpointSizeBytes: Int
        get() = immutablePublishedTeacherCheckpoint.size

    /** A fresh copy prevents the durable checkpoint from changing after validation. */
    fun copyPublishedTeacherCheckpoint(): ByteArray = immutablePublishedTeacherCheckpoint.copyOf()

    val markGroups: List<MarkGroup> = immutableMarkGroupsCopy(markGroups)

    internal fun checkpointBytesForCodec(): ByteArray = immutablePublishedTeacherCheckpoint
}

/**
 * Strict deterministic codec nested inside [com.studyink.monitor.core.PageAnnotationEnvelope].
 *
 * The small binary schema avoids Android/JSON parser differences and carries the already-binary
 * checkpoint without a second Base64 expansion. Floats and longs retain their exact JVM wire
 * representations. Unknown versions, invalid lengths, and trailing fields are rejected.
 */
internal object RemoteTeacherPageReviewCodec {
    private const val ROOT_FIXED_BYTES: Int = TeacherReviewPublicationLimits.ROOT_FIXED_BYTES
    const val VERSION: Int = 1

    /** Must itself fit inside the monitor-core CHECKPOINT payload. */
    const val MAX_ENCODED_BYTES: Int = RemoteReviewLimits.MAX_PAGE_ANNOTATION_CHECKPOINT_BYTES

    /** Maximum when no mark groups are present; ordinary payloads have less remaining space. */
    const val MAX_PUBLISHED_CHECKPOINT_BYTES: Int =
        MAX_ENCODED_BYTES - ROOT_FIXED_BYTES

    fun encode(
        pageNumber: Int,
        attemptNo: Int,
        publishedTeacherCheckpoint: ByteArray,
        markGroups: List<MarkGroup>,
    ): ByteArray {
        val payload = RemoteTeacherPageReviewPayload(
            version = VERSION,
            pageNumber = pageNumber,
            attemptNo = attemptNo,
            publishedTeacherCheckpoint = publishedTeacherCheckpoint,
            markGroups = markGroups,
        )
        validatePayload(payload)

        val checkpoint = payload.checkpointBytesForCodec()
        val encodedSize = encodedSize(payload, checkpoint.size)
        requireReview(encodedSize <= MAX_ENCODED_BYTES) {
            "Teacher page review exceeds $MAX_ENCODED_BYTES bytes"
        }

        return ByteArrayOutputStream(encodedSize).use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(payload.version)
                output.writeInt(payload.pageNumber)
                output.writeInt(payload.attemptNo)
                output.writeInt(checkpoint.size)
                output.write(checkpoint)
                output.writeInt(payload.markGroups.size)
                payload.markGroups.forEach { group -> output.writeMarkGroup(group) }
            }
            bytes.toByteArray().also { encoded ->
                check(encoded.size == encodedSize)
            }
        }
    }

    fun decode(bytes: ByteArray): RemoteTeacherPageReviewPayload {
        requireReview(bytes.size in MIN_ENCODED_BYTES..MAX_ENCODED_BYTES) {
            "Teacher page review size is outside $MIN_ENCODED_BYTES..$MAX_ENCODED_BYTES"
        }
        try {
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                requireReview(input.readInt() == MAGIC) { "Teacher page review header is invalid" }
                val version = input.readInt()
                requireReview(version == VERSION) { "Unsupported teacher page review version $version" }
                val pageNumber = input.readInt()
                val attemptNo = input.readInt()
                val checkpoint = input.readBoundedBytes(
                    MAX_PUBLISHED_CHECKPOINT_BYTES,
                    "published teacher checkpoint",
                )
                requireReview(checkpoint.isNotEmpty()) { "Published teacher checkpoint is empty" }

                val groupCount = input.readBoundedCount(MAX_MARK_GROUPS, "mark group")
                val groups = ArrayList<MarkGroup>(groupCount)
                var totalMarks = 0
                repeat(groupCount) {
                    val decodedGroup = input.readMarkGroup()
                    totalMarks = Math.addExact(totalMarks, decodedGroup.marks.size)
                    requireReview(totalMarks <= MAX_TOTAL_MARKS) {
                        "Teacher page review has too many marks"
                    }
                    groups += decodedGroup
                }
                requireReview(input.available() == 0) {
                    "Teacher page review has trailing schema bytes"
                }

                RemoteTeacherPageReviewPayload(
                    version = version,
                    pageNumber = pageNumber,
                    attemptNo = attemptNo,
                    publishedTeacherCheckpoint = checkpoint,
                    markGroups = groups,
                ).also(::validatePayload)
            }
        } catch (expected: RemoteTeacherPageReviewException) {
            throw expected
        } catch (expected: CharacterCodingException) {
            throw RemoteTeacherPageReviewException("Teacher page review contains malformed UTF-8", expected)
        } catch (expected: EOFException) {
            throw RemoteTeacherPageReviewException("Teacher page review is truncated", expected)
        } catch (expected: IOException) {
            throw RemoteTeacherPageReviewException("Teacher page review cannot be decoded", expected)
        } catch (expected: ArithmeticException) {
            throw RemoteTeacherPageReviewException("Teacher page review count overflowed", expected)
        } catch (expected: RuntimeException) {
            throw RemoteTeacherPageReviewException("Teacher page review is malformed", expected)
        }
    }

    private fun validatePayload(payload: RemoteTeacherPageReviewPayload) {
        requireReview(payload.version == VERSION) { "Teacher page review version is invalid" }
        requireReview(payload.pageNumber in 1..MAX_PAGE_NUMBER) {
            "Teacher page review page must be one-based"
        }
        requireReview(payload.attemptNo in 1..MAX_ATTEMPT_NO) {
            "Teacher page review attempt must be one-based"
        }
        requireReview(
            payload.publishedTeacherCheckpointSizeBytes in 1..MAX_PUBLISHED_CHECKPOINT_BYTES,
        ) { "Published teacher checkpoint size is invalid" }
        requireReview(payload.markGroups.size <= MAX_MARK_GROUPS) {
            "Teacher page review has too many mark groups"
        }

        val expectedCorePage = payload.pageNumber - 1
        val groupIds = HashSet<String>(payload.markGroups.size)
        var bookId: String? = null
        var totalMarks = 0
        payload.markGroups.forEach { group ->
            validateId(group.id, "mark group id", allowEmpty = false)
            requireReview(groupIds.add(group.id)) { "Mark group ids must be unique" }
            validateId(group.bookId, "mark group book id", allowEmpty = false)
            requireReview(group.pageNumber == expectedCorePage) {
                "Mark group page does not match the exact payload page"
            }
            if (bookId == null) bookId = group.bookId
            requireReview(bookId == group.bookId) {
                "Mark groups from different books cannot share one page payload"
            }
            validateAnchor(group.anchor)
            validateTime(group.createdAtEpochMillis, "mark group createdAt")
            validateNullableTime(group.hiddenAtEpochMillis, "mark group hiddenAt")
            requireReview(group.syncRevision >= 0L) { "Mark group revision must not be negative" }
            validateId(
                group.lastModifiedByDeviceId,
                "mark group lastModifiedByDeviceId",
                allowEmpty = group.syncRevision == 0L,
            )
            requireReview(group.marks.size in 1..MAX_MARKS_PER_GROUP) {
                "Mark group history size is invalid"
            }
            requireReview(group.marks.all { it.attemptNo == payload.attemptNo }) {
                "Mark group contains another attempt"
            }
            group.marks.forEach(::validateMark)
            totalMarks = Math.addExact(totalMarks, group.marks.size)
            requireReview(totalMarks <= MAX_TOTAL_MARKS) {
                "Teacher page review has too many marks"
            }
        }
    }

    private fun validateAnchor(anchor: PagePoint) {
        requireReview(anchor.x.isFinite() && anchor.x in 0f..CANONICAL_PAGE_WIDTH) {
            "Mark group anchor x is invalid"
        }
        requireReview(anchor.y.isFinite() && anchor.y in 0f..MAX_CANONICAL_PAGE_HEIGHT) {
            "Mark group anchor y is invalid"
        }
        requireReview(anchor.pressure.isFinite() && anchor.pressure in 0f..1f) {
            "Mark group anchor pressure is invalid"
        }
    }

    private fun validateMark(mark: Mark) {
        requireReview(mark.attemptNo in TEACHER_PAGE_REVIEW_ATTEMPT_NO..MAX_ATTEMPT_NO) {
            "Mark attempt is invalid"
        }
        validateTime(mark.gradedAtEpochMillis, "mark gradedAt")
        validateNullableTime(mark.hiddenAtEpochMillis, "mark hiddenAt")
    }

    private fun validateId(value: String, field: String, allowEmpty: Boolean) {
        if (allowEmpty && value.isEmpty()) return
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        requireReview(encoded.size in 1..MAX_ID_UTF8_BYTES && REVIEW_ID.matches(value)) {
            "$field is invalid"
        }
    }

    private fun validateTime(value: Long, field: String) {
        requireReview(value >= 0L) { "$field is invalid" }
    }

    private fun validateNullableTime(value: Long?, field: String) {
        requireReview(value == null || value >= 0L) { "$field is invalid" }
    }

    private fun encodedSize(payload: RemoteTeacherPageReviewPayload, checkpointBase64Bytes: Int): Int {
        val size = TeacherReviewPublicationLimits.encodedSize(checkpointBase64Bytes, payload.markGroups)
        requireReview(size <= MAX_ENCODED_BYTES) {
            "Teacher page review exceeds $MAX_ENCODED_BYTES bytes"
        }
        return size
    }

    private fun DataOutputStream.writeMarkGroup(group: MarkGroup) {
        writeUtf8(group.id)
        writeUtf8(group.bookId)
        writeInt(group.pageNumber)
        writeFloat(group.anchor.x)
        writeFloat(group.anchor.y)
        writeFloat(group.anchor.pressure)
        writeLong(group.createdAtEpochMillis)
        writeNullableLong(group.hiddenAtEpochMillis)
        writeLong(group.syncRevision)
        writeUtf8(group.lastModifiedByDeviceId)
        writeInt(group.marks.size)
        group.marks.forEach { mark ->
            writeInt(mark.attemptNo)
            writeByte(mark.color.wireCode())
            writeLong(mark.gradedAtEpochMillis)
            writeNullableLong(mark.hiddenAtEpochMillis)
        }
    }

    private fun DataInputStream.readMarkGroup(): MarkGroup {
        val id = readUtf8(MAX_ID_UTF8_BYTES, "mark group id")
        val bookId = readUtf8(MAX_ID_UTF8_BYTES, "mark group book id")
        val pageNumber = readInt()
        val anchor = PagePoint(readFloat(), readFloat(), readFloat())
        val createdAt = readLong()
        val hiddenAt = readNullableLong("mark group hiddenAt")
        val syncRevision = readLong()
        val lastModifiedByDeviceId = readUtf8(
            MAX_ID_UTF8_BYTES,
            "mark group lastModifiedByDeviceId",
        )
        val markCount = readBoundedCount(MAX_MARKS_PER_GROUP, "mark")
        requireReview(markCount > 0) { "Mark group history is empty" }
        val marks = ArrayList<Mark>(markCount)
        repeat(markCount) {
            marks += Mark(
                attemptNo = readInt(),
                color = markColorFromWire(readUnsignedByte()),
                gradedAtEpochMillis = readLong(),
                hiddenAtEpochMillis = readNullableLong("mark hiddenAt"),
            )
        }
        return MarkGroup(
            id = id,
            bookId = bookId,
            pageNumber = pageNumber,
            anchor = anchor,
            marks = immutableListCopy(marks),
            createdAtEpochMillis = createdAt,
            hiddenAtEpochMillis = hiddenAt,
            syncRevision = syncRevision,
            lastModifiedByDeviceId = lastModifiedByDeviceId,
        )
    }

    private fun DataOutputStream.writeUtf8(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(encoded.size)
        write(encoded)
    }

    private fun DataInputStream.readUtf8(maxBytes: Int, field: String): String {
        val encoded = readBoundedBytes(maxBytes, field)
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(encoded))
            .toString()
    }

    private fun DataInputStream.readBoundedBytes(maxBytes: Int, field: String): ByteArray {
        val length = readInt()
        requireReview(length in 0..maxBytes && length <= available()) {
            "$field length is invalid"
        }
        return ByteArray(length).also(::readFully)
    }

    private fun DataInputStream.readBoundedCount(maxCount: Int, field: String): Int {
        val count = readInt()
        requireReview(count in 0..maxCount) { "$field count is invalid" }
        return count
    }

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeByte(if (value == null) 0 else 1)
        if (value != null) writeLong(value)
    }

    private fun DataInputStream.readNullableLong(field: String): Long? = when (readUnsignedByte()) {
        0 -> null
        1 -> readLong()
        else -> throw RemoteTeacherPageReviewException("$field marker is invalid")
    }

    private fun MarkColor.wireCode(): Int = when (this) {
        MarkColor.BLUE -> 1
        MarkColor.RED -> 2
        MarkColor.GRAY -> 3
    }

    private fun markColorFromWire(code: Int): MarkColor = when (code) {
        1 -> MarkColor.BLUE
        2 -> MarkColor.RED
        3 -> MarkColor.GRAY
        else -> throw RemoteTeacherPageReviewException("Mark color $code is invalid")
    }

    private fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size

    private const val MAGIC: Int = 0x4d4e5452 // MNTR
    private const val MIN_ENCODED_BYTES: Int = ROOT_FIXED_BYTES + 1
    private const val MAX_PAGE_NUMBER: Int = 1_000_000
    private const val MAX_ATTEMPT_NO: Int = 1_000_000
    private const val MAX_ID_UTF8_BYTES: Int = 256
    private const val MAX_MARK_GROUPS: Int = 4_096
    private const val MAX_MARKS_PER_GROUP: Int = 4_096
    private const val MAX_TOTAL_MARKS: Int = 16_384
    private const val MAX_CANONICAL_PAGE_HEIGHT: Float = 1_000_000f
    private val REVIEW_ID = Regex("[A-Za-z0-9._:-]+")
}

internal class RemoteTeacherPageReviewException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

private fun immutableMarkGroupsCopy(values: List<MarkGroup>): List<MarkGroup> = immutableListCopy(
    values.map { group ->
        group.copy(marks = immutableListCopy(group.marks))
    },
)

private fun <T> immutableListCopy(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private inline fun requireReview(condition: Boolean, lazyMessage: () -> String) {
    if (!condition) throw RemoteTeacherPageReviewException(lazyMessage())
}
