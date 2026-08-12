package com.studyink.remote.sync

import com.studyink.remote.protocol.ProtobufRemoteMessageCodec
import com.studyink.remote.protocol.RemoteDurableOperation
import com.studyink.remote.protocol.RemoteDurableOperationBatch
import com.studyink.remote.protocol.RemoteEnvelope
import com.studyink.remote.protocol.RemoteLane
import com.studyink.remote.protocol.RemoteOperationType
import com.studyink.remote.storage.RemoteInboxStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableSyncTest {
    @Test fun orderedGapAndDuplicateDeliveryHaveEffectivelyOnceResults() = runTest {
        val inbox = MemoryInbox()
        val applied = mutableListOf<String>()
        val receiver = DurableReceiver("session", inbox, { applied += it.operationId }, { 1 })

        assertEquals(DurableReceiveResult.Acknowledged(1), receiver.receive(message(1, "op-1")))
        assertEquals(DurableReceiveResult.Missing(2), receiver.receive(message(3, "op-3")))
        assertEquals(DurableReceiveResult.Acknowledged(3), receiver.receive(message(2, "op-2")))
        assertEquals(DurableReceiveResult.Acknowledged(3), receiver.receive(message(2, "op-2")))
        assertEquals(listOf("op-1", "op-2", "op-3"), applied)
    }

    @Test fun sameOperationUnderDifferentMessageIdAppliesOnceAndLargeGapRequestsCheckpoint() = runTest {
        val inbox = MemoryInbox()
        val applied = mutableListOf<String>()
        val receiver = DurableReceiver("session", inbox, { applied += it.operationId }, { 1 })
        receiver.receive(message(1, "same", "first"))
        receiver.receive(message(2, "same", "second"))
        assertEquals(listOf("same"), applied)
        (4L..35L).forEach { receiver.receive(message(it, "gap-$it")) }
        assertTrue(receiver.receive(message(36, "overflow")) is DurableReceiveResult.CheckpointRequired)
    }

    private fun message(sequence: Long, operationId: String, messageId: String = "message-$sequence"): ByteArray =
        ProtobufRemoteMessageCodec().encode(RemoteEnvelope(
            sessionId = "session", senderDeviceId = "student", messageId = messageId,
            lane = RemoteLane.DURABLE, durableSequence = sequence, sentElapsedRealtimeMs = 1,
            payload = RemoteDurableOperationBatch(listOf(RemoteDurableOperation(
                operationId, RemoteOperationType.ADD_STROKE, "page", sequence,
            ))),
        ))

    private class MemoryInbox : RemoteInboxStore {
        private val operations = mutableSetOf<String>()
        private var highest = 0L
        override suspend fun hasOperation(sessionId: String, operationId: String) = operationId in operations
        override suspend fun markApplied(sessionId: String, sequence: Long, messageId: String, operationIds: List<String>, appliedAtEpochMillis: Long): Boolean {
            operations += operationIds
            highest = maxOf(highest, sequence)
            return true
        }
        override suspend fun highestContiguousSequence(sessionId: String) = highest
    }
}
