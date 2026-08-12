package com.studyink.core.model

@JvmInline value class TeacherId(val value: String)
@JvmInline value class ReviewId(val value: String)

enum class ReviewStatus { DRAFT, PUBLISHED, CANCELLED }
enum class ReviewDecision { NONE, ACCEPTED, RETRY_REQUESTED }
enum class ReviewPageCheckStatus { PENDING, CHECKED }
enum class AnswerVerdict { UNMARKED, CORRECT, INCORRECT, PARTIAL }
enum class TeacherQueueStatus { UNREVIEWED, IN_REVIEW, REVIEWED_ACCEPTED, REVIEWED_RETRY }

data class TeacherProfile(
    val teacherId: TeacherId,
    val displayName: String,
    val createdAtEpochMillis: Long,
)

data class TeacherPrepPage(
    val teacherId: TeacherId,
    val bookRevisionId: BookRevisionId,
    val pageId: PageId,
    val prepLayerId: LayerId,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

data class TeacherPreparationSession(
    val teacherId: TeacherId,
    val bookRevisionId: BookRevisionId,
    val documentId: String,
    val initialPageId: PageId,
    val pages: List<ActivityPage>,
)

data class SubmissionReview(
    val reviewId: ReviewId,
    val submissionId: SubmissionId,
    val reviewerId: TeacherId,
    val reviewNumber: Int,
    val status: ReviewStatus,
    val decision: ReviewDecision,
    val summaryText: String,
    val lastVisitedPageId: PageId?,
    val supersedesReviewId: ReviewId?,
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val publishedAtEpochMillis: Long?,
)

data class ReviewPage(
    val reviewId: ReviewId,
    val pageId: PageId,
    val pageNumber: Int,
    val feedbackLayerId: LayerId?,
    val checkStatus: ReviewPageCheckStatus,
    val lastVisitedAtEpochMillis: Long,
)

data class ReviewAnswerEvaluation(
    val reviewId: ReviewId,
    val fieldId: String,
    val verdict: AnswerVerdict,
    val commentText: String,
    val updatedAtEpochMillis: Long,
)

data class ReviewSession(
    val review: SubmissionReview,
    val attempt: Attempt,
    val documentId: String,
    val pages: List<ReviewPage>,
    val submission: SubmissionSnapshot,
)

data class PublishedReview(
    val review: SubmissionReview,
    val strokes: List<SubmissionStroke>,
    val evaluations: List<ReviewAnswerEvaluation>,
)
