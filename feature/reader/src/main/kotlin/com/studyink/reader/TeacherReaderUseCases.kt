package com.studyink.reader

import com.studyink.annotation.storage.LearningRepository
import com.studyink.annotation.storage.TeacherPreparationRepository
import com.studyink.annotation.storage.TeacherReviewRepository
import com.studyink.core.model.AttemptId
import com.studyink.core.model.AttemptStatus
import com.studyink.core.model.BookRevisionId
import com.studyink.core.model.PageId
import com.studyink.core.model.TeacherId
import com.studyink.core.model.ReviewId
import com.studyink.core.model.SubmissionId
import com.studyink.core.model.ReviewDecision
import com.studyink.core.model.PublishedReview

class OpenTeacherPreparationUseCase(
    private val repository: TeacherPreparationRepository,
) {
    suspend operator fun invoke(
        teacherId: TeacherId,
        revisionId: BookRevisionId,
        initialPageId: PageId? = null,
    ): ReaderScene {
        val session = repository.getPreparationSession(teacherId, revisionId, initialPageId)
        return ReaderScene.teacherPreparation(
            teacherId = teacherId,
            revisionId = revisionId,
            pageId = session.initialPageId,
        )
    }
}

class OpenAttemptObservationUseCase(
    private val repository: LearningRepository,
) {
    suspend operator fun invoke(attemptId: AttemptId, initialPageId: PageId? = null): ReaderScene {
        val session = repository.getAttemptSession(attemptId)
        check(session.attempt.status == AttemptStatus.IN_PROGRESS) {
            "Only an in-progress attempt can be observed as a live layer"
        }
        val pageId = initialPageId
            ?.takeIf { candidate -> session.pages.any { it.pageId == candidate } }
            ?: session.initialPageId
        return ReaderScene.attemptObservation(session.attempt.revisionId, attemptId, pageId)
    }
}

class OpenSubmissionReviewUseCase(
    private val repository: TeacherReviewRepository,
) {
    suspend operator fun invoke(submissionId: SubmissionId, teacherId: TeacherId): Pair<ReaderScene, ReviewId> {
        val session = repository.getOrCreateDraftReview(submissionId, teacherId)
        return ReaderScene.submissionReview(
            revisionId = session.attempt.revisionId,
            submissionId = submissionId,
            reviewId = session.review.reviewId,
            pageId = session.review.lastVisitedPageId ?: session.pages.first().pageId,
            teacherId = teacherId,
        ) to session.review.reviewId
    }
}

class PublishSubmissionReviewUseCase(
    private val repository: TeacherReviewRepository,
) {
    suspend operator fun invoke(
        reviewId: ReviewId,
        decision: ReviewDecision,
        flushPendingWrites: suspend () -> Unit,
    ): PublishedReview {
        flushPendingWrites()
        return repository.publishReview(reviewId, decision)
    }
}
