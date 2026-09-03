package com.studyink.monitor.telegram

import com.studyink.monitor.core.ParentCommandParser
import com.studyink.monitor.core.ParentInboundAction
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random

internal fun interface TelegramInboundHandler {
    /** Returning normally means the update was accepted and its offset may be committed. */
    fun handle(update: TelegramInboundUpdate, action: ParentInboundAction)
}

internal fun interface TelegramPeerInboundHandler {
    /** Handler must durably persist accepted documents before returning. */
    fun handle(update: TelegramInboundUpdate)
}

/** Prevents two in-process getUpdates owners from consuming the same dedicated bot queue. */
internal object TelegramBotPollOwnership {
    private val owners = ConcurrentHashMap.newKeySet<String>()

    fun acquire(botToken: String): String? {
        val fingerprint = botFingerprint(botToken)
        return fingerprint.takeIf(owners::add)
    }

    fun release(fingerprint: String) {
        owners.remove(fingerprint)
    }
}

internal class TelegramInboxPoller(
    private val credentials: TelegramCredentials,
    private val api: TelegramBotApi,
    private val offsetStore: TelegramUpdateOffsetStore,
    private val connectionTracker: TelegramConnectionTracker,
    private val handler: TelegramInboundHandler,
    private val nowEpochMs: () -> Long,
    private val jitter: TelegramJitterSource,
    private val onFatalError: (Throwable) -> Unit,
    private val credentialsProvider: () -> TelegramCredentials = { credentials },
    private val peerHandler: TelegramPeerInboundHandler? = null,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor(namedThreadFactory("telegram-inbox"))
    private val retrySignalLock = ReentrantLock()
    private val retrySignal = retrySignalLock.newCondition()
    private var retryWakeRequested = false
    @Volatile private var ownership: String? = null
    val isRunning: Boolean get() = running.get()

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        val acquired = TelegramBotPollOwnership.acquire(credentials.botToken)
        if (acquired == null) {
            running.set(false)
            return false
        }
        ownership = acquired
        executor.execute(::pollLoop)
        return true
    }

    private fun pollLoop() {
        val fingerprint = botFingerprint(credentials.botToken)
        var offset = offsetStore.load(fingerprint)
        var consecutiveFailures = 0
        var webhookCleared = false
        try {
            while (running.get() && !Thread.currentThread().isInterrupted) {
                try {
                    if (!webhookCleared) {
                        api.deleteWebhook()
                        webhookCleared = true
                    }
                    val updates = api.getUpdates(offset, POLL_TIMEOUT_SECONDS)
                        .sortedBy(TelegramInboundUpdate::updateId)
                    connectionTracker.success(nowEpochMs())
                    consecutiveFailures = 0
                    for (update in updates) {
                        if (!running.get()) break
                        if (update.updateId < offset) continue
                        if (isAllowedParent(update)) {
                            val action = update.text?.let(ParentCommandParser::parse)
                            if (action != null) handler.handle(update, action)
                        } else if (isAllowedPeerOrHandshake(update)) {
                            peerHandler?.handle(update)
                        }
                        offset = update.updateId + 1L
                        offsetStore.commit(fingerprint, offset)
                    }
                } catch (error: Throwable) {
                    if (!running.get() || error is InterruptedException) break
                    connectionTracker.failure(error, nowEpochMs())
                    if (TelegramRetryPolicy.isPermanent(error)) {
                        onFatalError(error)
                        break
                    }
                    val delayMs = TelegramRetryPolicy.retryDelayMs(
                        error,
                        consecutiveFailures++,
                        jitter.nextFraction().coerceIn(0.0, 1.0),
                    )
                    if (!awaitRetryDelay(delayMs)) break
                }
            }
        } catch (error: Throwable) {
            if (running.get()) onFatalError(error)
        } finally {
            running.set(false)
            ownership?.let(TelegramBotPollOwnership::release)
            ownership = null
        }
    }

    private fun isAllowedParent(update: TelegramInboundUpdate): Boolean =
        update.chatType == "private" &&
            update.chatId == credentials.allowedPrivateChatId &&
            !update.senderIsBot &&
            !update.text.isNullOrBlank()

    private fun isAllowedPeerOrHandshake(update: TelegramInboundUpdate): Boolean {
        if (peerHandler == null || update.chatType != "private" || !update.senderIsBot) return false
        val senderId = update.senderId ?: return false
        val username = runCatching { normalizeTelegramUsername(update.senderUsername.orEmpty()) }.getOrNull()
            ?: return false
        // Bot-to-bot private chats use the sender bot id as chat id. Requiring both fields avoids
        // accepting a forwarded/copy-shaped update from a group or a different bot identity.
        if (update.chatId != senderId) return false
        val active = credentialsProvider()
        val pinned = active.peerBinding
        if (pinned != null) return senderId == pinned.botId && username == pinned.username
        val isHandshake = update.text?.startsWith("${TelegramPeerProtocol.VERSION} HELLO ") == true ||
            update.text?.startsWith("${TelegramPeerProtocol.VERSION} PAIR_ACK ") == true
        return active.peerPairId != null && isHandshake
    }

    /**
     * Coalesced wake-up for a poller in retry backoff. Keeping a remembered flag avoids the classic
     * lost-signal race when a manual CONNECT_REQUEST is queued just before this thread starts to
     * wait. Interruption remains terminal and the interrupted status is restored for callers.
     */
    fun wake() = retrySignalLock.withLock {
        retryWakeRequested = true
        retrySignal.signalAll()
    }

    private fun awaitRetryDelay(delayMs: Long): Boolean = try {
        retrySignalLock.withLock {
            var remainingNanos = delayMs.coerceAtLeast(0L)
                .coerceAtMost(Long.MAX_VALUE / 1_000_000L) * 1_000_000L
            while (running.get() && !retryWakeRequested && remainingNanos > 0L) {
                remainingNanos = retrySignal.awaitNanos(remainingNanos)
            }
            retryWakeRequested = false
            running.get()
        }
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    override fun close() {
        running.set(false)
        wake()
        api.close() // disconnects an outstanding long poll before the executor is interrupted
        val neverStarted = executor.shutdownNow().isNotEmpty()
        if (neverStarted) {
            ownership?.let(TelegramBotPollOwnership::release)
            ownership = null
        } else {
            runCatching { executor.awaitTermination(2L, TimeUnit.SECONDS) }
        }
    }

    private companion object { const val POLL_TIMEOUT_SECONDS = 50 }
}

internal sealed interface OutboxProcessResult {
    data object Idle : OutboxProcessResult
    data class Waiting(val untilEpochMs: Long) : OutboxProcessResult
    data class Sent(val key: String) : OutboxProcessResult
    data class Retried(val key: String, val atEpochMs: Long) : OutboxProcessResult
    data class Dead(val key: String) : OutboxProcessResult
}

internal class TelegramOutboxProcessor(
    private val credentials: TelegramCredentials,
    private val api: TelegramBotApi,
    private val outbox: TelegramOutbox,
    private val retryGate: TelegramRetryGate,
    private val connectionTracker: TelegramConnectionTracker,
    private val ownedMediaRoot: File,
    private val jitter: TelegramJitterSource,
    private val credentialsProvider: () -> TelegramCredentials = { credentials },
    private val peerReceipts: TelegramPeerReceiptStore? = null,
    private val lane: TelegramOutboxLane = TelegramOutboxLane.ALL,
    private val rateLimitPropagationGate: TelegramRetryGate? = null,
    private val onPermanentFailure: (String) -> Unit = {},
) : AutoCloseable {
    private val claimStateLock = Any()
    private var closed = false
    private var activePreTransportClaim: String? = null

    fun processOne(nowEpochMs: Long): OutboxProcessResult {
        val gate = retryGate.nextAllowedEpochMs()
        if (gate > nowEpochMs) return OutboxProcessResult.Waiting(gate)
        retryGate.clearIfElapsed(nowEpochMs)
        val entry = outbox.claimDue(nowEpochMs, lane) ?: return outbox.nextWakeEpochMs(lane)
            ?.let(OutboxProcessResult::Waiting) ?: OutboxProcessResult.Idle
        if (!registerPreTransportClaim(entry.idempotencyKey)) {
            outbox.releaseClaim(entry.idempotencyKey)
            throw IllegalStateException("Telegram outbox processor is closed.")
        }

        var transportAttemptStarted = false
        try {
            val activeCredentials = credentialsProvider()
            when (entry.route) {
            TelegramOutboxRoute.PARENT -> {
                val rejection = when {
                    activeCredentials.remoteReviewRole == RemoteReviewRole.TEACHER ->
                        "원격 선생 기기에서는 부모 모니터 전송을 사용하지 않음"
                    entry.destinationChatId != activeCredentials.allowedPrivateChatId ->
                        "연결된 부모 채팅이 변경됨"
                    else -> null
                }
                if (rejection != null) {
                    val dead = outbox.deadLetter(entry.idempotencyKey, rejection, nowEpochMs)
                    if (dead?.entry?.deleteAfterSend == true) deleteOwnedFile(dead.entry.file)
                    return OutboxProcessResult.Dead(entry.idempotencyKey)
                }
            }
            TelegramOutboxRoute.PEER -> {
                val peer = activeCredentials.peerBinding
                if (peer == null || peer.botId != entry.destinationChatId ||
                    peer.username != entry.destinationUsername
                ) {
                    val dead = outbox.deadLetter(
                        entry.idempotencyKey,
                        "연결된 원격 첨삭 기기가 변경됨",
                        nowEpochMs,
                    )
                    if (dead?.entry?.deleteAfterSend == true) deleteOwnedFile(dead.entry.file)
                    return OutboxProcessResult.Dead(entry.idempotencyKey)
                }
                val transferId = requireNotNull(entry.peerTransferId)
                var receipt = peerReceipts?.receipt(transferId)
                if (entry.kind == TelegramOutboxKind.DOCUMENT &&
                    receipt?.serverAcceptedAtEpochMs == null &&
                    entry.lastError == PEER_ACK_WAIT_REASON
                ) {
                    // Migration from the old uploader: this reason was persisted only after a
                    // successful Telegram API response. Mark it accepted before it can be uploaded
                    // once more by the new binary. Rebuild a missing/corrupt receipt journal from
                    // the stronger durable outbox success marker when necessary.
                    val intent = receipt ?: peerReceipts?.recordSent(
                        transferId = transferId,
                        outboxKey = entry.idempotencyKey,
                        telegramMessageId = null,
                        sentAtEpochMs = entry.createdAtEpochMs,
                    )
                    receipt = peerReceipts?.recordServerAccepted(
                        transferId = transferId,
                        telegramMessageId = intent?.telegramMessageId,
                        acceptedAtEpochMs = intent?.sentAtEpochMs ?: entry.createdAtEpochMs,
                    ) ?: intent
                }
                if (entry.kind == TelegramOutboxKind.DOCUMENT &&
                    receipt?.acknowledgedAtEpochMs != null
                ) {
                    val acknowledged = outbox.acknowledge(entry.idempotencyKey, nowEpochMs)
                    if (acknowledged?.deleteAfterSend == true) deleteOwnedFile(acknowledged.file)
                    return OutboxProcessResult.Sent(entry.idempotencyKey)
                }
                if (entry.kind == TelegramOutboxKind.DOCUMENT &&
                    receipt?.serverAcceptedAtEpochMs != null
                ) {
                    val acceptedAt = requireNotNull(receipt.serverAcceptedAtEpochMs)
                    val deadline = safeAdd(acceptedAt, PEER_ACK_RETENTION_MS)
                    if (!peerAcknowledgementExpired(acceptedAt, nowEpochMs)) {
                        val deferred = outbox.deferUntil(
                            entry.idempotencyKey,
                            deadline,
                            PEER_ACK_WAIT_REASON,
                        )
                        if (deferred?.deleteAfterSend == true) deleteOwnedFile(deferred.file)
                        return OutboxProcessResult.Retried(entry.idempotencyKey, deadline)
                    }
                    val dead = outbox.deadLetter(
                        entry.idempotencyKey,
                        "원격 기기 수신 확인이 24시간 동안 없음",
                        nowEpochMs,
                    )
                    if (dead?.entry?.deleteAfterSend == true) deleteOwnedFile(dead.entry.file)
                    return OutboxProcessResult.Dead(entry.idempotencyKey)
                }
                if (entry.kind == TelegramOutboxKind.DOCUMENT &&
                    peerAcknowledgementExpired(entry.createdAtEpochMs, nowEpochMs)
                ) {
                    val dead = outbox.deadLetter(
                        entry.idempotencyKey,
                        "Telegram 서버 전송이 24시간 동안 성공하지 않음",
                        nowEpochMs,
                    )
                    if (dead?.entry?.deleteAfterSend == true) deleteOwnedFile(dead.entry.file)
                    return OutboxProcessResult.Dead(entry.idempotencyKey)
                }
            }
        }

            if (entry.route == TelegramOutboxRoute.PEER && entry.kind == TelegramOutboxKind.DOCUMENT) {
            // Establish the durable transfer record before the network call. The receiving bot can
            // acknowledge very quickly, on a different worker, so recording only after upload
            // completion leaves a small ACK-before-SENT loss window.
            peerReceipts?.recordSent(
                transferId = requireNotNull(entry.peerTransferId),
                outboxKey = entry.idempotencyKey,
                telegramMessageId = null,
                sentAtEpochMs = nowEpochMs,
            )
            }

            // From this point on an exception may be ambiguous: Telegram could have accepted the
            // request before the client observed a response. Never memory-release a peer document
            // claim through the preflight recovery path after transport has started.
            markTransportAttemptStarted(entry.idempotencyKey)
            transportAttemptStarted = true
            val sendResult: TelegramSendResult = try {
            when (entry.route) {
                TelegramOutboxRoute.PARENT -> when (entry.kind) {
                    TelegramOutboxKind.TEXT -> api.sendMessage(entry.destinationChatId, entry.text)
                    TelegramOutboxKind.DOCUMENT -> api.sendDocument(
                        entry.destinationChatId,
                        requireFile(entry),
                        entry.text,
                        requireNotNull(entry.mimeType),
                        requireNotNull(entry.displayName),
                    )
                    TelegramOutboxKind.VOICE -> api.sendVoice(
                        entry.destinationChatId,
                        requireFile(entry),
                        entry.text,
                        requireNotNull(entry.mimeType),
                        requireNotNull(entry.displayName),
                    )
                }
                TelegramOutboxRoute.PEER -> when (entry.kind) {
                    TelegramOutboxKind.TEXT -> api.sendPeerMessage(requireNotNull(entry.destinationUsername), entry.text)
                    TelegramOutboxKind.DOCUMENT -> api.sendPeerDocument(
                        requireNotNull(entry.destinationUsername),
                        requireFile(entry),
                        entry.text,
                        requireNotNull(entry.mimeType),
                        requireNotNull(entry.displayName),
                    )
                    TelegramOutboxKind.VOICE -> error("Peer voice transport is not supported.")
                }
            }
        } catch (error: Throwable) {
            runCatching { connectionTracker.failure(error, nowEpochMs) }
            if (entry.route == TelegramOutboxRoute.PEER &&
                entry.kind == TelegramOutboxKind.DOCUMENT &&
                peerReceipts?.receipt(requireNotNull(entry.peerTransferId))
                    ?.acknowledgedAtEpochMs != null
            ) {
                val acknowledged = outbox.acknowledge(entry.idempotencyKey, nowEpochMs)
                if (acknowledged?.deleteAfterSend == true) deleteOwnedFile(acknowledged.file)
                return OutboxProcessResult.Sent(entry.idempotencyKey)
            }
            val reason = TelegramRetryPolicy.shortReason(error)
            return if (TelegramRetryPolicy.isPermanent(error)) {
                val dead = outbox.deadLetter(entry.idempotencyKey, reason, nowEpochMs)
                if (dead?.entry?.deleteAfterSend == true) deleteOwnedFile(dead.entry.file)
                if (dead != null) runCatching { onPermanentFailure(reason) }
                OutboxProcessResult.Dead(entry.idempotencyKey)
            } else {
                val delay = TelegramRetryPolicy.retryDelayMs(
                    error,
                    entry.attempts,
                    jitter.nextFraction().coerceIn(0.0, 1.0),
                )
                val next = safeAdd(nowEpochMs, delay)
                outbox.retry(entry.idempotencyKey, nowEpochMs, delay, reason)
                if (error is TelegramApiException && error.statusCode == 429) {
                    retryGate.deferUntil(next)
                    rateLimitPropagationGate?.deferUntil(next)
                }
                OutboxProcessResult.Retried(entry.idempotencyKey, next)
            }
        }

            if (entry.route == TelegramOutboxRoute.PEER && entry.kind == TelegramOutboxKind.DOCUMENT) {
            val transferId = requireNotNull(entry.peerTransferId)
            val receiptStore = requireNotNull(peerReceipts) { "Peer receipt store is unavailable." }
            val accepted = runCatching {
                receiptStore.recordServerAccepted(
                    transferId = transferId,
                    telegramMessageId = sendResult.messageId,
                    acceptedAtEpochMs = nowEpochMs,
                ) ?: error("Peer transfer intent record is missing.")
            }.getOrElse { error ->
                // Telegram has already accepted this upload. A local bookkeeping failure must stop
                // the transfer instead of scheduling another server upload with the same payload.
                val dead = outbox.deadLetter(
                    entry.idempotencyKey,
                    "Telegram 전송 완료 후 로컬 확인 저장 실패: ${TelegramRetryPolicy.shortReason(error)}",
                    nowEpochMs,
                )
                if (dead?.entry?.deleteAfterSend == true) deleteOwnedFile(dead.entry.file)
                return OutboxProcessResult.Dead(entry.idempotencyKey)
            }
            val deadline = safeAdd(accepted.serverAcceptedAtEpochMs ?: nowEpochMs, PEER_ACK_RETENTION_MS)
            val deferred = outbox.deferUntil(
                entry.idempotencyKey,
                deadline,
                PEER_ACK_WAIT_REASON,
            )
            if (deferred?.deleteAfterSend == true) deleteOwnedFile(deferred.file)
            runCatching { connectionTracker.success(nowEpochMs) }
            if (receiptStore.receipt(transferId)?.acknowledgedAtEpochMs != null) {
                val acknowledged = outbox.acknowledge(entry.idempotencyKey, nowEpochMs)
                if (acknowledged?.deleteAfterSend == true) deleteOwnedFile(acknowledged.file)
                return OutboxProcessResult.Sent(entry.idempotencyKey)
            }
            return OutboxProcessResult.Retried(entry.idempotencyKey, deadline)
            }

            runCatching { connectionTracker.success(nowEpochMs) }
            val acknowledged = outbox.acknowledge(entry.idempotencyKey, nowEpochMs)
            if (acknowledged?.deleteAfterSend == true) deleteOwnedFile(acknowledged.file)
            return OutboxProcessResult.Sent(entry.idempotencyKey)
        } catch (error: Throwable) {
            if (!transportAttemptStarted) outbox.releaseClaim(entry.idempotencyKey)
            throw error
        } finally {
            clearPreTransportClaim(entry.idempotencyKey)
        }
    }

    private fun registerPreTransportClaim(idempotencyKey: String): Boolean =
        synchronized(claimStateLock) {
            if (closed) return@synchronized false
            check(activePreTransportClaim == null)
            activePreTransportClaim = idempotencyKey
            true
        }

    private fun markTransportAttemptStarted(idempotencyKey: String) = synchronized(claimStateLock) {
        check(!closed) { "Telegram outbox processor closed before transport." }
        check(activePreTransportClaim == idempotencyKey)
        activePreTransportClaim = null
    }

    private fun clearPreTransportClaim(idempotencyKey: String) = synchronized(claimStateLock) {
        if (activePreTransportClaim == idempotencyKey) activePreTransportClaim = null
    }

    private fun requireFile(entry: TelegramOutboxEntry): File = entry.file
        ?.takeIf { it.isFile && it.canRead() }
        ?: throw FileNotFoundException("Queued attachment is missing.")

    private fun deleteOwnedFile(file: File?) {
        if (file == null) return
        val root = ownedMediaRoot.canonicalFile
        val candidate = runCatching { file.canonicalFile }.getOrNull() ?: return
        if (candidate.toPath().startsWith(root.toPath())) runCatching { candidate.delete() }
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    override fun close() {
        val unsentClaim = synchronized(claimStateLock) {
            closed = true
            activePreTransportClaim.also { activePreTransportClaim = null }
        }
        unsentClaim?.let(outbox::releaseClaim)
        api.close()
    }
}

internal fun peerAcknowledgementExpired(createdAtEpochMs: Long, nowEpochMs: Long): Boolean {
    require(createdAtEpochMs >= 0L && nowEpochMs >= 0L)
    return nowEpochMs >= createdAtEpochMs &&
        nowEpochMs - createdAtEpochMs >= PEER_ACK_RETENTION_MS
}

internal const val PEER_ACK_RETENTION_MS = 24L * 60L * 60L * 1_000L
internal const val PEER_ACK_WAIT_REASON = "원격 기기 수신 확인 대기"

internal class TelegramOutboxWorker(
    private val outbox: TelegramOutbox,
    private val retryGate: TelegramRetryGate,
    private val processor: TelegramOutboxProcessor,
    private val nowEpochMs: () -> Long,
    private val onFatalError: (Throwable) -> Unit = {},
    private val lane: TelegramOutboxLane = TelegramOutboxLane.ALL,
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val signalLock = ReentrantLock()
    private val signal = signalLock.newCondition()
    private var wakeRequested = false
    private val executor = Executors.newSingleThreadExecutor(
        namedThreadFactory("telegram-outbox-${lane.name.lowercase()}"),
    )
    val isRunning: Boolean get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        executor.execute(::loop)
    }

    fun wake() = signalLock.withLock {
        wakeRequested = true
        signal.signalAll()
    }

    private fun loop() {
        try {
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val result = processor.processOne(nowEpochMs())
                val waitMs = when (result) {
                    OutboxProcessResult.Idle -> IDLE_WAKE_MS
                    is OutboxProcessResult.Waiting -> (result.untilEpochMs - nowEpochMs())
                        .coerceIn(MIN_WAKE_MS, IDLE_WAKE_MS)
                    is OutboxProcessResult.Sent, is OutboxProcessResult.Dead -> 0L
                    is OutboxProcessResult.Retried -> {
                        val queueWake = outbox.nextWakeEpochMs(lane) ?: result.atEpochMs
                        val gateWake = retryGate.nextAllowedEpochMs()
                        (maxOf(queueWake, gateWake) - nowEpochMs()).coerceIn(MIN_WAKE_MS, IDLE_WAKE_MS)
                    }
                }
                if (waitMs <= 0L) continue
                try {
                    signalLock.withLock {
                        if (running.get() && !wakeRequested) {
                            signal.awaitNanos(waitMs * 1_000_000L)
                        }
                        wakeRequested = false
                    }
                } catch (_: InterruptedException) {
                    break
                }
            }
        } catch (error: Throwable) {
            if (running.get()) onFatalError(error)
        } finally {
            running.set(false)
        }
    }

    override fun close() {
        running.set(false)
        wake()
        executor.shutdownNow()
        processor.close()
        // Pair/role mutation starts only after an in-flight parent or peer upload has actually
        // observed interruption/API close, avoiding a send racing past durable cancellation.
        runCatching { executor.awaitTermination(2L, TimeUnit.SECONDS) }
    }

    private companion object {
        const val MIN_WAKE_MS = 250L
        const val IDLE_WAKE_MS = 30_000L
    }
}

internal val defaultTelegramJitter = TelegramJitterSource { Random.nextDouble() }

internal fun namedThreadFactory(name: String) = ThreadFactory { runnable ->
    Thread(runnable, "MasterNote-$name").apply {
        priority = Thread.NORM_PRIORITY - 1
        isDaemon = true
    }
}
