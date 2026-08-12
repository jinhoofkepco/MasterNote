package com.studyink.remote.simulator

import com.studyink.remote.protocol.ProtobufRemoteMessageCodec
import com.studyink.remote.protocol.RemoteDurableOperation
import com.studyink.remote.protocol.RemoteDurableOperationBatch
import com.studyink.remote.protocol.RemoteEnvelope
import com.studyink.remote.protocol.RemoteLane
import com.studyink.remote.protocol.RemoteOperationType
import com.studyink.remote.protocol.RemoteStrokePoint
import com.studyink.remote.protocol.RemotePageState
import com.studyink.remote.storage.RemoteInboxStore
import com.studyink.remote.sync.DurableReceiveResult
import com.studyink.remote.sync.DurableReceiver
import com.studyink.remote.sync.MAX_GAP_BUFFER
import com.studyink.remote.sync.MAX_PREVIEW_POINTS
import com.studyink.remote.sync.MAX_UNACKED_WINDOW
import com.studyink.remote.sync.RemoteLivePublisher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSoakTest {
    @Test fun tenThousandDurableStrokesStayOrderedAndEffectivelyOnceAcrossRetries() = runTest {
        val inbox = MemoryInbox()
        var effects = 0
        val receiver = DurableReceiver(SESSION, inbox, { effects++ }, { 1 })
        repeat(10_000) { index ->
            val sequence = index + 1L
            val bytes = message(sequence, "stroke-$index", RemoteOperationType.ADD_STROKE)
            assertEquals(DurableReceiveResult.Acknowledged(sequence), receiver.receive(bytes))
            if (index % 100 == 0) receiver.receive(bytes) // ACK loss / at-least-once retry
        }
        assertEquals(10_000, effects)
        assertEquals(10_000L, inbox.highestContiguousSequence(SESSION))
    }

    @Test fun oneThousandPartialErasesSurviveDuplicateDelivery() = runTest {
        val inbox = MemoryInbox()
        var effects = 0
        val receiver = DurableReceiver(SESSION, inbox, { effects++ }, { 1 })
        repeat(1_000) { index ->
            val bytes = message(index + 1L, "replace-$index", RemoteOperationType.REPLACE_STROKES)
            receiver.receive(bytes); receiver.receive(bytes); receiver.receive(bytes)
        }
        assertEquals(1_000, effects)
    }

    @Test fun oneHundredDisconnectCyclesRecoverWithoutUnboundedGapState() = runTest {
        val inbox = MemoryInbox()
        var effects = 0
        val receiver = DurableReceiver(SESSION, inbox, { effects++ }, { 1 })
        repeat(100) { cycle ->
            val first = cycle * 10 + 1L
            // Receive later messages first, as can happen after a reconnect batch retry.
            (first + 5..first + 9).forEach { receiver.receive(message(it, "op-$it", RemoteOperationType.ADD_STROKE)) }
            (first..first + 4).forEach { receiver.receive(message(it, "op-$it", RemoteOperationType.ADD_STROKE)) }
        }
        assertEquals(1_000, effects)
        assertEquals(1_000L, inbox.highestContiguousSequence(SESSION))
        assertTrue(MAX_GAP_BUFFER == 32 && MAX_UNACKED_WINDOW == 64)
    }

    @Test fun virtualTwoHourPreviewAndPageChurnKeepsOneLatestItem() {
        val publisher = RemoteLivePublisher()
        repeat(72_000) { tick -> // 100 ms ticks = two virtual hours
            publisher.offerStroke("preview-${tick / 10}", "page-${tick % 20}", List(120) {
                RemoteStrokePoint(it.toFloat(), tick.toFloat(), .5f, it.toLong())
            }, tick * 100L)
            if (tick % 10 == 0) publisher.updatePage(RemotePageState("page-${tick % 20}", tick % 20, tick.toLong()))
        }
        assertEquals(MAX_PREVIEW_POINTS, publisher.preview.value?.points?.size)
        assertEquals(1, listOfNotNull(publisher.preview.value).size)
        assertEquals(1, listOfNotNull(publisher.pageState.value).size)
    }

    private fun message(sequence: Long, operationId: String, type: RemoteOperationType) =
        ProtobufRemoteMessageCodec().encode(RemoteEnvelope(
            sessionId = SESSION, senderDeviceId = "student", messageId = "message-$sequence",
            lane = RemoteLane.DURABLE, durableSequence = sequence, sentElapsedRealtimeMs = sequence,
            payload = RemoteDurableOperationBatch(listOf(RemoteDurableOperation(
                operationId, type, "page", sequence,
            ))),
        ))

    private class MemoryInbox : RemoteInboxStore {
        private val operations = mutableSetOf<String>()
        private var highest = 0L
        override suspend fun hasOperation(sessionId: String, operationId: String) = operationId in operations
        override suspend fun markApplied(sessionId: String, sequence: Long, messageId: String, operationIds: List<String>, appliedAtEpochMillis: Long): Boolean {
            operations += operationIds; highest = sequence; return true
        }
        override suspend fun highestContiguousSequence(sessionId: String) = highest
    }

    private companion object { const val SESSION = "soak-session" }
}
