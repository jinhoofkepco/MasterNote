package com.studyink.monitor.core

import java.security.MessageDigest
import java.util.UUID

/**
 * Complete student-authored memo state for one exact workbook page and attempt.
 *
 * The payload is intentionally opaque to the transport module. Memo storage owns its schema;
 * Telegram only validates identity, size and integrity before handing the bytes to that owner.
 */
class StudentMemoEnvelope(
    override val transferId: String,
    override val createdAtEpochMs: Long,
    val syncGeneration: Long,
    val pageToken: String,
    val workbookToken: String,
    val contentSha256: String,
    /** One-based on the wire; local stores may remain zero-based. */
    val pageNumber: Int,
    val attemptNo: Int,
    val memoId: String,
    val memoRevision: Long,
    val memoDigestSha256: String,
    val payloadSha256: String,
    payloadBytes: ByteArray,
    /** Requires the v2 outer frame so an older peer retains the document without a false ACK. */
    val extendedCanvas: Boolean = false,
    /** Non-null always uses an outer v3 frame, including a one-fragment compact memo. */
    val chunk: StudentMemoChunkInfo? = null,
) : RemoteReviewEnvelope {
    override val type: RemoteReviewEnvelopeType = RemoteReviewEnvelopeType.STUDENT_MEMO

    private val immutablePayloadBytes = payloadBytes.copyOf()

    val payloadSizeBytes: Int get() = immutablePayloadBytes.size

    fun copyPayloadBytes(): ByteArray = immutablePayloadBytes.copyOf()

    internal fun payloadBytesForCodec(): ByteArray = immutablePayloadBytes

    init {
        validateCommonEnvelope(transferId, createdAtEpochMs)
        checkProtocol(syncGeneration >= 1L, "syncGeneration") { "must be at least 1" }
        validateOpaqueToken(pageToken, "pageToken")
        validateOpaqueToken(workbookToken, "workbookToken")
        validateMemoSha256(contentSha256, "contentSha256")
        checkProtocol(pageNumber > 0, "pageNumber") { "must be one-based" }
        checkProtocol(attemptNo > 0, "attemptNo") { "must be one-based" }
        validateOpaqueToken(memoId, "memoId")
        checkProtocol(memoRevision >= 1L, "memoRevision") { "must be at least 1" }
        validateMemoSha256(memoDigestSha256, "memoDigestSha256")
        validateMemoSha256(payloadSha256, "payloadSha256")
        checkProtocol(immutablePayloadBytes.isNotEmpty(), "payloadBytes") {
            "must not be empty"
        }
        checkProtocol(
            immutablePayloadBytes.size <= RemoteReviewLimits.MAX_STUDENT_MEMO_BYTES,
            "payloadBytes",
        ) {
            "exceeds ${RemoteReviewLimits.MAX_STUDENT_MEMO_BYTES} bytes"
        }
        checkProtocol(
            MessageDigest.isEqual(
                payloadSha256.toByteArray(Charsets.US_ASCII),
                studentMemoPayloadSha256Hex(immutablePayloadBytes).toByteArray(Charsets.US_ASCII),
            ),
            "payloadSha256",
        ) { "does not match payloadBytes" }
        chunk?.let {
            checkProtocol(transferId == studentMemoChunkTransferId(it.groupId, it.index), "transferId") { "does not match chunk identity" }
            checkProtocol(payloadSizeBytes == minOf(RemoteReviewLimits.STUDENT_MEMO_CHUNK_BYTES,
                it.totalBytes - it.index * RemoteReviewLimits.STUDENT_MEMO_CHUNK_BYTES), "payloadBytes") { "incorrect chunk size" }
        }
    }
}

data class StudentMemoChunkInfo(val groupId: String, val index: Int, val count: Int,
    val totalBytes: Int, val completeSha256: String) {
    init {
        validateOpaqueToken(groupId, "chunk.groupId")
        validateMemoSha256(completeSha256, "chunk.completeSha256")
        checkProtocol(totalBytes in 1..RemoteReviewLimits.MAX_STUDENT_MEMO_ASSEMBLED_BYTES, "chunk.totalBytes") { "invalid assembly size" }
        checkProtocol(count in 1..RemoteReviewLimits.MAX_STUDENT_MEMO_CHUNKS &&
            count == (totalBytes + RemoteReviewLimits.STUDENT_MEMO_CHUNK_BYTES - 1) / RemoteReviewLimits.STUDENT_MEMO_CHUNK_BYTES,
            "chunk.count") { "invalid fragment count" }
        checkProtocol(index in 0 until count, "chunk.index") { "invalid fragment index" }
    }
}

fun studentMemoChunkTransferId(groupId: String, index: Int): String =
    UUID.nameUUIDFromBytes("memo-v3:$groupId:$index".toByteArray(Charsets.UTF_8)).toString()

/** Stateless assembly: unacknowledged gateway files, not this memory, are the restart journal. */
object StudentMemoChunks {
    fun compatible(first: StudentMemoEnvelope, next: StudentMemoEnvelope): Boolean {
        val a = first.chunk ?: return false
        val b = next.chunk ?: return false
        return a.copy(index = b.index) == b && first.createdAtEpochMs == next.createdAtEpochMs &&
            first.syncGeneration == next.syncGeneration && first.pageToken == next.pageToken &&
            first.workbookToken == next.workbookToken && first.contentSha256 == next.contentSha256 &&
            first.pageNumber == next.pageNumber && first.attemptNo == next.attemptNo && first.memoId == next.memoId &&
            first.memoRevision == next.memoRevision && first.memoDigestSha256 == next.memoDigestSha256 &&
            first.extendedCanvas == next.extendedCanvas
    }

    fun assemble(frames: Collection<StudentMemoEnvelope>): ByteArray? {
        val first = frames.firstOrNull() ?: return null
        val header = requireNotNull(first.chunk)
        val slots = arrayOfNulls<StudentMemoEnvelope>(header.count)
        frames.forEach { frame ->
            require(compatible(first, frame)) { "Memo fragment identity changed" }
            val index = requireNotNull(frame.chunk).index
            val old = slots[index]
            require(old == null || old.payloadSha256 == frame.payloadSha256) { "Memo fragment changed during retry" }
            slots[index] = frame
        }
        if (slots.any { it == null }) return null
        val bytes = ByteArray(header.totalBytes)
        slots.forEachIndexed { index, frame -> requireNotNull(frame).payloadBytesForCodec()
            .copyInto(bytes, index * RemoteReviewLimits.STUDENT_MEMO_CHUNK_BYTES) }
        require(studentMemoPayloadSha256Hex(bytes) == header.completeSha256) { "Memo assembly checksum mismatch" }
        return bytes
    }
}

/** Lower-case SHA-256 of the opaque memo snapshot bytes. */
fun studentMemoPayloadSha256Hex(payloadBytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(payloadBytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun validateMemoSha256(value: String, field: String) {
    checkProtocol(MEMO_SHA256.matches(value), field) {
        "must be exactly ${RemoteReviewLimits.SHA256_HEX_BYTES} lower-case hexadecimal characters"
    }
}

private val MEMO_SHA256 = Regex("[0-9a-f]{${RemoteReviewLimits.SHA256_HEX_BYTES}}")
