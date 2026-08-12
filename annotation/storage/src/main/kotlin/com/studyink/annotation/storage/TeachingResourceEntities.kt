package com.studyink.annotation.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "teaching_resources",
    primaryKeys = ["resourceId"],
    foreignKeys = [
        ForeignKey(entity = BookRevisionEntity::class, parentColumns = ["revisionId"], childColumns = ["bookRevisionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TeacherProfileEntity::class, parentColumns = ["teacherId"], childColumns = ["createdByTeacherId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("bookRevisionId"), Index("createdByTeacherId"), Index("currentRevisionId")],
)
internal data class TeachingResourceEntity(
    val resourceId: String,
    val bookRevisionId: String,
    val resourceType: String,
    val category: String,
    val title: String,
    val visibility: String,
    val status: String,
    val sourceType: String,
    val currentRevisionId: String?,
    val createdByTeacherId: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Entity(
    tableName = "teaching_resource_revisions",
    primaryKeys = ["revisionId"],
    foreignKeys = [
        ForeignKey(entity = TeachingResourceEntity::class, parentColumns = ["resourceId"], childColumns = ["resourceId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ManagedAssetEntity::class, parentColumns = ["assetId"], childColumns = ["imageAssetId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["resourceId", "revisionNumber"], unique = true), Index("imageAssetId")],
)
internal data class TeachingResourceRevisionEntity(
    val revisionId: String,
    val resourceId: String,
    val revisionNumber: Int,
    val textContent: String?,
    val structuredContentJson: String?,
    val imageAssetId: String?,
    val sourcePrompt: String?,
    val providerName: String?,
    val createdAtEpochMillis: Long,
)

@Entity(
    tableName = "book_page_resource_links",
    primaryKeys = ["linkId"],
    foreignKeys = [
        ForeignKey(entity = BookRevisionEntity::class, parentColumns = ["revisionId"], childColumns = ["bookRevisionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TeachingResourceEntity::class, parentColumns = ["resourceId"], childColumns = ["resourceId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["bookRevisionId", "pageId", "resourceId"], unique = true), Index("resourceId")],
)
internal data class BookPageResourceLinkEntity(
    val linkId: String,
    val bookRevisionId: String,
    val pageId: String,
    val resourceId: String,
    val anchorLeft: Float?,
    val anchorTop: Float?,
    val anchorRight: Float?,
    val anchorBottom: Float?,
    val triggerType: String,
    val sortOrder: Int,
    val createdAtEpochMillis: Long,
)

internal data class TeachingResourceSummaryRow(
    val resourceId: String,
    val title: String,
    val resourceType: String,
    val category: String,
    val status: String,
    val currentRevisionId: String?,
    val triggerType: String,
    val sortOrder: Int,
)
