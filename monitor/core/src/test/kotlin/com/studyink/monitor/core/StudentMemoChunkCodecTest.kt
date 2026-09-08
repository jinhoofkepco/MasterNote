package com.studyink.monitor.core

import org.junit.Assert.*
import org.junit.Test

class StudentMemoChunkCodecTest {
    @Test fun `ten MiB compact memo travels in small v3 documents and old readers reject before decoding`() {
        val payload = ByteArray(RemoteReviewLimits.MAX_STUDENT_MEMO_ASSEMBLED_BYTES) { (it * 31).toByte() }
        val frames = frames(payload)
        assertEquals(20, frames.size)
        val decoded = frames.map { frame ->
            val wire = RemoteReviewDocumentCodec.encode(frame).copyBytes()
            assertTrue(wire.size < RemoteReviewLimits.OPERATIONAL_DOCUMENT_BYTES)
            assertEquals(3, wire[4].toInt())
            val oldReader = assertThrows(RemoteReviewCodecException::class.java) {
                RemoteReviewDocumentCodec.decodeWithVersionCeiling(wire, 2)
            }
            assertEquals(RemoteReviewCodecError.UNSUPPORTED_VERSION, oldReader.error)
            RemoteReviewDocumentCodec.decode(wire).envelope as StudentMemoEnvelope
        }
        assertArrayEquals(payload, StudentMemoChunks.assemble(decoded))
        assertNull(RemoteReviewExchangeStateMachine.coalesceKey(decoded.first()))
        assertThrows(RemoteReviewValidationException::class.java) {
            StudentMemoChunkInfo("memo_group_001", 0, 21, RemoteReviewLimits.MAX_STUDENT_MEMO_ASSEMBLED_BYTES + 1, "ab".repeat(32))
        }
    }

    @Test fun `missing chunks remain incomplete and shuffled duplicate retry assembles exactly once without changed identities`() {
        val payload = ByteArray(RemoteReviewLimits.STUDENT_MEMO_CHUNK_BYTES * 2 + 17) { (it % 251).toByte() }
        val frames = frames(payload)
        assertNull(StudentMemoChunks.assemble(listOf(frames[2], frames[0], frames[0])))
        assertArrayEquals(payload, StudentMemoChunks.assemble(listOf(frames[2], frames[0], frames[0], frames[1])))
        val changed = payload.copyOf().also { it[0] = 19 }
        assertThrows(IllegalArgumentException::class.java) {
            StudentMemoChunks.assemble(listOf(frames[0], frames(changed)[1], frames[2]))
        }
        val poisoned = payload.copyOf().also { it[0] = 23 }
        val wrongWholeHash = frames(poisoned, studentMemoPayloadSha256Hex(payload))
        assertThrows(IllegalArgumentException::class.java) { StudentMemoChunks.assemble(wrongWholeHash) }
        val small = RemoteReviewDocumentCodec.encode(frames("compact JSON v3".toByteArray()).single()).copyBytes()
        assertEquals(3, small[4].toInt()) // v3 is required even without extended coordinates.
    }

    private fun frames(payload: ByteArray, wholeHash: String = studentMemoPayloadSha256Hex(payload)): List<StudentMemoEnvelope> {
        val count = (payload.size + RemoteReviewLimits.STUDENT_MEMO_CHUNK_BYTES - 1) / RemoteReviewLimits.STUDENT_MEMO_CHUNK_BYTES
        return (0 until count).map { index ->
            val offset = index * RemoteReviewLimits.STUDENT_MEMO_CHUNK_BYTES
            val part = payload.copyOfRange(offset, minOf(payload.size, offset + RemoteReviewLimits.STUDENT_MEMO_CHUNK_BYTES))
            StudentMemoEnvelope(studentMemoChunkTransferId("memo_group_001", index), 1_000L, 3,
                "page_token_001", "workbook_token_001", "11".repeat(32), 1, 1, "memo_id_001", 2,
                "22".repeat(32), studentMemoPayloadSha256Hex(part), part,
                chunk = StudentMemoChunkInfo("memo_group_001", index, count, payload.size, wholeHash))
        }
    }
}
