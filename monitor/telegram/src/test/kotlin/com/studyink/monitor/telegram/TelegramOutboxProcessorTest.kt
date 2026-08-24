package com.studyink.monitor.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TelegramOutboxProcessorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rateLimitPersistsGlobalGateAndRetry() {
        val fixture = fixture(TelegramApiException(429, "rate limited", retryAfterSeconds = 17L))
        fixture.outbox.enqueue(textEntry("idle:30"))

        val result = fixture.processor.processOne(1_000L)

        assertEquals(OutboxProcessResult.Retried("idle:30", 18_000L), result)
        assertEquals(18_000L, fixture.gate.nextAllowedEpochMs())
        assertEquals(1, fixture.outbox.pendingSnapshot().single().attempts)
    }

    @Test fun permanentFailureMovesEntryToDeadLetter() {
        val fixture = fixture(TelegramApiException(401, "Unauthorized"))
        fixture.outbox.enqueue(textEntry("message:4"))

        assertEquals(OutboxProcessResult.Dead("message:4"), fixture.processor.processOne(1_000L))
        assertEquals(0, fixture.outbox.size())
        assertEquals("message:4", fixture.outbox.deadLetters().single().entry.idempotencyKey)
    }

    @Test fun successAcknowledgesWithoutKeepingPayloadInMemory() {
        val fixture = fixture(null)
        fixture.outbox.enqueue(textEntry("message:5"))

        assertEquals(OutboxProcessResult.Sent("message:5"), fixture.processor.processOne(1_000L))
        assertTrue(fixture.outbox.isDelivered("message:5"))
        assertEquals(listOf("hello"), fixture.api.sentTexts)
    }

    @Test fun peerDocumentIsKeptUntilAuthenticatedPeerAcknowledgement() {
        val root = temporary.newFolder()
        val payload = root.resolve("payload.mne").apply { writeText("encrypted") }
        val outbox = TelegramOutbox(root.resolve("outbox"))
        val receiptStore = TelegramPeerReceiptStore(root.resolve("receipts"))
        val credentials = TelegramCredentials(
            "123456:${"a".repeat(24)}",
            7L,
            "parent",
            peerBotId = 202L,
            peerBotUsername = "teacher_bot",
        )
        val api = FakeApi(null)
        val processor = TelegramOutboxProcessor(
            credentials,
            api,
            outbox,
            TelegramRetryGate(root.resolve("gate")),
            TelegramConnectionTracker(TelegramConnectionStateStore(root.resolve("connection"))) {},
            root,
            TelegramJitterSource { 0.0 },
            peerReceipts = receiptStore,
        )
        outbox.enqueue(peerEntry("peer:1", "transfer_123", payload))

        assertEquals(OutboxProcessResult.Retried("peer:1", 61_000L), processor.processOne(1_000L))
        assertEquals(null, receiptStore.receipt("transfer_123")?.telegramMessageId)
        assertEquals(null, receiptStore.receipt("transfer_123")?.acknowledgedAtEpochMs)
        assertEquals(1, api.sentPeerDocuments)
        assertTrue(payload.isFile)
        assertEquals(1, outbox.size())

        assertTrue(receiptStore.recordAcknowledged("transfer_123", 92L, 2_000L))
        assertTrue(outbox.makeDueNow("peer:1", 2_000L))
        assertEquals(OutboxProcessResult.Sent("peer:1"), processor.processOne(2_000L))
        assertEquals(1, api.sentPeerDocuments)
        assertTrue(outbox.isDelivered("peer:1"))
        assertTrue(!payload.exists())
    }

    @Test fun missingPeerAcknowledgementRetriesSameTransferWithBoundedBackoff() {
        val root = temporary.newFolder()
        val payload = root.resolve("payload.mne").apply { writeText("encrypted") }
        val outbox = TelegramOutbox(root.resolve("outbox"))
        val receiptStore = TelegramPeerReceiptStore(root.resolve("receipts"))
        val credentials = TelegramCredentials(
            "123456:${"a".repeat(24)}",
            7L,
            "parent",
            peerBotId = 202L,
            peerBotUsername = "teacher_bot",
        )
        val api = FakeApi(null)
        val processor = TelegramOutboxProcessor(
            credentials,
            api,
            outbox,
            TelegramRetryGate(root.resolve("gate")),
            TelegramConnectionTracker(TelegramConnectionStateStore(root.resolve("connection"))) {},
            root,
            TelegramJitterSource { 0.0 },
            peerReceipts = receiptStore,
        )
        outbox.enqueue(peerEntry("peer:1", "transfer_123", payload))

        assertEquals(OutboxProcessResult.Retried("peer:1", 61_000L), processor.processOne(1_000L))
        assertEquals(OutboxProcessResult.Retried("peer:1", 181_000L), processor.processOne(61_000L))
        assertEquals(2, api.sentPeerDocuments)
        assertEquals(2, outbox.pendingSnapshot().single().attempts)
        assertEquals(8L * 60_000L, peerAcknowledgementRetryDelayMs(3))
        assertEquals(180L * 60_000L, peerAcknowledgementRetryDelayMs(20))
        assertTrue(!peerAcknowledgementExpired(1_000L, 1_000L + 86_399_999L))
        assertTrue(peerAcknowledgementExpired(1_000L, 1_000L + 86_400_000L))
    }

    @Test fun peerDocumentWithoutAcknowledgementExpiresAndDeletesOwnedPayload() {
        val root = temporary.newFolder()
        val payload = root.resolve("payload.mne").apply { writeText("encrypted") }
        val outbox = TelegramOutbox(root.resolve("outbox"))
        val receiptStore = TelegramPeerReceiptStore(root.resolve("receipts"))
        val credentials = TelegramCredentials(
            "123456:${"a".repeat(24)}",
            7L,
            "parent",
            peerBotId = 202L,
            peerBotUsername = "teacher_bot",
        )
        val api = FakeApi(null)
        val processor = TelegramOutboxProcessor(
            credentials,
            api,
            outbox,
            TelegramRetryGate(root.resolve("gate")),
            TelegramConnectionTracker(TelegramConnectionStateStore(root.resolve("connection"))) {},
            root,
            TelegramJitterSource { 0.0 },
            peerReceipts = receiptStore,
        )
        outbox.enqueue(peerEntry("peer:expired", "transfer_expired", payload))

        assertEquals(
            OutboxProcessResult.Dead("peer:expired"),
            processor.processOne(24L * 60L * 60L * 1_000L),
        )
        assertEquals(0, api.sentPeerDocuments)
        assertTrue(!payload.exists())
        assertEquals(0, outbox.size())
    }

    @Test fun permanentPeerUploadFailureDeletesOwnedPayload() {
        val root = temporary.newFolder()
        val payload = root.resolve("payload.mne").apply { writeText("encrypted") }
        val outbox = TelegramOutbox(root.resolve("outbox"))
        val receiptStore = TelegramPeerReceiptStore(root.resolve("receipts"))
        val credentials = TelegramCredentials(
            "123456:${"a".repeat(24)}",
            7L,
            "parent",
            peerBotId = 202L,
            peerBotUsername = "teacher_bot",
        )
        val api = FakeApi(TelegramApiException(401, "Unauthorized"))
        val processor = TelegramOutboxProcessor(
            credentials,
            api,
            outbox,
            TelegramRetryGate(root.resolve("gate")),
            TelegramConnectionTracker(TelegramConnectionStateStore(root.resolve("connection"))) {},
            root,
            TelegramJitterSource { 0.0 },
            peerReceipts = receiptStore,
        )
        outbox.enqueue(peerEntry("peer:unauthorized", "transfer_unauthorized", payload))

        assertEquals(
            OutboxProcessResult.Dead("peer:unauthorized"),
            processor.processOne(1_000L),
        )
        assertTrue(!payload.exists())
        assertEquals(0, outbox.size())
    }

    private fun fixture(error: Throwable?): Fixture {
        val root = temporary.newFolder()
        val outbox = TelegramOutbox(root.resolve("outbox"))
        val gate = TelegramRetryGate(root.resolve("gate"))
        val tracker = TelegramConnectionTracker(
            TelegramConnectionStateStore(root.resolve("connection")),
        ) {}
        val api = FakeApi(error)
        return Fixture(
            outbox,
            gate,
            api,
            TelegramOutboxProcessor(
                TelegramCredentials("123456:${"a".repeat(24)}", 7L, "parent"),
                api,
                outbox,
                gate,
                tracker,
                root,
                TelegramJitterSource { 0.0 },
            ),
        )
    }

    private fun textEntry(key: String) = TelegramOutboxEntry(
        idempotencyKey = key,
        destinationChatId = 7L,
        kind = TelegramOutboxKind.TEXT,
        filePath = null,
        text = "hello",
        mimeType = null,
        displayName = null,
        attempts = 0,
        nextAttemptEpochMs = 0L,
        createdAtEpochMs = 0L,
        deleteAfterSend = false,
    )

    private fun peerEntry(key: String, transferId: String, file: File) = TelegramOutboxEntry(
        idempotencyKey = key,
        destinationChatId = 202L,
        kind = TelegramOutboxKind.DOCUMENT,
        filePath = file.absolutePath,
        text = "MNTP1 DOC pair_identifier_123 $transferId PAGE_SNAPSHOT",
        mimeType = TelegramPeerProtocol.CIPHERTEXT_MIME,
        displayName = "payload.mne",
        attempts = 0,
        nextAttemptEpochMs = 0L,
        createdAtEpochMs = 0L,
        deleteAfterSend = true,
        route = TelegramOutboxRoute.PEER,
        destinationUsername = "teacher_bot",
        peerTransferId = transferId,
    )

    private data class Fixture(
        val outbox: TelegramOutbox,
        val gate: TelegramRetryGate,
        val api: FakeApi,
        val processor: TelegramOutboxProcessor,
    )

    private class FakeApi(private val failure: Throwable?) : TelegramBotApi {
        val sentTexts = mutableListOf<String>()
        var sentPeerDocuments = 0
        override fun getMe() = error("unused")
        override fun deleteWebhook() = Unit
        override fun getUpdates(offset: Long, timeoutSeconds: Int) = emptyList<TelegramInboundUpdate>()
        override fun sendMessage(chatId: Long, text: String): TelegramSendResult {
            failure?.let { throw it }
            sentTexts += text
            return TelegramSendResult(1L)
        }
        override fun sendDocument(chatId: Long, document: File, caption: String, mimeType: String, displayName: String) = error("unused")
        override fun sendVoice(chatId: Long, voice: File, caption: String, mimeType: String, displayName: String) = error("unused")
        override fun sendPeerDocument(
            peerUsername: String,
            document: File,
            caption: String,
            mimeType: String,
            displayName: String,
        ): TelegramSendResult {
            failure?.let { throw it }
            sentPeerDocuments++
            return TelegramSendResult(91L)
        }
    }
}
