package com.studyink.reader

import android.app.Activity
import android.content.Context
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class SharedMemoCanvasHostTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var root: FrameLayout
    private lateinit var host: SharedMemoCanvasHost
    private lateinit var geometry: RecordingView
    private lateinit var ink: RecordingView
    private val owners = mutableListOf<View>()
    private var time = 0L
    private var downTime = 0L

    @Before fun setUp() {
        controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        root = FrameLayout(activity)
        host = SharedMemoCanvasHost(activity)
        geometry = RecordingView(activity)
        ink = RecordingView(activity)
        host.addView(geometry, FrameLayout.LayoutParams(-1, -1))
        host.addView(ink, FrameLayout.LayoutParams(-1, -1))
        host.geometryLayer = geometry
        host.inkLayer = ink
        host.onOwnerChanged = { owners += it }
        root.addView(host, FrameLayout.LayoutParams(-1, -1))
        activity.setContentView(root)
        shadowOf(Looper.getMainLooper()).idle()
        resize(400, 600)
        time = SystemClock.uptimeMillis()
    }

    @After fun tearDown() {
        root.removeAllViews()
        shadowOf(Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
    }

    @Test fun `layers occupy the same full canvas and mode never falls through rejected input`() {
        assertEquals(geometry.left, ink.left)
        assertEquals(geometry.width, ink.width)
        assertEquals(geometry.height, ink.height)
        ink.accepts = false
        touch(MotionEvent.ACTION_DOWN, pen(50f, 50f))
        touch(MotionEvent.ACTION_MOVE, pen(60f, 70f))
        touch(MotionEvent.ACTION_UP, pen(70f, 80f))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), ink.actions())
        assertTrue(geometry.events.isEmpty())
        assertEquals(listOf(ink), owners)
        host.geometryMode = true
        touch(MotionEvent.ACTION_DOWN, finger(80f, 90f))
        touch(MotionEvent.ACTION_UP, finger(80f, 90f))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), geometry.actions())
        assertEquals(listOf(ink, geometry), owners)
    }

    @Test fun `mode switch cancels once and consumes held pen through physical up`() {
        touch(MotionEvent.ACTION_DOWN, pen(50f, 50f))
        host.geometryMode = true
        assertFalse(host.hasOwnedGesture)
        touch(MotionEvent.ACTION_MOVE, pen(70f, 80f))
        touch(MotionEvent.ACTION_UP, pen(70f, 80f))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), ink.actions())
        assertTrue(geometry.events.isEmpty())
        touch(MotionEvent.ACTION_DOWN, pen(80f, 100f))
        touch(MotionEvent.ACTION_UP, pen(80f, 100f))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), geometry.actions())
    }

    @Test fun `ink fingers pan and never edit either layer`() {
        touch(MotionEvent.ACTION_DOWN, finger(200f, 300f))
        touch(MotionEvent.ACTION_MOVE, finger(200f, 200f))
        touch(MotionEvent.ACTION_UP, finger(200f, 200f))
        assertEquals(-100f, host.viewport.paperBounds.top, .001f)
        assertTrue(ink.events.isEmpty())
        assertTrue(geometry.events.isEmpty())
    }

    @Test fun `two fingers cancel construction then pinch and continue pan without phantom point`() {
        host.geometryMode = true
        touch(MotionEvent.ACTION_DOWN, finger(150f, 300f))
        touch(pointerDown(1), finger(150f, 300f), finger(250f, 300f, 1))
        val initialScale = host.viewport.pixelsPerCm
        touch(MotionEvent.ACTION_MOVE, finger(100f, 300f), finger(300f, 300f, 1))
        assertEquals(initialScale * 2f, host.viewport.pixelsPerCm, .001f)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), geometry.actions())
        touch(pointerUp(1), finger(100f, 300f), finger(300f, 300f, 1))
        val previousTop = host.viewport.paperBounds.top
        touch(MotionEvent.ACTION_MOVE, finger(100f, 250f))
        assertEquals(previousTop - 50f, host.viewport.paperBounds.top, .001f)
        touch(MotionEvent.ACTION_UP, finger(100f, 250f))
        assertEquals(2, geometry.events.size)
        assertTrue(ink.events.isEmpty())
        touch(MotionEvent.ACTION_DOWN, finger(100f, 250f))
        touch(MotionEvent.ACTION_UP, finger(100f, 250f))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), geometry.actions())
    }

    @Test fun `palm contact never interrupts stylus and remaining palm never navigates`() {
        touch(MotionEvent.ACTION_DOWN, pen(80f, 100f))
        touch(pointerDown(1), pen(80f, 100f), finger(300f, 400f, 1))
        touch(MotionEvent.ACTION_MOVE, pen(90f, 110f), finger(280f, 420f, 1))
        touch(pointerUp(0), pen(100f, 120f), finger(280f, 420f, 1))
        touch(MotionEvent.ACTION_MOVE, finger(200f, 200f, 1))
        touch(MotionEvent.ACTION_UP, finger(200f, 200f, 1))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), ink.actions())
        assertTrue(ink.events.all { it.pointerCount == 1 && it.tool == MotionEvent.TOOL_TYPE_STYLUS })
        assertEquals(0f, host.viewport.paperBounds.top, .001f)
        assertTrue(geometry.events.isEmpty())
    }

    @Test fun `durability gate blocks pinch toolbar and resize until resumed`() {
        var ready = false
        var beforeChanges = 0
        host.canChangeViewport = { ready }
        host.onBeforeViewportChange = { beforeChanges++ }
        val initialBounds = host.viewport.paperBounds
        touch(MotionEvent.ACTION_DOWN, finger(150f, 300f))
        touch(pointerDown(1), finger(150f, 300f), finger(250f, 300f, 1))
        touch(MotionEvent.ACTION_MOVE, finger(100f, 300f), finger(300f, 300f, 1))
        touch(MotionEvent.ACTION_UP, finger(100f, 300f))
        assertFalse(host.zoomBy(2f))
        assertFalse(host.resetViewport())
        resize(600, 400)
        assertEquals(initialBounds, host.viewport.paperBounds)
        assertEquals(0, beforeChanges)
        ready = true
        host.resumePendingResize()
        assertEquals(600f, host.viewport.paperBounds.width(), .001f)
        assertEquals(1, beforeChanges)
        assertTrue(host.zoomBy(2f))
        assertEquals(1200f, host.viewport.paperBounds.width(), .001f)
        assertTrue(host.resetViewport())
    }

    @Test fun `resize cancels current stroke without allowing a held gesture to restart`() {
        touch(MotionEvent.ACTION_DOWN, pen(80f, 100f))
        touch(MotionEvent.ACTION_MOVE, pen(90f, 110f))
        resize(600, 400)
        touch(MotionEvent.ACTION_MOVE, pen(100f, 120f))
        touch(MotionEvent.ACTION_UP, pen(100f, 120f))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL), ink.actions())
        assertTrue(geometry.events.isEmpty())
    }

    @Test fun `new memo reset waits for durable ink even without a resize event`() {
        host.zoomBy(3f)
        var ready = false
        host.canChangeViewport = { ready }
        assertFalse(host.resetViewport())
        assertEquals(1200f, host.viewport.paperBounds.width(), .001f)
        ready = true
        host.resumePendingResize()
        assertEquals(400f, host.viewport.paperBounds.width(), .001f)
        assertEquals(0f, host.viewport.paperBounds.top, .001f)
    }

    @Test fun `content fit requested before first measure is applied after first layout`() {
        val fresh = SharedMemoCanvasHost(controller.get())
        fresh.viewport.geometryWorldBounds = RectF(39f, 4f, 41f, 6f)
        assertFalse(fresh.fitContent())
        fresh.resumePendingResize()
        assertNull(fresh.viewport.activePageBounds())
        fresh.measure(exact(400), exact(600))
        fresh.layout(0, 0, 400, 600)
        val center = fresh.viewport.worldToView(40.0, 5.0)
        assertEquals(200f, center.x, .001f)
        assertEquals(300f, center.y, .001f)
    }

    @Test fun `reset before first layout supersedes an earlier deferred fit`() {
        val fresh = SharedMemoCanvasHost(controller.get())
        fresh.viewport.geometryWorldBounds = RectF(39f, 4f, 41f, 6f)
        assertFalse(fresh.fitContent())
        assertFalse(fresh.resetViewport())
        fresh.resumePendingResize()
        assertNull(fresh.viewport.activePageBounds())
        fresh.measure(exact(400), exact(600))
        fresh.layout(0, 0, 400, 600)
        assertEquals(400f, fresh.viewport.paperBounds.width(), .001f)
        assertEquals(0f, fresh.viewport.paperBounds.top, .001f)
        assertEquals(0f, fresh.viewport.paperBounds.left, .001f)
    }

    @Test fun `first layout fit also waits for the durable ink gate`() {
        val fresh = SharedMemoCanvasHost(controller.get())
        var ready = false
        fresh.canChangeViewport = { ready }
        fresh.viewport.geometryWorldBounds = RectF(39f, 4f, 41f, 6f)
        assertFalse(fresh.fitContent())
        fresh.measure(exact(400), exact(600))
        fresh.layout(0, 0, 400, 600)
        fresh.resumePendingResize()
        assertNull(fresh.viewport.activePageBounds())
        ready = true
        fresh.resumePendingResize()
        val center = fresh.viewport.worldToView(40.0, 5.0)
        assertEquals(200f, center.x, .001f)
        assertEquals(300f, center.y, .001f)
    }

    @Test fun `paper and screen edges finish at boundary then swallow reentry`() {
        touch(MotionEvent.ACTION_DOWN, pen(350f, 100f))
        touch(MotionEvent.ACTION_MOVE, pen(450f, 200f))
        touch(MotionEvent.ACTION_MOVE, pen(300f, 200f))
        touch(MotionEvent.ACTION_UP, pen(300f, 200f))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), ink.actions())
        assertEquals(400f, ink.events.last().x, .001f)
        assertEquals(150f, ink.events.last().y, .001f)
        host.zoomBy(.01f)
        val margin = host.viewport.paperBounds.left / 2f
        touch(MotionEvent.ACTION_DOWN, pen(margin, 100f))
        touch(MotionEvent.ACTION_UP, pen(margin, 100f))
        assertEquals(2, ink.events.size)
    }

    @Test fun `hover is delivered only to chosen layer and detach cancels once`() {
        hover(MotionEvent.ACTION_HOVER_ENTER, pen(50f, 80f))
        assertEquals(listOf(MotionEvent.ACTION_HOVER_ENTER), ink.hovers)
        assertTrue(geometry.hovers.isEmpty())
        host.geometryMode = true
        hover(MotionEvent.ACTION_HOVER_MOVE, pen(50f, 80f))
        assertEquals(listOf(MotionEvent.ACTION_HOVER_MOVE), geometry.hovers)
        touch(MotionEvent.ACTION_DOWN, pen(50f, 80f))
        root.removeView(host)
        host.cancelOwnedGesture()
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), geometry.actions())
        assertFalse(host.hasOwnedGesture)
    }

    @Test fun `legacy geometry is editable beyond paper but ink remains bounded there`() {
        host.viewport.geometryWorldBounds = RectF(39f, 4f, 41f, 6f)
        var ready = false
        host.canChangeViewport = { ready }
        assertFalse(host.fitContent())
        ready = true
        host.resumePendingResize()
        val legacy = host.viewport.worldToView(40.0, 5.0)
        touch(MotionEvent.ACTION_DOWN, pen(legacy.x, legacy.y))
        touch(MotionEvent.ACTION_UP, pen(legacy.x, legacy.y))
        assertTrue(ink.events.isEmpty())
        host.geometryMode = true
        touch(MotionEvent.ACTION_DOWN, pen(legacy.x, legacy.y))
        touch(MotionEvent.ACTION_UP, pen(legacy.x, legacy.y))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), geometry.actions())
        assertTrue(ink.events.isEmpty())
    }

    private fun resize(w: Int, h: Int) {
        root.measure(exact(w), exact(h))
        root.layout(0, 0, w, h)
    }

    private fun touch(action: Int, vararg pointers: Pointer) = event(action, pointers) { host.dispatchTouchEvent(it) }
    private fun hover(action: Int, pointer: Pointer) = event(action, arrayOf(pointer)) { host.dispatchGenericMotionEvent(it) }
    private fun event(action: Int, pointers: Array<out Pointer>, dispatch: (MotionEvent) -> Boolean) {
        time += 16L
        if (action == MotionEvent.ACTION_DOWN) downTime = time
        val props = pointers.map { p -> MotionEvent.PointerProperties().apply { id = p.id; toolType = p.tool } }.toTypedArray()
        val coords = pointers.map { p -> MotionEvent.PointerCoords().apply { x = p.x; y = p.y; pressure = .6f; size = 1f } }.toTypedArray()
        val event = MotionEvent.obtain(downTime, time, action, pointers.size, props, coords, 0, 0,
            1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try {
            assertTrue(dispatch(event))
            assertEquals(action, event.action)
            assertEquals(pointers.first().x, event.x, .001f)
        } finally { event.recycle() }
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private fun pen(x: Float, y: Float, id: Int = 0) = Pointer(id, x, y, MotionEvent.TOOL_TYPE_STYLUS)
    private fun finger(x: Float, y: Float, id: Int = 0) = Pointer(id, x, y, MotionEvent.TOOL_TYPE_FINGER)
    private fun pointerDown(index: Int) = MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    private fun pointerUp(index: Int) = MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    private data class Pointer(val id: Int, val x: Float, val y: Float, val tool: Int)
    private data class RecordedEvent(val action: Int, val x: Float, val y: Float, val pointerCount: Int, val tool: Int)
    private class RecordingView(context: Context) : View(context) {
        var accepts = true
        val events = mutableListOf<RecordedEvent>()
        val hovers = mutableListOf<Int>()
        fun actions() = events.map { it.action }
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            events += RecordedEvent(event.actionMasked, event.x, event.y, event.pointerCount, event.getToolType(0))
            return accepts
        }
        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean { hovers += event.actionMasked; return true }
    }
}
