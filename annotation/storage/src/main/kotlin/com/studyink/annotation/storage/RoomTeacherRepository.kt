package com.studyink.annotation.storage

import android.content.Context
import androidx.room.withTransaction
import com.studyink.core.model.AnswerType
import com.studyink.core.model.AnswerVerdict
import com.studyink.core.model.Attempt
import com.studyink.core.model.AttemptId
import com.studyink.core.model.AttemptStatus
import com.studyink.core.model.BookRevisionId
import com.studyink.core.model.LayerId
import com.studyink.core.model.LearningActivityId
import com.studyink.core.model.PageId
import com.studyink.core.model.ProfileId
import com.studyink.core.model.ReviewAnswerEvaluation
import com.studyink.core.model.ReviewDecision
import com.studyink.core.model.ReviewId
import com.studyink.core.model.ReviewPage
import com.studyink.core.model.ReviewPageCheckStatus
import com.studyink.core.model.PublishedReview
import com.studyink.core.model.ReviewSession
import com.studyink.core.model.ReviewStatus
import com.studyink.core.model.SubmissionAnswer
import com.studyink.core.model.SubmissionId
import com.studyink.core.model.SubmissionSnapshot
import com.studyink.core.model.SubmissionStroke
import com.studyink.core.model.TeacherId
import com.studyink.core.model.TeacherPrepPage
import com.studyink.core.model.TeacherPreparationSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

class RoomTeacherRepository internal constructor(
    private val database: AnnotationDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: LearningClock = LearningClock(System::currentTimeMillis),
    private val idGenerator: LearningIdGenerator = LearningIdGenerator { UUID.randomUUID().toString() },
    private val publishFaultInjector: ReviewPublishFaultInjector = ReviewPublishFaultInjector.NONE,
) : TeacherPreparationRepository, TeacherReviewRepository {
    private val teacherDao = database.teacherDao()
    private val learningDao = database.learningDao()
    private val annotationDao = database.annotationDao()

    override suspend fun ensureDefaultTeacher() = withContext(dispatcher) {
        teacherDao.insertTeacher(TeacherProfileEntity(DEFAULT_TEACHER_ID, "선생님", clock.nowEpochMillis()))
        Unit
    }

    override suspend fun getPreparationSession(
        teacherId: TeacherId,
        bookRevisionId: BookRevisionId,
        initialPageId: PageId?,
    ): TeacherPreparationSession = withContext(dispatcher) {
        val revision = requireNotNull(learningDao.bookRevision(bookRevisionId.value))
        val pages = teacherDao.revisionPages(bookRevisionId.value).mapIndexed { order, row ->
            com.studyink.core.model.ActivityPage(PageId(row.pageId), row.pageNumber, order)
        }
        require(pages.isNotEmpty())
        TeacherPreparationSession(
            teacherId, bookRevisionId, revision.documentId,
            initialPageId?.takeIf { candidate -> pages.any { it.pageId == candidate } } ?: pages.first().pageId,
            pages,
        )
    }

    override suspend fun getOrCreatePrepLayer(
        teacherId: TeacherId,
        bookRevisionId: BookRevisionId,
        pageId: PageId,
    ): TeacherPrepPage = withContext(dispatcher) {
        database.withTransaction {
            teacherDao.prepPage(teacherId.value, bookRevisionId.value, pageId.value)?.toDomain()
                ?: createPrepPage(teacherId, bookRevisionId, pageId)
        }
    }

    override fun observePreparedPages(
        teacherId: TeacherId,
        bookRevisionId: BookRevisionId,
    ): Flow<List<TeacherPrepPage>> = teacherDao.observePrepPages(teacherId.value, bookRevisionId.value)
        .map { rows -> rows.map(TeacherPrepPageEntity::toDomain) }

    override suspend fun deleteEmptyPrepPage(
        teacherId: TeacherId,
        bookRevisionId: BookRevisionId,
        pageId: PageId,
    ): Boolean = withContext(dispatcher) {
        database.withTransaction {
            val prep = teacherDao.prepPage(teacherId.value, bookRevisionId.value, pageId.value)
                ?: return@withTransaction false
            if (annotationDao.layerStrokeCount(prep.prepLayerId) != 0) return@withTransaction false
            check(teacherDao.deletePrepPage(teacherId.value, bookRevisionId.value, pageId.value) == 1)
            check(annotationDao.deleteLayer(prep.prepLayerId) == 1)
            true
        }
    }

    override suspend fun getOrCreateDraftReview(
        submissionId: SubmissionId,
        teacherId: TeacherId,
    ): ReviewSession = withContext(dispatcher) {
        database.withTransaction {
            teacherDao.draftReview(submissionId.value, teacherId.value)?.let { return@withTransaction session(it) }
            val attempt = requireNotNull(teacherDao.attemptForSubmission(submissionId.value))
            val activityPages = learningDao.activityPages(attempt.activityId)
            require(activityPages.isNotEmpty())
            val now = clock.nowEpochMillis()
            val review = SubmissionReviewEntity(
                reviewId = idGenerator.nextId(),
                submissionId = submissionId.value,
                reviewerId = teacherId.value,
                reviewNumber = teacherDao.maxReviewNumber(submissionId.value) + 1,
                status = ReviewStatus.DRAFT.name,
                decision = ReviewDecision.NONE.name,
                summaryText = "",
                lastVisitedPageId = activityPages.first().pageId,
                supersedesReviewId = null,
                startedAtEpochMillis = now,
                updatedAtEpochMillis = now,
                publishedAtEpochMillis = null,
            )
            teacherDao.insertReview(review)
            teacherDao.insertReviewPages(activityPages.map { page ->
                ReviewPageEntity(
                    reviewId = review.reviewId,
                    pageId = page.pageId,
                    pageNumber = page.pageNumber,
                    feedbackLayerId = null,
                    checkStatus = ReviewPageCheckStatus.PENDING.name,
                    lastVisitedAtEpochMillis = now,
                )
            })
            session(review)
        }
    }

    override suspend fun getReview(reviewId: ReviewId): ReviewSession = withContext(dispatcher) {
        database.withTransaction { session(requireNotNull(teacherDao.review(reviewId.value))) }
    }

    override suspend fun getOrCreateFeedbackLayer(reviewId: ReviewId, pageId: PageId): LayerId = withContext(dispatcher) {
        database.withTransaction {
            val review = requireNotNull(teacherDao.review(reviewId.value))
            check(review.status == ReviewStatus.DRAFT.name) { "Published reviews are immutable" }
            teacherDao.reviewPage(reviewId.value, pageId.value)?.feedbackLayerId?.let(::LayerId)
                ?: createFeedbackLayer(review, pageId)
        }
    }

    override suspend fun markPageChecked(reviewId: ReviewId, pageId: PageId, checked: Boolean) = withContext(dispatcher) {
        check(teacherDao.updatePageCheck(
            reviewId.value,
            pageId.value,
            if (checked) ReviewPageCheckStatus.CHECKED.name else ReviewPageCheckStatus.PENDING.name,
            clock.nowEpochMillis(),
        ) == 1)
    }

    override suspend fun updateSummary(reviewId: ReviewId, text: String) = withContext(dispatcher) {
        check(teacherDao.updateSummary(reviewId.value, text, clock.nowEpochMillis()) == 1) { "Only a draft review can change" }
    }

    override suspend fun updateAnswerEvaluation(
        reviewId: ReviewId,
        fieldId: String,
        verdict: AnswerVerdict,
        comment: String,
    ) = withContext(dispatcher) {
        check(teacherDao.review(reviewId.value)?.status == ReviewStatus.DRAFT.name)
        teacherDao.upsertEvaluation(
            ReviewAnswerEvaluationEntity(reviewId.value, fieldId, verdict.name, comment, clock.nowEpochMillis())
        )
    }

    override suspend fun cancelDraftReview(reviewId: ReviewId) = withContext(dispatcher) {
        check(teacherDao.cancelDraft(reviewId.value, clock.nowEpochMillis()) == 1)
    }

    override suspend fun publishReview(reviewId: ReviewId, decision: ReviewDecision): PublishedReview = withContext(dispatcher) {
        require(decision != ReviewDecision.NONE) { "A published review needs a decision" }
        database.withTransaction {
            val review = requireNotNull(teacherDao.review(reviewId.value))
            if (review.status == ReviewStatus.PUBLISHED.name) {
                check(review.decision == decision.name) { "Published review decision cannot be changed" }
                return@withTransaction published(review)
            }
            check(review.status == ReviewStatus.DRAFT.name) { "Only a draft review can be published" }
            val refs = teacherDao.activeFeedbackStrokes(reviewId.value).map {
                ReviewStrokeRefEntity(reviewId.value, it.pageId, it.strokeId, it.zOrder)
            }
            if (refs.isNotEmpty()) teacherDao.insertReviewStrokeRefs(refs)
            publishFaultInjector.at(ReviewPublishPhase.AFTER_REFS)
            teacherDao.lockFeedbackLayers(reviewId.value)
            publishFaultInjector.at(ReviewPublishPhase.AFTER_LAYER_LOCK)
            publishFaultInjector.at(ReviewPublishPhase.BEFORE_STATUS_CHANGE)
            check(teacherDao.publishReview(reviewId.value, decision.name, clock.nowEpochMillis()) == 1)
            published(requireNotNull(teacherDao.review(reviewId.value)))
        }
    }

    override suspend fun getPublishedReview(reviewId: ReviewId): PublishedReview = withContext(dispatcher) {
        database.withTransaction {
            val review = requireNotNull(teacherDao.review(reviewId.value))
            check(review.status == ReviewStatus.PUBLISHED.name)
            published(review)
        }
    }

    fun close() = database.close()

    private suspend fun createPrepPage(teacherId: TeacherId, revisionId: BookRevisionId, pageId: PageId): TeacherPrepPage {
        val revision = requireNotNull(learningDao.bookRevision(revisionId.value))
        val revisionPage = requireNotNull(teacherDao.revisionPages(revisionId.value).firstOrNull { it.pageId == pageId.value })
        val now = clock.nowEpochMillis()
        annotationDao.insertDocument(AnnotationDocumentEntity(revision.documentId, 0L, now))
        annotationDao.insertPage(AnnotationPageEntity(pageId.value, revision.documentId, revisionPage.pageNumber, 0L, now))
        val layerId = LayerId("${pageId.value}:teacher:${teacherId.value}:prep")
        annotationDao.insertLayer(
            AnnotationLayerEntity(
                layerId.value, pageId.value, null,
                com.studyink.core.model.AnnotationLayerType.TEACHER_PREP.name,
                com.studyink.core.model.AnnotationOwnerType.TEACHER.name,
                0L, now,
            )
        )
        return TeacherPrepPageEntity(teacherId.value, revisionId.value, pageId.value, layerId.value, now, now)
            .also { teacherDao.insertPrepPage(it) }.toDomain()
    }

    private suspend fun createFeedbackLayer(review: SubmissionReviewEntity, pageId: PageId): LayerId {
        val page = requireNotNull(teacherDao.reviewPage(review.reviewId, pageId.value))
        val attempt = requireNotNull(teacherDao.attemptForSubmission(review.submissionId))
        val revision = requireNotNull(learningDao.bookRevision(attempt.revisionId))
        val now = clock.nowEpochMillis()
        annotationDao.insertDocument(AnnotationDocumentEntity(revision.documentId, 0L, now))
        annotationDao.insertPage(AnnotationPageEntity(pageId.value, revision.documentId, page.pageNumber, 0L, now))
        val layerId = LayerId("${pageId.value}:review:${review.reviewId}:feedback")
        annotationDao.insertLayer(
            AnnotationLayerEntity(
                layerId.value, pageId.value, null,
                com.studyink.core.model.AnnotationLayerType.TEACHER_FEEDBACK.name,
                com.studyink.core.model.AnnotationOwnerType.TEACHER.name,
                0L, now,
            )
        )
        check(teacherDao.attachFeedbackLayer(review.reviewId, pageId.value, layerId.value, now) == 1)
        return layerId
    }

    private suspend fun published(review: SubmissionReviewEntity): PublishedReview = PublishedReview(
        review.toDomain(),
        teacherDao.reviewStrokeRefs(review.reviewId).map {
            SubmissionStroke(PageId(it.pageId), com.studyink.core.model.StrokeId(it.strokeId), it.zOrder)
        },
        teacherDao.evaluations(review.reviewId).map(ReviewAnswerEvaluationEntity::toDomain),
    )

    private suspend fun session(review: SubmissionReviewEntity): ReviewSession {
        val submission = requireNotNull(learningDao.submission(review.submissionId))
        val attempt = requireNotNull(teacherDao.attemptForSubmission(review.submissionId))
        val revision = requireNotNull(learningDao.bookRevision(attempt.revisionId))
        return ReviewSession(
            review = review.toDomain(),
            attempt = attempt.toDomain(),
            documentId = revision.documentId,
            pages = teacherDao.reviewPages(review.reviewId).map(ReviewPageEntity::toDomain),
            submission = SubmissionSnapshot(
                SubmissionId(submission.submissionId), AttemptId(submission.attemptId),
                submission.submittedAtEpochMillis, submission.annotationRevision,
                learningDao.submissionStrokeRefs(submission.submissionId).map {
                    SubmissionStroke(PageId(it.pageId), com.studyink.core.model.StrokeId(it.strokeId), it.zOrder)
                },
                learningDao.submissionAnswers(submission.submissionId).map {
                    SubmissionAnswer(it.fieldId, AnswerType.valueOf(it.answerType), it.valueJson)
                },
            ),
        )
    }

    companion object {
        const val DEFAULT_TEACHER_ID = "default-teacher"
        suspend fun open(context: Context): RoomTeacherRepository = withContext(Dispatchers.IO) {
            RoomTeacherRepository(AnnotationDatabase.open(context)).also { it.ensureDefaultTeacher() }
        }
    }
}

private fun TeacherPrepPageEntity.toDomain() = TeacherPrepPage(
    TeacherId(teacherId), BookRevisionId(bookRevisionId), PageId(pageId), LayerId(prepLayerId),
    createdAtEpochMillis, updatedAtEpochMillis,
)

private fun SubmissionReviewEntity.toDomain() = com.studyink.core.model.SubmissionReview(
    ReviewId(reviewId), SubmissionId(submissionId), TeacherId(reviewerId), reviewNumber,
    ReviewStatus.valueOf(status), ReviewDecision.valueOf(decision), summaryText,
    lastVisitedPageId?.let(::PageId), supersedesReviewId?.let(::ReviewId), startedAtEpochMillis,
    updatedAtEpochMillis, publishedAtEpochMillis,
)

private fun ReviewPageEntity.toDomain() = ReviewPage(
    ReviewId(reviewId), PageId(pageId), pageNumber, feedbackLayerId?.let(::LayerId),
    ReviewPageCheckStatus.valueOf(checkStatus), lastVisitedAtEpochMillis,
)

private fun AttemptEntity.toDomain() = Attempt(
    AttemptId(attemptId), ProfileId(profileId), LearningActivityId(activityId), BookRevisionId(revisionId),
    attemptNumber, AttemptStatus.valueOf(status), lastVisitedPageId?.let(::PageId), startedAtEpochMillis,
    updatedAtEpochMillis, submittedAtEpochMillis, sourceReviewId?.let(::ReviewId),
)

private fun ReviewAnswerEvaluationEntity.toDomain() = ReviewAnswerEvaluation(
    ReviewId(reviewId), fieldId, AnswerVerdict.valueOf(verdict), commentText, updatedAtEpochMillis,
)
