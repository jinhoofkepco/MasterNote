package com.studyink.annotation.engine

import com.studyink.core.model.PageBounds
import com.studyink.core.model.PagePoint
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal fun distance(a: PagePoint, b: PagePoint): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}

internal fun pointToSegmentDistance(p: PagePoint, a: PagePoint, b: PagePoint): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared <= 0.0001f) return distance(p, a)
    val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
    return distance(p, PagePoint(a.x + t * dx, a.y + t * dy))
}

internal fun pointToPolylineDistance(point: PagePoint, path: List<PagePoint>): Float {
    if (path.isEmpty()) return Float.POSITIVE_INFINITY
    if (path.size == 1) return distance(point, path.first())
    var result = Float.POSITIVE_INFINITY
    for (index in 0 until path.lastIndex) {
        result = min(result, pointToSegmentDistance(point, path[index], path[index + 1]))
    }
    return result
}

internal fun polylineLength(points: List<PagePoint>): Float =
    (0 until points.lastIndex).sumOf { distance(points[it], points[it + 1]).toDouble() }.toFloat()

internal fun resample(points: List<PagePoint>, maximumStep: Float): List<PagePoint> {
    if (points.size < 2) return points
    val output = ArrayList<PagePoint>(points.size * 2)
    output += points.first()
    for (index in 0 until points.lastIndex) {
        val start = points[index]
        val end = points[index + 1]
        val segmentLength = distance(start, end)
        val pieces = max(1, ceil(segmentLength / maximumStep.coerceAtLeast(0.5f)).toInt())
        for (piece in 1..pieces) {
            val t = piece.toFloat() / pieces
            output += PagePoint(
                x = start.x + (end.x - start.x) * t,
                y = start.y + (end.y - start.y) * t,
                pressure = start.pressure + (end.pressure - start.pressure) * t,
            )
        }
    }
    return output
}

internal fun pathBounds(path: List<PagePoint>, radius: Float): PageBounds = PageBounds.from(path).expanded(radius)
