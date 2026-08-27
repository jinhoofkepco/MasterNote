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

    @Test fun peerLinkControlsUseReservedCapacityAndSupersedeOldProbeFirst() {
        val journal = temporary.newFile("reserved-controls.v1")
        val queue = TelegramOutbox(journal, maxPendingEntries = 1, reservedPeerTextEntries = 2)
        assertEquals(TelegramEnqueueResult.ENQUEUED, queue.enqueue(textEntry("ordinary", 1L)))
        assertEquals(TelegramEnqueueResult.QUEUE_FULL, queue.enqueue(textEntry("ordinary-2", 2L)))

        val ping = peerControl("telegram-peer-control:pair:ping:nonce_123", "nonce_123", 3L)
        val accept = peerControl("telegram-peer-control:pair:accept:request_123", "accept_request_123", 4L)
        val pong = peerControl("telegram-peer-control:pair:pong:nonce_456", "pong_nonce_456", 5L)
        assertEquals(TelegramEnqueueResult.ENQUEUED, queue.enqueuePeerLinkControl(ping))
        assertEquals(TelegramEnqueueResult.ENQUEUED, queue.enqueuePeerLinkControl(accept))
        assertEquals(TelegramEnqueueResult.ENQUEUED, queue.enqueuePeerLinkControl(pong))

        assertEquals(
            setOf("ordinary", accept.idempotencyKey, pong.idempotencyKey),
            TelegramOutbox(journal, maxPendingEntries = 1, reservedPeerTextEntries = 2)
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
            setOf(peerChat.idempotencyKey, pong.idempotencyKey),
            queue.pendingSnapshot().mapTo(linkedSetOf(), TelegramOutboxEntry::idempotencyKey),
        )
    }

    @Test fun deliveryAckUsesReservedCapacityAndSupersedesAStaleProbe() {
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
        assertEquals(listOf(received.idempotencyKey), queue.pendingSnapshot().map { it.idempotencyKey })
        assertEquals(TelegramEnqueueResult.PREVIOUSLY_SUPERSEDED, queue.enqueuePeerLinkControl(ping))
    }

    @Test fun essentialReservedResponsesCannotSupersedeEachOther() {
        val queue = TelegramOutbox(
            temporary.newFile("essential-response-reserve.v1"),
            maxPendingEntries = 1,
            reservedPeerTextEntries = 1,
        )
        val received = peerControl(
            "telegram-peer-received:pair_transfer_update_123",
            "received_transfer_123",
            1L,
        )
        val pong = peerControl("telegram-peer-control:pair:pong:nonce_456", "pong_nonce_456", 2L)

        assertEquals(TelegramEnqueueResult.ENQUEUED, queue.enqueuePeerLinkControl(received))
        assertEquals(TelegramEnqueueResult.QUEUE_FULL, queue.enqueuePeerLinkControl(pong))
        assertEquals(listOf(received.idempotencyKey), queue.pendingSnapshot().map { it.idempotencyKey })
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
}
