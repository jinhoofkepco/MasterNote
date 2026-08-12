package com.studyink.annotation.storage

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "remote_outbox",
    primaryKeys = ["sessionId", "durableSequence"],
    indices = [
        Index(value = ["sessionId", "operationId"], unique = true),
        Index(value = ["sessionId", "messageId"], unique = true),
        Index(value = ["sessionId", "acknowledgedAtEpochMillis"]),
    ],
)
internal data class RemoteOutboxEntity(
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

@Entity(
    tableName = "remote_inbox_sequences",
    primaryKeys = ["sessionId", "durableSequence"],
)
internal data class RemoteInboxSequenceEntity(
    val sessionId: String,
    val durableSequence: Long,
    val messageId: String,
    val appliedAtEpochMillis: Long,
)

@Entity(
    tableName = "remote_applied_operations",
    primaryKeys = ["sessionId", "operationId"],
)
internal data class RemoteAppliedOperationEntity(
    val sessionId: String,
    val operationId: String,
    val appliedAtEpochMillis: Long,
)

@Entity(
    tableName = "remote_replica_pages",
    primaryKeys = ["sessionId", "pageId"],
    indices = [Index(value = ["sessionId", "lastUpdatedAtEpochMillis"])],
)
internal data class RemoteReplicaPageEntity(
    val sessionId: String,
    val pageId: String,
    val pageNumber: Int,
    val layerRevision: Long,
    val lastAppliedSequence: Long,
    val lastUpdatedAtEpochMillis: Long,
)

@Entity(
    tableName = "remote_replica_strokes",
    primaryKeys = ["sessionId", "pageId", "strokeId"],
    indices = [Index(value = ["sessionId", "pageId", "zOrder"])],
)
internal data class RemoteReplicaStrokeEntity(
    val sessionId: String,
    val pageId: String,
    val strokeId: String,
    val zOrder: Long,
    val encodedStroke: ByteArray,
)
