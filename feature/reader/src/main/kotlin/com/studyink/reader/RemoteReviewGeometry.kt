package com.studyink.reader

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class RemoteReviewRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val shorterSide: Float get() = min(width, height)

    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

/** Shared page fitting and hit geometry for the editor and the read-only student overlay. */
object RemoteReviewGeometry {
    fun fitCenter(
        containerWidth: Float,
        containerHeight: Float,
        pageWidth: Float,
        pageHeight: Float,
    ): RemoteReviewRect {
        if (
            containerWidth <= 0f || containerHeight <= 0f ||
            pageWidth <= 0f || pageHeight <= 0f ||
            !containerWidth.isFinite() || !containerHeight.isFinite() ||
            !pageWidth.isFinite() || !pageHeight.isFinite()
        ) return RemoteReviewRect(0f, 0f, 0f, 0f)
        val scale = min(containerWidth / pageWidth, containerHeight / pageHeight)
        val width = pageWidth * scale
        val height = pageHeight * scale
        val left = (containerWidth - width) / 2f
        val top = (containerHeight - height) / 2f
        return RemoteReviewRect(left, top, left + width, top + height)
    }

    fun viewToNormalized(
        viewX: Float,
        viewY: Float,
        page: RemoteReviewRect,
    ): RemoteNormalizedPoint? {
        if (page.width <= 0f || page.height <= 0f || !page.contains(viewX, viewY)) return null
        return RemoteNormalizedPoint(
            x = ((viewX - page.left) / page.width).coerceIn(0f, 1f),
            y = ((viewY - page.top) / page.height).coerceIn(0f, 1f),
        )
    }

    fun normalizedToView(point: RemoteNormalizedPoint, page: RemoteReviewRect): ReviewPoint = ReviewPoint(
        x = page.left + point.x.coerceIn(0f, 1f) * page.width,
        y = page.top + point.y.coerceIn(0f, 1f) * page.height,
    )

    fun widthToView(widthFraction: Float, page: RemoteReviewRect): Float =
        max(1f, widthFraction.coerceAtLeast(0f) * page.shorterSide)

    /**
     * Tests an eraser corridor against a teacher trace in page pixels. The normalized X and Y axes
     * are intentionally scaled independently so portrait pages do not turn a circular eraser into
     * an ellipse.
     */
    fun intersectsEraser(
        stroke: RemoteFeedbackStroke,
        eraserPath: List<RemoteNormalizedPoint>,
        eraserRadiusFraction: Float,
        pageWidthPx: Int,
        pageHeightPx: Int,
    ): Boolean {
        if (stroke.points.isEmpty() || eraserPath.isEmpty()) return false
        val shortSide = min(pageWidthPx, pageHeightPx).coerceAtLeast(1).toFloat()
        val xScale = pageWidthPx.coerceAtLeast(1) / shortSide
        val yScale = pageHeightPx.coerceAtLeast(1) / shortSide
        fun metric(point: RemoteNormalizedPoint) = ReviewPoint(point.x * xScale, point.y * yScale)
        val strokePoints = stroke.points.map(::metric)
        val eraserPoints = eraserPath.map(::metric)
        val threshold = eraserRadiusFraction.coerceAtLeast(0f) + stroke.widthFraction.coerceAtLeast(0f) / 2f
        return polylineDistance(strokePoints, eraserPoints) <= threshold
    }

    private fun polylineDistance(first: List<ReviewPoint>, second: List<ReviewPoint>): Float {
        if (first.size == 1 && second.size == 1) return distance(first[0], second[0])
        if (first.size == 1) return segments(second).minOf { (a, b) -> pointSegmentDistance(first[0], a, b) }
        if (second.size == 1) return segments(first).minOf { (a, b) -> pointSegmentDistance(second[0], a, b) }
        var closest = Float.POSITIVE_INFINITY
        segments(first).forEach { (a, b) ->
            segments(second).forEach { (c, d) ->
                closest = min(closest, segmentDistance(a, b, c, d))
            }
        }
        return closest
    }

    private fun segments(points: List<ReviewPoint>): List<Pair<ReviewPoint, ReviewPoint>> =
        points.zipWithNext().ifEmpty { listOf(points.first() to points.first()) }

    private fun segmentDistance(a: ReviewPoint, b: ReviewPoint, c: ReviewPoint, d: ReviewPoint): Float {
        if (segmentsIntersect(a, b, c, d)) return 0f
        return minOf(
            pointSegmentDistance(a, c, d),
            pointSegmentDistance(b, c, d),
            pointSegmentDistance(c, a, b),
            pointSegmentDistance(d, a, b),
        )
    }

    private fun segmentsIntersect(a: ReviewPoint, b: ReviewPoint, c: ReviewPoint, d: ReviewPoint): Boolean {
        val o1 = orientation(a, b, c)
        val o2 = orientation(a, b, d)
        val o3 = orientation(c, d, a)
        val o4 = orientation(c, d, b)
        if (o1 * o2 < 0f && o3 * o4 < 0f) return true
        return (abs(o1) <= EPSILON && onSegment(a, b, c)) ||
            (abs(o2) <= EPSILON && onSegment(a, b, d)) ||
            (abs(o3) <= EPSILON && onSegment(c, d, a)) ||
            (abs(o4) <= EPSILON && onSegment(c, d, b))
    }

    private fun orientation(a: ReviewPoint, b: ReviewPoint, c: ReviewPoint): Float =
        (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    private fun onSegment(a: ReviewPoint, b: ReviewPoint, point: ReviewPoint): Boolean =
        point.x >= min(a.x, b.x) - EPSILON && point.x <= max(a.x, b.x) + EPSILON &&
            point.y >= min(a.y, b.y) - EPSILON && point.y <= max(a.y, b.y) + EPSILON

    private fun pointSegmentDistance(point: ReviewPoint, a: ReviewPoint, b: ReviewPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= EPSILON) return distance(point, a)
        val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
        return distance(point, ReviewPoint(a.x + t * dx, a.y + t * dy))
    }

    private fun distance(a: ReviewPoint, b: ReviewPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private const val EPSILON = 0.000001f
}

data class ReviewPoint(val x: Float, val y: Float)

