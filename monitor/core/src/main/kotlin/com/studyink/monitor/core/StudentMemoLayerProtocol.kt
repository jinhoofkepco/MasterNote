package com.studyink.monitor.core

import java.security.MessageDigest

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
