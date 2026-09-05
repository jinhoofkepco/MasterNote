package com.studyink.sync.lan

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConstructionLanWireTest {
    @Test fun maximumPacketCrosses32BoundedFrames() {
        val payload = ByteArray(ConstructionLanBridge.MAX_PACKET_BYTES) { it.toByte() }
        val frames = ConstructionLanWire.frames("local-book", payload).map(::JSONObject).toList()
        assertEquals(32, frames.size)
        val assembly = ConstructionLanAssembly()
        frames.dropLast(1).forEach { assertNull(assembly.accept(it, "local-book", 1, 10)) }
        assertArrayEquals(payload, assembly.accept(frames.last(), "local-book", 1, 20))
    }
    @Test fun sourceSessionTtlSizeAndChangedChunksFailClosed() {
        val frames = ConstructionLanWire.frames("book", ByteArray(ConstructionLanWire.CHUNK_BYTES + 1)).map(::JSONObject).toList()
        val a = ConstructionLanAssembly()
        assertThrows(IllegalArgumentException::class.java) { a.accept(frames[0], "another-book", 1, 0) }
        assertNull(a.accept(frames[0], "book", 1, 0))
        assertThrows(IllegalArgumentException::class.java) { a.accept(frames[1], "book", 2, 0) }
        assertThrows(IllegalArgumentException::class.java) { a.accept(frames[1], "book", 1, 60_000) }
        assertThrows(IllegalArgumentException::class.java) { ConstructionLanWire.frames("book", ByteArray(ConstructionLanBridge.MAX_PACKET_BYTES + 1)) }
        val bad = JSONObject(frames[0].toString()).put("chunkCount", 32)
        assertThrows(IllegalArgumentException::class.java) { a.accept(bad, "book", 1, 61_000) }
    }
    @Test fun oldTransportCloseCannotUnregisterReplacement() {
        val p1 = ConstructionLanPeer("book", "a".repeat(64), "one", true, 1)
        val p2 = p1.copy(peerDeviceId = "two", sessionId = 2)
        fun transport(peer: ConstructionLanPeer) = object : ConstructionLanBridge.Transport {
            override fun peer(bookId: String) = peer.takeIf { it.localBookId == bookId }
            override fun send(bookId: String, payload: ByteArray, expectedPeer: ConstructionLanPeer) = expectedPeer == peer
        }
        val old = ConstructionLanBridge.registerTransport(transport(p1))
        val current = ConstructionLanBridge.registerTransport(transport(p2))
        try { old.close(); assertEquals(p2, ConstructionLanBridge.peer("book")) } finally { current.close() }
        assertNull(ConstructionLanBridge.peer("book"))
    }
    @Test fun reconnectBetweenPeerSelectionAndSendCannotChangePublicationRecipient() {
        val original = ConstructionLanPeer("book", "a".repeat(64), "student-A", true, 5)
        var connected = original
        var writes = 0
        val registration = ConstructionLanBridge.registerTransport(object : ConstructionLanBridge.Transport {
            override fun peer(bookId: String) = connected
            override fun send(bookId: String, payload: ByteArray, expectedPeer: ConstructionLanPeer): Boolean {
                if (connected != expectedPeer) return false
                writes++
                return true
            }
        })
        try {
            val selected = requireNotNull(ConstructionLanBridge.peer("book"))
            connected = original.copy(peerDeviceId = "student-B", sessionId = 6)
            assertFalse(ConstructionLanBridge.send("book", byteArrayOf(1), selected))
            assertEquals(0, writes)
            assertTrue(ConstructionLanBridge.send("book", byteArrayOf(1), connected))
            assertEquals(1, writes)
        } finally { registration.close() }
    }
}
