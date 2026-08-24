package com.studyink.monitor.telegram

import com.studyink.monitor.core.ParentCommandParser

class TelegramPairingSession internal constructor(
    internal val botToken: String,
    val bot: TelegramBotIdentity,
    internal var nextOffset: Long,
) {
    override fun toString(): String =
        "TelegramPairingSession(botToken=<redacted>, bot=$bot, nextOffset=$nextOffset)"
}

/**
 * Setup-only client. It validates a bot, tails existing updates so old messages cannot be replayed,
 * and accepts a new `/연결` only from a non-bot private chat.
 */
class TelegramSetupClient(
    private val credentialStore: TelegramCredentialPersistence,
    private val offsetStore: TelegramUpdateOffsetStore,
    private val apiFactory: (String) -> TelegramBotApi = ::HttpTelegramBotApi,
) {
    fun validateBotToken(rawToken: String): TelegramBotIdentity {
        val token = normalizeToken(rawToken)
        return apiFactory(token).use(TelegramBotApi::getMe)
    }

    /**
     * Starts after the newest existing update. A `/연결` sent before this call is intentionally
     * forgotten and can never pair the app by accident.
     */
    fun beginPairing(rawToken: String): TelegramPairingSession {
        val token = normalizeToken(rawToken)
        val (bot, tail) = apiFactory(token).use { api ->
            val identity = api.getMe()
            api.deleteWebhook()
            identity to api.getUpdates(offset = -1L, timeoutSeconds = 0)
        }
        val nextOffset = tail.maxOfOrNull(TelegramInboundUpdate::updateId)?.plus(1L) ?: 0L
        return TelegramPairingSession(token, bot, nextOffset)
    }

    fun pollForConnection(
        session: TelegramPairingSession,
        timeoutSeconds: Int = 20,
    ): TelegramPairingRequest? {
        require(timeoutSeconds in 0..50)
        val updates = apiFactory(session.botToken).use { api ->
            api.getUpdates(session.nextOffset, timeoutSeconds)
        }
            .sortedBy(TelegramInboundUpdate::updateId)
        if (updates.isNotEmpty()) {
            session.nextOffset = updates.last().updateId + 1L
        }
        return updates.asReversed().firstNotNullOfOrNull { update ->
            if (update.chatType != "private" || update.chatId == null || update.senderIsBot ||
                update.text?.let(ParentCommandParser::isPairingRequest) != true
            ) {
                null
            } else {
                TelegramPairingRequest(
                    updateId = update.updateId,
                    chatId = update.chatId,
                    displayName = update.senderDisplayName?.takeIf(String::isNotBlank)
                        ?: "텔레그램 사용자",
                    username = update.senderUsername,
                )
            }
        }
    }

    /** Saves credentials and the consumed tail atomically per file; the durable outbox is retained. */
    fun completePairing(
        session: TelegramPairingSession,
        request: TelegramPairingRequest,
    ): TelegramCredentials {
        require(request.updateId < session.nextOffset) { "The pairing request was not consumed by this session." }
        val credentials = TelegramCredentials(
            botToken = session.botToken,
            allowedPrivateChatId = request.chatId,
            chatLabel = buildString {
                append(request.displayName)
                request.username?.let { append(" (@").append(it).append(')') }
            },
            localBotId = session.bot.id,
            localBotUsername = session.bot.username?.let(::normalizeTelegramUsername)
                ?: error("Telegram bot username is required for remote review."),
        )
        credentialStore.save(credentials)
        offsetStore.commit(botFingerprint(session.botToken), session.nextOffset)
        return credentials
    }

    fun sendConnectionTest(credentials: TelegramCredentials) {
        apiFactory(credentials.botToken).use { api ->
            api.sendMessage(
                credentials.allowedPrivateChatId,
                "MasterNote 학생 태블릿 연결 완료 · /화면 명령으로 현재 시험지를 받을 수 있습니다.",
            )
        }
    }

    private fun normalizeToken(rawToken: String): String {
        val token = rawToken.trim()
        require(TOKEN.matches(token)) { "BotFather가 발급한 봇 토큰 형식이 아닙니다." }
        return token
    }

    private companion object {
        val TOKEN = Regex("^[0-9]{6,}:[A-Za-z0-9_-]{20,}$")
    }
}
