package com.studyink.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

class RemoteReviewGeometryTest {
    @Test
    fun portraitPageFitsCenterWithoutChangingAspectRatio() {
        val page = RemoteReviewGeometry.fitCenter(
            containerWidth = 1_000f,
            containerHeight = 1_000f,
            pageWidth = 600f,
            pageHeight = 800f,
        )

        assertEquals(125f, page.left, 0.001f)
        assertEquals(0f, page.top, 0.001f)
        assertEquals(875f, page.right, 0.001f)
        assertEquals(1_000f, page.bottom, 0.001f)
    }

    @Test
    fun viewAndNormalizedCoordinatesRoundTripAndRejectMargins() {
        val page = RemoteReviewRect(100f, 50f, 700f, 850f)
        val normalized = RemoteReviewGeometry.viewToNormalized(250f, 650f, page)

        assertNotNull(normalized)
        normalized!!
        assertEquals(0.25f, normalized.x, 0.0001f)
        assertEquals(0.75f, normalized.y, 0.0001f)
        val restored = RemoteReviewGeometry.normalizedToView(normalized, page)
        assertEquals(250f, restored.x, 0.001f)
        assertEquals(650f, restored.y, 0.001f)
        assertNull(RemoteReviewGeometry.viewToNormalized(99f, 400f, page))
    }

    @Test
    fun crossingEraserPathHitsEvenWhenAllEndpointsAreFarAway() {
        val stroke = stroke(
            listOf(
                RemoteNormalizedPoint(0.1f, 0.5f),
                RemoteNormalizedPoint(0.9f, 0.5f),
            )
        )
        val eraser = listOf(
            RemoteNormalizedPoint(0.5f, 0.1f),
            RemoteNormalizedPoint(0.5f, 0.9f),
        )

        assertTrue(RemoteReviewGeometry.intersectsEraser(stroke, eraser, 0.01f, 600, 900))
    }

    @Test
    fun portraitPageUsesCircularPixelMetricForDistantStroke() {
        val stroke = stroke(
            listOf(
                RemoteNormalizedPoint(0.1f, 0.1f),
                RemoteNormalizedPoint(0.2f, 0.1f),
            )
        )
        val eraser = listOf(
            RemoteNormalizedPoint(0.1f, 0.8f),
            RemoteNormalizedPoint(0.2f, 0.8f),
        )

        assertFalse(RemoteReviewGeometry.intersectsEraser(stroke, eraser, 0.05f, 600, 1_200))
    }

    @Test
    fun spatialBatchMatchesLegacyExactSegmentScan() {
        val random = Random(20_260_828)
        val strokes = buildList {
            add(stroke(listOf(RemoteNormalizedPoint(0.5f, 0.5f))).copy(id = "single-point"))
            add(
                stroke(
                    listOf(
                        RemoteNormalizedPoint(0.05f, 0.5f),
                        RemoteNormalizedPoint(0.95f, 0.5f),
                    ),
                ).copy(id = "crossing"),
            )
            repeat(80) { strokeIndex ->
                val points = List(1 + random.nextInt(12)) {
                    RemoteNormalizedPoint(random.nextFloat(), random.nextFloat())
                }
                add(
                    stroke(points).copy(
                        id = "random-$strokeIndex",
                        widthFraction = 0.001f + random.nextFloat() * 0.05f,
                    ),
                )
            }
        }
        val eraser = buildList {
            add(RemoteNormalizedPoint(0.15f, 0.1f))
            repeat(48) { index ->
                val fraction = (index + 1f) / 49f
                add(
                    RemoteNormalizedPoint(
                        x = 0.15f + fraction * 0.7f,
                        y = 0.1f + fraction * 0.8f + if (index % 2 == 0) 0.01f else -0.01f,
                    ),
                )
            }
        }
        val expected = strokes
            .filter { candidate -> legacyIntersects(candidate, eraser, 0.023f, 600, 1_200) }
            .mapTo(linkedSetOf(), RemoteFeedbackStroke::id)

        val actual = RemoteReviewGeometry.intersectingStrokeIds(
            strokes = strokes,
            eraserPath = eraser,
            eraserRadiusFraction = 0.023f,
            pageWidthPx = 600,
            pageHeightPx = 1_200,
        )

        assertEquals(expected, actual)
    }

    /** Reference implementation of the pre-index whole-pair scan. */
    private fun legacyIntersects(
        stroke: RemoteFeedbackStroke,
        eraser: List<RemoteNormalizedPoint>,
        radius: Float,
        pageWidthPx: Int,
        pageHeightPx: Int,
    ): Boolean {
        if (stroke.points.isEmpty() || eraser.isEmpty()) return false
        val shortSide = min(pageWidthPx, pageHeightPx).coerceAtLeast(1).toFloat()
        val xScale = pageWidthPx.coerceAtLeast(1) / shortSide
        val yScale = pageHeightPx.coerceAtLeast(1) / shortSide
        fun metric(point: RemoteNormalizedPoint) = TestPoint(point.x * xScale, point.y * yScale)
        val first = stroke.points.map(::metric)
        val second = eraser.map(::metric)
        val threshold = radius.coerceAtLeast(0f) + stroke.widthFraction.coerceAtLeast(0f) / 2f
        val firstSegments = first.zipWithNext().ifEmpty { listOf(first.first() to first.first()) }
        val secondSegments = second.zipWithNext().ifEmpty { listOf(second.first() to second.first()) }
        return firstSegments.any { (a, b) ->
            secondSegments.any { (c, d) -> legacySegmentDistance(a, b, c, d) <= threshold }
        }
    }

    private fun legacySegmentDistance(a: TestPoint, b: TestPoint, c: TestPoint, d: TestPoint): Float {
        if (legacySegmentsIntersect(a, b, c, d)) return 0f
        return minOf(
            legacyPointSegmentDistance(a, c, d),
            legacyPointSegmentDistance(b, c, d),
            legacyPointSegmentDistance(c, a, b),
            legacyPointSegmentDistance(d, a, b),
        )
    }

    private fun legacySegmentsIntersect(a: TestPoint, b: TestPoint, c: TestPoint, d: TestPoint): Boolean {
        fun orientation(first: TestPoint, second: TestPoint, third: TestPoint): Float =
            (second.x - first.x) * (third.y - first.y) -
                (second.y - first.y) * (third.x - first.x)
        fun onSegment(first: TestPoint, second: TestPoint, point: TestPoint): Boolean =
            point.x >= minOf(first.x, second.x) - TEST_EPSILON &&
                point.x <= maxOf(first.x, second.x) + TEST_EPSILON &&
                point.y >= minOf(first.y, second.y) - TEST_EPSILON &&
                point.y <= maxOf(first.y, second.y) + TEST_EPSILON
        val o1 = orientation(a, b, c)
        val o2 = orientation(a, b, d)
        val o3 = orientation(c, d, a)
        val o4 = orientation(c, d, b)
        return (o1 * o2 < 0f && o3 * o4 < 0f) ||
            (abs(o1) <= TEST_EPSILON && onSegment(a, b, c)) ||
            (abs(o2) <= TEST_EPSILON && onSegment(a, b, d)) ||
            (abs(o3) <= TEST_EPSILON && onSegment(c, d, a)) ||
            (abs(o4) <= TEST_EPSILON && onSegment(c, d, b))
    }

    private fun legacyPointSegmentDistance(point: TestPoint, a: TestPoint, b: TestPoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= TEST_EPSILON) return legacyDistance(point, a)
        val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared).coerceIn(0f, 1f)
        return legacyDistance(point, TestPoint(a.x + t * dx, a.y + t * dy))
    }

    private fun legacyDistance(a: TestPoint, b: TestPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun stroke(points: List<RemoteNormalizedPoint>) = RemoteFeedbackStroke(
        id = "teacher-stroke",
        tool = RemoteFeedbackStrokeTool.PEN,
        colorArgb = 0xFFD94747.toInt(),
        widthFraction = 0.004f,
        points = points,
    )

    private data class TestPoint(val x: Float, val y: Float)

    private companion object {
        const val TEST_EPSILON = 0.000001f
    }
}
