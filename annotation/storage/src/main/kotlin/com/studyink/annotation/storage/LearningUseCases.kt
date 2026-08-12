package com.studyink.annotation.storage

import com.studyink.core.model.AttemptSession
import com.studyink.core.model.LearningActivityId
import com.studyink.core.model.ProfileId

class OpenActivityUseCase(
    private val repository: LearningRepository,
) {
    suspend operator fun invoke(
        profileId: ProfileId,
        activityId: LearningActivityId,
    ): AttemptSession = repository.getOrCreateActiveAttempt(profileId, activityId)
}
