package com.studyink.remote.storage

import com.studyink.remote.protocol.RemoteDurableOperation
import com.studyink.remote.protocol.RemoteStrokeAsset
import kotlinx.coroutines.flow.Flow

data class RemoteOutboxEntry(
    val sessionId: String,
    val durableSequence: Long,
    val operationId: String,
    val messageId: String,
    val messageType: String,
    val encodedEnvelope: ByteArray,
    val createdAtEpochMillis: Long,
    val lastSentAtEpochMillis: Long?,
    val sendAttemptCount: Int,
    val acknowledgedAtEpochMillis: Long?,
)

interface RemoteOutboxStore {
    fun observePending(sessionId: String): Flow<List<RemoteOutboxEntry>>
    suspend fun pending(sessionId: String, limit: Int = 64): List<RemoteOutboxEntry>
    suspend fun markSent(sessionId: String, sequence: Long, sentAtEpochMillis: Long)
    suspend fun acknowledgeThrough(sessionId: String, sequence: Long, acknowledgedAtEpochMillis: Long)
    suspend fun deleteAcknowledgedBefore(cutoffEpochMillis: Long): Int
    suspend fun pendingCount(sessionId: String): Int
}

interface RemoteInboxStore {
    suspend fun hasOperation(sessionId: String, operationId: String): Boolean
    suspend fun markApplied(
        sessionId: String,
        sequence: Long,
        messageId: String,
        operationIds: List<String>,
        appliedAtEpochMillis: Long,
    ): Boolean
    suspend fun highestContiguousSequence(sessionId: String): Long
}

data class RemoteReplicaPage(
    val sessionId: String,
    val pageId: String,
    val pageNumber: Int,
    val layerRevision: Long,
    val lastAppliedSequence: Long,
    val lastUpdatedAtEpochMillis: Long,
    val strokes: List<RemoteStrokeAsset>,
)

interface RemoteReplicaStore {
    suspend fun page(sessionId: String, pageId: String): RemoteReplicaPage?
    suspend fun pages(sessionId: String): List<RemoteReplicaPage>
    suspend fun replacePageAtomically(page: RemoteReplicaPage)
    suspend fun applyOperationAtomically(
        sessionId: String,
        durableSequence: Long,
        messageId: String,
        operation: RemoteDurableOperation,
        appliedAtEpochMillis: Long,
    ): Boolean
    suspend fun deleteSession(sessionId: String)
}
