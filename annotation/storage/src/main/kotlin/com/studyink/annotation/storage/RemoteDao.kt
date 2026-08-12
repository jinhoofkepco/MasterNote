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
}
