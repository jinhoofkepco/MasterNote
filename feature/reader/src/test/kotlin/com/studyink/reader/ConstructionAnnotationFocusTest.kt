package com.studyink.reader

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.GeometryCircle
import com.studyink.construction.core.GeometryConstraint
import com.studyink.construction.core.GeometryMeasurement
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.GeometrySegment
import com.studyink.construction.core.MeasurementType
import java.io.File
import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConstructionAnnotationFocusTest {
    @Test fun `driving reference and disabled dimensions explicitly label their different meanings`() {
        val scene = scene().copy(
            measurements = listOf(GeometryMeasurement("reference", MeasurementType.DISTANCE, listOf("A", "B"))),
            constraints = listOf(
                GeometryConstraint("driving", ConstraintType.DISTANCE_POINTS, listOf("C", "D"), value = 6.0),
                GeometryConstraint("paused", ConstraintType.RADIUS, listOf("circle"), value = 2.0, enabled = false),
            ),
        )
        render(scene) { bitmap, hits ->
            assertTrue(hits.single { it.id == "reference" }.label.startsWith("측정 · ("))
            assertTrue(hits.single { it.id == "driving" }.label.startsWith("고정 · "))
            assertTrue(hits.single { it.id == "paused" }.label.startsWith("꺼짐 · "))
            assertTrue("Driving labels have a filled pale blue surface", hasColor(bitmap, 0xFFEAF0F7.toInt()))
            assertTrue("Measurements retain an open paper surface", hasColor(bitmap, 0xF7FFFEF9.toInt()))
        }
    }

    @Test fun `paired relation appears at each line with matching text and explicit local attachment`() {
        val scene = scene().copy(constraints = listOf(GeometryConstraint("equal", ConstraintType.EQUAL_LENGTH, listOf("AB", "CD"))))
        render(scene) { _, hits ->
            assertEquals(2, hits.size)
            assertEquals(setOf("AB", "CD"), hits.map { it.targetEntityId }.toSet())
            assertEquals(1, hits.map { it.label }.distinct().size)
            for (hit in hits) {
                val line = scene.segment(hit.targetEntityId!!)!!
                val a = scene.point(line.startPointId)!!; val b = scene.point(line.endPointId)!!
                val anchor = hit.targetAnchor!!
                assertEquals(160f + ((a.x + b.x) / 2 * 30).toFloat(), anchor.x, .001f)
                assertEquals(660f - ((a.y + b.y) / 2 * 30).toFloat(), anchor.y, .001f)
                assertTrue(hypot(hit.visualBounds.centerX() - anchor.x, hit.visualBounds.centerY() - anchor.y) < 100f)
            }
        }
    }

    @Test fun `badges move with their own entities while zoom preserves a bounded local offset`() {
        val original = scene().copy(constraints = listOf(GeometryConstraint("equal", ConstraintType.EQUAL_LENGTH, listOf("AB", "CD"))))
        val moved = original.copy(points = original.points.map { it.copy(x = it.x + 2.0, y = it.y + 1.0) })
        var first = emptyList<ConstructionAnnotationHit>()
        render(original) { _, hits -> first = hits }
        render(moved) { _, hits ->
            first.zip(hits).forEach { (a, b) ->
                assertEquals(60f, b.visualBounds.centerX() - a.visualBounds.centerX(), .001f)
                assertEquals(-30f, b.visualBounds.centerY() - a.visualBounds.centerY(), .001f)
            }
        }
        render(original, scale = 12f) { _, hits ->
            first.zip(hits).forEach { (a, b) ->
                val oldAnchor = a.targetAnchor!!; val newAnchor = b.targetAnchor!!
                assertEquals(a.visualBounds.centerX() - oldAnchor.x, b.visualBounds.centerX() - newAnchor.x, .001f)
                assertEquals(a.visualBounds.centerY() - oldAnchor.y, b.visualBounds.centerY() - newAnchor.y, .001f)
            }
        }
    }

    @Test fun `selecting a paused neighboring relation never moves existing badges`() {
        val scene = scene().copy(constraints = listOf(
            GeometryConstraint("paused", ConstraintType.HORIZONTAL, listOf("AB"), enabled = false),
            GeometryConstraint("equal", ConstraintType.EQUAL_LENGTH, listOf("AB", "CD")),
        ), measurements = listOf(GeometryMeasurement("angle", MeasurementType.ANGLE, listOf("B", "A", "C"))))
        var positions = emptyMap<String?, RectF>()
        render(scene) { _, hits -> positions = hits.filter { it.id == "equal" }.associate { it.targetEntityId to RectF(it.visualBounds) } }
        render(scene, selectedConstraint = "paused") { _, hits ->
            assertEquals(positions, hits.filter { it.id == "equal" }.associate { it.targetEntityId to it.visualBounds })
            assertTrue(hits.single { it.id == "paused" }.label.startsWith("꺼짐"))
        }
    }

    @Test fun `simple paired length badges yield locally to an angle measurement instead of covering it`() {
        val scene = scene().copy(
            constraints = listOf(GeometryConstraint("equal", ConstraintType.EQUAL_LENGTH, listOf("AB", "CD"))),
            measurements = listOf(GeometryMeasurement("angle", MeasurementType.ANGLE, listOf("B", "A", "C"))),
        )
        for (scale in listOf(19.25f, 30f)) render(scene, scale = scale) { _, hits ->
            val measurement = hits.single { it.id == "angle" }
            val badges = hits.filter { it.id == "equal" }
            assertEquals(2, badges.size)
            for (badge in badges) {
                assertFalse("A local relation must not cover the visible angle caption", RectF.intersects(measurement.visualBounds, badge.visualBounds))
                val anchor = badge.targetAnchor!!
                assertTrue("Collision avoidance must remain near its own line", hypot(badge.visualBounds.centerX() - anchor.x,
                    badge.visualBounds.centerY() - anchor.y) < 100f)
            }
        }
    }

    @Test fun `dense badge stacks stay near their own anchor instead of searching across the screen`() {
        val scene = scene().copy(constraints = (0 until 25).map { GeometryConstraint("horizontal$it", ConstraintType.HORIZONTAL, listOf("AB")) })
        render(scene) { _, hits ->
            assertEquals(25, hits.size)
            assertTrue(hits.all {
                val anchor = it.targetAnchor!!
                hypot(it.visualBounds.centerX() - anchor.x, it.visualBounds.centerY() - anchor.y) <= 120f
            })
        }
    }

    @Test fun `point distance driving label takes over its measurement without creating a segment`() {
        val measure = GeometryMeasurement("reference", MeasurementType.DISTANCE, listOf("B", "C"), 1.0, -2.0)
        val rule = GeometryConstraint("fixed", ConstraintType.DISTANCE_POINTS, listOf("C", "B"), value = 7.0)
        val scene = scene().copy(measurements = listOf(measure), constraints = listOf(rule))
        val layout = ConstructionMeasurementGeometry.constraintLayout(scene, rule)!!
        assertEquals(ConstructionMeasurementGeometry.layout(scene, measure)!!.label, layout.label)
        render(scene) { _, hits -> assertEquals(listOf("fixed"), hits.map { it.id }) }
        assertEquals(2, scene.segments.size)
        assertEquals(listOf(measure), scene.measurements)
    }

    @Test fun `virtual distance equality displays both spans under one relation identity`() {
        val relation = GeometryConstraint("equal", ConstraintType.EQUAL_DISTANCE_POINTS, listOf("A", "D", "B", "C"))
        val scene = scene().copy(constraints = listOf(relation))
        render(scene) { _, hits ->
            assertEquals(listOf("equal", "equal"), hits.map { it.id })
            assertTrue(hits.all { it.label.contains("같은 거리 1") })
        }
        val targets = ConstructionMeasurementGeometry.annotationTargets(scene, "equal", null)
        assertEquals(setOf("A", "B", "C", "D"), targets.entityIds)
        assertEquals(setOf("A" to "D", "B" to "C"), targets.pointPairs.toSet())
    }

    @Test fun `condition badge and measurement selection highlight targets without changing editable selection`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val view = ConstructionCanvasView(controller.get())
            controller.get().setContentView(view)
            val original = scene().copy(
                constraints = listOf(GeometryConstraint("equal", ConstraintType.EQUAL_LENGTH, listOf("AB", "CD"))),
                measurements = listOf(GeometryMeasurement("angle", MeasurementType.ANGLE, listOf("B", "A", "C")),
                    GeometryMeasurement("radius", MeasurementType.RADIUS, listOf("circle"))),
            )
            view.scene = original; view.layout(0, 0, 420, 840); view.fitScene()
            view.selectedIds = setOf("O")
            draw(view)
            val badge = view.constraintScreenBounds("equal")!!
            tap(view, badge.centerX(), badge.centerY())
            assertEquals("equal", view.selectedConstraintId)
            assertEquals(setOf("AB", "CD", "A", "B", "C", "D"), view.annotationTargets().entityIds)
            assertEquals(setOf("O"), view.selectedIds)
            assertEquals(original, view.scene)
            savePreview(view, "build/outputs/annotation-focus-phone.png")
            view.selectedConstraintId = null; view.selectedMeasurementId = "angle"; view.referenceMeasurementId = "radius"
            val targets = view.annotationTargets()
            assertTrue(targets.entityIds.containsAll(setOf("AB", "A", "B", "C", "circle", "O")))
            assertTrue(targets.pointPairs.contains("A" to "C"))
            assertEquals(setOf("O"), view.selectedIds)
            assertEquals(original, view.scene)
            savePreview(view, "build/outputs/annotation-reference-focus-phone.png")
        } finally { controller.pause().stop().destroy() }
    }

    private fun scene() = ConstructionScene(
        points = listOf(GeometryPoint("A", 0.0, 0.0, "A"), GeometryPoint("B", 6.0, 0.0, "B"),
            GeometryPoint("C", 10.0, 5.0, "C"), GeometryPoint("D", 16.0, 5.0, "D"), GeometryPoint("O", 4.0, 10.0, "O")),
        segments = listOf(GeometrySegment("AB", "A", "B"), GeometrySegment("CD", "C", "D")),
        circles = listOf(GeometryCircle("circle", "O", 2.0)),
    )
    private inline fun render(scene: ConstructionScene, selectedConstraint: String? = null, scale: Float = 30f,
                              check: (Bitmap, List<ConstructionAnnotationHit>) -> Unit) {
        val bitmap = Bitmap.createBitmap(960, 960, Bitmap.Config.ARGB_8888)
        try {
            val hits = ConstructionAnnotationRenderer(1f).draw(Canvas(bitmap), scene, emptySet(), null, selectedConstraint,
                scale, { 160f + (it * scale).toFloat() }, { 660f - (it * scale).toFloat() })
            check(bitmap, hits)
        } finally { bitmap.recycle() }
    }
    private fun draw(view: ConstructionCanvasView) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try { view.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
    }
    private fun tap(view: ConstructionCanvasView, x: Float, y: Float) {
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(0, 16, MotionEvent.ACTION_UP, x, y, 0)
        try { assertTrue(view.dispatchTouchEvent(down)); assertTrue(view.dispatchTouchEvent(up)) }
        finally { down.recycle(); up.recycle() }
    }
    private fun savePreview(view: ConstructionCanvasView, path: String) {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap)); val output = File(path)
            check(output.parentFile!!.mkdirs() || output.parentFile!!.isDirectory)
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            println("Annotation focus native QA: ${output.absolutePath}")
        } finally { bitmap.recycle() }
    }
    private fun hasColor(bitmap: Bitmap, expected: Int): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.any { it == expected }
    }
}
