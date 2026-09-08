package com.studyink.app

import com.studyink.monitor.core.*
import com.studyink.core.model.MasterNoteDataRootBus
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StudentMemoTelegramReceiptsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `completion proof survives restart and partial ACK but cannot cross peer root or memo identity`() {
        val root = temporary.newFolder()
        val receipt = StudentMemoTelegramReceipts(root)
        val first = fragment(0)
        val remaining = fragment(1)
        assertFalse(receipt.contains("pair-one:123:local-book", first))
        // The runtime calls record only AFTER complete assembly and durable memo application.
        receipt.record("pair-one:123:local-book", first)
        MasterNoteDataRootBus.dataRootReplaced() // Process-local counters must not enter durable keys.
        val reopened = StudentMemoTelegramReceipts(root)
        assertTrue(reopened.contains("pair-one:123:local-book", remaining))
        assertFalse(reopened.contains("pair-two:123:local-book", remaining))
        assertFalse(reopened.contains("pair-one:456:local-book", remaining))
        assertFalse(reopened.contains("pair-one:123:another-book", remaining))
        assertFalse(StudentMemoTelegramReceipts(temporary.newFolder()).contains("pair-one:123:local-book", remaining))
        assertFalse(reopened.contains("pair-one:123:local-book", fragment(1, "memo_changed_001")))
        reopened.record("pair-one:123:local-book", remaining)
        assertTrue(reopened.contains("pair-one:123:local-book", first))
    }

    private fun fragment(index: Int, memoId: String = "memo_id_001"): StudentMemoEnvelope {
        val whole = ByteArray(RemoteReviewLimits.STUDENT_MEMO_CHUNK_BYTES + 1) { 7 }
        val part = if (index == 0) whole.copyOfRange(0, whole.lastIndex) else byteArrayOf(7)
        return StudentMemoEnvelope(studentMemoChunkTransferId("memo_group_001", index), 1_000L, 3,
            "page_token_001", "workbook_token_001", "11".repeat(32), 1, 1, memoId, 2,
            "22".repeat(32), studentMemoPayloadSha256Hex(part), part,
            chunk = StudentMemoChunkInfo("memo_group_001", index, 2, whole.size, studentMemoPayloadSha256Hex(whole)))
    }
}
