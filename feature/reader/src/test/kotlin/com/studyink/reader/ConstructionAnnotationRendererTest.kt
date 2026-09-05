package com.studyink.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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

    @Test fun `driving length radius and point line dimensions share the blue presentation`() {
        val constraints = listOf(
            GeometryConstraint("length", ConstraintType.LENGTH, listOf("AB"), value = 10.0),
            GeometryConstraint("radius", ConstraintType.RADIUS, listOf("circle"), value = 2.0),
            GeometryConstraint("distance", ConstraintType.DISTANCE_POINT_LINE, listOf("C", "AB"), value = 6.0),
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

    private fun scene() = ConstructionScene(
        points = listOf(GeometryPoint("A", 0.0, 0.0), GeometryPoint("B", 10.0, 0.0), GeometryPoint("C", 0.0, 6.0)),
        segments = listOf(GeometrySegment("AB", "A", "B")),
        circles = listOf(GeometryCircle("circle", "A", 2.0)),
    )

    private inline fun render(scene: ConstructionScene, selectedMeasurement: String? = null, selectedConstraint: String? = null,
                              check: (Bitmap, List<ConstructionAnnotationHit>) -> Unit) {
        val bitmap = Bitmap.createBitmap(960, 960, Bitmap.Config.ARGB_8888)
        try {
            val hits = ConstructionAnnotationRenderer(2f).draw(Canvas(bitmap), scene, emptySet(), selectedMeasurement,
                selectedConstraint, 40f, { 240f + it.toFloat() * 40f }, { 600f - it.toFloat() * 40f })
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
