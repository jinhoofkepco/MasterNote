package com.studyink.app

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.studyink.reader.DryInkView
import com.studyink.reader.ReaderActivity
import com.studyink.core.model.StrokeTool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderInteractionTest {
    private lateinit var scenario: ActivityScenario<ReaderActivity>
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Before
    fun launchReader() {
        scenario = ActivityScenario.launch(ReaderActivity::class.java)
        waitForDocument()
    }

    @After
    fun closeReader() {
        scenario.close()
    }

    @Test
    fun fingerDoesNotWriteButStylusDoes() {
        val before = revision()
        dispatchStroke(InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER, 320f, 760f, 430f, 790f)
        SystemClock.sleep(500)
        assertEquals("손가락 입력은 주석을 추가하면 안 됩니다", before, revision())

        dispatchStroke(InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, 360f, 820f, 520f, 870f)
        assertTrue("스타일러스 입력은 저장된 필기를 만들어야 합니다", waitForRevisionAfter(before))
    }

    @Test
    fun stylusSideButtonOpensIconMenuAtPointer() {
        val eventTime = SystemClock.uptimeMillis()
        val event = motionEvent(
            action = MotionEvent.ACTION_BUTTON_PRESS,
            source = InputDevice.SOURCE_STYLUS,
            toolType = MotionEvent.TOOL_TYPE_STYLUS,
            x = 520f,
            y = 920f,
            buttonState = MotionEvent.BUTTON_STYLUS_PRIMARY,
            eventTime = eventTime,
        )

        scenario.onActivity { activity ->
            assertTrue(activity.dispatchGenericMotionEvent(event))
        }
        event.recycle()

        assertTrue(device.wait(Until.hasObject(By.desc("펜")), 3_000))
        assertTrue(device.hasObject(By.desc("형광펜")))
        assertTrue(device.hasObject(By.desc("지우개")))
        assertTrue(device.hasObject(By.desc("다음 페이지")))
        assertTrue(device.hasObject(By.desc("되돌리기")))

        device.findObject(By.desc("다음 페이지")).click()
        repeat(30) {
            var page = 0
            scenario.onActivity { activity -> page = activity.findDryInkView().activePage }
            if (page == 1) {
                assertTrue("페이지 이동 후에도 메뉴가 유지되어야 합니다", device.hasObject(By.desc("펜")))
                dispatchStroke(
                    InputDevice.SOURCE_TOUCHSCREEN,
                    MotionEvent.TOOL_TYPE_FINGER,
                    300f,
                    720f,
                    360f,
                    740f,
                )
                SystemClock.sleep(250)
                assertTrue("손가락 터치로 메뉴가 닫히면 안 됩니다", device.hasObject(By.desc("펜")))

                dispatchStroke(
                    InputDevice.SOURCE_STYLUS,
                    MotionEvent.TOOL_TYPE_STYLUS,
                    330f,
                    760f,
                    390f,
                    780f,
                )
                assertTrue(
                    "S Pen이 페이지에 닿으면 메뉴가 닫혀야 합니다",
                    device.wait(Until.gone(By.desc("펜")), 3_000),
                )
                return
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("팝업의 다음 페이지 아이콘이 2페이지로 이동하지 못했습니다")
    }

    @Test
    fun highlighterSelectionSurvivesMenuDismissAndStoresTransparentStroke() {
        val eventTime = SystemClock.uptimeMillis()
        val event = motionEvent(
            action = MotionEvent.ACTION_BUTTON_PRESS,
            source = InputDevice.SOURCE_STYLUS,
            toolType = MotionEvent.TOOL_TYPE_STYLUS,
            x = 520f,
            y = 920f,
            buttonState = MotionEvent.BUTTON_STYLUS_PRIMARY,
            eventTime = eventTime,
        )
        scenario.onActivity { activity -> assertTrue(activity.dispatchGenericMotionEvent(event)) }
        event.recycle()

        assertTrue(device.wait(Until.hasObject(By.desc("형광펜")), 3_000))
        device.findObject(By.desc("형광펜")).click()
        val before = revision()
        dispatchStroke(InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS, 360f, 820f, 520f, 850f)
        assertTrue("형광펜 획이 저장되어야 합니다", waitForRevisionAfter(before))

        scenario.onActivity { activity ->
            val stroke = activity.findDryInkView().snapshot.activeStrokes.last()
            assertEquals(StrokeTool.HIGHLIGHTER, stroke.tool)
            assertEquals(0x66FFE45C, stroke.colorArgb)
        }
    }

    private fun dispatchStroke(
        source: Int,
        toolType: Int,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
    ) {
        val downTime = SystemClock.uptimeMillis()
        val events = listOf(
            motionEvent(MotionEvent.ACTION_DOWN, source, toolType, startX, startY, eventTime = downTime),
            motionEvent(MotionEvent.ACTION_MOVE, source, toolType, endX, endY, eventTime = downTime + 20),
            motionEvent(MotionEvent.ACTION_UP, source, toolType, endX, endY, eventTime = downTime + 40),
        )
        scenario.onActivity { activity -> events.forEach(activity::dispatchTouchEvent) }
        events.forEach(MotionEvent::recycle)
    }

    private fun motionEvent(
        action: Int,
        source: Int,
        toolType: Int,
        x: Float,
        y: Float,
        buttonState: Int = 0,
        eventTime: Long = SystemClock.uptimeMillis(),
    ): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            this.toolType = toolType
        })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 0.7f
            size = 0.05f
        })
        return MotionEvent.obtain(
            eventTime,
            eventTime,
            action,
            1,
            properties,
            coordinates,
            0,
            buttonState,
            1f,
            1f,
            0,
            0,
            source,
            0,
        )
    }

    private fun waitForDocument() {
        repeat(80) {
            var ready = false
            scenario.onActivity { activity ->
                ready = activity.findDryInkView().snapshot.documentId != "sample"
            }
            if (ready) return
            SystemClock.sleep(100)
        }
        throw AssertionError("PDF와 주석 문서가 준비되지 않았습니다")
    }

    private fun waitForRevisionAfter(revision: Long): Boolean {
        repeat(50) {
            if (revision() > revision) return true
            SystemClock.sleep(100)
        }
        return false
    }

    private fun revision(): Long {
        var revision = -1L
        scenario.onActivity { activity -> revision = activity.findDryInkView().snapshot.revision }
        return revision
    }

    private fun ReaderActivity.findDryInkView(): DryInkView {
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        val queue = ArrayDeque<android.view.View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            when (val view = queue.removeFirst()) {
                is DryInkView -> return view
                is android.view.ViewGroup -> (0 until view.childCount).forEach { queue.add(view.getChildAt(it)) }
            }
        }
        throw AssertionError("DryInkView를 찾을 수 없습니다")
    }
}
