package com.studyink.reader

import com.studyink.core.model.CANONICAL_PAGE_WIDTH
import com.studyink.core.model.PagePoint
import com.studyink.memo.core.MemoAnchor
import com.studyink.memo.core.MemoPoint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal const val MEMO_CANVAS_ASPECT_RATIO = 2.2f
internal const val MEMO_CANONICAL_HEIGHT = CANONICAL_PAGE_WIDTH * MEMO_CANVAS_ASPECT_RATIO

internal data class MemoUiPoint(val x: Float, val y: Float)

internal data class MemoUiAnchor(val id: String, val anchor: MemoAnchor)

internal data class MemoUiBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)

    fun contains(x: Float, y: Float): Boolean =
        x.isFinite() && y.isFinite() && x >= left && x <= right && y >= top && y <= bottom
}

internal fun memoAnchorCenter(anchor: MemoAnchor, page: MemoUiBounds): MemoUiPoint = MemoUiPoint(
    x = page.left + page.width * anchor.normalizedX,
    y = page.top + page.height * anchor.normalizedY,
)

internal fun memoAnchorAt(x: Float, y: Float, page: MemoUiBounds): MemoAnchor {
    val normalizedX = if (page.width > 0f) (x - page.left) / page.width else 0.5f
    val normalizedY = if (page.height > 0f) (y - page.top) / page.height else 0.5f
    return MemoAnchor(normalizedX.coerceIn(0f, 1f), normalizedY.coerceIn(0f, 1f))
}

internal fun memoIconHit(
    x: Float,
    y: Float,
    center: MemoUiPoint,
    hitRadius: Float,
): Boolean {
    if (hitRadius <= 0f || !hitRadius.isFinite()) return false
    val dx = x - center.x
    val dy = y - center.y
    return dx * dx + dy * dy <= hitRadius * hitRadius
}

/**
 * Gives coincident memo anchors separate, stable hit targets without changing their stored anchor.
 * Input order is preserved, so appending a new memo cannot move the older icons.
 */
internal fun spreadMemoIconCenters(
    anchors: List<MemoUiAnchor>,
    page: MemoUiBounds,
    minimumSeparation: Float,
    edgePadding: Float,
): Map<String, MemoUiPoint> {
    if (anchors.isEmpty()) return emptyMap()
    val separation = minimumSeparation.takeIf { it.isFinite() && it > 0f } ?: 1f
    val padding = edgePadding.takeIf { it.isFinite() && it >= 0f } ?: 0f
    val minX = (page.left + padding).coerceAtMost(page.right)
    val maxX = (page.right - padding).coerceAtLeast(page.left)
    val minY = (page.top + padding).coerceAtMost(page.bottom)
    val maxY = (page.bottom - padding).coerceAtLeast(page.top)
    val safeMinX = minOf(minX, maxX)
    val safeMaxX = maxOf(minX, maxX)
    val safeMinY = minOf(minY, maxY)
    val safeMaxY = maxOf(minY, maxY)
    val separationSquared = separation * separation
    val placed = linkedMapOf<String, MemoUiPoint>()

    anchors.forEach { item ->
        val base = memoAnchorCenter(item.anchor, page)
        fun clamp(point: MemoUiPoint) = MemoUiPoint(
            point.x.coerceIn(safeMinX, safeMaxX),
            point.y.coerceIn(safeMinY, safeMaxY),
        )
        fun available(point: MemoUiPoint): Boolean = placed.values.none { other ->
            val dx = point.x - other.x
            val dy = point.y - other.y
            dx * dx + dy * dy < separationSquared
        }

        var chosen = clamp(base)
        if (!available(chosen)) {
            val attempts = (anchors.size.coerceAtLeast(2) * 16)
            for (index in 0 until attempts) {
                val ring = index / 8 + 1
                val angle = (index % 8) * (PI / 4.0) - PI / 2.0
                val candidate = clamp(
                    MemoUiPoint(
                        x = base.x + cos(angle).toFloat() * separation * ring,
                        y = base.y + sin(angle).toFloat() * separation * ring,
                    ),
                )
                chosen = candidate
                if (available(candidate)) break
            }
        }
        placed[item.id] = chosen
    }
    return placed
}

internal fun MemoPoint.toCanonicalMemoPoint(): PagePoint = PagePoint(
    x = normalizedX * CANONICAL_PAGE_WIDTH,
    y = normalizedY * MEMO_CANONICAL_HEIGHT,
    pressure = pressure,
)

internal fun PagePoint.toMemoPoint(): MemoPoint = MemoPoint(
    normalizedX = (x / CANONICAL_PAGE_WIDTH).coerceIn(0f, 1f),
    normalizedY = (y / MEMO_CANONICAL_HEIGHT).coerceIn(0f, 1f),
    pressure = pressure.coerceAtLeast(0f),
)
