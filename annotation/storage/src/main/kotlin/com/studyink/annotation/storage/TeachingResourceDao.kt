package com.studyink.annotation.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
internal interface TeachingResourceDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertResource(entity: TeachingResourceEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertRevision(entity: TeachingResourceRevisionEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertLink(entity: BookPageResourceLinkEntity)
    @Query("SELECT * FROM teaching_resources WHERE resourceId = :id") suspend fun resource(id: String): TeachingResourceEntity?
    @Query("SELECT * FROM teaching_resource_revisions WHERE revisionId = :id") suspend fun revision(id: String): TeachingResourceRevisionEntity?
    @Query("SELECT COALESCE(MAX(revisionNumber), 0) FROM teaching_resource_revisions WHERE resourceId = :resourceId") suspend fun maxRevision(resourceId: String): Int
    @Query("UPDATE teaching_resources SET currentRevisionId = :revisionId, updatedAtEpochMillis = :now WHERE resourceId = :resourceId") suspend fun setCurrentRevision(resourceId: String, revisionId: String, now: Long): Int
    @Query("UPDATE teaching_resources SET status = :status, updatedAtEpochMillis = :now WHERE resourceId = :resourceId") suspend fun setStatus(resourceId: String, status: String, now: Long): Int

    @Query(
        """
        SELECT r.resourceId, r.title, r.resourceType, r.category, r.status, r.currentRevisionId,
               l.triggerType, l.sortOrder
        FROM book_page_resource_links l
        INNER JOIN teaching_resources r ON r.resourceId = l.resourceId
        WHERE l.bookRevisionId = :revisionId AND l.pageId = :pageId AND r.status != 'ARCHIVED'
        ORDER BY l.sortOrder, r.updatedAtEpochMillis DESC
        """
    )
    fun observePageResources(revisionId: String, pageId: String): Flow<List<TeachingResourceSummaryRow>>

    @Transaction suspend fun addRevisionAndSelect(entity: TeachingResourceRevisionEntity, now: Long) {
        insertRevision(entity)
        check(setCurrentRevision(entity.resourceId, entity.revisionId, now) == 1)
    }
}
