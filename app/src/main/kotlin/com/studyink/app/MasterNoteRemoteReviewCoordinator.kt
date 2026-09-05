package com.studyink.app

import com.studyink.construction.storage.ConstructionTarget

import android.app.Application
import android.os.SystemClock
import com.studyink.assistant.core.AssistantRepositoryProvider
import com.studyink.assistant.core.PendingStudentExplanationPublication
import com.studyink.assistant.core.StudentExplanationLayer
import com.studyink.assistant.core.StudentExplanationLayerBus
import com.studyink.assistant.core.StudentLayerApplyStatus
import com.studyink.annotation.engine.AnnotationChange
import com.studyink.annotation.storage.PageOperationLogStore
import com.studyink.core.model.ANNOTATION_FORMAT_VERSION
import com.studyink.core.model.Attempt
import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.AssetOperation
import com.studyink.core.model.CANONICAL_PAGE_WIDTH
import com.studyink.core.model.Mark
import com.studyink.core.model.MarkColor
import com.studyink.core.model.MarkGroup
import com.studyink.core.model.OperationId
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeId
import com.studyink.core.model.StrokeTool
import com.studyink.library.data.LibraryRepository
import com.studyink.library.data.LibraryAttemptBus
import com.studyink.memo.core.MemoAuthoritativeApplyStatus
import com.studyink.memo.core.MemoTarget
import com.studyink.memo.core.StudentMemo
import com.studyink.memo.core.StudentMemoChange
import com.studyink.memo.core.StudentMemoChangeBus
import com.studyink.memo.core.StudentMemoRepository
import com.studyink.memo.core.remapTo
import com.studyink.monitor.core.NormalizedTeacherPoint
import com.studyink.monitor.core.NormalizedTeacherStroke
import com.studyink.monitor.core.NormalizedGradeAnchor
import com.studyink.monitor.core.HybridLinkDecision
import com.studyink.monitor.core.HybridLinkHealth
import com.studyink.monitor.core.HybridLinkLabel
import com.studyink.monitor.core.HybridLinkMode
import com.studyink.monitor.core.HybridLinkSignals
import com.studyink.monitor.core.HybridLinkStateMachine
import com.studyink.monitor.core.HybridLinkStatus
import com.studyink.monitor.core.HybridLinkStatusBus
import com.studyink.monitor.core.HybridLinkTransport
import com.studyink.monitor.core.GptExplanationLayerEnvelope
import com.studyink.monitor.core.REMOTE_LEGACY_EXPLANATION_AUTHORITY
import com.studyink.monitor.core.ChatMessageEnvelope
import com.studyink.monitor.core.DecodedRemoteReviewDocument
import com.studyink.monitor.core.PageSnapshotEnvelope
import com.studyink.monitor.core.PageAnnotationEnvelope
import com.studyink.monitor.core.PageSyncAckEnvelope
import com.studyink.monitor.core.PageSyncManifestEnvelope
import com.studyink.monitor.core.PageSyncRequestEnvelope
import com.studyink.monitor.core.RemoteReviewDocumentCodec
import com.studyink.monitor.core.RemoteReviewEnvelopeType
import com.studyink.monitor.core.RemoteReviewFeedbackBus
import com.studyink.monitor.core.RemoteGradeApplied
import com.studyink.monitor.core.RemoteGradeAppliedBus
import com.studyink.monitor.core.RemoteGradeEnvelope
import com.studyink.monitor.core.RemotePeerChatScope
import com.studyink.monitor.core.RemotePeerChatState
import com.studyink.monitor.core.RemotePeerChatStateBus
import com.studyink.monitor.core.RemoteReviewLimits
import com.studyink.monitor.core.RemoteReviewExchangeStateMachine
import com.studyink.monitor.core.RemoteTeacherFeedbackApplied
import com.studyink.monitor.core.ReviewCanvasDimensions
import com.studyink.monitor.core.SnapshotImageFormat
import com.studyink.monitor.core.SnapshotReference
import com.studyink.monitor.core.StudentStudyPresence
import com.studyink.monitor.core.StudentStudyPresenceBus
import com.studyink.monitor.core.StudentMemoEnvelope
import com.studyink.monitor.core.StudentWorkHeartbeat
import com.studyink.monitor.core.StudentWorkHeartbeatBus
import com.studyink.monitor.core.StudentWorkKind
import com.studyink.monitor.core.TeacherFeedbackEnvelope
import com.studyink.monitor.core.TeacherInkTool
import com.studyink.monitor.core.TeacherReviewPublishedBus
import com.studyink.monitor.core.TeacherReviewPublicationProvenanceBus
import com.studyink.monitor.render.MasterNotePageRenderer
import com.studyink.monitor.render.PageRenderImageFormat
import com.studyink.monitor.render.PageRenderLimits
import com.studyink.monitor.render.PageRenderRequest
import com.studyink.monitor.render.RenderedPage
import com.studyink.monitor.telegram.PendingTelegramPeerDocument
import com.studyink.monitor.telegram.RemoteMonitorGateway
import com.studyink.monitor.telegram.RemoteReviewPeerStatus
import com.studyink.monitor.telegram.RemoteReviewRole
import com.studyink.monitor.telegram.RemoteMonitorStatus
import com.studyink.monitor.telegram.TelegramPeerLinkState
import com.studyink.monitor.telegram.TelegramEnqueueResult
import com.studyink.reader.RemoteFeedbackStrokeTool
import com.studyink.reader.RemoteFeedbackStroke
import com.studyink.reader.RemoteNormalizedPoint
import com.studyink.reader.RemoteTeacherFeedback
import com.studyink.sync.lan.LanConnectionState
import com.studyink.sync.lan.LanSessionPhase
import com.studyink.sync.lan.LanSessionSnapshot
import com.studyink.sync.lan.LanSyncBus
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max
import kotlin.math.min

private val RETIRED_LEGACY_REMOTE_REVIEW_PAYLOAD_TYPES = setOf(
    RemoteReviewEnvelopeType.PAGE_SNAPSHOT.name,
    RemoteReviewEnvelopeType.TEACHER_FEEDBACK.name,
    RemoteReviewEnvelopeType.REMOTE_GRADE.name,
)

internal const val LAN_CATCH_UP_GRACE_MS = 4_000L

/**
 * Application boundary for the optional Telegram remote-review path.
 *
 * The existing parent text/submission coordinator remains untouched. This coordinator owns one
 * serial worker and exchanges rendered student pages, a published teacher ink layer, typed chat,
 * and page-bound binary grades. It never mutates attempts, student ink, or LAN session state.
 */
object MasterNoteRemoteReviewCoordinator {
    /** The callback only copies metadata. Encoding and I/O must run outside this lock. */
    internal fun <T> withConstructionOutbound(target: ConstructionTarget, block: (ConstructionTelegramRoute) -> T): T? =
        runtime?.withConstructionOutbound(target, block)

    internal fun <T> withConstructionIncoming(senderBotId: Long, address: ConstructionTelegramAddress, block: (ConstructionTelegramRoute) -> T): T? =
        runtime?.withConstructionIncoming(senderBotId, address, block)
    private val lifecycleLock = Any()
    private val inboxListeners = CopyOnWriteArraySet<() -> Unit>()

    @Volatile
    private var runtime: RemoteReviewRuntime? = null

    /** Safe to call repeatedly; after [shutdown], a later call creates a fresh worker. */
    fun initialize(app: Application) {
        synchronized(lifecycleLock) {
            if (runtime != null) return
            runtime = RemoteReviewRuntime(app, ::notifyInboxChanged)
                .also(RemoteReviewRuntime::start)
        }
    }

    /** Does not stop the shared Telegram gateway owned by RemoteMonitorService. */
    fun shutdown() {
        val closing = synchronized(lifecycleLock) {
            val value = runtime
            runtime = null
            value
        }
        closing?.close()
    }

    /** Before initialization this intentionally returns an empty, immutable view. */
    internal fun incomingSnapshots(): List<IncomingRemoteSnapshot> =
        runtime?.incomingSnapshots().orEmpty()

    /**
     * Blocking durable enqueue API used by the teacher editor's background executor.
     * Before initialization or without the exact connected teacher peer it returns NOT_CONFIGURED.
     */
    fun publishTeacherFeedback(feedback: RemoteTeacherFeedback): TelegramEnqueueResult =
        runtime?.publishTeacherFeedback(feedback) ?: TelegramEnqueueResult.NOT_CONFIGURED

    /** Publishes one page-bound correct/incorrect mark for the exact rendered student attempt. */
    fun publishRemoteGrade(
        snapshotTransferId: String,
        anchorX: Float,
        anchorY: Float,
        color: MarkColor,
    ): TelegramEnqueueResult = runtime?.publishRemoteGrade(
        snapshotTransferId = snapshotTransferId,
        anchorX = anchorX,
        anchorY = anchorY,
        color = color,
    ) ?: TelegramEnqueueResult.NOT_CONFIGURED

    /** Current pair-scoped encrypted text conversation, if a peer is fully connected. */
    fun remotePeerChatState(): RemotePeerChatState? = runtime?.remotePeerChatState()

    /** Queues one encrypted peer-to-peer text message. Human Telegram chat is not used here. */
    fun sendRemotePeerChat(text: String): TelegramEnqueueResult =
        runtime?.sendRemotePeerChat(text) ?: TelegramEnqueueResult.NOT_CONFIGURED

    /** Sends an expiring signed wake-up to the already-paired student bot. */
    fun requestRemotePeerConnection(): TelegramEnqueueResult =
        runtime?.requestRemotePeerConnection() ?: TelegramEnqueueResult.NOT_CONFIGURED

    fun remotePeerLinkState(): TelegramPeerLinkState =
        runtime?.remotePeerLinkState() ?: TelegramPeerLinkState.UNAVAILABLE

    /** Durably clears the unread badge for the exact current pairing. */
    fun markRemotePeerChatRead(): RemotePeerChatState? = runtime?.markRemotePeerChatRead()

    /** Page-level Telegram backlog shown below the encrypted peer conversation. */
    fun pageSyncUiState(): RemotePageSyncUiState =
        runtime?.pageSyncUiState() ?: RemotePageSyncUiState()

    fun addPageSyncListener(listener: (RemotePageSyncUiState) -> Unit): AutoCloseable =
        runtime?.addPageSyncListener(listener) ?: AutoCloseable { }

    /** Starts latest-first manual recovery for pages outside the automatic recent-three set. */
    fun startPendingPageSync(intervalSeconds: Int) {
        runtime?.startPendingPageSync(intervalSeconds)
    }

    fun pausePendingPageSync() {
        runtime?.pausePendingPageSync()
    }

    fun workbookMappingCandidates(pageToken: String): List<RemoteWorkbookMappingCandidate> =
        runtime?.workbookMappingCandidates(pageToken).orEmpty()

    fun bindWorkbookMapping(pageToken: String, localBookId: String): Boolean =
        runtime?.bindWorkbookMapping(pageToken, localBookId) == true

    /**
     * Stops accepting review work and waits until every operation that could touch rendered pages,
     * annotations, or the review ledger has left the coordinator's serial boundary.
     */
    fun pauseAndAwait(timeoutMillis: Long): Boolean =
        runtime?.pauseAndAwait(timeoutMillis) ?: true

    /**
     * Invalidates student-side source mappings after a successful application data-root restore.
     * The maintenance owner must call this only after [pauseAndAwait] returned true.
     */
    fun onDataRootReplaced() {
        runtime?.onDataRootReplaced()
    }

    /** Resumes sticky-presence capture and pending encrypted inbox processing. */
    fun resume() {
        runtime?.resume()
    }

    /** Last full teacher layer rebound to the exact currently opened student snapshot. */
    internal fun publishedFeedbackState(snapshotTransferId: String): RemotePublishedFeedbackState =
        runtime?.publishedFeedbackState(snapshotTransferId) ?: RemotePublishedFeedbackState.NONE

    internal fun latestPublishedFeedback(snapshotTransferId: String): RemoteTeacherFeedback? =
        (publishedFeedbackState(snapshotTransferId) as? RemotePublishedFeedbackState.Available)?.feedback

    /** Listener registration is safe even before [initialize]. */
    fun addInboxListener(listener: () -> Unit): AutoCloseable {
        inboxListeners += listener
        return AutoCloseable { inboxListeners -= listener }
    }

    private fun notifyInboxChanged() {
        inboxListeners.forEach { listener -> runCatching(listener) }
    }
}

private class RemoteReviewRuntime(
    private val application: Application,
    private val onInboxChanged: () -> Unit,
) : AutoCloseable {
    private val gateway = RemoteMonitorGateway.get(application)
    private val library = LibraryRepository.get(application)
    private val annotationStore = PageOperationLogStore.get(application)
    private val assistantRepository = AssistantRepositoryProvider.get(application)
    private val memoRepository = StudentMemoRepository.get(application)
    private val ledger = RemoteReviewLedger(
        File(application.noBackupFilesDir, "remote-review/exchange-ledger"),
    )
    private val ownership = RemoteReviewPeerOwnershipStore(
        File(application.noBackupFilesDir, "remote-review/peer-ownership.v1"),
    )
    private val tokenFactory = RemoteReviewPageTokenFactory(
        File(application.noBackupFilesDir, "remote-review/page-token.key"),
    )
    private val teacherRevisions = RemoteReviewTeacherRevisionStore(
        File(application.noBackupFilesDir, "remote-review/teacher-revisions.v1"),
    )
    private val publishedFeedback = RemoteReviewPublishedFeedbackStore(
        File(application.noBackupFilesDir, "remote-review/published-feedback"),
    )
    private val peerChat = RemotePeerChatStore(
        File(application.noBackupFilesDir, "remote-review/peer-chat"),
    )
    private val pageSyncStore = RemotePageSyncStore(
        File(application.noBackupFilesDir, "remote-review/page-sync-metadata.v1.json"),
    )
    private val stagingDirectory = File(application.noBackupFilesDir, "remote-review/staging").apply {
        check(mkdirs() || isDirectory) { "Cannot create remote-review staging directory" }
        listFiles().orEmpty().filter(File::isFile).forEach(File::delete)
    }
    private val primaryRenderer = MasterNotePageRenderer(
        application,
        PageRenderLimits(
            targetWidthPixels = 1_600,
            minimumWidthPixels = 1_280,
            maximumWidthPixels = 1_600,
            imageFormat = PageRenderImageFormat.JPEG,
            jpegQuality = 86,
        ),
    )
    private val fallbackRenderer = MasterNotePageRenderer(
        application,
        PageRenderLimits(
            targetWidthPixels = 1_280,
            minimumWidthPixels = 1_280,
            maximumWidthPixels = 1_280,
            imageFormat = PageRenderImageFormat.JPEG,
            jpegQuality = 70,
        ),
    )
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "MasterNote-remote-review").apply { isDaemon = true }
    }
    private val pageSync = RemotePageSyncController(
        library = library,
        annotationStore = annotationStore,
        store = pageSyncStore,
        pageToken = tokenFactory::pageSyncPageToken,
        workbookToken = tokenFactory::pageSyncWorkbookToken,
        reserveTeacherReviewRevision = { pageToken, transferId ->
            teacherRevisions.reserve(pageToken, transferId, requestedRevision = 1L)
        },
        outboundState = { payloadType, transferId ->
            val receipt = gateway.peerDeliveryReceipt(transferId)
            when {
                receipt?.acknowledgedAtEpochMs != null ->
                    RemotePageSyncOutboundState.ACKNOWLEDGED
                gateway.pendingPeerDocumentTransfers(setOf(payloadType)).any {
                    it.transferId == transferId
                } -> RemotePageSyncOutboundState.PENDING
                gateway.deadLetters().any { it.entry.peerTransferId == transferId } ->
                    RemotePageSyncOutboundState.FAILED
                receipt != null && isUnacknowledgedPeerReceiptExpired(
                    receipt.serverAcceptedAtEpochMs ?: receipt.sentAtEpochMs,
                    System.currentTimeMillis(),
                ) -> RemotePageSyncOutboundState.FAILED
                receipt != null -> RemotePageSyncOutboundState.SENT
                else -> RemotePageSyncOutboundState.NONE
            }
        },
        sendEnvelope =(::enqueuePageSyncEnvelope),
    )
    private val operationLock = Any()
    private val captureState = RemoteReviewCaptureState()
    private var hybridStateMachine = HybridLinkStateMachine()
    private var hybridBookId: String? = null
    private var hybridDecision: HybridLinkDecision? = null
    private var pageSyncTransportRoute = GlobalPageSyncTransportRoute.TELEGRAM
    private var lanCatchUpBookId: String? = null
    private var lanCatchUpStartedAtElapsedMs: Long? = null
    private var lanCatchUpYieldRequested = false
    private var telegramStatus: RemoteMonitorStatus = gateway.status()
    private var telegramPeerLinkState: TelegramPeerLinkState = gateway.peerLinkState()
    private var observedSession: ConnectedRemoteReviewSession? = null
    private var presenceSubscription: AutoCloseable? = null
    private var heartbeatSubscription: AutoCloseable? = null
    private var peerDocumentSubscription: AutoCloseable? = null
    private var telegramStatusSubscription: AutoCloseable? = null
    private var telegramPeerLinkSubscription: AutoCloseable? = null
    private var teacherReviewSubscription: AutoCloseable? = null
    private var teacherReviewProvenanceSubscription: AutoCloseable? = null
    private var memoChangeSubscription: AutoCloseable? = null
    private val assistantLayerListener = object : StudentExplanationLayerBus.Listener {
        override fun onLocalLayerPublished(layer: StudentExplanationLayer) {
            execute { publishGptExplanationLayer(layer) }
        }
    }
    /** Retry timestamps only; the sidecar journal and layer files remain authoritative. */
    private val gptPublicationAttemptsAtElapsedMs = linkedMapOf<String, Long>()
    /** Local edits are coalesced by memo identity; recovery walks only one durable memo per tick. */
    private val pendingMemoChanges = linkedMapOf<StudentMemoKey, PendingStudentMemoChange>()
    private val memoRecoveryTargets = ArrayDeque<MemoTarget>()
    private val memoRecoveryMemos = ArrayDeque<StudentMemoKey>()
    private var lastMemoRecoveryEpochDay = Long.MIN_VALUE
    /**
     * Rendered snapshots and their separate feedback/grade replies had no ordering relation with
     * page sync. Keep retrying durable cancellation until the outbox proves all three types empty;
     * a disk-full failure must not leave a stale action queued for the next reconnect.
     */
    private var legacyRenderedPageTransportRetired = false
    /** Decode failures are retried after process/session restart, but skipped within this runtime. */
    private val retainedUndecodableUpdateIds = linkedSetOf<Long>()
    private val lanListener = object : LanSyncBus.Listener {
        override fun onLocalOperation(bookId: String, pageNumber: Int) {
            execute { pageSync.onLocalOperation(bookId, pageNumber) }
        }

        override fun onConnectionStateChanged(bookId: String, state: LanConnectionState) {
            execute {
                // Use the transition carried by the callback, not just the latest sticky state.
                // A fast same-book reconnect must always receive a fresh four-second lease.
                if (state != LanConnectionState.CONNECTED) resetLanCatchUpLease(bookId)
                updateHybridLink(bookId)
            }
        }

        override fun onSessionPhaseChanged(bookId: String, phase: LanSessionPhase) {
            execute { updateHybridLink(bookId) }
        }

        override fun onPagePresenceChanged(presence: com.studyink.sync.lan.PagePresence) {
            execute { updateHybridLink(presence.bookId) }
        }

        override fun onTeacherReviewAcknowledged(
            publication: com.studyink.sync.lan.LanTeacherReviewPublication,
        ) {
            execute { pageSync.onLanTeacherReviewAcknowledged(publication) }
        }
    }
    private val attemptListener = object : LibraryAttemptBus.Listener {
        override fun onLocalAttemptChanged(attempt: Attempt) {
            execute { pageSync.onLocalOperation(attempt.bookId, attempt.pageNumber) }
        }
    }

    @Volatile
    private var closed = false

    @Volatile
    private var pausedForMaintenance = false

    private val workGeneration = AtomicLong()

    fun start() {
        LanSyncBus.addListener(lanListener)
        LibraryAttemptBus.addListener(attemptListener)
        StudentExplanationLayerBus.addListener(assistantLayerListener)
        memoChangeSubscription = StudentMemoChangeBus.addListener { change ->
            execute { onStudentMemoChanged(change) }
        }
        teacherReviewProvenanceSubscription = TeacherReviewPublicationProvenanceBus.install { target ->
            synchronized(operationLock) {
                if (closed || pausedForMaintenance) null else {
                    refreshSession()
                    pageSync.teacherReviewPublicationProvenance(
                        target.bookId,
                        target.pageNumber,
                        target.attemptNo,
                    )
                }
            }
        }
        presenceSubscription = StudentStudyPresenceBus.subscribe { presence ->
            execute { handlePresence(presence) }
        }
        heartbeatSubscription = StudentWorkHeartbeatBus.subscribe { heartbeat ->
            execute { handleHeartbeat(heartbeat) }
        }
        teacherReviewSubscription = TeacherReviewPublishedBus.subscribe { event ->
            execute { pageSync.onTeacherReviewPublished(event) }
        }
        peerDocumentSubscription = gateway.subscribePeerDocuments { pending ->
            execute { processIncoming(pending) }
        }
        telegramStatusSubscription = gateway.subscribeStatus { status ->
            execute {
                telegramStatus = status
                hybridBookId?.let(::updateHybridLink)
                refreshPageSyncTransportState()
                pageSync.tick()
            }
        }
        telegramPeerLinkSubscription = gateway.subscribePeerLinkState { state ->
            execute {
                val becameAvailable = !telegramPeerLinkState.peerRecent && state.peerRecent
                telegramPeerLinkState = state
                hybridBookId?.let(::updateHybridLink)
                refreshPageSyncTransportState()
                if (becameAvailable) pageSync.onPeerAvailable() else pageSync.tick()
            }
        }
        // The LAN service can already be running before this application coordinator starts.
        // Listener registration prevents a later transition from being missed; this sticky atomic
        // read covers the state that existed before registration.
        LanSyncBus.activeSessionSnapshot()?.let { active ->
            execute { updateHybridLink(active.bookId) }
        }
        worker.scheduleWithFixedDelay(
            { runActiveOperation(workGeneration.get()) { tick() } },
            TICK_MILLIS,
            TICK_MILLIS,
            TimeUnit.MILLISECONDS,
        )
    }

    fun incomingSnapshots(): List<IncomingRemoteSnapshot> {
        if (closed || pausedForMaintenance) return emptyList()
        val snapshots = ledger.incomingSnapshots()
        val session = connectedSession()?.takeIf { it.role == RemoteReviewRole.TEACHER }
            ?: return emptyList()
        return snapshots.filter { snapshot -> ownership.owner(snapshot.transferId)?.matches(session) == true }
    }

    fun remotePeerChatState(): RemotePeerChatState? {
        if (closed || pausedForMaintenance) return null
        val session = connectedSession() ?: return null
        val scope = chatScope(session) ?: return null
        return peerChat.state(scope)
    }

    fun sendRemotePeerChat(text: String): TelegramEnqueueResult {
        if (closed || pausedForMaintenance) return TelegramEnqueueResult.NOT_CONFIGURED
        val normalized = text.trim()
        if (normalized.isEmpty() ||
            normalized.toByteArray(StandardCharsets.UTF_8).size > RemoteReviewLimits.MAX_CHAT_TEXT_UTF8_BYTES
        ) return TelegramEnqueueResult.PREVIOUSLY_SUPERSEDED
        return synchronized(operationLock) {
            val session = connectedSession()
                ?: return@synchronized TelegramEnqueueResult.NOT_CONFIGURED
            // A ready live session owns application traffic. The conversation remains readable,
            // but new Telegram messages wait until the UI has actually switched to 텔. A teacher
            // that never started a LAN service has no active LAN session and can use Telegram.
            if (!allowsTelegramUserActionNow()) {
                return@synchronized TelegramEnqueueResult.PREVIOUSLY_SUPERSEDED
            }
            val scope = chatScope(session)
                ?: return@synchronized TelegramEnqueueResult.NOT_CONFIGURED
            val suffix = UUID.randomUUID().toString().replace("-", "")
            val now = System.currentTimeMillis()
            val envelope = ChatMessageEnvelope(
                transferId = "chat_$suffix",
                createdAtEpochMs = now,
                messageId = "chatmsg_$suffix",
                senderDeviceId = scope.localDeviceId,
                sentAtEpochMs = now,
                text = normalized,
            )
            val document = writeProtocolDocument(envelope)
            try {
                // Building the encrypted protocol document can overlap a LAN transition. Re-read
                // the active service session immediately before the durable Telegram enqueue.
                if (!allowsTelegramUserActionNow()) {
                    TelegramEnqueueResult.PREVIOUSLY_SUPERSEDED
                } else {
                    gateway.enqueuePeerDocument(
                        transferId = envelope.transferId,
                        payloadType = envelope.type.name,
                        plaintext = document,
                    ).also { result ->
                        if (result.isDurablyAccepted()) {
                            val stored = peerChat.recordOutgoing(scope, envelope, now)
                            RemotePeerChatStateBus.publish(stored.state)
                        }
                    }
                }
            } finally {
                document.delete()
            }
        }
    }

    fun requestRemotePeerConnection(): TelegramEnqueueResult {
        if (closed || pausedForMaintenance) return TelegramEnqueueResult.NOT_CONFIGURED
        val session = connectedSession()?.takeIf { it.role == RemoteReviewRole.TEACHER }
            ?: return TelegramEnqueueResult.NOT_CONFIGURED
        return gateway.requestPeerConnection().also { result ->
            if (result.isDurablyAccepted()) execute {
                telegramPeerLinkState = gateway.peerLinkState()
                if (session.pairId == telegramPeerLinkState.pairId) {
                    hybridBookId?.let(::updateHybridLink)
                }
            }
        }
    }

    fun remotePeerLinkState(): TelegramPeerLinkState = gateway.peerLinkState()

    fun markRemotePeerChatRead(): RemotePeerChatState? {
        if (closed || pausedForMaintenance) return null
        return synchronized(operationLock) {
            val session = connectedSession() ?: return@synchronized null
            val scope = chatScope(session) ?: return@synchronized null
            peerChat.markRead(scope, readAtEpochMs = System.currentTimeMillis()).also {
                RemotePeerChatStateBus.publish(it)
            }
        }
    }

    fun pageSyncUiState(): RemotePageSyncUiState = pageSync.uiState()

    fun addPageSyncListener(listener: (RemotePageSyncUiState) -> Unit): AutoCloseable =
        pageSync.addUiListener(listener)

    fun startPendingPageSync(intervalSeconds: Int) {
        if (closed || pausedForMaintenance) return
        synchronized(operationLock) {
            if (closed || pausedForMaintenance) return
            refreshSession()
            refreshPageSyncTransportState()
            pageSync.startPendingSync(intervalSeconds)
        }
    }

    fun pausePendingPageSync() {
        if (closed || pausedForMaintenance) return
        synchronized(operationLock) { pageSync.pausePendingSync() }
    }

    fun workbookMappingCandidates(pageToken: String): List<RemoteWorkbookMappingCandidate> =
        if (closed || pausedForMaintenance) emptyList() else synchronized(operationLock) {
            pageSync.workbookMappingCandidates(pageToken)
        }

    fun bindWorkbookMapping(pageToken: String, localBookId: String): Boolean {
        if (closed || pausedForMaintenance) return false
        return synchronized(operationLock) {
            if (closed || pausedForMaintenance) return@synchronized false
            refreshSession()
            pageSync.bindWorkbookMapping(pageToken, localBookId)
        }
    }

    fun publishedFeedbackState(snapshotTransferId: String): RemotePublishedFeedbackState {
        if (closed || pausedForMaintenance) return RemotePublishedFeedbackState.NONE
        val session = connectedSession()?.takeIf { it.role == RemoteReviewRole.TEACHER }
            ?: return RemotePublishedFeedbackState.NONE
        val source = ledger.incoming(snapshotTransferId) ?: return RemotePublishedFeedbackState.NONE
        if (ownership.owner(source.transferId)?.matches(session) != true) {
            return RemotePublishedFeedbackState.NONE
        }
        val envelope = publishedFeedback.load(source.pageToken)
            ?.takeIf { it.sourceSnapshot.pageToken == source.pageToken }
        if (envelope != null) {
            return RemotePublishedFeedbackState.Available(
                envelope.toReaderFeedback(source, rebindToSource = true),
            )
        }
        return if (teacherRevisions.latestRevision(source.pageToken) > 0L) {
            // Sending an additive fragment here would erase the student's existing full teacher
            // layer. Refuse to edit when its bounded local source has been evicted.
            RemotePublishedFeedbackState.HISTORY_UNAVAILABLE
        } else {
            RemotePublishedFeedbackState.NONE
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun publishTeacherFeedback(feedback: RemoteTeacherFeedback): TelegramEnqueueResult {
        // The exact-attempt page-sync publication is the only supported teacher-data path. A
        // legacy reply cannot be ordered against it and could otherwise overwrite newer work.
        return TelegramEnqueueResult.PREVIOUSLY_SUPERSEDED
    }

    @Suppress("UNUSED_PARAMETER")
    fun publishRemoteGrade(
        snapshotTransferId: String,
        anchorX: Float,
        anchorY: Float,
        color: MarkColor,
    ): TelegramEnqueueResult {
        return TelegramEnqueueResult.PREVIOUSLY_SUPERSEDED
    }

    private fun publishTeacherFeedbackLocked(
        feedback: RemoteTeacherFeedback,
    ): TelegramEnqueueResult {
        if (!allowsTelegramUserActionNow()) {
            return TelegramEnqueueResult.PREVIOUSLY_SUPERSEDED
        }
        val session = connectedSession()?.takeIf { it.role == RemoteReviewRole.TEACHER }
            ?: return TelegramEnqueueResult.NOT_CONFIGURED
        val source = ledger.incoming(feedback.sourceTransferId)
            ?: return TelegramEnqueueResult.NOT_CONFIGURED
        if (ownership.owner(source.transferId)?.matches(session) != true) {
            return TelegramEnqueueResult.CHAT_CHANGED
        }
        require(feedback.pageToken == source.pageToken) { "Feedback page token changed" }
        require(feedback.bookFingerprint == source.pageToken) { "Feedback workbook identity changed" }
        require(feedback.pageNumber == source.pageNumber - 1) { "Feedback page number changed" }
        require(feedback.basedOnStudentRevision == source.studentRevision) {
            "Feedback student revision changed"
        }
        require(feedback.feedbackRevision >= 1L) { "Feedback revision must be positive" }

        val transferId = safeProtocolId("feedback", feedback.feedbackId)
        val protocolStrokes = feedback.strokes.mapIndexed { index, stroke ->
            NormalizedTeacherStroke(
                strokeId = safeProtocolId("stroke", "${feedback.feedbackId}:${stroke.id}:$index"),
                tool = when (stroke.tool) {
                    RemoteFeedbackStrokeTool.PEN -> TeacherInkTool.PEN
                    RemoteFeedbackStrokeTool.HIGHLIGHTER -> TeacherInkTool.HIGHLIGHTER
                },
                argb = stroke.colorArgb,
                widthNormalized = stroke.widthFraction,
                points = stroke.points.map { point ->
                    NormalizedTeacherPoint(point.x, point.y, point.pressure)
                },
            )
        }
        val monotonicRevision = teacherRevisions.reserve(
            pageToken = source.pageToken,
            transferId = transferId,
            requestedRevision = feedback.feedbackRevision,
        )
        val envelope = TeacherFeedbackEnvelope(
            transferId = transferId,
            createdAtEpochMs = feedback.createdAtEpochMillis,
            sourceSnapshot = SnapshotReference(
                transferId = source.transferId,
                pageToken = source.pageToken,
                revision = source.studentRevision,
                dimensions = ReviewCanvasDimensions(source.widthPx, source.heightPx),
            ),
            feedbackRevision = monotonicRevision,
            strokes = protocolStrokes,
        )
        val document = writeProtocolDocument(envelope)
        return try {
            if (!allowsTelegramUserActionNow()) {
                TelegramEnqueueResult.PREVIOUSLY_SUPERSEDED
            } else {
                gateway.enqueuePeerDocument(
                    transferId = envelope.transferId,
                    payloadType = envelope.type.name,
                    plaintext = document,
                ).also { result ->
                    if (result.isDurablyAccepted()) publishedFeedback.store(envelope)
                }
            }
        } finally {
            document.delete()
        }
    }

    private fun execute(block: () -> Unit) {
        if (closed || pausedForMaintenance) return
        val generation = workGeneration.get()
        runCatching { worker.execute { runActiveOperation(generation, block) } }
    }

    private fun runActiveOperation(generation: Long, block: () -> Unit) {
        if (closed || pausedForMaintenance || generation != workGeneration.get()) return
        synchronized(operationLock) {
            if (closed || pausedForMaintenance || generation != workGeneration.get()) return
            runCatching(block)
        }
    }

    fun pauseAndAwait(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0L)
        if (closed) return true
        pausedForMaintenance = true
        val pauseGeneration = workGeneration.incrementAndGet()
        val barrier = runCatching {
            worker.submit {
                synchronized(operationLock) {
                    if (pausedForMaintenance && workGeneration.get() == pauseGeneration) {
                        captureState.reset()
                        observedSession = null
                    }
                }
            }
        }.getOrElse { return false }
        return runCatching {
            barrier.get(timeoutMillis, TimeUnit.MILLISECONDS)
            true
        }.getOrDefault(false)
    }

    fun onDataRootReplaced() {
        check(pausedForMaintenance) { "Remote review must be paused before replacing app data" }
        check(!closed) { "Remote review coordinator is closed" }
        synchronized(operationLock) {
            check(pausedForMaintenance) { "Remote review resumed during data replacement" }
            workGeneration.incrementAndGet()
            captureState.reset()
            pageSync.onDataRootReplaced()
            observedSession = null
            gptPublicationAttemptsAtElapsedMs.clear()
            gateway.cancelPendingPeerDocumentTransfers(
                setOf(
                    RemoteReviewEnvelopeType.PAGE_SNAPSHOT.name,
                    RemoteReviewEnvelopeType.PAGE_SYNC_MANIFEST.name,
                    RemoteReviewEnvelopeType.PAGE_SYNC_REQUEST.name,
                    RemoteReviewEnvelopeType.PAGE_ANNOTATION.name,
                    RemoteReviewEnvelopeType.PAGE_SYNC_ACK.name,
                    RemoteReviewEnvelopeType.GPT_EXPLANATION_LAYER.name,
                    RemoteReviewEnvelopeType.STUDENT_MEMO.name,
                ),
            )
            clearStudentMemoQueues()
            ledger.clearStudentExchangeState()
            stagingDirectory.listFiles().orEmpty().filter(File::isFile).forEach(File::delete)
        }
    }

    fun resume() {
        if (closed || !pausedForMaintenance) return
        synchronized(operationLock) {
            if (closed || !pausedForMaintenance) return
            workGeneration.incrementAndGet()
            pausedForMaintenance = false
        }
        // refreshSession rehydrates the sticky student page and tick retries one durable inbox item.
        execute(::tick)
    }

    private fun handlePresence(presence: StudentStudyPresence) {
        val session = refreshSession()
        if (session?.role != RemoteReviewRole.STUDENT) return
        val target = presence.toCaptureTarget()
        target?.bookId?.let(::updateHybridLink)
        refreshPageSyncTransportState()
        pageSync.onStudentPresence(presence)
        pageSync.tick()
    }

    private fun handleHeartbeat(heartbeat: StudentWorkHeartbeat) {
        val session = refreshSession()
        if (session?.role != RemoteReviewRole.STUDENT) return
        pageSync.onStudentHeartbeat(heartbeat)
        pageSync.tick()
    }

    private fun tick() {
        if (closed) return
        // LAN can be the only configured transport. Durable GPT intents must still be replayed
        // after process restart even when no Telegram peer session exists.
        refreshSession()
        retireLegacyRenderedPageTransport()
        val previousPeerRecent = telegramPeerLinkState.peerRecent
        telegramPeerLinkState = gateway.maintainPeerLink()
        if (!previousPeerRecent && telegramPeerLinkState.peerRecent) {
            pageSync.onPeerAvailable()
        }
        val activeLan = LanSyncBus.activeSessionSnapshot()
        if (activeLan != null) {
            updateHybridLink(activeLan.bookId, activeLan.session)
        } else {
            hybridBookId?.let(::updateHybridLink)
        }
        refreshPageSyncTransportState()
        val pending = gateway.pendingPeerDocuments().filterNot { it.payloadType == CONSTRUCTION_TELEGRAM_PAYLOAD }
        val pendingIds = pending.mapTo(linkedSetOf(), PendingTelegramPeerDocument::updateId)
        retainedUndecodableUpdateIds.retainAll(pendingIds)
        val pendingById = pending.associateBy(PendingTelegramPeerDocument::updateId)
        selectRemoteReviewInboxUpdateIds(
            pendingUpdateIds = pendingIds.toList(),
            retainedUpdateIds = retainedUndecodableUpdateIds,
            limit = MAX_INBOX_DOCUMENTS_PER_TICK,
        ).forEach { updateId ->
            pendingById[updateId]?.let(::processIncoming)
        }
        pageSync.tick()
        ensurePeriodicStudentMemoRecovery()
        drainOneStudentMemo()
        retryPendingGptExplanationPublications()
    }

    private fun updateHybridLink(bookId: String) {
        val activeLan = LanSyncBus.activeSessionSnapshot()
        if (activeLan != null) {
            // Keep one grace history for the service's real socket. A Reader event from another
            // workbook must not reset that clock and shorten an established LAN loss episode.
            updateHybridLink(activeLan.bookId, activeLan.session)
        } else {
            updateHybridLink(bookId, LanSyncBus.sessionSnapshot(bookId))
        }
    }

    private fun updateHybridLink(bookId: String, lanSnapshot: LanSessionSnapshot) {
        evaluateHybridLink(bookId, lanSnapshot)
        refreshPageSyncTransportState()
    }

    private fun evaluateHybridLink(bookId: String, lanSnapshot: LanSessionSnapshot) {
        if (bookId.isBlank()) return
        if (hybridBookId != bookId) {
            hybridBookId = bookId
            hybridStateMachine = HybridLinkStateMachine()
            hybridDecision = null
        }
        val phase = lanSnapshot.phase
        val connection = lanSnapshot.connectionState
        val peerConnected = gateway.remoteReviewPeerStatus() is RemoteReviewPeerStatus.Connected
        val decision = hybridStateMachine.update(
            HybridLinkSignals(
                lanSocketConnected = connection == LanConnectionState.CONNECTED && phase in setOf(
                    LanSessionPhase.SOCKET_CONNECTED,
                    LanSessionPhase.HANDSHAKE_COMPLETE,
                    LanSessionPhase.PAGE_CATCHING_UP,
                    LanSessionPhase.READY,
                ),
                lanHandshakeComplete = phase in setOf(
                    LanSessionPhase.HANDSHAKE_COMPLETE,
                    LanSessionPhase.PAGE_CATCHING_UP,
                    LanSessionPhase.READY,
                ),
                lanPageCatchUpComplete = phase == LanSessionPhase.READY,
                telegramConfigured = peerConnected,
                telegramApiHealthy = telegramStatus is RemoteMonitorStatus.Connected,
                // Pairing proves identity; only a recent authenticated reply proves the remote
                // device is currently able to receive fallback traffic.
                telegramPeerRecent = peerConnected && telegramPeerLinkState.peerRecent,
                nowElapsedMs = SystemClock.elapsedRealtime(),
                // Grace is reserved for an authenticated socket that is temporarily catching up.
                // IDLE, CONNECTING, and DISCONNECTED all hand off to Telegram immediately.
                lanDefinitelyDisconnected = isLanTransportDefinitelyDisconnected(connection),
            ),
        )
        hybridDecision = decision
    }

    private fun refreshPageSyncTransportState() {
        val activeLan = LanSyncBus.activeSessionSnapshot()
        // The LAN service owns one process-wide session. Always arbitrate against that exact book,
        // even when a Reader for another workbook happens to be the last UI source that emitted a
        // presence update. Re-evaluating here also lets the four-second loss grace expire on the
        // coordinator's one-second tick without requiring another socket callback.
        activeLan?.let { evaluateHybridLink(it.bookId, it.session) }
        refreshLanCatchUpYield(activeLan)
        val route = globalPageSyncTransportRoute(activeLan?.session)
        val previousRoute = pageSyncTransportRoute
        pageSyncTransportRoute = route
        val fallbackDecision = hybridDecision
        val statusBookId = when (route) {
            GlobalPageSyncTransportRoute.TELEGRAM -> hybridBookId
            GlobalPageSyncTransportRoute.LAN_GRACE,
            GlobalPageSyncTransportRoute.LAN_OWNS,
            -> activeLan?.bookId
        }
        if (fallbackDecision != null && !statusBookId.isNullOrBlank()) {
            HybridLinkStatusBus.publish(
                HybridLinkStatus(
                    bookId = statusBookId,
                    decision = globalHybridDisplayDecision(route, fallbackDecision),
                    updatedAtElapsedMs = SystemClock.elapsedRealtime(),
                ),
            )
        }
        pageSync.setTransportState(
            active = route == GlobalPageSyncTransportRoute.TELEGRAM,
            online = telegramStatus is RemoteMonitorStatus.Connected && telegramPeerLinkState.peerRecent,
            lanOwnsData = route == GlobalPageSyncTransportRoute.LAN_OWNS,
        )
        if (route == GlobalPageSyncTransportRoute.TELEGRAM && previousRoute != route &&
            observedSession?.role == RemoteReviewRole.STUDENT
        ) scheduleStudentMemoRecovery()
        if (route == GlobalPageSyncTransportRoute.LAN_OWNS && previousRoute != route) {
            // Rotate durably before cancellation. A crash between these calls can leave an old
            // ciphertext, but it can never acknowledge the new current transport attempt.
            rotatePendingGptTelegramTransfers()
            suspendTelegramPageCaptureWhileLanIsReady()
        }
        if (shouldClearGptPublicationRetryGate(previousRoute, route)) {
            // LAN_GRACE may have stamped the in-memory retry clock without ever delivering. Once
            // Telegram becomes the owner it must get an immediate attempt, not wait up to 30 s.
            gptPublicationAttemptsAtElapsedMs.clear()
        }
    }

    /**
     * Give every connected socket one bounded chance to finish catch-up. At expiry, yield only when
     * Telegram is genuinely ready; otherwise the LAN watchdog remains responsible for recovery.
     * readLoop's subsequent DISCONNECTED publication is still the ownership barrier before send.
     */
    private fun refreshLanCatchUpYield(activeLan: com.studyink.sync.lan.LanActiveSessionSnapshot?) {
        val unreadyConnected = activeLan?.takeIf { active ->
            active.session.connectionState == LanConnectionState.CONNECTED &&
                active.session.phase != LanSessionPhase.READY
        }
        if (unreadyConnected == null) {
            resetLanCatchUpLease()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (lanCatchUpBookId != unreadyConnected.bookId || lanCatchUpStartedAtElapsedMs == null) {
            lanCatchUpBookId = unreadyConnected.bookId
            lanCatchUpStartedAtElapsedMs = now
            lanCatchUpYieldRequested = false
        }
        if (shouldRequestLanCatchUpYield(
                activeLanSession = unreadyConnected.session,
                decision = hybridDecision,
                startedAtElapsedMs = requireNotNull(lanCatchUpStartedAtElapsedMs),
                nowElapsedMs = now,
                alreadyRequested = lanCatchUpYieldRequested,
            )
        ) {
            lanCatchUpYieldRequested = true
            LanSyncBus.requestCatchUpYield(unreadyConnected.bookId)
        }
    }

    private fun resetLanCatchUpLease(bookId: String? = null) {
        if (bookId != null && lanCatchUpBookId != bookId) return
        lanCatchUpBookId = null
        lanCatchUpStartedAtElapsedMs = null
        lanCatchUpYieldRequested = false
    }

    private fun allowsTelegramUserActionNow(): Boolean {
        val activeLanSession = LanSyncBus.activeSessionSnapshot()
        activeLanSession?.let { updateHybridLink(it.bookId, it.session) }
        return globalPageSyncTransportRoute(
            activeLanSession?.session,
        ) == GlobalPageSyncTransportRoute.TELEGRAM
    }

    private fun suspendTelegramPageCaptureWhileLanIsReady() {
        gateway.cancelPendingPeerDocumentTransfers(
            setOf(
                RemoteReviewEnvelopeType.PAGE_SNAPSHOT.name,
                RemoteReviewEnvelopeType.PAGE_SYNC_MANIFEST.name,
                RemoteReviewEnvelopeType.PAGE_SYNC_REQUEST.name,
                RemoteReviewEnvelopeType.PAGE_ANNOTATION.name,
                RemoteReviewEnvelopeType.GPT_EXPLANATION_LAYER.name,
                RemoteReviewEnvelopeType.STUDENT_MEMO.name,
            ),
        )
        clearStudentMemoQueues()
        pageSync.setTransportState(
            active = false,
            online = telegramStatus is RemoteMonitorStatus.Connected && telegramPeerLinkState.peerRecent,
            lanOwnsData = true,
        )
        if (connectedSession()?.role != RemoteReviewRole.STUDENT) return
        // Cancellation makes prior transfer ids terminal in the transport journal. Rehydrate only
        // the sticky current page so a later fallback can immediately force one fresh full image.
        captureState.reset()
        val target = StudentStudyPresenceBus.current()?.toCaptureTarget()
        val revision = target?.let {
            runCatching { annotationStore.loadPage(it.bookId, it.pageNumber).revision }.getOrNull()
        }
        captureState.onPresence(target, revision, SystemClock.elapsedRealtime())
    }

    private fun refreshSession(): ConnectedRemoteReviewSession? {
        val current = connectedSession()
        if (current == observedSession) return current
        if (observedSession != null) {
            rotatePendingGptTelegramTransfers()
            gateway.cancelPendingPeerDocumentTransfers(
                setOf(
                    RemoteReviewEnvelopeType.GPT_EXPLANATION_LAYER.name,
                    RemoteReviewEnvelopeType.STUDENT_MEMO.name,
                ),
            )
        }
        pageSync.bindSession(current?.let { RemotePageSyncSession(it.role, it.pairId) })
        observedSession = current
        telegramPeerLinkState = gateway.peerLinkState()
        captureState.reset()
        retainedUndecodableUpdateIds.clear()
        clearStudentMemoQueues()
        current?.let { session ->
            chatScope(session)?.let { scope -> RemotePeerChatStateBus.publish(peerChat.state(scope)) }
        }
        if (current?.role == RemoteReviewRole.STUDENT) {
            StudentStudyPresenceBus.current()?.let(pageSync::onStudentPresence)
            scheduleStudentMemoRecovery()
        }
        refreshPageSyncTransportState()
        return current
    }

    private fun restoreOutstandingPageTransfers(nowElapsedMs: Long) {
        val pendingIds = gateway.pendingPeerDocumentTransfers(
            setOf(RemoteReviewEnvelopeType.PAGE_SNAPSHOT.name),
        ).mapTo(hashSetOf()) { it.transferId }
        if (pendingIds.isEmpty()) return
        ledger.outgoingSnapshots().asSequence()
            .filter { it.transferId in pendingIds }
            .groupBy { source ->
                RemoteReviewCaptureTarget(source.bookId, source.pageNumber, source.attemptNo)
            }
            .values
            .mapNotNull { candidates -> candidates.maxByOrNull(OutgoingRemoteSnapshot::createdAtEpochMs) }
            .forEach { source ->
                captureState.restoreOutstanding(
                    target = RemoteReviewCaptureTarget(
                        source.bookId,
                        source.pageNumber,
                        source.attemptNo,
                    ),
                    transferId = source.transferId,
                    revision = source.studentRevision,
                    nowElapsedMs = nowElapsedMs,
                )
            }
    }

    private fun drainOneDueCapture(session: ConnectedRemoteReviewSession) {
        if (connectedSession() != session) return
        if (pageSyncTransportRoute != GlobalPageSyncTransportRoute.TELEGRAM ||
            hybridDecision?.telegramActive != true
        ) return
        val deadTransfers = gateway.deadLetters().mapNotNull { it.entry.peerTransferId }.toSet()
        val ticket = captureState.nextDue(
            nowElapsedMs = SystemClock.elapsedRealtime(),
            outboundState = { transferId ->
                when {
                    gateway.peerDeliveryReceipt(transferId)?.acknowledgedAtEpochMs != null ->
                        RemoteReviewOutboundState.SENT
                    transferId in deadTransfers -> RemoteReviewOutboundState.FAILED
                    else -> RemoteReviewOutboundState.PENDING
                }
            },
        ) ?: return
        captureAndEnqueue(ticket, session)
    }

    private fun captureAndEnqueue(
        ticket: RemoteReviewCaptureTicket,
        session: ConnectedRemoteReviewSession,
    ) {
        var rendered: RenderedPage? = null
        try {
            if (connectedSession() != session) {
                captureState.completeFailure(ticket, SystemClock.elapsedRealtime())
                return
            }
            rendered = renderBounded(ticket.target)
            val page = rendered
            val shouldSend = captureState.shouldTransmit(ticket, page.annotationRevision, page.sha256)
            if (!shouldSend) {
                captureState.completeUnchanged(ticket, page.annotationRevision, SystemClock.elapsedRealtime())
                return
            }
            val studentInkDigest = ticket.target.attemptNo?.let { attemptNo ->
                val exactSnapshot = annotationStore.loadPage(ticket.target.bookId, ticket.target.pageNumber)
                require(exactSnapshot.revision == page.annotationRevision) {
                    "Student ink changed while the remote page was rendered"
                }
                studentInkDigest(exactSnapshot, attemptNo)
            }
            val imageBytes = readBoundedImage(page.file)
            // Rendering can take long enough for a fully caught-up LAN session to return. Re-read
            // sticky lower-layer state while the coordinator lock is still held and abort before
            // creating any new Telegram outbox entry if live has reclaimed the page.
            updateHybridLink(ticket.target.bookId)
            if (pageSyncTransportRoute != GlobalPageSyncTransportRoute.TELEGRAM ||
                hybridDecision?.telegramActive != true
            ) return
            val pairScopedPageToken = tokenFactory.pageToken(session.pairId, ticket.target)
            val randomSuffix = UUID.randomUUID().toString().replace("-", "")
            // Keep the stable ink fingerprint inside the encrypted document. Telegram captions
            // carry transfer ids in plaintext, so they must remain random and non-descriptive.
            val transferId = "snapshot_$randomSuffix"
            val envelope = PageSnapshotEnvelope(
                transferId = transferId,
                createdAtEpochMs = page.renderedAtEpochMillis,
                pageToken = pairScopedPageToken,
                workbookLabel = boundedDisplayLabel(
                    page.bookTitle,
                    RemoteReviewLimits.MAX_WORKBOOK_LABEL_UTF8_BYTES,
                    "문제집",
                ),
                pageNumber = ticket.target.pageNumber + 1,
                attemptNo = ticket.target.attemptNo,
                studentLabel = boundedDisplayLabel(
                    page.studentDisplayName,
                    RemoteReviewLimits.MAX_STUDENT_LABEL_UTF8_BYTES,
                    "학생",
                ),
                revision = page.annotationRevision,
                dimensions = ReviewCanvasDimensions(page.widthPixels, page.heightPixels),
                imageFormat = SnapshotImageFormat.JPEG,
                renderedPageBytes = imageBytes,
                studentInkDigest = studentInkDigest,
            )
            val document = writeProtocolDocument(envelope)
            try {
                // Mapping and peer ownership must reach disk before Telegram can deliver feedback.
                ledger.recordOutgoing(
                    OutgoingRemoteSnapshot(
                        transferId = transferId,
                        pageToken = pairScopedPageToken,
                        bookId = ticket.target.bookId,
                        pageNumber = ticket.target.pageNumber,
                        attemptNo = ticket.target.attemptNo,
                        studentRevision = page.annotationRevision,
                        widthPx = page.widthPixels,
                        heightPx = page.heightPixels,
                        createdAtEpochMs = page.renderedAtEpochMillis,
                        studentInkDigest = studentInkDigest,
                    ),
                )
                ownership.record(transferId, session)
                val result = gateway.enqueuePeerDocument(
                    transferId = transferId,
                    payloadType = envelope.type.name,
                    plaintext = document,
                )
                if (result.isDurablyAccepted()) {
                    captureState.completeSent(
                        ticket = ticket,
                        observedRevision = page.annotationRevision,
                        imageSha256 = page.sha256,
                        transferId = transferId,
                        nowElapsedMs = SystemClock.elapsedRealtime(),
                    )
                } else {
                    captureState.completeFailure(ticket, SystemClock.elapsedRealtime())
                }
            } finally {
                document.delete()
            }
        } catch (_: Throwable) {
            captureState.completeFailure(ticket, SystemClock.elapsedRealtime())
        } finally {
            rendered?.file?.delete()
        }
    }

    private fun renderBounded(target: RemoteReviewCaptureTarget): RenderedPage {
        val request = PageRenderRequest.currentPage(
            bookId = target.bookId,
            pageNumber = target.pageNumber,
            attemptNo = target.attemptNo,
        )
        val primary = primaryRenderer.render(request, stagingDirectory)
        if (primary.byteCount <= RemoteReviewLimits.MAX_SNAPSHOT_IMAGE_BYTES) return primary
        primary.file.delete()
        return fallbackRenderer.render(request, stagingDirectory).also { fallback ->
            require(fallback.byteCount <= RemoteReviewLimits.MAX_SNAPSHOT_IMAGE_BYTES) {
                "Rendered remote-review page exceeds the two MiB document budget"
            }
        }
    }

    private fun readBoundedImage(file: File): ByteArray {
        require(file.isFile && file.length() in 1..RemoteReviewLimits.MAX_SNAPSHOT_IMAGE_BYTES.toLong())
        return file.readBytes().also { bytes ->
            require(bytes.size <= RemoteReviewLimits.MAX_SNAPSHOT_IMAGE_BYTES)
        }
    }

    private fun writeProtocolDocument(envelope: com.studyink.monitor.core.RemoteReviewEnvelope): File {
        val encoded = RemoteReviewDocumentCodec.encode(envelope)
        require(encoded.sizeBytes <= RemoteReviewLimits.OPERATIONAL_DOCUMENT_BYTES)
        val file = File.createTempFile("remote-review-", ".${RemoteReviewDocumentCodec.FILE_EXTENSION}", stagingDirectory)
        try {
            FileOutputStream(file).use { output ->
                output.write(encoded.copyBytes())
                output.flush()
                output.fd.sync()
            }
            return file
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    private fun publishGptExplanationLayer(layer: StudentExplanationLayer) {
        if (closed || pausedForMaintenance || layer.revision <= 0L) return
        val pending = assistantRepository.pendingStudentExplanationPublications().firstOrNull {
            it.target == layer.target && it.revision == layer.revision &&
                it.digestSha256 == layer.digestSha256
        } ?: return
        publishPendingGptExplanation(pending, force = true)
    }

    private fun retryPendingGptExplanationPublications() {
        val pending = assistantRepository.pendingStudentExplanationPublications()
        gptPublicationAttemptsAtElapsedMs.keys.retainAll(
            pending.mapTo(hashSetOf(), PendingStudentExplanationPublication::publicationId),
        )
        val nowElapsed = SystemClock.elapsedRealtime()
        pending.firstOrNull { publication ->
            val lastAttempt = gptPublicationAttemptsAtElapsedMs[publication.publicationId]
            lastAttempt == null || nowElapsed - lastAttempt >= GPT_PUBLICATION_RETRY_MS
        }?.let { publishPendingGptExplanation(it, force = false) }
    }

    private fun publishPendingGptExplanation(
        pending: PendingStudentExplanationPublication,
        force: Boolean,
    ) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val lastAttempt = gptPublicationAttemptsAtElapsedMs[pending.publicationId]
        if (!force && lastAttempt != null && nowElapsed - lastAttempt < GPT_PUBLICATION_RETRY_MS) return
        gptPublicationAttemptsAtElapsedMs[pending.publicationId] = nowElapsed

        val target = pending.target
        val localBookId = target.page.bookId
        val pageNumber = target.page.pageNumber
        val attemptNo = target.attemptNo
        val exactAttempt = runCatching {
            library.attempts(localBookId, pageNumber).singleOrNull {
                it.bookId == localBookId && it.pageNumber == pageNumber && it.attemptNo == attemptNo
            }
        }.getOrNull() ?: return
        if (exactAttempt.attemptNo != attemptNo) return
        val authorityEpoch = runCatching { assistantRepository.teacherAuthorityEpoch() }
            .getOrNull() ?: return
        val frozen = runCatching {
            assistantRepository.layerForPendingPublication(pending.publicationId, authorityEpoch)
        }.getOrNull() ?: return

        LanSyncBus.withActiveSessionLease { activeLan ->
            when (globalPageSyncTransportRoute(activeLan?.session)) {
                GlobalPageSyncTransportRoute.LAN_GRACE,
                GlobalPageSyncTransportRoute.LAN_OWNS,
                -> {
                    // The LAN service re-reads this exact durable intent and resolves it only on
                    // an application ACK from the authenticated student.
                    LanSyncBus.gptExplanationLayerPublished(frozen)
                }
                GlobalPageSyncTransportRoute.TELEGRAM -> {
                    publishPendingGptExplanationOverTelegram(pending, frozen, nowElapsed)
                }
            }
        }
    }

    private fun publishPendingGptExplanationOverTelegram(
        pending: PendingStudentExplanationPublication,
        frozen: StudentExplanationLayer,
        nowElapsed: Long,
    ) {
        val session = refreshSession()?.takeIf { it.role == RemoteReviewRole.TEACHER } ?: return
        val localBookId = pending.target.page.bookId
        val pageNumber = pending.target.page.pageNumber
        val page = pageSyncStore.teacherPageForLocalTarget(localBookId, pageNumber) ?: return
        val localHash = runCatching { library.book(localBookId).contentSha256.lowercase() }.getOrNull()
            ?: return
        // Local existence is not remote provenance. The current manifest must authorize this
        // exact open or submitted attempt before any text can be attached remotely.
        if (!page.authorizesGptExplanation(localBookId, pageNumber, pending.target.attemptNo, localHash)) return

        val transferId = gptTelegramTransferId(pending, session.pairId, page.pageToken)
        val receipt = gateway.peerDeliveryReceipt(transferId)
        if (receipt?.acknowledgedAtEpochMs != null) {
            assistantRepository.resolvePendingStudentExplanationPublication(
                pending.publicationId,
                pending.target,
                pending.revision,
                pending.digestSha256,
            )
            gptPublicationAttemptsAtElapsedMs.remove(pending.publicationId)
            return
        }
        if (receipt != null || gateway.pendingPeerDocumentTransfers(
                setOf(RemoteReviewEnvelopeType.GPT_EXPLANATION_LAYER.name),
            ).any { it.transferId == transferId }
        ) return
        if (gateway.deadLetters().any { it.entry.peerTransferId == transferId }) {
            assistantRepository.advancePendingStudentExplanationDeliveryAttempt(pending.publicationId)
            gptPublicationAttemptsAtElapsedMs.remove(pending.publicationId)
            return
        }

        val envelope = runCatching {
            frozen.toRemoteEnvelope(
                pageToken = page.pageToken,
                transferId = transferId,
                createdAtEpochMs = pending.queuedAtEpochMillis,
                authorityEpoch = frozen.authorityEpoch,
            )
        }.getOrNull() ?: return
        gptPublicationAttemptsAtElapsedMs[pending.publicationId] = nowElapsed
        val result = enqueuePageSyncEnvelope(envelope)
        if (result == TelegramEnqueueResult.PREVIOUSLY_SUPERSEDED) {
            assistantRepository.advancePendingStudentExplanationDeliveryAttempt(pending.publicationId)
            gptPublicationAttemptsAtElapsedMs.remove(pending.publicationId)
        }
    }

    private fun rotatePendingGptTelegramTransfers() {
        runCatching { assistantRepository.advanceAllPendingStudentExplanationDeliveryAttempts() }
        gptPublicationAttemptsAtElapsedMs.clear()
    }

    private fun receiveGptExplanationLayer(
        envelope: GptExplanationLayerEnvelope,
    ): RemotePageSyncIncomingResult {
        val session = connectedSession()?.takeIf { it.role == RemoteReviewRole.STUDENT }
            ?: return RemotePageSyncIncomingResult.RETAIN
        if (session != observedSession) return RemotePageSyncIncomingResult.RETAIN
        if (envelope.authorityEpoch == REMOTE_LEGACY_EXPLANATION_AUTHORITY) {
            return RemotePageSyncIncomingResult.RETAIN
        }
        val page = pageSyncStore.studentPage(envelope.pageToken)
            ?: return RemotePageSyncIncomingResult.RETAIN
        if (page.pageNumber + 1 != envelope.pageNumber) return RemotePageSyncIncomingResult.RETAIN
        val localBook = runCatching { library.book(page.bookId) }.getOrNull()
            ?: return RemotePageSyncIncomingResult.RETAIN
        if (localBook.contentSha256 != page.contentSha256 ||
            envelope.pageNumber !in 1..localBook.pageCount
        ) return RemotePageSyncIncomingResult.RETAIN
        val exactAttemptExists = runCatching {
            library.attempts(page.bookId, page.pageNumber).any {
                it.bookId == page.bookId && it.pageNumber == page.pageNumber &&
                    it.attemptNo == envelope.attemptNo
            }
        }.getOrDefault(false)
        if (!exactAttemptExists) return RemotePageSyncIncomingResult.RETAIN

        val incoming = runCatching { envelope.toLocalLayer(page.bookId) }.getOrNull()
            ?: return RemotePageSyncIncomingResult.RETAIN
        val result = runCatching {
            assistantRepository.applyStudentExplanationLayer(incoming.target, incoming)
        }.getOrElse { return RemotePageSyncIncomingResult.RETAIN }
        return when (result.status) {
            StudentLayerApplyStatus.APPLIED -> {
                StudentExplanationLayerBus.remoteLayerApplied(result.current)
                RemotePageSyncIncomingResult.ACKNOWLEDGE
            }
            StudentLayerApplyStatus.ALREADY_CURRENT,
            StudentLayerApplyStatus.STALE,
            -> RemotePageSyncIncomingResult.ACKNOWLEDGE
            // Same-authority/same-revision different content is corruption, not success. Keep the
            // application document unacknowledged so the durable sender intent is never erased.
            StudentLayerApplyStatus.CONFLICT -> RemotePageSyncIncomingResult.RETAIN
        }
    }

    private fun onStudentMemoChanged(change: StudentMemoChange) {
        val session = refreshSession()?.takeIf { it.role == RemoteReviewRole.STUDENT } ?: return
        if (session != observedSession) return
        pageSync.onLocalOperation(change.target.bookId, change.target.pageNumber)
        val key = StudentMemoKey(change.target, change.memo.id)
        val now = SystemClock.elapsedRealtime()
        pendingMemoChanges[key] = pendingMemoChanges[key]?.copy(
            lastChangedAtElapsedMs = now,
            retryNotBeforeElapsedMs = 0L,
            retryFailures = 0,
        ) ?: PendingStudentMemoChange(now, now)
    }

    private fun scheduleStudentMemoRecovery() {
        lastMemoRecoveryEpochDay = studentMemoDeliveryEpochDay(System.currentTimeMillis())
        memoRecoveryTargets.clear()
        memoRecoveryMemos.clear()
        val targets = runCatching { memoRepository.targets() }.getOrDefault(emptyList())
        val current = StudentStudyPresenceBus.current()?.takeIf(StudentStudyPresence::active)
        targets.sortedWith(
            compareBy<MemoTarget> {
                when {
                    it.bookId == current?.bookId && it.pageNumber + 1 == current.pageNumber &&
                        it.attemptNo == current.attemptNo -> 0
                    it.bookId == current?.bookId && it.pageNumber + 1 == current.pageNumber -> 1
                    else -> 2
                }
            }.thenByDescending(MemoTarget::pageNumber).thenByDescending(MemoTarget::attemptNo),
        ).forEach(memoRecoveryTargets::addLast)
    }

    private fun ensurePeriodicStudentMemoRecovery() {
        if (observedSession?.role != RemoteReviewRole.STUDENT ||
            pageSyncTransportRoute != GlobalPageSyncTransportRoute.TELEGRAM
        ) return
        val currentDay = studentMemoDeliveryEpochDay(System.currentTimeMillis())
        if (currentDay != lastMemoRecoveryEpochDay) scheduleStudentMemoRecovery()
    }

    private fun clearStudentMemoQueues() {
        pendingMemoChanges.clear()
        memoRecoveryTargets.clear()
        memoRecoveryMemos.clear()
        lastMemoRecoveryEpochDay = Long.MIN_VALUE
    }

    private fun deferStudentMemoRetry(
        key: StudentMemoKey,
        current: PendingStudentMemoChange?,
        nowElapsedMs: Long,
    ) {
        val failures = (current?.retryFailures ?: 0) + 1
        val base = current ?: PendingStudentMemoChange(nowElapsedMs, nowElapsedMs)
        val deferred = base.copy(
            retryNotBeforeElapsedMs = safeStudentMemoAdd(
                nowElapsedMs,
                studentMemoRetryDelayMs(failures),
            ),
            retryFailures = failures,
        )
        // LinkedHashMap assignment does not change order. Remove first so one unavailable memo can
        // never monopolize the first queue position.
        pendingMemoChanges.remove(key)
        pendingMemoChanges[key] = deferred
    }

    /** Sends at most one memo document, while loading at most one durable page-attempt per tick. */
    private fun drainOneStudentMemo() {
        val session = observedSession?.takeIf { it.role == RemoteReviewRole.STUDENT } ?: return
        if (pageSyncTransportRoute != GlobalPageSyncTransportRoute.TELEGRAM) return

        val nowElapsed = SystemClock.elapsedRealtime()
        pendingMemoChanges.entries.firstOrNull { (_, state) -> state.isDue(nowElapsed) }?.let { (key, state) ->
            val result = runCatching { sendStudentMemo(session, key) }
                .getOrDefault(StudentMemoSendResult.RETRY)
            if (result == StudentMemoSendResult.RETRY) {
                deferStudentMemoRetry(key, state, nowElapsed)
            } else {
                pendingMemoChanges.remove(key)
            }
            return
        }

        if (memoRecoveryMemos.isEmpty()) {
            val target = memoRecoveryTargets.pollFirst() ?: return
            runCatching { memoRepository.snapshot(target) }.getOrNull()?.memos
                .orEmpty()
                .sortedWith(compareByDescending<StudentMemo>(StudentMemo::updatedAtEpochMillis).thenBy(StudentMemo::id))
                .forEach { memo -> memoRecoveryMemos.addLast(StudentMemoKey(target, memo.id)) }
            if (memoRecoveryMemos.isEmpty()) return
        }
        var key = memoRecoveryMemos.pollFirst() ?: return
        while (key in pendingMemoChanges) {
            key = memoRecoveryMemos.pollFirst() ?: return
        }
        if (runCatching { sendStudentMemo(session, key) }.getOrDefault(StudentMemoSendResult.RETRY) ==
            StudentMemoSendResult.RETRY
        ) deferStudentMemoRetry(key, null, nowElapsed)
    }

    private fun sendStudentMemo(
        session: ConnectedRemoteReviewSession,
        key: StudentMemoKey,
    ): StudentMemoSendResult {
        if (connectedSession() != session || session.role != RemoteReviewRole.STUDENT) {
            return StudentMemoSendResult.RETRY
        }
        val book = runCatching { library.book(key.target.bookId) }.getOrNull()
            ?: return StudentMemoSendResult.SKIP
        if (key.target.pageNumber !in 0 until book.pageCount || book.contentSha256.length != 64) {
            return StudentMemoSendResult.SKIP
        }
        val exactAttemptExists = runCatching {
            library.attempts(key.target.bookId, key.target.pageNumber).any {
                it.bookId == key.target.bookId && it.pageNumber == key.target.pageNumber &&
                    it.attemptNo == key.target.attemptNo
            }
        }.getOrDefault(false)
        if (!exactAttemptExists) return StudentMemoSendResult.SKIP

        pageSync.onLocalOperation(key.target.bookId, key.target.pageNumber)
        val generation = pageSyncStore.studentGeneration()
        if (generation <= 0L) return StudentMemoSendResult.RETRY
        val pageToken = tokenFactory.pageSyncPageToken(
            session.pairId,
            key.target.bookId,
            key.target.pageNumber,
            generation,
        )
        val page = pageSyncStore.studentPage(pageToken) ?: return StudentMemoSendResult.RETRY
        val expectedWorkbookToken = tokenFactory.pageSyncWorkbookToken(session.pairId, key.target.bookId)
        if (!page.authorizesStudentMemo(
                key.target,
                generation,
                pageToken,
                expectedWorkbookToken,
                book.contentSha256.lowercase(),
            )
        ) return StudentMemoSendResult.RETRY

        val payload = runCatching { memoRepository.exportMemo(key.target, key.memoId) }.getOrNull()
            ?: return StudentMemoSendResult.RETRY
        if (payload.isEmpty() || payload.size > RemoteReviewLimits.MAX_STUDENT_MEMO_BYTES) {
            return StudentMemoSendResult.SKIP
        }
        // Export and metadata must describe the same immutable revision. A pen commit may race this
        // worker between two repository reads, so derive the envelope only from the exported bytes.
        val memo = runCatching { memoRepository.decodeMemo(payload) }.getOrNull()
            ?.takeIf { it.target == key.target && it.id == key.memoId }
            ?: return StudentMemoSendResult.RETRY
        val attemptTransferIds = (0 until STUDENT_MEMO_DELIVERY_ATTEMPTS).associateWith { attempt ->
            studentMemoTelegramTransferId(
                pairId = session.pairId,
                syncGeneration = generation,
                pageToken = pageToken,
                memoId = memo.id,
                memoRevision = memo.revision,
                memoDigestSha256 = memo.digestSha256,
                deliveryAttempt = attempt,
            )
        }
        val receipts = attemptTransferIds.mapValues { (_, transferId) ->
            gateway.peerDeliveryReceipt(transferId)
        }
        if (receipts.values.any { it?.acknowledgedAtEpochMs != null }) {
            return StudentMemoSendResult.ACKNOWLEDGED
        }
        val pendingTransferIds = gateway.pendingPeerDocumentTransfers(
            setOf(RemoteReviewEnvelopeType.STUDENT_MEMO.name),
        ).mapTo(linkedSetOf()) { it.transferId }
        if (attemptTransferIds.values.any { it in pendingTransferIds }) {
            return StudentMemoSendResult.DEFERRED
        }
        val nowEpochMs = System.currentTimeMillis()
        if (receipts.values.any { receipt ->
                receipt != null && !isUnacknowledgedPeerReceiptExpired(
                    receipt.serverAcceptedAtEpochMs ?: receipt.sentAtEpochMs,
                    nowEpochMs,
                )
            }
        ) return StudentMemoSendResult.DEFERRED
        val deliveryAttempt = studentMemoDeliveryAttemptSlot(nowEpochMs)
        val transferId = requireNotNull(attemptTransferIds[deliveryAttempt])
        if (receipts[deliveryAttempt] != null ||
            gateway.deadLetters().any { it.entry.peerTransferId == transferId }
        ) return StudentMemoSendResult.DEFERRED

        val envelope = runCatching {
            StudentMemoEnvelope(
                transferId = transferId,
                createdAtEpochMs = memo.updatedAtEpochMillis,
                syncGeneration = generation,
                pageToken = pageToken,
                workbookToken = page.workbookToken,
                contentSha256 = page.contentSha256,
                pageNumber = page.pageNumber + 1,
                attemptNo = memo.target.attemptNo,
                memoId = memo.id,
                memoRevision = memo.revision,
                memoDigestSha256 = memo.digestSha256,
                payloadSha256 = com.studyink.monitor.core.studentMemoPayloadSha256Hex(payload),
                payloadBytes = payload,
            )
        }.getOrNull() ?: return StudentMemoSendResult.SKIP
        return when (enqueuePageSyncEnvelope(envelope)) {
            TelegramEnqueueResult.ENQUEUED,
            TelegramEnqueueResult.ALREADY_PENDING,
            TelegramEnqueueResult.ALREADY_DELIVERED,
            TelegramEnqueueResult.PREVIOUSLY_DEAD,
            TelegramEnqueueResult.PREVIOUSLY_SUPERSEDED,
            -> StudentMemoSendResult.DEFERRED
            TelegramEnqueueResult.NOT_CONFIGURED,
            TelegramEnqueueResult.CHAT_CHANGED,
            TelegramEnqueueResult.QUEUE_FULL,
            -> StudentMemoSendResult.RETRY
        }
    }

    private fun receiveStudentMemo(envelope: StudentMemoEnvelope): RemotePageSyncIncomingResult {
        val session = connectedSession()?.takeIf { it.role == RemoteReviewRole.TEACHER }
            ?: return RemotePageSyncIncomingResult.RETAIN
        if (session != observedSession) return RemotePageSyncIncomingResult.RETAIN
        val manifestGeneration = pageSyncStore.teacherManifestGeneration()
        if (manifestGeneration <= 0L || envelope.syncGeneration > manifestGeneration) {
            return RemotePageSyncIncomingResult.RETAIN
        }
        if (envelope.syncGeneration < manifestGeneration) return RemotePageSyncIncomingResult.DROP
        val page = pageSyncStore.teacherPage(envelope.pageToken)
            ?: return RemotePageSyncIncomingResult.RETAIN
        val localBookId = page.localBookId ?: return RemotePageSyncIncomingResult.RETAIN
        if (page.syncGeneration != manifestGeneration || envelope.pageToken != page.pageToken ||
            envelope.workbookToken != page.workbookToken || envelope.contentSha256 != page.contentSha256 ||
            envelope.pageNumber != page.pageNumber + 1
        ) return RemotePageSyncIncomingResult.DROP
        if (envelope.attemptNo !in page.attemptNos) return RemotePageSyncIncomingResult.RETAIN
        val localBook = runCatching { library.book(localBookId) }.getOrNull()
            ?: return RemotePageSyncIncomingResult.RETAIN
        val localTarget = page.studentMemoLocalTarget(
            envelope,
            manifestGeneration,
            localBook.contentSha256.lowercase(),
        ) ?: return RemotePageSyncIncomingResult.RETAIN
        val decoded = runCatching { memoRepository.decodeMemo(envelope.copyPayloadBytes()) }
            .getOrElse { return RemotePageSyncIncomingResult.DROP }
        if (decoded.id != envelope.memoId || decoded.revision != envelope.memoRevision ||
            decoded.digestSha256 != envelope.memoDigestSha256 ||
            decoded.target.pageNumber + 1 != envelope.pageNumber ||
            decoded.target.attemptNo != envelope.attemptNo
        ) return RemotePageSyncIncomingResult.DROP

        val incoming = runCatching { decoded.remapTo(localTarget) }
            .getOrElse { return RemotePageSyncIncomingResult.DROP }
        // This document is authenticated to the pinned student and fenced to the exact current
        // session, manifest generation, page token, workbook/content identity, and attempt above.
        // Therefore a same-revision fork after a student backup restore is authoritative here. An
        // older Telegram document from before that restore carries the former generation/token and
        // is dropped before reaching this call.
        val result = runCatching { memoRepository.applyAuthenticatedStudentMemo(incoming) }
            .getOrElse { return RemotePageSyncIncomingResult.RETAIN }
        return when (result.status) {
            MemoAuthoritativeApplyStatus.APPLIED,
            MemoAuthoritativeApplyStatus.ALREADY_CURRENT,
            MemoAuthoritativeApplyStatus.STALE,
            -> RemotePageSyncIncomingResult.ACKNOWLEDGE
            // The authenticated single-memo apply contract resolves equal-revision forks. Do not
            // create an immortal Telegram inbox entry if a future implementation reports conflict.
            MemoAuthoritativeApplyStatus.CONFLICT -> RemotePageSyncIncomingResult.ACKNOWLEDGE
        }
    }

    private fun enqueuePageSyncEnvelope(
        envelope: com.studyink.monitor.core.RemoteReviewEnvelope,
    ): TelegramEnqueueResult {
        if (!allowsTelegramUserActionNow()) return TelegramEnqueueResult.PREVIOUSLY_SUPERSEDED
        val document = writeProtocolDocument(envelope)
        return try {
            if (!allowsTelegramUserActionNow()) {
                TelegramEnqueueResult.PREVIOUSLY_SUPERSEDED
            } else {
                gateway.enqueuePeerDocument(
                    transferId = envelope.transferId,
                    payloadType = envelope.type.name,
                    plaintext = document,
                    coalesceKey = (envelope as? StudentMemoEnvelope)?.let(
                        RemoteReviewExchangeStateMachine::coalesceKey,
                    ),
                )
            }
        } finally {
            document.delete()
        }
    }

    private fun processIncoming(pending: PendingTelegramPeerDocument) {
        if (pending.payloadType == CONSTRUCTION_TELEGRAM_PAYLOAD) return
        val session = refreshSession() ?: return
        if (pending.senderBotId != session.peerBotId) {
            dropIncoming(pending)
            return
        }
        // New builds exchange one ordered page-scoped stream. Any part of the former rendered
        // snapshot/reply protocol may have spent days in Telegram while a device was offline;
        // settle it before decoding so it cannot overwrite a newer exact-attempt page state.
        if (isRetiredLegacyRemoteReviewPayloadType(pending.payloadType)) {
            dropIncoming(pending)
            return
        }
        if (pending.byteCount !in 1..RemoteReviewLimits.OPERATIONAL_DOCUMENT_BYTES.toLong()) {
            dropIncoming(pending)
            return
        }
        val documentBytes = runCatching { pending.file.readBytes() }.getOrNull() ?: run {
            retainedUndecodableUpdateIds += pending.updateId
            return
        }
        val decoded = when (val result = decodeRemoteReviewInboxDocument(documentBytes)) {
            is RemoteReviewInboxDecodeResult.Decoded -> result.document
            RemoteReviewInboxDecodeResult.RetainWithoutAcknowledgement -> {
                retainedUndecodableUpdateIds += pending.updateId
                return
            }
        }
        retainedUndecodableUpdateIds -= pending.updateId
        if (decoded.exceedsOperationalLimit || decoded.envelope.transferId != pending.transferId ||
            decoded.envelope.type.name != pending.payloadType
        ) {
            dropIncoming(pending)
            return
        }

        when (val envelope = decoded.envelope) {
            is PageSyncManifestEnvelope -> {
                if (pending.payloadType != RemoteReviewEnvelopeType.PAGE_SYNC_MANIFEST.name) {
                    dropIncoming(pending)
                } else {
                    processPageSyncIncoming(pending) { pageSync.receiveManifest(envelope) }
                }
            }
            is PageSyncRequestEnvelope -> {
                if (pending.payloadType != RemoteReviewEnvelopeType.PAGE_SYNC_REQUEST.name) {
                    dropIncoming(pending)
                } else {
                    processPageSyncIncoming(pending) { pageSync.receiveRequest(envelope) }
                }
            }
            is PageAnnotationEnvelope -> {
                if (pending.payloadType != RemoteReviewEnvelopeType.PAGE_ANNOTATION.name) {
                    dropIncoming(pending)
                } else {
                    processPageSyncIncoming(pending) { pageSync.receiveAnnotation(envelope) }
                }
            }
            is PageSyncAckEnvelope -> {
                if (pending.payloadType != RemoteReviewEnvelopeType.PAGE_SYNC_ACK.name) {
                    dropIncoming(pending)
                } else {
                    processPageSyncIncoming(pending) { pageSync.receiveAck(envelope) }
                }
            }
            is GptExplanationLayerEnvelope -> {
                if (pending.payloadType != RemoteReviewEnvelopeType.GPT_EXPLANATION_LAYER.name) {
                    dropIncoming(pending)
                } else {
                    // Unlike ordinary recoverable page data, this document is the sender's only
                    // durable explicit-publish proof. LAN ownership retains it rather than issuing
                    // a false semantic success ACK before LAN has applied the same publication.
                    processPageSyncIncoming(pending, dropWhenLanOwns = false) {
                        receiveGptExplanationLayer(envelope)
                    }
                }
            }
            is StudentMemoEnvelope -> {
                if (pending.payloadType != RemoteReviewEnvelopeType.STUDENT_MEMO.name) {
                    dropIncoming(pending)
                } else {
                    processPageSyncIncoming(pending, dropWhenLanOwns = false) {
                        receiveStudentMemo(envelope)
                    }
                }
            }
            is PageSnapshotEnvelope -> {
                // Retained only in the codec for historical readability. The pre-decode gate
                // normally catches this; keep a decoded defense for mismatched/crafted metadata.
                dropIncoming(pending)
                return
            }
            is TeacherFeedbackEnvelope -> {
                dropIncoming(pending)
                return
            }
            is RemoteGradeEnvelope -> {
                dropIncoming(pending)
                return
            }
            is ChatMessageEnvelope -> {
                if (pending.payloadType != RemoteReviewEnvelopeType.CHAT_MESSAGE.name) {
                    dropIncoming(pending)
                    return
                }
                receivePeerChat(pending, envelope, session)
            }
            else -> dropIncoming(pending) // Semantic ACK envelopes are not used by this path.
        }
    }

    private fun settlePageSyncIncoming(
        pending: PendingTelegramPeerDocument,
        result: RemotePageSyncIncomingResult,
    ) {
        when (result) {
            RemotePageSyncIncomingResult.ACKNOWLEDGE,
            RemotePageSyncIncomingResult.DROP,
            -> gateway.acknowledgePeerDocument(pending.updateId)
            RemotePageSyncIncomingResult.RETAIN -> Unit
        }
    }

    /**
     * A delayed Telegram page document must never mutate a page while LAN owns or is catching up
     * the same application data. READY documents are obsolete and can be transport-ACKed; grace
     * documents remain in the encrypted inbox until one transport has actually won.
     */
    private inline fun processPageSyncIncoming(
        pending: PendingTelegramPeerDocument,
        dropWhenLanOwns: Boolean = true,
        crossinline apply: () -> RemotePageSyncIncomingResult,
    ) {
        val (route, result) = LanSyncBus.withActiveSessionLease { activeLan ->
            val currentRoute = globalPageSyncTransportRoute(activeLan?.session)
            currentRoute to if (currentRoute == GlobalPageSyncTransportRoute.TELEGRAM) {
                apply()
            } else {
                null
            }
        }
        when (route) {
            GlobalPageSyncTransportRoute.LAN_OWNS -> if (dropWhenLanOwns) dropIncoming(pending)
            GlobalPageSyncTransportRoute.LAN_GRACE -> Unit
            GlobalPageSyncTransportRoute.TELEGRAM -> settlePageSyncIncoming(
                pending,
                requireNotNull(result),
            )
        }
    }

    /**
     * Legacy rendered-page feedback and the delta protocol share the same transport ownership.
     * Holding the LAN bus lease through [apply] closes the check/apply race without introducing a
     * second generation journal or allowing two writers. The transport ACK may happen inside the
     * guarded operation, but no LAN session transition can begin until its durable mutation ends.
     */
    private inline fun processTelegramPageDataIncoming(
        pending: PendingTelegramPeerDocument,
        crossinline apply: () -> Unit,
    ) {
        val route = LanSyncBus.withActiveSessionLease { activeLan ->
            globalPageSyncTransportRoute(activeLan?.session).also { currentRoute ->
                if (currentRoute == GlobalPageSyncTransportRoute.TELEGRAM) apply()
            }
        }
        when (route) {
            GlobalPageSyncTransportRoute.LAN_OWNS -> dropIncoming(pending)
            GlobalPageSyncTransportRoute.LAN_GRACE,
            GlobalPageSyncTransportRoute.TELEGRAM,
            -> Unit
        }
    }

    private fun dropIncoming(pending: PendingTelegramPeerDocument) {
        // Gateway authenticated/decrypted this exact pinned peer. A transport receipt lets it drop
        // an unsupported or invalid payload instead of replaying a permanent poison entry.
        gateway.acknowledgePeerDocument(pending.updateId)
    }

    private fun retireLegacyRenderedPageTransport() {
        if (legacyRenderedPageTransportRetired) return
        captureState.reset()
        gateway.cancelPendingPeerDocumentTransfers(
            RETIRED_LEGACY_REMOTE_REVIEW_PAYLOAD_TYPES,
        )
        legacyRenderedPageTransportRetired = gateway.pendingPeerDocumentTransfers(
            RETIRED_LEGACY_REMOTE_REVIEW_PAYLOAD_TYPES,
        ).isEmpty()
    }

    private fun receivePageSnapshot(
        pending: PendingTelegramPeerDocument,
        snapshot: PageSnapshotEnvelope,
        session: ConnectedRemoteReviewSession,
    ) {
        val temporaryImage = File.createTempFile("incoming-page-", ".jpg", stagingDirectory)
        try {
            FileOutputStream(temporaryImage).use { output ->
                output.write(snapshot.copyRenderedPageBytes())
                output.flush()
                output.fd.sync()
            }
            val existed = ledger.incoming(snapshot.transferId) != null
            ledger.storeIncoming(
                value = IncomingRemoteSnapshot(
                    transferId = snapshot.transferId,
                    pageToken = snapshot.pageToken,
                    workbookLabel = snapshot.workbookLabel,
                    studentLabel = snapshot.studentLabel,
                    pageNumber = snapshot.pageNumber,
                    attemptNo = snapshot.attemptNo,
                    studentRevision = snapshot.revision,
                    widthPx = snapshot.dimensions.widthPx,
                    heightPx = snapshot.dimensions.heightPx,
                    receivedAtEpochMs = pending.receivedAtEpochMs,
                    imagePath = "pending",
                    // The transfer-id parser is retained only for compatibility with development
                    // builds which briefly carried the digest in the caption.
                    studentInkDigest = snapshot.studentInkDigest
                        ?: snapshotStudentInkDigest(snapshot.transferId),
                ),
                sourceImage = temporaryImage,
                maximumBytes = RemoteReviewLimits.MAX_SNAPSHOT_IMAGE_BYTES.toLong(),
            )
            ownership.record(snapshot.transferId, session)
            // This transport receipt is deliberately after both fsynced application records.
            if (gateway.acknowledgePeerDocument(pending.updateId) && !existed) onInboxChanged()
        } finally {
            temporaryImage.delete()
        }
    }

    private fun receiveTeacherFeedback(
        pending: PendingTelegramPeerDocument,
        feedback: TeacherFeedbackEnvelope,
        session: ConnectedRemoteReviewSession,
    ) {
        val source = ledger.outgoing(feedback.sourceSnapshot.transferId) ?: run {
            dropIncoming(pending)
            return
        }
        if (ownership.owner(source.transferId)?.matches(session) != true || !feedback.matches(source)) {
            dropIncoming(pending)
            return
        }
        when (ledger.feedbackDecision(feedback.transferId, feedback.sourceSnapshot.pageToken, feedback.feedbackRevision)) {
            RemoteFeedbackDecision.DUPLICATE,
            RemoteFeedbackDecision.SUPERSEDED,
            -> {
                gateway.acknowledgePeerDocument(pending.updateId)
                return
            }
            RemoteFeedbackDecision.APPLY -> Unit
        }

        val attemptLayer = source.attemptNo ?: 1
        val peerDeviceId = remoteTeacherDeviceId(session.peerBotId)
        val stored = annotationStore.loadPageState(source.bookId, source.pageNumber)
        buildPublishedRemoteTeacherLayerChange(
            snapshot = stored.snapshot,
            operationClockHighWater = stored.operationClockHighWater,
            feedback = feedback,
            attemptNo = attemptLayer,
            peerDeviceId = peerDeviceId,
        )?.let(annotationStore::append)
        ledger.recordFeedbackApplied(
            transferId = feedback.transferId,
            pageToken = feedback.sourceSnapshot.pageToken,
            revision = feedback.feedbackRevision,
            appliedAtEpochMs = System.currentTimeMillis(),
        )
        RemoteReviewFeedbackBus.publish(
            RemoteTeacherFeedbackApplied(
                bookId = source.bookId,
                pageNumber = source.pageNumber,
                attemptNo = source.attemptNo,
                transferId = feedback.transferId,
                basedOnStudentRevision = feedback.sourceSnapshot.revision,
                note = feedback.note,
            ),
        )
        // Annotation append and feedback revision journal are both durable at this point.
        gateway.acknowledgePeerDocument(pending.updateId)
    }

    private fun receiveRemoteGrade(
        pending: PendingTelegramPeerDocument,
        grade: RemoteGradeEnvelope,
        session: ConnectedRemoteReviewSession,
    ) {
        val source = ledger.outgoing(grade.sourceSnapshot.transferId) ?: run {
            dropIncoming(pending)
            return
        }
        if (
            ownership.owner(source.transferId)?.matches(session) != true ||
            !grade.matches(source, chatScope(session)?.peerDeviceId) ||
            grade.maximumScore != 1 ||
            grade.score !in 0..1 ||
            source.attemptNo == null ||
            source.studentInkDigest == null
        ) {
            dropIncoming(pending)
            return
        }

        val currentSnapshot = runCatching {
            annotationStore.loadPage(source.bookId, source.pageNumber)
        }.getOrNull() ?: run {
            if (runCatching { library.book(source.bookId) }.isFailure) dropIncoming(pending)
            return
        }
        if (studentInkDigest(currentSnapshot, source.attemptNo) != grade.studentInkDigest) {
            // The teacher graded an image that has since changed. Settle this stale action so it
            // cannot poison-replay forever, then request a fresh exact snapshot when Telegram is
            // still the active route. No mark is written in this branch.
            gateway.acknowledgePeerDocument(pending.updateId)
            captureState.forceCurrent(SystemClock.elapsedRealtime())
            // Rendering is intentionally outside the LAN ownership lease. The queued drain
            // rechecks the route before it creates an outbox entry, while the stale grade itself
            // is already durably settled under the lease.
            execute { drainOneDueCapture(session) }
            return
        }

        val targetBook = runCatching { library.book(source.bookId) }.getOrNull()
        if (targetBook == null || source.pageNumber !in 0 until targetBook.pageCount) {
            dropIncoming(pending)
            return
        }

        val markGroup = runCatching { buildRemoteGradeMarkGroup(source, grade) }
            .getOrElse { error ->
                if (error is IllegalArgumentException) {
                    dropIncoming(pending)
                    return
                }
                throw error
            }
        val changed = try {
            library.upsertMarkGroupFromSync(
                bookId = source.bookId,
                pageNumber = source.pageNumber,
                incoming = markGroup,
            )
        } catch (_: IllegalArgumentException) {
            // A validly encrypted but domain-invalid marker (for example an id colliding with a
            // different local page) is permanent poison, not a transient disk failure.
            dropIncoming(pending)
            return
        }
        if (changed) {
            RemoteGradeAppliedBus.publish(
                RemoteGradeApplied(
                    bookId = source.bookId,
                    pageNumber = source.pageNumber,
                    attemptNo = grade.attemptNo,
                    gradeGroupId = grade.gradeGroupId,
                    correct = grade.score == grade.maximumScore,
                ),
            )
        }
        // The catalog merge and ownership binding are both durable before the transport ACK.
        gateway.acknowledgePeerDocument(pending.updateId)
    }

    private fun receivePeerChat(
        pending: PendingTelegramPeerDocument,
        message: ChatMessageEnvelope,
        session: ConnectedRemoteReviewSession,
    ) {
        val scope = chatScope(session) ?: return
        if (message.senderDeviceId != scope.peerDeviceId) {
            dropIncoming(pending)
            return
        }
        val recorded = runCatching {
            peerChat.recordIncoming(scope, message, pending.receivedAtEpochMs)
        }.getOrNull() ?: return
        if (recorded.disposition == RemotePeerChatRecordDisposition.CONFLICT) {
            dropIncoming(pending)
            return
        }
        RemotePeerChatStateBus.publish(recorded.state)
        // The message and duplicate guard are fsynced before Telegram may delete its ciphertext.
        gateway.acknowledgePeerDocument(pending.updateId)
    }

    fun <T> withConstructionOutbound(target: ConstructionTarget, block: (ConstructionTelegramRoute) -> T): T? = synchronized(operationLock) {
        if (closed || pausedForMaintenance) return@synchronized null
        val session = refreshSession() ?: return@synchronized null
        if (session != observedSession || pageSyncStore.currentPairId() != session.pairId) return@synchronized null
        val route = constructionRouteLocked(target, session) ?: return@synchronized null
        if (closed || pausedForMaintenance || connectedSession() != session) return@synchronized null
        block(route)
    }

    fun <T> withConstructionIncoming(senderBotId: Long, address: ConstructionTelegramAddress, block: (ConstructionTelegramRoute) -> T): T? = synchronized(operationLock) {
        if (closed || pausedForMaintenance) return@synchronized null
        val session = refreshSession() ?: return@synchronized null
        if (session != observedSession || senderBotId != session.peerBotId || address.pairId != session.pairId ||
            pageSyncStore.currentPairId() != session.pairId) return@synchronized null
        val localBookId = when (session.role) {
            RemoteReviewRole.STUDENT -> pageSyncStore.studentPage(address.pageToken)?.bookId
            RemoteReviewRole.TEACHER -> pageSyncStore.teacherPage(address.pageToken)?.localBookId
        } ?: return@synchronized null
        val target = runCatching { ConstructionTarget(localBookId, address.pageNumber, address.attemptNo, address.memoId) }.getOrNull()
            ?: return@synchronized null
        val route = constructionRouteLocked(target, session) ?: return@synchronized null
        if (route.address != address || closed || pausedForMaintenance || connectedSession() != session) return@synchronized null
        block(route)
    }

    private fun constructionRouteLocked(target: ConstructionTarget, session: ConnectedRemoteReviewSession): ConstructionTelegramRoute? {
        if (target.ownerScope != "local" || target.attemptNo <= 0 || pageSyncStore.currentPairId() != session.pairId) return null
        val book = runCatching { library.book(target.bookId) }.getOrNull() ?: return null
        val hash = book.contentSha256.lowercase()
        if (target.pageNumber !in 0 until book.pageCount || !Regex("[0-9a-f]{64}").matches(hash)) return null
        if (!runCatching { library.attempts(target.bookId, target.pageNumber).any { it.attemptNo == target.attemptNo } }.getOrDefault(false)) return null
        val address = when (session.role) {
            RemoteReviewRole.STUDENT -> {
                val generation = pageSyncStore.studentGeneration()
                if (generation <= 0) return null
                val pageToken = tokenFactory.pageSyncPageToken(session.pairId, target.bookId, target.pageNumber, generation)
                val workbookToken = tokenFactory.pageSyncWorkbookToken(session.pairId, target.bookId)
                val page = pageSyncStore.studentPage(pageToken) ?: return null
                if (page.syncGeneration != generation || page.pageToken != pageToken || page.workbookToken != workbookToken ||
                    page.bookId != target.bookId || page.pageNumber != target.pageNumber || page.contentSha256 != hash || target.attemptNo !in page.attemptNos) return null
                ConstructionTelegramAddress(session.pairId, generation, pageToken, workbookToken, hash, target.pageNumber, target.attemptNo, target.memoId)
            }
            RemoteReviewRole.TEACHER -> {
                val generation = pageSyncStore.teacherManifestGeneration()
                if (generation <= 0) return null
                val page = pageSyncStore.teacherPageForLocalTarget(target.bookId, target.pageNumber) ?: return null
                if (page.syncGeneration != generation || page.localBookId != target.bookId || page.pageNumber != target.pageNumber ||
                    page.contentSha256 != hash || target.attemptNo !in page.attemptNos) return null
                ConstructionTelegramAddress(session.pairId, generation, page.pageToken, page.workbookToken, hash, target.pageNumber, target.attemptNo, target.memoId)
            }
        }
        return ConstructionTelegramRoute(target, session.role, session.peerBotId, address)
    }

    private fun connectedSession(): ConnectedRemoteReviewSession? =
        (gateway.remoteReviewPeerStatus() as? RemoteReviewPeerStatus.Connected)?.let { status ->
            ConnectedRemoteReviewSession(
                role = status.role,
                pairId = status.pairId,
                peerBotId = status.peer.botId,
                peerUsername = status.peer.username,
            )
        }

    private fun chatScope(session: ConnectedRemoteReviewSession): RemotePeerChatScope? {
        val localBotId = runCatching { gateway.localBotIdentity().id }.getOrNull() ?: return null
        return RemotePeerChatScope(
            pairId = session.pairId,
            localDeviceId = telegramBotDeviceId(localBotId),
            peerDeviceId = telegramBotDeviceId(session.peerBotId),
        )
    }

    override fun close() {
        closed = true
        pausedForMaintenance = true
        workGeneration.incrementAndGet()
        LanSyncBus.removeListener(lanListener)
        LibraryAttemptBus.removeListener(attemptListener)
        StudentExplanationLayerBus.removeListener(assistantLayerListener)
        memoChangeSubscription?.close()
        presenceSubscription?.close()
        heartbeatSubscription?.close()
        peerDocumentSubscription?.close()
        telegramStatusSubscription?.close()
        telegramPeerLinkSubscription?.close()
        teacherReviewSubscription?.close()
        teacherReviewProvenanceSubscription?.close()
        worker.shutdownNow()
        runCatching { worker.awaitTermination(2, TimeUnit.SECONDS) }
        synchronized(operationLock) {
            captureState.reset()
            clearStudentMemoQueues()
            pageSync.close()
            observedSession = null
            hybridBookId = null
            hybridDecision = null
            pageSyncTransportRoute = GlobalPageSyncTransportRoute.TELEGRAM
            HybridLinkStatusBus.clear()
            stagingDirectory.listFiles().orEmpty().filter(File::isFile).forEach(File::delete)
        }
    }

    private companion object {
        const val TICK_MILLIS = 1_000L
        const val MAX_INBOX_DOCUMENTS_PER_TICK = 8
        const val GPT_PUBLICATION_RETRY_MS = 30_000L
    }
}

internal enum class GlobalPageSyncTransportRoute { TELEGRAM, LAN_GRACE, LAN_OWNS }

internal fun shouldClearGptPublicationRetryGate(
    previous: GlobalPageSyncTransportRoute,
    current: GlobalPageSyncTransportRoute,
): Boolean = current == GlobalPageSyncTransportRoute.TELEGRAM && previous != current

internal fun TeacherPageSyncRecord.authorizesGptExplanation(
    localBookId: String,
    pageNumber: Int,
    attemptNo: Int,
    contentSha256: String,
): Boolean = syncGeneration > 0L && !mappingRequired && this.localBookId == localBookId &&
    this.pageNumber == pageNumber && this.contentSha256 == contentSha256 && attemptNo in attemptNos

internal fun gptTelegramTransferId(
    pending: PendingStudentExplanationPublication,
    pairId: String,
    pageToken: String,
): String {
    val seed = buildString {
        append(pending.publicationId).append('|')
        append(pending.deliveryAttempt).append('|')
        append(pairId).append('|')
        append(pageToken)
    }.toByteArray(StandardCharsets.UTF_8)
    return "gpt_" + MessageDigest.getInstance("SHA-256")
        .digest(seed)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

private data class StudentMemoKey(val target: MemoTarget, val memoId: String)

private data class PendingStudentMemoChange(
    val firstChangedAtElapsedMs: Long,
    val lastChangedAtElapsedMs: Long,
    val retryNotBeforeElapsedMs: Long = 0L,
    val retryFailures: Int = 0,
) {
    init {
        require(firstChangedAtElapsedMs >= 0L)
        require(lastChangedAtElapsedMs >= firstChangedAtElapsedMs)
        require(retryNotBeforeElapsedMs >= 0L)
        require(retryFailures >= 0)
    }

    fun isDue(nowElapsedMs: Long): Boolean = studentMemoSendIsDue(
        firstChangedAtElapsedMs,
        lastChangedAtElapsedMs,
        retryNotBeforeElapsedMs,
        nowElapsedMs,
    )
}

private enum class StudentMemoSendResult { ACKNOWLEDGED, DEFERRED, RETRY, SKIP }

internal fun studentMemoSendIsDue(
    firstChangedAtElapsedMs: Long,
    lastChangedAtElapsedMs: Long,
    retryNotBeforeElapsedMs: Long,
    nowElapsedMs: Long,
): Boolean {
    require(firstChangedAtElapsedMs >= 0L && lastChangedAtElapsedMs >= firstChangedAtElapsedMs)
    require(retryNotBeforeElapsedMs >= 0L && nowElapsedMs >= 0L)
    val quietDeadline = safeStudentMemoAdd(lastChangedAtElapsedMs, STUDENT_MEMO_QUIET_MS)
    val maximumDeadline = safeStudentMemoAdd(firstChangedAtElapsedMs, STUDENT_MEMO_MAX_DELAY_MS)
    return nowElapsedMs >= maxOf(minOf(quietDeadline, maximumDeadline), retryNotBeforeElapsedMs)
}

internal fun studentMemoRetryDelayMs(failureCount: Int): Long {
    require(failureCount > 0)
    val exponent = (failureCount - 1).coerceAtMost(4)
    return (STUDENT_MEMO_RETRY_BASE_MS shl exponent).coerceAtMost(STUDENT_MEMO_RETRY_MAX_MS)
}

internal fun studentMemoDeliveryEpochDay(nowEpochMs: Long): Long {
    require(nowEpochMs >= 0L)
    return nowEpochMs / STUDENT_MEMO_DAY_MS
}

internal fun studentMemoDeliveryAttemptSlot(nowEpochMs: Long): Int =
    (studentMemoDeliveryEpochDay(nowEpochMs) % STUDENT_MEMO_DELIVERY_ATTEMPTS).toInt()

private fun safeStudentMemoAdd(left: Long, right: Long): Long =
    if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

private const val STUDENT_MEMO_QUIET_MS = 5_000L
private const val STUDENT_MEMO_MAX_DELAY_MS = 30_000L
private const val STUDENT_MEMO_RETRY_BASE_MS = 2_000L
private const val STUDENT_MEMO_RETRY_MAX_MS = 30_000L
private const val STUDENT_MEMO_DAY_MS = 24L * 60L * 60L * 1_000L
private const val STUDENT_MEMO_DELIVERY_ATTEMPTS = 7

internal fun StudentPageSyncRecord.authorizesStudentMemo(
    target: MemoTarget,
    expectedGeneration: Long,
    expectedPageToken: String,
    expectedWorkbookToken: String,
    expectedContentSha256: String,
): Boolean = syncGeneration == expectedGeneration && syncGeneration > 0L &&
    pageToken == expectedPageToken && workbookToken == expectedWorkbookToken &&
    bookId == target.bookId && pageNumber == target.pageNumber &&
    contentSha256 == expectedContentSha256 && target.attemptNo in attemptNos

internal fun TeacherPageSyncRecord.studentMemoLocalTarget(
    envelope: StudentMemoEnvelope,
    currentManifestGeneration: Long,
    localContentSha256: String,
): MemoTarget? {
    val bookId = localBookId ?: return null
    if (syncGeneration != currentManifestGeneration ||
        envelope.syncGeneration != currentManifestGeneration ||
        envelope.pageToken != pageToken || envelope.workbookToken != workbookToken ||
        envelope.contentSha256 != contentSha256 || localContentSha256 != contentSha256 ||
        envelope.pageNumber != pageNumber + 1 || envelope.attemptNo !in attemptNos
    ) return null
    return runCatching { MemoTarget(bookId, pageNumber, envelope.attemptNo) }.getOrNull()
}

internal fun studentMemoTelegramTransferId(
    pairId: String,
    syncGeneration: Long,
    pageToken: String,
    memoId: String,
    memoRevision: Long,
    memoDigestSha256: String,
    deliveryAttempt: Int = 0,
): String {
    require(deliveryAttempt in 0 until STUDENT_MEMO_DELIVERY_ATTEMPTS)
    val seed = buildString {
        append(pairId).append('|')
        append(syncGeneration).append('|')
        append(pageToken).append('|')
        append(memoId).append('|')
        append(memoRevision).append('|')
        append(memoDigestSha256)
        // Attempt zero intentionally preserves the pre-reliability transfer identity so an ACK
        // already stored by an older build still settles the same immutable memo revision.
        if (deliveryAttempt > 0) append('|').append(deliveryAttempt)
    }.toByteArray(StandardCharsets.UTF_8)
    return "memo_" + MessageDigest.getInstance("SHA-256")
        .digest(seed)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

/** Legacy rendered-page messages have no shared revision order with the page-sync stream. */
internal fun isRetiredLegacyRemoteReviewPayloadType(payloadType: String): Boolean =
    payloadType in RETIRED_LEGACY_REMOTE_REVIEW_PAYLOAD_TYPES

internal fun globalHybridDisplayDecision(
    route: GlobalPageSyncTransportRoute,
    telegramDecision: HybridLinkDecision,
): HybridLinkDecision = when (route) {
    GlobalPageSyncTransportRoute.TELEGRAM -> telegramDecision
    GlobalPageSyncTransportRoute.LAN_GRACE -> HybridLinkDecision(
        mode = HybridLinkMode.LAN_GRACE,
        label = HybridLinkLabel.LAN,
        health = HybridLinkHealth.TRANSITIONING,
        activeTransport = null,
        enteredTelegramFallback = false,
    )
    GlobalPageSyncTransportRoute.LAN_OWNS -> HybridLinkDecision(
        mode = HybridLinkMode.LAN_LIVE,
        label = HybridLinkLabel.LAN,
        health = HybridLinkHealth.READY,
        activeTransport = HybridLinkTransport.LAN,
        enteredTelegramFallback = false,
    )
}

/**
 * Page deltas are application-global, while the Reader's hybrid indicator is book-scoped. Route
 * from the LAN service's one real session so opening another book cannot accidentally enable both
 * transports. A connected pre-READY session keeps Telegram paused; the four-second policy asks the
 * socket to yield, and only its definitive DISCONNECTED publication hands ownership to Telegram.
 */
internal fun globalPageSyncTransportRoute(
    activeLanSession: LanSessionSnapshot?,
): GlobalPageSyncTransportRoute = when {
    activeLanSession == null || isLanTransportDefinitelyDisconnected(activeLanSession.connectionState) ->
        GlobalPageSyncTransportRoute.TELEGRAM
    activeLanSession.phase == LanSessionPhase.READY -> GlobalPageSyncTransportRoute.LAN_OWNS
    else -> GlobalPageSyncTransportRoute.LAN_GRACE
}

internal fun shouldRequestLanCatchUpYield(
    activeLanSession: LanSessionSnapshot?,
    decision: HybridLinkDecision?,
    startedAtElapsedMs: Long,
    nowElapsedMs: Long,
    alreadyRequested: Boolean,
): Boolean {
    require(startedAtElapsedMs >= 0L && nowElapsedMs >= startedAtElapsedMs)
    return !alreadyRequested &&
        nowElapsedMs - startedAtElapsedMs >= LAN_CATCH_UP_GRACE_MS &&
        activeLanSession?.connectionState == LanConnectionState.CONNECTED &&
        activeLanSession.phase != LanSessionPhase.READY &&
        decision?.activeTransport == HybridLinkTransport.TELEGRAM
}

internal fun isLanTransportDefinitelyDisconnected(connectionState: LanConnectionState): Boolean =
    connectionState != LanConnectionState.CONNECTED

/** Mirrors the gateway's 24-hour peer-document retention after bounded dead-letter eviction. */
internal fun isUnacknowledgedPeerReceiptExpired(acceptedAtEpochMs: Long, nowEpochMs: Long): Boolean =
    acceptedAtEpochMs >= 0L && nowEpochMs >= acceptedAtEpochMs &&
        nowEpochMs - acceptedAtEpochMs >= 24L * 60L * 60L * 1_000L

internal data class RemoteReviewCaptureTarget(
    val bookId: String,
    /** Zero-based local page number. */
    val pageNumber: Int,
    val attemptNo: Int?,
) {
    init {
        require(bookId.isNotBlank())
        require(pageNumber >= 0)
        require(attemptNo == null || attemptNo > 0)
    }
}

internal data class RemoteReviewCaptureTicket(
    val target: RemoteReviewCaptureTarget,
    val dirtySequence: Long,
    val knownRevision: Long,
    val lastSentRevision: Long?,
    val lastImageSha256: String?,
    val forceSend: Boolean,
)

internal sealed interface RemotePublishedFeedbackState {
    data object NONE : RemotePublishedFeedbackState
    data object HISTORY_UNAVAILABLE : RemotePublishedFeedbackState
    data class Available(val feedback: RemoteTeacherFeedback) : RemotePublishedFeedbackState
}

internal enum class RemoteReviewOutboundState { PENDING, SENT, FAILED }

/** Thread-confined, Android-free dirty/rate/coalescing policy tested independently. */
internal class RemoteReviewCaptureState(
    private val intervalMs: Long = 60_000L,
    private val settleMs: Long = 750L,
    private val unchangedRetryMs: Long = 1_000L,
    private val failureRetryMs: Long = 30_000L,
) {
    private data class Entry(
        var knownRevision: Long,
        var dirty: Boolean = false,
        var forceSend: Boolean = false,
        var dirtySequence: Long = 0L,
        var dueAtElapsedMs: Long = Long.MAX_VALUE,
        var rendering: Boolean = false,
        var unchangedRetries: Int = 0,
        var lastSentAtElapsedMs: Long? = null,
        var lastSentRevision: Long? = null,
        var lastImageSha256: String? = null,
        var outstandingTransferId: String? = null,
    )

    private var current: RemoteReviewCaptureTarget? = null
    private val entries = linkedMapOf<RemoteReviewCaptureTarget, Entry>()

    init {
        require(intervalMs > 0L && settleMs >= 0L && unchangedRetryMs > 0L && failureRetryMs > 0L)
    }

    fun reset() {
        current = null
        entries.clear()
    }

    /** Forces one full snapshot when Telegram becomes the active transport. */
    fun forceCurrent(nowElapsedMs: Long) {
        require(nowElapsedMs >= 0L)
        val target = current ?: return
        val entry = entries[target] ?: return
        // A durable page already in Telegram's outbox is itself the immediate fallback snapshot.
        // New pen/submit heartbeats may still dirty the entry while it is pending.
        if (entry.outstandingTransferId != null) return
        entry.dirty = true
        entry.forceSend = true
        entry.dirtySequence++
        entry.unchangedRetries = 0
        entry.dueAtElapsedMs = nowElapsedMs
    }

    /** Rebinds a durable Telegram outbox item after process restart without rendering a duplicate. */
    fun restoreOutstanding(
        target: RemoteReviewCaptureTarget,
        transferId: String,
        revision: Long,
        nowElapsedMs: Long,
    ): Boolean {
        require(transferId.isNotBlank() && revision >= 0L && nowElapsedMs >= 0L)
        val entry = entries.getOrPut(target) { Entry(revision) }
        entry.knownRevision = max(entry.knownRevision, revision)
        entry.lastSentRevision = revision
        entry.lastSentAtElapsedMs = nowElapsedMs
        entry.outstandingTransferId = transferId
        entry.rendering = false
        return true
    }

    fun onPresence(
        next: RemoteReviewCaptureTarget?,
        observedRevision: Long?,
        nowElapsedMs: Long,
    ) {
        require(nowElapsedMs >= 0L)
        val previous = current
        if (previous != next) {
            previous?.let { target ->
                entries[target]?.takeIf(Entry::dirty)?.let { it.dueAtElapsedMs = nowElapsedMs }
            }
            current = next
        }
        if (next != null && observedRevision != null) {
            entries.getOrPut(next) { Entry(observedRevision.coerceAtLeast(0L)) }
        }
        trim()
    }

    fun onHeartbeat(heartbeat: StudentWorkHeartbeat, nowElapsedMs: Long) {
        require(nowElapsedMs >= 0L)
        if (heartbeat.kind == StudentWorkKind.PAGE_CHANGE) return
        val target = current ?: return
        if (heartbeat.bookId != target.bookId || heartbeat.pageNumber != target.pageNumber + 1) return
        val entry = entries[target] ?: return
        entry.dirty = true
        entry.dirtySequence++
        entry.unchangedRetries = 0
        if (heartbeat.kind == StudentWorkKind.SUBMIT) {
            entry.forceSend = true
            entry.dueAtElapsedMs = nowElapsedMs
        } else {
            val rateBoundary = entry.lastSentAtElapsedMs?.let { safeAdd(it, intervalMs) }
                ?: safeAdd(nowElapsedMs, settleMs)
            entry.dueAtElapsedMs = min(entry.dueAtElapsedMs, max(safeAdd(nowElapsedMs, settleMs), rateBoundary))
        }
    }

    fun nextDue(
        nowElapsedMs: Long,
        outboundState: (String) -> RemoteReviewOutboundState,
    ): RemoteReviewCaptureTicket? {
        require(nowElapsedMs >= 0L)
        val ordered = entries.entries.sortedBy { it.value.dueAtElapsedMs }
        for ((target, entry) in ordered) {
            if (!entry.dirty || entry.rendering || entry.dueAtElapsedMs > nowElapsedMs) continue
            entry.outstandingTransferId?.let { transferId ->
                when (outboundState(transferId)) {
                    RemoteReviewOutboundState.PENDING -> return@let
                    RemoteReviewOutboundState.SENT,
                    RemoteReviewOutboundState.FAILED,
                    -> entry.outstandingTransferId = null
                }
            }
            if (entry.outstandingTransferId != null) continue
            entry.rendering = true
            return RemoteReviewCaptureTicket(
                target = target,
                dirtySequence = entry.dirtySequence,
                knownRevision = entry.knownRevision,
                lastSentRevision = entry.lastSentRevision,
                lastImageSha256 = entry.lastImageSha256,
                forceSend = entry.forceSend,
            )
        }
        return null
    }

    fun shouldTransmit(
        ticket: RemoteReviewCaptureTicket,
        observedRevision: Long,
        imageSha256: String,
    ): Boolean {
        require(observedRevision >= 0L && imageSha256.isNotBlank())
        if (ticket.forceSend) return true
        if (observedRevision != ticket.knownRevision) return true
        return ticket.lastImageSha256 != null && ticket.lastImageSha256 != imageSha256
    }

    fun completeUnchanged(
        ticket: RemoteReviewCaptureTicket,
        observedRevision: Long,
        nowElapsedMs: Long,
    ) {
        val entry = entries[ticket.target] ?: return
        entry.rendering = false
        entry.knownRevision = observedRevision.coerceAtLeast(0L)
        if (entry.dirtySequence != ticket.dirtySequence) return
        if (entry.unchangedRetries < MAX_UNCHANGED_RETRIES) {
            entry.unchangedRetries++
            entry.dueAtElapsedMs = safeAdd(nowElapsedMs, unchangedRetryMs)
        } else {
            entry.dirty = false
            entry.forceSend = false
            entry.dueAtElapsedMs = Long.MAX_VALUE
        }
    }

    fun completeSent(
        ticket: RemoteReviewCaptureTicket,
        observedRevision: Long,
        imageSha256: String,
        transferId: String,
        nowElapsedMs: Long,
    ) {
        val entry = entries[ticket.target] ?: return
        entry.rendering = false
        entry.knownRevision = observedRevision
        entry.lastSentRevision = observedRevision
        entry.lastImageSha256 = imageSha256
        entry.lastSentAtElapsedMs = nowElapsedMs
        entry.outstandingTransferId = transferId
        entry.unchangedRetries = 0
        if (entry.dirtySequence == ticket.dirtySequence) {
            entry.dirty = false
            entry.forceSend = false
            entry.dueAtElapsedMs = Long.MAX_VALUE
        } else {
            entry.dueAtElapsedMs = safeAdd(nowElapsedMs, intervalMs)
        }
    }

    fun completeFailure(ticket: RemoteReviewCaptureTicket, nowElapsedMs: Long) {
        val entry = entries[ticket.target] ?: return
        entry.rendering = false
        entry.dirty = true
        entry.dueAtElapsedMs = safeAdd(nowElapsedMs, failureRetryMs)
    }

    private fun trim() {
        if (entries.size <= MAX_TRACKED_PAGES) return
        val removable = entries.entries.iterator()
        while (entries.size > MAX_TRACKED_PAGES && removable.hasNext()) {
            val next = removable.next()
            if (next.key != current && !next.value.dirty && next.value.outstandingTransferId == null) {
                removable.remove()
            }
        }
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private companion object {
        const val MAX_UNCHANGED_RETRIES = 3
        const val MAX_TRACKED_PAGES = 64
    }
}

/** Pure full-layer replacement: all layers from this Telegram teacher and attempt are replaced. */
internal fun buildPublishedRemoteTeacherLayerChange(
    snapshot: AnnotationSnapshot,
    operationClockHighWater: Long,
    feedback: TeacherFeedbackEnvelope,
    attemptNo: Int,
    peerDeviceId: String,
): AnnotationChange? {
    require(snapshot.pageNumber >= 0 && operationClockHighWater >= 0L)
    require(attemptNo >= 0 && peerDeviceId.isNotBlank())
    val operationId = OperationId("remote_feedback_${stableHash(feedback.transferId).take(40)}")
    if (operationId in snapshot.appliedOperationIds) return null
    val itemId = "remote-review:${feedback.sourceSnapshot.pageToken}"
    val removed = snapshot.activeStrokes.asSequence()
        .filter { stroke ->
            stroke.authorId == REMOTE_TEACHER_AUTHOR_ID &&
                stroke.deviceId == peerDeviceId &&
                stroke.attemptNo == attemptNo
        }
        .mapTo(linkedSetOf(), StrokeAsset::id)
    val logicalClock = max(
        operationClockHighWater,
        snapshot.assets.values.maxOfOrNull(StrokeAsset::logicalClock) ?: 0L,
    ) + 1L
    val canonicalHeight = CANONICAL_PAGE_WIDTH *
        feedback.sourceSnapshot.dimensions.heightPx.toFloat() /
        feedback.sourceSnapshot.dimensions.widthPx.toFloat()
    val canonicalShortSide = min(CANONICAL_PAGE_WIDTH, canonicalHeight)
    val renderedFeedbackStrokes = feedback.strokes.mapIndexed { index, stroke ->
        val points = stroke.points.map { point ->
            PagePoint(
                x = point.x * CANONICAL_PAGE_WIDTH,
                y = point.y * canonicalHeight,
                pressure = point.pressure,
            )
        }
        StrokeAsset(
            id = StrokeId(
                "remote_feedback_stroke_${stableHash("${feedback.transferId}:${stroke.strokeId}:$index").take(40)}",
            ),
            pageNumber = snapshot.pageNumber,
            tool = when (stroke.tool) {
                TeacherInkTool.PEN -> StrokeTool.PEN
                TeacherInkTool.HIGHLIGHTER -> StrokeTool.HIGHLIGHTER
            },
            colorArgb = stroke.argb,
            width = (stroke.widthNormalized * canonicalShortSide).coerceAtLeast(0.1f),
            points = points,
            authorId = REMOTE_TEACHER_AUTHOR_ID,
            attemptNo = attemptNo,
            logicalClock = logicalClock,
            deviceId = peerDeviceId,
            itemId = itemId,
            publishedAtEpochMillis = feedback.createdAtEpochMs,
            createdAtEpochMillis = feedback.createdAtEpochMs,
            formatVersion = ANNOTATION_FORMAT_VERSION,
        )
    }
    // An empty full-layer publish still needs a durable, invisible generation marker. Without it,
    // the student renderer could fall back to an older correction from another attempt and show a
    // layer the teacher explicitly erased.
    val added = renderedFeedbackStrokes.ifEmpty {
        listOf(
            StrokeAsset(
                id = StrokeId("remote_feedback_empty_${stableHash(feedback.transferId).take(40)}"),
                pageNumber = snapshot.pageNumber,
                tool = StrokeTool.PEN,
                colorArgb = 0x00000000,
                width = 0.1f,
                points = emptyList(),
                authorId = REMOTE_TEACHER_AUTHOR_ID,
                attemptNo = attemptNo,
                logicalClock = logicalClock,
                deviceId = peerDeviceId,
                itemId = itemId,
                publishedAtEpochMillis = feedback.createdAtEpochMs,
                createdAtEpochMillis = feedback.createdAtEpochMs,
                formatVersion = ANNOTATION_FORMAT_VERSION,
            ),
        )
    }
    val operation = AssetOperation(
        id = operationId,
        removedStrokeIds = removed,
        addedStrokeIds = added.mapTo(linkedSetOf(), StrokeAsset::id),
        logicalClock = logicalClock,
        deviceId = peerDeviceId,
    )
    val assets = snapshot.assets + added.associateBy(StrokeAsset::id)
    val active = snapshot.activeStrokeIds.toMutableSet().apply {
        removeAll(removed)
        addAll(operation.addedStrokeIds)
    }
    return AnnotationChange(
        snapshot = AnnotationSnapshot(
            bookId = snapshot.bookId,
            pageNumber = snapshot.pageNumber,
            revision = snapshot.revision + 1L,
            assets = assets,
            activeStrokeIds = active,
            appliedOperationIds = snapshot.appliedOperationIds + operation.id,
        ),
        operation = operation,
        addedAssets = added,
    )
}

internal fun TeacherFeedbackEnvelope.matches(source: OutgoingRemoteSnapshot): Boolean =
    sourceSnapshot.transferId == source.transferId &&
        sourceSnapshot.pageToken == source.pageToken &&
        sourceSnapshot.revision == source.studentRevision &&
        sourceSnapshot.dimensions.widthPx == source.widthPx &&
        sourceSnapshot.dimensions.heightPx == source.heightPx

internal fun RemoteGradeEnvelope.matches(source: OutgoingRemoteSnapshot): Boolean =
    sourceSnapshot.transferId == source.transferId &&
        sourceSnapshot.pageToken == source.pageToken &&
        sourceSnapshot.revision == source.studentRevision &&
        sourceSnapshot.dimensions.widthPx == source.widthPx &&
        sourceSnapshot.dimensions.heightPx == source.heightPx &&
        attemptNo == source.attemptNo &&
        studentInkDigest == source.studentInkDigest

internal fun RemoteGradeEnvelope.matches(
    source: OutgoingRemoteSnapshot,
    expectedPeerDeviceId: String?,
): Boolean = expectedPeerDeviceId != null &&
    lastModifiedByDeviceId == expectedPeerDeviceId &&
    matches(source)

/** Pure mapping used after exact source/digest validation; never reads or changes student ink. */
internal fun buildRemoteGradeMarkGroup(
    source: OutgoingRemoteSnapshot,
    grade: RemoteGradeEnvelope,
): MarkGroup {
    require(grade.matches(source))
    require(grade.maximumScore == 1 && grade.score in 0..1)
    val canonicalHeight = CANONICAL_PAGE_WIDTH * source.heightPx.toFloat() / source.widthPx.toFloat()
    return MarkGroup(
        id = grade.gradeGroupId,
        bookId = source.bookId,
        pageNumber = source.pageNumber,
        anchor = PagePoint(
            x = grade.anchor.x * CANONICAL_PAGE_WIDTH,
            y = grade.anchor.y * canonicalHeight,
        ),
        marks = listOf(
            Mark(
                attemptNo = grade.attemptNo,
                color = if (grade.score == grade.maximumScore) MarkColor.BLUE else MarkColor.RED,
                gradedAtEpochMillis = grade.createdAtEpochMs,
            ),
        ),
        createdAtEpochMillis = grade.createdAtEpochMs,
        syncRevision = grade.syncRevision,
        lastModifiedByDeviceId = grade.lastModifiedByDeviceId,
    )
}

/** Deterministic student-only visual state used to reject grades for a changed attempt. */
internal fun studentInkDigest(snapshot: AnnotationSnapshot, attemptNo: Int): String {
    require(attemptNo > 0)
    val digest = MessageDigest.getInstance("SHA-256")
    val intBytes = ByteArray(4)
    fun putInt(value: Int) {
        intBytes[0] = (value ushr 24).toByte()
        intBytes[1] = (value ushr 16).toByte()
        intBytes[2] = (value ushr 8).toByte()
        intBytes[3] = value.toByte()
        digest.update(intBytes)
    }
    fun putText(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        putInt(bytes.size)
        digest.update(bytes)
    }

    putText("masternote-student-ink-v1")
    putInt(attemptNo)
    val strokes = snapshot.activeStrokes.asSequence()
        .filter { it.authorId == "student" && it.attemptNo == attemptNo }
        .sortedBy { it.id.value }
        .toList()
    putInt(strokes.size)
    strokes.forEach { stroke ->
        putText(stroke.id.value)
        putText(stroke.tool.name)
        putInt(stroke.colorArgb)
        putInt(stroke.width.toRawBits())
        putInt(stroke.points.size)
        stroke.points.forEach { point ->
            putInt(point.x.toRawBits())
            putInt(point.y.toRawBits())
            putInt(point.pressure.toRawBits())
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

internal fun snapshotStudentInkDigest(transferId: String): String? =
    SNAPSHOT_WITH_DIGEST.matchEntire(transferId)?.groupValues?.get(1)

/**
 * Undecodable authenticated documents stay durable and unacknowledged. A later compatible app can
 * reopen them, while the sender keeps its encrypted retry copy instead of treating decode failure as
 * successful persistence.
 */
internal sealed interface RemoteReviewInboxDecodeResult {
    data class Decoded(
        val document: DecodedRemoteReviewDocument,
    ) : RemoteReviewInboxDecodeResult

    data object RetainWithoutAcknowledgement : RemoteReviewInboxDecodeResult
}

internal fun decodeRemoteReviewInboxDocument(bytes: ByteArray): RemoteReviewInboxDecodeResult =
    try {
        RemoteReviewInboxDecodeResult.Decoded(RemoteReviewDocumentCodec.decode(bytes))
    } catch (_: Exception) {
        RemoteReviewInboxDecodeResult.RetainWithoutAcknowledgement
    }

internal fun selectRemoteReviewInboxUpdateIds(
    pendingUpdateIds: List<Long>,
    retainedUpdateIds: Set<Long>,
    limit: Int,
): List<Long> {
    require(limit > 0)
    return pendingUpdateIds.asSequence()
        .distinct()
        .filterNot(retainedUpdateIds::contains)
        .take(limit)
        .toList()
}

private fun StudentStudyPresence.toCaptureTarget(): RemoteReviewCaptureTarget? {
    val resolvedBookId = bookId
    val resolvedPageNumber = pageNumber
    if (!active || resolvedBookId.isNullOrBlank() || resolvedPageNumber == null) return null
    return RemoteReviewCaptureTarget(
        bookId = resolvedBookId,
        pageNumber = resolvedPageNumber - 1,
        attemptNo = attemptNo,
    )
}

private data class ConnectedRemoteReviewSession(
    val role: RemoteReviewRole,
    val pairId: String,
    val peerBotId: Long,
    val peerUsername: String,
)

private data class RemoteReviewPeerOwner(
    val pairId: String,
    val peerBotId: Long,
) {
    fun matches(session: ConnectedRemoteReviewSession): Boolean =
        pairId == session.pairId && peerBotId == session.peerBotId
}

/** Tiny append-only peer binding index kept separate from the image/mapping ledger. */
private class RemoteReviewPeerOwnershipStore(private val file: File) {
    private val owners = linkedMapOf<String, RemoteReviewPeerOwner>()
    private var journalRecordCount = 0

    init {
        require(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true)
        File(file.parentFile, "${file.name}.compact").delete()
        if (file.isFile) {
            file.forEachLine(StandardCharsets.UTF_8) { line ->
                journalRecordCount++
                runCatching {
                    val fields = line.split('\t')
                    require(fields.size == 4 && fields[0] == VERSION)
                    val transferId = decode(fields[1])
                    val pairId = decode(fields[2])
                    val peerBotId = fields[3].toLong().also { require(it > 0L) }
                    owners[transferId] = RemoteReviewPeerOwner(pairId, peerBotId)
                }
            }
        }
        trim()
        compactIfNeeded()
    }

    @Synchronized
    fun record(transferId: String, session: ConnectedRemoteReviewSession) {
        owners[transferId]?.let { existing ->
            require(existing.matches(session)) { "Remote-review transfer belongs to another peer" }
            return
        }
        val line = encodeRecord(transferId, RemoteReviewPeerOwner(session.pairId, session.peerBotId))
        FileOutputStream(file, true).use { output ->
            output.write(line.toByteArray(StandardCharsets.UTF_8))
            output.write('\n'.code)
            output.flush()
            output.fd.sync()
        }
        journalRecordCount++
        owners[transferId] = RemoteReviewPeerOwner(session.pairId, session.peerBotId)
        trim()
        compactIfNeeded()
    }

    @Synchronized
    fun owner(transferId: String): RemoteReviewPeerOwner? = owners[transferId]

    private fun trim() {
        while (owners.size > MAX_OWNERS) owners.remove(owners.keys.first())
    }

    private fun compactIfNeeded() {
        val recordPressure = journalRecordCount >= COMPACT_AFTER_RECORDS &&
            journalRecordCount >= owners.size.coerceAtLeast(1) * 2
        if (!recordPressure && file.length() <= MAX_JOURNAL_BYTES) return
        val temporary = File(file.parentFile, "${file.name}.compact")
        try {
            FileOutputStream(temporary, false).use { output ->
                owners.forEach { (transferId, owner) ->
                    output.write(encodeRecord(transferId, owner).toByteArray(StandardCharsets.UTF_8))
                    output.write('\n'.code)
                }
                output.flush()
                output.fd.sync()
            }
            replaceAtomically(temporary, file)
            journalRecordCount = owners.size
        } finally {
            temporary.delete()
        }
    }

    private fun encodeRecord(transferId: String, owner: RemoteReviewPeerOwner): String = listOf(
        VERSION,
        encode(transferId),
        encode(owner.pairId),
        owner.peerBotId.toString(),
    ).joinToString("\t")

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    private fun decode(value: String): String = Base64.getUrlDecoder().decode(value)
        .toString(StandardCharsets.UTF_8)

    private companion object {
        const val VERSION = "RRP1"
        const val MAX_OWNERS = 10_000
        const val COMPACT_AFTER_RECORDS = 12_000
        const val MAX_JOURNAL_BYTES = 8L * 1_024L * 1_024L
    }
}

/** Durable page-token sequence; UI edit revisions are session-local and cannot be trusted globally. */
internal class RemoteReviewTeacherRevisionStore(
    private val file: File,
    private val maxReservations: Int = DEFAULT_MAX_RESERVATIONS,
    private val compactAfterRecords: Int = DEFAULT_COMPACT_AFTER_RECORDS,
    private val maximumJournalBytes: Long = DEFAULT_MAX_JOURNAL_BYTES,
) {
    private data class Reservation(val pageToken: String, val revision: Long)

    private val byTransfer = linkedMapOf<String, Reservation>()
    private val latestByPage = linkedMapOf<String, Long>()
    private var journalRecordCount = 0

    init {
        require(maxReservations > 0 && compactAfterRecords > 0 && maximumJournalBytes > 0L)
        require(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true)
        File(file.parentFile, "${file.name}.compact").delete()
        repairTrailingJournalTail()
        if (file.isFile) {
            file.forEachLine(StandardCharsets.UTF_8) { line ->
                journalRecordCount++
                runCatching {
                    val fields = line.split('\t')
                    require(fields.size == 4 && fields[0] == VERSION)
                    if (fields[1] == LATEST_RECORD) {
                        val pageToken = decode(fields[2]).also { require(PROTOCOL_ID.matches(it)) }
                        val revision = fields[3].toLong().also { require(it >= 1L) }
                        updateLatest(pageToken, revision)
                    } else {
                        val pageToken = decode(fields[1]).also { require(PROTOCOL_ID.matches(it)) }
                        val transferId = decode(fields[2]).also { require(PROTOCOL_ID.matches(it)) }
                        val revision = fields[3].toLong().also { require(it >= 1L) }
                        byTransfer[transferId] = Reservation(pageToken, revision)
                        updateLatest(pageToken, revision)
                    }
                }
            }
        }
        trim()
        compactIfNeeded()
    }

    /** Remove a crash-torn final record before a later append can fuse with it permanently. */
    private fun repairTrailingJournalTail() {
        if (!file.isFile || file.length() == 0L) return
        val bytes = file.readBytes()
        if (bytes.last() == '\n'.code.toByte()) return
        val lastCompleteEnd = bytes.indexOfLast { it == '\n'.code.toByte() } + 1
        RandomAccessFile(file, "rw").use { journal ->
            journal.setLength(lastCompleteEnd.toLong())
            journal.fd.sync()
        }
    }

    @Synchronized
    fun reserve(pageToken: String, transferId: String, requestedRevision: Long): Long {
        require(PROTOCOL_ID.matches(pageToken) && PROTOCOL_ID.matches(transferId))
        require(requestedRevision >= 1L)
        byTransfer[transferId]?.let { existing ->
            require(existing.pageToken == pageToken) { "Feedback transfer changed page" }
            return existing.revision
        }
        val latest = latestByPage[pageToken] ?: 0L
        check(latest < Long.MAX_VALUE) { "Feedback revision exhausted" }
        val revision = max(requestedRevision, latest + 1L)
        val line = encodeReservation(pageToken, transferId, revision)
        FileOutputStream(file, true).use { output ->
            output.write(line.toByteArray(StandardCharsets.UTF_8))
            output.write('\n'.code)
            output.flush()
            output.fd.sync()
        }
        journalRecordCount++
        byTransfer[transferId] = Reservation(pageToken, revision)
        updateLatest(pageToken, revision)
        trim()
        compactIfNeeded()
        return revision
    }

    @Synchronized
    fun latestRevision(pageToken: String): Long {
        require(PROTOCOL_ID.matches(pageToken))
        return latestByPage[pageToken] ?: 0L
    }

    private fun trim() {
        while (byTransfer.size > maxReservations) byTransfer.remove(byTransfer.keys.first())
        while (latestByPage.size > maxReservations) latestByPage.remove(latestByPage.keys.first())
    }

    private fun updateLatest(pageToken: String, revision: Long) {
        val current = latestByPage[pageToken] ?: 0L
        if (revision < current) return
        // Reinsert so the bounded map retains recently edited pages rather than first-seen pages.
        latestByPage.remove(pageToken)
        latestByPage[pageToken] = revision
    }

    private fun compactIfNeeded() {
        val liveUpperBound = byTransfer.size + latestByPage.size
        val recordPressure = journalRecordCount >= compactAfterRecords &&
            journalRecordCount >= liveUpperBound.coerceAtLeast(1) * 2
        if (!recordPressure && file.length() <= maximumJournalBytes) return

        val latestRepresentedByReservation = byTransfer.values
            .groupingBy(Reservation::pageToken)
            .fold(0L) { value, reservation -> max(value, reservation.revision) }
        val lines = buildList {
            latestByPage.forEach { (pageToken, revision) ->
                if ((latestRepresentedByReservation[pageToken] ?: 0L) < revision) {
                    add(encodeLatest(pageToken, revision))
                }
            }
            byTransfer.forEach { (transferId, reservation) ->
                add(encodeReservation(reservation.pageToken, transferId, reservation.revision))
            }
        }
        val temporary = File(file.parentFile, "${file.name}.compact")
        try {
            FileOutputStream(temporary, false).use { output ->
                lines.forEach { line ->
                    output.write(line.toByteArray(StandardCharsets.UTF_8))
                    output.write('\n'.code)
                }
                output.flush()
                output.fd.sync()
            }
            replaceAtomically(temporary, file)
            journalRecordCount = lines.size
        } finally {
            temporary.delete()
        }
    }

    private fun encodeReservation(pageToken: String, transferId: String, revision: Long): String =
        listOf(VERSION, encode(pageToken), encode(transferId), revision.toString()).joinToString("\t")

    private fun encodeLatest(pageToken: String, revision: Long): String =
        listOf(VERSION, LATEST_RECORD, encode(pageToken), revision.toString()).joinToString("\t")

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    private fun decode(value: String): String = Base64.getUrlDecoder().decode(value)
        .toString(StandardCharsets.UTF_8)

    private companion object {
        const val VERSION = "RRT1"
        const val LATEST_RECORD = "LATEST"
        const val DEFAULT_MAX_RESERVATIONS = 10_000
        const val DEFAULT_COMPACT_AFTER_RECORDS = 12_000
        const val DEFAULT_MAX_JOURNAL_BYTES = 8L * 1_024L * 1_024L
    }
}

/** Keeps only the last durably queued full teacher layer per page token. */
internal class RemoteReviewPublishedFeedbackStore(
    private val directory: File,
    private val maxPublishedPages: Int = DEFAULT_MAX_PUBLISHED_PAGES,
) {
    private var lastWriteTimestamp = 0L

    init {
        require(maxPublishedPages > 0)
        require(directory.mkdirs() || directory.isDirectory)
        directory.listFiles().orEmpty().filter { it.name.endsWith(".part") }.forEach(File::delete)
        lastWriteTimestamp = directory.listFiles().orEmpty().maxOfOrNull(File::lastModified) ?: 0L
        trim()
    }

    @Synchronized
    fun store(feedback: TeacherFeedbackEnvelope) {
        val encoded = RemoteReviewDocumentCodec.encode(feedback)
        val target = fileFor(feedback.sourceSnapshot.pageToken)
        val temporary = File(directory, "${target.name}.part")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(encoded.copyBytes())
                output.flush()
                output.fd.sync()
            }
            runCatching {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            val nextTimestamp = max(
                System.currentTimeMillis(),
                if (lastWriteTimestamp == Long.MAX_VALUE) Long.MAX_VALUE else lastWriteTimestamp + 1L,
            )
            if (target.setLastModified(nextTimestamp)) lastWriteTimestamp = nextTimestamp
            trim()
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    @Synchronized
    fun load(pageToken: String): TeacherFeedbackEnvelope? = runCatching {
        val file = fileFor(pageToken)
        if (!file.isFile || file.length() !in 1..RemoteReviewLimits.OPERATIONAL_DOCUMENT_BYTES.toLong()) {
            return null
        }
        val decoded = RemoteReviewDocumentCodec.decode(file.readBytes()).envelope
        (decoded as? TeacherFeedbackEnvelope)?.takeIf { it.sourceSnapshot.pageToken == pageToken }
    }.getOrNull()

    private fun fileFor(pageToken: String): File {
        require(PROTOCOL_ID.matches(pageToken))
        return File(directory, "${stableHash(pageToken)}.${RemoteReviewDocumentCodec.FILE_EXTENSION}")
    }

    private fun trim() {
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".${RemoteReviewDocumentCodec.FILE_EXTENSION}") }
            .sortedByDescending(File::lastModified)
            .drop(maxPublishedPages)
            .forEach(File::delete)
    }

    private companion object {
        const val DEFAULT_MAX_PUBLISHED_PAGES = 512
    }
}

private fun TeacherFeedbackEnvelope.toReaderFeedback(
    source: IncomingRemoteSnapshot,
    rebindToSource: Boolean,
): RemoteTeacherFeedback =
    RemoteTeacherFeedback(
        feedbackId = transferId,
        sourceTransferId = if (rebindToSource) source.transferId else sourceSnapshot.transferId,
        pageToken = source.pageToken,
        bookFingerprint = source.pageToken,
        pageNumber = source.pageNumber - 1,
        basedOnStudentRevision = if (rebindToSource) source.studentRevision else sourceSnapshot.revision,
        feedbackRevision = feedbackRevision,
        strokes = strokes.map { stroke ->
            RemoteFeedbackStroke(
                id = stroke.strokeId,
                tool = when (stroke.tool) {
                    TeacherInkTool.PEN -> RemoteFeedbackStrokeTool.PEN
                    TeacherInkTool.HIGHLIGHTER -> RemoteFeedbackStrokeTool.HIGHLIGHTER
                },
                colorArgb = stroke.argb,
                widthFraction = stroke.widthNormalized,
                points = stroke.points.map { point ->
                    RemoteNormalizedPoint(point.x, point.y, point.pressure)
                },
            )
        },
        createdAtEpochMillis = createdAtEpochMs,
    )

/** Stable pair-scoped opaque token; no book title or page number is exposed in the caption. */
private class RemoteReviewPageTokenFactory(private val keyFile: File) {
    private val key: ByteArray = loadOrCreateKey()

    fun pageToken(pairId: String, target: RemoteReviewCaptureTarget): String {
        return token("snapshot", pairId, target.bookId, target.pageNumber, target.attemptNo ?: 0, 0L, "page")
    }

    fun pageSyncWorkbookToken(pairId: String, bookId: String): String =
        token("page-sync-workbook", pairId, bookId, 0, 0, 0L, "workbook")

    fun pageSyncPageToken(pairId: String, bookId: String, pageNumber: Int, generation: Long): String {
        require(pageNumber >= 0 && generation > 0L)
        return token("page-sync-page", pairId, bookId, pageNumber, 0, generation, "page")
    }

    private fun token(
        domain: String,
        pairId: String,
        bookId: String,
        pageNumber: Int,
        attemptNo: Int,
        generation: Long,
        prefix: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val material = "$domain\u0000$pairId\u0000$bookId\u0000$pageNumber\u0000$attemptNo\u0000$generation"
        val digest = mac.doFinal(material.toByteArray(StandardCharsets.UTF_8))
        return "${prefix}_${Base64.getUrlEncoder().withoutPadding().encodeToString(digest)}"
    }

    private fun loadOrCreateKey(): ByteArray {
        keyFile.takeIf(File::isFile)?.readBytes()?.takeIf { it.size == KEY_BYTES }?.let { return it }
        require(keyFile.parentFile?.mkdirs() == true || keyFile.parentFile?.isDirectory == true)
        val key = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        val temporary = File(keyFile.parentFile, "${keyFile.name}.part")
        FileOutputStream(temporary).use { output ->
            output.write(key)
            output.flush()
            output.fd.sync()
        }
        if (keyFile.exists() && !keyFile.delete()) error("Cannot replace remote-review token key")
        check(temporary.renameTo(keyFile)) { "Cannot commit remote-review token key" }
        return key
    }

    private companion object { const val KEY_BYTES = 32 }
}

private fun TelegramEnqueueResult.isDurablyAccepted(): Boolean =
    this == TelegramEnqueueResult.ENQUEUED ||
        this == TelegramEnqueueResult.ALREADY_PENDING ||
        this == TelegramEnqueueResult.ALREADY_DELIVERED

private fun remoteTeacherDeviceId(peerBotId: Long): String = "telegram-teacher-$peerBotId"

private fun telegramBotDeviceId(botId: Long): String = "telegrambot_$botId"

private fun safeProtocolId(prefix: String, value: String): String =
    value.takeIf { PROTOCOL_ID.matches(it) } ?: "${prefix}_${stableHash(value).take(40)}"

private fun boundedDisplayLabel(value: String, maxUtf8Bytes: Int, fallback: String): String {
    val clean = value.filterNot(Char::isISOControl).trim().ifBlank { fallback }
    val result = StringBuilder()
    for (character in clean) {
        val candidate = result.toString() + character
        if (candidate.toByteArray(StandardCharsets.UTF_8).size > maxUtf8Bytes) break
        result.append(character)
    }
    return result.toString().ifBlank { fallback }
}

private fun stableHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

private fun replaceAtomically(source: File, target: File) {
    runCatching {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }.getOrElse {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

private val PROTOCOL_ID = Regex("[A-Za-z0-9_-]{8,128}")
private val SNAPSHOT_WITH_DIGEST = Regex("snapshot_([0-9a-f]{64})_[A-Za-z0-9_-]{8,55}")
private const val REMOTE_TEACHER_AUTHOR_ID = "teacher"
