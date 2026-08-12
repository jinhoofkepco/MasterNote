package com.studyink.annotation.storage

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao internal interface LibraryDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertFolder(value: LibraryFolderEntity)
    @Update suspend fun updateFolder(value: LibraryFolderEntity)
    @Query("SELECT * FROM library_folders") suspend fun allFolders(): List<LibraryFolderEntity>
    @Query("SELECT * FROM library_folders WHERE folderId=:id") suspend fun folder(id: String): LibraryFolderEntity?
    @Query("SELECT * FROM library_folders WHERE parentFolderId IS :parent AND deletedAtEpochMillis IS NULL ORDER BY position") fun observeFolders(parent: String?): Flow<List<LibraryFolderEntity>>
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM library_folders WHERE parentFolderId IS :parent AND deletedAtEpochMillis IS NULL") suspend fun nextFolderPosition(parent: String?): Int
    @Query("SELECT COUNT(*) FROM library_folders WHERE parentFolderId=:id AND deletedAtEpochMillis IS NULL") suspend fun activeChildCount(id: String): Int
    @Query("SELECT COUNT(*) FROM book_placements WHERE folderId=:id") suspend fun placedBookCount(id: String): Int
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertBook(value: LibraryBookEntity)
    @Update suspend fun updateBook(value: LibraryBookEntity)
    @Query("SELECT * FROM library_books WHERE bookId=:id") suspend fun book(id: String): LibraryBookEntity?
    @Query("SELECT * FROM library_books WHERE currentRevisionId=:revisionId LIMIT 1") suspend fun bookByRevision(revisionId: String): LibraryBookEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRevisionSource(value: LibraryRevisionSourceEntity)
    @Query("SELECT * FROM library_revision_sources WHERE revisionId=:revisionId") suspend fun revisionSource(revisionId: String): LibraryRevisionSourceEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun placeBook(value: BookPlacementEntity)
    @Query("SELECT * FROM book_placements WHERE bookId=:bookId") suspend fun placement(bookId: String): BookPlacementEntity?
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM book_placements WHERE folderId=:folderId") suspend fun nextBookPosition(folderId: String): Int
    @Query("SELECT b.* FROM library_books b INNER JOIN book_placements p ON p.bookId=b.bookId WHERE p.folderId=:folderId AND b.status!='TRASHED' ORDER BY p.position") fun observeBooks(folderId: String): Flow<List<LibraryBookEntity>>
}
