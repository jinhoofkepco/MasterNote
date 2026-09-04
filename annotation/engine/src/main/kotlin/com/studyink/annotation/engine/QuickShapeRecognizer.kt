package com.studyink.annotation.engine

import com.studyink.core.model.PagePoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Geometric primitives that can replace a deliberate freehand quick-shape stroke. */
enum class QuickShapeKind {
    LINE,
    TRIANGLE,
    RECTANGLE,
    SQUARE,
    CIRCLE,
    ELLIPSE,
}

/**
 * A conservative recognition result. [score] is normalized to `0..1`; [points] contain canonical
 * geometry rather than a simplified copy of the input stroke.
 */
data class QuickShapeRecognition(
    val kind: QuickShapeKind,
    val score: Float,
    val points: List<PagePoint>,
)

/**
 * Recognizes the geometry of a completed quick-shape stroke.
 *
 * Gesture timing deliberately does not live here. The caller passes only the immutable prefix
 * before the end-hold began, so stationary tail jitter cannot influence recognition. Ambiguous
 * strokes are rejected: a winner must clear both an absolute confidence floor and a margin over
 * the best competing shape family.
 */
object QuickShapeRecognizer {
    private const val MIN_POINT_COUNT = 6
    private const val MIN_CLOSED_POINT_COUNT = 10
    const val DEFAULT_MINIMUM_DIAGONAL = 18f

    // Large closure tolerances turn a deliberately open C into a circle. Twelve percent still
    // permits a visibly imperfect hand-drawn seam while requiring a substantially complete loop.
    private const val CLOSED_GAP_FRACTION = 0.12f
    private const val MIN_WINNER_SCORE = 0.72f
    private const val MIN_RUNNER_UP_MARGIN = 0.075f
    private const val ELLIPSE_SEGMENTS = 72

    private val CORNER_EPSILON_FRACTIONS = floatArrayOf(0.025f, 0.04f, 0.06f, 0.085f)

    /**
     * [minimumDiagonal] is expressed in canonical page units. The input owner should convert its
     * physical/dp minimum through the current viewport so recognition stays invariant under zoom.
     */
    fun recognize(
        points: List<PagePoint>,
        minimumDiagonal: Float = DEFAULT_MINIMUM_DIAGONAL,
    ): QuickShapeRecognition? {
        if (!minimumDiagonal.isFinite() || minimumDiagonal <= 0f) return null
        if (points.size < MIN_POINT_COUNT || points.any { !it.x.isFinite() || !it.y.isFinite() }) {
            return null
        }

        val rawBounds = Bounds.of(points)
        val rawDiagonal = hypot(rawBounds.width, rawBounds.height)
        if (!rawDiagonal.isFinite() || rawDiagonal < minimumDiagonal) return null
        val input = removeConsecutiveDuplicates(points, rawDiagonal * 1e-6f)
        if (input.size < MIN_POINT_COUNT) return null

        val bounds = Bounds.of(input)
        val diagonal = hypot(bounds.width, bounds.height)
        val pathLength = pathLength(input)
        if (diagonal < minimumDiagonal || pathLength < minimumDiagonal) return null

        val pressure = input.map(PagePoint::pressure)
            .filter(Float::isFinite)
            .average()
            .takeIf(Double::isFinite)
            ?.toFloat()
            ?.coerceIn(0f, 1f)
            ?: 1f

        // A deliberate quick shape often runs a short distance past its starting point. Keep that
        // tail out of the closed-loop fit: otherwise the retraced edge can invent extra RDP corners
        // and unfairly inflate perimeter coverage.
        val loopInput = trimClosureOvershoot(input, diagonal)
        val loopPathLength = pathLength(loopInput)
        val gap = distance(loopInput.first(), loopInput.last())
        val isClosed = loopInput.size >= MIN_CLOSED_POINT_COUNT &&
            gap <= CLOSED_GAP_FRACTION * diagonal
        val candidates = mutableListOf<Candidate>()

        lineCandidate(input, diagonal, pathLength, pressure, minimumDiagonal)?.let(candidates::add)
        if (isClosed) {
            val closed = closeAtMidpoint(loopInput)
            val uniformlySampled = resampleByArcLength(closed, 96)
            ellipseCandidate(
                uniformlySampled, loopInput, diagonal, loopPathLength, gap, pressure,
            )?.let(candidates::add)
            candidates += polygonCandidates(
                uniformlySampled, diagonal, loopPathLength, gap, pressure,
            )
        }

        // Different RDP scales can produce the same kind. Only the best fit for each kind competes.
        val ranked = candidates
            .groupBy(Candidate::kind)
            .mapNotNull { (_, family) -> family.maxByOrNull(Candidate::score) }
            .sortedByDescending(Candidate::score)
        val winner = ranked.firstOrNull() ?: return null
        if (winner.score < MIN_WINNER_SCORE) return null
        val runnerUp = ranked.getOrNull(1)
        if (runnerUp != null && winner.score - runnerUp.score < MIN_RUNNER_UP_MARGIN) return null

        return QuickShapeRecognition(winner.kind, winner.score.coerceIn(0f, 1f), winner.points)
    }

    private fun lineCandidate(
        points: List<PagePoint>,
        diagonal: Float,
        pathLength: Float,
        pressure: Float,
        minimumDiagonal: Float,
    ): Candidate? {
        val first = points.first()
        val last = points.last()
        val chord = distance(first, last)
        if (chord < minimumDiagonal || chord < diagonal * 0.72f) return null

        val fit = principalAxis(points) ?: return null
        val deviations = points.map { orthogonalDistance(it, fit.center, fit.axis) }
        val rms = sqrt(deviations.sumOf { (it * it).toDouble() } / deviations.size).toFloat() / chord
        val maximum = deviations.maxOrNull()!! / chord
        val efficiency = chord / pathLength
        if (rms > 0.045f || maximum > 0.105f || efficiency < 0.86f) return null

        val startProjection = projection(first, fit.center, fit.axis)
        val endProjection = projection(last, fit.center, fit.axis)
        if (abs(endProjection - startProjection) < minimumDiagonal) return null
        val start = pointOnAxis(fit.center, fit.axis, startProjection, pressure)
        val end = pointOnAxis(fit.center, fit.axis, endProjection, pressure)

        val score = 1f - (
            0.45f * (rms / 0.07f) +
                0.30f * (maximum / 0.16f) +
                0.25f * ((1f - efficiency) / 0.20f)
            )
        return Candidate(QuickShapeKind.LINE, score.coerceIn(0f, 1f), listOf(start, end))
    }

    private fun ellipseCandidate(
        points: List<PagePoint>,
        sourcePoints: List<PagePoint>,
        diagonal: Float,
        pathLength: Float,
        closureGap: Float,
        pressure: Float,
    ): Candidate? {
        if (points.size < MIN_CLOSED_POINT_COUNT) return null
        val initialAxis = principalAxis(points) ?: return null
        var axisX = initialAxis.axis
        var axisY = Vector(-axisX.y, axisX.x)

        val initialU = points.map { projection(it, initialAxis.center, axisX) }
        val initialV = points.map { projection(it, initialAxis.center, axisY) }
        val center = initialAxis.center + axisX * ((initialU.min() + initialU.max()) / 2f) +
            axisY * ((initialV.min() + initialV.max()) / 2f)

        val us = points.map { projection(it, center, axisX) }
        val vs = points.map { projection(it, center, axisY) }
        var radiusX = (us.max() - us.min()) / 2f
        var radiusY = (vs.max() - vs.min()) / 2f
        if (radiusX < diagonal * 0.10f || radiusY < diagonal * 0.10f) return null
        if (radiusY > radiusX) {
            val oldX = axisX
            axisX = axisY
            axisY = Vector(-oldX.y, oldX.x)
            val swap = radiusX
            radiusX = radiusY
            radiusY = swap
        }
        val aspect = radiusX / radiusY
        if (aspect > 4f) return null

        val radialErrors = points.map { point ->
            val u = projection(point, center, axisX) / radiusX
            val v = projection(point, center, axisY) / radiusY
            abs(sqrt(u * u + v * v) - 1f)
        }
        val meanError = radialErrors.average().toFloat()
        val rmsError = sqrt(radialErrors.sumOf { (it * it).toDouble() } / radialErrors.size).toFloat()
        val p90Error = percentile(radialErrors, 0.90f)
        if (
            meanError > 0.075f || rmsError > 0.105f || p90Error > 0.17f ||
            radialErrors.max() > 0.20f
        ) return null
        if (!hasCoherentAngularTraversal(points, center, axisX, axisY, radiusX, radiusY)) return null
        if (!endpointTangentsFollowEllipse(
                sourcePoints, center, axisX, axisY, radiusX, radiusY, diagonal,
            )
        ) return null

        val circumference = ellipseCircumference(radiusX, radiusY)
        val coverageRatio = pathLength / circumference
        if (coverageRatio !in 0.76f..1.38f) return null
        val closurePenalty = closureGap / diagonal
        val coveragePenalty = abs(coverageRatio - 1f)

        val circle = aspect <= 1.16f
        if (circle) {
            val radius = (radiusX + radiusY) / 2f
            radiusX = radius
            radiusY = radius
        }
        val kind = if (circle) QuickShapeKind.CIRCLE else QuickShapeKind.ELLIPSE
        val score = 1f - (
            0.38f * (meanError / 0.10f) +
                0.24f * (rmsError / 0.14f) +
                0.16f * (p90Error / 0.24f) +
                0.12f * (closurePenalty / CLOSED_GAP_FRACTION) +
                0.10f * (coveragePenalty / 0.38f)
            )
        val canonical = buildEllipse(center, axisX, axisY, radiusX, radiusY, pressure)
        return Candidate(kind, score.coerceIn(0f, 1f), canonical)
    }

    private fun endpointTangentsFollowEllipse(
        points: List<PagePoint>,
        center: PagePoint,
        axisX: Vector,
        axisY: Vector,
        radiusX: Float,
        radiusY: Float,
        diagonal: Float,
    ): Boolean {
        if (points.size < 4 || diagonal <= 0f) return false
        val windowLength = 0.025f * diagonal

        var startIndex = 1
        var traveled = 0f
        while (startIndex < points.lastIndex && traveled < windowLength) {
            traveled += distance(points[startIndex - 1], points[startIndex])
            startIndex++
        }
        var endIndex = points.lastIndex - 1
        traveled = 0f
        while (endIndex > 0 && traveled < windowLength) {
            traveled += distance(points[endIndex], points[endIndex + 1])
            endIndex--
        }

        val startDirection = Vector(
            points[startIndex].x - points.first().x,
            points[startIndex].y - points.first().y,
        ).normalized()
        val endDirection = Vector(
            points.last().x - points[endIndex].x,
            points.last().y - points[endIndex].y,
        ).normalized()
        if (startDirection.length < 0.5f || endDirection.length < 0.5f) return false

        fun expectedTangent(point: PagePoint): Vector {
            val cosine = projection(point, center, axisX) / radiusX
            val sine = projection(point, center, axisY) / radiusY
            return (axisX * (-radiusX * sine) + axisY * (radiusY * cosine)).normalized()
        }
        val startTangent = expectedTangent(points.first())
        val endTangent = expectedTangent(points.last())
        val orientation = if (startDirection.dot(startTangent) >= 0f) 1f else -1f
        return orientation * startDirection.dot(startTangent) >= 0.90f &&
            orientation * endDirection.dot(endTangent) >= 0.90f
    }

    private fun hasCoherentAngularTraversal(
        points: List<PagePoint>,
        center: PagePoint,
        axisX: Vector,
        axisY: Vector,
        radiusX: Float,
        radiusY: Float,
    ): Boolean {
        var positive = 0f
        var negative = 0f
        val angles = points.map { point ->
            val u = projection(point, center, axisX) / radiusX
            val v = projection(point, center, axisY) / radiusY
            atan2(v, u)
        }
        for (index in angles.indices) {
            var delta = angles[(index + 1) % angles.size] - angles[index]
            while (delta > PI) delta -= (2.0 * PI).toFloat()
            while (delta < -PI) delta += (2.0 * PI).toFloat()
            if (delta >= 0f) positive += delta else negative -= delta
        }
        val totalTurn = positive + negative
        if (totalTurn !in 5.2f..7.5f) return false
        return max(positive, negative) / totalTurn >= 0.985f
    }

    private fun polygonCandidates(
        sampledPoints: List<PagePoint>,
        diagonal: Float,
        pathLength: Float,
        closureGap: Float,
        pressure: Float,
    ): List<Candidate> {
        val cornerSets = CORNER_EPSILON_FRACTIONS
            .map { cyclicRdpCorners(sampledPoints, diagonal * it, diagonal) }
            .filter { it.size == 3 || it.size == 4 }
        if (cornerSets.isEmpty()) return emptyList()

        val stableCounts = cornerSets.groupingBy(List<PagePoint>::size).eachCount()
        val candidates = mutableListOf<Candidate>()
        for (corners in cornerSets) {
            // One accidental vertex count at a single scale is too weak to steal handwriting.
            if ((stableCounts[corners.size] ?: 0) < 2) continue
            when (corners.size) {
                3 -> triangleCandidate(
                    corners, sampledPoints, diagonal, pathLength, closureGap, pressure,
                    stableCounts.getValue(3),
                )?.let(candidates::add)
                4 -> rectangleCandidate(
                    corners, sampledPoints, diagonal, pathLength, closureGap, pressure,
                    stableCounts.getValue(4),
                )?.let(candidates::add)
            }
        }
        return candidates
    }

    private fun triangleCandidate(
        corners: List<PagePoint>,
        points: List<PagePoint>,
        diagonal: Float,
        pathLength: Float,
        closureGap: Float,
        pressure: Float,
        stableScaleCount: Int,
    ): Candidate? {
        if (!isConvex(corners)) return null
        // RDP finds the topology, but its retained points are usually on the rounded shoulder of a
        // hand-drawn corner. Fit every side with total least squares, then intersect neighboring
        // side fits to recover crisp vertices without forcing any particular triangle angles.
        val refinedCorners = fitPolygonCorners(corners, points, diagonal) ?: return null
        if (!isConvex(refinedCorners)) return null
        val edges = cyclicEdges(refinedCorners)
        if (edges.minOf(Vector::length) < 0.20f * diagonal) return null
        val area = abs(signedArea(refinedCorners))
        if (area < 0.075f * diagonal * diagonal) return null
        val turns = cornerTurns(refinedCorners)
        if (turns.any { it !in 24f..154f }) return null

        val canonicalCorners = refinedCorners.map { PagePoint(it.x, it.y, pressure) }
        val closed = canonicalCorners + canonicalCorners.first()
        return polygonFitCandidate(
            kind = QuickShapeKind.TRIANGLE,
            canonical = closed,
            raw = points,
            diagonal = diagonal,
            pathLength = pathLength,
            closureGap = closureGap,
            shapePenalty = 0f,
            stableScaleCount = stableScaleCount,
        )
    }

    private fun fitPolygonCorners(
        corners: List<PagePoint>,
        points: List<PagePoint>,
        diagonal: Float,
    ): List<PagePoint>? {
        if (corners.size < 3) return null
        val sideBuckets = List(corners.size) { mutableListOf<SideSample>() }
        for (point in points) {
            var nearestSide = -1
            var nearestDistance = Float.POSITIVE_INFINITY
            var nearestFraction = 0f
            for (side in corners.indices) {
                val start = corners[side]
                val end = corners[(side + 1) % corners.size]
                val fraction = segmentProjectionFraction(point, start, end)
                val projected = interpolate(start, end, fraction)
                val candidateDistance = distance(point, projected)
                if (candidateDistance < nearestDistance) {
                    nearestDistance = candidateDistance
                    nearestSide = side
                    nearestFraction = fraction
                }
            }
            if (nearestSide >= 0) sideBuckets[nearestSide] += SideSample(point, nearestFraction)
        }

        val lines = ArrayList<LineFit>(corners.size)
        for (side in corners.indices) {
            val bucket = sideBuckets[side]
            if (bucket.size < 4) return null
            // Rounded corners and overshoots belong to two sides at once. The middle 76% of an
            // edge is a much cleaner TLS sample; fall back to the full bucket for very short sides.
            val middle = bucket.filter { it.fraction in 0.12f..0.88f }.map(SideSample::point)
            val fittingPoints = if (middle.size >= 4) middle else bucket.map(SideSample::point)
            val axisFit = principalAxis(fittingPoints) ?: return null
            val rms = sqrt(
                fittingPoints.sumOf {
                    val deviation = orthogonalDistance(it, axisFit.center, axisFit.axis)
                    (deviation * deviation).toDouble()
                } / fittingPoints.size,
            ).toFloat()
            if (rms > 0.04f * diagonal) return null
            lines += LineFit(axisFit.center, axisFit.axis)
        }

        return corners.indices.map { cornerIndex ->
            val previousLine = lines[(cornerIndex - 1 + lines.size) % lines.size]
            val currentLine = lines[cornerIndex]
            val intersection = intersect(previousLine, currentLine) ?: return null
            if (distance(intersection, corners[cornerIndex]) > 0.18f * diagonal) return null
            intersection
        }
    }

    private fun intersect(first: LineFit, second: LineFit): PagePoint? {
        val denominator = first.axis.cross(second.axis)
        if (abs(denominator) < 0.18f) return null
        val between = Vector(second.center.x - first.center.x, second.center.y - first.center.y)
        val amount = between.cross(second.axis) / denominator
        return pointOnAxis(first.center, first.axis, amount, 1f)
    }

    private fun segmentProjectionFraction(point: PagePoint, start: PagePoint, end: PagePoint): Float {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 1e-8f) return 0f
        return (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared)
            .coerceIn(0f, 1f)
    }

    private fun rectangleCandidate(
        corners: List<PagePoint>,
        points: List<PagePoint>,
        diagonal: Float,
        pathLength: Float,
        closureGap: Float,
        pressure: Float,
        stableScaleCount: Int,
    ): Candidate? {
        if (!isConvex(corners)) return null
        val edges = cyclicEdges(corners)
        if (edges.minOf(Vector::length) < 0.15f * diagonal) return null
        val unitEdges = edges.map(Vector::normalized)
        val rightAngleError = unitEdges.indices
            .map { abs(unitEdges[it].dot(unitEdges[(it + 1) % 4])) }
            .average()
            .toFloat()
        val parallelError = (
            abs(unitEdges[0].cross(unitEdges[2])) + abs(unitEdges[1].cross(unitEdges[3]))
            ) / 2f
        if (rightAngleError > 0.34f || parallelError > 0.28f) return null

        var axisX = (unitEdges[0] - unitEdges[2]).normalized()
        if (axisX.length < 0.5f) return null
        var axisY = Vector(-axisX.y, axisX.x)
        if (axisY.dot(unitEdges[1] - unitEdges[3]) < 0f) axisY = axisY * -1f

        val origin = centroid(corners)
        val us = points.map { projection(it, origin, axisX) }
        val vs = points.map { projection(it, origin, axisY) }
        val minU = us.min()
        val maxU = us.max()
        val minV = vs.min()
        val maxV = vs.max()
        val width = maxU - minU
        val height = maxV - minV
        if (min(width, height) < 0.15f * diagonal) return null

        val aspect = max(width, height) / min(width, height)
        val kind = if (aspect <= 1.18f) QuickShapeKind.SQUARE else QuickShapeKind.RECTANGLE
        val centerU = (minU + maxU) / 2f
        val centerV = (minV + maxV) / 2f
        val canonicalWidth = if (kind == QuickShapeKind.SQUARE) (width + height) / 2f else width
        val canonicalHeight = if (kind == QuickShapeKind.SQUARE) canonicalWidth else height
        val canonicalMinU = centerU - canonicalWidth / 2f
        val canonicalMaxU = centerU + canonicalWidth / 2f
        val canonicalMinV = centerV - canonicalHeight / 2f
        val canonicalMaxV = centerV + canonicalHeight / 2f
        val p0 = pointOnAxes(origin, axisX, axisY, canonicalMinU, canonicalMinV, pressure)
        val p1 = pointOnAxes(origin, axisX, axisY, canonicalMaxU, canonicalMinV, pressure)
        val p2 = pointOnAxes(origin, axisX, axisY, canonicalMaxU, canonicalMaxV, pressure)
        val p3 = pointOnAxes(origin, axisX, axisY, canonicalMinU, canonicalMaxV, pressure)
        val canonical = listOf(p0, p1, p2, p3, p0)
        val shapePenalty = 0.65f * (rightAngleError / 0.34f) + 0.35f * (parallelError / 0.28f)
        return polygonFitCandidate(
            kind, canonical, points, diagonal, pathLength, closureGap,
            shapePenalty.coerceIn(0f, 1f), stableScaleCount,
        )
    }

    private fun polygonFitCandidate(
        kind: QuickShapeKind,
        canonical: List<PagePoint>,
        raw: List<PagePoint>,
        diagonal: Float,
        pathLength: Float,
        closureGap: Float,
        shapePenalty: Float,
        stableScaleCount: Int,
    ): Candidate? {
        val deviations = raw.map { pointToClosedPolylineDistance(it, canonical) / diagonal }
        val meanDeviation = deviations.average().toFloat()
        val p90Deviation = percentile(deviations, 0.90f)
        if (meanDeviation > 0.045f || p90Deviation > 0.085f) return null

        val perimeter = pathLength(canonical)
        if (perimeter <= 0f) return null
        val coverageRatio = pathLength / perimeter
        if (coverageRatio !in 0.76f..1.42f) return null
        val coveragePenalty = abs(coverageRatio - 1f)
        val closurePenalty = closureGap / diagonal
        val stabilityBonus = ((stableScaleCount - 2).coerceAtMost(2)) * 0.015f
        val score = 1f - (
            0.42f * (meanDeviation / 0.065f) +
                0.25f * (p90Deviation / 0.11f) +
                0.14f * shapePenalty +
                0.10f * (closurePenalty / CLOSED_GAP_FRACTION) +
                0.09f * (coveragePenalty / 0.42f)
            ) + stabilityBonus
        return Candidate(kind, score.coerceIn(0f, 1f), canonical)
    }

    private fun cyclicRdpCorners(points: List<PagePoint>, epsilon: Float, diagonal: Float): List<PagePoint> {
        if (points.size < 4) return emptyList()
        val loop = if (
            points.first().x == points.last().x && points.first().y == points.last().y
        ) {
            points.dropLast(1)
        } else {
            points
        }
        if (loop.size < 3) return emptyList()

        // Ordinary RDP has a privileged first/last segment. On a closed stroke those points are
        // identical, so a stroke begun halfway along an edge can manufacture a seam corner. Split
        // the loop at an approximate diameter and simplify both open arcs instead.
        var firstAnchor = farthestPointIndex(loop, 0)
        var secondAnchor = farthestPointIndex(loop, firstAnchor)
        firstAnchor = farthestPointIndex(loop, secondAnchor)
        if (firstAnchor == secondAnchor) return emptyList()
        if (firstAnchor > secondAnchor) {
            val swap = firstAnchor
            firstAnchor = secondAnchor
            secondAnchor = swap
        }
        val firstArc = loop.subList(firstAnchor, secondAnchor + 1)
        val secondArc = loop.subList(secondAnchor, loop.size) + loop.subList(0, firstAnchor + 1)
        val simplified = (
            rdp(firstArc, epsilon).dropLast(1) + rdp(secondArc, epsilon).dropLast(1)
            ).toMutableList()
        if (simplified.size < 3) return simplified

        var changed = true
        while (changed && simplified.size > 3) {
            changed = false
            var index = 0
            while (index < simplified.size && simplified.size > 3) {
                val previous = simplified[(index - 1 + simplified.size) % simplified.size]
                val current = simplified[index]
                val next = simplified[(index + 1) % simplified.size]
                val shortEdge = min(distance(previous, current), distance(current, next)) < 0.035f * diagonal
                val nearlyStraight = directionChange(previous, current, next) < 18f
                if (shortEdge || nearlyStraight) {
                    simplified.removeAt(index)
                    changed = true
                } else {
                    index++
                }
            }
        }
        return simplified
    }

    private fun farthestPointIndex(points: List<PagePoint>, originIndex: Int): Int {
        val origin = points[originIndex]
        var farthestIndex = originIndex
        var maximumDistanceSquared = -1f
        for (index in points.indices) {
            val dx = points[index].x - origin.x
            val dy = points[index].y - origin.y
            val distanceSquared = dx * dx + dy * dy
            if (distanceSquared > maximumDistanceSquared) {
                maximumDistanceSquared = distanceSquared
                farthestIndex = index
            }
        }
        return farthestIndex
    }

    private fun rdp(points: List<PagePoint>, epsilon: Float): List<PagePoint> {
        if (points.size <= 2) return points
        val first = points.first()
        val last = points.last()
        var maximum = -1f
        var split = -1
        for (index in 1 until points.lastIndex) {
            val deviation = segmentDistance(points[index], first, last)
            if (deviation > maximum) {
                maximum = deviation
                split = index
            }
        }
        if (maximum <= epsilon || split <= 0) return listOf(first, last)
        val left = rdp(points.subList(0, split + 1), epsilon)
        val right = rdp(points.subList(split, points.size), epsilon)
        return left.dropLast(1) + right
    }

    private fun closeAtMidpoint(points: List<PagePoint>): List<PagePoint> {
        val first = points.first()
        val last = points.last()
        val pressure = (first.pressure + last.pressure) / 2f
        val midpoint = PagePoint((first.x + last.x) / 2f, (first.y + last.y) / 2f, pressure)
        return buildList(points.size) {
            add(midpoint)
            addAll(points.subList(1, points.lastIndex))
            add(midpoint)
        }
    }

    private fun resampleByArcLength(points: List<PagePoint>, count: Int): List<PagePoint> {
        if (points.size < 2 || count < 2) return points
        val cumulative = FloatArray(points.size)
        for (index in 1 until points.size) {
            cumulative[index] = cumulative[index - 1] + distance(points[index - 1], points[index])
        }
        val total = cumulative.last()
        if (total <= 0f) return listOf(points.first())
        val result = ArrayList<PagePoint>(count)
        var segment = 1
        for (sample in 0 until count) {
            val target = total * sample / count
            while (segment < cumulative.lastIndex && cumulative[segment] < target) segment++
            val startDistance = cumulative[segment - 1]
            val endDistance = cumulative[segment]
            val t = if (endDistance > startDistance) {
                (target - startDistance) / (endDistance - startDistance)
            } else {
                0f
            }
            result += interpolate(points[segment - 1], points[segment], t)
        }
        return result
    }

    private fun buildEllipse(
        center: PagePoint,
        axisX: Vector,
        axisY: Vector,
        radiusX: Float,
        radiusY: Float,
        pressure: Float,
    ): List<PagePoint> = (0..ELLIPSE_SEGMENTS).map { index ->
        val angle = 2.0 * PI * index / ELLIPSE_SEGMENTS
        val u = radiusX * cos(angle).toFloat()
        val v = radiusY * sin(angle).toFloat()
        pointOnAxes(center, axisX, axisY, u, v, pressure)
    }

    private fun principalAxis(points: List<PagePoint>): AxisFit? {
        if (points.size < 2) return null
        val center = centroid(points)
        var xx = 0.0
        var yy = 0.0
        var xy = 0.0
        for (point in points) {
            val dx = (point.x - center.x).toDouble()
            val dy = (point.y - center.y).toDouble()
            xx += dx * dx
            yy += dy * dy
            xy += dx * dy
        }
        val angle = 0.5 * atan2(2.0 * xy, xx - yy)
        val axis = Vector(cos(angle).toFloat(), sin(angle).toFloat()).normalized()
        return if (axis.length > 0f) AxisFit(center, axis) else null
    }

    private fun removeConsecutiveDuplicates(points: List<PagePoint>, epsilon: Float): List<PagePoint> {
        if (points.isEmpty()) return emptyList()
        val result = ArrayList<PagePoint>(points.size)
        result += points.first()
        for (index in 1 until points.size) {
            if (distance(result.last(), points[index]) > epsilon) result += points[index]
        }
        return result
    }

    private fun trimClosureOvershoot(points: List<PagePoint>, diagonal: Float): List<PagePoint> {
        if (points.size < MIN_CLOSED_POINT_COUNT + 2 || diagonal <= 0f) return points
        val first = points.first()
        var closestIndex = points.lastIndex
        var closestDistance = distance(first, points.last())
        var candidateTailLength = 0f
        for (index in points.lastIndex - 1 downTo MIN_CLOSED_POINT_COUNT - 1) {
            candidateTailLength += distance(points[index], points[index + 1])
            if (candidateTailLength > 0.25f * diagonal) break
            val candidateDistance = distance(first, points[index])
            if (candidateDistance <= closestDistance) {
                closestDistance = candidateDistance
                closestIndex = index
            }
        }
        if (closestIndex == points.lastIndex || closestDistance > 0.08f * diagonal) return points

        val finalGap = distance(first, points.last())
        val tailLength = pathLength(points.subList(closestIndex, points.size))
        val meaningfullyCloser = closestDistance + 0.01f * diagonal < finalGap
        val shortTail = tailLength <= 0.25f * diagonal
        val retracesBeginning = shortTail && tailRetracesBeginning(
            points = points,
            closureIndex = closestIndex,
            tailLength = tailLength,
            diagonal = diagonal,
        )
        return if (meaningfullyCloser && retracesBeginning) {
            points.subList(0, closestIndex + 1)
        } else {
            points
        }
    }

    private fun tailRetracesBeginning(
        points: List<PagePoint>,
        closureIndex: Int,
        tailLength: Float,
        diagonal: Float,
    ): Boolean {
        val prefix = ArrayList<PagePoint>()
        prefix += points.first()
        val requiredPrefixLength = min(0.30f * diagonal, tailLength * 1.35f + 0.02f * diagonal)
        var prefixLength = 0f
        var index = 1
        while (index <= closureIndex && prefixLength < requiredPrefixLength) {
            prefixLength += distance(points[index - 1], points[index])
            prefix += points[index]
            index++
        }
        if (prefix.size < 2) return false

        val deviations = points.subList(closureIndex + 1, points.size)
            .map { pointToOpenPolylineDistance(it, prefix) / diagonal }
        if (deviations.isEmpty()) return false
        return percentile(deviations, 0.90f) <= 0.03f && deviations.max() <= 0.06f
    }

    private fun pointToOpenPolylineDistance(point: PagePoint, polyline: List<PagePoint>): Float {
        if (polyline.size < 2) return Float.POSITIVE_INFINITY
        var result = Float.POSITIVE_INFINITY
        for (index in 0 until polyline.lastIndex) {
            result = min(result, segmentDistance(point, polyline[index], polyline[index + 1]))
        }
        return result
    }

    private fun pointToClosedPolylineDistance(point: PagePoint, polygon: List<PagePoint>): Float {
        var result = Float.POSITIVE_INFINITY
        for (index in 0 until polygon.lastIndex) {
            result = min(result, segmentDistance(point, polygon[index], polygon[index + 1]))
        }
        return result
    }

    private fun segmentDistance(point: PagePoint, start: PagePoint, end: PagePoint): Float {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 1e-8f) return distance(point, start)
        val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared)
            .coerceIn(0f, 1f)
        return hypot(point.x - (start.x + t * dx), point.y - (start.y + t * dy))
    }

    private fun pathLength(points: List<PagePoint>): Float {
        var result = 0f
        for (index in 1 until points.size) result += distance(points[index - 1], points[index])
        return result
    }

    private fun distance(a: PagePoint, b: PagePoint): Float = hypot(a.x - b.x, a.y - b.y)

    private fun projection(point: PagePoint, origin: PagePoint, axis: Vector): Float =
        (point.x - origin.x) * axis.x + (point.y - origin.y) * axis.y

    private fun orthogonalDistance(point: PagePoint, origin: PagePoint, axis: Vector): Float =
        abs((point.x - origin.x) * axis.y - (point.y - origin.y) * axis.x)

    private fun pointOnAxis(origin: PagePoint, axis: Vector, amount: Float, pressure: Float): PagePoint =
        PagePoint(origin.x + axis.x * amount, origin.y + axis.y * amount, pressure)

    private fun pointOnAxes(
        origin: PagePoint,
        axisX: Vector,
        axisY: Vector,
        u: Float,
        v: Float,
        pressure: Float,
    ): PagePoint = PagePoint(
        origin.x + axisX.x * u + axisY.x * v,
        origin.y + axisX.y * u + axisY.y * v,
        pressure,
    )

    private fun interpolate(a: PagePoint, b: PagePoint, t: Float): PagePoint = PagePoint(
        a.x + (b.x - a.x) * t,
        a.y + (b.y - a.y) * t,
        a.pressure + (b.pressure - a.pressure) * t,
    )

    private fun centroid(points: List<PagePoint>): PagePoint = PagePoint(
        points.sumOf { it.x.toDouble() }.div(points.size).toFloat(),
        points.sumOf { it.y.toDouble() }.div(points.size).toFloat(),
    )

    private fun cyclicEdges(points: List<PagePoint>): List<Vector> = points.indices.map { index ->
        Vector(
            points[(index + 1) % points.size].x - points[index].x,
            points[(index + 1) % points.size].y - points[index].y,
        )
    }

    private fun cornerTurns(points: List<PagePoint>): List<Float> = points.indices.map { index ->
        val previous = points[(index - 1 + points.size) % points.size]
        val current = points[index]
        val next = points[(index + 1) % points.size]
        directionChange(previous, current, next)
    }

    /** Direction change in degrees: zero is straight; ninety is a square corner. */
    private fun directionChange(previous: PagePoint, current: PagePoint, next: PagePoint): Float {
        val incoming = Vector(current.x - previous.x, current.y - previous.y).normalized()
        val outgoing = Vector(next.x - current.x, next.y - current.y).normalized()
        return Math.toDegrees(acos(incoming.dot(outgoing).coerceIn(-1f, 1f)).toDouble()).toFloat()
    }

    private fun isConvex(points: List<PagePoint>): Boolean {
        if (points.size < 3) return false
        var sign = 0
        for (index in points.indices) {
            val a = points[index]
            val b = points[(index + 1) % points.size]
            val c = points[(index + 2) % points.size]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (cross == 0f) return false
            val currentSign = if (cross > 0f) 1 else -1
            if (sign != 0 && sign != currentSign) return false
            sign = currentSign
        }
        return true
    }

    private fun signedArea(points: List<PagePoint>): Float {
        var twiceArea = 0f
        for (index in points.indices) {
            val next = points[(index + 1) % points.size]
            twiceArea += points[index].x * next.y - next.x * points[index].y
        }
        return twiceArea / 2f
    }

    private fun percentile(values: List<Float>, fraction: Float): Float {
        if (values.isEmpty()) return Float.POSITIVE_INFINITY
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * fraction).toInt().coerceIn(sorted.indices)
        return sorted[index]
    }

    private fun ellipseCircumference(a: Float, b: Float): Float {
        val h = ((a - b) * (a - b)) / ((a + b) * (a + b))
        return (PI * (a + b) * (1.0 + 3.0 * h / (10.0 + sqrt(4.0 - 3.0 * h)))).toFloat()
    }

    private data class Candidate(
        val kind: QuickShapeKind,
        val score: Float,
        val points: List<PagePoint>,
    )

    private data class AxisFit(val center: PagePoint, val axis: Vector)

    private data class LineFit(val center: PagePoint, val axis: Vector)

    private data class SideSample(val point: PagePoint, val fraction: Float)

    private data class Vector(val x: Float, val y: Float) {
        val length: Float get() = hypot(x, y)
        fun normalized(): Vector = if (length > 1e-6f) Vector(x / length, y / length) else Vector(0f, 0f)
        fun dot(other: Vector): Float = x * other.x + y * other.y
        fun cross(other: Vector): Float = x * other.y - y * other.x
        operator fun plus(other: Vector): Vector = Vector(x + other.x, y + other.y)
        operator fun minus(other: Vector): Vector = Vector(x - other.x, y - other.y)
        operator fun times(scale: Float): Vector = Vector(x * scale, y * scale)
    }

    private operator fun PagePoint.plus(vector: Vector): PagePoint = PagePoint(x + vector.x, y + vector.y, pressure)

    private data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top

        companion object {
            fun of(points: List<PagePoint>) = Bounds(
                points.minOf(PagePoint::x),
                points.minOf(PagePoint::y),
                points.maxOf(PagePoint::x),
                points.maxOf(PagePoint::y),
            )
        }
    }
}
