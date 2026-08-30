package com.studyink.memo.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Exact ownership boundary for student-created memos. Page numbers are zero-based. */
data class MemoTarget(
    val bookId: String,
    val pageNumber: Int,
    val attemptNo: Int,
) {
    init {
        require(bookId.isNotBlank() && bookId.toByteArray(Charsets.UTF_8).size <= MAX_MEMO_BOOK_ID_BYTES)
        require(pageNumber in 0..MAX_MEMO_PAGE_NUMBER)
        require(attemptNo in 1..MAX_MEMO_ATTEMPT_NUMBER)
    }
}

/** Position of the collapsed memo icon in normalized problem-page coordinates. */
data class MemoAnchor(
    val normalizedX: Float,
    val normalizedY: Float,
) {
    init {
        require(normalizedX.isFinite() && normalizedX in 0f..1f)
        require(normalizedY.isFinite() && normalizedY in 0f..1f)
    }
}

/** Position inside the fixed, non-zoomable memo canvas. */
data class MemoPoint(
    val normalizedX: Float,
    val normalizedY: Float,
    val pressure: Float = 1f,
) {
    init {
        require(normalizedX.isFinite() && normalizedX in 0f..1f)
        require(normalizedY.isFinite() && normalizedY in 0f..1f)
        require(pressure.isFinite() && pressure >= 0f)
    }
}

enum class MemoTool { PEN, HIGHLIGHTER }

/** One immutable stroke on a memo-local canvas. [widthFraction] is relative to canvas width. */
data class MemoStroke(
    val id: String = UUID.randomUUID().toString(),
    val tool: MemoTool,
    val colorArgb: Int,
    val widthFraction: Float,
    val points: List<MemoPoint>,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
) {
    init {
        requireValidMemoUuid(id, "stroke id")
        require(widthFraction.isFinite() && widthFraction > 0f && widthFraction <= 1f)
        require(points.isNotEmpty())
        require(createdAtEpochMillis >= 0L)
    }
}

/**
 * Full state for one memo. Deletion advances [revision] and leaves a durable tombstone, preventing
 * a delayed older transport frame from resurrecting the memo.
 */
data class StudentMemo(
    val id: String,
    val target: MemoTarget,
    val anchor: MemoAnchor,
    val revision: Long,
    val digestSha256: String,
    val strokes: List<MemoStroke>,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val deletedAtEpochMillis: Long? = null,
) {
    val deleted: Boolean get() = deletedAtEpochMillis != null

    init {
        requireValidMemoUuid(id, "memo id")
        require(revision >= 1L)
        require(MEMO_SHA256.matches(digestSha256))
        require(createdAtEpochMillis >= 0L && updatedAtEpochMillis >= createdAtEpochMillis)
        require(deletedAtEpochMillis == null || deletedAtEpochMillis >= createdAtEpochMillis)
        require(deletedAtEpochMillis == null || deletedAtEpochMillis <= updatedAtEpochMillis)
        require(deletedAtEpochMillis == null || strokes.isEmpty())
    }
}

/** Exact, transport-ready state for one page attempt. Tombstones are intentionally included. */
data class StudentMemoTargetSnapshot(
    val target: MemoTarget,
    val revision: Long,
    val digestSha256: String,
    val memos: List<StudentMemo>,
) {
    init {
        require(revision >= 0L)
        require(MEMO_SHA256.matches(digestSha256))
        require(memos.all { it.target == target })
        require(memos.all { it.revision <= revision })
    }
}

/** Canonical digests shared by local storage and future LAN/Telegram codecs. */
object StudentMemoDigest {
    fun memoSha256(
        id: String,
        target: MemoTarget,
        anchor: MemoAnchor,
        revision: Long,
        strokes: List<MemoStroke>,
        createdAtEpochMillis: Long,
        updatedAtEpochMillis: Long,
        deletedAtEpochMillis: Long?,
    ): String = canonicalSha256 { output ->
        output.writeString("student-memo-v1")
        output.writeTarget(target)
        output.writeString(id)
        output.writeInt(anchor.normalizedX.toRawBits())
        output.writeInt(anchor.normalizedY.toRawBits())
        output.writeLong(revision)
        output.writeLong(createdAtEpochMillis)
        output.writeLong(updatedAtEpochMillis)
        output.writeBoolean(deletedAtEpochMillis != null)
        deletedAtEpochMillis?.let(output::writeLong)
        output.writeInt(strokes.size)
        strokes.forEach { stroke ->
            output.writeString(stroke.id)
            output.writeString(stroke.tool.name)
            output.writeInt(stroke.colorArgb)
            output.writeInt(stroke.widthFraction.toRawBits())
            output.writeLong(stroke.createdAtEpochMillis)
            output.writeInt(stroke.points.size)
            stroke.points.forEach { point ->
                output.writeInt(point.normalizedX.toRawBits())
                output.writeInt(point.normalizedY.toRawBits())
                output.writeInt(point.pressure.toRawBits())
            }
        }
    }

    fun memoSha256(memo: StudentMemo): String = memoSha256(
        id = memo.id,
        target = memo.target,
        anchor = memo.anchor,
        revision = memo.revision,
        strokes = memo.strokes,
        createdAtEpochMillis = memo.createdAtEpochMillis,
        updatedAtEpochMillis = memo.updatedAtEpochMillis,
        deletedAtEpochMillis = memo.deletedAtEpochMillis,
    )

    fun targetSha256(target: MemoTarget, memos: Collection<StudentMemo>): String = canonicalSha256 { output ->
        output.writeString("student-memo-target-v1")
        output.writeTarget(target)
        val ordered = memos.sortedBy(StudentMemo::id)
        output.writeInt(ordered.size)
        ordered.forEach { memo ->
            require(memo.target == target)
            output.writeString(memo.id)
            output.writeLong(memo.revision)
            output.writeString(memo.digestSha256)
        }
    }
}

/** Rebinds authenticated transport content to an already verified local workbook/page/attempt. */
fun StudentMemoTargetSnapshot.remapTo(localTarget: MemoTarget): StudentMemoTargetSnapshot {
    val remapped = memos.map { it.remapTo(localTarget) }
    return StudentMemoTargetSnapshot(
        target = localTarget,
        revision = revision,
        digestSha256 = StudentMemoDigest.targetSha256(localTarget, remapped),
        memos = remapped,
    )
}

/** Rebinds one memo while preserving its stable identity, revision, ink, and tombstone. */
fun StudentMemo.remapTo(localTarget: MemoTarget): StudentMemo {
    val digest = StudentMemoDigest.memoSha256(
        id = id,
        target = localTarget,
        anchor = anchor,
        revision = revision,
        strokes = strokes,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        deletedAtEpochMillis = deletedAtEpochMillis,
    )
    return copy(target = localTarget, digestSha256 = digest)
}

internal const val MAX_MEMO_BOOK_ID_BYTES = 512
internal const val MAX_MEMO_PAGE_NUMBER = 100_000
internal const val MAX_MEMO_ATTEMPT_NUMBER = 10_000
internal val MEMO_SHA256 = Regex("[0-9a-f]{64}")

internal fun requireValidMemoUuid(value: String, label: String) {
    require(runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)) {
        "$label must be a canonical UUID"
    }
}

private inline fun canonicalSha256(write: (DataOutputStream) -> Unit): String {
    val bytes = ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use(write)
        buffer.toByteArray()
    }
    return MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
}

private fun DataOutputStream.writeTarget(target: MemoTarget) {
    writeString(target.bookId)
    writeInt(target.pageNumber)
    writeInt(target.attemptNo)
}

private fun DataOutputStream.writeString(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
