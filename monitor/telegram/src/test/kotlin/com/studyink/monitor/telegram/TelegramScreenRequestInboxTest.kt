package com.studyink.monitor.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TelegramScreenRequestInboxTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun fifoSnapshotAndExactAcknowledgementSurviveRestart() {
        val file = temporary.newFile("screen-inbox.v1")
        val inbox = TelegramScreenRequestInbox(file)
        val first = request(10L, pageNumber = 7)
        val second = request(12L, pageNumber = 8)

        assertTrue(inbox.offer(first))
        assertFalse(inbox.offer(first))
        assertTrue(inbox.offer(second))
        assertFalse(inbox.offer(request(11L, pageNumber = 99)))
        assertEquals(listOf(first, second), TelegramScreenRequestInbox(file).pending())

        assertTrue(inbox.acknowledge(first.updateId))
        assertFalse(inbox.acknowledge(first.updateId))
        assertEquals(listOf(second), TelegramScreenRequestInbox(file).pending())
        assertTrue(TelegramScreenRequestInbox(file).acknowledge(second.updateId))

        val empty = TelegramScreenRequestInbox(file)
        assertTrue(empty.pending().isEmpty())
        assertFalse(empty.offer(second))
        assertTrue(empty.offer(request(13L, pageNumber = 9)))
    }

    @Test fun subscriptionReplaysEveryPendingRequestInFifoOrder() {
        val inbox = TelegramScreenRequestInbox(temporary.newFile("subscription.v1"))
        val first = request(20L, pageNumber = 3)
        val second = request(21L, pageNumber = 4)
        inbox.offer(first)
        inbox.offer(second)
        val observed = mutableListOf<PendingScreenRequest>()

        val subscription = inbox.subscribe { observed += it }
        val third = request(22L, pageNumber = 5)
        inbox.offer(third)
        subscription.close()
        inbox.offer(request(23L, pageNumber = 6))

        assertEquals(listOf(first, second, third), observed)
    }

    @Test fun fullQueueRejectsWithoutAdvancingHighestAcceptedUpdate() {
        val file = temporary.newFile("full.v1")
        val inbox = TelegramScreenRequestInbox(file, maxPendingRequests = 2)
        inbox.offer(request(30L, pageNumber = null, active = false, bookId = null))
        inbox.offer(request(31L, pageNumber = 2))

        val failure = runCatching { inbox.offer(request(32L, pageNumber = 3)) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(inbox.acknowledge(30L))
        assertTrue(inbox.offer(request(32L, pageNumber = 3)))
        assertEquals(listOf(31L, 32L), TelegramScreenRequestInbox(file).pending().map { it.updateId })
    }

    @Test fun connectionChangeClearRemovesOldRequestsAndResetsUpdateScopeDurably() {
        val file = temporary.newFile("clear.v1")
        val inbox = TelegramScreenRequestInbox(file)
        inbox.offer(request(100L, pageNumber = 12))

        inbox.clear()

        val replayed = TelegramScreenRequestInbox(file)
        assertTrue(replayed.pending().isEmpty())
        assertTrue(replayed.offer(request(2L, pageNumber = 1)))
    }

    private fun request(
        updateId: Long,
        pageNumber: Int?,
        active: Boolean = true,
        bookId: String? = "book-1",
    ) = PendingScreenRequest(
        updateId = updateId,
        requestId = "telegram-screen:$updateId",
        chatId = 7L,
        requestedAtElapsedMs = 100L + updateId,
        active = active,
        bookId = bookId,
        pageNumber = pageNumber,
        attemptNo = pageNumber?.let { 2 },
    )
}
