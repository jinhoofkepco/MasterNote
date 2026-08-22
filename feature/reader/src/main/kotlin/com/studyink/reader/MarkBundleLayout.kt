package com.studyink.reader

import kotlin.math.round

internal data class MarkBundleOrigin(
    val x: Float,
    val y: Float,
)

internal data class MarkBundleBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/**
 * Snap display coordinates to the paper's own grid, then keep the complete bundle inside the
 * active page. Snapping only affects rendering and hit targets; the canonical teacher-selected
 * anchor remains untouched and can still be moved or synchronized exactly as before.
 */
internal fun alignedMarkBundleOrigin(
    anchorX: Float,
    anchorY: Float,
    pageLeft: Float,
    pageTop: Float,
    pageRight: Float,
    pageBottom: Float,
    bundleWidth: Float,
    bundleHeight: Float,
    horizontalSnap: Float,
    verticalSnap: Float,
    edgePadding: Float,
): MarkBundleOrigin {
    fun snap(value: Float, start: Float, interval: Float): Float = if (interval > 0f) {
        start + round((value - start) / interval) * interval
    } else {
        value
    }

    val minX = pageLeft + edgePadding
    val maxX = (pageRight - edgePadding - bundleWidth).coerceAtLeast(minX)
    val minY = pageTop + edgePadding
    val maxY = (pageBottom - edgePadding - bundleHeight).coerceAtLeast(minY)
    return MarkBundleOrigin(
        x = snap(anchorX, pageLeft, horizontalSnap).coerceIn(minX, maxX),
        y = snap(anchorY, pageTop, verticalSnap).coerceIn(minY, maxY),
    )
}

/**
 * Keep nearby snapped marks on clean rows without allowing two result mosaics to cover each
 * other. Downward rows are preferred; near the bottom of a page the search safely continues
 * upward. The order is deterministic, so zoom/redraw never makes bundles jump randomly.
 */
internal fun nonOverlappingMarkBundleOrigin(
    candidate: MarkBundleOrigin,
    bundleWidth: Float,
    bundleHeight: Float,
    minY: Float,
    maxY: Float,
    verticalStep: Float,
    separation: Float,
    occupied: List<MarkBundleBox>,
): MarkBundleOrigin {
    require(bundleWidth >= 0f && bundleHeight >= 0f)
    require(verticalStep > 0f)
    val safeMinY = minY
    val safeMaxY = maxY.coerceAtLeast(safeMinY)

    fun isFree(y: Float): Boolean {
        val box = MarkBundleBox(
            candidate.x - separation,
            y - separation,
            candidate.x + bundleWidth + separation,
            y + bundleHeight + separation,
        )
        return occupied.none { other ->
            box.left < other.right && box.right > other.left &&
                box.top < other.bottom && box.bottom > other.top
        }
    }

    var y = candidate.y.coerceIn(safeMinY, safeMaxY)
    while (y <= safeMaxY) {
        if (isFree(y)) return candidate.copy(y = y)
        y += verticalStep
    }
    y = candidate.y.coerceIn(safeMinY, safeMaxY) - verticalStep
    while (y >= safeMinY) {
        if (isFree(y)) return candidate.copy(y = y)
        y -= verticalStep
    }
    return candidate.copy(y = candidate.y.coerceIn(safeMinY, safeMaxY))
}
