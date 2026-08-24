package com.studyink.app

import android.app.Application
import android.os.SystemClock
import com.studyink.annotation.engine.AnnotationChange
import com.studyink.annotation.storage.PageOperationLogStore
import com.studyink.core.model.ANNOTATION_FORMAT_VERSION
import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.AssetOperation
import com.studyink.core.model.CANONICAL_PAGE_WIDTH
import com.studyink.core.model.OperationId
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeId
import com.studyink.core.model.StrokeTool
import com.studyink.library.data.LibraryRepository
import com.studyink.monitor.core.NormalizedTeacherPoint
import com.studyink.monitor.core.NormalizedTeacherStroke
import com.studyink.monitor.core.PageSnapshotEnvelope
import com.studyink.monitor.core.RemoteReviewDocumentCodec
import com.studyink.monitor.core.RemoteReviewEnvelopeType
import com.studyink.monitor.core.RemoteReviewFeedbackBus
import com.studyink.monitor.core.RemoteReviewLimits
import com.studyink.monitor.core.RemoteTeacherFeedbackApplied
import com.studyink.monitor.core.ReviewCanvasDimensions
import com.studyink.monitor.core.SnapshotImageFormat
import com.studyink.monitor.core.SnapshotReference
import com.studyink.monitor.core.StudentStudyPresence
import com.studyink.monitor.core.StudentStudyPresenceBus
import com.studyink.monitor.core.StudentWorkHeartbeat
import com.studyink.monitor.core.StudentWorkHeartbeatBus
import com.studyink.monitor.core.StudentWorkKind
import com.studyink.monitor.core.TeacherFeedbackEnvelope
import com.studyink.monitor.core.TeacherInkTool
import com.studyink.monitor.render.MasterNotePageRenderer
import com.studyink.monitor.render.PageRenderImageFormat
import com.studyink.monitor.render.PageRenderLimits
import com.studyink.monitor.render.PageRenderRequest
import com.studyink.monitor.render.RenderedPage
import com.studyink.monitor.telegram.PendingTelegramPeerDocument
import com.studyink.monitor.telegram.RemoteMonitorGateway
import com.studyink.monitor.telegram.RemoteReviewPeerStatus
import com.studyink.monitor.telegram.RemoteReviewRole
import com.studyink.monitor.telegram.TelegramEnqueueResult
import com.studyink.reader.RemoteFeedbackStrokeTool
import com.studyink.reader.RemoteFeedbackStroke
import com.studyink.reader.RemoteNormalizedPoint
import com.studyink.reader.RemoteTeacherFeedback
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max
import kotlin.math.min

/**
 * Application boundary for the optional Telegram remote-review path.
 *
 * The existing parent text/submission coordinator remains untouched. This coordinator owns one
 * serial worker and exchanges only rendered student pages and a published teacher ink layer; it
 * never writes marks, attempts, grading state, student assets, or LAN session state.
 */
object MasterNoteRemoteReviewCoordinator {
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
    private val operationLock = Any()
    private val captureState = RemoteReviewCaptureState()
    private var observedSession: ConnectedRemoteReviewSession? = null
    private var presenceSubscription: AutoCloseable? = null
    private var heartbeatSubscription: AutoCloseable? = null
    private var peerDocumentSubscription: AutoCloseable? = null

    @Volatile
    private var closed = false

    @Volatile
    private var pausedForMaintenance = false

    private val workGeneration = AtomicLong()

    fun start() {
        presenceSubscription = StudentStudyPresenceBus.subscribe { presence ->
            execute { handlePresence(presence) }
        }
        heartbeatSubscription = StudentWorkHeartbeatBus.subscribe { heartbeat ->
            execute { handleHeartbeat(heartbeat) }
        }
        peerDocumentSubscription = gateway.subscribePeerDocuments { pending ->
            execute { processIncoming(pending) }
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

    fun publishTeacherFeedback(feedback: RemoteTeacherFeedback): TelegramEnqueueResult {
        if (closed || pausedForMaintenance) return TelegramEnqueueResult.NOT_CONFIGURED
        return synchronized(operationLock) {
            if (closed || pausedForMaintenance) {
                TelegramEnqueueResult.NOT_CONFIGURED
            } else {
                publishTeacherFeedbackLocked(feedback)
            }
        }
    }

    private fun publishTeacherFeedbackLocked(
        feedback: RemoteTeacherFeedback,
    ): TelegramEnqueueResult {
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
            gateway.enqueuePeerDocument(
                transferId = envelope.transferId,
                payloadType = envelope.type.name,
                plaintext = document,
            ).also { result ->
                if (result.isDurablyAccepted()) publishedFeedback.store(envelope)
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
            observedSession = null
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
        val observedRevision = target?.let {
            runCatching { annotationStore.loadPage(it.bookId, it.pageNumber).revision }.getOrNull()
        }
        captureState.onPresence(target, observedRevision, SystemClock.elapsedRealtime())
        drainOneDueCapture(session)
    }

    private fun handleHeartbeat(heartbeat: StudentWorkHeartbeat) {
        val session = refreshSession()
        if (session?.role != RemoteReviewRole.STUDENT) return
        captureState.onHeartbeat(heartbeat, SystemClock.elapsedRealtime())
        drainOneDueCapture(session)
    }

    private fun tick() {
        if (closed) return
        val session = refreshSession() ?: return
        gateway.pendingPeerDocuments().firstOrNull()?.let(::processIncoming)
        if (session.role == RemoteReviewRole.STUDENT) drainOneDueCapture(session)
    }

    private fun refreshSession(): ConnectedRemoteReviewSession? {
        val current = connectedSession()
        if (current == observedSession) return current
        observedSession = current
        captureState.reset()
        if (current?.role == RemoteReviewRole.STUDENT) {
            val target = StudentStudyPresenceBus.current()?.toCaptureTarget()
            val revision = target?.let {
                runCatching { annotationStore.loadPage(it.bookId, it.pageNumber).revision }.getOrNull()
            }
            captureState.onPresence(target, revision, SystemClock.elapsedRealtime())
        }
        return current
    }

    private fun drainOneDueCapture(session: ConnectedRemoteReviewSession) {
        if (connectedSession() != session) return
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
            val imageBytes = readBoundedImage(page.file)
            val pairScopedPageToken = tokenFactory.pageToken(session.pairId, ticket.target)
            val transferId = "snapshot_${UUID.randomUUID().toString().replace("-", "")}"
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

    private fun processIncoming(pending: PendingTelegramPeerDocument) {
        val session = refreshSession() ?: return
        if (pending.senderBotId != session.peerBotId) {
            dropIncoming(pending)
            return
        }
        if (pending.byteCount !in 1..RemoteReviewLimits.OPERATIONAL_DOCUMENT_BYTES.toLong()) {
            dropIncoming(pending)
            return
        }
        val decoded = runCatching {
            RemoteReviewDocumentCodec.decode(pending.file.readBytes())
        }.getOrNull() ?: run {
            dropIncoming(pending)
            return
        }
        if (decoded.exceedsOperationalLimit || decoded.envelope.transferId != pending.transferId ||
            decoded.envelope.type.name != pending.payloadType
        ) {
            dropIncoming(pending)
            return
        }

        when (val envelope = decoded.envelope) {
            is PageSnapshotEnvelope -> {
                if (session.role != RemoteReviewRole.TEACHER ||
                    pending.payloadType != RemoteReviewEnvelopeType.PAGE_SNAPSHOT.name
                ) {
                    dropIncoming(pending)
                    return
                }
                receivePageSnapshot(pending, envelope, session)
            }
            is TeacherFeedbackEnvelope -> {
                if (session.role != RemoteReviewRole.STUDENT ||
                    pending.payloadType != RemoteReviewEnvelopeType.TEACHER_FEEDBACK.name
                ) {
                    dropIncoming(pending)
                    return
                }
                receiveTeacherFeedback(pending, envelope, session)
            }
            else -> dropIncoming(pending) // Semantic ACK envelopes are not used by this path.
        }
    }

    private fun dropIncoming(pending: PendingTelegramPeerDocument) {
        // Gateway authenticated/decrypted this exact pinned peer. A transport receipt lets it drop
        // an unsupported or invalid payload instead of replaying a permanent poison entry.
        gateway.acknowledgePeerDocument(pending.updateId)
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

    private fun connectedSession(): ConnectedRemoteReviewSession? =
        (gateway.remoteReviewPeerStatus() as? RemoteReviewPeerStatus.Connected)?.let { status ->
            ConnectedRemoteReviewSession(
                role = status.role,
                pairId = status.pairId,
                peerBotId = status.peer.botId,
                peerUsername = status.peer.username,
            )
        }

    override fun close() {
        closed = true
        pausedForMaintenance = true
        workGeneration.incrementAndGet()
        presenceSubscription?.close()
        heartbeatSubscription?.close()
        peerDocumentSubscription?.close()
        worker.shutdownNow()
        runCatching { worker.awaitTermination(2, TimeUnit.SECONDS) }
        synchronized(operationLock) {
            captureState.reset()
            observedSession = null
            stagingDirectory.listFiles().orEmpty().filter(File::isFile).forEach(File::delete)
        }
    }

    private companion object {
        const val TICK_MILLIS = 1_000L
    }
}

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
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val material = "$pairId\u0000${target.bookId}\u0000${target.pageNumber}\u0000${target.attemptNo ?: 0}"
        val digest = mac.doFinal(material.toByteArray(StandardCharsets.UTF_8))
        return "page_${Base64.getUrlEncoder().withoutPadding().encodeToString(digest)}"
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
private const val REMOTE_TEACHER_AUTHOR_ID = "teacher"
