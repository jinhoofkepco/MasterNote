package com.studyink.monitor.telegram

/** Bounded, non-sensitive control names used by device diagnostics. */
internal enum class TelegramPeerControlKind {
    HELLO,
    PAIR_ACK,
    RECEIVED,
    CONNECT_REQUEST,
    CONNECT_ACCEPT,
    PING,
    PONG,
    DOCUMENT,
    OTHER,
}

internal fun telegramPeerControlKindHint(text: String?): TelegramPeerControlKind {
    val value = text?.trim().orEmpty()
    if (!value.startsWith("${TelegramPeerProtocol.VERSION} ")) return TelegramPeerControlKind.OTHER
    return when (value.substringAfter(' ').substringBefore(' ')) {
        "HELLO" -> TelegramPeerControlKind.HELLO
        "PAIR_ACK" -> TelegramPeerControlKind.PAIR_ACK
        "RECEIVED" -> TelegramPeerControlKind.RECEIVED
        "CONNECT_REQUEST" -> TelegramPeerControlKind.CONNECT_REQUEST
        "CONNECT_ACCEPT" -> TelegramPeerControlKind.CONNECT_ACCEPT
        "PING" -> TelegramPeerControlKind.PING
        "PONG" -> TelegramPeerControlKind.PONG
        "DOC" -> TelegramPeerControlKind.DOCUMENT
        else -> TelegramPeerControlKind.OTHER
    }
}

internal fun telegramPeerControlKind(entry: TelegramOutboxEntry): TelegramPeerControlKind =
    telegramPeerControlKindHint(entry.text)

internal enum class TelegramPeerTransportPhase { ATTEMPT, SENT, RETRY, DEAD }

internal enum class TelegramPeerTransportFailure {
    BOT_TO_BOT_DISABLED,
    RATE_LIMITED,
    CONFLICT,
    INBOX_FULL,
    RESPONSE_QUEUE_FULL,
    RESPONSE_UNAVAILABLE,
    UNAUTHORIZED,
    FORBIDDEN,
    BAD_REQUEST,
    SERVER,
    NETWORK,
    LOCAL,
    DESTINATION_CHANGED,
}

/** Safe DTO: it deliberately cannot carry a token, peer identity, payload, path, or raw error. */
internal data class TelegramPeerControlTransportEvent(
    val phase: TelegramPeerTransportPhase,
    val kind: TelegramPeerControlKind,
    val attempt: Int,
    val correlationId: String? = null,
    val messageId: Long? = null,
    val failure: TelegramPeerTransportFailure? = null,
    val httpStatus: Int? = null,
    val permanent: Boolean = false,
    val retryDelayMs: Long? = null,
)

/** Ten hexadecimal characters are enough to correlate local events without exposing protocol ids. */
internal fun telegramPeerCorrelationId(value: String): String {
    require(value.isNotBlank())
    return java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        .take(5)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

internal fun telegramPeerCorrelationId(entry: TelegramOutboxEntry): String? =
    entry.peerTransferId?.let(::telegramPeerCorrelationId)

internal fun clearFailedPeerControlState(
    record: TelegramPeerLinkRecord,
    event: TelegramPeerControlTransportEvent,
): TelegramPeerLinkRecord {
    if (event.phase != TelegramPeerTransportPhase.DEAD || event.correlationId == null) return record
    return when (event.kind) {
        TelegramPeerControlKind.CONNECT_REQUEST -> if (
            record.pendingRequestId?.let(::telegramPeerCorrelationId) == event.correlationId
        ) record.withoutRequest() else record
        TelegramPeerControlKind.PING -> if (
            record.pendingPingNonce?.let(::telegramPeerCorrelationId) == event.correlationId
        ) record.withoutPing() else record
        else -> record
    }
}

internal enum class TelegramPeerUpdateDecision {
    ACCEPTED,
    DROP_NOT_PRIVATE,
    DROP_SENDER_NOT_BOT,
    DROP_MISSING_SENDER,
    DROP_INVALID_USERNAME,
    DROP_CHAT_MISMATCH,
    DROP_PEER_ID_MISMATCH,
    DROP_PEER_USERNAME_MISMATCH,
    DROP_NO_PAIR,
    DROP_NOT_HANDSHAKE,
}

/** Safe DTO emitted only for bot/protocol-shaped candidate updates. */
internal data class TelegramPeerUpdateEvent(
    val updateId: Long,
    val kind: TelegramPeerControlKind,
    val decision: TelegramPeerUpdateDecision,
)

internal data class TelegramPeerPollFailureEvent(
    val failure: TelegramPeerTransportFailure,
    val httpStatus: Int?,
    val permanent: Boolean,
    val retryDelayMs: Long?,
)

internal class TelegramPeerResponseRetryException(
    val result: TelegramEnqueueResult,
) : IllegalStateException("Required peer response is temporarily unavailable.")

internal fun telegramPeerTransportFailure(error: Throwable): TelegramPeerTransportFailure {
    if (error is TelegramPeerInboxCapacityException) {
        return TelegramPeerTransportFailure.INBOX_FULL
    }
    if (error is TelegramPeerResponseRetryException) {
        return if (error.result == TelegramEnqueueResult.QUEUE_FULL) {
            TelegramPeerTransportFailure.RESPONSE_QUEUE_FULL
        } else {
            TelegramPeerTransportFailure.RESPONSE_UNAVAILABLE
        }
    }
    if (error !is TelegramApiException) {
        return if (error is java.io.IOException) {
            TelegramPeerTransportFailure.NETWORK
        } else {
            TelegramPeerTransportFailure.LOCAL
        }
    }
    val normalized = error.message.orEmpty().lowercase()
    return when {
        "bot_to_bot" in normalized ||
            "bots can't send messages to bots" in normalized ||
            "bots cannot send messages to bots" in normalized ->
            TelegramPeerTransportFailure.BOT_TO_BOT_DISABLED
        error.statusCode == 429 -> TelegramPeerTransportFailure.RATE_LIMITED
        error.statusCode == 409 -> TelegramPeerTransportFailure.CONFLICT
        error.statusCode == 401 -> TelegramPeerTransportFailure.UNAUTHORIZED
        error.statusCode == 403 -> TelegramPeerTransportFailure.FORBIDDEN
        error.statusCode == 400 -> TelegramPeerTransportFailure.BAD_REQUEST
        error.statusCode in 500..599 -> TelegramPeerTransportFailure.SERVER
        else -> TelegramPeerTransportFailure.NETWORK
    }
}
