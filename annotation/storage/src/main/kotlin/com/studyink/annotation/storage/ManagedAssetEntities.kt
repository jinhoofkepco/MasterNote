package com.studyink.annotation.storage

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "managed_assets",
    indices = [
        Index(value = ["sha256"], unique = true),
        Index(value = ["createdAtEpochMillis"]),
    ],
)
internal data class ManagedAssetEntity(
    @PrimaryKey val assetId: String,
    val sha256: String,
    val mimeType: String,
    val originalFileName: String,
    val byteSize: Long,
    val relativePath: String,
    val widthPx: Int?,
    val heightPx: Int?,
    val pageCount: Int?,
    val createdAtEpochMillis: Long,
    val lastVerifiedAtEpochMillis: Long,
)
