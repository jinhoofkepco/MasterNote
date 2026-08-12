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
import com.studyink.core.model.ReviewDecision
import com.studyink.annotation.engine.AnnotationDocument
import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import com.studyink.core.model.ActivityProgressState
import com.studyink.core.model.TeacherQueueStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong
import java.io.IOException

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

    @Test fun concurrentDraftRequestsStillCreateOneReview() = runTest {
        val ids = AtomicLong()
        val generator = LearningIdGenerator { "concurrent-${ids.incrementAndGet()}" }
        val clock = LearningClock { now.getAndIncrement() }
        val learning = RoomLearningRepository(database, Dispatchers.IO, clock, generator)
        val teacher = RoomTeacherRepository(database, Dispatchers.IO, clock, generator)
        val submission = submittedAttempt(learning)

        val reviews = coroutineScope {
            List(8) { async(Dispatchers.Default) { teacher.getOrCreateDraftReview(submission, TEACHER) } }.awaitAll()
        }

        assertEquals(1, reviews.map { it.review.reviewId }.distinct().size)
        assertEquals(1, database.teacherDao().maxReviewNumber(submission.value))
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

    @Test fun preparationSessionUsesAllRevisionPagesAndCreatesLayersLazily() = runTest {
        val (learning, teacher) = repositories()
        learning.ensureContent(seed())
        database.teacherDao().insertTeacher(TeacherProfileEntity(TEACHER.value, "선생님", 1L))

        val session = teacher.getPreparationSession(TEACHER, REVISION, PAGE_TWO)
        assertEquals(listOf(PAGE_ONE, PAGE_TWO), session.pages.map { it.pageId })
        assertEquals(PAGE_TWO, session.initialPageId)
        assertTrue(database.teacherDao().prepPages(TEACHER.value, REVISION.value).isEmpty())

        val first = teacher.getOrCreatePrepLayer(TEACHER, REVISION, PAGE_ONE)
        val same = teacher.getOrCreatePrepLayer(TEACHER, REVISION, PAGE_ONE)
        assertEquals(first.prepLayerId, same.prepLayerId)
        assertTrue(teacher.deleteEmptyPrepPage(TEACHER, REVISION, PAGE_ONE))
        assertTrue(database.teacherDao().prepPages(TEACHER.value, REVISION.value).isEmpty())
    }

    @Test fun publishingSnapshotsFeedbackLocksLayerAndIsIdempotent() = runTest {
        val (learning, teacher) = repositories("attempt-1", "submission-1", "review-1")
        val submission = submittedAttempt(learning)
        val review = teacher.getOrCreateDraftReview(submission, TEACHER)
        val layer = teacher.getOrCreateFeedbackLayer(review.review.reviewId, PAGE_ONE)
        val store = RoomAnnotationStore(database)
        val document = AnnotationDocument(AnnotationSnapshot.empty(DOCUMENT))
        val feedback = stroke("feedback")
        store.applyMutationToLayer(document.addStroke(feedback), layer.value)

        val first = teacher.publishReview(review.review.reviewId, ReviewDecision.ACCEPTED)
        val second = teacher.publishReview(review.review.reviewId, ReviewDecision.ACCEPTED)

        assertEquals(first, second)
        assertEquals(listOf(feedback.id), first.strokes.map { it.strokeId })
        assertTrue(requireNotNull(database.annotationDao().layer(layer.value)).locked)
        assertEquals(setOf(feedback.id), store.loadPublishedReview(DOCUMENT, review.review.reviewId.value).activeStrokeIds)
        assertTrue(runCatching {
            teacher.publishReview(review.review.reviewId, ReviewDecision.RETRY_REQUESTED)
        }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching {
            store.applyMutationToLayer(document.addStroke(stroke("late")), layer.value)
        }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching {
            teacher.markPageChecked(review.review.reviewId, PAGE_ONE, true)
        }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching {
            teacher.updateSummary(review.review.reviewId, "late summary")
        }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching {
            teacher.updateAnswerEvaluation(
                review.review.reviewId,
                "answer-1",
                com.studyink.core.model.AnswerVerdict.CORRECT,
                "late evaluation",
            )
        }.exceptionOrNull() is IllegalStateException)
    }

    @Test fun everyInjectedPublishFailureRollsBackRefsLockAndStatus() = runTest {
        ReviewPublishPhase.entries.forEach { phase ->
            val isolated = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(), AnnotationDatabase::class.java,
            ).build()
            try {
                val ids = listOf("attempt-$phase", "submission-$phase", "review-$phase").iterator()
                val generator = LearningIdGenerator(ids::next)
                val clock = LearningClock { now.getAndIncrement() }
                val learning = RoomLearningRepository(isolated, Dispatchers.Unconfined, clock, generator)
                val teacher = RoomTeacherRepository(
                    isolated, Dispatchers.Unconfined, clock, generator,
                    ReviewPublishFaultInjector { reached -> if (reached == phase) throw IOException("injected") },
                )
                learning.ensureContent(seed())
                isolated.teacherDao().insertTeacher(TeacherProfileEntity(TEACHER.value, "선생님", 1L))
                val attempt = learning.getOrCreateActiveAttempt(PROFILE, ACTIVITY)
                val submission = learning.submitAttempt(attempt.attempt.attemptId)
                val review = teacher.getOrCreateDraftReview(submission, TEACHER)
                val layer = teacher.getOrCreateFeedbackLayer(review.review.reviewId, PAGE_ONE)
                val store = RoomAnnotationStore(isolated)
                store.applyMutationToLayer(
                    AnnotationDocument(AnnotationSnapshot.empty(DOCUMENT)).addStroke(stroke("stroke-$phase")),
                    layer.value,
                )

                assertTrue(runCatching {
                    teacher.publishReview(review.review.reviewId, ReviewDecision.ACCEPTED)
                }.exceptionOrNull() is IOException)
                assertEquals("DRAFT", isolated.teacherDao().review(review.review.reviewId.value)?.status)
                assertTrue(isolated.teacherDao().reviewStrokeRefs(review.review.reviewId.value).isEmpty())
                assertTrue(!requireNotNull(isolated.annotationDao().layer(layer.value)).locked)
            } finally {
                isolated.close()
            }
        }
    }

    @Test fun reviewQueueProgressAndRetryAttemptAreDerivedFromReviewHistory() = runTest {
        val (learning, teacher) = repositories("attempt-1", "submission-1", "review-1", "attempt-2")
        val submission = submittedAttempt(learning)
        assertEquals(TeacherQueueStatus.UNREVIEWED, teacher.observeReviewQueue(TEACHER).first().single().status)

        val review = teacher.getOrCreateDraftReview(submission, TEACHER)
        assertEquals(TeacherQueueStatus.IN_REVIEW, teacher.observeReviewQueue(TEACHER).first().single().status)
        assertEquals(
            ActivityProgressState.SUBMITTED,
            learning.observeActivitiesWithProgress(PROFILE, REVISION).first().single().state,
        )

        teacher.publishReview(review.review.reviewId, ReviewDecision.RETRY_REQUESTED)
        assertEquals(TeacherQueueStatus.REVIEWED_RETRY, teacher.observeReviewQueue(TEACHER).first().single().status)
        assertEquals(
            ActivityProgressState.RETRY_REQUIRED,
            learning.observeActivitiesWithProgress(PROFILE, REVISION).first().single().state,
        )

        val retry = learning.getOrCreateActiveAttempt(PROFILE, ACTIVITY)
        assertEquals(review.review.reviewId, retry.attempt.sourceReviewId)
        assertEquals(2, retry.attempt.attemptNumber)
        assertEquals(
            ActivityProgressState.IN_PROGRESS,
            learning.observeActivitiesWithProgress(PROFILE, REVISION).first().single().state,
        )
    }

    @Test fun acceptedPublishedReviewCompletesProgress() = runTest {
        val (learning, teacher) = repositories("attempt-1", "submission-1", "review-1")
        val submission = submittedAttempt(learning)
        val review = teacher.getOrCreateDraftReview(submission, TEACHER)
        teacher.publishReview(review.review.reviewId, ReviewDecision.ACCEPTED)

        assertEquals(TeacherQueueStatus.REVIEWED_ACCEPTED, teacher.observeReviewQueue(TEACHER).first().single().status)
        assertEquals(
            ActivityProgressState.COMPLETED,
            learning.observeActivitiesWithProgress(PROFILE, REVISION).first().single().state,
        )
    }

    @Test fun largeReviewSnapshotsReferenceAssetsWithoutBlobDuplication() = runTest {
        val (learning, teacher) = repositories("large-attempt", "large-submission", "large-review")
        val seed = largeSeed()
        learning.ensureContent(seed)
        database.teacherDao().insertTeacher(TeacherProfileEntity(TEACHER.value, "선생님", 1L))
        val attempt = learning.getOrCreateActiveAttempt(PROFILE, ACTIVITY)
        val store = RoomAnnotationStore(database)

        seed.activities.single().pages.forEach { page ->
            learning.prepareAttemptPage(attempt.attempt.attemptId, page.pageId)
            val pageDocument = AnnotationDocument(AnnotationSnapshot.empty(DOCUMENT))
            repeat(100) { index ->
                store.applyMutation(
                    pageDocument.addStroke(stroke("student-${page.pageNumber}-$index", page.pageNumber)),
                    attempt.attempt.attemptId.value,
                )
            }
        }
        val submission = learning.submitAttempt(attempt.attempt.attemptId)
        val review = teacher.getOrCreateDraftReview(submission, TEACHER)
        review.pages.forEach { page ->
            val layer = teacher.getOrCreateFeedbackLayer(review.review.reviewId, page.pageId)
            val pageDocument = AnnotationDocument(AnnotationSnapshot.empty(DOCUMENT))
            repeat(25) { index ->
                store.applyMutationToLayer(
                    pageDocument.addStroke(stroke("feedback-${page.pageNumber}-$index", page.pageNumber)),
                    layer.value,
                )
            }
        }
        val assetCountBeforePublish = database.learningDao().strokeAssetCount()

        val published = teacher.publishReview(review.review.reviewId, ReviewDecision.ACCEPTED)

        assertEquals(2_000, learning.getSubmission(submission).strokes.size)
        assertEquals(500, published.strokes.size)
        assertEquals(2_500, assetCountBeforePublish)
        assertEquals(assetCountBeforePublish, database.learningDao().strokeAssetCount())
        assertEquals(500, store.loadPublishedReview(DOCUMENT, review.review.reviewId.value).activeStrokeIds.size)
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

    private fun largeSeed() = LearningContentSeed(
        LearnerProfile(PROFILE, "학생", 1L),
        BookRevision(REVISION, "book", DOCUMENT, 1, "large-hash", "큰 책", 1L),
        listOf(LearningActivitySeed(
            LearningActivity(ACTIVITY, REVISION, "20 page activity", 0, SubmissionMode.INK_ONLY),
            List(20) { page -> ActivityPage(PageId("$DOCUMENT:page:$page"), page, page) },
        )),
    )

    private fun stroke(id: String, pageNumber: Int = 0) = StrokeAsset(
        id = com.studyink.core.model.StrokeId(id), pageNumber = pageNumber, tool = StrokeTool.PEN,
        colorArgb = 0xffcc2233.toInt(), width = 3f,
        points = listOf(PagePoint(10f, 10f), PagePoint(50f, 50f)),
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
