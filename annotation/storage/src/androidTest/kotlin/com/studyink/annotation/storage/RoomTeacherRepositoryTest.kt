package com.studyink.annotation.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studyink.core.model.ActivityPage
import com.studyink.core.model.BookRevision
import com.studyink.core.model.BookRevisionId
import com.studyink.core.model.LearnerProfile
import com.studyink.core.model.LearningActivity
import com.studyink.core.model.LearningActivityId
import com.studyink.core.model.LearningActivitySeed
import com.studyink.core.model.LearningContentSeed
import com.studyink.core.model.PageId
import com.studyink.core.model.ProfileId
import com.studyink.core.model.SubmissionMode
import com.studyink.core.model.TeacherId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class RoomTeacherRepositoryTest {
    private lateinit var database: AnnotationDatabase
    private val now = AtomicLong(1_000L)

    @Before fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AnnotationDatabase::class.java,
        ).build()
    }

    @After fun closeDatabase() = database.close()

    @Test fun sameSubmissionReusesOneDraftAndCreatesAllReviewPages() = runTest {
        val (learning, teacher) = repositories("attempt-1", "submission-1", "review-1")
        val submission = submittedAttempt(learning)

        val first = teacher.getOrCreateDraftReview(submission, TEACHER)
        val second = teacher.getOrCreateDraftReview(submission, TEACHER)

        assertEquals(first.review.reviewId, second.review.reviewId)
        assertEquals(1, first.review.reviewNumber)
        assertEquals(listOf(0, 1), first.pages.map { it.pageNumber })
        assertTrue(first.pages.all { it.feedbackLayerId == null })
    }

    @Test fun differentSubmissionsCreateDifferentReviews() = runTest {
        val (learning, teacher) = repositories(
            "attempt-1", "submission-1", "review-1", "attempt-2", "submission-2", "review-2",
        )
        val firstSubmission = submittedAttempt(learning)
        val firstReview = teacher.getOrCreateDraftReview(firstSubmission, TEACHER)
        val secondSubmission = submittedAttempt(learning)
        val secondReview = teacher.getOrCreateDraftReview(secondSubmission, TEACHER)

        assertNotEquals(firstReview.review.reviewId, secondReview.review.reviewId)
    }

    @Test fun cancelledDraftCannotBeChangedAndNextOpenCreatesReviewTwo() = runTest {
        val (learning, teacher) = repositories("attempt-1", "submission-1", "review-1", "review-2")
        val submission = submittedAttempt(learning)
        val first = teacher.getOrCreateDraftReview(submission, TEACHER)
        teacher.cancelDraftReview(first.review.reviewId)

        val failure = runCatching { teacher.updateSummary(first.review.reviewId, "변경") }.exceptionOrNull()
        val next = teacher.getOrCreateDraftReview(submission, TEACHER)

        assertTrue(failure is IllegalStateException)
        assertEquals(2, next.review.reviewNumber)
    }

    private fun repositories(vararg ids: String): Pair<RoomLearningRepository, RoomTeacherRepository> {
        val iterator = ids.iterator()
        val generator = LearningIdGenerator(iterator::next)
        val clock = LearningClock { now.getAndIncrement() }
        return RoomLearningRepository(database, Dispatchers.Unconfined, clock, generator) to
            RoomTeacherRepository(database, Dispatchers.Unconfined, clock, generator)
    }

    private suspend fun submittedAttempt(repository: RoomLearningRepository): com.studyink.core.model.SubmissionId {
        repository.ensureContent(seed())
        database.teacherDao().insertTeacher(TeacherProfileEntity(TEACHER.value, "선생님", 1L))
        val attempt = repository.getOrCreateActiveAttempt(PROFILE, ACTIVITY)
        return repository.submitAttempt(attempt.attempt.attemptId)
    }

    private fun seed() = LearningContentSeed(
        LearnerProfile(PROFILE, "학생", 1L),
        BookRevision(REVISION, "book", DOCUMENT, 1, "hash", "책", 1L),
        listOf(LearningActivitySeed(
            LearningActivity(ACTIVITY, REVISION, "Unit", 0, SubmissionMode.INK_ONLY),
            listOf(ActivityPage(PAGE_ONE, 0, 0), ActivityPage(PAGE_TWO, 1, 1)),
        )),
    )

    private companion object {
        val PROFILE = ProfileId("student")
        val TEACHER = TeacherId("teacher")
        val REVISION = BookRevisionId("revision")
        val ACTIVITY = LearningActivityId("activity")
        const val DOCUMENT = "document"
        val PAGE_ONE = PageId("document:page:0")
        val PAGE_TWO = PageId("document:page:1")
    }
}
