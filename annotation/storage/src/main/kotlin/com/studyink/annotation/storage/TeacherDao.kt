package com.studyink.annotation.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface TeacherDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTeacher(entity: TeacherProfileEntity): Long

    @Query("SELECT * FROM teacher_prep_pages WHERE teacherId = :teacherId AND bookRevisionId = :revisionId AND pageId = :pageId")
    suspend fun prepPage(teacherId: String, revisionId: String, pageId: String): TeacherPrepPageEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPrepPage(entity: TeacherPrepPageEntity)

    @Query("SELECT * FROM teacher_prep_pages WHERE teacherId = :teacherId AND bookRevisionId = :revisionId ORDER BY pageId")
    fun observePrepPages(teacherId: String, revisionId: String): Flow<List<TeacherPrepPageEntity>>

    @Query("SELECT * FROM teacher_prep_pages WHERE teacherId = :teacherId AND bookRevisionId = :revisionId ORDER BY pageId")
    suspend fun prepPages(teacherId: String, revisionId: String): List<TeacherPrepPageEntity>

    @Query("DELETE FROM teacher_prep_pages WHERE teacherId = :teacherId AND bookRevisionId = :revisionId AND pageId = :pageId")
    suspend fun deletePrepPage(teacherId: String, revisionId: String, pageId: String): Int

    @Query(
        """
        SELECT DISTINCT refs.pageId AS pageId, refs.pageNumber AS pageNumber
        FROM activity_page_refs refs
        INNER JOIN learning_activities activity ON activity.activityId = refs.activityId
        WHERE activity.revisionId = :revisionId
        ORDER BY refs.pageNumber
        """
    )
    suspend fun revisionPages(revisionId: String): List<RevisionPageRow>

    @Query("SELECT * FROM submission_reviews WHERE submissionId = :submissionId AND reviewerId = :teacherId AND status = 'DRAFT' ORDER BY reviewNumber DESC LIMIT 1")
    suspend fun draftReview(submissionId: String, teacherId: String): SubmissionReviewEntity?

    @Query("SELECT * FROM submission_reviews WHERE reviewId = :reviewId")
    suspend fun review(reviewId: String): SubmissionReviewEntity?

    @Query("SELECT COALESCE(MAX(reviewNumber), 0) FROM submission_reviews WHERE submissionId = :submissionId")
    suspend fun maxReviewNumber(submissionId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReview(entity: SubmissionReviewEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReviewPages(entities: List<ReviewPageEntity>)

    @Query("SELECT * FROM review_pages WHERE reviewId = :reviewId ORDER BY pageNumber")
    suspend fun reviewPages(reviewId: String): List<ReviewPageEntity>

    @Query("SELECT * FROM review_pages WHERE reviewId = :reviewId AND pageId = :pageId")
    suspend fun reviewPage(reviewId: String, pageId: String): ReviewPageEntity?

    @Query("UPDATE review_pages SET feedbackLayerId = :layerId, lastVisitedAtEpochMillis = :updatedAt WHERE reviewId = :reviewId AND pageId = :pageId AND feedbackLayerId IS NULL AND EXISTS (SELECT 1 FROM submission_reviews WHERE reviewId = :reviewId AND status = 'DRAFT')")
    suspend fun attachFeedbackLayer(reviewId: String, pageId: String, layerId: String, updatedAt: Long): Int

    @Query(
        """
        SELECT review_pages.pageId AS pageId, layer_strokes.strokeId AS strokeId, layer_strokes.zOrder AS zOrder
        FROM review_pages
        INNER JOIN layer_strokes ON layer_strokes.layerId = review_pages.feedbackLayerId
        WHERE review_pages.reviewId = :reviewId AND layer_strokes.active = 1
        ORDER BY review_pages.pageNumber, layer_strokes.zOrder
        """
    )
    suspend fun activeFeedbackStrokes(reviewId: String): List<ReviewActiveStrokeRow>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertReviewStrokeRefs(entities: List<ReviewStrokeRefEntity>)

    @Query("SELECT * FROM review_stroke_refs WHERE reviewId = :reviewId ORDER BY pageId, zOrder")
    suspend fun reviewStrokeRefs(reviewId: String): List<ReviewStrokeRefEntity>

    @Query("UPDATE annotation_layers SET locked = 1 WHERE layerId IN (SELECT feedbackLayerId FROM review_pages WHERE reviewId = :reviewId AND feedbackLayerId IS NOT NULL)")
    suspend fun lockFeedbackLayers(reviewId: String): Int

    @Query("UPDATE submission_reviews SET status = 'PUBLISHED', decision = :decision, publishedAtEpochMillis = :publishedAt, updatedAtEpochMillis = :publishedAt WHERE reviewId = :reviewId AND status = 'DRAFT'")
    suspend fun publishReview(reviewId: String, decision: String, publishedAt: Long): Int

    @Query("UPDATE submission_reviews SET summaryText = :text, updatedAtEpochMillis = :updatedAt WHERE reviewId = :reviewId AND status = 'DRAFT'")
    suspend fun updateSummary(reviewId: String, text: String, updatedAt: Long): Int

    @Query("UPDATE submission_reviews SET lastVisitedPageId = :pageId, updatedAtEpochMillis = :updatedAt WHERE reviewId = :reviewId AND status = 'DRAFT'")
    suspend fun updateResumePage(reviewId: String, pageId: String, updatedAt: Long): Int

    @Query("UPDATE review_pages SET checkStatus = :status, lastVisitedAtEpochMillis = :updatedAt WHERE reviewId = :reviewId AND pageId = :pageId")
    suspend fun updatePageCheck(reviewId: String, pageId: String, status: String, updatedAt: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEvaluation(entity: ReviewAnswerEvaluationEntity)

    @Query("SELECT * FROM review_answer_evaluations WHERE reviewId = :reviewId ORDER BY fieldId")
    suspend fun evaluations(reviewId: String): List<ReviewAnswerEvaluationEntity>

    @Query("UPDATE submission_reviews SET status = 'CANCELLED', updatedAtEpochMillis = :updatedAt WHERE reviewId = :reviewId AND status = 'DRAFT'")
    suspend fun cancelDraft(reviewId: String, updatedAt: Long): Int

    @Query("SELECT attempts.* FROM submissions INNER JOIN attempts ON attempts.attemptId = submissions.attemptId WHERE submissions.submissionId = :submissionId")
    suspend fun attemptForSubmission(submissionId: String): AttemptEntity?
}
