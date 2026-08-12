package com.studyink.core.model

@JvmInline value class ProfileId(val value: String)
@JvmInline value class BookRevisionId(val value: String)
@JvmInline value class LearningActivityId(val value: String)
@JvmInline value class AttemptId(val value: String)
@JvmInline value class SubmissionId(val value: String)

enum class SubmissionMode { INK_ONLY, STRUCTURED_ONLY, INK_AND_STRUCTURED }
enum class AttemptStatus { IN_PROGRESS, SUBMITTED, ABANDONED }
enum class AnswerType { TEXT, NUMBER, BOOLEAN, JSON }
enum class ActivityProgressState { NOT_STARTED, IN_PROGRESS, SUBMITTED }

data class LearnerProfile(
    val profileId: ProfileId,
    val displayName: String,
    val createdAtEpochMillis: Long,
)

data class BookRevision(
    val revisionId: BookRevisionId,
    val bookId: String,
    val documentId: String,
    val revisionNumber: Int,
    val contentHash: String,
    val title: String,
    val createdAtEpochMillis: Long,
)

data class LearningActivity(
    val activityId: LearningActivityId,
    val revisionId: BookRevisionId,
    val title: String,
    val sortOrder: Int,
    val submissionMode: SubmissionMode,
)

data class ActivityPage(
    val pageId: PageId,
    val pageNumber: Int,
    val pageOrder: Int,
)

data class LearningActivitySeed(
    val activity: LearningActivity,
    val pages: List<ActivityPage>,
)

data class LearningContentSeed(
    val profile: LearnerProfile,
    val bookRevision: BookRevision,
    val activities: List<LearningActivitySeed>,
)

data class Attempt(
    val attemptId: AttemptId,
    val profileId: ProfileId,
    val activityId: LearningActivityId,
    val revisionId: BookRevisionId,
    val attemptNumber: Int,
    val status: AttemptStatus,
    val lastVisitedPageId: PageId?,
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val submittedAtEpochMillis: Long?,
    val sourceReviewId: ReviewId? = null,
)

data class AttemptSession(
    val attempt: Attempt,
    val documentId: String,
    val initialPageId: PageId,
    val pages: List<ActivityPage>,
)

data class DraftAnswer(
    val attemptId: AttemptId,
    val fieldId: String,
    val answerType: AnswerType,
    val valueJson: String,
    val updatedAtEpochMillis: Long,
)

data class SubmissionStroke(
    val pageId: PageId,
    val strokeId: StrokeId,
    val zOrder: Long,
)

data class SubmissionAnswer(
    val fieldId: String,
    val answerType: AnswerType,
    val valueJson: String,
)

data class SubmissionSnapshot(
    val submissionId: SubmissionId,
    val attemptId: AttemptId,
    val submittedAtEpochMillis: Long,
    val annotationRevision: Long,
    val strokes: List<SubmissionStroke>,
    val answers: List<SubmissionAnswer>,
)

data class ActivityProgress(
    val activityId: LearningActivityId,
    val title: String,
    val sortOrder: Int,
    val attemptCount: Int,
    val submissionCount: Int,
    val hasDraft: Boolean,
    val latestAttemptId: AttemptId?,
    val lastOpenedAtEpochMillis: Long?,
    val lastSubmittedAtEpochMillis: Long?,
) {
    val state: ActivityProgressState = when {
        hasDraft -> ActivityProgressState.IN_PROGRESS
        submissionCount > 0 -> ActivityProgressState.SUBMITTED
        else -> ActivityProgressState.NOT_STARTED
    }
}
