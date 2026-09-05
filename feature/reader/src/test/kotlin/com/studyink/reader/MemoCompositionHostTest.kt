package com.studyink.reader

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
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
class MemoCompositionHostTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var root: FrameLayout
    private lateinit var host: MemoCompositionHost
    private lateinit var left: RecordingView
    private lateinit var right: RecordingView
    private val owners = mutableListOf<View>()
    private var time = 0L
    private var downTime = 0L

    @Before fun setUp() {
        controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        root = FrameLayout(activity)
        host = MemoCompositionHost(activity)
        left = RecordingView(activity)
        right = RecordingView(activity)
        host.addView(left, LinearLayout.LayoutParams(200, 200))
        host.addView(right, LinearLayout.LayoutParams(200, 200))
        host.onOwnerChanged = { owners += it }
        root.addView(host, FrameLayout.LayoutParams(400, 200))
        activity.setContentView(root)
        shadowOf(Looper.getMainLooper()).idle()
        root.measure(exact(400), exact(200))
        root.layout(0, 0, 400, 200)
        assertEquals(0, left.left)
        assertEquals(200, left.right)
        assertEquals(200, right.left)
        assertEquals(400, right.right)
        time = SystemClock.uptimeMillis()
    }

    @After fun tearDown() {
        root.removeAllViews()
        shadowOf(Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
    }

    @Test fun `ordinary gesture remains in its original pane until physical up`() {
        touch(MotionEvent.ACTION_DOWN, 250f, 30f)
        touch(MotionEvent.ACTION_MOVE, 280f, 60f)
        assertTrue(host.hasOwnedGesture)
        touch(MotionEvent.ACTION_UP, 290f, 70f)
        assertFalse(host.hasOwnedGesture)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), right.actions())
        assertTrue(left.events.isEmpty())
        assertEquals(listOf(right), owners)
        assertPosition(right.events.first(), 50f, 30f)
        assertPosition(right.events.last(), 90f, 70f)
        assertTrue(host.clipChildren)
        assertTrue(host.clipToPadding)
        assertFalse(host.isMotionEventSplittingEnabled)
    }

    @Test fun `crossing ends at boundary and reentry cannot resume or switch panes`() {
        touch(MotionEvent.ACTION_DOWN, 50f, 30f)
        touch(MotionEvent.ACTION_MOVE, 350f, 90f)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), left.actions())
        assertPosition(left.events.last(), 200f, 60f)
        assertEquals(left.events.first().downTime, left.events.last().downTime)
        assertTrue("Ownership remains latched after synthetic UP", host.hasOwnedGesture)
        touch(MotionEvent.ACTION_MOVE, 80f, 50f)
        touch(MotionEvent.ACTION_MOVE, 330f, 80f)
        assertEquals(2, left.events.size)
        assertTrue(right.events.isEmpty())
        assertEquals(listOf(left), owners)
        touch(MotionEvent.ACTION_UP, 330f, 80f)
        assertFalse(host.hasOwnedGesture)
        assertEquals(2, left.events.size)
        touch(MotionEvent.ACTION_DOWN, 300f, 80f)
        touch(MotionEvent.ACTION_UP, 310f, 90f)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), right.actions())
        assertPosition(right.events.first(), 100f, 80f)
        assertPosition(right.events.last(), 110f, 90f)
        assertEquals(listOf(left, right), owners)
    }

    @Test fun `diagonal exit clips the first edge instead of clamping both coordinates`() {
        touch(MotionEvent.ACTION_DOWN, 150f, 150f)
        touch(MotionEvent.ACTION_MOVE, 250f, 350f)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), left.actions())
        assertPosition(left.events.last(), 175f, 200f)
        assertTrue(right.events.isEmpty())
        touch(MotionEvent.ACTION_UP, 250f, 350f)
        assertEquals(2, left.events.size)
    }

    @Test fun `reverse crossing on physical up clips and transforms into right pane coordinates`() {
        touch(MotionEvent.ACTION_DOWN, 350f, 50f)
        touch(MotionEvent.ACTION_UP, 100f, 150f)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), right.actions())
        assertPosition(right.events.last(), 0f, 110f)
        assertTrue(left.events.isEmpty())
        assertFalse(host.hasOwnedGesture)
    }

    @Test fun `detaching cancels the active owner exactly once`() {
        assertTrue(host.isAttachedToWindow)
        touch(MotionEvent.ACTION_DOWN, 40f, 50f)
        touch(MotionEvent.ACTION_MOVE, 70f, 90f)
        root.removeView(host)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_CANCEL), left.actions())
        assertPosition(left.events.last(), 70f, 90f)
        assertFalse(host.hasOwnedGesture)
        assertTrue(right.events.isEmpty())
        host.cancelOwnedGesture()
        assertEquals(3, left.events.size)
    }

    @Test fun `second pointer crossing cancels once and consumes the remainder`() {
        touch(MotionEvent.ACTION_DOWN, 80f, 80f)
        time += 16L
        val properties = Array(2) { i -> MotionEvent.PointerProperties().apply {
            id = i; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        val coords = arrayOf(80f, 260f).map { x -> MotionEvent.PointerCoords().apply {
            this.x = x; y = 80f; pressure = 1f; size = 1f
        } }.toTypedArray()
        val event = MotionEvent.obtain(
            downTime, time,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try { assertTrue(host.dispatchTouchEvent(event)) } finally { event.recycle() }
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), left.actions())
        assertTrue(host.hasOwnedGesture)
        touch(MotionEvent.ACTION_MOVE, 80f, 80f)
        touch(MotionEvent.ACTION_UP, 80f, 80f)
        assertEquals(2, left.events.size)
        assertTrue(right.events.isEmpty())
        assertFalse(host.hasOwnedGesture)
    }

    @Test
    @Config(qualifiers = "mdpi")
    fun `narrow portrait composition clips vertically and cancels before rotating`() {
        val divider = View(controller.get())
        host.addView(divider, 1, LinearLayout.LayoutParams(1, -1))
        host.layoutParams = FrameLayout.LayoutParams(-1, -1)
        root.measure(exact(400), exact(800))
        root.layout(0, 0, 400, 800)
        assertEquals(LinearLayout.VERTICAL, host.orientation)
        assertEquals(400, left.width)
        assertEquals(400, right.width)
        assertEquals(1, divider.height)
        assertEquals(left.bottom, divider.top)
        assertEquals(divider.bottom, right.top)

        touch(MotionEvent.ACTION_DOWN, 100f, 50f)
        touch(MotionEvent.ACTION_MOVE, 100f, right.top + 10f)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), left.actions())
        assertPosition(left.events.last(), 100f, left.height.toFloat())
        assertTrue(right.events.isEmpty())
        touch(MotionEvent.ACTION_UP, 100f, right.top + 10f)

        touch(MotionEvent.ACTION_DOWN, 100f, right.top + 20f)
        root.measure(exact(800), exact(400))
        root.layout(0, 0, 800, 400)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), right.actions())
        assertFalse(host.hasOwnedGesture)
        assertEquals(LinearLayout.HORIZONTAL, host.orientation)
        assertEquals(1, divider.width)
        assertEquals(400, divider.height)
        touch(MotionEvent.ACTION_UP, 100f, 100f)
        assertEquals(2, right.events.size)

        left.visibility = View.GONE
        divider.visibility = View.GONE
        root.measure(exact(400), exact(800))
        root.layout(0, 0, 400, 800)
        assertEquals(400, right.width)
        assertEquals(800, right.height)
    }

    private fun touch(action: Int, x: Float, y: Float) {
        time += 16L
        if (action == MotionEvent.ACTION_DOWN) downTime = time
        val event = MotionEvent.obtain(downTime, time, action, x, y, 0)
        try {
            assertTrue(host.dispatchTouchEvent(event))
            assertEquals("Dispatch must not mutate the source event", x, event.x, .001f)
            assertEquals(y, event.y, .001f)
        } finally { event.recycle() }
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private fun assertPosition(event: RecordedEvent, x: Float, y: Float) {
        assertEquals(x, event.x, .001f)
        assertEquals(y, event.y, .001f)
    }
    private data class RecordedEvent(val action: Int, val x: Float, val y: Float, val downTime: Long)
    private class RecordingView(context: Context) : View(context) {
        val events = mutableListOf<RecordedEvent>()
        fun actions() = events.map { it.action }
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            events += RecordedEvent(event.actionMasked, event.x, event.y, event.downTime)
            return true
        }
    }
}
