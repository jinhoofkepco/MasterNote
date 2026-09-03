package com.studyink.monitor.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TelegramOutboxTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun pendingAndDeliveredIdempotencySurviveReplay() {
        val journal = temporary.newFile("outbox.v1")
        val queue = TelegramOutbox(journal)
        val entry = textEntry("idle:30", createdAt = 100L)

        assertEquals(TelegramEnqueueResult.ENQUEUED, queue.enqueue(entry))
        assertEquals(TelegramEnqueueResult.ALREADY_PENDING, queue.enqueue(entry))
        assertEquals(entry, TelegramOutbox(journal).due(100L))

        queue.acknowledge(entry.idempotencyKey, 200L)
        val replayed = TelegramOutbox(journal)
        assertNull(replayed.due(1_000L))
        assertTrue(replayed.isDelivered(entry.idempotencyKey))
        assertEquals(TelegramEnqueueResult.ALREADY_DELIVERED, replayed.enqueue(entry))
    }

    @Test fun retryAndDeadLetterAreDurable() {
        val journal = temporary.newFile("dead.v1")
        val queue = TelegramOutbox(journal)
        val entry = textEntry("message:7", createdAt = 100L)
        queue.enqueue(entry)

        val retried = queue.retry(entry.idempotencyKey, 200L, 5_000L, "offline")!!
        assertEquals(1, retried.attempts)
        assertEquals(5_200L, retried.nextAttemptEpochMs)
        assertNull(queue.due(5_199L))

        queue.deadLetter(entry.idempotencyKey, "bad request", 300L)
        val replayed = TelegramOutbox(journal)
        assertEquals(0, replayed.size())
        assertEquals("bad request", replayed.deadLetters().single().reason)
        assertEquals(TelegramEnqueueResult.PREVIOUSLY_DEAD, replayed.enqueue(entry))
    }

    @Test fun currentScreenAndInteractiveMessagesPrecedeSubmissionsAndIdle() {
        val queue = TelegramOutbox(temporary.newFile("priority.v1"))
        val idle = textEntry("idle:30", 1L).copy(coalesceKey = "idle-current")
        val submission = documentEntry("submission:page:1", "submission.png", 2L)
        val voice = documentEntry("student-voice:1", "voice.m4a", 3L).copy(
            kind = TelegramOutboxKind.VOICE,
            mimeType = "audio/mp4",
        )
        val interactiveText = textEntry("setup-result", 4L)
        val currentScreen = documentEntry("telegram-screen:2", "current.png", 5L)
        queue.enqueueLatestText(idle)
        queue.enqueue(submission)
        queue.enqueue(voice)
        queue.enqueue(interactiveText)
        queue.enqueue(currentScreen)

        listOf(
            currentScreen.idempotencyKey,
            voice.idempotencyKey,
            interactiveText.idempotencyKey,
            submission.idempotencyKey,
            idle.idempotencyKey,
        ).forEachIndexed { index, expected ->
            assertEquals(expected, queue.due(20L + index)?.idempotencyKey)
            queue.acknowledge(expected, 20L + index)
        }
    }

    @Test fun regularAndPriorityPeerControlLanesClaimOnlyTheirOwnEntries() {
        val queue = TelegramOutbox(temporary.newFile("isolated-lanes.v1"))
        val accept = peerControl(
            "telegram-peer-control:pair_identifier_123:accept:accept_request_001",
            "accept_request_001",
            1L,
        )
        val pong = peerControl(
            "telegram-peer-control:pair_identifier_123:pong:pong_nonce_001",
            "pong_nonce_001",
            2L,
        )
        val connect = peerControl(
            "telegram-peer-control:pair_identifier_123:connect:request_001",
            "request_001",
            10L,
        )
        val ping = peerControl(
            "telegram-peer-control:pair_identifier_123:ping:nonce_001",
            "nonce_001",
            20L,
        )
        val parentText = textEntry("ordinary-parent", 30L)
        listOf(accept, pong, connect, ping).forEach {
            assertEquals(TelegramEnqueueResult.ENQUEUED, queue.enqueuePeerLinkControl(it))
        }
        assertEquals(TelegramEnqueueResult.ENQUEUED, queue.enqueue(parentText))

        assertEquals(1L, queue.nextWakeEpochMs(TelegramOutboxLane.PRIORITY_PEER_CONTROL))
        assertEquals(30L, queue.nextWakeEpochMs(TelegramOutboxLane.REGULAR))

        assertEquals(accept, queue.claimDue(100L, TelegramOutboxLane.PRIORITY_PEER_CONTROL))
        queue.acknowledge(accept.idempotencyKey, 102L)
        assertEquals(pong, queue.claimDue(100L, TelegramOutboxLane.PRIORITY_PEER_CONTROL))
        queue.acknowledge(pong.idempotencyKey, 104L)
        assertEquals(connect, queue.claimDue(100L, TelegramOutboxLane.PRIORITY_PEER_CONTROL))
        queue.acknowledge(connect.idempotencyKey, 105L)
        assertEquals(ping, queue.claimDue(100L, TelegramOutboxLane.PRIORITY_PEER_CONTROL))
        queue.acknowledge(ping.idempotencyKey, 106L)
        assertEquals(parentText, queue.claimDue(100L, TelegramOutboxLane.REGULAR))
        assertNull(queue.claimDue(100L, TelegramOutboxLane.PRIORITY_PEER_CONTROL))
    }

    @Test fun replayedLegacyPeerControlsAreRecoveredIntoPriorityLane() {
        val journal = temporary.newFile("replayed-priority-peer-controls.v1")
        val accept = peerControl(
            "telegram-peer-control:pair_identifier_123:accept:accept_request_002",
            "accept_request_002",
            5L,
        )
        val pong = peerControl(
            "telegram-peer-control:pair_identifier_123:pong:pong_nonce_002",
            "pong_nonce_002",
            6L,
        )
        val connect = peerControl(
            "telegram-peer-control:pair_identifier_123:connect:request_002",
            "request_002",
            7L,
        )
        val ping = peerControl(
            "telegram-peer-control:pair_identifier_123:ping:nonce_003",
            "nonce_003",
            8L,
        )
        TelegramOutbox(journal).also { queue ->
            assertEquals(TelegramEnqueueResult.ENQUEUED, queue.enqueuePeerLinkControl(accept))
            assertEquals(TelegramEnqueueResult.ENQUEUED, queue.enqueuePeerLinkControl(pong))
            assertEquals(TelegramEnqueueResult.ENQUEUED, queue.enqueuePeerLinkControl(connect))
            assertEquals(TelegramEnqueueResult.ENQUEUED, queue.enqueuePeerLinkControl(ping))
        }

        val replayed = TelegramOutbox(journal)
        assertNull(replayed.claimDue(100L, TelegramOutboxLane.REGULAR))
        assertEquals(5L, replayed.nextWakeEpochMs(TelegramOutboxLane.PRIORITY_PEER_CONTROL))
        assertEquals(accept, replayed.claimDue(100L, TelegramOutboxLane.PRIORITY_PEER_CONTROL))
        replayed.acknowledge(accept.idempotencyKey, 101L)
        assertEquals(pong, replayed.claimDue(100L, TelegramOutboxLane.PRIORITY_PEER_CONTROL))
        replayed.acknowledge(pong.idempotencyKey, 102L)
        assertEquals(connect, replayed.claimDue(100L, TelegramOutboxLane.PRIORITY_PEER_CONTROL))
        replayed.acknowledge(connect.idempotencyKey, 103L)
        assertEquals(ping, replayed.claimDue(100L, TelegramOutboxLane.PRIORITY_PEER_CONTROL))
    }

    @Test fun priorityResponsesJumpAheadOfOlderConnectAndPingProbes() {
        val queue = TelegramOutbox(temporary.newFile("priority-control-order.v1"))
        val ping = peerControl(
            "telegram-peer-control:pair_identifier_123:ping:nonce_old",
            "nonce_old",
            1L,
        )
        val connect = peerControl(
            "telegram-peer-control:pair_identifier_123:connect:request_old",
            "request_old",
            2L,
        )
        val accept = peerControl(
            "telegram-peer-control:pair_identifier_123:accept:accept_request_new",
            "accept_request_new",
            100L,
        )
        val pong = peerControl(
            "telegram-peer-control:pair_identifier_123:pong:pong_nonce_new",
            "pong_nonce_new",
            101L,
        )
        listOf(ping, connect, accept, pong).forEach {
            assertEquals(TelegramEnqueueResult.ENQUEUED, queue.enqueuePeerLinkControl(it))
        }

        assertEquals(accept, queue.claimDue(200L, TelegramOutboxLane.PRIORITY_PEER_CONTROL))
        queue.acknowledge(accept.idempotencyKey, 201L)
        assertEquals(pong, queue.claimDue(200L, TelegramOutboxLane.PRIORITY_PEER_CONTROL))
        queue.acknowledge(pong.idempotencyKey, 202L)
        assertEquals(connect, queue.claimDue(200L, TelegramOutboxLane.PRIORITY_PEER_CONTROL))
        queue.acknowledge(connect.idempotencyKey, 203L)
        assertEquals(ping, queue.claimDue(200L, TelegramOutboxLane.PRIORITY_PEER_CONTROL))
    }

    @Test fun retriedConnectKeepsPriorityLaneAndLivenessStateAcrossReplay() {
        val journal = temporary.newFile("priority-connect-retry.v1")
        val queue = TelegramOutbox(journal)
        val connect = peerControl(
            "telegram-peer-control:pair_identifier_123:connect:request_retry",
            "request_retry",
            1L,
        )
        assertEquals(TelegramEnqueueResult.ENQUEUED, queue.enqueuePeerLinkControl(connect))
        assertTrue(queue.isPendingOrDelivered(connect.idempotencyKey))
        assertEquals(connect, queue.claimDue(100L, TelegramOutboxLane.PRIORITY_PEER_CONTROL))
        queue.retry(connect.idempotencyKey, 100L, 500L, "network")

        val replayed = TelegramOutbox(journal)
        assertTrue(replayed.isPendingOrDelivered(connect.idempotencyKey))
        assertNull(replayed.claimDue(600L, TelegramOutboxLane.REGULAR))
        assertEquals(600L, replayed.nextWakeEpochMs(TelegramOutboxLane.PRIORITY_PEER_CONTROL))
        val retried = replayed.claimDue(600L, TelegramOutboxLane.PRIORITY_PEER_CONTROL)
        assertEquals(1, retried?.attempts)
        replayed.deadLetter(connect.idempotencyKey, "permanent", 601L)
        assertFalse(replayed.isPendingOrDelivered(connect.idempotencyKey))
    }

    private fun documentEntry(key: String, name: String, createdAt: Long) = TelegramOutboxEntry(
            idempotencyKey = key,
            destinationChatId = 7L,
            kind = TelegramOutboxKind.DOCUMENT,
            filePath = temporary.newFile(name).absolutePath,
            text = "page",
            mimeType = if (name.endsWith(".m4a")) "audio/mp4" else "image/png",
            displayName = name,
            attempts = 0,
            nextAttemptEpochMs = 10L,
            createdAtEpochMs = createdAt,
            deleteAfterSend = true,
        )

    @Test fun latestTextReplacesOnlyPendingEntryInItsStream() {
        val journal = temporary.newFile("latest.v1")
        val queue = TelegramOutbox(journal)
        val first = textEntry("idle:30", 30L).copy(coalesceKey = "idle-current")
        val latest = textEntry("idle:40", 40L).copy(coalesceKey = "idle-current", text = "40 seconds")
        queue.enqueueLatestText(first)
        assertEquals(TelegramEnqueueResult.ENQUEUED, queue.enqueueLatestText(latest))

        assertEquals(listOf("idle:40"), queue.pendingSnapshot().map { it.idempotencyKey })
        assertEquals("idle:40", TelegramOutbox(journal).due(100L)?.idempotencyKey)
        assertEquals(TelegramEnqueueResult.PREVIOUSLY_SUPERSEDED, queue.enqueueLatestText(first))
    }

    @Test fun latestPeerDocumentDurablyReplacesOnlyUnsentEntry() {
        val journal = temporary.newFile("latest-peer-document.v1")
        val queue = TelegramOutbox(journal)
        val first = peerDocument("telegram-peer-document:pair:memo_first", "memo_first", "first.mne", 30L)
        val latest = peerDocument("telegram-peer-document:pair:memo_latest", "memo_latest", "latest.mne", 40L)
        queue.enqueue(first)

        val outcome = queue.enqueueLatest(latest)

        assertEquals(TelegramEnqueueResult.ENQUEUED, outcome.result)
        assertEquals(listOf(first), outcome.supersededEntries)
        assertEquals(listOf(latest.idempotencyKey), queue.pendingSnapshot().map { it.idempotencyKey })
        assertEquals(
            listOf(latest.idempotencyKey),
            TelegramOutbox(journal).pendingSnapshot().map { it.idempotencyKey },
        )
        assertEquals(TelegramEnqueueResult.PREVIOUSLY_SUPERSEDED, queue.enqueueLatest(first).result)
    }

    @Test fun latestPeerDocumentLeavesClaimedEntryImmutableAcrossReplay() {
        val journal = temporary.newFile("latest-peer-in-flight.v1")
        val queue = TelegramOutbox(journal)
        val claimed = peerDocument("telegram-peer-document:pair:memo_claimed", "memo_claimed", "claimed.mne", 30L)
        val latest = peerDocument("telegram-peer-document:pair:memo_after", "memo_after", "after.mne", 40L)
        queue.enqueue(claimed)
        assertEquals(claimed, queue.claimDue(30L))

        val outcome = queue.enqueueLatest(latest)

        assertTrue(outcome.supersededEntries.isEmpty())
        assertEquals(
            setOf(claimed.idempotencyKey, latest.idempotencyKey),
            queue.pendingSnapshot().mapTo(linkedSetOf(), TelegramOutboxEntry::idempotencyKey),
        )
        assertEquals(
            setOf(claimed.idempotencyKey, latest.idempotencyKey),
            TelegramOutbox(journal).pendingSnapshot()
                .mapTo(linkedSetOf(), TelegramOutboxEntry::idempotencyKey),
        )
    }

    @Test fun latestPeerDocumentNeverSupersedesServerAcceptedAckRetention() {
        val journal = temporary.newFile("latest-peer-accepted.v1")
        val queue = TelegramOutbox(journal)
        val accepted = peerDocument("telegram-peer-document:pair:memo_accepted", "memo_accepted", "accepted.mne", 30L)
        val latest = peerDocument("telegram-peer-document:pair:memo_newer", "memo_newer", "newer.mne", 40L)
        queue.enqueue(accepted)
        queue.deferUntil(accepted.idempotencyKey, 86_400_030L, PEER_ACK_WAIT_REASON)

        val outcome = queue.enqueueLatest(latest)

        assertTrue(outcome.supersededEntries.isEmpty())
        assertEquals(
            setOf(accepted.idempotencyKey, latest.idempotencyKey),
            TelegramOutbox(journal).pendingSnapshot()
                .mapTo(linkedSetOf(), TelegramOutboxEntry::idempotencyKey),
        )
    }

    @Test fun supersededCiphertextCleanupDeletesOnlyUnreferencedOwnedFiles() {
        val owned = temporary.newFolder("owned-peer-outbox")
        val obsoleteFile = owned.resolve("obsolete.mne").apply { writeBytes(byteArrayOf(1)) }
        val protectedFile = owned.resolve("protected.mne").apply { writeBytes(byteArrayOf(2)) }
        val outsideFile = temporary.newFile("outside.mne").apply { writeBytes(byteArrayOf(3)) }
        val obsolete = peerDocument("telegram-peer-document:pair:memo_old", "memo_old", "ignored-a", 1L)
            .copy(filePath = obsoleteFile.absolutePath)
        val protected = peerDocument("telegram-peer-document:pair:memo_protected", "memo_protected", "ignored-b", 2L)
            .copy(filePath = protectedFile.absolutePath)
        val outside = peerDocument("telegram-peer-document:pair:memo_outside", "memo_outside", "ignored-c", 3L)
            .copy(filePath = outsideFile.absolutePath)

        assertEquals(
            1,
            deleteSupersededOwnedPeerPayloads(owned, listOf(obsolete, protected, outside), listOf(protected)),
        )
        assertFalse(obsoleteFile.exists())
        assertTrue(protectedFile.exists())
        assertTrue(outsideFile.exists())
    }

    @Test fun cancellingCoalescedTextIsDurableAndPreventsInflightRetry() {
        val journal = temporary.newFile("cancel-latest.v1")
        val queue = TelegramOutbox(journal)
        val stale = textEntry("idle:episode:30", 30L).copy(coalesceKey = "idle-current")
        queue.enqueueLatestText(stale)
        assertEquals(stale, queue.claimDue(30L))

        assertEquals(1, queue.cancelCoalesced("idle-current", 31L))
        assertNull(queue.retry(stale.idempotencyKey, 31L, 1_000L, "disconnected"))
        assertNull(queue.due(2_000L))
        assertTrue(queue.hasSeen(stale.idempotencyKey))
        assertEquals(TelegramEnqueueResult.PREVIOUSLY_SUPERSEDED, queue.enqueueLatestText(stale))

        val replayed = TelegramOutbox(journal)
        assertNull(replayed.due(2_000L))
        assertTrue(replayed.hasSeen(stale.idempotencyKey))
        val next = textEntry("idle:episode2:30", 40L).copy(coalesceKey = "idle-current")
        assertEquals(TelegramEnqueueResult.ENQUEUED, replayed.enqueueLatestText(next))
        assertEquals(next, replayed.due(40L))
    }

    @Test fun hasSeenCoversPendingDeliveredDeadAndSupersededKeys() {
        val queue = TelegramOutbox(temporary.newFile("seen.v1"))
        val pending = textEntry("pending", 1L)
        val delivered = textEntry("delivered", 2L)
        val dead = textEntry("dead", 3L)
        val replaced = textEntry("replaced", 4L).copy(coalesceKey = "idle-current")
        val latest = textEntry("latest", 5L).copy(coalesceKey = "idle-current")

        queue.enqueue(pending)
        queue.enqueue(delivered)
        queue.acknowledge(delivered.idempotencyKey, 10L)
        queue.enqueue(dead)
        queue.deadLetter(dead.idempotencyKey, "terminal", 11L)
        queue.enqueueLatestText(replaced)
        queue.enqueueLatestText(latest)

        assertTrue(queue.hasSeen(pending.idempotencyKey))
        assertTrue(queue.hasSeen(delivered.idempotencyKey))
        assertTrue(queue.hasSeen(dead.idempotencyKey))
        assertTrue(queue.hasSeen(replaced.idempotencyKey))
        assertFalse(queue.hasSeen("never-seen"))
    }

    @Test fun peerControlsAndDeliveryAcksUseIndependentReservedCapacity() {
        val journal = temporary.newFile("reserved-controls.v1")
        val queue = TelegramOutbox(
            journal,
            maxPendingEntries = 1,
            reservedPeerTextEntries = 1,
            reservedPriorityPeerControlEntries = 3,
        )
        assertEquals(TelegramEnqueueResult.ENQUEUED, queue.enqueue(textEntry("ordinary", 1L)))
        assertEquals(TelegramEnqueueResult.QUEUE_FULL, queue.enqueue(textEntry("ordinary-2", 2L)))

        val ping = peerControl("telegram-peer-control:pair:ping:nonce_123", "nonce_123", 3L)
        val connect = peerControl("telegram-peer-control:pair:connect:request_123", "request_123", 4L)
        val accept = peerControl("telegram-peer-control:pair:accept:request_123", "accept_request_123", 5L)
        val pong = peerControl("telegram-peer-control:pair:pong:nonce_456", "pong_nonce_456", 6L)
        val received = peerControl(
            "telegram-peer-received:pair_transfer_update_123",
            "received_transfer_123",
            7L,
        )
        assertEquals(TelegramEnqueueResult.ENQUEUED, queue.enqueuePeerLinkControl(ping))
        assertEquals(TelegramEnqueueResult.ENQUEUED, queue.enqueuePeerLinkControl(connect))
        assertEquals(TelegramEnqueueResult.ENQUEUED, queue.enqueuePeerLinkControl(accept))
        assertEquals(TelegramEnqueueResult.ENQUEUED, queue.enqueuePeerLinkControl(pong))
        assertEquals(TelegramEnqueueResult.ENQUEUED, queue.enqueuePeerLinkControl(received))

        assertEquals(
            setOf("ordinary", received.idempotencyKey, connect.idempotencyKey, accept.idempotencyKey, pong.idempotencyKey),
            TelegramOutbox(
                journal,
                maxPendingEntries = 1,
                reservedPeerTextEntries = 1,
                reservedPriorityPeerControlEntries = 3,
            )
                .pendingSnapshot().mapTo(linkedSetOf(), TelegramOutboxEntry::idempotencyKey),
        )
        assertEquals(TelegramEnqueueResult.PREVIOUSLY_SUPERSEDED, queue.enqueuePeerLinkControl(ping))
    }

    @Test fun genericPeerTextStaysInRegularLaneAndCannotBeSupersededByControls() {
        val queue = TelegramOutbox(
            temporary.newFile("peer-chat-regular-lane.v1"),
            maxPendingEntries = 1,
            reservedPeerTextEntries = 1,
        )
        val peerChat = peerControl("telegram-peer-chat:message_123", "message_123", 1L)
        val ping = peerControl("telegram-peer-control:pair:ping:nonce_123", "nonce_123", 2L)
        val pong = peerControl("telegram-peer-control:pair:pong:nonce_456", "pong_nonce_456", 3L)

        assertEquals(TelegramEnqueueResult.ENQUEUED, queue.enqueue(peerChat))
        assertEquals(TelegramEnqueueResult.ENQUEUED, queue.enqueuePeerLinkControl(ping))
        assertEquals(TelegramEnqueueResult.ENQUEUED, queue.enqueuePeerLinkControl(pong))
        assertEquals(
            setOf(peerChat.idempotencyKey, ping.idempotencyKey, pong.idempotencyKey),
            queue.pendingSnapshot().mapTo(linkedSetOf(), TelegramOutboxEntry::idempotencyKey),
        )
    }

    @Test fun deliveryAckAndPriorityProbeUseIndependentReservedCapacity() {
        val queue = TelegramOutbox(
            temporary.newFile("delivery-ack-reserve.v1"),
            maxPendingEntries = 1,
            reservedPeerTextEntries = 1,
        )
        val ping = peerControl("telegram-peer-control:pair:ping:nonce_123", "nonce_123", 1L)
        val received = peerControl(
            "telegram-peer-received:pair_transfer_update_123",
            "received_transfer_123",
            2L,
        )

        assertEquals(TelegramEnqueueResult.ENQUEUED, queue.enqueuePeerLinkControl(ping))
        assertEquals(TelegramEnqueueResult.ENQUEUED, queue.enqueuePeerLinkControl(received))
        assertEquals(
            setOf(ping.idempotencyKey, received.idempotencyKey),
            queue.pendingSnapshot().mapTo(linkedSetOf(), TelegramOutboxEntry::idempotencyKey),
        )
        assertEquals(TelegramEnqueueResult.ALREADY_PENDING, queue.enqueuePeerLinkControl(ping))
    }

    @Test fun priorityResponseCapacityIsIndependentFromDeliveryAcknowledgements() {
        val queue = TelegramOutbox(
            temporary.newFile("essential-response-reserve.v1"),
            maxPendingEntries = 1,
            reservedPeerTextEntries = 1,
            reservedPriorityPeerControlEntries = 1,
        )
        val received = peerControl(
            "telegram-peer-received:pair_transfer_update_123",
            "received_transfer_123",
            1L,
        )
        val pong = peerControl("telegram-peer-control:pair:pong:nonce_456", "pong_nonce_456", 2L)
        val accept = peerControl("telegram-peer-control:pair:accept:request_456", "accept_request_456", 3L)

        assertEquals(TelegramEnqueueResult.ENQUEUED, queue.enqueuePeerLinkControl(received))
        assertEquals(TelegramEnqueueResult.ENQUEUED, queue.enqueuePeerLinkControl(pong))
        assertEquals(TelegramEnqueueResult.QUEUE_FULL, queue.enqueuePeerLinkControl(accept))
        assertEquals(
            setOf(received.idempotencyKey, pong.idempotencyKey),
            queue.pendingSnapshot().mapTo(linkedSetOf(), TelegramOutboxEntry::idempotencyKey),
        )
    }

    @Test fun parentRoleCancellationRemovesEveryParentKindButLeavesPeerTraffic() {
        val journal = temporary.newFile("cancel-parent.v1")
        val queue = TelegramOutbox(journal)
        val parentText = textEntry("hourly-report", 1L)
        val parentDocument = documentEntry("submission", "submission-parent.png", 2L)
        val peer = peerControl("telegram-peer-control:pair:pong:nonce_123", "pong_nonce_123", 3L)
        queue.enqueue(parentText)
        queue.enqueue(parentDocument)
        queue.enqueuePeerLinkControl(peer)

        assertEquals(
            setOf(parentText.idempotencyKey, parentDocument.idempotencyKey),
            queue.cancelParentEntries(10L).mapTo(linkedSetOf(), TelegramOutboxEntry::idempotencyKey),
        )
        assertEquals(listOf(peer.idempotencyKey), TelegramOutbox(journal).pendingSnapshot().map { it.idempotencyKey })
    }

    private fun textEntry(key: String, createdAt: Long) = TelegramOutboxEntry(
        idempotencyKey = key,
        destinationChatId = 7L,
        kind = TelegramOutboxKind.TEXT,
        filePath = null,
        text = "hello",
        mimeType = null,
        displayName = null,
        attempts = 0,
        nextAttemptEpochMs = createdAt,
        createdAtEpochMs = createdAt,
        deleteAfterSend = false,
    )

    private fun peerControl(key: String, transferId: String, createdAt: Long) = TelegramOutboxEntry(
        idempotencyKey = key,
        destinationChatId = 202L,
        kind = TelegramOutboxKind.TEXT,
        filePath = null,
        text = "control",
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

    private fun peerDocument(
        key: String,
        transferId: String,
        name: String,
        createdAt: Long,
    ) = TelegramOutboxEntry(
        idempotencyKey = key,
        destinationChatId = 202L,
        kind = TelegramOutboxKind.DOCUMENT,
        filePath = temporary.newFile(name).absolutePath,
        text = "MNPEER2 pair_123 $transferId STUDENT_MEMO",
        mimeType = TelegramPeerProtocol.CIPHERTEXT_MIME,
        displayName = name,
        attempts = 0,
        nextAttemptEpochMs = createdAt,
        createdAtEpochMs = createdAt,
        deleteAfterSend = true,
        coalesceKey = "STUDENT_MEMO:page:4:memo",
        route = TelegramOutboxRoute.PEER,
        destinationUsername = "teacher_bot",
        peerTransferId = transferId,
    )
}
