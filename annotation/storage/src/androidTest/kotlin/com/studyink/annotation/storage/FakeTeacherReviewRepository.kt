package com.studyink.annotation.storage

import com.studyink.core.model.AnswerVerdict
import com.studyink.core.model.PageId
import com.studyink.core.model.ReviewId
import com.studyink.core.model.ReviewSession
import com.studyink.core.model.SubmissionId
import com.studyink.core.model.TeacherId

/** Small deterministic fake for teacher ViewModel tests; it never exposes a DAO. */
class FakeTeacherReviewRepository(
    private val sessions: MutableMap<ReviewId, ReviewSession> = linkedMapOf(),
    private val draftBySubmission: MutableMap<SubmissionId, ReviewId> = linkedMapOf(),
    private val createDraft: (SubmissionId, TeacherId) -> ReviewSession,
) : TeacherReviewRepository {
    val summaries = linkedMapOf<ReviewId, String>()
    val checkedPages = linkedMapOf<Pair<ReviewId, PageId>, Boolean>()
    val evaluations = linkedMapOf<Pair<ReviewId, String>, Pair<AnswerVerdict, String>>()

    override suspend fun ensureDefaultTeacher() = Unit

    override suspend fun getOrCreateDraftReview(submissionId: SubmissionId, teacherId: TeacherId): ReviewSession {
        draftBySubmission[submissionId]?.let { return sessions.getValue(it) }
        return createDraft(submissionId, teacherId).also {
            sessions[it.review.reviewId] = it
            draftBySubmission[submissionId] = it.review.reviewId
        }
    }

    override suspend fun getReview(reviewId: ReviewId): ReviewSession = sessions.getValue(reviewId)

    override suspend fun markPageChecked(reviewId: ReviewId, pageId: PageId, checked: Boolean) {
        checkedPages[reviewId to pageId] = checked
    }

    override suspend fun updateSummary(reviewId: ReviewId, text: String) {
        summaries[reviewId] = text
    }

    override suspend fun updateAnswerEvaluation(
        reviewId: ReviewId,
        fieldId: String,
        verdict: AnswerVerdict,
        comment: String,
    ) {
        evaluations[reviewId to fieldId] = verdict to comment
    }

    override suspend fun cancelDraftReview(reviewId: ReviewId) {
        draftBySubmission.entries.removeAll { it.value == reviewId }
    }
}
