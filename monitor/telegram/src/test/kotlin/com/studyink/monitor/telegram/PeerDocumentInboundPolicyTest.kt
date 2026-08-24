package com.studyink.monitor.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerDocumentInboundPolicyTest {
    @Test fun missingOctetStreamAndCustomMimeAreAccepted() {
        listOf(
            null,
            "application/octet-stream",
            TelegramPeerProtocol.CIPHERTEXT_MIME,
        ).forEach { mimeType ->
            val decision = classifyInboundPeerDocument(update(mimeType = mimeType), PINNED, PAIR_ID)

            assertTrue(decision is PeerDocumentMetadataDecision.Accepted)
            assertTrue((decision as PeerDocumentMetadataDecision.Accepted).recognizedMime)
            assertEquals(TRANSFER_ID, decision.header.transferId)
        }
    }

    @Test fun unfamiliarMimeRemainsAdvisoryBecauseCiphertextIsAuthenticatedLater() {
        val decision = classifyInboundPeerDocument(
            update(mimeType = "application/x-telegram-normalized"),
            PINNED,
            PAIR_ID,
        )

        assertTrue(decision is PeerDocumentMetadataDecision.Accepted)
        assertFalse((decision as PeerDocumentMetadataDecision.Accepted).recognizedMime)
    }

    @Test fun wrongPeerWrongPairAndOversizeRemainRejected() {
        assertRejected(
            update(senderId = 999L, chatId = 999L),
            PeerDocumentRejectionReason.WRONG_PEER,
        )
        assertRejected(
            update(captionPairId = "other_pair_123"),
            PeerDocumentRejectionReason.WRONG_PAIR,
        )
        assertRejected(
            update(fileSize = TelegramPeerPayloadCipher.MAX_CIPHERTEXT_BYTES + 1L),
            PeerDocumentRejectionReason.OVERSIZED,
        )
    }

    private fun assertRejected(
        update: TelegramInboundUpdate,
        expected: PeerDocumentRejectionReason,
    ) {
        val decision = classifyInboundPeerDocument(update, PINNED, PAIR_ID)
        assertTrue(decision is PeerDocumentMetadataDecision.Rejected)
        assertEquals(expected, (decision as PeerDocumentMetadataDecision.Rejected).reason)
    }

    private fun update(
        mimeType: String? = TelegramPeerProtocol.CIPHERTEXT_MIME,
        senderId: Long = PINNED.botId,
        chatId: Long = senderId,
        captionPairId: String = PAIR_ID,
        fileSize: Long = 1_048_576L,
    ) = TelegramInboundUpdate(
        updateId = 81L,
        messageId = 44L,
        chatId = chatId,
        chatType = "private",
        text = null,
        senderIsBot = true,
        senderDisplayName = "Student",
        senderUsername = if (senderId == PINNED.botId) PINNED.username else "other_student_bot",
        sentAtEpochSeconds = 123L,
        senderId = senderId,
        caption = TelegramPeerProtocol.documentCaption(captionPairId, TRANSFER_ID, "PAGE_SNAPSHOT"),
        document = TelegramInboundDocument(
            fileId = "telegram-file-id",
            fileUniqueId = "stable-file-id",
            fileName = "master-note-$TRANSFER_ID.mne",
            mimeType = mimeType,
            fileSizeBytes = fileSize,
        ),
    )

    private companion object {
        const val PAIR_ID = "pair_identifier_123"
        const val TRANSFER_ID = "snapshot_transfer_123"
        val PINNED = TelegramPeerBinding(202L, "student_bot")
    }
}
