package com.studyink.monitor.telegram

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.studyink.monitor.core.ParentInboundAction
import com.studyink.monitor.core.ParentMessage
import com.studyink.monitor.core.ParentMessageBus
import com.studyink.monitor.core.RemoteMonitorCommand
import com.studyink.monitor.core.RemoteMonitorCommandBus
import com.studyink.monitor.core.StudentStudyPresenceBus
import java.io.File

/**
 * Process singleton used by the app coordinator. It owns exactly one long poller and one serialized
 * upload worker, while Reader/Library integrations communicate only through monitor:core buses.
 */
class RemoteMonitorGateway private constructor(context: Context) : AutoCloseable {
    private val paths = TelegramStoragePaths.from(context)
    private val credentials = TelegramCredentialStore(paths)
    private val offsets = TelegramUpdateOffsetStore(paths.offsetFile)
    private val outbox = TelegramOutbox(paths.outboxJournal)
    private val retryGate = TelegramRetryGate(paths.retryGateFile)
    private val preferenceStore = RemoteMonitorPreferencesStore(paths)
    private val parentMessageInbox = TelegramParentMessageInbox(paths.parentMessageInboxFile)
    private val screenRequestInbox = TelegramScreenRequestInbox(paths.screenRequestInboxFile)
    private val peerDocumentInbox = TelegramPeerDocumentInbox(paths.peerInboxJournal, paths.peerInboxDirectory)
    private val peerReceipts = TelegramPeerReceiptStore(paths.peerReceiptJournal)
    private val peerHandshake = TelegramPeerHandshakeStateStore(paths.peerHandshakeFile)
    private val setupClient = TelegramSetupClient(credentials, offsets)
    private val lifecycleLock = Any()
    private val statusLock = Any()
    private val statusListeners = linkedSetOf<(RemoteMonitorStatus) -> Unit>()
    private var poller: TelegramInboxPoller? = null
    private var uploader: TelegramOutboxWorker? = null
    private var currentStatus: RemoteMonitorStatus = if (credentials.load() == null) {
        RemoteMonitorStatus.NotConfigured
    } else {
        RemoteMonitorStatus.Stopped
    }

    init {
        val recoveryFiles = recoverUnqueuedVoiceMedia(
            paths = paths,
            outbox = outbox,
            credentials = credentials.load(),
            nowEpochMs = System.currentTimeMillis(),
        )
        cleanupUnqueuedOwnedMedia(paths, outbox, recoveryFiles)
    }

    val mediaDirectory: File get() = paths.mediaDirectory
    val voiceDirectory: File get() = paths.voiceDirectory
    val peerOutboxDirectory: File get() = paths.peerOutboxDirectory

    /** Destination identity only; the bot token never leaves the credential store. */
    fun configuredChatId(): Long? = credentials.load()?.allowedPrivateChatId

    /** Fetches getMe once for credentials created before local bot identity was persisted. */
    fun localBotIdentity(): TelegramBotIdentity = synchronized(lifecycleLock) {
        ensureLocalBotIdentityLocked().second
    }

    fun peerBinding(): TelegramPeerBinding? = credentials.load()?.peerBinding

    fun remoteReviewPeerStatus(): RemoteReviewPeerStatus = resolveRemoteReviewPeerStatus(
        credentials = credentials.load(),
        pending = peerHandshake.load(),
        nowEpochMs = System.currentTimeMillis(),
    )

    /**
     * Creates the student QR payload. It contains the local bot identity and a new 256-bit session
     * key, never the Bot API token. Any previous peer queue/inbox is revoked first.
     */
    fun createStudentPairingPayload(
        lifetimeMs: Long = TelegramPeerProtocol.DEFAULT_PAIRING_LIFETIME_MS,
    ): RemoteReviewPairingPayload = synchronized(lifecycleLock) {
        require(lifetimeMs in 60_000L..TelegramPeerProtocol.DEFAULT_PAIRING_LIFETIME_MS)
        val (active, identity) = ensureLocalBotIdentityLocked()
        mutatePeerWorkersLocked {
            val now = System.currentTimeMillis()
            val expiresAt = now + lifetimeMs
            val pairId = TelegramPeerProtocol.newPairId()
            val keyBase64 = TelegramPeerProtocol.encodeKey(TelegramPeerProtocol.newSharedKey())
            revokePeerStateLocked(now)
            credentials.save(
                active.withRemoteReviewPairing(
                    role = RemoteReviewRole.STUDENT,
                    pairId = pairId,
                    sharedKeyBase64 = keyBase64,
                    expiresAtEpochMs = expiresAt,
                    binding = null,
                ),
            )
            peerHandshake.save(
                TelegramPeerHandshakeState(
                    role = RemoteReviewRole.STUDENT,
                    pairId = pairId,
                    expectedPeerBotId = null,
                    expectedPeerUsername = null,
                    nonce = null,
                    expiresAtEpochMs = expiresAt,
                ),
            )
            TelegramPeerProtocol.createStudentPayload(identity, pairId, keyBase64, expiresAt)
        }
    }

    /** Teacher-side QR accept. This is a blocking setup operation and sends HELLO immediately. */
    fun acceptStudentPairingPayload(encoded: String) {
        val handshake = synchronized(lifecycleLock) {
            val now = System.currentTimeMillis()
            val decoded = TelegramPeerProtocol.decodeStudentPayload(encoded, now)
            val (active, identity) = ensureLocalBotIdentityLocked()
            requireDistinctRemoteReviewBots(identity, decoded.studentBotId)
            mutatePeerWorkersLocked {
                val nonce = TelegramPeerProtocol.newNonce()
                revokePeerStateLocked(now)
                val configured = active.withRemoteReviewPairing(
                    role = RemoteReviewRole.TEACHER,
                    pairId = decoded.pairId,
                    sharedKeyBase64 = decoded.sharedKeyBase64,
                    expiresAtEpochMs = decoded.expiresAtEpochMs,
                    binding = null,
                )
                credentials.save(configured)
                val state = TelegramPeerHandshakeState(
                    role = RemoteReviewRole.TEACHER,
                    pairId = decoded.pairId,
                    expectedPeerBotId = decoded.studentBotId,
                    expectedPeerUsername = decoded.studentBotUsername,
                    nonce = nonce,
                    expiresAtEpochMs = decoded.expiresAtEpochMs,
                )
                peerHandshake.save(state)
                Triple(configured, identity, state)
            }
        }
        sendTeacherHello(handshake.first, handshake.second, handshake.third)
    }

    /** Retries a failed HELLO without changing the QR key or expected numeric student bot id. */
    fun retryRemoteReviewHandshake() {
        val handshake = synchronized(lifecycleLock) {
            val (active, identity) = ensureLocalBotIdentityLocked()
            val state = requireNotNull(peerHandshake.load()) { "No pending remote-review handshake." }
            require(state.role == RemoteReviewRole.TEACHER && active.peerBinding == null)
            require(System.currentTimeMillis() <= state.expiresAtEpochMs) { "Remote-review QR has expired." }
            Triple(active, identity, state)
        }
        sendTeacherHello(handshake.first, handshake.second, handshake.third)
    }

    fun clearRemoteReviewPeer() = synchronized(lifecycleLock) {
        val active = credentials.load() ?: return@synchronized
        mutatePeerWorkersLocked {
            revokePeerStateLocked(System.currentTimeMillis())
            credentials.save(active.withoutRemoteReview())
        }
    }

    fun subscribePeerDocuments(
        emitPending: Boolean = true,
        listener: (PendingTelegramPeerDocument) -> Unit,
    ): RemoteMonitorStatusSubscription = peerDocumentInbox.subscribe(emitPending, listener)

    fun pendingPeerDocuments(): List<PendingTelegramPeerDocument> = peerDocumentInbox.pending()

    fun peerDeliveryReceipt(transferId: String): TelegramDeliveryReceipt? = peerReceipts.receipt(transferId)

    /** Encrypts and durably queues an opaque page/feedback envelope for the exact pinned peer bot. */
    fun enqueuePeerDocument(
        transferId: String,
        payloadType: String,
        plaintext: File,
    ): TelegramEnqueueResult = synchronized(lifecycleLock) {
        val active = credentials.load() ?: return TelegramEnqueueResult.NOT_CONFIGURED
        val peer = active.peerBinding ?: return TelegramEnqueueResult.NOT_CONFIGURED
        val pairId = active.peerPairId ?: return TelegramEnqueueResult.NOT_CONFIGURED
        val key = active.peerSharedKey() ?: return TelegramEnqueueResult.NOT_CONFIGURED
        val caption = TelegramPeerProtocol.documentCaption(pairId, transferId, payloadType)
        require(plaintext.isFile && plaintext.canRead())
        require(plaintext.length() <= TelegramPeerPayloadCipher.MAX_PLAINTEXT_BYTES)
        // Never reuse the path for a duplicate transfer: a rejected enqueue must not delete the
        // file already referenced by the original durable entry.
        val staged = paths.peerOutboxDirectory.resolve(
            "peer-${safePeerFileToken(transferId)}-${java.util.UUID.randomUUID()}.mne",
        )
        TelegramPeerPayloadCipher.encrypt(plaintext, staged, key, caption)
        val now = System.currentTimeMillis()
        val entry = TelegramOutboxEntry(
            idempotencyKey = "telegram-peer-document:$pairId:$transferId",
            destinationChatId = peer.botId,
            kind = TelegramOutboxKind.DOCUMENT,
            filePath = staged.absolutePath,
            text = caption,
            mimeType = TelegramPeerProtocol.CIPHERTEXT_MIME,
            displayName = "master-note-$transferId.mne",
            attempts = 0,
            nextAttemptEpochMs = now,
            createdAtEpochMs = now,
            deleteAfterSend = true,
            route = TelegramOutboxRoute.PEER,
            destinationUsername = peer.username,
            peerTransferId = transferId,
        )
        val pendingPeerDocuments = outbox.pendingSnapshot().filter { pending ->
            pending.route == TelegramOutboxRoute.PEER && pending.kind == TelegramOutboxKind.DOCUMENT
        }
        val alreadyPending = pendingPeerDocuments.any { it.idempotencyKey == entry.idempotencyKey }
        val pendingBytes = pendingPeerDocuments.sumOf { pending ->
            pending.file?.takeIf(File::isFile)?.length()
                ?: TelegramPeerPayloadCipher.MAX_CIPHERTEXT_BYTES
        }
        if (!alreadyPending && !withinPeerDocumentDiskQuota(
                pendingDocumentCount = pendingPeerDocuments.size,
                pendingCiphertextBytes = pendingBytes,
                nextCiphertextBytes = staged.length(),
            )
        ) {
            staged.delete()
            return TelegramEnqueueResult.QUEUE_FULL
        }
        val result = outbox.enqueue(entry)
        if (result == TelegramEnqueueResult.ENQUEUED) uploader?.wake() else staged.delete()
        result
    }

    /** Queues RECEIVED first, then removes the durable plaintext inbox entry. */
    fun acknowledgePeerDocument(updateId: Long): Boolean = synchronized(lifecycleLock) {
        val pending = peerDocumentInbox.pending().firstOrNull { it.updateId == updateId } ?: return false
        val ackResult = enqueuePeerDeliveryAckLocked(pending.transferId, pending.updateId)
        if (ackResult !in setOf(
                TelegramEnqueueResult.ENQUEUED,
                TelegramEnqueueResult.ALREADY_PENDING,
                TelegramEnqueueResult.ALREADY_DELIVERED,
            )
        ) return false
        peerDocumentInbox.acknowledge(updateId, System.currentTimeMillis()) != null
    }

    fun status(): RemoteMonitorStatus = synchronized(statusLock) { currentStatus }

    fun subscribeStatus(
        emitCurrent: Boolean = true,
        listener: (RemoteMonitorStatus) -> Unit,
    ): RemoteMonitorStatusSubscription {
        val initial = synchronized(statusLock) {
            statusListeners += listener
            if (emitCurrent) currentStatus else null
        }
        initial?.let(listener)
        return RemoteMonitorStatusSubscription { synchronized(statusLock) { statusListeners -= listener } }
    }

    fun preferences(): RemoteMonitorPreferences = preferenceStore.get()

    fun subscribePreferences(
        emitCurrent: Boolean = true,
        listener: (RemoteMonitorPreferences) -> Unit,
    ): RemoteMonitorStatusSubscription = preferenceStore.subscribe(emitCurrent, listener)

    /**
     * Delivers the latest parent text, including one which arrived while Reader was stopped.
     * Call [acknowledgeParentMessage] after the message has been handed to the visible overlay.
     */
    fun subscribeParentMessages(
        emitPending: Boolean = true,
        listener: (ParentMessage) -> Unit,
    ): RemoteMonitorStatusSubscription = parentMessageInbox.subscribe(emitPending) { pending ->
        listener(
            ParentMessage(
                updateId = pending.updateId,
                text = pending.text,
                receivedAtElapsedMs = SystemClock.elapsedRealtime(),
            ),
        )
    }

    fun pendingParentMessage(): ParentMessage? = parentMessageInbox.pending()?.let { pending ->
        ParentMessage(
            updateId = pending.updateId,
            text = pending.text,
            receivedAtElapsedMs = SystemClock.elapsedRealtime(),
        )
    }

    fun acknowledgeParentMessage(updateId: Long): Boolean = parentMessageInbox.acknowledge(updateId)

    /** Replays every durable `/화면` request in FIFO order. Ack only after durable outbox enqueue. */
    fun subscribePendingScreenRequests(
        emitPending: Boolean = true,
        listener: (PendingScreenRequest) -> Unit,
    ): RemoteMonitorStatusSubscription = screenRequestInbox.subscribe(emitPending, listener)

    fun pendingScreenRequests(): List<PendingScreenRequest> = screenRequestInbox.pending()

    fun acknowledgeScreenRequest(updateId: Long): Boolean = screenRequestInbox.acknowledge(updateId)

    fun updatePreferences(
        transform: (RemoteMonitorPreferences) -> RemoteMonitorPreferences,
    ): RemoteMonitorPreferences = synchronized(lifecycleLock) {
        val before = preferenceStore.get()
        val after = preferenceStore.update(transform)
        if (before.monitoringEnabled != after.monitoringEnabled) {
            // The intrinsic lifecycle lock is reentrant. Keeping the durable preference write and
            // worker transition in one critical section prevents startIfEnabled/enqueue from
            // observing a half-applied monitoring transition.
            if (after.monitoringEnabled) start() else stopLocked(updateStoppedStatus = true)
        }
        after
    }

    fun startIfEnabled(): Boolean = synchronized(lifecycleLock) {
        if (preferenceStore.get().monitoringEnabled) start() else false
    }

    fun start(): Boolean = synchronized(lifecycleLock) {
        if (poller?.isRunning == true && uploader?.isRunning == true) return true
        val activeCredentials = credentials.load() ?: run {
            updateStatus(RemoteMonitorStatus.NotConfigured)
            return false
        }
        stopLocked(updateStoppedStatus = false)
        updateStatus(RemoteMonitorStatus.Starting(activeCredentials.chatLabel))
        val connectionTracker = TelegramConnectionTracker(
            TelegramConnectionStateStore(paths.connectionStateFile),
        ) { state ->
            when (state) {
                TelegramConnectionState.Unknown -> Unit
                TelegramConnectionState.Connected ->
                    updateStatus(RemoteMonitorStatus.Connected(activeCredentials.chatLabel))
                is TelegramConnectionState.Outage ->
                    updateStatus(RemoteMonitorStatus.Offline(activeCredentials.chatLabel, state.startedAtEpochMs))
            }
        }
        val pollApi = HttpTelegramBotApi(activeCredentials.botToken)
        val uploadApi = HttpTelegramBotApi(activeCredentials.botToken)
        val nextPoller = TelegramInboxPoller(
            credentials = activeCredentials,
            api = pollApi,
            offsetStore = offsets,
            connectionTracker = connectionTracker,
            handler = TelegramInboundHandler { update, action -> handleInbound(activeCredentials, update, action) },
            nowEpochMs = System::currentTimeMillis,
            jitter = defaultTelegramJitter,
            onFatalError = { error ->
                updateStatus(
                    RemoteMonitorStatus.Error(
                        activeCredentials.chatLabel,
                        TelegramRetryPolicy.shortReason(error),
                    ),
                )
            },
            credentialsProvider = { credentials.load() ?: activeCredentials },
            peerHandler = TelegramPeerInboundHandler { update ->
                handlePeerInbound(pollApi, update)
            },
        )
        val processor = TelegramOutboxProcessor(
            credentials = activeCredentials,
            api = uploadApi,
            outbox = outbox,
            retryGate = retryGate,
            connectionTracker = connectionTracker,
            ownedMediaRoot = paths.mediaDirectory,
            jitter = defaultTelegramJitter,
            credentialsProvider = { credentials.load() ?: activeCredentials },
            peerReceipts = peerReceipts,
        )
        val nextUploader = TelegramOutboxWorker(
            outbox = outbox,
            retryGate = retryGate,
            processor = processor,
            nowEpochMs = System::currentTimeMillis,
            onFatalError = { error ->
                updateStatus(
                    RemoteMonitorStatus.Error(
                        activeCredentials.chatLabel,
                        "전송 큐 오류 · ${TelegramRetryPolicy.shortReason(error)}",
                    ),
                )
            },
        )
        if (!nextPoller.start()) {
            nextPoller.close()
            updateStatus(RemoteMonitorStatus.Error(activeCredentials.chatLabel, "이 봇의 수신기가 이미 실행 중입니다."))
            return false
        }
        nextUploader.start()
        poller = nextPoller
        uploader = nextUploader
        true
    }

    fun stop() = synchronized(lifecycleLock) { stopLocked(updateStoppedStatus = true) }

    /** Re-pairing never removes the durable outbox. */
    fun saveConnection(newCredentials: TelegramCredentials, startNow: Boolean = true) {
        synchronized(lifecycleLock) {
            stopLocked(updateStoppedStatus = false)
            clearInboundForConnectionChange()
            preferenceStore.update { it.copy(realtimeActivityEnabled = false) }
            credentials.save(newCredentials)
            updateStatus(RemoteMonitorStatus.Stopped)
        }
        if (startNow) start()
    }

    fun clearConnection() {
        synchronized(lifecycleLock) {
            stopLocked(updateStoppedStatus = false)
            clearInboundForConnectionChange()
            preferenceStore.update { it.copy(realtimeActivityEnabled = false) }
            credentials.clear()
            offsets.reset()
            updateStatus(RemoteMonitorStatus.NotConfigured)
        }
    }

    /** Blocking setup API; call from a setup worker, never the Android main thread. */
    fun validateBotToken(token: String): TelegramBotIdentity = setupClient.validateBotToken(token)

    /** Stops the active poller before tailing updates, preventing two getUpdates owners. */
    fun beginPairing(token: String): TelegramPairingSession {
        stop()
        return setupClient.beginPairing(token)
    }

    fun pollForPairing(
        session: TelegramPairingSession,
        timeoutSeconds: Int = 20,
    ): TelegramPairingRequest? = setupClient.pollForConnection(session, timeoutSeconds)

    fun completePairing(
        session: TelegramPairingSession,
        request: TelegramPairingRequest,
        sendTestMessage: Boolean = true,
    ): TelegramCredentials {
        val paired = synchronized(lifecycleLock) {
            // Stop under the same lock and clear before the setup client commits new credentials.
            // If the process dies between the two durable writes, losing an old pending instruction
            // is safer than showing it to the newly connected parent.
            stopLocked(updateStoppedStatus = false)
            clearInboundForConnectionChange()
            val completed = setupClient.completePairing(session, request)
            // Credentials, the enabled preference and the newly bound workers become visible as
            // one lifecycle transition. Enqueue cannot target the new chat before it is enabled,
            // or the old chat after credentials have changed.
            preferenceStore.update {
                it.copy(monitoringEnabled = true, realtimeActivityEnabled = false)
            }
            start()
            completed
        }
        if (sendTestMessage) setupClient.sendConnectionTest(paired)
        return paired
    }

    fun enqueueText(
        idempotencyKey: String,
        text: String,
        expectedChatId: Long? = null,
    ): TelegramEnqueueResult = enqueue(
        idempotencyKey = idempotencyKey,
        kind = TelegramOutboxKind.TEXT,
        file = null,
        text = text,
        mimeType = null,
        displayName = null,
        expectedChatId = expectedChatId,
        deleteAfterSend = false,
        coalesceKey = null,
    )

    /** Replaces only an older not-yet-sent text in the same logical stream (for idle alerts). */
    fun enqueueLatestText(
        coalesceKey: String = "idle-current",
        idempotencyKey: String,
        text: String,
        expectedChatId: Long? = null,
    ): TelegramEnqueueResult = enqueue(
        idempotencyKey = idempotencyKey,
        kind = TelegramOutboxKind.TEXT,
        file = null,
        text = text,
        mimeType = null,
        displayName = null,
        expectedChatId = expectedChatId,
        deleteAfterSend = false,
        coalesceKey = coalesceKey,
    )

    fun enqueueDocument(
        idempotencyKey: String,
        document: File,
        caption: String,
        mimeType: String,
        displayName: String = document.name,
        expectedChatId: Long? = null,
        deleteAfterSend: Boolean? = null,
    ): TelegramEnqueueResult = enqueue(
        idempotencyKey,
        TelegramOutboxKind.DOCUMENT,
        document,
        caption,
        mimeType,
        displayName,
        expectedChatId,
        deleteAfterSend ?: isOwnedMedia(document),
        coalesceKey = null,
    )

    fun enqueueVoice(
        idempotencyKey: String,
        voice: File,
        caption: String = "학생 음성 메시지",
        mimeType: String = "audio/mp4",
        displayName: String = voice.name,
        expectedChatId: Long? = null,
        deleteAfterSend: Boolean? = null,
    ): TelegramEnqueueResult = enqueue(
        idempotencyKey,
        TelegramOutboxKind.VOICE,
        voice,
        caption,
        mimeType,
        displayName,
        expectedChatId,
        deleteAfterSend ?: isOwnedMedia(voice),
        coalesceKey = null,
    )

    fun enqueueVoice(
        idempotencyKey: String,
        voice: RecordedVoiceMessage,
        caption: String = "학생 음성 메시지",
        expectedChatId: Long? = null,
    ): TelegramEnqueueResult = enqueueVoice(
        idempotencyKey = idempotencyKey,
        voice = voice.file,
        caption = caption,
        expectedChatId = expectedChatId,
    )

    fun pendingOutbox(): List<TelegramOutboxEntry> = outbox.pendingSnapshot()
    fun deadLetters(): List<TelegramDeadLetter> = outbox.deadLetters()
    fun hasSeen(idempotencyKey: String): Boolean = outbox.hasSeen(idempotencyKey)

    /** Removes a not-yet-accepted latest-wins text, for example after student activity resumes. */
    fun cancelCoalesced(coalesceKey: String): Int =
        outbox.cancelCoalesced(coalesceKey, System.currentTimeMillis())

    override fun close() = stop()

    private fun enqueue(
        idempotencyKey: String,
        kind: TelegramOutboxKind,
        file: File?,
        text: String,
        mimeType: String?,
        displayName: String?,
        expectedChatId: Long?,
        deleteAfterSend: Boolean,
        coalesceKey: String?,
    ): TelegramEnqueueResult = synchronized(lifecycleLock) {
        val activeCredentials = credentials.load()
        enqueuePrecondition(
            monitoringEnabled = preferenceStore.get().monitoringEnabled,
            activeCredentials = activeCredentials,
            expectedChatId = expectedChatId,
        )?.let { return it }
        checkNotNull(activeCredentials)
        if (kind != TelegramOutboxKind.TEXT && (file == null || !file.isFile || !file.canRead())) {
            throw IllegalArgumentException("Attachment is missing or unreadable.")
        }
        val now = System.currentTimeMillis()
        val entry = TelegramOutboxEntry(
                idempotencyKey = idempotencyKey,
                destinationChatId = activeCredentials.allowedPrivateChatId,
                kind = kind,
                filePath = file?.absolutePath,
                text = text,
                mimeType = mimeType,
                displayName = displayName,
                attempts = 0,
                nextAttemptEpochMs = now,
                createdAtEpochMs = now,
                deleteAfterSend = deleteAfterSend,
                coalesceKey = coalesceKey,
            )
        val result = if (coalesceKey == null) outbox.enqueue(entry) else outbox.enqueueLatestText(entry)
        if (result == TelegramEnqueueResult.ENQUEUED) uploader?.wake()
        else if (shouldDeleteRejectedOwnedMedia(result) && file != null && isOwnedMedia(file) &&
            outbox.pendingSnapshot().none { it.filePath == file.absolutePath }
        ) {
            runCatching { file.delete() }
        }
        result
    }

    private fun handlePeerInbound(api: TelegramBotApi, update: TelegramInboundUpdate) {
        val active = credentials.load() ?: return
        val key = active.peerSharedKey() ?: return
        val pairId = active.peerPairId ?: return
        val senderId = update.senderId ?: return
        val senderUsername = runCatching { normalizeTelegramUsername(update.senderUsername.orEmpty()) }
            .getOrNull() ?: return

        val control = TelegramPeerProtocol.parseControl(update.text, key)
        if (control != null) {
            handlePeerControl(api, active, update, senderId, senderUsername, pairId, key, control)
            return
        }

        val pinned = active.peerBinding ?: return
        val accepted = when (val decision = classifyInboundPeerDocument(update, pinned, pairId)) {
            is PeerDocumentMetadataDecision.Accepted -> decision
            is PeerDocumentMetadataDecision.Rejected -> {
                logPeerDocumentRejection(update.updateId, decision.reason)
                return
            }
        }
        val document = accepted.document
        val header = accepted.header
        if (!accepted.recognizedMime) {
            // Telegram may omit or normalize this advisory field. Authenticity comes from the
            // pinned sender, strict caption/pair, size bound, and AES-GCM below—not MIME text.
            Log.i(PEER_INBOUND_LOG_TAG, "Peer document has an unrecognized advisory MIME; update=${update.updateId}")
        }
        if (peerDocumentInbox.hasSeen(update.updateId, header.transferId)) {
            if (peerDocumentInbox.isCompleted(header.transferId)) {
                enqueuePeerDeliveryAckLocked(header.transferId, update.updateId)
            }
            return
        }
        val messageId = accepted.messageId
        val encrypted = paths.peerInboxDirectory.resolve("${update.updateId}-${safePeerFileToken(header.transferId)}.cipher")
        val plaintext = paths.peerInboxDirectory.resolve("${update.updateId}-${safePeerFileToken(header.transferId)}.payload")
        try {
            api.downloadFile(
                fileId = document.fileId,
                destination = encrypted,
                maxBytes = TelegramPeerPayloadCipher.MAX_CIPHERTEXT_BYTES,
            )
            val byteCount = TelegramPeerPayloadCipher.decrypt(
                ciphertext = encrypted,
                destination = plaintext,
                key = key,
                associatedData = requireNotNull(update.caption),
            )
            peerDocumentInbox.offer(
                PendingTelegramPeerDocument(
                    updateId = update.updateId,
                    telegramMessageId = messageId,
                    senderBotId = senderId,
                    senderUsername = senderUsername,
                    transferId = header.transferId,
                    payloadType = header.payloadType,
                    fileUniqueId = document.fileUniqueId,
                    originalFileName = null,
                    mimeType = null,
                    byteCount = byteCount,
                    localFilePath = plaintext.absolutePath,
                    receivedAtEpochMs = System.currentTimeMillis(),
                    replyToMessageId = update.replyToMessageId,
                ),
            )
        } catch (error: Throwable) {
            plaintext.delete()
            // Invalid/oversized/authentication-failed content from the exact peer is consumed so a
            // single bad document cannot permanently stop long polling. Network errors still retry.
            if (error is SecurityException) {
                logPeerDocumentRejection(update.updateId, PeerDocumentRejectionReason.AUTHENTICATION_FAILED)
                return
            }
            if (error is IllegalArgumentException) {
                logPeerDocumentRejection(update.updateId, PeerDocumentRejectionReason.INVALID_CIPHERTEXT)
                return
            }
            if (error is TelegramApiException && error.statusCode == 413) {
                logPeerDocumentRejection(update.updateId, PeerDocumentRejectionReason.OVERSIZED)
                return
            }
            throw error
        } finally {
            encrypted.delete()
        }
    }

    private fun handlePeerControl(
        api: TelegramBotApi,
        active: TelegramCredentials,
        update: TelegramInboundUpdate,
        senderId: Long,
        senderUsername: String,
        pairId: String,
        key: ByteArray,
        control: TelegramPeerProtocol.PeerControl,
    ) {
        if (update.chatId != senderId) return
        when (control) {
            is TelegramPeerProtocol.PeerControl.Received -> {
                val pinned = active.peerBinding ?: return
                if (pinned.botId != senderId || pinned.username != senderUsername || control.pairId != pairId) return
                val acknowledgedAt = System.currentTimeMillis()
                if (peerReceipts.recordAcknowledged(
                        control.transferId,
                        update.messageId,
                        acknowledgedAt,
                    )
                ) {
                    peerReceipts.receipt(control.transferId)?.outboxKey?.let { outboxKey ->
                        outbox.makeDueNow(outboxKey, acknowledgedAt)
                    }
                    uploader?.wake()
                }
            }
            is TelegramPeerProtocol.PeerControl.Hello -> {
                if (active.remoteReviewRole != RemoteReviewRole.STUDENT || control.pairId != pairId) return
                if (control.botId != senderId || control.username != senderUsername) return
                val state = peerHandshake.load() ?: return
                if (state.role != RemoteReviewRole.STUDENT || state.pairId != pairId ||
                    System.currentTimeMillis() > state.expiresAtEpochMs
                ) return
                active.peerBinding?.let { pinned ->
                    if (pinned.botId != senderId || pinned.username != senderUsername) return
                }
                val localId = active.localBotId ?: return
                val localUsername = active.localBotUsername ?: return
                val binding = TelegramPeerBinding(senderId, senderUsername)
                credentials.save(active.withPeer(binding))
                peerHandshake.save(
                    state.copy(
                        expectedPeerBotId = senderId,
                        expectedPeerUsername = senderUsername,
                        nonce = control.nonce,
                    ),
                )
                api.sendPeerMessage(
                    senderUsername,
                    TelegramPeerProtocol.ack(
                        pairId,
                        TelegramBotIdentity(localId, localUsername, "MasterNote bot"),
                        control.nonce,
                        key,
                    ),
                )
            }
            is TelegramPeerProtocol.PeerControl.PairAck -> {
                if (active.remoteReviewRole != RemoteReviewRole.TEACHER || control.pairId != pairId) return
                val state = peerHandshake.load() ?: return
                if (state.role != RemoteReviewRole.TEACHER || state.pairId != pairId ||
                    state.nonce != control.nonce || state.expectedPeerBotId != senderId ||
                    state.expectedPeerUsername != senderUsername || control.botId != senderId ||
                    control.username != senderUsername || System.currentTimeMillis() > state.expiresAtEpochMs
                ) return
                credentials.save(active.withPeer(TelegramPeerBinding(senderId, senderUsername)))
            }
        }
    }

    private fun enqueuePeerDeliveryAckLocked(
        transferId: String,
        deliveryUpdateId: Long,
    ): TelegramEnqueueResult {
        val active = credentials.load() ?: return TelegramEnqueueResult.NOT_CONFIGURED
        val peer = active.peerBinding ?: return TelegramEnqueueResult.NOT_CONFIGURED
        val pairId = active.peerPairId ?: return TelegramEnqueueResult.NOT_CONFIGURED
        val key = active.peerSharedKey() ?: return TelegramEnqueueResult.NOT_CONFIGURED
        val ackTransportId = peerDeliveryAckInstanceId(pairId, transferId, deliveryUpdateId)
        val now = System.currentTimeMillis()
        val result = outbox.enqueue(
            TelegramOutboxEntry(
                idempotencyKey = "telegram-peer-received:$ackTransportId",
                destinationChatId = peer.botId,
                kind = TelegramOutboxKind.TEXT,
                filePath = null,
                text = TelegramPeerProtocol.deliveryAck(pairId, transferId, key),
                mimeType = null,
                displayName = null,
                attempts = 0,
                nextAttemptEpochMs = now,
                createdAtEpochMs = now,
                deleteAfterSend = false,
                route = TelegramOutboxRoute.PEER,
                destinationUsername = peer.username,
                peerTransferId = ackTransportId,
            ),
        )
        if (result == TelegramEnqueueResult.ENQUEUED) uploader?.wake()
        return result
    }

    private fun ensureLocalBotIdentityLocked(): Pair<TelegramCredentials, TelegramBotIdentity> {
        val active = requireNotNull(credentials.load()) { "Telegram is not configured." }
        val existingId = active.localBotId
        val existingUsername = active.localBotUsername
        if (existingId != null && existingUsername != null) {
            return active to TelegramBotIdentity(existingId, existingUsername, "MasterNote bot")
        }
        val identity = HttpTelegramBotApi(active.botToken).use(TelegramBotApi::getMe)
        val migrated = active.withLocalBot(identity)
        credentials.save(migrated)
        return migrated to identity
    }

    private fun sendTeacherHello(
        active: TelegramCredentials,
        identity: TelegramBotIdentity,
        state: TelegramPeerHandshakeState,
    ) {
        val target = requireNotNull(state.expectedPeerUsername)
        val nonce = requireNotNull(state.nonce)
        val key = requireNotNull(active.peerSharedKey())
        HttpTelegramBotApi(active.botToken).use { api ->
            api.sendPeerMessage(target, TelegramPeerProtocol.hello(state.pairId, identity, nonce, key))
        }
    }

    /** Stops a possible in-flight upload before revoking its key/destination, then resumes. */
    private inline fun <T> mutatePeerWorkersLocked(block: () -> T): T {
        val restart = poller?.isRunning == true || uploader?.isRunning == true
        if (restart) stopLocked(updateStoppedStatus = false)
        return try {
            block()
        } finally {
            if (restart && preferenceStore.get().monitoringEnabled) start()
        }
    }

    private fun revokePeerStateLocked(nowEpochMs: Long) {
        outbox.cancelPeerEntries(nowEpochMs).forEach { entry ->
            if (entry.deleteAfterSend) deleteOwnedPeerFile(entry.file)
        }
        peerDocumentInbox.clear()
        peerReceipts.clear()
        peerHandshake.clear()
    }

    private fun deleteOwnedPeerFile(file: File?) {
        if (file == null) return
        val root = paths.peerOutboxDirectory.canonicalFile
        val candidate = runCatching { file.canonicalFile }.getOrNull() ?: return
        if (candidate.toPath().startsWith(root.toPath())) runCatching { candidate.delete() }
    }

    private fun safePeerFileToken(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .take(16)
        .joinToString("") { "%02x".format(it) }

    private fun handleInbound(
        activeCredentials: TelegramCredentials,
        update: TelegramInboundUpdate,
        action: ParentInboundAction,
    ) {
        // A remote-review peer can keep this bot's single poller alive even while the human-parent
        // monitor is disabled. In that state consume but do not retain or execute parent traffic.
        if (!preferenceStore.get().monitoringEnabled) return
        when (action) {
            is ParentInboundAction.Text -> {
                // Persist before TelegramInboxPoller commits the update offset. The legacy process
                // bus remains during migration; Reader integrations should use the replayable API.
                if (parentMessageInbox.offer(update.updateId, action.text)) {
                    ParentMessageBus.publish(
                        ParentMessage(update.updateId, action.text, SystemClock.elapsedRealtime()),
                    )
                }
            }
            ParentInboundAction.CurrentPageSnapshot -> {
                val presence = StudentStudyPresenceBus.current()
                val request = PendingScreenRequest(
                    updateId = update.updateId,
                    // Update ids restart in a different bot. Namespace the durable/outbox key so
                    // reconnecting cannot mistake a new parent's /화면 for an old delivered one.
                    requestId = "telegram-screen:${botFingerprint(activeCredentials.botToken)}:${update.updateId}",
                    chatId = activeCredentials.allowedPrivateChatId,
                    requestedAtElapsedMs = SystemClock.elapsedRealtime(),
                    active = presence?.active == true,
                    bookId = presence?.bookId,
                    pageNumber = presence?.pageNumber,
                    attemptNo = presence?.attemptNo,
                )
                if (screenRequestInbox.offer(request)) {
                    // Compatibility event for process-local consumers. Durable integrations must
                    // subscribe through subscribePendingScreenRequests and explicitly acknowledge.
                    RemoteMonitorCommandBus.publish(
                        RemoteMonitorCommand.CurrentPageSnapshot(
                            requestId = request.requestId,
                            updateId = request.updateId,
                            chatId = request.chatId,
                            requestedAtElapsedMs = request.requestedAtElapsedMs,
                        ),
                    )
                }
            }
            ParentInboundAction.EnableRealtimeActivity -> setActivityReportingMode(
                activeCredentials = activeCredentials,
                updateId = update.updateId,
                realtime = true,
            )
            ParentInboundAction.UseHourlyActivity -> setActivityReportingMode(
                activeCredentials = activeCredentials,
                updateId = update.updateId,
                realtime = false,
            )
        }
    }

    private fun setActivityReportingMode(
        activeCredentials: TelegramCredentials,
        updateId: Long,
        realtime: Boolean,
    ) {
        updatePreferences { it.copy(realtimeActivityEnabled = realtime) }
        enqueueLatestText(
            coalesceKey = ACTIVITY_MODE_CONFIRMATION_COALESCE_KEY,
            idempotencyKey = "telegram-activity-mode:${botFingerprint(activeCredentials.botToken)}:$updateId",
            expectedChatId = activeCredentials.allowedPrivateChatId,
            text = if (realtime) {
                "활동 알림 · 실시간 모드\n30초부터 움직임이 없을 때 바로 알려드립니다. /일반 으로 돌아갈 수 있습니다."
            } else {
                "활동 알림 · 일반 모드\n실시간 알림을 멈추고 1시간마다 가볍게 요약합니다. /실시간 으로 다시 켤 수 있습니다."
            },
        )
    }

    private fun stopLocked(updateStoppedStatus: Boolean) {
        poller?.close()
        uploader?.close()
        poller = null
        uploader = null
        if (updateStoppedStatus) {
            updateStatus(if (credentials.load() == null) RemoteMonitorStatus.NotConfigured else RemoteMonitorStatus.Stopped)
        }
    }

    private fun clearInboundForConnectionChange() {
        parentMessageInbox.clear()
        screenRequestInbox.clear()
        revokePeerStateLocked(System.currentTimeMillis())
        purgeUnqueuedVoiceMedia(paths, outbox)
    }

    private fun updateStatus(next: RemoteMonitorStatus) {
        val listeners = synchronized(statusLock) {
            if (next == currentStatus) return
            currentStatus = next
            statusListeners.toList()
        }
        listeners.forEach { it(next) }
    }

    private fun isOwnedMedia(file: File): Boolean = runCatching {
        file.canonicalFile.toPath().startsWith(paths.mediaDirectory.canonicalFile.toPath())
    }.getOrDefault(false)

    companion object {
        private const val ACTIVITY_MODE_CONFIRMATION_COALESCE_KEY = "activity-mode-confirmation"
        @Volatile private var instance: RemoteMonitorGateway? = null

        fun get(context: Context): RemoteMonitorGateway = instance ?: synchronized(this) {
            instance ?: RemoteMonitorGateway(context.applicationContext).also { instance = it }
        }
    }
}

/** Must be evaluated while [RemoteMonitorGateway]'s lifecycle lock is held. */
internal fun enqueuePrecondition(
    monitoringEnabled: Boolean,
    activeCredentials: TelegramCredentials?,
    expectedChatId: Long?,
): TelegramEnqueueResult? = when {
    !monitoringEnabled || activeCredentials == null -> TelegramEnqueueResult.NOT_CONFIGURED
    expectedChatId != null && expectedChatId != activeCredentials.allowedPrivateChatId ->
        TelegramEnqueueResult.CHAT_CHANGED
    else -> null
}

internal enum class PeerDocumentRejectionReason {
    WRONG_PEER,
    MISSING_DOCUMENT,
    INVALID_CAPTION,
    WRONG_PAIR,
    OVERSIZED,
    MISSING_MESSAGE_ID,
    AUTHENTICATION_FAILED,
    INVALID_CIPHERTEXT,
}

internal sealed interface PeerDocumentMetadataDecision {
    data class Accepted(
        val messageId: Long,
        val document: TelegramInboundDocument,
        val header: TelegramPeerProtocol.PeerDocumentHeader,
        val recognizedMime: Boolean,
    ) : PeerDocumentMetadataDecision

    data class Rejected(
        val reason: PeerDocumentRejectionReason,
    ) : PeerDocumentMetadataDecision
}

/**
 * Pure metadata gate before the bounded download and authenticated decrypt.
 *
 * Telegram's Document.mime_type is optional and may be normalized to application/octet-stream.
 * It is intentionally advisory; an unfamiliar value is recorded on [Accepted] for diagnostics,
 * but never substitutes for the pinned identity, pair/caption, size, or AES-GCM checks.
 */
internal fun classifyInboundPeerDocument(
    update: TelegramInboundUpdate,
    pinned: TelegramPeerBinding,
    pairId: String,
): PeerDocumentMetadataDecision {
    val senderId = update.senderId
        ?: return PeerDocumentMetadataDecision.Rejected(PeerDocumentRejectionReason.WRONG_PEER)
    val senderUsername = runCatching { normalizeTelegramUsername(update.senderUsername.orEmpty()) }
        .getOrNull()
        ?: return PeerDocumentMetadataDecision.Rejected(PeerDocumentRejectionReason.WRONG_PEER)
    if (!update.senderIsBot || update.chatType != "private" || update.chatId != senderId ||
        pinned.botId != senderId || pinned.username != senderUsername
    ) {
        return PeerDocumentMetadataDecision.Rejected(PeerDocumentRejectionReason.WRONG_PEER)
    }
    val document = update.document
        ?: return PeerDocumentMetadataDecision.Rejected(PeerDocumentRejectionReason.MISSING_DOCUMENT)
    val header = TelegramPeerProtocol.parseDocumentCaption(update.caption)
        ?: return PeerDocumentMetadataDecision.Rejected(PeerDocumentRejectionReason.INVALID_CAPTION)
    if (header.pairId != pairId) {
        return PeerDocumentMetadataDecision.Rejected(PeerDocumentRejectionReason.WRONG_PAIR)
    }
    if (document.fileSizeBytes?.let { it > TelegramPeerPayloadCipher.MAX_CIPHERTEXT_BYTES } == true) {
        return PeerDocumentMetadataDecision.Rejected(PeerDocumentRejectionReason.OVERSIZED)
    }
    val messageId = update.messageId
        ?: return PeerDocumentMetadataDecision.Rejected(PeerDocumentRejectionReason.MISSING_MESSAGE_ID)
    return PeerDocumentMetadataDecision.Accepted(
        messageId = messageId,
        document = document,
        header = header,
        recognizedMime = isRecognizedPeerDocumentMime(document.mimeType),
    )
}

internal fun isRecognizedPeerDocumentMime(value: String?): Boolean =
    value == null ||
        value.equals("application/octet-stream", ignoreCase = true) ||
        value.equals(TelegramPeerProtocol.CIPHERTEXT_MIME, ignoreCase = true)

private fun logPeerDocumentRejection(updateId: Long, reason: PeerDocumentRejectionReason) {
    // Only a bounded enum and Telegram update id are logged: no bot username, caption, path, key,
    // file id, API token, or exception text can escape through this diagnostic.
    Log.w(PEER_INBOUND_LOG_TAG, "Peer document rejected: reason=${reason.name} update=$updateId")
}

/** Queue-full means the caller still owns the only copy and may retry once capacity is available. */
internal fun shouldDeleteRejectedOwnedMedia(result: TelegramEnqueueResult): Boolean = when (result) {
    TelegramEnqueueResult.ENQUEUED,
    TelegramEnqueueResult.QUEUE_FULL,
    -> false
    else -> true
}

internal fun withinPeerDocumentDiskQuota(
    pendingDocumentCount: Int,
    pendingCiphertextBytes: Long,
    nextCiphertextBytes: Long,
): Boolean {
    require(pendingDocumentCount >= 0 && pendingCiphertextBytes >= 0L && nextCiphertextBytes >= 0L)
    if (pendingDocumentCount >= MAX_PENDING_PEER_DOCUMENTS) return false
    return nextCiphertextBytes <= MAX_PENDING_PEER_DOCUMENT_BYTES -
        pendingCiphertextBytes.coerceAtMost(MAX_PENDING_PEER_DOCUMENT_BYTES)
}

private const val MAX_PENDING_PEER_DOCUMENTS = 48
private const val MAX_PENDING_PEER_DOCUMENT_BYTES = 96L * 1_024L * 1_024L
private const val PEER_INBOUND_LOG_TAG = "MasterNotePeerInbound"

/** A retransmitted document gets a fresh ACK while duplicate handling stays idempotent per update. */
internal fun peerDeliveryAckInstanceId(pairId: String, transferId: String, updateId: Long): String {
    require(pairId.isNotBlank() && transferId.isNotBlank() && updateId >= 0L)
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest("$pairId:$transferId:$updateId".toByteArray(java.nio.charset.StandardCharsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    return "ack_$digest"
}

/**
 * Recovers a recording which was atomically finalized but whose Activity died before enqueue.
 * Returned paths are the only queue-full/temporary-failure files cleanup must retain for retry on
 * the next process start. Without credentials there is no safe destination, so cleanup removes the
 * orphan rather than exposing an old parent's voice to a future pairing.
 */
internal fun recoverUnqueuedVoiceMedia(
    paths: TelegramStoragePaths,
    outbox: TelegramOutbox,
    credentials: TelegramCredentials?,
    nowEpochMs: Long,
): Set<String> {
    require(nowEpochMs >= 0L)
    val referenced = outbox.pendingSnapshot().mapNotNull(TelegramOutboxEntry::filePath)
        .mapNotNull { runCatching { File(it).canonicalPath }.getOrNull() }
        .toSet()
    if (credentials == null) return emptySet()
    val retain = linkedSetOf<String>()
    paths.voiceDirectory.listFiles().orEmpty()
        .asSequence()
        .filter { it.isFile && it.name.endsWith(".m4a", ignoreCase = true) }
        .sortedBy(File::getName)
        .forEach { candidate ->
            val canonicalPath = runCatching { candidate.canonicalPath }.getOrNull() ?: return@forEach
            if (canonicalPath in referenced) return@forEach
            val key = "student-voice:${candidate.name}"
            if (outbox.hasSeen(key)) return@forEach
            val result = runCatching {
                outbox.enqueue(
                    TelegramOutboxEntry(
                        idempotencyKey = key,
                        destinationChatId = credentials.allowedPrivateChatId,
                        kind = TelegramOutboxKind.VOICE,
                        filePath = candidate.absolutePath,
                        text = "학생 음성 메시지",
                        mimeType = "audio/mp4",
                        displayName = candidate.name,
                        attempts = 0,
                        nextAttemptEpochMs = nowEpochMs,
                        createdAtEpochMs = nowEpochMs,
                        deleteAfterSend = true,
                    ),
                )
            }.getOrNull()
            if (result == null || result == TelegramEnqueueResult.QUEUE_FULL) retain += canonicalPath
        }
    return retain
}

internal fun cleanupUnqueuedOwnedMedia(
    paths: TelegramStoragePaths,
    outbox: TelegramOutbox,
    additionallyProtected: Set<String> = emptySet(),
) {
    // A staging recording is never complete and must not survive a process restart, even if a
    // corrupt/old journal happened to mention it.
    paths.voiceDirectory.listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(".part", ignoreCase = true) }
        .forEach { runCatching { it.delete() } }
    val protected = outbox.pendingSnapshot().mapNotNull(TelegramOutboxEntry::filePath)
        .mapNotNull { runCatching { File(it).canonicalPath }.getOrNull() }
        .toSet() + additionallyProtected
    paths.mediaDirectory.walkBottomUp().forEach { candidate ->
        if (candidate.isFile && runCatching { candidate.canonicalPath }.getOrNull() !in protected) {
            runCatching { candidate.delete() }
        }
    }
}

/** An unqueued recording has no durable destination identity, so it cannot cross a pairing. */
internal fun purgeUnqueuedVoiceMedia(paths: TelegramStoragePaths, outbox: TelegramOutbox) {
    val protected = outbox.pendingSnapshot().mapNotNull(TelegramOutboxEntry::filePath)
        .mapNotNull { runCatching { File(it).canonicalPath }.getOrNull() }
        .toSet()
    paths.voiceDirectory.listFiles().orEmpty().forEach { candidate ->
        val canonicalPath = runCatching { candidate.canonicalPath }.getOrNull()
        if (candidate.isFile && (
                candidate.name.endsWith(".part", ignoreCase = true) || canonicalPath !in protected
            )
        ) {
            runCatching { candidate.delete() }
        }
    }
}
