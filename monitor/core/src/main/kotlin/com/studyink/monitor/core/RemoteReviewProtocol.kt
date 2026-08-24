package com.studyink.monitor.core

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
    const val MAX_TOKEN_UTF8_BYTES: Int = 128
    const val MAX_WORKBOOK_LABEL_UTF8_BYTES: Int = 160
    const val MAX_STUDENT_LABEL_UTF8_BYTES: Int = 80
}

class RemoteReviewValidationException(
    val field: String,
    message: String,
) : IllegalArgumentException("$field: $message")

enum class RemoteReviewEnvelopeType {
    PAGE_SNAPSHOT,
    TEACHER_FEEDBACK,
    ACK,
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
