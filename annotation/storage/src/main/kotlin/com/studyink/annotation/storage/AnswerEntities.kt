package com.studyink.annotation.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "answer_documents",
    primaryKeys = ["answerDocumentId"],
    foreignKeys = [
        ForeignKey(
            entity = BookRevisionEntity::class,
            parentColumns = ["revisionId"],
            childColumns = ["bookRevisionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ManagedAssetEntity::class,
            parentColumns = ["assetId"],
            childColumns = ["assetId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index("bookRevisionId"),
        Index("assetId"),
        Index(value = ["bookRevisionId", "assetId", "answerKind"], unique = true),
    ],
)
internal data class AnswerDocumentEntity(
    val answerDocumentId: String,
    val bookRevisionId: String,
    val assetId: String,
    val documentType: String,
    val answerKind: String,
    val pageCount: Int,
    val displayName: String,
    val isActive: Boolean,
    val linkedAtEpochMillis: Long,
)

@Entity(
    tableName = "answer_page_links",
    primaryKeys = ["linkId"],
    foreignKeys = [
        ForeignKey(
            entity = BookRevisionEntity::class,
            parentColumns = ["revisionId"],
            childColumns = ["bookRevisionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AnswerDocumentEntity::class,
            parentColumns = ["answerDocumentId"],
            childColumns = ["answerDocumentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("bookRevisionId"),
        Index("answerDocumentId"),
        Index("activityId"),
        Index("problemPageId"),
    ],
)
internal data class AnswerPageLinkEntity(
    val linkId: String,
    val bookRevisionId: String,
    val answerDocumentId: String,
    val activityId: String?,
    val problemPageId: String?,
    val problemLeft: Float?,
    val problemTop: Float?,
    val problemRight: Float?,
    val problemBottom: Float?,
    val answerPageIndex: Int,
    val answerLeft: Float?,
    val answerTop: Float?,
    val answerRight: Float?,
    val answerBottom: Float?,
    val sortOrder: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "answer_bookmarks",
    primaryKeys = ["teacherId", "answerDocumentId"],
    foreignKeys = [
        ForeignKey(
            entity = TeacherProfileEntity::class,
            parentColumns = ["teacherId"],
            childColumns = ["teacherId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = AnswerDocumentEntity::class,
            parentColumns = ["answerDocumentId"],
            childColumns = ["answerDocumentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("answerDocumentId")],
)
internal data class AnswerBookmarkEntity(
    val teacherId: String,
    val answerDocumentId: String,
    val pageIndex: Int,
    val normalizedCenterX: Float,
    val normalizedCenterY: Float,
    val zoomScale: Float,
    val updatedAtEpochMillis: Long,
)
