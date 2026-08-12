package com.studyink.annotation.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface LearningDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProfile(entity: LearnerProfileEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookRevision(entity: BookRevisionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertActivity(entity: LearningActivityEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertActivityPages(entities: List<ActivityPageRefEntity>): List<Long>

    @Query("SELECT * FROM book_revisions WHERE revisionId = :revisionId")
    suspend fun bookRevision(revisionId: String): BookRevisionEntity?

    @Query("SELECT * FROM learning_activities WHERE activityId = :activityId")
    suspend fun activity(activityId: String): LearningActivityEntity?

    @Query("SELECT * FROM activity_page_refs WHERE activityId = :activityId ORDER BY pageOrder")
    suspend fun activityPages(activityId: String): List<ActivityPageRefEntity>

    @Query(
        """
        SELECT * FROM attempts
        WHERE profileId = :profileId AND activityId = :activityId AND status = 'IN_PROGRESS'
        ORDER BY attemptNumber DESC LIMIT 1
        """
    )
    suspend fun activeAttempt(profileId: String, activityId: String): AttemptEntity?

    @Query("SELECT * FROM attempts WHERE attemptId = :attemptId")
    suspend fun attempt(attemptId: String): AttemptEntity?

    @Query(
        """
        SELECT COALESCE(MAX(attemptNumber), 0) FROM attempts
        WHERE profileId = :profileId AND activityId = :activityId
        """
    )
    suspend fun maxAttemptNumber(profileId: String, activityId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttempt(entity: AttemptEntity)

    @Query(
        """
        UPDATE attempts
        SET lastVisitedPageId = :pageId,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE attemptId = :attemptId AND status = 'IN_PROGRESS'
        """
    )
    suspend fun updateResumePage(attemptId: String, pageId: String, updatedAtEpochMillis: Long): Int

    @Query(
        """
        UPDATE attempts
        SET status = 'ABANDONED', updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE attemptId = :attemptId AND status = 'IN_PROGRESS'
        """
    )
    suspend fun abandonAttempt(attemptId: String, updatedAtEpochMillis: Long): Int

    @Query("SELECT * FROM attempt_pages WHERE attemptId = :attemptId AND pageId = :pageId")
    suspend fun attemptPage(attemptId: String, pageId: String): AttemptPageEntity?

    @Query("SELECT * FROM attempt_pages WHERE attemptId = :attemptId ORDER BY lastViewedAtEpochMillis")
    suspend fun attemptPages(attemptId: String): List<AttemptPageEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAttemptPage(entity: AttemptPageEntity)

    @Query(
        """
        UPDATE attempt_pages SET lastViewedAtEpochMillis = :viewedAtEpochMillis
        WHERE attemptId = :attemptId AND pageId = :pageId
        """
    )
    suspend fun touchAttemptPage(attemptId: String, pageId: String, viewedAtEpochMillis: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDraftAnswer(entity: DraftAnswerEntity)

    @Query("SELECT * FROM draft_answers WHERE attemptId = :attemptId ORDER BY fieldId")
    suspend fun draftAnswers(attemptId: String): List<DraftAnswerEntity>

    @Query("SELECT * FROM submissions WHERE attemptId = :attemptId")
    suspend fun submissionForAttempt(attemptId: String): SubmissionEntity?

    @Query("SELECT * FROM submissions WHERE submissionId = :submissionId")
    suspend fun submission(submissionId: String): SubmissionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSubmission(entity: SubmissionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSubmissionStrokeRefs(entities: List<SubmissionStrokeRefEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSubmissionAnswers(entities: List<SubmissionAnswerEntity>)

    @Query("SELECT * FROM submission_stroke_refs WHERE submissionId = :submissionId ORDER BY pageId, zOrder")
    suspend fun submissionStrokeRefs(submissionId: String): List<SubmissionStrokeRefEntity>

    @Query("SELECT * FROM submission_answers WHERE submissionId = :submissionId ORDER BY fieldId")
    suspend fun submissionAnswers(submissionId: String): List<SubmissionAnswerEntity>

    @Query(
        """
        UPDATE attempts
        SET status = 'SUBMITTED',
            submittedAtEpochMillis = :submittedAtEpochMillis,
            updatedAtEpochMillis = :submittedAtEpochMillis
        WHERE attemptId = :attemptId AND status = 'IN_PROGRESS'
        """
    )
    suspend fun markAttemptSubmitted(attemptId: String, submittedAtEpochMillis: Long): Int

    @Query(
        """
        SELECT
            activity.activityId AS activityId,
            activity.title AS title,
            activity.sortOrder AS sortOrder,
            CAST(COUNT(attempt.attemptId) AS INTEGER) AS attemptCount,
            CAST(COALESCE(SUM(CASE WHEN attempt.status = 'SUBMITTED' THEN 1 ELSE 0 END), 0) AS INTEGER) AS submissionCount,
            EXISTS(
                SELECT 1 FROM attempts active
                WHERE active.profileId = :profileId
                  AND active.activityId = activity.activityId
                  AND active.status = 'IN_PROGRESS'
            ) AS hasDraft,
            (
                SELECT latest.attemptId FROM attempts latest
                WHERE latest.profileId = :profileId AND latest.activityId = activity.activityId
                ORDER BY latest.updatedAtEpochMillis DESC, latest.attemptNumber DESC LIMIT 1
            ) AS latestAttemptId,
            MAX(attempt.updatedAtEpochMillis) AS lastOpenedAtEpochMillis,
            MAX(attempt.submittedAtEpochMillis) AS lastSubmittedAtEpochMillis
        FROM learning_activities activity
        LEFT JOIN attempts attempt
          ON attempt.activityId = activity.activityId AND attempt.profileId = :profileId
        WHERE activity.revisionId = :revisionId
        GROUP BY activity.activityId
        ORDER BY activity.sortOrder
        """
    )
    fun observeActivityProgress(profileId: String, revisionId: String): Flow<List<ActivityProgressRow>>

    @Query("SELECT COUNT(*) FROM submissions WHERE attemptId = :attemptId")
    suspend fun submissionCountForAttempt(attemptId: String): Int

    @Query("SELECT COUNT(*) FROM submission_stroke_refs WHERE submissionId = :submissionId")
    suspend fun submissionStrokeRefCount(submissionId: String): Int

    @Query("SELECT COUNT(*) FROM submission_answers WHERE submissionId = :submissionId")
    suspend fun submissionAnswerCount(submissionId: String): Int
}
