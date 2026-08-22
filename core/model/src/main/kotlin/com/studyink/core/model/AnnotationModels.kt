package com.studyink.core.model

import java.util.UUID

const val CANONICAL_PAGE_WIDTH = 1000f
const val ANNOTATION_FORMAT_VERSION = 2

@JvmInline value class StrokeId(val value: String)
@JvmInline value class OperationId(val value: String)

data class Student(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val hiddenAtEpochMillis: Long? = null,
)

data class Book(
    val id: String = UUID.randomUUID().toString(),
    val studentId: String,
    val title: String,
    val pageCount: Int,
    /** App-private path, relative to the MasterNote book directory. */
    val pdfRelativePath: String,
    /** Matching aid for LAN pairing; UUID remains the document identity. */
    val contentSha256: String = "",
    val answerSourceRelativePath: String? = null,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val hiddenAtEpochMillis: Long? = null,
)

data class AnswerSource(
    val sourceId: String,
    val items: List<AnswerItem>,
)

data class AnswerItem(
    val id: String,
    val pageNumber: Int,
    val bounds: PageBounds,
    val answer: String? = null,
)

data class Attempt(
    val bookId: String,
    val pageNumber: Int,
    val attemptNo: Int,
    val locked: Boolean = false,
    val startedAtEpochMillis: Long = System.currentTimeMillis(),
    val lockedAtEpochMillis: Long? = null,
)

/**
 * Reserved review slot for teacher marks made before a student attempt exists.
 * Student attempts are numbered from 1, so this slot must never be persisted as an [Attempt].
 */
const val TEACHER_PAGE_REVIEW_ATTEMPT_NO = 0

enum class MarkColor { BLUE, RED, GRAY }

data class Mark(
    val attemptNo: Int,
    val color: MarkColor,
    val gradedAtEpochMillis: Long = System.currentTimeMillis(),
    val hiddenAtEpochMillis: Long? = null,
)

data class MarkGroup(
    val id: String = UUID.randomUUID().toString(),
    val bookId: String,
    val pageNumber: Int,
    val anchor: PagePoint,
    val marks: List<Mark> = emptyList(),
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val hiddenAtEpochMillis: Long? = null,
    /** Monotonic per-group version used to converge full-state LAN upserts. */
    val syncRevision: Long = 0L,
    /** Deterministic tie-break when two paired devices edit the same revision while offline. */
    val lastModifiedByDeviceId: String = "",
)

data class PagePoint(
    val x: Float,
    val y: Float,
    val pressure: Float = 1f,
)

data class PageBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun expanded(amount: Float) = PageBounds(left - amount, top - amount, right + amount, bottom + amount)
    fun intersects(other: PageBounds): Boolean =
        left <= other.right && right >= other.left && top <= other.bottom && bottom >= other.top

    companion object {
        fun from(points: List<PagePoint>): PageBounds {
            if (points.isEmpty()) return PageBounds(0f, 0f, 0f, 0f)
            return PageBounds(
                points.minOf { it.x },
                points.minOf { it.y },
                points.maxOf { it.x },
                points.maxOf { it.y },
            )
        }
    }
}

enum class StrokeTool { PEN, HIGHLIGHTER }

data class StrokeAsset(
    val id: StrokeId = StrokeId(UUID.randomUUID().toString()),
    val pageNumber: Int,
    val tool: StrokeTool,
    val colorArgb: Int,
    val width: Float,
    val points: List<PagePoint>,
    val authorId: String = "student",
    val attemptNo: Int = 1,
    val logicalClock: Long = 0L,
    val deviceId: String = "local",
    val itemId: String? = null,
    val publishedAtEpochMillis: Long? = null,
    val bounds: PageBounds = PageBounds.from(points),
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val parentStrokeId: StrokeId? = null,
    val formatVersion: Int = ANNOTATION_FORMAT_VERSION,
)

sealed interface AnnotationOperation {
    val id: OperationId
    val removedStrokeIds: Set<StrokeId>
    val addedStrokeIds: Set<StrokeId>
    val logicalClock: Long
    val deviceId: String
}

data class AssetOperation(
    override val id: OperationId = OperationId(UUID.randomUUID().toString()),
    override val removedStrokeIds: Set<StrokeId>,
    override val addedStrokeIds: Set<StrokeId>,
    override val logicalClock: Long = 0L,
    override val deviceId: String = "local",
) : AnnotationOperation

/**
 * Immutable, single-page materialized state. Ordered lists are built once here rather than in
 * View.onDraw. Undo/redo are intentionally absent: they belong to the live editor process only.
 */
class AnnotationSnapshot(
    val bookId: String,
    val pageNumber: Int,
    val revision: Long,
    val assets: Map<StrokeId, StrokeAsset>,
    val activeStrokeIds: Set<StrokeId>,
    val appliedOperationIds: Set<OperationId> = emptySet(),
) {
    val activeStrokes: List<StrokeAsset> = activeStrokeIds.asSequence()
        .mapNotNull(assets::get)
        .sortedWith(compareBy<StrokeAsset>({ it.logicalClock }, { it.createdAtEpochMillis }, { it.id.value }))
        .toList()

    fun visibleStrokes(attemptNo: Int): List<StrokeAsset> =
        activeStrokes.filter { it.attemptNo == attemptNo }

    companion object {
        fun empty(bookId: String, pageNumber: Int = 0) =
            AnnotationSnapshot(bookId, pageNumber, 0L, emptyMap(), emptySet(), emptySet())
    }
}
