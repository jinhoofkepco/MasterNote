package com.studyink.annotation.storage

import com.studyink.core.model.AnswerVerdict
import com.studyink.core.model.BookRevisionId
import com.studyink.core.model.PageId
import com.studyink.core.model.ReviewId
import com.studyink.core.model.ReviewSession
import com.studyink.core.model.SubmissionId
import com.studyink.core.model.TeacherId
import com.studyink.core.model.TeacherPrepPage
import kotlinx.coroutines.flow.Flow

interface TeacherPreparationRepository {
    suspend fun getOrCreatePrepLayer(teacherId: TeacherId, bookRevisionId: BookRevisionId, pageId: PageId): TeacherPrepPage
    fun observePreparedPages(teacherId: TeacherId, bookRevisionId: BookRevisionId): Flow<List<TeacherPrepPage>>
    suspend fun deleteEmptyPrepPage(teacherId: TeacherId, bookRevisionId: BookRevisionId, pageId: PageId): Boolean
}

interface TeacherReviewRepository {
    suspend fun ensureDefaultTeacher()
    suspend fun getOrCreateDraftReview(submissionId: SubmissionId, teacherId: TeacherId): ReviewSession
    suspend fun getReview(reviewId: ReviewId): ReviewSession
    suspend fun markPageChecked(reviewId: ReviewId, pageId: PageId, checked: Boolean)
    suspend fun updateSummary(reviewId: ReviewId, text: String)
    suspend fun updateAnswerEvaluation(reviewId: ReviewId, fieldId: String, verdict: AnswerVerdict, comment: String)
    suspend fun cancelDraftReview(reviewId: ReviewId)
}
