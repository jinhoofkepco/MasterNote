package com.studyink.app

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.lifecycle.ViewModelProvider
import com.studyink.progress.ProgressActivity
import com.studyink.progress.ProgressUiState
import com.studyink.progress.ProgressViewModel
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProgressFlowTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var scenario: ActivityScenario<ProgressActivity>? = null

    @Before
    fun resetDatabase() {
        context.deleteDatabase("master-note-annotations.db")
    }

    @After
    fun closeActivity() {
        scenario?.close()
    }

    @Test
    fun activityCreatesAttemptSubmitsAndUpdatesProgressProjection() {
        scenario = ActivityScenario.launch(ProgressActivity::class.java)
        val unit = device.wait(Until.findObject(By.text("Unit 1")), TIMEOUT)
        assertNotNull("고정 진도 항목이 표시되지 않았습니다", unit)
        unit.click()

        val submit = device.wait(Until.findObject(By.text("답안 제출")), TIMEOUT)
        assertNotNull("Reader 제출 버튼이 표시되지 않았습니다", submit)
        submit.click()

        var updated = false
        for (attempt in 0 until 150) {
            scenario!!.onActivity { activity ->
                val state = ViewModelProvider(activity)[ProgressViewModel::class.java].uiState.value
                updated = state is ProgressUiState.Content &&
                    state.activities.first { item -> item.title == "Unit 1" }.let { item ->
                        item.markerCount == 1 && !item.hasDraftMarker
                    }
            }
            if (updated) break
            Thread.sleep(100)
        }
        org.junit.Assert.assertTrue("제출 후 진도 목록이 갱신되지 않았습니다", updated)
    }

    private companion object {
        const val TIMEOUT = 15_000L
    }
}
