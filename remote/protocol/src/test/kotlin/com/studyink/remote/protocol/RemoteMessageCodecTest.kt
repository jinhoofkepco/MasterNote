package com.studyink.remote.protocol

import com.google.protobuf.CodedOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

class RemoteMessageCodecTest {
    private val codec = ProtobufRemoteMessageCodec()

    @Test fun everyPayloadRoundTrips() {
        payloads().forEachIndexed { index, payload ->
            val envelope = envelope(payload, if (payload is RemoteLiveStrokePreview || payload is RemotePageState || payload is RemoteViewportState) RemoteLane.EPHEMERAL else RemoteLane.DURABLE, index + 1L)
            assertEquals(envelope, codec.decode(codec.encode(envelope)))
        }
    }

    @Test fun unknownFutureFieldDoesNotBreakOldDecoder() {
        val original = codec.encode(envelope(RemotePing(7), RemoteLane.DURABLE, 1))
        val output = ByteArrayOutputStream()
        output.write(original)
        CodedOutputStream.newInstance(output).also { coded ->
            coded.writeString(999, "future")
            coded.flush()
        }
        assertEquals(RemotePing(7), codec.decode(output.toByteArray()).payload)
    }

    @Test fun incompatibleVersionsAndOversizedOrCorruptPayloadsAreRejected() {
        assertEquals(2, negotiateProtocol(1, 2, 2, 3))
        assertThrows(RemoteProtocolException::class.java) { negotiateProtocol(1, 1, 2, 2) }
        assertThrows(RemoteProtocolException::class.java) { codec.decode(ByteArray(MAX_MESSAGE_BYTES + 1)) }
        assertThrows(RemoteProtocolException::class.java) { codec.decode(byteArrayOf(1, 2, 3, 4)) }
        assertThrows(RemoteProtocolException::class.java) {
            codec.encode(envelope(RemoteSessionRejected("x".repeat(MAX_GENERAL_MESSAGE_BYTES)), RemoteLane.DURABLE, 1))
        }
    }

    private fun envelope(payload: RemotePayload, lane: RemoteLane, sequence: Long) = RemoteEnvelope(
        sessionId = "session", senderDeviceId = "device", messageId = "message-$sequence",
        lane = lane, durableSequence = if (lane == RemoteLane.DURABLE) sequence else 0,
        sentElapsedRealtimeMs = 42, payload = payload,
    )

    private fun payloads(): List<RemotePayload> {
        val point = RemoteStrokePoint(1f, 2f, .5f, 3)
        val stroke = RemoteStrokeAsset("stroke", 0, 1, 0xff001122.toInt(), 3f, listOf(point))
        return listOf(
            RemoteHello(1, 1, "1", RemoteDeviceRole.STUDENT, "device", "revision", "hash", 1, "attempt", "page", 0, listOf(RemotePageGeometry("page", 100f, 200f))),
            RemoteHelloAccepted(1, 1), RemoteSessionRejected("no"),
            RemoteDurableOperationBatch(listOf(RemoteDurableOperation("op", RemoteOperationType.REPLACE_STROKES, "page", 2, listOf("old"), listOf(stroke)))),
            RemoteLiveStrokePreview("preview", "page", listOf(point)), RemotePageState("page", 0, 1),
            RemoteViewportState("page", .5f, .5f, 2f, .4f, .6f), RemoteAck(9), RemoteNack(10),
            RemotePageDigest("page", 2, 1, byteArrayOf(1, 2)), RemoteCheckpointRequest("page", 2),
            RemoteCheckpointChunk("snapshot", "page", 2, 0, 1, byteArrayOf(3), byteArrayOf(4)),
            RemotePing(7), RemotePong(7), RemoteProtocolError("bad", "message"),
            RemoteResourceOffer("hash", "image/png", "설명", 3), RemoteResourceNeed("hash"),
            RemoteResourceChunk("transfer", "hash", 0, 1, byteArrayOf(1, 2, 3)), RemoteResourceReady("hash"),
            RemotePresentResource("hash", "설명", "내용", "text/plain"), RemoteDismissResource("hash"),
        )
    }
}
