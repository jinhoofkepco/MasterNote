package com.studyink.annotation.storage

import android.content.Context
import androidx.room.withTransaction
import com.studyink.remote.protocol.RemoteOperationType
import com.studyink.remote.protocol.RemoteStrokeAssetListCodec
import com.studyink.remote.storage.RemoteInboxStore
import com.studyink.remote.storage.RemoteOutboxEntry
import com.studyink.remote.storage.RemoteOutboxStore
import com.studyink.remote.storage.RemoteReplicaPage
import com.studyink.remote.storage.RemoteReplicaStore
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

class RoomRemoteReplicaStore internal constructor(
    private val database: AnnotationDatabase,
) : RemoteReplicaStore {
    private val dao = database.remoteDao()

    override suspend fun page(sessionId: String, pageId: String): RemoteReplicaPage? = withContext(Dispatchers.IO) {
        dao.replicaPage(sessionId, pageId)?.toDomain(dao.replicaStrokes(sessionId, pageId))
    }

    override suspend fun pages(sessionId: String): List<RemoteReplicaPage> = withContext(Dispatchers.IO) {
        dao.replicaPages(sessionId).map { it.toDomain(dao.replicaStrokes(sessionId, it.pageId)) }
    }

    override suspend fun replacePageAtomically(page: RemoteReplicaPage) = withContext(Dispatchers.IO) {
        database.withTransaction {
            dao.deleteReplicaPageStrokes(page.sessionId, page.pageId)
            dao.upsertReplicaPage(page.toEntity())
            dao.upsertReplicaStrokes(page.strokes.mapIndexed { index, stroke ->
                RemoteReplicaStrokeEntity(
                    page.sessionId, page.pageId, stroke.strokeId, index.toLong(),
                    RemoteStrokeAssetListCodec.encode(listOf(stroke)),
                )
            })
        }
    }

    override suspend fun applyOperationAtomically(
        sessionId: String,
        durableSequence: Long,
        messageId: String,
        operation: com.studyink.remote.protocol.RemoteDurableOperation,
        appliedAtEpochMillis: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        database.withTransaction {
            val newOperation = !dao.hasOperation(sessionId, operation.operationId)
            if (newOperation) {
                if (operation.removedStrokeIds.isNotEmpty()) {
                    dao.deleteReplicaStrokes(sessionId, operation.pageId, operation.removedStrokeIds)
                }
                if (operation.type != RemoteOperationType.SESSION_CONTROL && operation.addedStrokes.isNotEmpty()) {
                    var z = dao.maxReplicaZOrder(sessionId, operation.pageId) + 1L
                    dao.upsertReplicaStrokes(operation.addedStrokes.map { stroke ->
                        RemoteReplicaStrokeEntity(
                            sessionId, operation.pageId, stroke.strokeId, z++,
                            RemoteStrokeAssetListCodec.encode(listOf(stroke)),
                        )
                    })
                }
                val existing = dao.replicaPage(sessionId, operation.pageId)
                val pageNumber = operation.addedStrokes.firstOrNull()?.pageNumber ?: existing?.pageNumber ?: 0
                dao.upsertReplicaPage(RemoteReplicaPageEntity(
                    sessionId, operation.pageId, pageNumber, operation.pageRevision,
                    durableSequence, appliedAtEpochMillis,
                ))
            }
            val sequenceInserted = dao.insertInboxSequence(
                RemoteInboxSequenceEntity(sessionId, durableSequence, messageId, appliedAtEpochMillis)
            ) != -1L
            if (newOperation) dao.insertAppliedOperations(listOf(
                RemoteAppliedOperationEntity(sessionId, operation.operationId, appliedAtEpochMillis)
            ))
            sequenceInserted
        }
    }

    override suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            dao.deleteReplicaSessionStrokes(sessionId)
            dao.deleteReplicaSessionPages(sessionId)
        }
    }

    fun close() = database.close()

    companion object {
        fun open(context: Context) = RoomRemoteReplicaStore(AnnotationDatabase.open(context))
    }
}

private fun RemoteOutboxEntity.toDomain() = RemoteOutboxEntry(
    sessionId, durableSequence, operationId, messageId, messageType, encodedEnvelope,
    createdAtEpochMillis, lastSentAtEpochMillis, sendAttemptCount, acknowledgedAtEpochMillis,
)

private fun RemoteReplicaPageEntity.toDomain(strokes: List<RemoteReplicaStrokeEntity>) = RemoteReplicaPage(
    sessionId, pageId, pageNumber, layerRevision, lastAppliedSequence, lastUpdatedAtEpochMillis,
    strokes.mapNotNull { runCatching { RemoteStrokeAssetListCodec.decode(it.encodedStroke).single() }.getOrNull() },
)

private fun RemoteReplicaPage.toEntity() = RemoteReplicaPageEntity(
    sessionId, pageId, pageNumber, layerRevision, lastAppliedSequence, lastUpdatedAtEpochMillis,
)
