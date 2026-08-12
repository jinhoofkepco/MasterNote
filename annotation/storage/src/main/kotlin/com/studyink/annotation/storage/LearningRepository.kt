package com.studyink.annotation.storage

import com.studyink.core.model.ActivityProgress
import com.studyink.core.model.AttemptId
import com.studyink.core.model.AttemptSession
import com.studyink.core.model.BookRevisionId
import com.studyink.core.model.DraftAnswer
import com.studyink.core.model.LearningActivityId
import com.studyink.core.model.LearningContentSeed
import com.studyink.core.model.PageId
import com.studyink.core.model.ProfileId
import com.studyink.core.model.SubmissionId
import com.studyink.core.model.SubmissionSnapshot
import kotlinx.coroutines.flow.Flow

interface LearningRepository {
    suspend fun ensureContent(seed: LearningContentSeed)

    fun observeActivitiesWithProgress(
        profileId: ProfileId,
        revisionId: BookRevisionId,
    ): Flow<List<ActivityProgress>>

    suspend fun getOrCreateActiveAttempt(
        profileId: ProfileId,
        activityId: LearningActivityId,
    ): AttemptSession

    suspend fun startNewAttempt(
        profileId: ProfileId,
        activityId: LearningActivityId,
    ): AttemptSession

    suspend fun getAttemptSession(attemptId: AttemptId): AttemptSession

    suspend fun prepareAttemptPage(attemptId: AttemptId, pageId: PageId)

    suspend fun updateResumePage(attemptId: AttemptId, pageId: PageId)
    suspend fun abandonAttempt(attemptId: AttemptId)
    suspend fun upsertDraftAnswer(answer: DraftAnswer)
    suspend fun submitAttempt(attemptId: AttemptId): SubmissionId
    suspend fun getSubmission(submissionId: SubmissionId): SubmissionSnapshot
}
