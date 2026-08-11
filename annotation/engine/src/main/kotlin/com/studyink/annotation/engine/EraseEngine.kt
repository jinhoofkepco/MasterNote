package com.studyink.annotation.engine

import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeId

data class EraseResult(
    val removedStrokeIds: Set<StrokeId>,
    val fragments: List<StrokeAsset>,
)

object EraseEngine {
    fun previewCandidates(
        strokes: List<StrokeAsset>,
        pageNumber: Int,
        eraserPath: List<PagePoint>,
        eraserRadius: Float,
    ): Set<StrokeId> {
        if (eraserPath.isEmpty()) return emptySet()
        val broadBounds = pathBounds(eraserPath, eraserRadius)
        return strokes.asSequence()
            .filter { it.pageNumber == pageNumber }
            .filter { it.bounds.expanded(it.width / 2f).intersects(broadBounds) }
            .filter { stroke ->
                val threshold = eraserRadius + stroke.width / 2f
                resample(stroke.points, threshold.coerceAtLeast(1f) / 2f)
                    .any { pointToPolylineDistance(it, eraserPath) <= threshold }
            }
            .map { it.id }
            .toSet()
    }

    fun wholeStrokeErase(
        strokes: List<StrokeAsset>,
        pageNumber: Int,
        eraserPath: List<PagePoint>,
        eraserRadius: Float,
    ): EraseResult = EraseResult(
        removedStrokeIds = previewCandidates(strokes, pageNumber, eraserPath, eraserRadius),
        fragments = emptyList(),
    )

    fun partialErasePreviewSegments(
        strokes: List<StrokeAsset>,
        pageNumber: Int,
        eraserPath: List<PagePoint>,
        eraserRadius: Float,
    ): List<StrokeAsset> {
        val candidates = previewCandidates(strokes, pageNumber, eraserPath, eraserRadius)
        return buildList {
            for (stroke in strokes.filter { it.id in candidates }) {
                val threshold = eraserRadius + stroke.width / 2f
                val sampled = resample(stroke.points, (eraserRadius / 2f).coerceIn(0.75f, 6f))
                val groups = splitByEraseState(sampled, eraserPath, threshold, keepErased = true)
                groups.filter { it.isNotEmpty() }.forEach { points ->
                    add(
                        stroke.copy(
                            points = points,
                            bounds = com.studyink.core.model.PageBounds.from(points),
                        )
                    )
                }
            }
        }
    }

    fun partialErase(
        strokes: List<StrokeAsset>,
        pageNumber: Int,
        eraserPath: List<PagePoint>,
        eraserRadius: Float,
        minimumFragmentLength: Float = 3f,
    ): EraseResult {
        val candidates = previewCandidates(strokes, pageNumber, eraserPath, eraserRadius)
        val fragments = mutableListOf<StrokeAsset>()
        for (stroke in strokes.filter { it.id in candidates }) {
            val threshold = eraserRadius + stroke.width / 2f
            val sampled = resample(stroke.points, (eraserRadius / 2f).coerceIn(0.75f, 6f))
            val groups = splitByEraseState(sampled, eraserPath, threshold, keepErased = false)
            groups.asSequence()
                .filter { it.size >= 2 && polylineLength(it) >= minimumFragmentLength }
                .forEach { points ->
                    fragments += stroke.copy(
                        id = StrokeId(java.util.UUID.randomUUID().toString()),
                        points = points,
                        bounds = com.studyink.core.model.PageBounds.from(points),
                        parentStrokeId = stroke.id,
                        createdAtEpochMillis = System.currentTimeMillis(),
                    )
                }
        }
        return EraseResult(candidates, fragments)
    }

    private fun splitByEraseState(
        sampled: List<PagePoint>,
        eraserPath: List<PagePoint>,
        threshold: Float,
        keepErased: Boolean,
    ): List<MutableList<PagePoint>> {
        val groups = mutableListOf<MutableList<PagePoint>>()
        var current: MutableList<PagePoint>? = null
        for (point in sampled) {
            val erased = pointToPolylineDistance(point, eraserPath) <= threshold
            if (erased == keepErased) {
                if (current == null) {
                    current = mutableListOf()
                    groups += current
                }
                current += point
            } else {
                current = null
            }
        }
        return groups
    }
}
