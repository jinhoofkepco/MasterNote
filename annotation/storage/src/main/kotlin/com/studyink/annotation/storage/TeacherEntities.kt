package com.studyink.annotation.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "teacher_profiles", primaryKeys = ["teacherId"])
internal data class TeacherProfileEntity(
    val teacherId: String,
    val displayName: String,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "teacher_prep_pages",
    primaryKeys = ["teacherId", "bookRevisionId", "pageId"],
    foreignKeys = [
        ForeignKey(TeacherProfileEntity::class, ["teacherId"], ["teacherId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(BookRevisionEntity::class, ["revisionId"], ["bookRevisionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(AnnotationLayerEntity::class, ["layerId"], ["prepLayerId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("bookRevisionId"), Index("pageId"), Index("prepLayerId", unique = true)],
)
internal data class TeacherPrepPageEntity(
    val teacherId: String,
    val bookRevisionId: String,
    val pageId: String,
    val prepLayerId: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "submission_reviews",
    primaryKeys = ["reviewId"],
    foreignKeys = [
        ForeignKey(SubmissionEntity::class, ["submissionId"], ["submissionId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(TeacherProfileEntity::class, ["teacherId"], ["reviewerId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index("submissionId"), Index("reviewerId"), Index("supersedesReviewId"),
        Index(value = ["submissionId", "reviewNumber"], unique = true),
        Index(value = ["submissionId", "reviewerId", "status"]),
    ],
)
internal data class SubmissionReviewEntity(
    val reviewId: String,
    val submissionId: String,
    val reviewerId: String,
    val reviewNumber: Int,
    val status: String,
    val decision: String,
    val summaryText: String,
    val lastVisitedPageId: String?,
    val supersedesReviewId: String?,
    val startedAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val publishedAtEpochMillis: Long?,
)

@Entity(
    tableName = "review_pages",
    primaryKeys = ["reviewId", "pageId"],
    foreignKeys = [
        ForeignKey(SubmissionReviewEntity::class, ["reviewId"], ["reviewId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(AnnotationLayerEntity::class, ["layerId"], ["feedbackLayerId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("reviewId"), Index("pageId"), Index("feedbackLayerId", unique = true)],
)
internal data class ReviewPageEntity(
    val reviewId: String,
    val pageId: String,
    val pageNumber: Int,
    val feedbackLayerId: String?,
    val checkStatus: String,
    val lastVisitedAtEpochMillis: Long,
)

@Entity(
    tableName = "review_stroke_refs",
    primaryKeys = ["reviewId", "pageId", "strokeId"],
    foreignKeys = [
        ForeignKey(SubmissionReviewEntity::class, ["reviewId"], ["reviewId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(StrokeAssetEntity::class, ["strokeId"], ["strokeId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("reviewId"), Index("strokeId")],
)
internal data class ReviewStrokeRefEntity(
    val reviewId: String,
    val pageId: String,
    val strokeId: String,
    val zOrder: Long,
)

@Entity(
    tableName = "review_answer_evaluations",
    primaryKeys = ["reviewId", "fieldId"],
    foreignKeys = [
        ForeignKey(SubmissionReviewEntity::class, ["reviewId"], ["reviewId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("reviewId")],
)
internal data class ReviewAnswerEvaluationEntity(
    val reviewId: String,
    val fieldId: String,
    val verdict: String,
    val commentText: String,
    val updatedAtEpochMillis: Long,
)

internal data class ReviewActiveStrokeRow(
    val pageId: String,
    val strokeId: String,
    val zOrder: Long,
)

internal data class RevisionPageRow(
    val pageId: String,
    val pageNumber: Int,
)
