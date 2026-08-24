package com.studyink.monitor.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TelegramPeerInboxTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun putSurvivesRestartAndAckDeletesOnlyOwnedPayload() {
        val root = temporary.newFolder()
        val owned = root.resolve("inbox").apply { mkdirs() }
        val journal = root.resolve("journal")
        val first = TelegramPeerDocumentInbox(journal, owned)
        val payload = owned.resolve("payload.bin").apply { writeText("page") }
        assertTrue(first.offer(entry(payload)))

        val recovered = TelegramPeerDocumentInbox(journal, owned)
        assertEquals("transfer_123", recovered.pending().single().transferId)
        assertTrue(recovered.acknowledge(7L, 200L) != null)
        assertFalse(payload.exists())

        val afterAck = TelegramPeerDocumentInbox(journal, owned)
        assertTrue(afterAck.pending().isEmpty())
        assertTrue(afterAck.isCompleted("transfer_123"))
    }

    @Test fun duplicateTransferIsNotDeliveredTwice() {
        val root = temporary.newFolder()
        val owned = root.resolve("inbox").apply { mkdirs() }
        val inbox = TelegramPeerDocumentInbox(root.resolve("journal"), owned)
        val first = owned.resolve("first").apply { writeText("page") }
        val duplicate = owned.resolve("duplicate").apply { writeText("page") }

        assertTrue(inbox.offer(entry(first)))
        assertFalse(inbox.offer(entry(duplicate).copy(updateId = 8L, localFilePath = duplicate.absolutePath)))
        assertFalse(duplicate.exists())
        assertEquals(1, inbox.pending().size)
    }

    private fun entry(file: java.io.File) = PendingTelegramPeerDocument(
        updateId = 7L,
        telegramMessageId = 9L,
        senderBotId = 202L,
        senderUsername = "teacher_bot",
        transferId = "transfer_123",
        payloadType = "PAGE_SNAPSHOT",
        fileUniqueId = "unique",
        originalFileName = null,
        mimeType = null,
        byteCount = file.length(),
        localFilePath = file.absolutePath,
        receivedAtEpochMs = 100L,
    )
}
