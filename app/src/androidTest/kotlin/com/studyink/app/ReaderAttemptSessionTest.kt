package com.studyink.app

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studyink.annotation.storage.OpenActivityUseCase
import com.studyink.annotation.storage.RoomLearningRepository
import com.studyink.core.model.LearningActivityId
import com.studyink.core.model.ProfileId
import com.studyink.core.model.SubmissionId
import com.studyink.reader.DryInkView
import com.studyink.reader.InkInputView
import com.studyink.reader.ReaderActivity
import com.studyink.reader.ReaderLaunchArgs
import com.studyink.reader.ReaderViewModel
import com.studyink.reader.SampleLearningContent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderAttemptSessionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var repository: RoomLearningRepository
    private var scenario: ActivityScenario<ReaderActivity>? = null

    @Before
    fun seedLearningContent() = runBlocking {
        context.deleteDatabase("master-note-annotations.db")
        repository = RoomLearningRepository.open(context)
        repository.ensureContent(SampleLearningContent.createSeed(context))
    }

    @After
    fun closeResources() {
        scenario?.close()
        repository.close()
    }

    @Test
    fun attemptWorkingLayerAndLastPageRestoreAfterReaderIsClosed() {
        runBlocking {
            val openActivity = OpenActivityUseCase(repository)
            val session = openActivity(
                ProfileId(SampleLearningContent.PROFILE_ID),
                LearningActivityId("sample-review"),
            )
            launch(session.attempt.attemptId.value, session.initialPageId.value)
            waitForReady(expectedPage = 0)

            scenario!!.onActivity { activity ->
                ReaderActivity::class.java
                    .getDeclaredMethod("showPage", Int::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                    .invoke(activity, 2)
            }
            dispatchStylusStroke()
            flush()
            scenario!!.close()
            scenario = null

            val resumed = openActivity(
                ProfileId(SampleLearningContent.PROFILE_ID),
                LearningActivityId("sample-review"),
            )
            assertEquals(session.attempt.attemptId, resumed.attempt.attemptId)
            assertEquals(2, resumed.pages.first { it.pageId == resumed.initialPageId }.pageNumber)

            launch(resumed.attempt.attemptId.value, resumed.initialPageId.value)
            waitForReady(expectedPage = 2)
            scenario!!.onActivity { activity ->
                assertEquals(resumed.attempt.attemptId.value, activity.snapshotAttemptId())
                assertEquals(1, activity.findDryInkView().snapshot.activeStrokes.size)
                assertEquals(2, activity.findDryInkView().snapshot.activeStrokes.single().pageNumber)
            }
        }
    }

    @Test
    fun submittedAttemptReopensAsAnImmutableReadOnlySnapshot() {
        runBlocking {
            val session = OpenActivityUseCase(repository)(
                ProfileId(SampleLearningContent.PROFILE_ID),
                LearningActivityId("sample-review"),
            )
            launch(session.attempt.attemptId.value, session.initialPageId.value)
            waitForReady(expectedPage = 0)
            dispatchStylusStroke()

            var submittedId: SubmissionId? = null
            scenario!!.onActivity { activity ->
                ViewModelProvider(activity)[ReaderViewModel::class.java].submit { submittedId = it }
            }
            for (attempt in 0 until 100) {
                if (submittedId != null) break
                SystemClock.sleep(100)
            }
            val submissionId = checkNotNull(submittedId) { "제출이 완료되지 않았습니다" }
            assertEquals(submissionId, repository.submitAttempt(session.attempt.attemptId))
            assertEquals(1, repository.getSubmission(submissionId).strokes.size)

            scenario!!.close()
            scenario = null
            launch(
                session.attempt.attemptId.value,
                session.initialPageId.value,
                submissionId,
            )
            waitForReady(expectedPage = 0)
            scenario!!.onActivity { activity ->
                val state = ViewModelProvider(activity)[ReaderViewModel::class.java].uiState.value
                assertTrue(state.readOnly)
                assertEquals(submissionId, state.submissionId)
                assertEquals(1, activity.findDryInkView().snapshot.activeStrokes.size)
                assertEquals(View.INVISIBLE, activity.findInkInputView().visibility)
            }
        }
    }

    private fun launch(
        attemptId: String,
        initialPageId: String,
        submissionId: SubmissionId? = null,
    ) {
        val intent = ReaderLaunchArgs(
            profileId = ProfileId(SampleLearningContent.PROFILE_ID),
            activityId = LearningActivityId("sample-review"),
            attemptId = com.studyink.core.model.AttemptId(attemptId),
            initialPageId = com.studyink.core.model.PageId(initialPageId),
            submissionId = submissionId,
        ).putInto(Intent(context, ReaderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        scenario = ActivityScenario.launch(intent)
    }

    private fun waitForReady(expectedPage: Int) {
        repeat(100) {
            var ready = false
            scenario!!.onActivity { activity ->
                val snapshot = activity.findDryInkView().snapshot
                ready = !snapshot.documentId.startsWith("loading-") &&
                    snapshot.documentId != "sample" &&
                    activity.findDryInkView().activePage == expectedPage &&
                    activity.snapshotAttemptId() != null
            }
            if (ready) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Attempt Reader가 페이지 $expectedPage 로 복원되지 않았습니다")
    }

    private fun dispatchStylusStroke() {
        val downTime = SystemClock.uptimeMillis()
        val events = listOf(
            motionEvent(MotionEvent.ACTION_DOWN, 340f, 780f, downTime),
            motionEvent(MotionEvent.ACTION_MOVE, 440f, 820f, downTime + 20),
            motionEvent(MotionEvent.ACTION_UP, 520f, 840f, downTime + 40),
        )
        scenario!!.onActivity { activity -> events.forEach(activity::dispatchTouchEvent) }
        events.forEach(MotionEvent::recycle)
        repeat(50) {
            var saved = false
            scenario!!.onActivity { activity -> saved = activity.findDryInkView().snapshot.revision > 0 }
            if (saved) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Attempt 필기가 생성되지 않았습니다")
    }

    private fun flush() {
        lateinit var viewModel: ReaderViewModel
        scenario!!.onActivity { activity ->
            viewModel = ViewModelProvider(activity)[ReaderViewModel::class.java]
        }
        runBlocking { viewModel.flush() }
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

    private fun ReaderActivity.snapshotAttemptId(): String? =
        ViewModelProvider(this)[ReaderViewModel::class.java].uiState.value.attemptSession?.attempt?.attemptId?.value

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

    private fun ReaderActivity.findInkInputView(): InkInputView {
        val root = findViewById<android.view.ViewGroup>(android.R.id.content)
        val queue = ArrayDeque<android.view.View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            when (val view = queue.removeFirst()) {
                is InkInputView -> return view
                is android.view.ViewGroup -> (0 until view.childCount).forEach { queue.add(view.getChildAt(it)) }
            }
        }
        throw AssertionError("InkInputView를 찾을 수 없습니다")
    }
}
