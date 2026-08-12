package com.studyink.annotation.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "library_folders", primaryKeys = ["folderId"], indices = [Index("parentFolderId"), Index(value = ["parentFolderId", "normalizedName"], unique = true)])
internal data class LibraryFolderEntity(
    val folderId: String, val parentFolderId: String?, val displayName: String, val normalizedName: String,
    val position: Int, val createdAtEpochMillis: Long, val updatedAtEpochMillis: Long, val deletedAtEpochMillis: Long?,
)

@Entity(tableName = "library_books", primaryKeys = ["bookId"], indices = [Index("currentRevisionId")])
internal data class LibraryBookEntity(
    val bookId: String, val title: String, val subtitle: String?, val coverAssetId: String?, val currentRevisionId: String,
    val status: String, val createdAtEpochMillis: Long, val updatedAtEpochMillis: Long, val archivedAtEpochMillis: Long?,
)

@Entity(
    tableName = "book_placements", primaryKeys = ["bookId"],
    foreignKeys = [
        ForeignKey(entity = LibraryBookEntity::class, parentColumns = ["bookId"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = LibraryFolderEntity::class, parentColumns = ["folderId"], childColumns = ["folderId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("folderId"), Index(value = ["folderId", "position"], unique = true)],
)
internal data class BookPlacementEntity(val bookId: String, val folderId: String, val position: Int, val placedAtEpochMillis: Long)
