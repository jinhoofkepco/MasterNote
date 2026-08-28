package com.studyink.monitor.core

import com.studyink.core.model.TeacherReviewPublicationLimits

/** Wire and allocation limits for the deliberately small remote-review exchange. */
object RemoteReviewLimits {
    /** Normal writers must stay below this size, including the protocol frame. */
    const val OPERATIONAL_DOCUMENT_BYTES: Int = 2 * 1024 * 1024

    /** Readers reject input above this before parsing or allocating a declared payload. */
    const val HARD_DOCUMENT_BYTES: Int = 3 * 1024 * 1024

    /** Leaves room for metadata and framing inside the two MiB operational document. */
    const val MAX_SNAPSHOT_IMAGE_BYTES: Int = OPERATIONAL_DOCUMENT_BYTES - (32 * 1024)

    const val MAX_DIMENSION_PX: Int = 8_192
    const val MAX_CANVAS_PIXELS: Long = 16_000_000L
    const val MAX_STROKES: Int = 4_096
    const val MAX_POINTS_PER_STROKE: Int = 8_192
    const val MAX_TOTAL_POINTS: Int = 120_000
    const val MAX_NOTE_UTF8_BYTES: Int = 2_000
    const val MAX_CHAT_TEXT_UTF8_BYTES: Int = 4_096
    const val MAX_CHAT_STATE_MESSAGES: Int = 64
    const val MAX_TOKEN_UTF8_BYTES: Int = 128
    const val MAX_WORKBOOK_LABEL_UTF8_BYTES: Int = 160
    const val MAX_STUDENT_LABEL_UTF8_BYTES: Int = 80
    const val SHA256_HEX_BYTES: Int = 64
    const val MAX_GRADE_SCORE: Int = 1_000_000

    /** A normal delta writer should split before this size. Readers accept recovery batches below. */
    const val PAGE_ANNOTATION_DELTA_TARGET_BYTES: Int = 512 * 1024

    /** Hard decoded and encoded payload limit for one delta. */
    const val MAX_PAGE_ANNOTATION_DELTA_BYTES: Int = 1024 * 1024

    /** Leaves the same frame/metadata headroom as a rendered page snapshot. */
    const val MAX_PAGE_ANNOTATION_CHECKPOINT_BYTES: Int =
        TeacherReviewPublicationLimits.MAX_WIRE_PAYLOAD_BYTES

    const val MAX_PAGE_ANNOTATION_CHUNKS: Int = 8

    /**
     * A complete student-page checkpoint may span several ordinary Telegram documents. Each
     * fragment still obeys [MAX_PAGE_ANNOTATION_CHECKPOINT_BYTES], so no individual decode or
     * transport allocation grows with a long-offline page.
     */
    const val MAX_PAGE_ANNOTATION_ASSEMBLED_BYTES: Int =
        MAX_PAGE_ANNOTATION_CHUNKS * MAX_PAGE_ANNOTATION_CHECKPOINT_BYTES

    const val MAX_PAGE_SYNC_MANIFEST_ENTRIES: Int = 4_096
    const val MAX_PAGE_SYNC_INVENTORY_PAGES: Int = 100_000
    // Checkpoints contain the complete active student layer, so silently truncating this list can
    // never be safe. 4,096 keeps the manifest bounded while covering years of repeated work.
    const val MAX_PAGE_SYNC_ATTEMPTS_PER_PAGE: Int = 4_096
    const val MAX_PAGE_SYNC_APPROX_BYTES: Long = 64L * 1024L * 1024L
}

class RemoteReviewValidationException(
    val field: String,
    message: String,
) : IllegalArgumentException("$field: $message")

enum class RemoteReviewEnvelopeType {
    PAGE_SNAPSHOT,
    TEACHER_FEEDBACK,
    ACK,
    CHAT_MESSAGE,
    REMOTE_GRADE,
    PAGE_SYNC_MANIFEST,
    PAGE_SYNC_REQUEST,
    PAGE_ANNOTATION,
    PAGE_SYNC_ACK,
}

sealed interface RemoteReviewEnvelope {
    val type: RemoteReviewEnvelopeType
    val transferId: String
    val createdAtEpochMs: Long
}

data class ReviewCanvasDimensions(
    val widthPx: Int,
    val heightPx: Int,
) {
    init {
        checkProtocol(widthPx in 1..RemoteReviewLimits.MAX_DIMENSION_PX, "dimensions.widthPx") {
            "must be between 1 and ${RemoteReviewLimits.MAX_DIMENSION_PX}"
        }
        checkProtocol(heightPx in 1..RemoteReviewLimits.MAX_DIMENSION_PX, "dimensions.heightPx") {
            "must be between 1 and ${RemoteReviewLimits.MAX_DIMENSION_PX}"
        }
        checkProtocol(
            widthPx.toLong() * heightPx.toLong() <= RemoteReviewLimits.MAX_CANVAS_PIXELS,
            "dimensions",
        ) { "canvas exceeds ${RemoteReviewLimits.MAX_CANVAS_PIXELS} pixels" }
    }
}

enum class SnapshotImageFormat(
    val mimeType: String,
) {
    PNG("image/png"),
    JPEG("image/jpeg"),
}

/**
 * A rendered workbook page with the student's ink already composited into the image.
 *
 * [pageToken] is intentionally opaque: integrations should use a keyed digest rather than a
 * workbook title or page number. The image is copied on input and output so queued bytes cannot be
 * mutated after validation.
 */
class PageSnapshotEnvelope(
    override val transferId: String,
    override val createdAtEpochMs: Long,
    val pageToken: String,
    /** Human-readable metadata stays inside the encrypted document, never the Telegram caption. */
    val workbookLabel: String,
    val pageNumber: Int,
    val attemptNo: Int? = null,
    val studentLabel: String? = null,
    val revision: Long,
    val dimensions: ReviewCanvasDimensions,
    val imageFormat: SnapshotImageFormat,
    renderedPageBytes: ByteArray,
    /**
     * Optional exact student-only state. The codec embeds it in encrypted image metadata so the v1
     * snapshot field layout remains readable by checkpoint apps; it is never placed in the caption.
     */
    val studentInkDigest: String? = null,
) : RemoteReviewEnvelope {
    override val type: RemoteReviewEnvelopeType = RemoteReviewEnvelopeType.PAGE_SNAPSHOT

    private val immutableRenderedPageBytes = renderedPageBytes.copyOf()

    val renderedPageSizeBytes: Int get() = immutableRenderedPageBytes.size

    fun copyRenderedPageBytes(): ByteArray = immutableRenderedPageBytes.copyOf()

    internal fun renderedPageBytesForCodec(): ByteArray = immutableRenderedPageBytes

    init {
        validateCommonEnvelope(transferId, createdAtEpochMs)
        validateOpaqueToken(pageToken, "pageToken")
        validateDisplayLabel(
            workbookLabel,
            RemoteReviewLimits.MAX_WORKBOOK_LABEL_UTF8_BYTES,
            "workbookLabel",
        )
        checkProtocol(pageNumber > 0, "pageNumber") { "must be one-based" }
        checkProtocol(attemptNo == null || attemptNo > 0, "attemptNo") {
            "must be null or one-based"
        }
        studentLabel?.let {
            validateDisplayLabel(
                it,
                RemoteReviewLimits.MAX_STUDENT_LABEL_UTF8_BYTES,
                "studentLabel",
            )
        }
        checkProtocol(revision >= 0L, "revision") { "must not be negative" }
        checkProtocol(immutableRenderedPageBytes.isNotEmpty(), "renderedPageBytes") {
            "must not be empty"
        }
        checkProtocol(
            immutableRenderedPageBytes.size <= RemoteReviewLimits.MAX_SNAPSHOT_IMAGE_BYTES,
            "renderedPageBytes",
        ) { "exceeds the operational snapshot limit" }
        checkProtocol(
            hasImageSignature(imageFormat, immutableRenderedPageBytes),
            "renderedPageBytes",
        ) { "does not match ${imageFormat.mimeType}" }
        studentInkDigest?.let { digest ->
            checkProtocol(SHA256_HEX.matches(digest), "studentInkDigest") {
                "must be exactly ${RemoteReviewLimits.SHA256_HEX_BYTES} lower-case hexadecimal characters"
            }
        }
    }
}

data class SnapshotReference(
    val transferId: String,
    val pageToken: String,
    val revision: Long,
    val dimensions: ReviewCanvasDimensions,
) {
    init {
        validateOpaqueToken(transferId, "sourceSnapshot.transferId")
        validateOpaqueToken(pageToken, "sourceSnapshot.pageToken")
        checkProtocol(revision >= 0L, "sourceSnapshot.revision") { "must not be negative" }
    }
}

/** A small peer-to-peer text message carried only inside the authenticated encrypted document. */
data class ChatMessageEnvelope(
    override val transferId: String,
    override val createdAtEpochMs: Long,
    val messageId: String,
    /** Stable installation identity used by the receiver to enforce the configured pair scope. */
    val senderDeviceId: String,
    val sentAtEpochMs: Long,
    val text: String,
) : RemoteReviewEnvelope {
    override val type: RemoteReviewEnvelopeType = RemoteReviewEnvelopeType.CHAT_MESSAGE

    init {
        validateCommonEnvelope(transferId, createdAtEpochMs)
        validateOpaqueToken(messageId, "messageId")
        validateOpaqueToken(senderDeviceId, "senderDeviceId")
        checkProtocol(sentAtEpochMs >= 0L, "sentAtEpochMs") { "must not be negative" }
        validateChatText(text)
    }
}

enum class TeacherInkTool {
    PEN,
    HIGHLIGHTER,
}

data class NormalizedTeacherPoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
) {
    init {
        validateUnitFloat(x, "point.x")
        validateUnitFloat(y, "point.y")
        validateUnitFloat(pressure, "point.pressure")
    }
}

/** A single immutable teacher stroke. Erasing is resolved locally before this is transmitted. */
class NormalizedTeacherStroke(
    val strokeId: String,
    val tool: TeacherInkTool,
    val argb: Int,
    val widthNormalized: Float,
    points: List<NormalizedTeacherPoint>,
) {
    val points: List<NormalizedTeacherPoint> = points.toList()

    init {
        validateOpaqueToken(strokeId, "stroke.strokeId")
        checkProtocol(widthNormalized.isFinite(), "stroke.widthNormalized") {
            "must be finite"
        }
        checkProtocol(widthNormalized in MIN_WIDTH_NORMALIZED..MAX_WIDTH_NORMALIZED, "stroke.widthNormalized") {
            "must be between $MIN_WIDTH_NORMALIZED and $MAX_WIDTH_NORMALIZED"
        }
        checkProtocol(this.points.isNotEmpty(), "stroke.points") { "must not be empty" }
        checkProtocol(this.points.size <= RemoteReviewLimits.MAX_POINTS_PER_STROKE, "stroke.points") {
            "exceeds ${RemoteReviewLimits.MAX_POINTS_PER_STROKE} points"
        }
    }

    companion object {
        const val MIN_WIDTH_NORMALIZED: Float = 0.0001f
        const val MAX_WIDTH_NORMALIZED: Float = 0.25f
    }
}

/**
 * A teacher-only vector layer based on one received [PageSnapshotEnvelope]. It never contains a
 * grading decision, attempt mutation, student operation, or destructive erase command.
 */
class TeacherFeedbackEnvelope(
    override val transferId: String,
    override val createdAtEpochMs: Long,
    val sourceSnapshot: SnapshotReference,
    /** Monotonic per [SnapshotReference.pageToken]; each document is the complete teacher layer. */
    val feedbackRevision: Long,
    strokes: List<NormalizedTeacherStroke>,
    val note: String? = null,
) : RemoteReviewEnvelope {
    override val type: RemoteReviewEnvelopeType = RemoteReviewEnvelopeType.TEACHER_FEEDBACK

    val strokes: List<NormalizedTeacherStroke> = strokes.toList()

    init {
        validateCommonEnvelope(transferId, createdAtEpochMs)
        checkProtocol(feedbackRevision >= 1L, "feedbackRevision") { "must be at least 1" }
        checkProtocol(this.strokes.size <= RemoteReviewLimits.MAX_STROKES, "strokes") {
            "exceeds ${RemoteReviewLimits.MAX_STROKES} strokes"
        }
        var totalPoints = 0L
        this.strokes.forEach { stroke ->
            totalPoints += stroke.points.size.toLong()
            checkProtocol(totalPoints <= RemoteReviewLimits.MAX_TOTAL_POINTS, "strokes") {
                "exceeds ${RemoteReviewLimits.MAX_TOTAL_POINTS} total points"
            }
        }
        note?.let {
            checkProtocol(it.utf8Size() <= RemoteReviewLimits.MAX_NOTE_UTF8_BYTES, "note") {
                "exceeds ${RemoteReviewLimits.MAX_NOTE_UTF8_BYTES} UTF-8 bytes"
            }
        }
    }
}

/** A page-bound, conflict-resolvable numeric grade. It never carries or mutates student ink. */
data class RemoteGradeEnvelope(
    override val transferId: String,
    override val createdAtEpochMs: Long,
    val actionId: String,
    val sourceSnapshot: SnapshotReference,
    /** Exact student attempt being graded; unlike snapshots this can never be absent. */
    val attemptNo: Int,
    /** Lower-case SHA-256 of the student-ink state the teacher actually reviewed. */
    val studentInkDigest: String,
    /** Stable across edits so one logical grade can be replaced without creating duplicates. */
    val gradeGroupId: String,
    val syncRevision: Long,
    val lastModifiedByDeviceId: String,
    val anchor: NormalizedGradeAnchor,
    val score: Int,
    val maximumScore: Int,
) : RemoteReviewEnvelope {
    override val type: RemoteReviewEnvelopeType = RemoteReviewEnvelopeType.REMOTE_GRADE

    init {
        validateCommonEnvelope(transferId, createdAtEpochMs)
        validateOpaqueToken(actionId, "actionId")
        checkProtocol(attemptNo > 0, "attemptNo") { "must be one-based" }
        checkProtocol(SHA256_HEX.matches(studentInkDigest), "studentInkDigest") {
            "must be exactly ${RemoteReviewLimits.SHA256_HEX_BYTES} lower-case hexadecimal characters"
        }
        validateOpaqueToken(gradeGroupId, "gradeGroupId")
        checkProtocol(syncRevision >= 1L, "syncRevision") { "must be at least 1" }
        validateOpaqueToken(lastModifiedByDeviceId, "lastModifiedByDeviceId")
        checkProtocol(maximumScore in 1..RemoteReviewLimits.MAX_GRADE_SCORE, "maximumScore") {
            "must be between 1 and ${RemoteReviewLimits.MAX_GRADE_SCORE}"
        }
        checkProtocol(score in 0..maximumScore, "score") {
            "must be between 0 and maximumScore"
        }
    }
}

/** Page-size-independent grade marker location. */
data class NormalizedGradeAnchor(
    val x: Float,
    val y: Float,
) {
    init {
        validateUnitFloat(x, "anchor.x")
        validateUnitFloat(y, "anchor.y")
    }
}

enum class RemoteReviewAckDisposition {
    APPLIED,
    SUPERSEDED,
    DUPLICATE,
    REJECTED,
}

data class RemoteReviewAckEnvelope(
    override val transferId: String,
    override val createdAtEpochMs: Long,
    val acknowledgedTransferId: String,
    val disposition: RemoteReviewAckDisposition,
    /** Stable machine-readable code only; user text and private workbook data do not belong here. */
    val detailCode: String? = null,
) : RemoteReviewEnvelope {
    override val type: RemoteReviewEnvelopeType = RemoteReviewEnvelopeType.ACK

    init {
        validateCommonEnvelope(transferId, createdAtEpochMs)
        validateOpaqueToken(acknowledgedTransferId, "acknowledgedTransferId")
        detailCode?.let {
            checkProtocol(DETAIL_CODE.matches(it), "detailCode") {
                "must be an uppercase machine-readable code"
            }
        }
    }

    private companion object {
        val DETAIL_CODE = Regex("[A-Z0-9_]{1,64}")
    }
}

internal fun validateCommonEnvelope(transferId: String, createdAtEpochMs: Long) {
    validateOpaqueToken(transferId, "transferId")
    checkProtocol(createdAtEpochMs >= 0L, "createdAtEpochMs") { "must not be negative" }
}

internal fun validateOpaqueToken(value: String, field: String) {
    checkProtocol(value.utf8Size() in MIN_TOKEN_BYTES..RemoteReviewLimits.MAX_TOKEN_UTF8_BYTES, field) {
        "must contain $MIN_TOKEN_BYTES..${RemoteReviewLimits.MAX_TOKEN_UTF8_BYTES} ASCII bytes"
    }
    checkProtocol(OPAQUE_TOKEN.matches(value), field) {
        "must contain only ASCII letters, digits, '-' or '_'"
    }
}

internal fun validateDisplayLabel(value: String, maxUtf8Bytes: Int, field: String) {
    checkProtocol(value.isNotBlank(), field) { "must not be blank" }
    checkProtocol(value.utf8Size() <= maxUtf8Bytes, field) {
        "exceeds $maxUtf8Bytes UTF-8 bytes"
    }
    checkProtocol(value.none(Char::isISOControl), field) { "must not contain control characters" }
}

internal inline fun checkProtocol(condition: Boolean, field: String, lazyMessage: () -> String) {
    if (!condition) throw RemoteReviewValidationException(field, lazyMessage())
}

internal fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

internal fun validateChatText(text: String) {
    checkProtocol(text.isNotBlank(), "text") { "must not be blank" }
    checkProtocol(text.utf8Size() <= RemoteReviewLimits.MAX_CHAT_TEXT_UTF8_BYTES, "text") {
        "exceeds ${RemoteReviewLimits.MAX_CHAT_TEXT_UTF8_BYTES} UTF-8 bytes"
    }
    checkProtocol(text.hasWellFormedSurrogatePairs(), "text") { "contains malformed Unicode" }
    checkProtocol(
        text.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' },
        "text",
    ) { "contains an unsupported control character" }
}

private fun String.hasWellFormedSurrogatePairs(): Boolean {
    var index = 0
    while (index < length) {
        val current = this[index]
        when {
            Character.isHighSurrogate(current) -> {
                if (index + 1 >= length || !Character.isLowSurrogate(this[index + 1])) return false
                index += 2
            }
            Character.isLowSurrogate(current) -> return false
            else -> index += 1
        }
    }
    return true
}

private fun validateUnitFloat(value: Float, field: String) {
    checkProtocol(value.isFinite(), field) { "must be finite" }
    checkProtocol(value in 0f..1f, field) { "must be normalized to 0..1" }
}

private fun hasImageSignature(format: SnapshotImageFormat, bytes: ByteArray): Boolean = when (format) {
    SnapshotImageFormat.PNG -> bytes.size >= PNG_SIGNATURE.size &&
        PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }
    SnapshotImageFormat.JPEG -> bytes.size >= 3 &&
        bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
}

private const val MIN_TOKEN_BYTES = 8
private val OPAQUE_TOKEN = Regex("[A-Za-z0-9_-]+")
private val SHA256_HEX = Regex("[0-9a-f]{${RemoteReviewLimits.SHA256_HEX_BYTES}}")
private val PNG_SIGNATURE = byteArrayOf(
    0x89.toByte(),
    0x50,
    0x4e,
    0x47,
    0x0d,
    0x0a,
    0x1a,
    0x0a,
)
