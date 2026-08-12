package com.studyink.annotation.storage

import com.studyink.core.model.AttemptSession
import com.studyink.core.model.LearningActivityId
import com.studyink.core.model.ProfileId
import com.studyink.core.model.AttemptId
import com.studyink.core.model.SubmissionId
import com.studyink.core.model.ReviewId

class OpenActivityUseCase(
    private val repository: LearningRepository,
) {
    suspend operator fun invoke(
        profileId: ProfileId,
        activityId: LearningActivityId,
    ): AttemptSession = repository.getOrCreateActiveAttempt(profileId, activityId)
}

fun interface AnnotationSaveFlusher {
    suspend fun flush()
}

class SubmitAttemptUseCase(
    private val repository: LearningRepository,
) {
    suspend operator fun invoke(
        attemptId: AttemptId,
        annotationFlusher: AnnotationSaveFlusher,
    ): SubmissionId {
        annotationFlusher.flush()
        return repository.submitAttempt(attemptId)
    }
}

class StartRetryAttemptUseCase(
    private val repository: LearningRepository,
) {
    suspend operator fun invoke(
        profileId: ProfileId,
        activityId: LearningActivityId,
        sourceReviewId: ReviewId,
    ): AttemptSession = repository.startRetryAttempt(profileId, activityId, sourceReviewId)
}
