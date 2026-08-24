package com.studyink.app

import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.studyink.reader.RemoteReviewView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class RemoteReviewLayoutTest {
    @Test
    fun headerClearsStatusBarAndReviewStatusStaysOnOneLineAtS23Width() {
        ActivityScenario.launch(RemoteReviewActivity::class.java).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val title = activity.window.decorView.findTextView("받은 원격 페이지")
                assertNotNull(title)
                val titleLocation = IntArray(2)
                checkNotNull(title).getLocationOnScreen(titleLocation)
                val safeTop = ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.getInsets(
                        WindowInsetsCompat.Type.systemBars() or
                            WindowInsetsCompat.Type.displayCutout(),
                    )
                    ?.top
                    ?: 0
                assertTrue(
                    "review title must start below the status bar: y=${titleLocation[1]}, top=$safeTop",
                    titleLocation[1] >= safeTop,
                )

                val review = RemoteReviewView(activity)
                val density = activity.resources.displayMetrics.density
                val width = (412f * density).roundToInt()
                val height = (800f * density).roundToInt()
                review.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                )
                review.layout(0, 0, width, height)

                val status = checkNotNull(review.findTextView("받은 페이지 없음"))
                val scroller = checkNotNull(review.findHorizontalScroller())
                assertEquals(1, status.lineCount)
                assertTrue("status must be below the action row", status.top >= scroller.bottom)
            }
        }
    }
}

private fun View.findTextView(text: String): TextView? {
    if (this is TextView && this.text.toString() == text) return this
    if (this !is ViewGroup) return null
    repeat(childCount) { index -> getChildAt(index).findTextView(text)?.let { return it } }
    return null
}

private fun View.findHorizontalScroller(): HorizontalScrollView? {
    if (this is HorizontalScrollView) return this
    if (this !is ViewGroup) return null
    repeat(childCount) { index -> getChildAt(index).findHorizontalScroller()?.let { return it } }
    return null
}
