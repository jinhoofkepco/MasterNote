package com.studyink.monitor.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PeerOutboxCancellationTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun pendingViewFiltersStrictDocumentTypesWithoutExposingOtherRoutes() {
        val root = temporary.newFolder("view")
        val outbox = TelegramOutbox(root.resolve("outbox.v1"))
        val page = peerDocument(root, "snapshot_0001", "PAGE_SNAPSHOT", createdAt = 10L)
        val chat = peerDocument(root, "chat_message_0001", "CHAT_MESSAGE", createdAt = 20L)
        val feedback = peerDocument(root, "feedback_0001", "TEACHER_FEEDBACK", createdAt = 30L)
        val control = peerText("ack_transport_0001", createdAt = 40L)
        val parent = parentDocument(root, "parent_0001", createdAt = 50L)
        listOf(page, chat, feedback, control, parent).forEach(outbox::enqueue)

        val pending = inspectPendingPeerDocumentTransfers(
            outbox.pendingSnapshot(),
            setOf("PAGE_SNAPSHOT", "CHAT_MESSAGE"),
        )

        assertEquals(listOf("snapshot_0001", "chat_message_0001"), pending.map { it.transferId })
        assertEquals(listOf("PAGE_SNAPSHOT", "CHAT_MESSAGE"), pending.map { it.payloadType })
        assertEquals(listOf(10L, 20L), pending.map { it.createdAtEpochMs })
        assertTrue(pending.all { it.ciphertextBytes > 0L })
    }

    @Test
    fun cancellingPageSnapshotsIsDurableAndPreservesChatFeedbackControlsAndParentTraffic() {
        val root = temporary.newFolder("cancel")
        val owned = root.resolve("peer-outbox").apply { mkdirs() }
        val outside = temporary.newFile("caller-owned.mne").apply { writeText("outside") }
        val journal = root.resolve("outbox.v1")
        val outbox = TelegramOutbox(journal)
        val firstPage = peerDocument(owned, "snapshot_0001", "PAGE_SNAPSHOT", createdAt = 10L)
        val secondPage = peerDocument(
            owned,
            "snapshot_0002",
            "PAGE_SNAPSHOT",
            createdAt = 20L,
            file = outside,
        )
        val chat = peerDocument(owned, "chat_message_0001", "CHAT_MESSAGE", createdAt = 30L)
        val feedback = peerDocument(owned, "feedback_0001", "TEACHER_FEEDBACK", createdAt = 40L)
        val control = peerText("ack_transport_0001", createdAt = 50L)
        val parent = parentDocument(owned, "parent_0001", createdAt = 60L)
        listOf(firstPage, secondPage, chat, feedback, control, parent).forEach(outbox::enqueue)
        assertEquals(firstPage, outbox.claimDue(10L))

        val cancelled = cancelPeerDocumentPayloads(
            outbox = outbox,
            ownedPeerOutboxRoot = owned,
            payloadTypes = setOf("PAGE_SNAPSHOT"),
            cancelledAtEpochMs = 100L,
        )

        assertEquals(listOf("snapshot_0001", "snapshot_0002"), cancelled.map { it.transferId })
        assertFalse(requireNotNull(firstPage.file).exists())
        assertTrue(outside.exists())
        assertTrue(requireNotNull(chat.file).exists())
        assertTrue(requireNotNull(feedback.file).exists())
        assertTrue(requireNotNull(parent.file).exists())
        assertNull(outbox.retry(firstPage.idempotencyKey, 101L, 1_000L, "interrupted"))
        assertEquals(
            setOf(chat.idempotencyKey, feedback.idempotencyKey, control.idempotencyKey, parent.idempotencyKey),
            outbox.pendingSnapshot().mapTo(linkedSetOf(), TelegramOutboxEntry::idempotencyKey),
        )

        val replayed = TelegramOutbox(journal)
        assertEquals(
            setOf(chat.idempotencyKey, feedback.idempotencyKey, control.idempotencyKey, parent.idempotencyKey),
            replayed.pendingSnapshot().mapTo(linkedSetOf(), TelegramOutboxEntry::idempotencyKey),
        )
        assertEquals(TelegramEnqueueResult.PREVIOUSLY_SUPERSEDED, replayed.enqueue(firstPage))
        assertTrue(cancelPeerDocumentPayloads(replayed, owned, setOf("PAGE_SNAPSHOT"), 200L).isEmpty())
    }

    @Test
    fun malformedOrMismatchedCaptionCannotCancelAnUnrelatedDocument() {
        val root = temporary.newFolder("mismatch")
        val owned = root.resolve("peer-outbox").apply { mkdirs() }
        val outbox = TelegramOutbox(root.resolve("outbox.v1"))
        val mismatched = peerDocument(
            owned,
            transferId = "snapshot_0001",
            payloadType = "PAGE_SNAPSHOT",
            createdAt = 10L,
        ).copy(
            text = TelegramPeerProtocol.documentCaption(PAIR_ID, "snapshot_other", "PAGE_SNAPSHOT"),
        )
        outbox.enqueue(mismatched)

        assertTrue(
            cancelPeerDocumentPayloads(outbox, owned, setOf("PAGE_SNAPSHOT"), 20L).isEmpty(),
        )
        assertEquals(listOf(mismatched), outbox.pendingSnapshot())
        assertTrue(requireNotNull(mismatched.file).exists())
    }

    @Test
    fun emptyFilterIsNoOpAndInvalidPayloadTypeIsRejected() {
        val root = temporary.newFolder("filter")
        val outbox = TelegramOutbox(root.resolve("outbox.v1"))

        assertTrue(inspectPendingPeerDocumentTransfers(outbox.pendingSnapshot(), emptySet()).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            inspectPendingPeerDocumentTransfers(outbox.pendingSnapshot(), setOf("page_snapshot"))
        }
    }

    private fun peerDocument(
        directory: File,
        transferId: String,
        payloadType: String,
        createdAt: Long,
        file: File = directory.resolve("$transferId.mne").apply { writeText("cipher-$transferId") },
    ) = TelegramOutboxEntry(
        idempotencyKey = "telegram-peer-document:$PAIR_ID:$transferId",
        destinationChatId = 202L,
        kind = TelegramOutboxKind.DOCUMENT,
        filePath = file.absolutePath,
        text = TelegramPeerProtocol.documentCaption(PAIR_ID, transferId, payloadType),
        mimeType = TelegramPeerProtocol.CIPHERTEXT_MIME,
        displayName = "$transferId.mne",
        attempts = 0,
        nextAttemptEpochMs = createdAt,
        createdAtEpochMs = createdAt,
        deleteAfterSend = true,
        route = TelegramOutboxRoute.PEER,
        destinationUsername = "teacher_bot",
        peerTransferId = transferId,
    )

    private fun peerText(transferId: String, createdAt: Long) = TelegramOutboxEntry(
        idempotencyKey = "telegram-peer-control:$transferId",
        destinationChatId = 202L,
        kind = TelegramOutboxKind.TEXT,
        filePath = null,
        text = "peer control remains queued",
        mimeType = null,
        displayName = null,
        attempts = 0,
        nextAttemptEpochMs = createdAt,
        createdAtEpochMs = createdAt,
        deleteAfterSend = false,
        route = TelegramOutboxRoute.PEER,
        destinationUsername = "teacher_bot",
        peerTransferId = transferId,
    )

    private fun parentDocument(directory: File, transferId: String, createdAt: Long): TelegramOutboxEntry {
        val file = directory.resolve("$transferId-parent.mne").apply { writeText("parent") }
        return TelegramOutboxEntry(
            idempotencyKey = "parent:$transferId",
            destinationChatId = 7L,
            kind = TelegramOutboxKind.DOCUMENT,
            filePath = file.absolutePath,
            text = TelegramPeerProtocol.documentCaption(PAIR_ID, transferId, "PAGE_SNAPSHOT"),
            mimeType = "application/octet-stream",
            displayName = file.name,
            attempts = 0,
            nextAttemptEpochMs = createdAt,
            createdAtEpochMs = createdAt,
            deleteAfterSend = true,
        )
    }

    private companion object {
        const val PAIR_ID = "pair_identifier_123"
    }
}
