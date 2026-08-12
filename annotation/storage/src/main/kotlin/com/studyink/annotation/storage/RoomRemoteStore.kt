package com.studyink.annotation.storage

import android.content.Context
import androidx.room.withTransaction
import com.studyink.remote.storage.RemoteInboxStore
import com.studyink.remote.storage.RemoteOutboxEntry
import com.studyink.remote.storage.RemoteOutboxStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomRemoteStore internal constructor(
    private val database: AnnotationDatabase,
) : RemoteOutboxStore, RemoteInboxStore {
    private val dao = database.remoteDao()

    override fun observePending(sessionId: String): Flow<List<RemoteOutboxEntry>> =
        dao.observePending(sessionId).map { rows -> rows.map(RemoteOutboxEntity::toDomain) }

    override suspend fun pending(sessionId: String, limit: Int): List<RemoteOutboxEntry> = withContext(Dispatchers.IO) {
        dao.pending(sessionId, limit).map(RemoteOutboxEntity::toDomain)
    }

    override suspend fun markSent(sessionId: String, sequence: Long, sentAtEpochMillis: Long) = withContext(Dispatchers.IO) {
        check(dao.markSent(sessionId, sequence, sentAtEpochMillis) == 1)
    }

    override suspend fun acknowledgeThrough(sessionId: String, sequence: Long, acknowledgedAtEpochMillis: Long) = withContext(Dispatchers.IO) {
        dao.acknowledgeThrough(sessionId, sequence, acknowledgedAtEpochMillis)
        Unit
    }

    override suspend fun deleteAcknowledgedBefore(cutoffEpochMillis: Long): Int = withContext(Dispatchers.IO) {
        dao.deleteAcknowledgedBefore(cutoffEpochMillis)
    }

    override suspend fun pendingCount(sessionId: String): Int = withContext(Dispatchers.IO) { dao.pendingCount(sessionId) }
    override suspend fun hasOperation(sessionId: String, operationId: String): Boolean = withContext(Dispatchers.IO) {
        dao.hasOperation(sessionId, operationId)
    }

    override suspend fun markApplied(
        sessionId: String,
        sequence: Long,
        messageId: String,
        operationIds: List<String>,
        appliedAtEpochMillis: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        database.withTransaction {
            val inserted = dao.insertInboxSequence(
                RemoteInboxSequenceEntity(sessionId, sequence, messageId, appliedAtEpochMillis)
            ) != -1L
            if (inserted) {
                dao.insertAppliedOperations(operationIds.distinct().map { operationId ->
                    RemoteAppliedOperationEntity(sessionId, operationId, appliedAtEpochMillis)
                })
            }
            inserted
        }
    }

    override suspend fun highestContiguousSequence(sessionId: String): Long = withContext(Dispatchers.IO) {
        dao.maxInboxSequence(sessionId)
    }

    fun close() = database.close()

    companion object {
        fun open(context: Context) = RoomRemoteStore(AnnotationDatabase.open(context))
    }
}

private fun RemoteOutboxEntity.toDomain() = RemoteOutboxEntry(
    sessionId, durableSequence, operationId, messageId, messageType, encodedEnvelope,
    createdAtEpochMillis, lastSentAtEpochMillis, sendAttemptCount, acknowledgedAtEpochMillis,
)
