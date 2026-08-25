package com.studyink.monitor.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * The student's currently visible page. [sequence] must advance whenever this cursor changes, even
 * if the page annotation revision did not, so a delayed Telegram manifest cannot move a viewer
 * backwards after reconnecting.
 */
data class PageSyncCursor(
    val sequence: Long,
    val pageToken: String,
    val pageNumber: Int,
    val currentAttemptNo: Int?,
    val revision: Long,
) {
    init {
        checkProtocol(sequence >= 1L, "currentCursor.sequence") { "must be at least 1" }
        validateOpaqueToken(pageToken, "currentCursor.pageToken")
        checkProtocol(pageNumber > 0, "currentCursor.pageNumber") { "must be one-based" }
        checkProtocol(currentAttemptNo == null || currentAttemptNo > 0, "currentCursor.currentAttemptNo") {
            "must be null or one-based"
        }
        checkProtocol(revision >= 0L, "currentCursor.revision") { "must not be negative" }
    }
}

/** One page-level manifest row. [attemptNos] lists all student layers represented by the digest. */
class PageSyncManifestEntry(
    val pageToken: String,
    val workbookToken: String,
    val contentSha256: String,
    val studentLayerSha256: String,
    val pageNumber: Int,
    attemptNos: List<Int>,
    submittedAttemptNos: List<Int>,
    val revision: Long,
    val lastChangedEpochMs: Long,
    val approxBytes: Long,
) {
    val attemptNos: List<Int> = immutableListCopy(attemptNos)
    val submittedAttemptNos: List<Int> = immutableListCopy(submittedAttemptNos)
    val submitted: Boolean get() = submittedAttemptNos.isNotEmpty()

    init {
        validateOpaqueToken(pageToken, "entries.pageToken")
        validateOpaqueToken(workbookToken, "entries.workbookToken")
        validateSha256Hex(contentSha256, "entries.contentSha256")
        validateSha256Hex(studentLayerSha256, "entries.studentLayerSha256")
        checkProtocol(pageNumber > 0, "entries.pageNumber") { "must be one-based" }
        validateAttemptNos(this.attemptNos, "entries.attemptNos", allowEmpty = true)
        validateAttemptNos(
            this.submittedAttemptNos,
            "entries.submittedAttemptNos",
            allowEmpty = true,
        )
        checkProtocol(
            this.attemptNos.containsAll(this.submittedAttemptNos),
            "entries.submittedAttemptNos",
        ) { "must be a subset of entries.attemptNos" }
        checkProtocol(revision >= 0L, "entries.revision") { "must not be negative" }
        checkProtocol(lastChangedEpochMs >= 0L, "entries.lastChangedEpochMs") {
            "must not be negative"
        }
        checkProtocol(approxBytes in 0L..RemoteReviewLimits.MAX_PAGE_SYNC_APPROX_BYTES, "entries.approxBytes") {
            "must be between 0 and ${RemoteReviewLimits.MAX_PAGE_SYNC_APPROX_BYTES}"
        }
    }
}

/**
 * A bounded page inventory. The current page is explicit while each page has one row containing all
 * known attempt numbers, keeping queueing and UI ownership page-scoped.
 */
class PageSyncManifestEnvelope(
    override val transferId: String,
    override val createdAtEpochMs: Long,
    val syncGeneration: Long,
    val sequence: Long,
    val currentCursor: PageSyncCursor?,
    entries: List<PageSyncManifestEntry>,
    /** Total durable page rows in this inventory. Null is accepted only for older queued frames. */
    val inventoryPageCount: Int? = null,
) : RemoteReviewEnvelope {
    override val type: RemoteReviewEnvelopeType = RemoteReviewEnvelopeType.PAGE_SYNC_MANIFEST

    val entries: List<PageSyncManifestEntry> = immutableListCopy(entries)

    init {
        validateCommonEnvelope(transferId, createdAtEpochMs)
        validateSyncGeneration(syncGeneration)
        checkProtocol(sequence >= 1L, "sequence") { "must be at least 1" }
        checkProtocol(
            this.entries.size <= RemoteReviewLimits.MAX_PAGE_SYNC_MANIFEST_ENTRIES,
            "entries",
        ) { "exceeds ${RemoteReviewLimits.MAX_PAGE_SYNC_MANIFEST_ENTRIES} pages" }
        checkProtocol(
            inventoryPageCount == null || inventoryPageCount in
                this.entries.size..RemoteReviewLimits.MAX_PAGE_SYNC_INVENTORY_PAGES,
            "inventoryPageCount",
        ) {
            "must include every row in this window and not exceed " +
                RemoteReviewLimits.MAX_PAGE_SYNC_INVENTORY_PAGES
        }

        val pageTokens = HashSet<String>(this.entries.size)
        val workbookPages = HashSet<Pair<String, Int>>(this.entries.size)
        this.entries.forEach { entry ->
            checkProtocol(pageTokens.add(entry.pageToken), "entries.pageToken") {
                "must be unique within a manifest"
            }
            checkProtocol(
                workbookPages.add(entry.workbookToken to entry.pageNumber),
                "entries.pageNumber",
            ) {
                "must be unique per workbookToken within a manifest"
            }
        }

        currentCursor?.let { cursor ->
            checkProtocol(cursor.sequence == sequence, "currentCursor.sequence") {
                "must match the enclosing manifest sequence"
            }
            val entry = this.entries.firstOrNull { it.pageToken == cursor.pageToken }
            checkProtocol(entry != null, "currentCursor.pageToken") {
                "must reference an entry in the same manifest"
            }
            checkProtocol(entry!!.pageNumber == cursor.pageNumber, "currentCursor.pageNumber") {
                "does not match the referenced manifest entry"
            }
            checkProtocol(entry.revision == cursor.revision, "currentCursor.revision") {
                "does not match the referenced manifest entry"
            }
            cursor.currentAttemptNo?.let { attemptNo ->
                checkProtocol(attemptNo in entry.attemptNos, "currentCursor.currentAttemptNo") {
                    "must be listed by the referenced manifest entry"
                }
            }
        }
    }
}

/** A page request. A null [attemptNo] requests the complete page including every student attempt. */
data class PageSyncRequestEnvelope(
    override val transferId: String,
    override val createdAtEpochMs: Long,
    val syncGeneration: Long,
    val pageToken: String,
    val pageNumber: Int,
    val attemptNo: Int? = null,
    /** Latest source revision already applied by the requester; zero requests an initial state. */
    val requesterRevision: Long,
) : RemoteReviewEnvelope {
    override val type: RemoteReviewEnvelopeType = RemoteReviewEnvelopeType.PAGE_SYNC_REQUEST

    init {
        validateCommonEnvelope(transferId, createdAtEpochMs)
        validateSyncGeneration(syncGeneration)
        validateOpaqueToken(pageToken, "pageToken")
        checkProtocol(pageNumber > 0, "pageNumber") { "must be one-based" }
        checkProtocol(attemptNo == null || attemptNo > 0, "attemptNo") {
            "must be null or one-based"
        }
        checkProtocol(requesterRevision >= 0L, "requesterRevision") { "must not be negative" }
    }
}

enum class PageAnnotationKind {
    DELTA,
    CHECKPOINT,
}

enum class PageAnnotationCompression {
    NONE,
    GZIP,
}

enum class PageAnnotationPurpose {
    STUDENT_PAGE,
    TEACHER_REVIEW,
}

/**
 * Opaque annotation bytes for one page. The payload may contain several attempts, all of which must
 * be enumerated by [attemptNos]. [payloadSha256] covers the decoded (uncompressed) bytes so the same
 * semantic payload has one identity regardless of transport compression.
 */
class PageAnnotationEnvelope(
    override val transferId: String,
    override val createdAtEpochMs: Long,
    val syncGeneration: Long,
    val purpose: PageAnnotationPurpose,
    val responseToTransferId: String?,
    val pageToken: String,
    val pageNumber: Int,
    attemptNos: List<Int>,
    val kind: PageAnnotationKind,
    /** Revision the delta must apply to; checkpoints are independent and therefore use zero. */
    val baseRevision: Long,
    val sourceRevision: Long,
    val deltaOriginDeviceId: String?,
    val baseOriginCursor: Long,
    val sourceOriginCursor: Long,
    val compression: PageAnnotationCompression,
    payloadBytes: ByteArray,
    val payloadSha256: String,
    val resultLayerSha256: String,
) : RemoteReviewEnvelope {
    override val type: RemoteReviewEnvelopeType = RemoteReviewEnvelopeType.PAGE_ANNOTATION

    val attemptNos: List<Int> = immutableListCopy(attemptNos)
    private val immutablePayloadBytes: ByteArray = payloadBytes.copyOf()

    val payloadSizeBytes: Int get() = immutablePayloadBytes.size

    fun copyPayloadBytes(): ByteArray = immutablePayloadBytes.copyOf()

    /** Returns newly allocated canonical bytes and enforces the same decompression bound again. */
    fun copyDecodedPayloadBytes(): ByteArray = decodePageAnnotationPayload(
        compression = compression,
        payloadBytes = immutablePayloadBytes,
        maxDecodedBytes = kind.maxPayloadBytes(),
    )

    internal fun payloadBytesForCodec(): ByteArray = immutablePayloadBytes

    init {
        validateCommonEnvelope(transferId, createdAtEpochMs)
        validateSyncGeneration(syncGeneration)
        when (purpose) {
            PageAnnotationPurpose.STUDENT_PAGE -> {
                checkProtocol(responseToTransferId != null, "responseToTransferId") {
                    "is required for a student-page response"
                }
                validateOpaqueToken(requireNotNull(responseToTransferId), "responseToTransferId")
            }
            PageAnnotationPurpose.TEACHER_REVIEW -> {
                checkProtocol(responseToTransferId == null, "responseToTransferId") {
                    "must be null for teacher review"
                }
                checkProtocol(kind == PageAnnotationKind.CHECKPOINT, "kind") {
                    "must be CHECKPOINT for teacher review"
                }
                checkProtocol(this.attemptNos.size == 1, "attemptNos") {
                    "must contain exactly one target attempt for teacher review"
                }
            }
        }
        validateOpaqueToken(pageToken, "pageToken")
        checkProtocol(pageNumber > 0, "pageNumber") { "must be one-based" }
        validateAttemptNos(this.attemptNos, "attemptNos", allowEmpty = kind == PageAnnotationKind.CHECKPOINT)
        checkProtocol(sourceRevision >= 0L, "sourceRevision") { "must not be negative" }
        when (kind) {
            PageAnnotationKind.DELTA -> {
                checkProtocol(baseRevision >= 0L, "baseRevision") { "must not be negative" }
                checkProtocol(baseRevision < sourceRevision, "baseRevision") {
                    "must be lower than sourceRevision for a delta"
                }
                checkProtocol(deltaOriginDeviceId != null, "deltaOriginDeviceId") {
                    "is required for a delta"
                }
                validateOpaqueToken(requireNotNull(deltaOriginDeviceId), "deltaOriginDeviceId")
                checkProtocol(baseOriginCursor >= 0L, "baseOriginCursor") {
                    "must not be negative"
                }
                checkProtocol(sourceOriginCursor > baseOriginCursor, "sourceOriginCursor") {
                    "must be greater than baseOriginCursor"
                }
            }
            PageAnnotationKind.CHECKPOINT -> {
                checkProtocol(baseRevision == 0L, "baseRevision") {
                    "must be zero for an independent checkpoint"
                }
                checkProtocol(deltaOriginDeviceId == null, "deltaOriginDeviceId") {
                    "must be null for a checkpoint"
                }
                checkProtocol(baseOriginCursor == 0L, "baseOriginCursor") {
                    "must be zero for a checkpoint"
                }
                checkProtocol(sourceOriginCursor == 0L, "sourceOriginCursor") {
                    "must be zero for a checkpoint"
                }
            }
        }
        checkProtocol(immutablePayloadBytes.isNotEmpty(), "payloadBytes") { "must not be empty" }
        checkProtocol(immutablePayloadBytes.size <= kind.maxPayloadBytes(), "payloadBytes") {
            "exceeds the ${kind.name.lowercase()} hard limit of ${kind.maxPayloadBytes()} bytes"
        }
        validateSha256Hex(payloadSha256, "payloadSha256")
        validateSha256Hex(resultLayerSha256, "resultLayerSha256")

        val decodedPayload = decodePageAnnotationPayload(
            compression = compression,
            payloadBytes = immutablePayloadBytes,
            maxDecodedBytes = kind.maxPayloadBytes(),
        )
        checkProtocol(decodedPayload.isNotEmpty(), "payloadBytes") {
            "decoded payload must not be empty"
        }
        checkProtocol(
            MessageDigest.isEqual(
                payloadSha256.toByteArray(Charsets.US_ASCII),
                pageAnnotationSha256Hex(decodedPayload).toByteArray(Charsets.US_ASCII),
            ),
            "payloadSha256",
        ) { "does not match the decoded payload" }
    }

    companion object {
        /** Builds a valid envelope from canonical bytes, optionally compressing them for the wire. */
        fun fromDecodedPayload(
            transferId: String,
            createdAtEpochMs: Long,
            syncGeneration: Long,
            purpose: PageAnnotationPurpose,
            responseToTransferId: String?,
            pageToken: String,
            pageNumber: Int,
            attemptNos: List<Int>,
            kind: PageAnnotationKind,
            baseRevision: Long,
            sourceRevision: Long,
            deltaOriginDeviceId: String?,
            baseOriginCursor: Long,
            sourceOriginCursor: Long,
            compression: PageAnnotationCompression,
            decodedPayloadBytes: ByteArray,
            resultLayerSha256: String,
        ): PageAnnotationEnvelope {
            checkProtocol(decodedPayloadBytes.isNotEmpty(), "payloadBytes") { "must not be empty" }
            checkProtocol(decodedPayloadBytes.size <= kind.maxPayloadBytes(), "payloadBytes") {
                "decoded payload exceeds the ${kind.name.lowercase()} hard limit of " +
                    "${kind.maxPayloadBytes()} bytes"
            }
            val canonicalPayload = decodedPayloadBytes.copyOf()
            val wirePayload = when (compression) {
                PageAnnotationCompression.NONE -> canonicalPayload
                PageAnnotationCompression.GZIP -> gzipPageAnnotationPayload(canonicalPayload)
            }
            return PageAnnotationEnvelope(
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
                payloadBytes = wirePayload,
                payloadSha256 = pageAnnotationSha256Hex(canonicalPayload),
                resultLayerSha256 = resultLayerSha256,
            )
        }
    }
}

enum class PageSyncAckDisposition {
    APPLIED,
    DUPLICATE,
    REJECTED,
}

enum class PageSyncAckSourceType {
    REQUEST,
    ANNOTATION,
}

/** Semantic acknowledgement for one page annotation transfer. */
data class PageSyncAckEnvelope(
    override val transferId: String,
    override val createdAtEpochMs: Long,
    val syncGeneration: Long,
    val sourceType: PageSyncAckSourceType,
    val sourceTransferId: String,
    val pageToken: String,
    val pageNumber: Int,
    val sourceRevision: Long,
    val disposition: PageSyncAckDisposition,
    val reasonCode: String? = null,
) : RemoteReviewEnvelope {
    override val type: RemoteReviewEnvelopeType = RemoteReviewEnvelopeType.PAGE_SYNC_ACK

    init {
        validateCommonEnvelope(transferId, createdAtEpochMs)
        validateSyncGeneration(syncGeneration)
        validateOpaqueToken(sourceTransferId, "sourceTransferId")
        validateOpaqueToken(pageToken, "pageToken")
        checkProtocol(pageNumber > 0, "pageNumber") { "must be one-based" }
        checkProtocol(sourceRevision >= 0L, "sourceRevision") { "must not be negative" }
        reasonCode?.let {
            checkProtocol(PAGE_SYNC_REASON_CODE.matches(it), "reasonCode") {
                "must be an uppercase machine-readable code"
            }
        }
        checkProtocol(disposition != PageSyncAckDisposition.REJECTED || reasonCode != null, "reasonCode") {
            "is required for a rejected page-sync transfer"
        }
        checkProtocol(
            sourceType != PageSyncAckSourceType.REQUEST || disposition != PageSyncAckDisposition.APPLIED,
            "disposition",
        ) {
            "must not be APPLIED for a request; its successful response is the annotation"
        }
    }
}

/** Lower-case SHA-256 of canonical (uncompressed) page annotation bytes. */
fun pageAnnotationSha256Hex(payloadBytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(payloadBytes)
    val result = CharArray(digest.size * 2)
    digest.forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xff
        result[index * 2] = HEX[value ushr 4]
        result[index * 2 + 1] = HEX[value and 0x0f]
    }
    return String(result)
}

private fun <T> immutableListCopy(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun validateAttemptNos(attemptNos: List<Int>, field: String, allowEmpty: Boolean) {
    checkProtocol(allowEmpty || attemptNos.isNotEmpty(), field) { "must not be empty" }
    checkProtocol(attemptNos.size <= RemoteReviewLimits.MAX_PAGE_SYNC_ATTEMPTS_PER_PAGE, field) {
        "exceeds ${RemoteReviewLimits.MAX_PAGE_SYNC_ATTEMPTS_PER_PAGE} attempts"
    }
    var previous = 0
    attemptNos.forEach { attemptNo ->
        checkProtocol(attemptNo > previous, field) {
            "must contain unique one-based attempt numbers in ascending order"
        }
        previous = attemptNo
    }
}

private fun validateSha256Hex(value: String, field: String) {
    checkProtocol(PAGE_SYNC_SHA256.matches(value), field) {
        "must be exactly ${RemoteReviewLimits.SHA256_HEX_BYTES} lower-case hexadecimal characters"
    }
}

private fun validateSyncGeneration(value: Long) {
    checkProtocol(value >= 1L, "syncGeneration") { "must be at least 1" }
}

internal fun PageAnnotationKind.maxPayloadBytes(): Int = when (this) {
    PageAnnotationKind.DELTA -> RemoteReviewLimits.MAX_PAGE_ANNOTATION_DELTA_BYTES
    PageAnnotationKind.CHECKPOINT -> RemoteReviewLimits.MAX_PAGE_ANNOTATION_CHECKPOINT_BYTES
}

private fun gzipPageAnnotationPayload(decodedPayloadBytes: ByteArray): ByteArray {
    val output = ByteArrayOutputStream()
    GZIPOutputStream(output).use { it.write(decodedPayloadBytes) }
    return output.toByteArray()
}

private fun decodePageAnnotationPayload(
    compression: PageAnnotationCompression,
    payloadBytes: ByteArray,
    maxDecodedBytes: Int,
): ByteArray = when (compression) {
    PageAnnotationCompression.NONE -> payloadBytes.copyOf()
    PageAnnotationCompression.GZIP -> {
        try {
            val output = ByteArrayOutputStream(minOf(payloadBytes.size * 2, maxDecodedBytes))
            GZIPInputStream(ByteArrayInputStream(payloadBytes)).use { input ->
                val buffer = ByteArray(GZIP_BUFFER_BYTES)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (total > maxDecodedBytes - read) {
                        throw RemoteReviewValidationException(
                            "payloadBytes",
                            "decoded GZIP payload exceeds $maxDecodedBytes bytes",
                        )
                    }
                    output.write(buffer, 0, read)
                    total += read
                }
            }
            output.toByteArray()
        } catch (expected: RemoteReviewValidationException) {
            throw expected
        } catch (expected: IOException) {
            throw RemoteReviewValidationException("payloadBytes", "is not a valid GZIP stream")
        }
    }
}

private const val GZIP_BUFFER_BYTES: Int = 8 * 1024
private const val HEX: String = "0123456789abcdef"
private val PAGE_SYNC_SHA256 = Regex("[0-9a-f]{${RemoteReviewLimits.SHA256_HEX_BYTES}}")
private val PAGE_SYNC_REASON_CODE = Regex("[A-Z0-9_]{1,64}")
