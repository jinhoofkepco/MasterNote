package com.studyink.annotation.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "assistant_prompt_templates", primaryKeys = ["templateId"])
internal data class AssistantPromptTemplateEntity(
    val templateId: String,
    val name: String,
    val category: String,
    val promptBody: String,
    val outputPreference: String,
    val version: Int,
)

@Entity(
    tableName = "assistant_jobs",
    primaryKeys = ["assistantJobId"],
    foreignKeys = [
        ForeignKey(entity = BookRevisionEntity::class, parentColumns = ["revisionId"], childColumns = ["bookRevisionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = ManagedAssetEntity::class, parentColumns = ["assetId"], childColumns = ["requestImageAssetId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("bookRevisionId"), Index("pageId"), Index("requestImageAssetId"), Index("status")],
)
internal data class AssistantJobEntity(
    val assistantJobId: String,
    val bookRevisionId: String,
    val pageId: String,
    val selectionLeft: Float,
    val selectionTop: Float,
    val selectionRight: Float,
    val selectionBottom: Float,
    val requestType: String,
    val promptTemplateId: String,
    val promptText: String,
    val requestImageAssetId: String?,
    val providerType: String,
    val status: String,
    val includeStudentInk: Boolean,
    val includeTeacherFeedback: Boolean,
    val createdAtEpochMillis: Long,
    val externalOpenedAtEpochMillis: Long?,
    val resultImportedAtEpochMillis: Long?,
)
