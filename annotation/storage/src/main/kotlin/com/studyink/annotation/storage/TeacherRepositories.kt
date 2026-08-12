package com.studyink.annotation.storage

import com.studyink.core.model.AnswerVerdict
import com.studyink.core.model.BookRevisionId
import com.studyink.core.model.PageId
import com.studyink.core.model.ReviewId
import com.studyink.core.model.ReviewSession
import com.studyink.core.model.ReviewDecision
import com.studyink.core.model.PublishedReview
import com.studyink.core.model.LayerId
import com.studyink.core.model.SubmissionId
import com.studyink.core.model.TeacherId
import com.studyink.core.model.TeacherPrepPage
import com.studyink.core.model.TeacherPreparationSession
import kotlinx.coroutines.flow.Flow
import com.studyink.core.model.ReviewQueueItem

interface TeacherPreparationRepository {
    suspend fun getPreparationSession(teacherId: TeacherId, bookRevisionId: BookRevisionId, initialPageId: PageId? = null): TeacherPreparationSession
    suspend fun getOrCreatePrepLayer(teacherId: TeacherId, bookRevisionId: BookRevisionId, pageId: PageId): TeacherPrepPage
    fun observePreparedPages(teacherId: TeacherId, bookRevisionId: BookRevisionId): Flow<List<TeacherPrepPage>>
    suspend fun deleteEmptyPrepPage(teacherId: TeacherId, bookRevisionId: BookRevisionId, pageId: PageId): Boolean
}

interface TeacherReviewRepository {
    suspend fun ensureDefaultTeacher()
    suspend fun getOrCreateDraftReview(submissionId: SubmissionId, teacherId: TeacherId): ReviewSession
    suspend fun getReview(reviewId: ReviewId): ReviewSession
    suspend fun getOrCreateFeedbackLayer(reviewId: ReviewId, pageId: PageId): LayerId
    suspend fun markPageChecked(reviewId: ReviewId, pageId: PageId, checked: Boolean)
    suspend fun updateReviewResumePage(reviewId: ReviewId, pageId: PageId)
    suspend fun updateSummary(reviewId: ReviewId, text: String)
    suspend fun updateAnswerEvaluation(reviewId: ReviewId, fieldId: String, verdict: AnswerVerdict, comment: String)
    suspend fun cancelDraftReview(reviewId: ReviewId)
    suspend fun publishReview(reviewId: ReviewId, decision: ReviewDecision): PublishedReview
    suspend fun getPublishedReview(reviewId: ReviewId): PublishedReview
    fun observeReviewQueue(teacherId: TeacherId): Flow<List<ReviewQueueItem>>
}

enum class ReviewPublishPhase { AFTER_REFS, AFTER_LAYER_LOCK, BEFORE_STATUS_CHANGE }

fun interface ReviewPublishFaultInjector {
    fun at(phase: ReviewPublishPhase)

    companion object { val NONE = ReviewPublishFaultInjector {} }
}
