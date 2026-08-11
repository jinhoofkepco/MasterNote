package com.studyink.app

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studyink.reader.InkInputView
import com.studyink.reader.ReaderActivity
import com.studyink.reader.ReaderTool
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InkCallbackContractTest {
    private lateinit var scenario: ActivityScenario<ReaderActivity>

    @Before
    fun launchReader() {
        scenario = ActivityScenario.launch(ReaderActivity::class.java)
        repeat(80) {
            var ready = false
            scenario.onActivity { activity -> ready = activity.findInkInputView().viewport.activePageBounds() != null }
            if (ready) return
            SystemClock.sleep(100)
        }
        throw AssertionError("PDF 입력 좌표계가 준비되지 않았습니다")
    }

    @After
    fun closeReader() = scenario.close()

    @Test
    fun completedPenAndPartialEraseGesturesEachEmitOneCompletionCallback() {
        var strokeCompletions = 0
        var partialEraseCompletions = 0
        scenario.onActivity { activity ->
            activity.findInkInputView().apply {
                onStroke = { strokeCompletions++ }
                onErase = { _, _, _, wholeStroke ->
                    if (!wholeStroke) partialEraseCompletions++
                }
            }
            activity.dispatchGesture(ReaderTool.PEN, 360f, 820f, 520f, 870f)
            activity.dispatchGesture(ReaderTool.PARTIAL_ERASER, 430f, 820f, 450f, 870f)
        }

        assertEquals("완성된 펜 제스처는 콜백을 정확히 한 번 호출해야 합니다", 1, strokeCompletions)
        assertEquals("완성된 부분 지우개 제스처는 콜백을 정확히 한 번 호출해야 합니다", 1, partialEraseCompletions)
    }

    private fun ReaderActivity.dispatchGesture(
        tool: ReaderTool,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
    ) {
        findInkInputView().tool = tool
        val downTime = SystemClock.uptimeMillis()
        listOf(
            motionEvent(MotionEvent.ACTION_DOWN, startX, startY, downTime),
            motionEvent(MotionEvent.ACTION_MOVE, endX, endY, downTime + 20L),
            motionEvent(MotionEvent.ACTION_UP, endX, endY, downTime + 40L),
        ).forEach { event ->
            try {
                dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }

    private fun motionEvent(action: Int, x: Float, y: Float, eventTime: Long): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_STYLUS
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
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_STYLUS,
            0,
        )
    }

    private fun ReaderActivity.findInkInputView(): InkInputView {
        val queue = ArrayDeque<View>()
        queue += findViewById<ViewGroup>(android.R.id.content)
        while (queue.isNotEmpty()) {
            when (val view = queue.removeFirst()) {
                is InkInputView -> return view
                is ViewGroup -> (0 until view.childCount).forEach { queue += view.getChildAt(it) }
            }
        }
        throw AssertionError("InkInputView를 찾을 수 없습니다")
    }
}
