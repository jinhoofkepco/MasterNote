package com.studyink.annotation.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
internal interface AnswerDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDocument(entity: AnswerDocumentEntity)

    @Query("SELECT * FROM answer_documents WHERE answerDocumentId = :id")
    suspend fun document(id: String): AnswerDocumentEntity?

    @Query("SELECT * FROM answer_documents WHERE bookRevisionId = :revisionId AND isActive = 1 ORDER BY linkedAtEpochMillis")
    fun observeDocuments(revisionId: String): Flow<List<AnswerDocumentEntity>>

    @Query("SELECT * FROM answer_documents WHERE bookRevisionId = :revisionId AND isActive = 1 ORDER BY linkedAtEpochMillis")
    suspend fun documents(revisionId: String): List<AnswerDocumentEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLink(entity: AnswerPageLinkEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLinks(entities: List<AnswerPageLinkEntity>)

    @Query("DELETE FROM answer_page_links WHERE linkId = :linkId")
    suspend fun deleteLink(linkId: String): Int

    @Query(
        """
        SELECT * FROM answer_page_links
        WHERE bookRevisionId = :revisionId AND answerDocumentId = :documentId
          AND problemPageId = :pageId
        ORDER BY sortOrder, createdAtEpochMillis
        """
    )
    suspend fun pageLinks(revisionId: String, documentId: String, pageId: String): List<AnswerPageLinkEntity>

    @Query(
        """
        SELECT * FROM answer_page_links
        WHERE bookRevisionId = :revisionId AND answerDocumentId = :documentId
          AND activityId = :activityId AND problemPageId IS NULL
        ORDER BY sortOrder, createdAtEpochMillis
        """
    )
    suspend fun activityLinks(revisionId: String, documentId: String, activityId: String): List<AnswerPageLinkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBookmark(entity: AnswerBookmarkEntity)

    @Query("SELECT * FROM answer_bookmarks WHERE teacherId = :teacherId AND answerDocumentId = :documentId")
    suspend fun bookmark(teacherId: String, documentId: String): AnswerBookmarkEntity?

    @Transaction
    suspend fun insertValidatedLinks(entities: List<AnswerPageLinkEntity>) {
        insertLinks(entities)
    }
}
