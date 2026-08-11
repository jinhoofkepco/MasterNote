package com.studyink.core.model

import java.util.UUID

const val CANONICAL_PAGE_WIDTH = 1000f

@JvmInline value class StrokeId(val value: String)
@JvmInline value class OperationId(val value: String)

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
    val bounds: PageBounds = PageBounds.from(points),
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val parentStrokeId: StrokeId? = null,
    val formatVersion: Int = 1,
)

sealed interface AnnotationOperation {
    val id: OperationId
    val removedStrokeIds: Set<StrokeId>
    val addedStrokeIds: Set<StrokeId>
}

data class AssetOperation(
    override val id: OperationId = OperationId(UUID.randomUUID().toString()),
    override val removedStrokeIds: Set<StrokeId>,
    override val addedStrokeIds: Set<StrokeId>,
) : AnnotationOperation

data class AnnotationSnapshot(
    val documentId: String,
    val revision: Long,
    val assets: Map<StrokeId, StrokeAsset>,
    val activeStrokeIds: Set<StrokeId>,
    val undoStack: List<AssetOperation> = emptyList(),
    val redoStack: List<AssetOperation> = emptyList(),
) {
    val activeStrokes: List<StrokeAsset>
        get() = activeStrokeIds.mapNotNull(assets::get).sortedBy { it.createdAtEpochMillis }

    companion object {
        fun empty(documentId: String) = AnnotationSnapshot(documentId, 0L, emptyMap(), emptySet())
    }
}
