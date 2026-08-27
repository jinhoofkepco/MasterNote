package com.studyink.monitor.telegram

import java.io.File

/** User-facing health of the already-paired bot-to-bot path. */
enum class TelegramPeerLinkHealth {
    UNAVAILABLE,
    CHECKING,
    CONNECTED,
    STALE,
}

data class TelegramPeerLinkState(
    val health: TelegramPeerLinkHealth,
    val role: RemoteReviewRole? = null,
    val pairId: String? = null,
    val lastPeerResponseEpochMs: Long? = null,
    val connectionRequestPending: Boolean = false,
) {
    val peerRecent: Boolean get() = health == TelegramPeerLinkHealth.CONNECTED

    companion object {
        val UNAVAILABLE = TelegramPeerLinkState(TelegramPeerLinkHealth.UNAVAILABLE)
    }
}

/**
 * Small pair-scoped journal for liveness correlation. It contains no bot token or message body.
 * Wall-clock timestamps are deliberately persisted so a process restart cannot turn an old reply
 * into a fresh connection; callers still apply freshness and clock-skew policy on every read.
 */
internal data class TelegramPeerLinkRecord(
    val pairId: String,
    val sessionId: String,
    val lastPeerResponseEpochMs: Long = 0L,
    val pendingRequestId: String? = null,
    val pendingRequestExpiresAtEpochMs: Long = 0L,
    val pendingPingNonce: String? = null,
    val pendingPingSentAtEpochMs: Long = 0L,
    val pendingPingExpiresAtEpochMs: Long = 0L,
    val lastPingScheduledAtEpochMs: Long = 0L,
) {
    init {
        require(PEER_IDENTIFIER.matches(pairId))
        require(PEER_IDENTIFIER.matches(sessionId))
        require(lastPeerResponseEpochMs >= 0L)
        require((pendingRequestId == null) == (pendingRequestExpiresAtEpochMs == 0L))
        require(pendingRequestId == null || PEER_IDENTIFIER.matches(pendingRequestId))
        require(pendingRequestExpiresAtEpochMs >= 0L)
        require((pendingPingNonce == null) ==
            (pendingPingSentAtEpochMs == 0L && pendingPingExpiresAtEpochMs == 0L))
        require(pendingPingNonce == null || PEER_IDENTIFIER.matches(pendingPingNonce))
        require(pendingPingSentAtEpochMs >= 0L && pendingPingExpiresAtEpochMs >= 0L)
        require(pendingPingNonce == null || pendingPingExpiresAtEpochMs >= pendingPingSentAtEpochMs)
        require(lastPingScheduledAtEpochMs >= 0L)
    }

    fun withoutRequest(): TelegramPeerLinkRecord = copy(
        pendingRequestId = null,
        pendingRequestExpiresAtEpochMs = 0L,
    )

    fun withoutPing(): TelegramPeerLinkRecord = copy(
        pendingPingNonce = null,
        pendingPingSentAtEpochMs = 0L,
        pendingPingExpiresAtEpochMs = 0L,
    )
}

internal class TelegramPeerLinkStateStore(private val file: File) {
    @Synchronized
    fun load(): TelegramPeerLinkRecord? {
        if (!file.isFile) return null
        return runCatching {
            val fields = file.readText().trimEnd().split('\t')
            require(fields.size == 10 && fields[0] == VERSION)
            TelegramPeerLinkRecord(
                pairId = fields[1],
                sessionId = fields[2],
                lastPeerResponseEpochMs = fields[3].toLong(),
                pendingRequestId = fields[4].ifBlank { null },
                pendingRequestExpiresAtEpochMs = fields[5].toLong(),
                pendingPingNonce = fields[6].ifBlank { null },
                pendingPingSentAtEpochMs = fields[7].toLong(),
                pendingPingExpiresAtEpochMs = fields[8].toLong(),
                lastPingScheduledAtEpochMs = fields[9].toLong(),
            )
        }.getOrNull()
    }

    @Synchronized
    fun save(record: TelegramPeerLinkRecord) {
        AtomicDiskFile.writeText(
            file,
            listOf(
                VERSION,
                record.pairId,
                record.sessionId,
                record.lastPeerResponseEpochMs,
                record.pendingRequestId.orEmpty(),
                record.pendingRequestExpiresAtEpochMs,
                record.pendingPingNonce.orEmpty(),
                record.pendingPingSentAtEpochMs,
                record.pendingPingExpiresAtEpochMs,
                record.lastPingScheduledAtEpochMs,
            ).joinToString("\t", postfix = "\n"),
        )
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private companion object { const val VERSION = "V1" }
}

internal fun resolveTelegramPeerLinkState(
    peerStatus: RemoteReviewPeerStatus,
    record: TelegramPeerLinkRecord?,
    nowEpochMs: Long,
    freshnessMs: Long,
): TelegramPeerLinkState {
    require(nowEpochMs >= 0L && freshnessMs > 0L)
    val connected = peerStatus as? RemoteReviewPeerStatus.Connected
        ?: return TelegramPeerLinkState.UNAVAILABLE
    val scoped = record?.takeIf { it.pairId == connected.pairId }
    val lastResponse = scoped?.lastPeerResponseEpochMs?.takeIf { it > 0L }
    val recent = lastResponse != null && lastResponse <= saturatedPeerTimeAdd(
        nowEpochMs,
        TelegramPeerProtocol.MAX_CONTROL_CLOCK_SKEW_MS,
    ) && nowEpochMs - lastResponse.coerceAtMost(nowEpochMs) <= freshnessMs
    val requestPending = scoped?.let { hasUsablePendingConnectionRequest(it, nowEpochMs) } == true
    val pingPending = scoped?.pendingPingNonce != null && isUsablePeerControlWindow(
        scoped.pendingPingSentAtEpochMs,
        scoped.pendingPingExpiresAtEpochMs,
        nowEpochMs,
    )
    return TelegramPeerLinkState(
        health = when {
            recent -> TelegramPeerLinkHealth.CONNECTED
            requestPending || pingPending -> TelegramPeerLinkHealth.CHECKING
            else -> TelegramPeerLinkHealth.STALE
        },
        role = connected.role,
        pairId = connected.pairId,
        lastPeerResponseEpochMs = lastResponse,
        connectionRequestPending = requestPending,
    )
}

/** Rejects a persisted request whose deadline jumped implausibly far ahead after a clock rollback. */
internal fun hasUsablePendingConnectionRequest(
    record: TelegramPeerLinkRecord,
    nowEpochMs: Long,
): Boolean {
    if (record.pendingRequestId == null || nowEpochMs < 0L) return false
    val latestPlausibleExpiry = saturatedPeerTimeAdd(
        nowEpochMs,
        TelegramPeerProtocol.MAX_CONTROL_REQUEST_LIFETIME_MS +
            TelegramPeerProtocol.MAX_CONTROL_CLOCK_SKEW_MS,
    )
    return nowEpochMs <= record.pendingRequestExpiresAtEpochMs &&
        record.pendingRequestExpiresAtEpochMs <= latestPlausibleExpiry
}

/** A genuine new response repairs a persisted timestamp made implausible by wall-clock rollback. */
internal fun mergeAuthenticatedPeerObservation(
    previousEpochMs: Long,
    observedAtEpochMs: Long,
): Long {
    require(previousEpochMs >= 0L && observedAtEpochMs >= 0L)
    return if (previousEpochMs <= saturatedPeerTimeAdd(
            observedAtEpochMs,
            TelegramPeerProtocol.MAX_CONTROL_CLOCK_SKEW_MS,
        )
    ) {
        maxOf(previousEpochMs, observedAtEpochMs)
    } else {
        observedAtEpochMs
    }
}

internal fun isUsablePeerControlWindow(
    sentAtEpochMs: Long,
    expiresAtEpochMs: Long,
    nowEpochMs: Long,
): Boolean = sentAtEpochMs > 0L &&
    expiresAtEpochMs >= sentAtEpochMs &&
    expiresAtEpochMs - sentAtEpochMs <= TelegramPeerProtocol.MAX_CONTROL_REQUEST_LIFETIME_MS &&
    sentAtEpochMs <= saturatedPeerTimeAdd(nowEpochMs, TelegramPeerProtocol.MAX_CONTROL_CLOCK_SKEW_MS) &&
    nowEpochMs <= expiresAtEpochMs

internal fun isFreshPeerControlResponse(sentAtEpochMs: Long, nowEpochMs: Long): Boolean =
    sentAtEpochMs > 0L &&
        sentAtEpochMs <= saturatedPeerTimeAdd(nowEpochMs, TelegramPeerProtocol.MAX_CONTROL_CLOCK_SKEW_MS) &&
        sentAtEpochMs >= (nowEpochMs - TelegramPeerProtocol.MAX_CONTROL_RESPONSE_AGE_MS).coerceAtLeast(0L)

/**
 * Telegram's server-authored message date lets ordinary authenticated traffic prove recent peer
 * activity without treating a long-offline server backlog as a fresh reconnect.
 */
internal fun freshTelegramPeerUpdateEpochMs(
    sentAtEpochSeconds: Long?,
    nowEpochMs: Long,
    freshnessMs: Long,
): Long? {
    if (sentAtEpochSeconds == null || sentAtEpochSeconds <= 0L || nowEpochMs < 0L || freshnessMs <= 0L ||
        sentAtEpochSeconds > Long.MAX_VALUE / 1_000L
    ) return null
    val sentAtEpochMs = sentAtEpochSeconds * 1_000L
    return sentAtEpochMs.takeIf {
        sentAtEpochMs <= saturatedPeerTimeAdd(
            nowEpochMs,
            TelegramPeerProtocol.MAX_CONTROL_CLOCK_SKEW_MS,
        ) && nowEpochMs - sentAtEpochMs.coerceAtMost(nowEpochMs) <= freshnessMs
    }
}

/**
 * Automatic probes are intentionally bounded. A new pair gets one bootstrap probe, and a peer
 * which has answered recently keeps receiving the lightweight heartbeat. Once the link is stale,
 * automatic probes stop so Telegram cannot accumulate one server-side update per minute while the
 * other device is switched off; an explicit CONNECT_REQUEST is then the only wake-up path.
 */
internal fun shouldScheduleAutomaticPeerPing(
    record: TelegramPeerLinkRecord,
    nowEpochMs: Long,
    intervalMs: Long,
    freshnessMs: Long,
): Boolean {
    require(nowEpochMs >= 0L && intervalMs > 0L && freshnessMs > 0L)
    if (record.pendingRequestId != null || record.pendingPingNonce != null) return false

    val lastScheduled = record.lastPingScheduledAtEpochMs
    val due = lastScheduled == 0L ||
        lastScheduled > saturatedPeerTimeAdd(
            nowEpochMs,
            TelegramPeerProtocol.MAX_CONTROL_CLOCK_SKEW_MS,
        ) ||
        nowEpochMs - lastScheduled.coerceAtMost(nowEpochMs) >= intervalMs
    if (!due) return false

    // Exactly one automatic bootstrap attempt is allowed before the first authenticated reply.
    if (lastScheduled == 0L) return true
    val lastResponse = record.lastPeerResponseEpochMs
    return lastResponse > 0L &&
        lastResponse <= saturatedPeerTimeAdd(
            nowEpochMs,
            TelegramPeerProtocol.MAX_CONTROL_CLOCK_SKEW_MS,
        ) &&
        nowEpochMs - lastResponse.coerceAtMost(nowEpochMs) <= freshnessMs
}

private fun saturatedPeerTimeAdd(left: Long, right: Long): Long =
    if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
