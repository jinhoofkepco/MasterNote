package com.studyink.annotation.storage

import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "import_sessions", primaryKeys = ["importSessionId"], indices = [Index("state"), Index("bookId"), Index("revisionId")])
internal data class ImportSessionEntity(
    val importSessionId: String, val sourceUri: String, val requestedFolderId: String, val detectedSourceType: String?,
    val state: String, val progressCurrent: Long, val progressTotal: Long, val stagingPath: String?, val managedAssetId: String?,
    val packageId: String?, val bookId: String?, val revisionId: String?, val title: String?, val errorCode: String?, val errorDetail: String?,
    val confirmed: Boolean, val createdAtEpochMillis: Long, val updatedAtEpochMillis: Long, val completedAtEpochMillis: Long?,
)
