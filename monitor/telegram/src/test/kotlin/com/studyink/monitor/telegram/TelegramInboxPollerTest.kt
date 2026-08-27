package com.studyink.monitor.telegram

import com.studyink.monitor.core.ParentInboundAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TelegramInboxPollerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun onlyPairedPrivateHumanTextIsHandledAndOffsetIsCommitted() {
        val token = "123456:${"p".repeat(24)}"
        val credentials = TelegramCredentials(token, 77L, "parent")
        val updates = listOf(
            update(1L, 77L, "group", false, "group"),
            update(2L, 88L, "private", false, "wrong chat"),
            update(3L, 77L, "private", true, "bot"),
            update(4L, 77L, "private", false, "잘 하고 있어"),
        )
        val api = PollingFakeApi(updates)
        val offsetStore = TelegramUpdateOffsetStore(temporary.newFile("offset"))
        val handled = mutableListOf<ParentInboundAction>()
        val handledLatch = CountDownLatch(1)
        val poller = TelegramInboxPoller(
            credentials,
            api,
            offsetStore,
            TelegramConnectionTracker(
                TelegramConnectionStateStore(temporary.newFile("connection")),
            ) {},
            TelegramInboundHandler { _, action -> handled += action; handledLatch.countDown() },
            nowEpochMs = { 1L },
            jitter = TelegramJitterSource { 0.0 },
            onFatalError = { throw AssertionError(it) },
        )

        assertTrue(poller.start())
        assertTrue(handledLatch.await(2L, TimeUnit.SECONDS))
        repeat(100) {
            if (offsetStore.load(botFingerprint(token)) == 5L) return@repeat
            Thread.sleep(5L)
        }
        poller.close()

        assertEquals(listOf(ParentInboundAction.Text("잘 하고 있어")), handled)
        assertEquals(5L, offsetStore.load(botFingerprint(token)))
    }

    @Test fun processOwnershipAllowsOnlyOnePollerPerBotFingerprint() {
        val token = "123456:${"q".repeat(24)}"
        val first = TelegramBotPollOwnership.acquire(token)
        try {
            assertNotNull(first)
            assertNull(TelegramBotPollOwnership.acquire(token))
        } finally {
            first?.let(TelegramBotPollOwnership::release)
        }
        val reacquired = TelegramBotPollOwnership.acquire(token)
        assertNotNull(reacquired)
        reacquired?.let(TelegramBotPollOwnership::release)
    }

    @Test fun peerRouteRequiresPinnedNumericIdUsernameAndPrivateChatId() {
        val token = "123456:${"r".repeat(24)}"
        val credentials = TelegramCredentials(
            token,
            77L,
            "parent",
            peerBotId = 202L,
            peerBotUsername = "teacher_bot",
        )
        fun peerUpdate(id: Long, senderId: Long, username: String, chatId: Long = senderId) =
            TelegramInboundUpdate(
                updateId = id,
                messageId = id,
                chatId = chatId,
                chatType = "private",
                text = "${TelegramPeerProtocol.VERSION} RECEIVED pair_identifier_123 transfer_123 invalid",
                senderIsBot = true,
                senderDisplayName = "peer",
                senderUsername = username,
                sentAtEpochSeconds = 1L,
                senderId = senderId,
            )
        val api = PollingFakeApi(
            listOf(
                peerUpdate(1L, 999L, "teacher_bot"),
                peerUpdate(2L, 202L, "other_teacher"),
                peerUpdate(3L, 202L, "teacher_bot", chatId = 999L),
                peerUpdate(4L, 202L, "teacher_bot"),
            ),
        )
        val latch = CountDownLatch(1)
        val handled = mutableListOf<Long>()
        val poller = TelegramInboxPoller(
            credentials = credentials,
            api = api,
            offsetStore = TelegramUpdateOffsetStore(temporary.newFile("peer-offset")),
            connectionTracker = TelegramConnectionTracker(
                TelegramConnectionStateStore(temporary.newFile("peer-connection")),
            ) {},
            handler = TelegramInboundHandler { _, _ -> },
            nowEpochMs = { 1L },
            jitter = TelegramJitterSource { 0.0 },
            onFatalError = { throw AssertionError(it) },
            peerHandler = TelegramPeerInboundHandler { update -> handled += update.updateId; latch.countDown() },
        )

        assertTrue(poller.start())
        assertTrue(latch.await(2L, TimeUnit.SECONDS))
        poller.close()

        assertEquals(listOf(4L), handled)
    }

    @Test fun wakeInterruptsRetryBackoffWithoutLosingAnEarlySignal() {
        val token = "123456:${"w".repeat(24)}"
        val firstFailure = CountDownLatch(1)
        val secondPoll = CountDownLatch(1)
        val releaseSecondPoll = CountDownLatch(1)
        val calls = AtomicInteger()
        val api = object : TelegramBotApi {
            override fun getMe() = error("unused")
            override fun deleteWebhook() = Unit
            override fun getUpdates(offset: Long, timeoutSeconds: Int): List<TelegramInboundUpdate> {
                if (calls.getAndIncrement() == 0) {
                    firstFailure.countDown()
                    throw TelegramApiException(500, "temporary")
                }
                secondPoll.countDown()
                releaseSecondPoll.await(5L, TimeUnit.SECONDS)
                return emptyList()
            }
            override fun sendMessage(chatId: Long, text: String) = error("unused")
            override fun sendDocument(chatId: Long, document: File, caption: String, mimeType: String, displayName: String) = error("unused")
            override fun sendVoice(chatId: Long, voice: File, caption: String, mimeType: String, displayName: String) = error("unused")
            override fun close() { releaseSecondPoll.countDown() }
        }
        val poller = TelegramInboxPoller(
            credentials = TelegramCredentials(token, 77L, "parent"),
            api = api,
            offsetStore = TelegramUpdateOffsetStore(temporary.newFile("wake-offset")),
            connectionTracker = TelegramConnectionTracker(
                TelegramConnectionStateStore(temporary.newFile("wake-connection")),
            ) {},
            handler = TelegramInboundHandler { _, _ -> },
            nowEpochMs = { 1L },
            jitter = TelegramJitterSource { 0.0 },
            onFatalError = { throw AssertionError(it) },
        )

        assertTrue(poller.start())
        assertTrue(firstFailure.await(1L, TimeUnit.SECONDS))
        assertFalse(secondPoll.await(100L, TimeUnit.MILLISECONDS))
        poller.wake()
        assertTrue(secondPoll.await(1L, TimeUnit.SECONDS))
        poller.close()
    }

    @Test fun inboundHandlerFailureLeavesOffsetForTheSameUpdateToRetry() {
        val token = "123456:${"x".repeat(24)}"
        val inbound = update(1L, 77L, "private", false, "다시 시도")
        val firstFailure = CountDownLatch(1)
        val secondHandling = CountDownLatch(1)
        val releasePoll = CountDownLatch(1)
        val handlerCalls = AtomicInteger()
        val api = object : TelegramBotApi {
            override fun getMe() = error("unused")
            override fun deleteWebhook() = Unit
            override fun getUpdates(offset: Long, timeoutSeconds: Int): List<TelegramInboundUpdate> {
                if (offset <= inbound.updateId) return listOf(inbound)
                releasePoll.await(5L, TimeUnit.SECONDS)
                return emptyList()
            }
            override fun sendMessage(chatId: Long, text: String) = error("unused")
            override fun sendDocument(chatId: Long, document: File, caption: String, mimeType: String, displayName: String) = error("unused")
            override fun sendVoice(chatId: Long, voice: File, caption: String, mimeType: String, displayName: String) = error("unused")
            override fun close() { releasePoll.countDown() }
        }
        val offsetStore = TelegramUpdateOffsetStore(temporary.newFile("handler-retry-offset"))
        val poller = TelegramInboxPoller(
            credentials = TelegramCredentials(token, 77L, "parent"),
            api = api,
            offsetStore = offsetStore,
            connectionTracker = TelegramConnectionTracker(
                TelegramConnectionStateStore(temporary.newFile("handler-retry-connection")),
            ) {},
            handler = TelegramInboundHandler { _, _ ->
                if (handlerCalls.getAndIncrement() == 0) {
                    firstFailure.countDown()
                    throw IllegalStateException("durable peer response queue is full")
                }
                secondHandling.countDown()
            },
            nowEpochMs = { 1L },
            jitter = TelegramJitterSource { 0.0 },
            onFatalError = { throw AssertionError(it) },
        )

        assertTrue(poller.start())
        assertTrue(firstFailure.await(1L, TimeUnit.SECONDS))
        assertEquals(0L, offsetStore.load(botFingerprint(token)))
        poller.wake()
        assertTrue(secondHandling.await(1L, TimeUnit.SECONDS))
        repeat(100) {
            if (offsetStore.load(botFingerprint(token)) == 2L) return@repeat
            Thread.sleep(5L)
        }
        poller.close()

        assertEquals(2, handlerCalls.get())
        assertEquals(2L, offsetStore.load(botFingerprint(token)))
    }

    private fun update(
        id: Long,
        chatId: Long,
        chatType: String,
        senderIsBot: Boolean,
        text: String,
    ) = TelegramInboundUpdate(
        id,
        id,
        chatId,
        chatType,
        text,
        senderIsBot,
        "parent",
        null,
        1L,
    )

    private class PollingFakeApi(private val first: List<TelegramInboundUpdate>) : TelegramBotApi {
        private val calls = AtomicInteger()
        override fun getMe() = error("unused")
        override fun deleteWebhook() = Unit
        override fun getUpdates(offset: Long, timeoutSeconds: Int): List<TelegramInboundUpdate> {
            if (calls.getAndIncrement() == 0) return first
            Thread.sleep(10_000L)
            return emptyList()
        }
        override fun sendMessage(chatId: Long, text: String) = error("unused")
        override fun sendDocument(chatId: Long, document: File, caption: String, mimeType: String, displayName: String) = error("unused")
        override fun sendVoice(chatId: Long, voice: File, caption: String, mimeType: String, displayName: String) = error("unused")
    }
}
