package com.studyink.monitor.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

    @Test fun preTransportFailureReleasesTheClaimForTheSameProcessorToRetry() {
        val root = temporary.newFolder()
        val outbox = TelegramOutbox(root.resolve("outbox"))
        val api = FakeApi(null)
        val active = TelegramCredentials("123456:${"a".repeat(24)}", 7L, "parent")
        var failPreflight = true
        val processor = TelegramOutboxProcessor(
            active,
            api,
            outbox,
            TelegramRetryGate(root.resolve("gate")),
            TelegramConnectionTracker(TelegramConnectionStateStore(root.resolve("connection"))) {},
            root,
            TelegramJitterSource { 0.0 },
            credentialsProvider = {
                if (failPreflight) {
                    failPreflight = false
                    error("credential read failed")
                }
                active
            },
        )
        outbox.enqueue(textEntry("preflight-retry"))

        assertThrows(IllegalStateException::class.java) { processor.processOne(1_000L) }
        assertEquals(
            OutboxProcessResult.Sent("preflight-retry"),
            processor.processOne(1_000L),
        )
        assertEquals(listOf("hello"), api.sentTexts)
    }

    @Test fun closeReleasesOnlyAClaimThatHasNotStartedTransport() {
        val root = temporary.newFolder()
        val outbox = TelegramOutbox(root.resolve("outbox"))
        val firstApi = FakeApi(null)
        val active = TelegramCredentials("123456:${"a".repeat(24)}", 7L, "parent")
        val enteredPreflight = CountDownLatch(1)
        val releasePreflight = CountDownLatch(1)
        val processor = TelegramOutboxProcessor(
            active,
            firstApi,
            outbox,
            TelegramRetryGate(root.resolve("gate")),
            TelegramConnectionTracker(TelegramConnectionStateStore(root.resolve("connection"))) {},
            root,
            TelegramJitterSource { 0.0 },
            credentialsProvider = {
                enteredPreflight.countDown()
                releasePreflight.await(5L, TimeUnit.SECONDS)
                active
            },
        )
        outbox.enqueue(textEntry("close-preflight"))
        val failure = AtomicReference<Throwable?>()
        val executor = Executors.newSingleThreadExecutor()
        val task = executor.submit {
            runCatching { processor.processOne(1_000L) }.exceptionOrNull().let(failure::set)
        }

        assertTrue(enteredPreflight.await(1L, TimeUnit.SECONDS))
        processor.close()
        releasePreflight.countDown()
        task.get(2L, TimeUnit.SECONDS)
        executor.shutdownNow()
        assertTrue(failure.get() is IllegalStateException)
        assertTrue(firstApi.sentTexts.isEmpty())

        val secondApi = FakeApi(null)
        val recovered = TelegramOutboxProcessor(
            active,
            secondApi,
            outbox,
            TelegramRetryGate(root.resolve("gate-2")),
            TelegramConnectionTracker(TelegramConnectionStateStore(root.resolve("connection-2"))) {},
            root,
            TelegramJitterSource { 0.0 },
        )
        assertEquals(
            OutboxProcessResult.Sent("close-preflight"),
            recovered.processOne(1_000L),
        )
        assertEquals(listOf("hello"), secondApi.sentTexts)
    }

    @Test fun teacherRoleNeverFlushesARecoveredParentOutboxEntry() {
        val root = temporary.newFolder()
        val outbox = TelegramOutbox(root.resolve("outbox"))
        val api = FakeApi(null)
        val teacher = TelegramCredentials(
            botToken = "123456:${"a".repeat(24)}",
            allowedPrivateChatId = 7L,
            chatLabel = "parent",
            remoteReviewRole = RemoteReviewRole.TEACHER,
            peerPairId = "pair_identifier_123",
            peerSharedKeyBase64 = TelegramPeerProtocol.encodeKey(ByteArray(32) { 1 }),
            peerPairingExpiresAtEpochMs = 100_000L,
        )
        val processor = TelegramOutboxProcessor(
            teacher,
            api,
            outbox,
            TelegramRetryGate(root.resolve("gate")),
            TelegramConnectionTracker(TelegramConnectionStateStore(root.resolve("connection"))) {},
            root,
            TelegramJitterSource { 0.0 },
        )
        outbox.enqueue(textEntry("stale-parent"))

        assertEquals(
            OutboxProcessResult.Dead("stale-parent"),
            processor.processOne(1_000L),
        )
        assertTrue(api.sentTexts.isEmpty())
    }

    @Test fun peerDocumentIsUploadedOnceAndBookkeepingRemainsUntilAuthenticatedAcknowledgement() {
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

        val acknowledgementDeadline = 1_000L + PEER_ACK_RETENTION_MS
        assertEquals(
            OutboxProcessResult.Retried("peer:1", acknowledgementDeadline),
            processor.processOne(1_000L),
        )
        assertEquals(91L, receiptStore.receipt("transfer_123")?.telegramMessageId)
        assertEquals(1_000L, receiptStore.receipt("transfer_123")?.serverAcceptedAtEpochMs)
        assertEquals(null, receiptStore.receipt("transfer_123")?.acknowledgedAtEpochMs)
        assertEquals(1, api.sentPeerDocuments)
        assertTrue(!payload.exists())
        assertEquals(1, outbox.size())

        assertTrue(receiptStore.recordAcknowledged("transfer_123", 92L, 2_000L))
        assertTrue(outbox.makeDueNow("peer:1", 2_000L))
        assertEquals(OutboxProcessResult.Sent("peer:1"), processor.processOne(2_000L))
        assertEquals(1, api.sentPeerDocuments)
        assertTrue(outbox.isDelivered("peer:1"))
        assertTrue(!payload.exists())
    }

    @Test fun missingPeerAcknowledgementNeverUploadsSameTransferAgain() {
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

        val deadline = 1_000L + PEER_ACK_RETENTION_MS
        assertEquals(
            OutboxProcessResult.Retried("peer:1", deadline),
            processor.processOne(1_000L),
        )
        assertEquals(1, api.sentPeerDocuments)
        assertEquals(0, outbox.pendingSnapshot().single().attempts)
        assertTrue(!payload.exists())
        val replayedOutbox = TelegramOutbox(root.resolve("outbox"))
        val replayedReceipts = TelegramPeerReceiptStore(root.resolve("receipts"))
        val replayedApi = FakeApi(null)
        val replayedProcessor = TelegramOutboxProcessor(
            credentials,
            replayedApi,
            replayedOutbox,
            TelegramRetryGate(root.resolve("replayed-gate")),
            TelegramConnectionTracker(TelegramConnectionStateStore(root.resolve("replayed-connection"))) {},
            root,
            TelegramJitterSource { 0.0 },
            peerReceipts = replayedReceipts,
        )
        assertEquals(
            OutboxProcessResult.Waiting(deadline),
            replayedProcessor.processOne(61_000L),
        )
        assertEquals(0, replayedApi.sentPeerDocuments)
        assertTrue(!peerAcknowledgementExpired(1_000L, 1_000L + 86_399_999L))
        assertTrue(peerAcknowledgementExpired(1_000L, 1_000L + 86_400_000L))

        assertEquals(OutboxProcessResult.Dead("peer:1"), replayedProcessor.processOne(deadline))
        assertEquals(0, replayedApi.sentPeerDocuments)
        assertEquals(0, replayedOutbox.size())
    }

    @Test fun legacySuccessfulUploadMarkerIsMigratedWithoutAnotherTelegramUpload() {
        val root = temporary.newFolder()
        val payload = root.resolve("payload.mne").apply { writeText("encrypted") }
        val outbox = TelegramOutbox(root.resolve("outbox"))
        val receiptStore = TelegramPeerReceiptStore(root.resolve("receipts"))
        val entry = peerEntry("peer:legacy", "transfer_legacy", payload)
        outbox.enqueue(entry)
        receiptStore.recordSent("transfer_legacy", entry.idempotencyKey, null, 1_000L)
        outbox.retry(entry.idempotencyKey, 1_000L, 1L, "원격 기기 수신 확인 대기")
        val api = FakeApi(null)
        val processor = TelegramOutboxProcessor(
            peerCredentials(),
            api,
            outbox,
            TelegramRetryGate(root.resolve("gate")),
            TelegramConnectionTracker(TelegramConnectionStateStore(root.resolve("connection"))) {},
            root,
            TelegramJitterSource { 0.0 },
            peerReceipts = receiptStore,
        )

        assertEquals(
            OutboxProcessResult.Retried("peer:legacy", 1_000L + PEER_ACK_RETENTION_MS),
            processor.processOne(1_001L),
        )
        assertEquals(0, api.sentPeerDocuments)
        assertEquals(1_000L, receiptStore.receipt("transfer_legacy")?.serverAcceptedAtEpochMs)
        assertTrue(!payload.exists())
    }

    @Test fun legacySuccessMarkerPreventsReuploadEvenIfReceiptJournalWasLost() {
        val root = temporary.newFolder()
        val payload = root.resolve("payload.mne").apply { writeText("encrypted") }
        val outbox = TelegramOutbox(root.resolve("outbox"))
        val entry = peerEntry("peer:legacy-lost", "transfer_legacy_lost", payload)
        outbox.enqueue(entry)
        outbox.retry(entry.idempotencyKey, 1_000L, 1L, "원격 기기 수신 확인 대기")
        val receipts = TelegramPeerReceiptStore(root.resolve("receipts"))
        val api = FakeApi(null)
        val processor = TelegramOutboxProcessor(
            peerCredentials(),
            api,
            outbox,
            TelegramRetryGate(root.resolve("gate")),
            TelegramConnectionTracker(TelegramConnectionStateStore(root.resolve("connection"))) {},
            root,
            TelegramJitterSource { 0.0 },
            peerReceipts = receipts,
        )

        assertEquals(
            OutboxProcessResult.Retried("peer:legacy-lost", PEER_ACK_RETENTION_MS),
            processor.processOne(1_001L),
        )
        assertEquals(0, api.sentPeerDocuments)
        assertEquals(0L, receipts.receipt("transfer_legacy_lost")?.serverAcceptedAtEpochMs)
        assertTrue(!payload.exists())
    }

    @Test fun aConfirmedNetworkFailureCanRetryUntilTelegramFirstAcceptsTheUpload() {
        val root = temporary.newFolder()
        val payload = root.resolve("payload.mne").apply { writeText("encrypted") }
        val outbox = TelegramOutbox(root.resolve("outbox"))
        val receipts = TelegramPeerReceiptStore(root.resolve("receipts"))
        val api = FakeApi(TelegramApiException(500, "temporary"))
        val processor = TelegramOutboxProcessor(
            peerCredentials(),
            api,
            outbox,
            TelegramRetryGate(root.resolve("gate")),
            TelegramConnectionTracker(TelegramConnectionStateStore(root.resolve("connection"))) {},
            root,
            TelegramJitterSource { 0.0 },
            peerReceipts = receipts,
        )
        outbox.enqueue(peerEntry("peer:retry", "transfer_retry", payload))

        assertEquals(
            OutboxProcessResult.Retried("peer:retry", 3_000L),
            processor.processOne(1_000L),
        )
        assertEquals(null, receipts.receipt("transfer_retry")?.serverAcceptedAtEpochMs)
        api.failure = null
        assertEquals(
            OutboxProcessResult.Retried("peer:retry", 3_000L + PEER_ACK_RETENTION_MS),
            processor.processOne(3_000L),
        )
        assertEquals(3_000L, receipts.receipt("transfer_retry")?.serverAcceptedAtEpochMs)
        assertEquals(1, api.sentPeerDocuments)
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

    private fun peerCredentials() = TelegramCredentials(
        "123456:${"a".repeat(24)}",
        7L,
        "parent",
        peerBotId = 202L,
        peerBotUsername = "teacher_bot",
    )

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

    private class FakeApi(var failure: Throwable?) : TelegramBotApi {
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
