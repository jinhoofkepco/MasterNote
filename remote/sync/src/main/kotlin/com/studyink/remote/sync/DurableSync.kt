package com.studyink.remote.sync

import com.studyink.remote.protocol.ProtobufRemoteMessageCodec
import com.studyink.remote.protocol.RemoteDurableOperation
import com.studyink.remote.protocol.RemoteDurableOperationBatch
import com.studyink.remote.protocol.RemoteEnvelope
import com.studyink.remote.protocol.RemoteMessageCodec
import com.studyink.remote.storage.RemoteInboxStore
import com.studyink.remote.storage.RemoteOutboxStore
import com.studyink.remote.transport.RemoteTransport
import java.util.TreeMap

const val MAX_UNACKED_WINDOW = 64
const val MAX_BATCH_OPERATIONS = 20
const val MAX_GAP_BUFFER = 32

sealed interface DurableReceiveResult {
    data class Acknowledged(val highestContiguousSequence: Long) : DurableReceiveResult
    data class Missing(val expectedSequence: Long) : DurableReceiveResult
    data class CheckpointRequired(val pageId: String?) : DurableReceiveResult
}

class DurableReceiver(
    private val sessionId: String,
    private val inbox: RemoteInboxStore,
    private val applyOperation: suspend (RemoteDurableOperation) -> Unit,
    private val nowEpochMillis: () -> Long,
    private val codec: RemoteMessageCodec = ProtobufRemoteMessageCodec(),
) {
    private val gaps = TreeMap<Long, RemoteEnvelope>()

    suspend fun receive(bytes: ByteArray): DurableReceiveResult {
        val envelope = codec.decode(bytes)
        require(envelope.sessionId == sessionId && envelope.durableSequence > 0)
        val expected = inbox.highestContiguousSequence(sessionId) + 1L
        if (envelope.durableSequence < expected) return DurableReceiveResult.Acknowledged(expected - 1L)
        if (envelope.durableSequence > expected) {
            gaps.putIfAbsent(envelope.durableSequence, envelope)
            if (gaps.size > MAX_GAP_BUFFER) {
                val page = (envelope.payload as? RemoteDurableOperationBatch)?.operations?.firstOrNull()?.pageId
                gaps.clear()
                return DurableReceiveResult.CheckpointRequired(page)
            }
            return DurableReceiveResult.Missing(expected)
        }
        applyEnvelope(envelope)
        var contiguous = envelope.durableSequence
        while (true) {
            val next = gaps.remove(contiguous + 1L) ?: break
            applyEnvelope(next)
            contiguous++
        }
        return DurableReceiveResult.Acknowledged(contiguous)
    }

    private suspend fun applyEnvelope(envelope: RemoteEnvelope) {
        val batch = envelope.payload as? RemoteDurableOperationBatch
            ?: error("Durable lane requires an operation batch")
        val newlyApplied = mutableListOf<String>()
        batch.operations.forEach { operation ->
            if (!inbox.hasOperation(sessionId, operation.operationId)) {
                applyOperation(operation)
                newlyApplied += operation.operationId
            }
        }
        check(inbox.markApplied(
            sessionId, envelope.durableSequence, envelope.messageId,
            newlyApplied, nowEpochMillis(),
        ))
    }
}

class DurableOutboxSender(
    private val sessionId: String,
    private val endpointId: () -> String?,
    private val outbox: RemoteOutboxStore,
    private val transport: RemoteTransport,
    private val nowEpochMillis: () -> Long,
) {
    suspend fun sendWindow(): Int {
        val endpoint = endpointId() ?: return 0
        val entries = outbox.pending(sessionId, MAX_UNACKED_WINDOW)
        entries.forEach { entry ->
            transport.send(endpoint, entry.encodedEnvelope)
            outbox.markSent(sessionId, entry.durableSequence, nowEpochMillis())
        }
        return entries.size
    }

    suspend fun acknowledge(highestContiguousSequence: Long) {
        outbox.acknowledgeThrough(sessionId, highestContiguousSequence, nowEpochMillis())
    }
}
