package com.studyink.monitor.telegram

import java.io.File

class TelegramCredentials(
    val botToken: String,
    val allowedPrivateChatId: Long,
    val chatLabel: String,
    val peerBotId: Long? = null,
    val peerBotUsername: String? = null,
    val localBotId: Long? = null,
    val localBotUsername: String? = null,
    val remoteReviewRole: RemoteReviewRole? = null,
    val peerPairId: String? = null,
    val peerSharedKeyBase64: String? = null,
    val peerPairingExpiresAtEpochMs: Long? = null,
) {
    init {
        require(botToken.isNotBlank()) { "botToken must not be blank." }
        require(!botToken.any(Char::isWhitespace)) { "botToken must not contain whitespace." }
        require(allowedPrivateChatId != 0L) { "allowedPrivateChatId must not be zero." }
        require(chatLabel.length <= 120) { "chatLabel is too long." }
        require((peerBotId == null) == (peerBotUsername == null)) {
            "peer bot id and username must be configured together."
        }
        require(peerBotId == null || peerBotId > 0L) { "peerBotId must be positive." }
        require(peerBotUsername == null || normalizeTelegramUsername(peerBotUsername) == peerBotUsername) {
            "peerBotUsername must be normalized and valid."
        }
        require((localBotId == null) == (localBotUsername == null)) {
            "local bot id and username must be configured together."
        }
        require(localBotId == null || localBotId > 0L) { "localBotId must be positive." }
        require(localBotUsername == null || normalizeTelegramUsername(localBotUsername) == localBotUsername) {
            "localBotUsername must be normalized and valid."
        }
        val pairingFields = listOf(remoteReviewRole, peerPairId, peerSharedKeyBase64, peerPairingExpiresAtEpochMs)
        require(pairingFields.all { it == null } || pairingFields.all { it != null }) {
            "remote-review pairing fields must be configured together."
        }
        require(peerPairId == null || PEER_IDENTIFIER.matches(peerPairId))
        require(peerSharedKeyBase64 == null || decodePeerKey(peerSharedKeyBase64).size == 32)
        require(peerPairingExpiresAtEpochMs == null || peerPairingExpiresAtEpochMs > 0L)
    }

    val peerBinding: TelegramPeerBinding?
        get() = peerBotId?.let { TelegramPeerBinding(it, requireNotNull(peerBotUsername)) }

    fun withPeer(binding: TelegramPeerBinding?): TelegramCredentials = TelegramCredentials(
        botToken = botToken,
        allowedPrivateChatId = allowedPrivateChatId,
        chatLabel = chatLabel,
        peerBotId = binding?.botId,
        peerBotUsername = binding?.username,
        localBotId = localBotId,
        localBotUsername = localBotUsername,
        remoteReviewRole = remoteReviewRole,
        peerPairId = peerPairId,
        peerSharedKeyBase64 = peerSharedKeyBase64,
        peerPairingExpiresAtEpochMs = peerPairingExpiresAtEpochMs,
    )

    fun withLocalBot(identity: TelegramBotIdentity): TelegramCredentials {
        val username = requireNotNull(identity.username) { "The Telegram bot has no username." }
        return TelegramCredentials(
            botToken = botToken,
            allowedPrivateChatId = allowedPrivateChatId,
            chatLabel = chatLabel,
            peerBotId = peerBotId,
            peerBotUsername = peerBotUsername,
            localBotId = identity.id,
            localBotUsername = normalizeTelegramUsername(username),
            remoteReviewRole = remoteReviewRole,
            peerPairId = peerPairId,
            peerSharedKeyBase64 = peerSharedKeyBase64,
            peerPairingExpiresAtEpochMs = peerPairingExpiresAtEpochMs,
        )
    }

    fun withRemoteReviewPairing(
        role: RemoteReviewRole,
        pairId: String,
        sharedKeyBase64: String,
        expiresAtEpochMs: Long,
        binding: TelegramPeerBinding? = peerBinding,
    ): TelegramCredentials = TelegramCredentials(
        botToken = botToken,
        allowedPrivateChatId = allowedPrivateChatId,
        chatLabel = chatLabel,
        peerBotId = binding?.botId,
        peerBotUsername = binding?.username,
        localBotId = localBotId,
        localBotUsername = localBotUsername,
        remoteReviewRole = role,
        peerPairId = pairId,
        peerSharedKeyBase64 = sharedKeyBase64,
        peerPairingExpiresAtEpochMs = expiresAtEpochMs,
    )

    fun withoutRemoteReview(): TelegramCredentials = TelegramCredentials(
        botToken = botToken,
        allowedPrivateChatId = allowedPrivateChatId,
        chatLabel = chatLabel,
        localBotId = localBotId,
        localBotUsername = localBotUsername,
    )

    fun peerSharedKey(): ByteArray? = peerSharedKeyBase64?.let(::decodePeerKey)

    // Never put a Bot API token into crash logs through a generated data-class toString().
    override fun toString(): String =
        "TelegramCredentials(botToken=<redacted>, allowedPrivateChatId=$allowedPrivateChatId, " +
            "chatLabel=$chatLabel, peerBotId=$peerBotId, peerBotUsername=$peerBotUsername, " +
            "localBotId=$localBotId, localBotUsername=$localBotUsername, " +
            "remoteReviewRole=$remoteReviewRole, peerPairId=$peerPairId, peerSharedKey=<redacted>)"
}

enum class RemoteReviewRole { STUDENT, TEACHER }

data class TelegramPeerBinding(
    val botId: Long,
    val username: String,
) {
    init {
        require(botId > 0L)
        require(username == normalizeTelegramUsername(username))
    }
}

fun normalizeTelegramUsername(value: String): String {
    val normalized = value.trim().removePrefix("@").lowercase()
    require(TELEGRAM_USERNAME.matches(normalized)) { "Invalid Telegram bot username." }
    return normalized
}

data class TelegramBotIdentity(
    val id: Long,
    val username: String?,
    val displayName: String,
)

data class TelegramInboundUpdate(
    val updateId: Long,
    val messageId: Long?,
    val chatId: Long?,
    val chatType: String?,
    val text: String?,
    val senderIsBot: Boolean,
    val senderDisplayName: String?,
    val senderUsername: String?,
    val sentAtEpochSeconds: Long?,
    val senderId: Long? = null,
    val caption: String? = null,
    val document: TelegramInboundDocument? = null,
    val replyToMessageId: Long? = null,
)

data class TelegramInboundDocument(
    val fileId: String,
    val fileUniqueId: String,
    val fileName: String?,
    val mimeType: String?,
    val fileSizeBytes: Long?,
) {
    init {
        require(fileId.isNotBlank() && fileId.length <= 512)
        require(fileUniqueId.isNotBlank() && fileUniqueId.length <= 256)
        require(fileName == null || fileName.length <= 240)
        require(mimeType == null || mimeType.length <= 120)
        require(fileSizeBytes == null || fileSizeBytes >= 0L)
    }
}

data class TelegramDownloadedFile(
    val file: File,
    val byteCount: Long,
    val telegramFilePath: String,
)

data class TelegramSendResult(val messageId: Long?)

enum class TelegramOutboxRoute { PARENT, PEER }

data class TelegramDeliveryReceipt(
    val transferId: String,
    val outboxKey: String,
    val telegramMessageId: Long?,
    val sentAtEpochMs: Long,
    /** Telegram accepted this upload; absence of a peer ACK must never trigger another upload. */
    val serverAcceptedAtEpochMs: Long? = null,
    val acknowledgedAtEpochMs: Long? = null,
    val acknowledgementMessageId: Long? = null,
)

/** Path-free view of one durable encrypted peer document waiting in the local outbox. */
data class PendingTelegramPeerDocumentTransfer(
    val transferId: String,
    val payloadType: String,
    val createdAtEpochMs: Long,
    val nextAttemptEpochMs: Long,
    val attempts: Int,
    val ciphertextBytes: Long,
) {
    init {
        require(PEER_IDENTIFIER.matches(transferId))
        require(PEER_PAYLOAD_TYPE.matches(payloadType))
        require(createdAtEpochMs >= 0L && nextAttemptEpochMs >= 0L && attempts >= 0)
        require(ciphertextBytes >= 0L)
    }
}

data class PendingTelegramPeerDocument(
    val updateId: Long,
    val telegramMessageId: Long,
    val senderBotId: Long,
    val senderUsername: String,
    val transferId: String,
    val payloadType: String,
    val fileUniqueId: String,
    val originalFileName: String?,
    val mimeType: String?,
    val byteCount: Long,
    val localFilePath: String,
    val receivedAtEpochMs: Long,
    val replyToMessageId: Long? = null,
) {
    val file: File get() = File(localFilePath)
}

data class TelegramPairingRequest(
    val updateId: Long,
    val chatId: Long,
    val displayName: String,
    val username: String?,
)

/** A durable `/화면` request with the student's location frozen at command receipt time. */
data class PendingScreenRequest(
    val updateId: Long,
    val requestId: String,
    val chatId: Long,
    val requestedAtElapsedMs: Long,
    val active: Boolean,
    val bookId: String?,
    val pageNumber: Int?,
    val attemptNo: Int?,
) {
    init {
        require(updateId >= 0L)
        require(requestId.isNotBlank() && requestId.length <= 256)
        require(chatId != 0L)
        require(requestedAtElapsedMs >= 0L)
        require(pageNumber == null || pageNumber > 0)
        require(attemptNo == null || attemptNo > 0)
        if (active) {
            require(!bookId.isNullOrBlank())
            requireNotNull(pageNumber)
        }
    }
}

enum class TelegramOutboxKind { TEXT, DOCUMENT, VOICE }

data class TelegramOutboxEntry(
    val idempotencyKey: String,
    val destinationChatId: Long,
    val kind: TelegramOutboxKind,
    val filePath: String?,
    val text: String,
    val mimeType: String?,
    val displayName: String?,
    val attempts: Int,
    val nextAttemptEpochMs: Long,
    val createdAtEpochMs: Long,
    val deleteAfterSend: Boolean,
    val lastError: String? = null,
    val coalesceKey: String? = null,
    val route: TelegramOutboxRoute = TelegramOutboxRoute.PARENT,
    val destinationUsername: String? = null,
    val peerTransferId: String? = null,
) {
    val file: File? get() = filePath?.let(::File)
}

private val TELEGRAM_USERNAME = Regex("^[a-z0-9_]{5,32}$")
internal val PEER_IDENTIFIER = Regex("^[A-Za-z0-9_-]{8,128}$")
internal val PEER_PAYLOAD_TYPE = Regex("^[A-Z][A-Z0-9_]{0,39}$")

private fun decodePeerKey(value: String): ByteArray = runCatching {
    java.util.Base64.getUrlDecoder().decode(value)
}.getOrElse { throw IllegalArgumentException("Invalid peer key encoding.", it) }

data class TelegramDeadLetter(
    val entry: TelegramOutboxEntry,
    val reason: String,
    val failedAtEpochMs: Long,
)

enum class TelegramEnqueueResult {
    ENQUEUED,
    ALREADY_PENDING,
    ALREADY_DELIVERED,
    PREVIOUSLY_DEAD,
    PREVIOUSLY_SUPERSEDED,
    NOT_CONFIGURED,
    CHAT_CHANGED,
    QUEUE_FULL,
}

data class RemoteMonitorPreferences(
    val ttsEnabled: Boolean = false,
    val wakeVoiceEnabled: Boolean = false,
    val monitoringEnabled: Boolean = false,
    /** False is the low-noise default; the paired parent may temporarily enable live alerts. */
    val realtimeActivityEnabled: Boolean = false,
)

sealed interface RemoteMonitorStatus {
    data object NotConfigured : RemoteMonitorStatus
    data object Stopped : RemoteMonitorStatus
    data class Starting(val chatLabel: String) : RemoteMonitorStatus
    data class Connected(val chatLabel: String) : RemoteMonitorStatus
    data class Offline(val chatLabel: String, val sinceEpochMs: Long) : RemoteMonitorStatus
    data class Error(val chatLabel: String?, val reason: String) : RemoteMonitorStatus
}

fun interface RemoteMonitorStatusSubscription : AutoCloseable {
    override fun close()
}

class TelegramApiException(
    val statusCode: Int,
    message: String,
    val retryAfterSeconds: Long? = null,
    val indicatesConnectionFailure: Boolean = false,
) : java.io.IOException(message)
