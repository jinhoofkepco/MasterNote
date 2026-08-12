package com.studyink.annotation.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface RemoteDao {
    @Query("SELECT COALESCE(MAX(durableSequence), 0) FROM remote_outbox WHERE sessionId = :sessionId")
    suspend fun maxOutboxSequence(sessionId: String): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutbox(entity: RemoteOutboxEntity)

    @Query("SELECT * FROM remote_outbox WHERE sessionId = :sessionId AND acknowledgedAtEpochMillis IS NULL ORDER BY durableSequence LIMIT :limit")
    suspend fun pending(sessionId: String, limit: Int): List<RemoteOutboxEntity>

    @Query("SELECT * FROM remote_outbox WHERE sessionId = :sessionId AND acknowledgedAtEpochMillis IS NULL ORDER BY durableSequence")
    fun observePending(sessionId: String): Flow<List<RemoteOutboxEntity>>

    @Query("UPDATE remote_outbox SET lastSentAtEpochMillis = :sentAt, sendAttemptCount = sendAttemptCount + 1 WHERE sessionId = :sessionId AND durableSequence = :sequence AND acknowledgedAtEpochMillis IS NULL")
    suspend fun markSent(sessionId: String, sequence: Long, sentAt: Long): Int

    @Query("UPDATE remote_outbox SET acknowledgedAtEpochMillis = :acknowledgedAt WHERE sessionId = :sessionId AND durableSequence <= :sequence AND acknowledgedAtEpochMillis IS NULL")
    suspend fun acknowledgeThrough(sessionId: String, sequence: Long, acknowledgedAt: Long): Int

    @Query("DELETE FROM remote_outbox WHERE acknowledgedAtEpochMillis IS NOT NULL AND acknowledgedAtEpochMillis < :cutoff")
    suspend fun deleteAcknowledgedBefore(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM remote_outbox WHERE sessionId = :sessionId AND acknowledgedAtEpochMillis IS NULL")
    suspend fun pendingCount(sessionId: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM remote_applied_operations WHERE sessionId = :sessionId AND operationId = :operationId)")
    suspend fun hasOperation(sessionId: String, operationId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInboxSequence(entity: RemoteInboxSequenceEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAppliedOperations(entities: List<RemoteAppliedOperationEntity>): List<Long>

    @Query("SELECT COALESCE(MAX(durableSequence), 0) FROM remote_inbox_sequences WHERE sessionId = :sessionId")
    suspend fun maxInboxSequence(sessionId: String): Long

    @Query("SELECT * FROM remote_replica_pages WHERE sessionId = :sessionId AND pageId = :pageId")
    suspend fun replicaPage(sessionId: String, pageId: String): RemoteReplicaPageEntity?

    @Query("SELECT * FROM remote_replica_pages WHERE sessionId = :sessionId ORDER BY pageNumber")
    suspend fun replicaPages(sessionId: String): List<RemoteReplicaPageEntity>

    @Query("SELECT * FROM remote_replica_strokes WHERE sessionId = :sessionId AND pageId = :pageId ORDER BY zOrder")
    suspend fun replicaStrokes(sessionId: String, pageId: String): List<RemoteReplicaStrokeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReplicaPage(entity: RemoteReplicaPageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertReplicaStrokes(entities: List<RemoteReplicaStrokeEntity>)

    @Query("DELETE FROM remote_replica_strokes WHERE sessionId = :sessionId AND pageId = :pageId")
    suspend fun deleteReplicaPageStrokes(sessionId: String, pageId: String)

    @Query("DELETE FROM remote_replica_strokes WHERE sessionId = :sessionId AND pageId = :pageId AND strokeId IN (:strokeIds)")
    suspend fun deleteReplicaStrokes(sessionId: String, pageId: String, strokeIds: List<String>)

    @Query("SELECT COALESCE(MAX(zOrder), -1) FROM remote_replica_strokes WHERE sessionId = :sessionId AND pageId = :pageId")
    suspend fun maxReplicaZOrder(sessionId: String, pageId: String): Long

    @Query("DELETE FROM remote_replica_strokes WHERE sessionId = :sessionId")
    suspend fun deleteReplicaSessionStrokes(sessionId: String)

    @Query("DELETE FROM remote_replica_pages WHERE sessionId = :sessionId")
    suspend fun deleteReplicaSessionPages(sessionId: String)
}
