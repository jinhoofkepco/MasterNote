package com.studyink.reader

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.FrameLayout
import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.GeometryConstraint
import com.studyink.construction.core.GeometryMeasurement
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.GeometrySegment
import com.studyink.construction.core.MeasurementType
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class ConstructionCanvasInteractionTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var view: ConstructionCanvasView

    @Before fun setup() {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        view = ConstructionCanvasView(activity)
        activity.setContentView(FrameLayout(activity).apply { addView(view) })
        view.scene = lineScene()
        view.layout(0, 0, 800, 1000)
        view.fitScene()
    }
    @After fun cleanup() { controller.pause().stop().destroy() }

    @Test fun `measurement text drag emits world offsets without moving mathematical points`() {
        val measurement = GeometryMeasurement("measurement", MeasurementType.DISTANCE, listOf("A", "B"), 1.2, -.1)
        view.scene = lineScene().copy(measurements = listOf(measurement))
        view.fitScene(); render()
        val original = view.scene
        val events = mutableListOf<Triple<ConstructionDragPhase, Double, Double>>()
        view.onMeasurementDrag = { id, x, y, phase -> assertEquals(measurement.id, id); events += Triple(phase, x, y) }
        val label = view.measurementScreenPosition(measurement.id)!!
        val a = view.pointScreenPosition("A")!!; val b = view.pointScreenPosition("B")!!
        val scale = (b.x - a.x) / 10.0
        touch(MotionEvent.ACTION_DOWN, label.x, label.y)
        touch(MotionEvent.ACTION_MOVE, label.x + 70f, label.y - 35f)
        touch(MotionEvent.ACTION_UP, label.x + 70f, label.y - 35f)
        assertEquals(listOf(ConstructionDragPhase.START, ConstructionDragPhase.MOVE, ConstructionDragPhase.END), events.map { it.first })
        assertEquals(measurement.offsetX + 70 / scale, events.last().second, 1e-5)
        assertEquals(measurement.offsetY + 35 / scale, events.last().third, 1e-5)
        assertEquals(original, view.scene)
    }

    @Test fun `cancelled annotation gesture reports its original offsets`() {
        val measurement = GeometryMeasurement("measurement", MeasurementType.DISTANCE, listOf("A", "B"), .6, .2)
        view.scene = lineScene().copy(measurements = listOf(measurement)); view.fitScene(); render()
        val events = mutableListOf<Triple<ConstructionDragPhase, Double, Double>>()
        view.onMeasurementDrag = { _, x, y, phase -> events += Triple(phase, x, y) }
        val label = view.measurementScreenPosition(measurement.id)!!
        touch(MotionEvent.ACTION_DOWN, label.x, label.y)
        touch(MotionEvent.ACTION_MOVE, label.x + 50f, label.y)
        touch(MotionEvent.ACTION_CANCEL, label.x + 50f, label.y)
        assertEquals(ConstructionDragPhase.CANCEL, events.last().first)
        assertEquals(.6, events.last().second, 0.0); assertEquals(.2, events.last().third, 0.0)
    }

    @Test fun `point tool carries exact line snap incidence through actual touch event`() {
        view.tool = ConstructionTool.POINT
        var received: ConstructionAnchor? = null
        view.onPoint = { received = it }
        val a = view.pointScreenPosition("A")!!; val b = view.pointScreenPosition("B")!!
        val x = a.x + (b.x - a.x) * .4f
        touch(MotionEvent.ACTION_DOWN, x, a.y + 4f)
        touch(MotionEvent.ACTION_UP, x, a.y + 4f)
        assertEquals(listOf("AB"), received!!.lineIds)
        assertEquals(4.0, received!!.x, 1e-5)
        assertEquals(0.0, received!!.y, 1e-12)
    }

    @Test fun `tool hint updates at each endpoint without modifying viewport`() {
        val hints = mutableListOf<String>()
        val a = view.pointScreenPosition("A")!!; val b = view.pointScreenPosition("B")!!
        view.onToolHintChanged = { hints += it }
        view.tool = ConstructionTool.SEGMENT
        touch(MotionEvent.ACTION_DOWN, a.x, a.y); touch(MotionEvent.ACTION_UP, a.x, a.y)
        assertTrue(hints.last().contains("끝점"))
        var segment: Pair<ConstructionAnchor, ConstructionAnchor>? = null
        view.onSegment = { start, end -> segment = start to end }
        touch(MotionEvent.ACTION_DOWN, b.x, b.y); touch(MotionEvent.ACTION_UP, b.x, b.y)
        assertEquals("A", segment!!.first.pointId); assertEquals("B", segment!!.second.pointId)
        assertTrue(hints.last().contains("시작점"))
        view.selectedIds = setOf("AB")
        assertEquals(a, view.pointScreenPosition("A")); assertEquals(b, view.pointScreenPosition("B"))
    }

    @Test fun `driving dimension label opens its condition instead of dragging geometry`() {
        view.scene = lineScene().copy(constraints = listOf(GeometryConstraint("length", ConstraintType.LENGTH, listOf("AB"), value = 10.0)))
        view.fitScene(); render()
        val a = view.pointScreenPosition("A")!!; val b = view.pointScreenPosition("B")!!
        val scale = (b.x - a.x) / 10f
        var selected: String? = null
        view.onConstraintSelected = { selected = it }
        val x = (a.x + b.x) / 2; val y = a.y - .9f * scale
        touch(MotionEvent.ACTION_DOWN, x, y); touch(MotionEvent.ACTION_UP, x, y)
        assertEquals("length", selected)
        assertEquals(lineScene().points, view.scene.points)
    }

    @Test fun `circle radius snapping explicitly distinguishes coordinate fit from a condition`() {
        val hints = mutableListOf<String>()
        view.onToolHintChanged = { hints += it }
        view.tool = ConstructionTool.CIRCLE
        val a = view.pointScreenPosition("A")!!; val b = view.pointScreenPosition("B")!!
        touch(MotionEvent.ACTION_DOWN, a.x, a.y); touch(MotionEvent.ACTION_UP, a.x, a.y)
        var center: ConstructionAnchor? = null
        var radius: Double? = null
        view.onCircle = { anchor, value -> center = anchor; radius = value }
        touch(MotionEvent.ACTION_DOWN, b.x, b.y)
        assertTrue(hints.last().contains("반지름 위치 맞춤"))
        assertTrue(hints.last().contains("조건 없음"))
        touch(MotionEvent.ACTION_UP, b.x, b.y)
        assertEquals("A", center!!.pointId)
        assertEquals(10.0, radius!!, 1e-5)
    }

    private fun lineScene() = ConstructionScene(
        points = listOf(GeometryPoint("A", 0.0, 0.0, "A"), GeometryPoint("B", 10.0, 0.0, "B")),
        segments = listOf(GeometrySegment("AB", "A", "B")),
    )
    private fun render() {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try { view.draw(Canvas(bitmap)) } finally { bitmap.recycle() }
    }
    private fun touch(action: Int, x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(time, time, action, x, y, 0)
        try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
    }
}
