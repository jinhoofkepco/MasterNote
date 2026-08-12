package com.studyink.annotation.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "learner_profiles", primaryKeys = ["profileId"])
internal data class LearnerProfileEntity(
    val profileId: String,
    val displayName: String,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "book_revisions",
    primaryKeys = ["revisionId"],
    indices = [Index(value = ["bookId", "revisionNumber"], unique = true), Index("documentId")],
)
internal data class BookRevisionEntity(
    val revisionId: String,
    val bookId: String,
    val documentId: String,
    val revisionNumber: Int,
    val contentHash: String,
    val title: String,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "learning_activities",
    primaryKeys = ["activityId"],
    foreignKeys = [
        ForeignKey(
            entity = BookRevisionEntity::class,
            parentColumns = ["revisionId"],
            childColumns = ["revisionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("revisionId"), Index(value = ["revisionId", "sortOrder"], unique = true)],
)
internal data class LearningActivityEntity(
    val activityId: String,
    val revisionId: String,
    val title: String,
    val sortOrder: Int,
    val submissionMode: String,
)

@Entity(
    tableName = "activity_page_refs",
    primaryKeys = ["activityId", "pageId"],
    foreignKeys = [
        ForeignKey(
            entity = LearningActivityEntity::class,
            parentColumns = ["activityId"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("activityId"), Index(value = ["activityId", "pageOrder"], unique = true)],
)
internal data class ActivityPageRefEntity(
    val activityId: String,
    val pageId: String,
    val pageNumber: Int,
    val pageOrder: Int,
)

@Entity(
    tableName = "attempts",
    primaryKeys = ["attemptId"],
    foreignKeys = [
        ForeignKey(
            entity = LearnerProfileEntity::class,
            parentColumns = ["profileId"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LearningActivityEntity::class,
            parentColumns = ["activityId"],
            childColumns = ["activityId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = BookRevisionEntity::class,
            parentColumns = ["revisionId"],
            childColumns = ["revisionId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("profileId"),
        Index("activityId"),
        Index("revisionId"),
        Index(value = ["profileId", "activityId", "attemptNumber"], unique = true),
        Index(value = ["profileId", "activityId", "status"]),
    ],
)
internal data class AttemptEntity(
    val attemptId: String,
    val profileId: String,
    val activityId: String,
    val revisionId: String,
    val attemptNumber: Int,
    val status: String,
    val lastVisitedPageId: String?,
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val submittedAtEpochMillis: Long?,
)

@Entity(
    tableName = "attempt_pages",
    primaryKeys = ["attemptId", "pageId"],
    foreignKeys = [
        ForeignKey(
            entity = AttemptEntity::class,
            parentColumns = ["attemptId"],
            childColumns = ["attemptId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AnnotationLayerEntity::class,
            parentColumns = ["layerId"],
            childColumns = ["workingLayerId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("attemptId"), Index("workingLayerId", unique = true)],
)
internal data class AttemptPageEntity(
    val attemptId: String,
    val pageId: String,
    val workingLayerId: String,
    val lastViewedAtEpochMillis: Long,
)

@Entity(
    tableName = "submissions",
    primaryKeys = ["submissionId"],
    foreignKeys = [
        ForeignKey(
            entity = AttemptEntity::class,
            parentColumns = ["attemptId"],
            childColumns = ["attemptId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("attemptId", unique = true)],
)
internal data class SubmissionEntity(
    val submissionId: String,
    val attemptId: String,
    val submittedAtEpochMillis: Long,
    val annotationRevision: Long,
)

@Entity(
    tableName = "submission_stroke_refs",
    primaryKeys = ["submissionId", "pageId", "strokeId"],
    foreignKeys = [
        ForeignKey(
            entity = SubmissionEntity::class,
            parentColumns = ["submissionId"],
            childColumns = ["submissionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = StrokeAssetEntity::class,
            parentColumns = ["strokeId"],
            childColumns = ["strokeId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index("submissionId"), Index("strokeId")],
)
internal data class SubmissionStrokeRefEntity(
    val submissionId: String,
    val pageId: String,
    val strokeId: String,
    val zOrder: Long,
)

@Entity(
    tableName = "draft_answers",
    primaryKeys = ["attemptId", "fieldId"],
    foreignKeys = [
        ForeignKey(
            entity = AttemptEntity::class,
            parentColumns = ["attemptId"],
            childColumns = ["attemptId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("attemptId")],
)
internal data class DraftAnswerEntity(
    val attemptId: String,
    val fieldId: String,
    val answerType: String,
    val valueJson: String,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "submission_answers",
    primaryKeys = ["submissionId", "fieldId"],
    foreignKeys = [
        ForeignKey(
            entity = SubmissionEntity::class,
            parentColumns = ["submissionId"],
            childColumns = ["submissionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("submissionId")],
)
internal data class SubmissionAnswerEntity(
    val submissionId: String,
    val fieldId: String,
    val answerType: String,
    val valueJson: String,
)

internal data class ActivityProgressRow(
    val activityId: String,
    val title: String,
    val sortOrder: Int,
    val attemptCount: Int,
    val submissionCount: Int,
    val hasDraft: Boolean,
    val latestAttemptId: String?,
    val lastOpenedAtEpochMillis: Long?,
    val lastSubmittedAtEpochMillis: Long?,
)
