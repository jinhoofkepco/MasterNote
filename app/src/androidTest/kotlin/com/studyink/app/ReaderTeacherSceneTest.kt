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
import com.studyink.annotation.storage.RoomTeacherRepository
import com.studyink.core.model.BookRevisionId
import com.studyink.core.model.LearningActivityId
import com.studyink.core.model.ProfileId
import com.studyink.core.model.TeacherId
import com.studyink.reader.DryInkView
import com.studyink.reader.InkInputView
import com.studyink.reader.OpenAttemptObservationUseCase
import com.studyink.reader.OpenTeacherPreparationUseCase
import com.studyink.reader.ReaderActivity
import com.studyink.reader.ReaderScene
import com.studyink.reader.ReaderSceneIntentCodec
import com.studyink.reader.ReaderViewModel
import com.studyink.reader.SampleLearningContent
import com.studyink.reader.OpenSubmissionReviewUseCase
import com.studyink.core.model.ReviewDecision
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderTeacherSceneTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var scenario: ActivityScenario<ReaderActivity>? = null

    @Before fun resetAndSeed() {
        runBlocking {
            context.deleteDatabase("master-note-annotations.db")
            RoomLearningRepository.open(context).also {
                it.ensureContent(SampleLearningContent.createSeed(context))
                it.close()
            }
        }
    }

    @After fun close() { scenario?.close() }

    @Test fun teacherPreparationStrokeRestoresAfterReaderReopen() = runBlocking {
        val teacherRepository = RoomTeacherRepository.open(context)
        val scene = OpenTeacherPreparationUseCase(teacherRepository)(
            TeacherId(RoomTeacherRepository.DEFAULT_TEACHER_ID),
            BookRevisionId(SampleLearningContent.REVISION_ID),
        )
        teacherRepository.close()

        launch(scene)
        waitForReady(readOnly = false)
        dispatchStylusStroke()
        flush()
        scenario!!.close()
        scenario = null

        launch(scene)
        waitForReady(readOnly = false, strokes = 1)
    }

    @Test fun attemptObservationHidesInkInputAndCannotMutateStudentLayer() {
        runBlocking {
            val learning = RoomLearningRepository.open(context)
            val session = OpenActivityUseCase(learning)(
                ProfileId(SampleLearningContent.PROFILE_ID), LearningActivityId("sample-review"),
            )
            val scene = OpenAttemptObservationUseCase(learning)(session.attempt.attemptId)
            learning.close()

            launch(scene)
            waitForReady(readOnly = true)
            scenario!!.onActivity { activity ->
                assertEquals(View.INVISIBLE, activity.findInkInputView().visibility)
                val viewModel = ViewModelProvider(activity)[ReaderViewModel::class.java]
                viewModel.addStroke(testStroke())
            }
            SystemClock.sleep(250)
            scenario!!.onActivity { activity ->
                assertTrue(activity.findDryInkView().snapshot.activeStrokes.isEmpty())
            }
        }
    }

    @Test fun submissionReviewEditsOnlyFeedbackAndPublishedSceneIsReadOnly() {
        runBlocking {
        val learning = RoomLearningRepository.open(context)
        val session = OpenActivityUseCase(learning)(
            ProfileId(SampleLearningContent.PROFILE_ID), LearningActivityId("sample-review"),
        )
        learning.prepareAttemptPage(session.attempt.attemptId, session.initialPageId)
        val documentId = session.documentId
        val studentStore = com.studyink.annotation.storage.RoomAnnotationStore.open(context)
        val student = testStroke("student")
        studentStore.applyMutation(
            com.studyink.annotation.engine.AnnotationDocument(
                com.studyink.core.model.AnnotationSnapshot.empty(documentId),
            ).addStroke(student),
            session.attempt.attemptId.value,
        )
        val submission = learning.submitAttempt(session.attempt.attemptId)
        studentStore.close()
        learning.close()

        val teacher = RoomTeacherRepository.open(context)
        val (reviewScene, reviewId) = OpenSubmissionReviewUseCase(teacher)(
            submission, TeacherId(RoomTeacherRepository.DEFAULT_TEACHER_ID),
        )
        teacher.close()
        launch(reviewScene)
        waitForReady(readOnly = false, strokes = 1)
        lateinit var viewModel: ReaderViewModel
        scenario!!.onActivity { viewModel = ViewModelProvider(it)[ReaderViewModel::class.java] }
        viewModel.erase(
            0,
            listOf(com.studyink.core.model.PagePoint(1f, 1f), com.studyink.core.model.PagePoint(2f, 2f)),
            100f,
            true,
        )
        SystemClock.sleep(250)
        scenario!!.onActivity { assertEquals(setOf(student.id), it.findDryInkView().snapshot.activeStrokeIds) }

        val feedback = testStroke("feedback")
        viewModel.addStroke(feedback)
        flush()
        scenario!!.onActivity { assertEquals(setOf(student.id, feedback.id), it.findDryInkView().snapshot.activeStrokeIds) }
        scenario!!.close()
        scenario = null

        val publishRepository = RoomTeacherRepository.open(context)
        publishRepository.publishReview(reviewId, ReviewDecision.ACCEPTED)
        val review = publishRepository.getReview(reviewId)
        publishRepository.close()
        launch(ReaderScene.publishedReview(
            review.attempt.revisionId, submission, reviewId, review.pages.first().pageId,
        ))
        waitForReady(readOnly = true, strokes = 2)
            scenario!!.onActivity { assertEquals(View.INVISIBLE, it.findInkInputView().visibility) }
        }
    }

    private fun launch(scene: ReaderScene) {
        scenario = ActivityScenario.launch(
            ReaderSceneIntentCodec.put(
                Intent(context, ReaderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), scene,
            )
        )
    }

    private fun waitForReady(readOnly: Boolean, strokes: Int = 0) {
        var diagnostic = "no activity"
        repeat(100) {
            var ready = false
            scenario!!.onActivity { activity ->
                val state = ViewModelProvider(activity)[ReaderViewModel::class.java].uiState.value
                diagnostic = "status=${state.status}, busy=${state.busy}, readOnly=${state.readOnly}, scene=${state.scene != null}, strokes=${activity.findDryInkView().snapshot.activeStrokes.size}"
                ready = state.scene != null && !state.busy && state.readOnly == readOnly &&
                    activity.findDryInkView().snapshot.activeStrokes.size == strokes
            }
            if (ready) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Teacher Reader scene did not become ready: $diagnostic")
    }

    private fun dispatchStylusStroke() {
        val down = SystemClock.uptimeMillis()
        val events = listOf(
            event(MotionEvent.ACTION_DOWN, 330f, 760f, down),
            event(MotionEvent.ACTION_MOVE, 430f, 800f, down + 20),
            event(MotionEvent.ACTION_UP, 510f, 820f, down + 40),
        )
        scenario!!.onActivity { activity -> events.forEach(activity::dispatchTouchEvent) }
        events.forEach(MotionEvent::recycle)
        repeat(50) {
            var saved = false
            scenario!!.onActivity { saved = it.findDryInkView().snapshot.activeStrokes.size == 1 }
            if (saved) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Teacher preparation stroke was not created")
    }

    private fun flush() {
        lateinit var viewModel: ReaderViewModel
        scenario!!.onActivity { viewModel = ViewModelProvider(it)[ReaderViewModel::class.java] }
        runBlocking { viewModel.flush() }
    }

    private fun event(action: Int, x: Float, y: Float, time: Long): MotionEvent = MotionEvent.obtain(
        time, time, action, 1,
        arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }),
        arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = .7f; size = .05f }),
        0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0,
    )

    private fun testStroke(id: String = "forbidden") = com.studyink.core.model.StrokeAsset(
        com.studyink.core.model.StrokeId(id), 0, com.studyink.core.model.StrokeTool.PEN,
        0xff000000.toInt(), 3f,
        listOf(com.studyink.core.model.PagePoint(1f, 1f, .5f, 0L), com.studyink.core.model.PagePoint(2f, 2f, .5f, 1L)),
        com.studyink.core.model.PageBounds(1f, 1f, 2f, 2f), 1L,
    )

    private fun ReaderActivity.findDryInkView(): DryInkView = findDescendant()
    private fun ReaderActivity.findInkInputView(): InkInputView = findDescendant()
    private inline fun <reified T : View> ReaderActivity.findDescendant(): T {
        val queue = ArrayDeque<View>()
        queue.add(findViewById(android.R.id.content))
        while (queue.isNotEmpty()) when (val view = queue.removeFirst()) {
            is T -> return view
            is android.view.ViewGroup -> (0 until view.childCount).forEach { queue.add(view.getChildAt(it)) }
        }
        throw AssertionError("${T::class.java.simpleName} not found")
    }
}
