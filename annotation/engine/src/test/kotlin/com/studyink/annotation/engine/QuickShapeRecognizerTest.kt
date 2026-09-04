package com.studyink.annotation.engine

import com.studyink.core.model.PagePoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickShapeRecognizerTest {
    @Test
    fun `recognizes a noisy diagonal line and emits two fitted endpoints`() {
        val points = sampleOpenLine(
            PagePoint(40f, 75f),
            PagePoint(390f, 265f),
            count = 56,
            noise = 1.4f,
        )

        val result = QuickShapeRecognizer.recognize(points)

        assertNotNull(result)
        assertEquals(QuickShapeKind.LINE, result!!.kind)
        assertEquals(2, result.points.size)
        assertTrue(result.score >= 0.72f)
        assertTrue(distance(result.points.first(), points.first()) < 3f)
        assertTrue(distance(result.points.last(), points.last()) < 3f)
    }

    @Test
    fun `recognizes a triangle and closes its canonical polyline`() {
        val points = sampleClosedPolygon(
            listOf(
                PagePoint(90f, 310f),
                PagePoint(245f, 65f),
                PagePoint(425f, 325f),
            ),
            samplesPerEdge = 22,
            noise = 1.2f,
        )

        val result = QuickShapeRecognizer.recognize(points)

        assertNotNull(result)
        assertEquals(QuickShapeKind.TRIANGLE, result!!.kind)
        assertEquals(4, result.points.size)
        assertPointNear(result.points.first(), result.points.last(), 0.001f)
        assertTrue(result.score >= 0.72f)
    }

    @Test
    fun `TLS side fits recover triangle vertices from rounded hand drawn corners`() {
        val expected = listOf(
            PagePoint(105f, 335f),
            PagePoint(275f, 70f),
            PagePoint(455f, 350f),
        )
        val points = sampleRoundedPolygon(
            expected,
            edgeSamples = 24,
            cornerSamples = 8,
            rounding = 0.075f,
            noise = 1.4f,
        )

        val result = QuickShapeRecognizer.recognize(points)

        assertNotNull(result)
        assertEquals(QuickShapeKind.TRIANGLE, result!!.kind)
        val actual = result.points.dropLast(1)
        expected.forEach { vertex ->
            assertTrue(
                "TLS vertices $actual do not contain expected $vertex",
                actual.minOf { distance(it, vertex) } < 9f,
            )
        }
    }

    @Test
    fun `recognizes a rotated rectangle and emits four square corners`() {
        val points = sampleClosedPolygon(
            rotatedBox(centerX = 310f, centerY = 280f, width = 310f, height = 135f, angleDegrees = 31f),
            samplesPerEdge = 24,
            noise = 1.5f,
        )

        val result = QuickShapeRecognizer.recognize(points)

        assertNotNull(result)
        assertEquals(QuickShapeKind.RECTANGLE, result!!.kind)
        assertEquals(5, result.points.size)
        assertPointNear(result.points.first(), result.points.last(), 0.001f)
        assertRightAngles(result.points.dropLast(1))
        assertTrue(result.score >= 0.72f)
    }

    @Test
    fun `distinguishes a rotated square from a rectangle`() {
        val points = sampleClosedPolygon(
            rotatedBox(centerX = 250f, centerY = 220f, width = 190f, height = 183f, angleDegrees = -23f),
            samplesPerEdge = 20,
            noise = 1.0f,
        )

        val result = QuickShapeRecognizer.recognize(points)

        assertNotNull(result)
        assertEquals(QuickShapeKind.SQUARE, result!!.kind)
        assertEquals(5, result.points.size)
        assertRightAngles(result.points.dropLast(1))
        val canonicalEdges = result.points.zipWithNext { first, second -> distance(first, second) }
        assertTrue(canonicalEdges.max() - canonicalEdges.min() < 0.01f)
    }

    @Test
    fun `accepts rounded rectangle corners`() {
        val corners = rotatedBox(330f, 260f, width = 300f, height = 150f, angleDegrees = 17f)
        val points = sampleRoundedPolygon(
            corners,
            edgeSamples = 25,
            cornerSamples = 8,
            rounding = 0.065f,
            noise = 1.2f,
        )

        val result = QuickShapeRecognizer.recognize(points)

        assertNotNull(result)
        assertEquals(QuickShapeKind.RECTANGLE, result!!.kind)
        assertEquals(5, result.points.size)
        assertRightAngles(result.points.dropLast(1))
    }

    @Test
    fun `accepts corner dwell jitter and a small closure overshoot`() {
        val corners = listOf(
            PagePoint(95f, 325f),
            PagePoint(260f, 70f),
            PagePoint(440f, 335f),
        )
        val base = sampleClosedPolygon(corners, samplesPerEdge = 20, noise = 1.0f)
        val withDwell = addCornerDwell(base, corners, repeats = 9, radius = 1.6f).toMutableList()
        assertNotNull(QuickShapeRecognizer.recognize(withDwell))
        // Continue a little way along the first edge after closing the loop.
        repeat(100) { index ->
            // Deliberately dense tail sampling verifies that closure search is arc-length based.
            val t = (index + 1) / 500f
            withDwell += lerp(corners[0], corners[1], t)
        }

        val result = QuickShapeRecognizer.recognize(withDwell)

        assertNotNull(result)
        assertEquals(QuickShapeKind.TRIANGLE, result!!.kind)
    }

    @Test
    fun `does not discard an arbitrary inward flourish after a closed outline`() {
        val corners = listOf(
            PagePoint(95f, 325f),
            PagePoint(260f, 70f),
            PagePoint(440f, 335f),
        )
        val stroke = sampleClosedPolygon(corners, samplesPerEdge = 20, noise = 0.8f).toMutableList()
        val inward = PagePoint(235f, 235f)
        repeat(8) { index ->
            stroke += lerp(corners.first(), inward, (index + 1) / 16f)
        }

        assertNull(QuickShapeRecognizer.recognize(stroke))
    }

    @Test
    fun `rejects circle-like paths with a spur chord or local self crossing`() {
        val circleWithSpur = sampleEllipse(
            centerX = 260f,
            centerY = 245f,
            radiusX = 145f,
            radiusY = 140f,
            angleDegrees = 0f,
            noise = 0.8f,
        ).toMutableList()
        val circleStart = circleWithSpur.first()
        val inward = PagePoint(260f + 0.74f * 145f, 245f)
        repeat(10) { index ->
            circleWithSpur += lerp(circleStart, inward, (index + 1) / 10f)
        }

        val arc = sampleEllipseArc(
            centerX = 260f,
            centerY = 245f,
            radiusX = 145f,
            radiusY = 140f,
            startDegrees = 0f,
            sweepDegrees = 300f,
            noise = 0.8f,
            samples = 100,
        )
        val chordClosed = buildList {
            addAll(arc)
            repeat(20) { index -> add(lerp(arc.last(), arc.first(), (index + 1) / 20f)) }
        }
        fun circlePoint(degrees: Float): PagePoint {
            val angle = degrees / 180f * PI.toFloat()
            return PagePoint(260f + 145f * cos(angle), 245f + 140f * sin(angle))
        }
        val crossingLead = sampleOpenPolyline(
            listOf(circlePoint(0f), circlePoint(20f), circlePoint(10f), circlePoint(30f)),
            samplesPerEdge = 6,
            noise = 0f,
        )
        val remainingArc = sampleEllipseArc(
            centerX = 260f,
            centerY = 245f,
            radiusX = 145f,
            radiusY = 140f,
            startDegrees = 30f,
            sweepDegrees = 330f,
            noise = 0.5f,
            samples = 100,
        )
        val locallyCrossed = crossingLead + remainingArc.drop(1)

        assertNull(QuickShapeRecognizer.recognize(circleWithSpur))
        assertNull(QuickShapeRecognizer.recognize(chordClosed))
        assertNull(QuickShapeRecognizer.recognize(locallyCrossed))
    }

    @Test
    fun `recognizes a noisy circle and emits a 72 segment canonical loop`() {
        val points = sampleEllipse(
            centerX = 300f,
            centerY = 260f,
            radiusX = 145f,
            radiusY = 140f,
            angleDegrees = 0f,
            noise = 1.5f,
        )

        val result = QuickShapeRecognizer.recognize(points)

        assertNotNull(result)
        assertEquals(QuickShapeKind.CIRCLE, result!!.kind)
        assertEquals(73, result.points.size)
        assertPointNear(result.points.first(), result.points.last(), 0.001f)
        assertTrue(result.score >= 0.72f)
    }

    @Test
    fun `recognizes a rotated ellipse rather than a circle`() {
        val points = sampleEllipse(
            centerX = 380f,
            centerY = 330f,
            radiusX = 185f,
            radiusY = 82f,
            angleDegrees = 34f,
            noise = 1.3f,
        )

        val result = QuickShapeRecognizer.recognize(points)

        assertNotNull(result)
        assertEquals(QuickShapeKind.ELLIPSE, result!!.kind)
        assertEquals(73, result.points.size)
        assertPointNear(result.points.first(), result.points.last(), 0.001f)
    }

    @Test
    fun `rejects too few points tiny marks open polygons and handwriting-like scribbles`() {
        assertNull(
            QuickShapeRecognizer.recognize(
                listOf(PagePoint(0f, 0f), PagePoint(50f, 50f), PagePoint(100f, 0f)),
            ),
        )
        assertNull(
            QuickShapeRecognizer.recognize(
                sampleOpenLine(PagePoint(10f, 10f), PagePoint(20f, 14f), count = 20, noise = 0.1f),
            ),
        )

        val openTriangle = sampleOpenPolyline(
            listOf(PagePoint(80f, 300f), PagePoint(245f, 65f), PagePoint(425f, 325f)),
            18,
            1f,
        )
        assertNull(QuickShapeRecognizer.recognize(openTriangle))

        val scribble = buildList {
            repeat(9) { index ->
                val x = 50f + index * 38f
                add(PagePoint(x, if (index % 2 == 0) 80f else 270f))
                add(PagePoint(x + 19f, if (index % 2 == 0) 255f else 95f))
            }
        }
        assertNull(QuickShapeRecognizer.recognize(scribble))
    }

    @Test
    fun `triangle and rectangle recognizers do not steal V check A four circle or self crossing strokes`() {
        val openV = sampleOpenPolyline(
            listOf(PagePoint(80f, 80f), PagePoint(240f, 320f), PagePoint(410f, 75f)),
            samplesPerEdge = 18,
            noise = 0.8f,
        )
        val check = sampleOpenPolyline(
            listOf(PagePoint(75f, 210f), PagePoint(165f, 300f), PagePoint(390f, 65f)),
            samplesPerEdge = 18,
            noise = 0.8f,
        )
        val letterA = sampleOpenPolyline(
            listOf(
                PagePoint(75f, 340f),
                PagePoint(235f, 55f),
                PagePoint(410f, 340f),
                PagePoint(325f, 205f),
                PagePoint(150f, 205f),
            ),
            samplesPerEdge = 12,
            noise = 0.6f,
        )
        val digitFour = sampleOpenPolyline(
            listOf(
                PagePoint(335f, 350f),
                PagePoint(335f, 65f),
                PagePoint(95f, 260f),
                PagePoint(420f, 260f),
            ),
            samplesPerEdge = 14,
            noise = 0.5f,
        )
        val bowTie = sampleClosedPolygon(
            listOf(
                PagePoint(80f, 80f),
                PagePoint(400f, 320f),
                PagePoint(80f, 320f),
                PagePoint(400f, 80f),
            ),
            samplesPerEdge = 18,
            noise = 0.5f,
        )

        listOf(openV, check, letterA, digitFour, bowTie).forEach { stroke ->
            val result = QuickShapeRecognizer.recognize(stroke)
            assertTrue(
                "unexpected polygon recognition: $result",
                result == null || (result.kind != QuickShapeKind.TRIANGLE &&
                    result.kind != QuickShapeKind.RECTANGLE && result.kind != QuickShapeKind.SQUARE),
            )
        }

        val circle = QuickShapeRecognizer.recognize(
            sampleEllipse(260f, 240f, 145f, 142f, 0f, noise = 0.8f),
        )
        assertNotNull(circle)
        assertEquals(QuickShapeKind.CIRCLE, circle!!.kind)
        assertNotEquals(QuickShapeKind.TRIANGLE, circle.kind)
    }

    @Test
    fun `rejects an open C arc instead of silently closing it into a circle`() {
        val openC = sampleEllipseArc(
            centerX = 260f,
            centerY = 245f,
            radiusX = 150f,
            radiusY = 145f,
            startDegrees = 12.5f,
            sweepDegrees = 335f,
            noise = 1.2f,
        )

        assertNull(QuickShapeRecognizer.recognize(openC))
    }

    @Test
    fun `allows a small hand drawn circle seam without accepting a clear C gap`() {
        val almostClosedCircle = sampleEllipseArc(
            centerX = 260f,
            centerY = 245f,
            radiusX = 150f,
            radiusY = 145f,
            startDegrees = 6f,
            sweepDegrees = 348f,
            noise = 1.2f,
        )

        val result = QuickShapeRecognizer.recognize(almostClosedCircle)

        assertNotNull(result)
        assertEquals(QuickShapeKind.CIRCLE, result!!.kind)
    }

    @Test
    fun `recognizes seeded realistic variations in rotation noise direction and stroke start`() {
        val random = Random(0x51A9E)
        repeat(16) { case ->
            val length = random.nextFloat() * 300f + 170f
            val angle = random.nextFloat() * 2f * PI.toFloat()
            val start = PagePoint(random.nextFloat() * 120f, random.nextFloat() * 120f)
            val end = PagePoint(start.x + cos(angle) * length, start.y + sin(angle) * length)
            val line = sampleWobblyLine(start, end, 28 + random.nextInt(50), length * 0.018f)
            assertKind("line $case", line, QuickShapeKind.LINE)
        }

        repeat(16) { case ->
            val width = random.nextFloat() * 190f + 210f
            val height = random.nextFloat() * 150f + 190f
            val apexOffset = (random.nextFloat() - 0.5f) * width * 0.22f
            val triangleAngle = random.nextFloat() * 360f - 180f
            val triangle = listOf(
                PagePoint(-width / 2f, height / 2f),
                PagePoint(apexOffset, -height / 2f),
                PagePoint(width / 2f, height / 2f),
            ).rotate(triangleAngle, PagePoint(360f, 330f))
            val rounding = 0.035f + random.nextFloat() * 0.045f
            val noise = 1.2f + random.nextFloat() * 3.8f
            var stroke = sampleRoundedPolygon(
                triangle,
                edgeSamples = 18 + random.nextInt(15),
                cornerSamples = 5 + random.nextInt(6),
                rounding = rounding,
                noise = noise,
            )
            val startOffset = random.nextInt(stroke.size - 1)
            stroke = rotateClosedStroke(stroke, startOffset)
            if (case % 4 == 0) stroke = stroke.dropLast(2)
            val reversed = random.nextBoolean()
            if (reversed) stroke = stroke.reversed()
            assertKind(
                "triangle $case width=$width height=$height apex=$apexOffset angle=$triangleAngle " +
                    "rounding=$rounding noise=$noise start=$startOffset reversed=$reversed",
                stroke,
                QuickShapeKind.TRIANGLE,
            )
        }

        repeat(16) { case ->
            val height = random.nextFloat() * 100f + 115f
            val width = height * (1.45f + random.nextFloat() * 1.65f)
            val rectangle = rotatedBox(
                centerX = 350f,
                centerY = 300f,
                width = width,
                height = height,
                angleDegrees = random.nextFloat() * 360f - 180f,
            )
            var stroke = sampleRoundedPolygon(
                rectangle,
                edgeSamples = 18 + random.nextInt(14),
                cornerSamples = 5 + random.nextInt(6),
                rounding = 0.03f + random.nextFloat() * 0.045f,
                noise = 1f + random.nextFloat() * 3f,
            )
            stroke = rotateClosedStroke(stroke, random.nextInt(stroke.size - 1))
            if (case % 4 == 0) stroke = stroke.dropLast(2)
            if (random.nextBoolean()) stroke = stroke.reversed()
            assertKind("rectangle $case", stroke, QuickShapeKind.RECTANGLE)
        }

        repeat(16) { case ->
            val radiusY = random.nextFloat() * 75f + 80f
            val isCircle = case % 2 == 0
            val radiusX = radiusY * if (isCircle) {
                1.01f + random.nextFloat() * 0.08f
            } else {
                1.38f + random.nextFloat() * 1.35f
            }
            val ellipseAngle = random.nextFloat() * 360f - 180f
            val ellipseNoise = 1f + random.nextFloat() * 3f
            val ellipseSamples = 72 + random.nextInt(80)
            var stroke = sampleWobblyEllipse(
                centerX = 330f,
                centerY = 290f,
                radiusX = radiusX,
                radiusY = radiusY,
                angleDegrees = ellipseAngle,
                noise = ellipseNoise,
                samples = ellipseSamples,
            )
            val ellipseStart = random.nextInt(stroke.size - 1)
            stroke = rotateClosedStroke(stroke, ellipseStart)
            if (case % 4 == 0) stroke = stroke.dropLast(2)
            val ellipseReversed = random.nextBoolean()
            if (ellipseReversed) stroke = stroke.reversed()
            assertKind(
                "ellipse $case rx=$radiusX ry=$radiusY angle=$ellipseAngle noise=$ellipseNoise " +
                    "samples=$ellipseSamples start=$ellipseStart reversed=$ellipseReversed",
                stroke,
                if (isCircle) QuickShapeKind.CIRCLE else QuickShapeKind.ELLIPSE,
            )
        }
    }

    @Test
    fun `rejects seeded handwriting like waves spirals loops stars and jagged strokes`() {
        val wave = (0..120).map { index ->
            val t = index / 120f
            PagePoint(
                40f + 420f * t,
                240f + 82f * sin(t * 6f * PI.toFloat()) + 18f * sin(t * 14f * PI.toFloat()),
            )
        }
        val spiral = (0..150).map { index ->
            val t = index / 150f
            val angle = t * 4.5f * PI.toFloat()
            val radius = 24f + 145f * t
            PagePoint(270f + radius * cos(angle), 250f + radius * sin(angle))
        }
        val figureEight = (0..128).map { index ->
            val angle = index / 128f * 2f * PI.toFloat()
            PagePoint(260f + 155f * sin(angle), 240f + 100f * sin(2f * angle))
        }
        val doubleCircle = (0..180).map { index ->
            val angle = index / 180f * 4f * PI.toFloat()
            PagePoint(260f + 145f * cos(angle), 240f + 140f * sin(angle))
        }
        val starVertices = (0 until 10).map { index ->
            val angle = -PI.toFloat() / 2f + index * PI.toFloat() / 5f
            val radius = if (index % 2 == 0) 170f else 70f
            PagePoint(270f + radius * cos(angle), 250f + radius * sin(angle))
        }
        val star = sampleClosedPolygon(starVertices, samplesPerEdge = 10, noise = 0.8f)

        val namedStrokes = listOf(
            "wave" to wave,
            "spiral" to spiral,
            "figure eight" to figureEight,
            "double circle" to doubleCircle,
            "star" to star,
        )
        namedStrokes.forEach { (label, stroke) ->
            assertNull("$label unexpectedly recognized", QuickShapeRecognizer.recognize(stroke))
        }

        val random = Random(0xBAD5EED)
        repeat(32) { case ->
            val vertices = buildList {
                repeat(8) { index ->
                    val x = 50f + index * 52f
                    val upperBand = if (index % 2 == 0) 70f else 230f
                    add(PagePoint(x, upperBand + random.nextFloat() * 95f))
                }
            }
            val jagged = sampleOpenPolyline(vertices, samplesPerEdge = 7, noise = 1f)
            assertNull(
                "jagged stroke $case unexpectedly recognized",
                QuickShapeRecognizer.recognize(jagged),
            )
        }
    }

    @Test
    fun `caller supplied minimum diagonal keeps decisions scale independent`() {
        val original = sampleClosedPolygon(
            listOf(PagePoint(50f, 220f), PagePoint(150f, 45f), PagePoint(270f, 225f)),
            samplesPerEdge = 18,
            noise = 0.5f,
        )
        val scale = 0.05f
        val scaled = original.map { PagePoint(it.x * scale, it.y * scale, it.pressure) }
        val tinyScale = 1e-5f
        val tiny = original.map { PagePoint(it.x * tinyScale, it.y * tinyScale, it.pressure) }

        val fullResult = QuickShapeRecognizer.recognize(original, minimumDiagonal = 18f)
        val scaledDefault = QuickShapeRecognizer.recognize(scaled)
        val scaledResult = QuickShapeRecognizer.recognize(scaled, minimumDiagonal = 18f * scale)
        val tinyResult = QuickShapeRecognizer.recognize(tiny, minimumDiagonal = 18f * tinyScale)

        assertNotNull(fullResult)
        assertNull(scaledDefault)
        assertNotNull(scaledResult)
        assertNotNull(tinyResult)
        assertEquals(fullResult!!.kind, scaledResult!!.kind)
        assertEquals(fullResult.kind, tinyResult!!.kind)
        assertEquals(fullResult.score, scaledResult.score, 0.025f)
        assertEquals(fullResult.score, tinyResult.score, 0.025f)
        assertNull(QuickShapeRecognizer.recognize(original, minimumDiagonal = 500f))
    }

    @Test
    fun `rejects non-finite coordinates and makes canonical pressure uniform`() {
        assertNull(
            QuickShapeRecognizer.recognize(
                listOf(
                    PagePoint(0f, 0f),
                    PagePoint(20f, 20f),
                    PagePoint(Float.NaN, 30f),
                    PagePoint(40f, 40f),
                    PagePoint(50f, 50f),
                    PagePoint(60f, 60f),
                ),
            ),
        )

        val line = sampleOpenLine(PagePoint(20f, 20f), PagePoint(300f, 120f), 40, 0.5f)
            .mapIndexed { index, point -> point.copy(pressure = if (index % 2 == 0) 0.2f else 0.8f) }
        val result = QuickShapeRecognizer.recognize(line)
        assertNotNull(result)
        assertTrue(result!!.points.all { abs(it.pressure - 0.5f) < 0.02f })
    }

    private fun sampleOpenLine(
        start: PagePoint,
        end: PagePoint,
        count: Int,
        noise: Float,
    ): List<PagePoint> {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val length = hypot(dx, dy)
        val nx = -dy / length
        val ny = dx / length
        return (0 until count).map { index ->
            val t = index.toFloat() / (count - 1)
            val offset = if (index == 0 || index == count - 1) 0f else noise * sin(index * 1.73).toFloat()
            PagePoint(start.x + dx * t + nx * offset, start.y + dy * t + ny * offset)
        }
    }

    private fun sampleWobblyLine(
        start: PagePoint,
        end: PagePoint,
        count: Int,
        noise: Float,
    ): List<PagePoint> {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val length = hypot(dx, dy)
        val nx = -dy / length
        val ny = dx / length
        return (0 until count).map { index ->
            val t = index.toFloat() / (count - 1)
            val offset = if (index == 0 || index == count - 1) {
                0f
            } else {
                noise * (
                    0.65f * sin(t * 6f * PI.toFloat()) +
                        0.35f * sin(t * 14f * PI.toFloat() + 0.7f)
                    )
            }
            PagePoint(start.x + dx * t + nx * offset, start.y + dy * t + ny * offset)
        }
    }

    private fun sampleClosedPolygon(
        corners: List<PagePoint>,
        samplesPerEdge: Int,
        noise: Float,
    ): List<PagePoint> {
        val closed = corners + corners.first()
        return sampleOpenPolyline(closed, samplesPerEdge, noise)
    }

    private fun sampleRoundedPolygon(
        corners: List<PagePoint>,
        edgeSamples: Int,
        cornerSamples: Int,
        rounding: Float,
        noise: Float,
    ): List<PagePoint> = buildList {
        val before = corners.indices.map { index ->
            lerp(corners[index], corners[(index - 1 + corners.size) % corners.size], rounding)
        }
        val after = corners.indices.map { index ->
            lerp(corners[index], corners[(index + 1) % corners.size], rounding)
        }
        add(after[0])
        for (index in corners.indices) {
            val next = (index + 1) % corners.size
            val lineStart = after[index]
            val lineEnd = before[next]
            for (sample in 1..edgeSamples) {
                add(lerp(lineStart, lineEnd, sample.toFloat() / edgeSamples))
            }
            for (sample in 1..cornerSamples) {
                val t = sample.toFloat() / cornerSamples
                val omt = 1f - t
                val control = corners[next]
                val x = omt * omt * before[next].x + 2f * omt * t * control.x + t * t * after[next].x
                val y = omt * omt * before[next].y + 2f * omt * t * control.y + t * t * after[next].y
                val jitter = if (sample == cornerSamples) 0f else noise * sin((index * cornerSamples + sample) * 1.37).toFloat()
                add(PagePoint(x + jitter, y - jitter * 0.6f))
            }
        }
        // Keep the closure exact even if the last rounded-corner sample had floating point drift.
        set(lastIndex, first())
    }

    private fun addCornerDwell(
        points: List<PagePoint>,
        corners: List<PagePoint>,
        repeats: Int,
        radius: Float,
    ): List<PagePoint> = buildList {
        points.forEachIndexed { index, point ->
            add(point)
            if (corners.any { distance(it, point) < 0.01f }) {
                repeat(repeats) { dwell ->
                    val angle = (index + dwell) * 1.73f
                    add(PagePoint(point.x + cos(angle) * radius, point.y + sin(angle) * radius))
                }
            }
        }
    }

    private fun sampleOpenPolyline(
        vertices: List<PagePoint>,
        samplesPerEdge: Int,
        noise: Float,
    ): List<PagePoint> = buildList {
        for (edge in 0 until vertices.lastIndex) {
            val start = vertices[edge]
            val end = vertices[edge + 1]
            val dx = end.x - start.x
            val dy = end.y - start.y
            val length = hypot(dx, dy).coerceAtLeast(1f)
            val nx = -dy / length
            val ny = dx / length
            repeat(samplesPerEdge) { sample ->
                val t = sample.toFloat() / samplesPerEdge
                val global = edge * samplesPerEdge + sample
                val offset = if (sample == 0) 0f else noise * sin(global * 1.19).toFloat()
                add(PagePoint(start.x + dx * t + nx * offset, start.y + dy * t + ny * offset))
            }
        }
        add(vertices.last())
    }

    private fun sampleEllipse(
        centerX: Float,
        centerY: Float,
        radiusX: Float,
        radiusY: Float,
        angleDegrees: Float,
        noise: Float,
        samples: Int = 112,
    ): List<PagePoint> {
        val rotation = angleDegrees / 180f * PI.toFloat()
        val cosRotation = cos(rotation)
        val sinRotation = sin(rotation)
        return (0..samples).map { index ->
            val angle = 2f * PI.toFloat() * index / samples
            val radialNoise = if (index == 0 || index == samples) 0f else noise * sin(index * 1.31).toFloat()
            val x = (radiusX + radialNoise) * cos(angle)
            val y = (radiusY + radialNoise) * sin(angle)
            PagePoint(
                centerX + x * cosRotation - y * sinRotation,
                centerY + x * sinRotation + y * cosRotation,
            )
        }
    }

    private fun sampleEllipseArc(
        centerX: Float,
        centerY: Float,
        radiusX: Float,
        radiusY: Float,
        startDegrees: Float,
        sweepDegrees: Float,
        noise: Float,
        samples: Int = 112,
    ): List<PagePoint> = (0..samples).map { index ->
        val progress = index.toFloat() / samples
        val angle = (startDegrees + sweepDegrees * progress) / 180f * PI.toFloat()
        val radialNoise = if (index == 0 || index == samples) 0f else noise * sin(index * 1.31).toFloat()
        PagePoint(
            centerX + (radiusX + radialNoise) * cos(angle),
            centerY + (radiusY + radialNoise) * sin(angle),
        )
    }

    private fun sampleWobblyEllipse(
        centerX: Float,
        centerY: Float,
        radiusX: Float,
        radiusY: Float,
        angleDegrees: Float,
        noise: Float,
        samples: Int,
    ): List<PagePoint> {
        val rotation = angleDegrees / 180f * PI.toFloat()
        val cosRotation = cos(rotation)
        val sinRotation = sin(rotation)
        return (0..samples).map { index ->
            val progress = index.toFloat() / samples
            val angle = 2f * PI.toFloat() * progress
            val radialNoise = if (index == 0 || index == samples) {
                0f
            } else {
                noise * (
                    0.65f * sin(progress * 6f * PI.toFloat()) +
                        0.35f * sin(progress * 14f * PI.toFloat() + 0.7f)
                    )
            }
            val x = (radiusX + radialNoise) * cos(angle)
            val y = (radiusY + radialNoise) * sin(angle)
            PagePoint(
                centerX + x * cosRotation - y * sinRotation,
                centerY + x * sinRotation + y * cosRotation,
            )
        }
    }

    private fun rotatedBox(
        centerX: Float,
        centerY: Float,
        width: Float,
        height: Float,
        angleDegrees: Float,
    ): List<PagePoint> {
        val angle = angleDegrees / 180f * PI.toFloat()
        val c = cos(angle)
        val s = sin(angle)
        return listOf(
            -width / 2f to -height / 2f,
            width / 2f to -height / 2f,
            width / 2f to height / 2f,
            -width / 2f to height / 2f,
        ).map { (x, y) -> PagePoint(centerX + x * c - y * s, centerY + x * s + y * c) }
    }

    private fun List<PagePoint>.rotate(angleDegrees: Float, center: PagePoint): List<PagePoint> {
        val angle = angleDegrees / 180f * PI.toFloat()
        val c = cos(angle)
        val s = sin(angle)
        return map { point ->
            PagePoint(
                center.x + point.x * c - point.y * s,
                center.y + point.x * s + point.y * c,
                point.pressure,
            )
        }
    }

    private fun rotateClosedStroke(points: List<PagePoint>, offset: Int): List<PagePoint> {
        val open = points.dropLast(1)
        if (open.isEmpty()) return points
        val normalizedOffset = offset.mod(open.size)
        val rotated = open.drop(normalizedOffset) + open.take(normalizedOffset)
        return rotated + rotated.first()
    }

    private fun lerp(start: PagePoint, end: PagePoint, t: Float): PagePoint = PagePoint(
        start.x + (end.x - start.x) * t,
        start.y + (end.y - start.y) * t,
        start.pressure + (end.pressure - start.pressure) * t,
    )

    private fun assertRightAngles(corners: List<PagePoint>) {
        corners.indices.forEach { index ->
            val previous = corners[(index - 1 + corners.size) % corners.size]
            val current = corners[index]
            val next = corners[(index + 1) % corners.size]
            val ax = previous.x - current.x
            val ay = previous.y - current.y
            val bx = next.x - current.x
            val by = next.y - current.y
            val cosine = (ax * bx + ay * by) / (hypot(ax, ay) * hypot(bx, by))
            assertTrue("corner $index is not square: cosine=$cosine", abs(cosine) < 0.001f)
        }
    }

    private fun assertPointNear(expected: PagePoint, actual: PagePoint, tolerance: Float) {
        assertTrue("expected=$expected actual=$actual", distance(expected, actual) <= tolerance)
    }

    private fun assertKind(label: String, stroke: List<PagePoint>, expected: QuickShapeKind) {
        val result = QuickShapeRecognizer.recognize(stroke)
        assertNotNull("$label was rejected", result)
        assertEquals(label, expected, result!!.kind)
    }

    private fun distance(a: PagePoint, b: PagePoint): Float = hypot(a.x - b.x, a.y - b.y)
}
