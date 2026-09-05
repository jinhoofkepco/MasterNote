package com.studyink.app

import com.studyink.construction.storage.ConstructionPacketKind
import com.studyink.construction.storage.ConstructionSyncCodec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class ConstructionTelegramWireTest {
    private val address = ConstructionTelegramAddress("pair", 4, "page-token", "workbook-token", "a".repeat(64), 0, 1, UUID.randomUUID().toString())
    @Test fun maximumPacketRoundTripsInBoundedFramesAndArbitraryArrivalOrder() {
        val payload = ByteArray(ConstructionSyncCodec.MAX_PACKET_BYTES) { (it * 31).toByte() }
        val frames = ConstructionTelegramWire.frames(address, payload)
        assertEquals(8, frames.size)
        assertTrue(frames.all { it.second.size <= ConstructionTelegramWire.MAX_FRAME_BYTES })
        val chunks = frames.reversed().map { (id, bytes) -> ConstructionTelegramWire.decode(bytes).also { assertEquals(id, it.transferId) } }
        assertArrayEquals(payload, ConstructionTelegramWire.assemble(chunks + chunks.first()))
        assertNull(ConstructionTelegramWire.assemble(chunks.drop(1)))
    }
    @Test fun newDeliveryAttemptRetriesAfterAnOldTransportAcknowledgement() {
        val payload = "same durable publication request id".toByteArray()
        val attempt = UUID.randomUUID().toString()
        val first = ConstructionTelegramWire.frames(address, payload, attempt)
        val same = ConstructionTelegramWire.frames(address, payload, attempt)
        val retry = ConstructionTelegramWire.frames(address, payload)
        assertEquals(first[0].first, same[0].first)
        assertNotEquals(first[0].first, retry[0].first)
        assertArrayEquals(payload, ConstructionTelegramWire.assemble(retry.map { ConstructionTelegramWire.decode(it.second) }))
    }
    @Test fun malformedCountsOversizedPayloadsAndChangedDigestsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { ConstructionTelegramWire.frames(address, ByteArray(ConstructionSyncCodec.MAX_PACKET_BYTES + 1)) }
        val original = ConstructionTelegramWire.frames(address, ByteArray(9)).single().second
        listOf("count" to 2, "index" to 1, "totalBytes" to 10, "format" to 2, "attemptNo" to 0, "pageNumber" to -1).forEach { (key, value) ->
            val changed = JSONObject(original.toString(Charsets.UTF_8)).put(key, value).toString().toByteArray()
            assertThrows(IllegalArgumentException::class.java) { ConstructionTelegramWire.decode(changed) }
        }
        val chunk = ConstructionTelegramWire.decode(original)
        assertThrows(IllegalArgumentException::class.java) { ConstructionTelegramWire.assemble(listOf(chunk.copy(bytes = ByteArray(9) { 1 }))) }
    }
    @Test fun crossMemoChunksCannotBeAssembledTogether() {
        val a = ConstructionTelegramWire.frames(address, ByteArray(ConstructionTelegramWire.CHUNK_BYTES + 1)).map { ConstructionTelegramWire.decode(it.second) }
        val b = ConstructionTelegramWire.frames(address.copy(memoId = UUID.randomUUID().toString()), ByteArray(ConstructionTelegramWire.CHUNK_BYTES + 1)).map { ConstructionTelegramWire.decode(it.second) }
        assertThrows(IllegalArgumentException::class.java) { ConstructionTelegramWire.assemble(listOf(a[0], b[1])) }
    }
    @Test fun teacherDraftsHaveNoUnsolicitedOutboundPacketKind() {
        assertTrue(constructionPeerMaySend(false, ConstructionPacketKind.REQUEST_STATE))
        assertTrue(constructionPeerMaySend(false, ConstructionPacketKind.PUBLISH))
        assertFalse(constructionPeerMaySend(false, ConstructionPacketKind.STUDENT_SNAPSHOT))
        assertFalse(constructionPeerMaySend(false, ConstructionPacketKind.RESULT))
        assertTrue(constructionPeerMaySend(true, ConstructionPacketKind.STUDENT_SNAPSHOT))
        assertTrue(constructionPeerMaySend(true, ConstructionPacketKind.RESULT))
        assertFalse(constructionPeerMaySend(true, ConstructionPacketKind.PUBLISH))
        assertFalse(constructionPeerMaySend(true, ConstructionPacketKind.REQUEST_STATE))
    }
    @Test fun samePdfDoesNotAuthorizeAnotherPairedStudent() {
        val hash = "a".repeat(64)
        val lanStudentA = ConstructionSyncRuntime.PeerPin(lanDevice = "student-A", contentSha256 = hash)
        val telegramStudentB = ConstructionSyncRuntime.PeerPin(pairId = "pair-B", peerBotId = 22, contentSha256 = hash)
        assertFalse(lanStudentA.accepts(telegramStudentB))
        assertFalse(telegramStudentB.accepts(lanStudentA))
        assertFalse(lanStudentA.accepts(lanStudentA.copy(lanDevice = "student-B")))
        assertTrue(lanStudentA.accepts(lanStudentA.copy()))
        assertFalse(telegramStudentB.accepts(telegramStudentB.copy(peerBotId = 23)))
        assertFalse(telegramStudentB.accepts(telegramStudentB.copy(pairId = "new-pair")))
    }
    @Test fun coalescingRespectsGatewayLimitAndKeepsFreshQueryRepliesSeparate() {
        val scope = "scope".repeat(500)
        assertTrue(constructionCoalesceKey(scope, "latest", 0).length <= 120)
        assertNotEquals(constructionCoalesceKey(scope, "query-id", 0), constructionCoalesceKey(scope, "latest", 0))
        assertNotEquals(constructionCoalesceKey(scope, "query-id", 0), constructionCoalesceKey(scope, "query-id", 1))
    }
    @Test fun slowMultipartDeliveryKeepsItsUnsentSuffixUntilTheAttemptFinishes() {
        val bytes = ByteArray(ConstructionSyncCodec.MAX_PACKET_BYTES)
        val attempt = UUID.randomUUID().toString()
        val original = ConstructionTelegramWire.frames(address, bytes, attempt)
        assertFalse(constructionMayStartNewDelivery(6, false, false))
        val resumed = ConstructionTelegramWire.frames(address, bytes, attempt)
        assertEquals(original.drop(2).map { it.first }, resumed.drop(2).map { it.first })
        assertFalse(constructionMayStartNewDelivery(1, false, true))
        assertFalse(constructionMayStartNewDelivery(0, false, false))
        assertTrue(constructionMayStartNewDelivery(0, true, false))
        assertTrue(constructionMayStartNewDelivery(0, false, true))
        assertNotEquals(original.map { it.first }, ConstructionTelegramWire.frames(address, bytes).map { it.first })
    }
}
