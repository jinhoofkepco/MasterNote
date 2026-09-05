package com.studyink.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.GeometryCircle
import com.studyink.construction.core.GeometryConstraint
import com.studyink.construction.core.GeometryMeasurement
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.GeometrySegment
import com.studyink.construction.core.MeasurementType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConstructionAnnotationRendererTest {
    @Test fun `all measured values use quiet blue text instead of geometry ink`() {
        for (type in MeasurementType.entries) {
            val entities = when (type) {
                MeasurementType.DISTANCE -> listOf("A", "B")
                MeasurementType.RADIUS -> listOf("circle")
                MeasurementType.ANGLE -> listOf("B", "A", "C")
                MeasurementType.AREA -> listOf("A", "B", "C")
            }
            val measurement = GeometryMeasurement("measure", type, entities)
            render(scene().copy(measurements = listOf(measurement))) { bitmap, hits ->
                assertTrue("$type has muted blue numeric text", bitmap.hasOpaqueColor(0xFF5F82AD.toInt()))
                if (type != MeasurementType.AREA) {
                    assertTrue("$type has light blue dimension guides", bitmap.hasOpaqueColor(0xFF96B4D8.toInt()))
                }
                assertEquals(listOf("measure"), hits.map { it.id })
                assertEquals(ConstructionAnnotationKind.MEASUREMENT, hits.single().kind)
            }
        }
    }

    @Test fun `driving length radius angle and point line dimensions share the blue presentation`() {
        val constraints = listOf(
            GeometryConstraint("length", ConstraintType.LENGTH, listOf("AB"), value = 10.0),
            GeometryConstraint("radius", ConstraintType.RADIUS, listOf("circle"), value = 2.0),
            GeometryConstraint("distance", ConstraintType.DISTANCE_POINT_LINE, listOf("C", "AB"), value = 6.0),
            GeometryConstraint("angle", ConstraintType.ANGLE, listOf("AB", "AC"), value = 90.0),
        )
        for (constraint in constraints) {
            render(scene().copy(constraints = listOf(constraint))) { bitmap, hits ->
                assertTrue(bitmap.hasOpaqueColor(0xFF5F82AD.toInt()))
                assertTrue(bitmap.hasOpaqueColor(0xFF96B4D8.toInt()))
                assertEquals(constraint.id, hits.single().id)
                assertEquals(ConstructionAnnotationKind.CONSTRAINT, hits.single().kind)
            }
        }
    }

    @Test fun `selected measurement stays visibly blue and retains its existing hit target`() {
        val scene = scene().copy(measurements = listOf(
            GeometryMeasurement("measure", MeasurementType.DISTANCE, listOf("A", "B")),
        ))
        var originalBounds: android.graphics.RectF? = null
        render(scene) { _, hits -> originalBounds = hits.single().bounds }
        render(scene, selectedMeasurement = "measure") { bitmap, hits ->
            assertTrue(bitmap.hasOpaqueColor(0xFF4776C5.toInt()))
            assertEquals(originalBounds, hits.single().bounds)
        }
    }

    @Test fun `disabled relation badges preserve muted disabled styling`() {
        val constraint = GeometryConstraint("horizontal", ConstraintType.HORIZONTAL, listOf("AB"), enabled = false)
        render(scene().copy(constraints = listOf(constraint)), selectedConstraint = constraint.id) { bitmap, hits ->
            assertTrue(bitmap.hasOpaqueColor(0xFF9A9FA8.toInt()))
            assertEquals(constraint.id, hits.single().id)
        }
    }

    @Test fun `numeric conditions stay on the canvas while disabled and keep editable hits`() {
        val constraint = GeometryConstraint("angle", ConstraintType.ANGLE, listOf("AB", "AC"), value = 60.0, enabled = false)
        render(scene().copy(constraints = listOf(constraint))) { bitmap, hits ->
            assertTrue(bitmap.hasOpaqueColor(0xFF9A9FA8.toInt()))
            assertEquals("angle", hits.single().id)
            assertEquals(ConstructionAnnotationKind.CONSTRAINT, hits.single().kind)
        }
    }

    @Test fun `length and radius reference labels never cover the editable condition hit`() {
        val dimensions = listOf(
            GeometryConstraint("length", ConstraintType.LENGTH, listOf("AB"), value = 10.0) to
                GeometryMeasurement("reference", MeasurementType.DISTANCE, listOf("B", "A"), offsetX = 1.2, offsetY = 2.0),
            GeometryConstraint("radius", ConstraintType.RADIUS, listOf("circle"), value = 2.0) to
                GeometryMeasurement("reference", MeasurementType.RADIUS, listOf("circle"), offsetX = 1.2, offsetY = 2.0),
        )
        for ((constraint, measurement) in dimensions) {
            for (enabled in listOf(true, false)) {
                val scene = scene().copy(constraints = listOf(constraint.copy(enabled = enabled)), measurements = listOf(measurement))
                render(scene) { _, hits ->
                    assertEquals(constraint.id, hits.single().id)
                    assertEquals(ConstructionAnnotationKind.CONSTRAINT, hits.single().kind)
                    val original = ConstructionMeasurementGeometry.layout(scene, measurement)!!
                    assertEquals(240f + original.label.x.toFloat() * 40f, hits.single().bounds.centerX(), .001f)
                    assertEquals(600f - original.label.y.toFloat() * 40f, hits.single().bounds.centerY(), .001f)
                }
                assertEquals("Saved reference must not be deleted", listOf(measurement), scene.measurements)
            }
        }
    }

    @Test fun `selecting a numeric condition does not add a second badge over its dimension`() {
        val constraint = GeometryConstraint("length", ConstraintType.LENGTH, listOf("AB"), value = 10.0)
        render(scene().copy(constraints = listOf(constraint)), selectedConstraint = "length") { bitmap, hits ->
            assertTrue(bitmap.hasOpaqueColor(0xFF4776C5.toInt()))
            assertEquals("length", hits.single().id)
        }
    }

    @Test fun `driving captions have distinct visual and touch targets after zooming out`() {
        val scene = scene().copy(constraints = listOf(
            GeometryConstraint("length", ConstraintType.LENGTH, listOf("AB"), value = 10.0),
            GeometryConstraint("angle", ConstraintType.ANGLE, listOf("AB", "AC"), value = 90.0),
            GeometryConstraint("radius", ConstraintType.RADIUS, listOf("circle"), value = 2.0),
        ))
        for (scale in listOf(6f, 10f, 20f)) {
            var positions = emptyList<RectF>()
            render(scene, scale = scale) { _, hits ->
                assertEquals(setOf("length", "angle", "radius"), hits.map { it.id }.toSet())
                for ((index, hit) in hits.withIndex()) {
                    assertTrue(hit.bounds.contains(hit.visualBounds))
                    assertTrue(hit.bounds.width() > hit.visualBounds.width())
                    for (other in hits.drop(index + 1)) {
                        assertTrue("$scale: ${hit.id} and ${other.id} must be individually tappable", !RectF.intersects(hit.bounds, other.bounds))
                    }
                }
                positions = hits.map { RectF(it.bounds) }
            }
            render(scene, selectedConstraint = "angle", scale = scale) { _, hits ->
                assertEquals("Selection must not shuffle dimension positions", positions, hits.map { it.bounds })
            }
        }
    }

    @Test fun `enabled relation badges persist without selection while disabled badges stay contextual`() {
        val scene = scene().copy(constraints = listOf(
            GeometryConstraint("horizontal", ConstraintType.HORIZONTAL, listOf("AB")),
            GeometryConstraint("parallel", ConstraintType.PARALLEL, listOf("AB", "AC"), enabled = false),
        ))
        render(scene) { _, hits -> assertEquals(listOf("horizontal"), hits.map { it.id }) }
        render(scene, selectedConstraint = "parallel") { _, hits ->
            assertEquals(setOf("horizontal", "parallel"), hits.map { it.id }.toSet())
        }
    }

    @Test fun `offscreen relation anchors do not stack unrelated badges at the canvas edge`() {
        val scene = scene().copy(
            points = scene().points.map { it.copy(x = it.x + 1000.0) },
            constraints = listOf(GeometryConstraint("horizontal", ConstraintType.HORIZONTAL, listOf("AB"))),
        )
        render(scene) { _, hits -> assertTrue(hits.isEmpty()) }
    }

    private fun scene() = ConstructionScene(
        points = listOf(GeometryPoint("A", 0.0, 0.0), GeometryPoint("B", 10.0, 0.0), GeometryPoint("C", 0.0, 6.0)),
        segments = listOf(GeometrySegment("AB", "A", "B"), GeometrySegment("AC", "A", "C")),
        circles = listOf(GeometryCircle("circle", "A", 2.0)),
    )

    private inline fun render(scene: ConstructionScene, selectedMeasurement: String? = null, selectedConstraint: String? = null, scale: Float = 40f,
                              check: (Bitmap, List<ConstructionAnnotationHit>) -> Unit) {
        val bitmap = Bitmap.createBitmap(960, 960, Bitmap.Config.ARGB_8888)
        try {
            val hits = ConstructionAnnotationRenderer(2f).draw(Canvas(bitmap), scene, emptySet(), selectedMeasurement,
                selectedConstraint, scale, { 240f + it.toFloat() * scale }, { 600f - it.toFloat() * scale })
            check(bitmap, hits)
        } finally {
            bitmap.recycle()
        }
    }

    private fun Bitmap.hasOpaqueColor(expected: Int): Boolean {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels.any { Color.alpha(it) == 255 && it == expected }
    }
}
