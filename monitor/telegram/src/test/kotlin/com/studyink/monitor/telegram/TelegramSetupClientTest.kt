package com.studyink.monitor.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TelegramSetupClientTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun pairingTailsOldMessagesAndAcceptsOnlyNewPrivateConnect() {
        val fake = FakeApi()
        fake.responses.addLast(listOf(update(8L, "/연결"))) // ignored tail
        fake.responses.addLast(listOf(
            update(9L, "/연결", chatId = -100L, chatType = "group"),
            update(10L, "/연결", chatId = 77L, chatType = "private"),
        ))
        val persistence = FakeCredentials()
        val offsets = TelegramUpdateOffsetStore(temporary.newFile("offset"))
        val client = TelegramSetupClient(persistence, offsets) { fake }

        val session = client.beginPairing(validToken())
        val request = client.pollForConnection(session, 0)!!

        assertEquals(listOf(-1L, 9L), fake.requestedOffsets)
        assertEquals(10L, request.updateId)
        assertEquals(77L, request.chatId)
        val saved = client.completePairing(session, request)
        assertEquals(77L, saved.allowedPrivateChatId)
        assertEquals(11L, offsets.load(botFingerprint(validToken())))
        assertEquals(77L, persistence.value?.allowedPrivateChatId)
    }

    @Test fun pairingCommandParserDoesNotAcceptOrdinaryText() {
        val fake = FakeApi().apply {
            responses.addLast(emptyList())
            responses.addLast(listOf(update(1L, "안녕")))
        }
        val client = TelegramSetupClient(
            FakeCredentials(),
            TelegramUpdateOffsetStore(temporary.newFile("offset2")),
        ) { fake }
        val session = client.beginPairing(validToken())
        assertNull(client.pollForConnection(session, 0))
    }

    private fun validToken() = "123456:${"a".repeat(24)}"

    private fun update(
        id: Long,
        text: String,
        chatId: Long = 77L,
        chatType: String = "private",
    ) = TelegramInboundUpdate(
        updateId = id,
        messageId = id,
        chatId = chatId,
        chatType = chatType,
        text = text,
        senderIsBot = false,
        senderDisplayName = "아빠",
        senderUsername = "parent",
        sentAtEpochSeconds = 1L,
    )

    private class FakeCredentials : TelegramCredentialPersistence {
        var value: TelegramCredentials? = null
        override fun save(credentials: TelegramCredentials) { value = credentials }
        override fun load(): TelegramCredentials? = value
        override fun clear() { value = null }
    }

    private class FakeApi : TelegramBotApi {
        val responses = ArrayDeque<List<TelegramInboundUpdate>>()
        val requestedOffsets = mutableListOf<Long>()
        override fun getMe() = TelegramBotIdentity(1L, "test_bot", "Bot")
        override fun deleteWebhook() = Unit
        override fun getUpdates(offset: Long, timeoutSeconds: Int): List<TelegramInboundUpdate> {
            requestedOffsets += offset
            return responses.removeFirst()
        }
        override fun sendMessage(chatId: Long, text: String) = TelegramSendResult(1L)
        override fun sendDocument(chatId: Long, document: File, caption: String, mimeType: String, displayName: String) = TelegramSendResult(1L)
        override fun sendVoice(chatId: Long, voice: File, caption: String, mimeType: String, displayName: String) = TelegramSendResult(1L)
    }
}
