package com.studyink.app

import android.os.SystemClock
import com.studyink.annotation.storage.PageOperationLogStore
import com.studyink.annotation.storage.TeacherReviewPublishIntent
import com.studyink.core.model.Attempt
import com.studyink.core.model.MarkColor
import com.studyink.core.model.MarkGroup
import com.studyink.library.data.LibraryRepository
import com.studyink.monitor.core.PageAnnotationCompression
import com.studyink.monitor.core.PageAnnotationEnvelope
import com.studyink.monitor.core.PageAnnotationKind
import com.studyink.monitor.core.PageAnnotationPurpose
import com.studyink.monitor.core.PageSyncAckDisposition
import com.studyink.monitor.core.PageSyncAckEnvelope
import com.studyink.monitor.core.PageSyncAckSourceType
import com.studyink.monitor.core.PageSyncCursor
import com.studyink.monitor.core.PageSyncManifestEntry
import com.studyink.monitor.core.PageSyncManifestEnvelope
import com.studyink.monitor.core.PageSyncRequestEnvelope
import com.studyink.monitor.core.RemoteGradeApplied
import com.studyink.monitor.core.RemoteGradeAppliedBus
import com.studyink.monitor.core.RemoteReviewEnvelope
import com.studyink.monitor.core.RemoteReviewEnvelopeType
import com.studyink.monitor.core.RemoteReviewFeedbackBus
import com.studyink.monitor.core.RemoteReviewLimits
import com.studyink.monitor.core.RemoteStudentCursor
import com.studyink.monitor.core.RemoteStudentCursorBus
import com.studyink.monitor.core.RemoteStudentCursorTransport
import com.studyink.monitor.core.RemoteStudentPageApplied
import com.studyink.monitor.core.RemoteStudentPageAppliedBus
import com.studyink.monitor.core.RemoteTeacherFeedbackApplied
import com.studyink.monitor.core.StudentStudyPresence
import com.studyink.monitor.core.StudentWorkHeartbeat
import com.studyink.monitor.core.TeacherReviewPublished
import com.studyink.monitor.core.TeacherReviewPublicationProvenance
import com.studyink.monitor.core.pageAnnotationSha256Hex
import com.studyink.monitor.telegram.RemoteReviewRole
import com.studyink.monitor.telegram.TelegramEnqueueResult
import com.studyink.sync.lan.LanTeacherReviewPublication
import com.studyink.sync.lan.LanSyncBus
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

internal data class RemotePageSyncSession(val role: RemoteReviewRole, val pairId: String)

internal enum class RemotePageSyncIncomingResult {
    ACKNOWLEDGE,
    RETAIN,
    DROP,
}

/** Durable transport state as seen through the Telegram gateway journals. */
internal enum class RemotePageSyncOutboundState { NONE, PENDING, SENT, ACKNOWLEDGED, FAILED }

/**
 * Serial, page-scoped slow-live synchronization. It intentionally allows only one student-page
 * request at a time; current/recent pages are automatic and every older page is explicit UI work.
 */
internal class RemotePageSyncController(
    private val library: LibraryRepository,
    private val annotationStore: PageOperationLogStore,
    private val store: RemotePageSyncStore,
    private val pageToken: (pairId: String, bookId: String, pageNumber: Int, generation: Long) -> String,
    private val workbookToken: (pairId: String, bookId: String) -> String,
    private val reserveTeacherReviewRevision: (pageToken: String, transferId: String) -> Long,
    private val outboundState: (payloadType: String, transferId: String) -> RemotePageSyncOutboundState,
    private val sendEnvelope: (RemoteReviewEnvelope) -> TelegramEnqueueResult,
    private val nowElapsedMs: () -> Long = SystemClock::elapsedRealtime,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {
    private val listeners = CopyOnWriteArraySet<(RemotePageSyncUiState) -> Unit>()
    private var session: RemotePageSyncSession? = null
    private var telegramActive = false
    private var telegramOnline = false
    private var lanOwnsData = false
    private var currentPresence: StudentStudyPresence? = null
    private var manifestDueAtElapsedMs = Long.MAX_VALUE
    private var lastManifestSentAtElapsedMs: Long? = null
    /** Remaining bounded manifest windows in the current inventory advertisement cycle. */
    private var manifestBatchesRemaining = 0
    private val seededStudentBooks = linkedSetOf<String>()
    private var manualRunning = false
    private var intervalSeconds = DEFAULT_REMOTE_PAGE_SYNC_INTERVAL_SECONDS
    private var requestCooldownUntilElapsedMs = 0L
    private var lastRequestedPageToken: String? = null
    private var preferManualPageNext = false
    private val failedPageTokens = linkedSetOf<String>()
    private val failedTeacherReviewKeys = linkedSetOf<String>()
    private var nextTeacherReviewSendAtElapsedMs = 0L

    @Synchronized
    fun bindSession(next: RemotePageSyncSession?) {
        if (session == next) return
        // Commit the pair-scoped journal before exposing the new peer in memory. If storage is
        // full, the old controller session and the old durable pair remain aligned and a later
        // coordinator tick can retry safely.
        next?.let {
            store.bindPair(it.pairId)
            if (it.role == RemoteReviewRole.STUDENT && telegramActive) {
                store.beginStudentGeneration()
            }
        }
        session = next
        currentPresence = null
        manifestDueAtElapsedMs = Long.MAX_VALUE
        lastManifestSentAtElapsedMs = null
        manifestBatchesRemaining = 0
        seededStudentBooks.clear()
        manualRunning = false
        requestCooldownUntilElapsedMs = 0L
        lastRequestedPageToken = null
        preferManualPageNext = false
        failedPageTokens.clear()
        failedTeacherReviewKeys.clear()
        nextTeacherReviewSendAtElapsedMs = 0L
        if (next?.role == RemoteReviewRole.STUDENT && telegramActive) {
            seedAllStudentBooks()
            manifestDueAtElapsedMs = nowElapsedMs()
            manifestBatchesRemaining = requiredManifestBatchCount(store.studentPages().size)
        }
        RemoteStudentCursorBus.clear(RemoteStudentCursorTransport.TELEGRAM)
        notifyUiChanged()
    }

    @Synchronized
    fun setTransportState(active: Boolean, online: Boolean, lanOwnsData: Boolean = false) {
        val enteredFallback = active && !telegramActive
        telegramActive = active
        telegramOnline = online
        this.lanOwnsData = lanOwnsData
        when (session?.role) {
            RemoteReviewRole.STUDENT -> {
                if (active) {
                    store.beginStudentGeneration()
                    if (enteredFallback) {
                        seededStudentBooks.clear()
                        currentPresence?.takeIf(StudentStudyPresence::active)?.let { presence ->
                            refreshStudentPage(requireNotNull(presence.bookId), requireNotNull(presence.pageNumber) - 1)
                        }
                        seedAllStudentBooks()
                        manifestDueAtElapsedMs = nowElapsedMs()
                        manifestBatchesRemaining = requiredManifestBatchCount(store.studentPages().size)
                    }
                } else if (lanOwnsData) {
                    store.closeStudentGeneration()
                    manifestDueAtElapsedMs = Long.MAX_VALUE
                    manifestBatchesRemaining = 0
                }
            }
            RemoteReviewRole.TEACHER -> {
                if (lanOwnsData) {
                    store.clearTeacherManifestPagesForLan()
                    // LAN pauses Telegram page traffic but does not prove application delivery.
                    // Keep the exact publication queued so an immediate LAN loss can fall back
                    // without losing the correction.
                    drainTeacherPublishIntents()
                }
                if (enteredFallback) publishTeacherCursor()
            }
            null -> Unit
        }
        if (!active) RemoteStudentCursorBus.clear(RemoteStudentCursorTransport.TELEGRAM)
        notifyUiChanged()
    }

    @Synchronized
    fun onStudentPresence(presence: StudentStudyPresence) {
        if (session?.role != RemoteReviewRole.STUDENT) return
        if (presence.active && !isVisibleStudentBook(requireNotNull(presence.bookId))) {
            currentPresence = null
            return
        }
        currentPresence = presence
        if (telegramActive && presence.active) {
            val bookId = requireNotNull(presence.bookId)
            refreshStudentPage(bookId, requireNotNull(presence.pageNumber) - 1)
            seedStudentBook(bookId)
            scheduleManifestAtRateBoundary()
        }
    }

    @Synchronized
    fun onStudentHeartbeat(heartbeat: StudentWorkHeartbeat) {
        if (session?.role != RemoteReviewRole.STUDENT || !telegramActive) return
        val presence = currentPresence?.takeIf(StudentStudyPresence::active)
        val bookId = heartbeat.bookId ?: presence?.bookId ?: return
        val oneBasedPage = heartbeat.pageNumber ?: presence?.pageNumber ?: return
        if (!isVisibleStudentBook(bookId)) return
        refreshStudentPage(bookId, oneBasedPage - 1)
        scheduleManifestAtRateBoundary()
    }

    @Synchronized
    fun onLocalOperation(bookId: String, pageNumber: Int) {
        if (session?.role != RemoteReviewRole.STUDENT || !telegramActive) return
        if (!isVisibleStudentBook(bookId)) return
        refreshStudentPage(bookId, pageNumber)
        scheduleManifestAtRateBoundary()
    }

    @Synchronized
    fun onTeacherReviewPublished(event: TeacherReviewPublished) {
        if (session?.role != RemoteReviewRole.TEACHER) return
        // The immutable journal already contains pair provenance; the live event only accelerates
        // queueing. A publication owned by another pair remains held.
        queueTeacherEvent(event)
        drainTeacherPublishIntents()
        nextTeacherReviewSendAtElapsedMs = minOf(nextTeacherReviewSendAtElapsedMs, nowElapsedMs())
        tick()
    }

    /** Captured by Reader before its immutable publication preparation is fsynced. */
    @Synchronized
    fun teacherReviewPublicationProvenance(
        bookId: String,
        pageNumber: Int,
        attemptNo: Int,
    ): TeacherReviewPublicationProvenance? {
        val current = session?.takeIf { it.role == RemoteReviewRole.TEACHER } ?: return null
        if (store.currentPairId() != current.pairId) return null
        val book = runCatching { library.book(bookId) }.getOrNull() ?: return null
        if (pageNumber !in 0 until book.pageCount || book.contentSha256.length != 64) return null
        return TeacherReviewPublicationProvenance(
            pairId = current.pairId,
            workbookToken = resolvePublishedReviewWorkbookToken(
                book.id,
                book.contentSha256.lowercase(),
                pageNumber,
                attemptNo,
            ),
            manifestGeneration = store.teacherManifestGeneration(),
            manifestSequence = store.teacherManifestSequence(),
        )
    }

    @Synchronized
    fun onLanTeacherReviewAcknowledged(publication: LanTeacherReviewPublication) {
        val key = "${publication.bookId}:${publication.pageNumber}:${publication.attemptNo}"
        val pending = store.pendingTeacherReviews().firstOrNull { it.key == key }
        if (pending != null && pending.intentId != publication.publicationId) return
        if (!store.completeTeacherReviewFromLan(
                publication.bookId,
                publication.pageNumber,
                publication.attemptNo,
                publication.publicationId,
                nowEpochMs(),
            )
        ) return
        annotationStore.removeTeacherReviewPublishIntent(
            publication.bookId,
            publication.pageNumber,
            publication.attemptNo,
            publication.publicationId,
        )
        notifyUiChanged()
    }

    @Synchronized
    fun drainDurableTeacherPublishIntents() {
        if (session?.role == RemoteReviewRole.TEACHER) {
            drainTeacherPublishIntents()
        }
    }

    @Synchronized
    fun tick() {
        val currentSession = session ?: return
        if (currentSession.role == RemoteReviewRole.TEACHER) {
            drainTeacherPublishIntents()
        }
        if (!telegramActive) return
        when (currentSession.role) {
            RemoteReviewRole.STUDENT -> tickStudent(currentSession)
            RemoteReviewRole.TEACHER -> tickTeacher(currentSession)
        }
    }

    @Synchronized
    fun receiveManifest(envelope: PageSyncManifestEnvelope): RemotePageSyncIncomingResult {
        if (session?.role != RemoteReviewRole.TEACHER) return RemotePageSyncIncomingResult.DROP
        return runCatching {
            val requiresExplicitMapping = envelope.entries.asSequence()
                .map { it.workbookToken to it.contentSha256 }
                .distinct()
                .filter { (token, digest) ->
                    val mappedId = store.mappedLocalBookId(token, digest)
                    val mappedBookWasDeleted = mappedId != null &&
                        library.booksByContentSha256(digest).none { it.id == mappedId }
                    if (mappedBookWasDeleted) {
                        store.unbindMissingTeacherWorkbook(token, digest)
                    }
                    mappedBookWasDeleted || store.requiresExplicitWorkbookMapping(token, digest)
                }
                .map { it.first }
                .toSet()
            val pendingMappings = linkedMapOf<String, String>()
            val localClaims = linkedMapOf<String, String>()
            val pages = envelope.entries.map { entry ->
                val localBook = resolveLocalWorkbook(
                    entry.workbookToken,
                    entry.contentSha256,
                    allowAutomaticMapping = entry.workbookToken !in requiresExplicitMapping,
                )
                    ?.takeIf { book ->
                        canAssignLocalWorkbook(entry.workbookToken, book.id, localClaims)
                    }
                    ?.also { book -> localClaims[book.id] = entry.workbookToken }
                if (localBook != null) pendingMappings[entry.workbookToken] = localBook.id
                val localPage = entry.pageNumber - 1
                val validBook = localBook?.takeIf { localPage in 0 until it.pageCount }
                val localDigest = validBook?.let {
                    runCatching { annotationStore.studentLayerSha256(it.id, localPage) }.getOrNull()
                }
                TeacherPageSyncRecord(
                    syncGeneration = envelope.syncGeneration,
                    pageToken = entry.pageToken,
                    workbookToken = entry.workbookToken,
                    contentSha256 = entry.contentSha256,
                    studentLayerSha256 = entry.studentLayerSha256,
                    workbookLabel = validBook?.title ?: "교재 연결 필요",
                    localBookId = validBook?.id,
                    pageNumber = localPage,
                    attemptNos = entry.attemptNos,
                    submittedAttemptNos = entry.submittedAttemptNos,
                    sourceRevision = entry.revision,
                    appliedRevision = if (localDigest == entry.studentLayerSha256) entry.revision else 0L,
                    appliedStudentLayerSha256 = localDigest,
                    lastChangedAtEpochMs = entry.lastChangedEpochMs,
                    approximateBytes = entry.approxBytes,
                )
            }
            val byToken = pages.associateBy(TeacherPageSyncRecord::pageToken)
            val cursor = envelope.currentCursor?.let { remote ->
                val page = byToken[remote.pageToken] ?: return@let null
                TeacherPageSyncCursorRecord(
                    syncGeneration = envelope.syncGeneration,
                    sequence = remote.sequence,
                    pageToken = page.pageToken,
                    workbookToken = page.workbookToken,
                    contentSha256 = page.contentSha256,
                    pageNumber = page.pageNumber,
                    attemptNo = remote.currentAttemptNo,
                    sourceRevision = remote.revision,
                    updatedAtEpochMs = envelope.createdAtEpochMs,
                )
            }
            when (store.replaceTeacherManifest(
                envelope.syncGeneration,
                envelope.sequence,
                pages,
                cursor,
                envelope.inventoryPageCount,
            )) {
                TeacherManifestInstallResult.STALE -> return RemotePageSyncIncomingResult.ACKNOWLEDGE
                // Preserve the previous safe state, but consume this poison sequence so a later
                // manifest is not starved behind it forever.
                TeacherManifestInstallResult.REGRESSION -> return RemotePageSyncIncomingResult.ACKNOWLEDGE
                TeacherManifestInstallResult.DUPLICATE -> {
                    // The manifest high-water is durable before catalog side effects. Re-run those
                    // idempotent effects so a crash between the two cannot lose submitted attempts.
                    store.teacherPages().filter { it.localBookId != null }.forEach { page ->
                        store.rememberWorkbookMapping(
                            page.workbookToken,
                            requireNotNull(page.localBookId),
                            page.contentSha256,
                        )
                        applyManifestAttempts(page)
                    }
                    bindDeferredTeacherReviews(store.teacherPages(), store.teacherInventoryComplete())
                    publishTeacherCursor()
                    tickTeacher(requireNotNull(session))
                    notifyUiChanged()
                    return RemotePageSyncIncomingResult.ACKNOWLEDGE
                }
                TeacherManifestInstallResult.APPLIED -> Unit
            }
            pendingMappings.forEach { (token, bookId) ->
                val digest = envelope.entries.first { it.workbookToken == token }.contentSha256
                store.rememberWorkbookMapping(token, bookId, digest)
            }
            pages.filter { it.localBookId != null }.forEach(::applyManifestAttempts)
            bindDeferredTeacherReviews(store.teacherPages(), store.teacherInventoryComplete())
            failedPageTokens.retainAll(
                store.teacherPages().mapTo(linkedSetOf(), TeacherPageSyncRecord::pageToken),
            )
            publishTeacherCursor()
            tickTeacher(requireNotNull(session))
            notifyUiChanged()
            RemotePageSyncIncomingResult.ACKNOWLEDGE
        }.getOrElse { RemotePageSyncIncomingResult.RETAIN }
    }

    @Synchronized
    fun receiveRequest(envelope: PageSyncRequestEnvelope): RemotePageSyncIncomingResult {
        if (session?.role != RemoteReviewRole.STUDENT) return RemotePageSyncIncomingResult.DROP
        if (envelope.syncGeneration != store.studentGeneration()) return RemotePageSyncIncomingResult.DROP
        val known = store.studentPage(envelope.pageToken) ?: return RemotePageSyncIncomingResult.DROP
        if (known.syncGeneration != envelope.syncGeneration || known.pageNumber + 1 != envelope.pageNumber) {
            return RemotePageSyncIncomingResult.DROP
        }
        return runCatching {
            val current = refreshStudentPage(known.bookId, known.pageNumber) ?: run {
                val rejection = pageSyncAck(
                    syncGeneration = envelope.syncGeneration,
                    sourceType = PageSyncAckSourceType.REQUEST,
                    sourceTransferId = envelope.transferId,
                    pageToken = envelope.pageToken,
                    pageNumber = envelope.pageNumber,
                    sourceRevision = known.sourceRevision,
                    disposition = PageSyncAckDisposition.REJECTED,
                    reasonCode = "PAGE_UNAVAILABLE",
                )
                if (sendEnvelope(rejection).isDurablyAccepted()) {
                    store.removeStudentPage(envelope.pageToken)
                    scheduleManifestAtRateBoundary()
                    return RemotePageSyncIncomingResult.ACKNOWLEDGE
                }
                return RemotePageSyncIncomingResult.RETAIN
            }
            val reservedTransferId = current.outgoingAnnotationTransferId
            val reservedRequestId = current.responseToRequestTransferId
            if (reservedTransferId != null && reservedRequestId != null) {
                val reservedState = outboundState(
                    RemoteReviewEnvelopeType.PAGE_ANNOTATION.name,
                    reservedTransferId,
                )
                if (reservedRequestId == envelope.transferId && reservedState in setOf(
                        RemotePageSyncOutboundState.PENDING,
                        RemotePageSyncOutboundState.SENT,
                        RemotePageSyncOutboundState.ACKNOWLEDGED,
                    )
                ) {
                    // The exact response is already durable. Settling this replayed request must not
                    // rebuild it from newer handwriting and overwrite its semantic ACK correlation.
                    return RemotePageSyncIncomingResult.ACKNOWLEDGE
                }
                val sameFrozenState = reservedRequestId == envelope.transferId &&
                    current.outgoingSourceRevision == current.sourceRevision &&
                    current.outgoingStateFingerprint == current.stateFingerprint &&
                    current.outgoingResultLayerSha256 == current.studentLayerSha256
                val oldReservationCanBeAbandoned = reservedRequestId != envelope.transferId &&
                    reservedState in setOf(
                        RemotePageSyncOutboundState.NONE,
                        RemotePageSyncOutboundState.FAILED,
                        RemotePageSyncOutboundState.ACKNOWLEDGED,
                    )
                if (oldReservationCanBeAbandoned) {
                    // A different request proves the teacher moved past this response. In
                    // particular, transport ACKNOWLEDGED means its semantic ACK was durably queued;
                    // losing that later ACK must not pin the student's response slot forever.
                    store.abandonStudentAnnotationResponse(current.pageToken, reservedRequestId)
                } else if (!sameFrozenState || reservedState !in setOf(
                        RemotePageSyncOutboundState.NONE,
                    )) {
                    val rejection = pageSyncAck(
                        syncGeneration = envelope.syncGeneration,
                        sourceType = PageSyncAckSourceType.REQUEST,
                        sourceTransferId = envelope.transferId,
                        pageToken = envelope.pageToken,
                        pageNumber = envelope.pageNumber,
                        sourceRevision = current.sourceRevision,
                        disposition = PageSyncAckDisposition.REJECTED,
                        reasonCode = if (reservedState == RemotePageSyncOutboundState.FAILED) {
                            "RESPONSE_FAILED"
                        } else {
                            "RESPONSE_IN_FLIGHT"
                        },
                    ).let { ack ->
                        // The old annotation id is a permanent outbox tombstone. A fresh ACK id is
                        // safe here because it only asks the teacher to issue a new request id.
                        if (reservedState == RemotePageSyncOutboundState.FAILED) {
                            ack.copy(transferId = randomTransferId("page_ack_recovery"))
                        } else ack
                    }
                    if (sendEnvelope(rejection).isDurablyAccepted()) {
                        if (reservedRequestId == envelope.transferId &&
                            (reservedState == RemotePageSyncOutboundState.NONE ||
                                reservedState == RemotePageSyncOutboundState.FAILED)
                        ) {
                            store.abandonStudentAnnotationResponse(current.pageToken, reservedRequestId)
                        }
                        return RemotePageSyncIncomingResult.ACKNOWLEDGE
                    }
                    return RemotePageSyncIncomingResult.RETAIN
                }
            }
            val annotation = buildStudentAnnotationResponse(envelope, current)
            if (reservedRequestId == envelope.transferId && reservedTransferId != null) {
                require(annotation.transferId == reservedTransferId) {
                    "Replayed request produced a different annotation transfer"
                }
            }
            store.markStudentAnnotationInFlight(
                pageToken = current.pageToken,
                requestTransferId = envelope.transferId,
                annotationTransferId = annotation.transferId,
                sourceRevision = annotation.sourceRevision,
                originCursor = if (annotation.kind == PageAnnotationKind.DELTA) {
                    annotation.sourceOriginCursor
                } else current.originDeviceHighWater,
                stateFingerprint = current.stateFingerprint,
                resultLayerSha256 = annotation.resultLayerSha256,
                sentAtEpochMs = nowEpochMs(),
            )
            if (sendEnvelope(annotation).isDurablyAccepted()) {
                RemotePageSyncIncomingResult.ACKNOWLEDGE
            } else RemotePageSyncIncomingResult.RETAIN
        }.getOrElse {
            val current = store.studentPage(envelope.pageToken) ?: known
            val rejection = pageSyncAck(
                syncGeneration = envelope.syncGeneration,
                sourceType = PageSyncAckSourceType.REQUEST,
                sourceTransferId = envelope.transferId,
                pageToken = envelope.pageToken,
                pageNumber = envelope.pageNumber,
                sourceRevision = current.sourceRevision,
                disposition = PageSyncAckDisposition.REJECTED,
                reasonCode = "PAYLOAD_UNAVAILABLE",
            )
            if (runCatching { sendEnvelope(rejection).isDurablyAccepted() }.getOrDefault(false)) {
                RemotePageSyncIncomingResult.ACKNOWLEDGE
            } else RemotePageSyncIncomingResult.RETAIN
        }
    }

    @Synchronized
    fun receiveAnnotation(envelope: PageAnnotationEnvelope): RemotePageSyncIncomingResult {
        return when (session?.role) {
            RemoteReviewRole.STUDENT -> receiveTeacherReview(envelope)
            RemoteReviewRole.TEACHER -> receiveStudentPage(envelope)
            null -> RemotePageSyncIncomingResult.DROP
        }
    }

    @Synchronized
    fun receiveAck(envelope: PageSyncAckEnvelope): RemotePageSyncIncomingResult {
        return when (session?.role) {
            RemoteReviewRole.STUDENT -> {
                if (envelope.sourceType != PageSyncAckSourceType.ANNOTATION) {
                    return RemotePageSyncIncomingResult.DROP
                }
                val accepted = envelope.disposition != PageSyncAckDisposition.REJECTED
                val resolved = store.resolveStudentAnnotationAck(
                    envelope.syncGeneration,
                    envelope.pageToken,
                    envelope.sourceTransferId,
                    envelope.sourceRevision,
                    accepted,
                )
                if (!resolved) return RemotePageSyncIncomingResult.DROP
                store.studentPage(envelope.pageToken)?.let { refreshStudentPage(it.bookId, it.pageNumber) }
                scheduleManifestAtRateBoundary()
                RemotePageSyncIncomingResult.ACKNOWLEDGE
            }
            RemoteReviewRole.TEACHER -> receiveTeacherAck(envelope)
            null -> RemotePageSyncIncomingResult.DROP
        }
    }

    @Synchronized fun uiState(): RemotePageSyncUiState = buildUiState()

    fun addUiListener(listener: (RemotePageSyncUiState) -> Unit): AutoCloseable {
        listeners += listener
        listener(uiState())
        return AutoCloseable { listeners -= listener }
    }

    @Synchronized
    fun startPendingSync(requestedIntervalSeconds: Int) {
        intervalSeconds = normalizeRemotePageSyncInterval(requestedIntervalSeconds)
        manualRunning = true
        preferManualPageNext = true
        requestCooldownUntilElapsedMs = minOf(requestCooldownUntilElapsedMs, nowElapsedMs())
        tick()
        notifyUiChanged()
    }

    @Synchronized fun pausePendingSync() { manualRunning = false; notifyUiChanged() }

    @Synchronized
    fun workbookMappingCandidates(pageToken: String): List<RemoteWorkbookMappingCandidate> {
        if (session?.role != RemoteReviewRole.TEACHER) return emptyList()
        val page = store.teacherPage(pageToken) ?: return emptyList()
        val selectedStudentId = library.state.selectedStudentId
        val siblingPages = store.teacherPages().filter {
            it.workbookToken == page.workbookToken && it.contentSha256 == page.contentSha256
        }
        return library.booksByContentSha256(page.contentSha256)
            .filter { candidate ->
                candidate.studentId == selectedStudentId &&
                    siblingPages.all { it.pageNumber in 0 until candidate.pageCount } &&
                    !store.localBookActivelyClaimedByDifferentWorkbook(
                        page.workbookToken,
                        candidate.id,
                        page.contentSha256,
                    )
            }
            .map { RemoteWorkbookMappingCandidate(it.id, it.title, it.pageCount) }
    }

    @Synchronized
    fun bindWorkbookMapping(pageToken: String, localBookId: String): Boolean {
        if (session?.role != RemoteReviewRole.TEACHER) return false
        val page = store.teacherPage(pageToken) ?: return false
        val candidate = workbookMappingCandidates(pageToken).firstOrNull {
            it.localBookId == localBookId
        } ?: return false
        val rebound = store.rebindTeacherWorkbook(
            page.workbookToken,
            candidate.localBookId,
            page.contentSha256,
            candidate.title,
            store.teacherPages()
                .filter { it.workbookToken == page.workbookToken && it.contentSha256 == page.contentSha256 }
                .associate { remotePage ->
                    remotePage.pageToken to annotationStore.studentLayerSha256(
                        candidate.localBookId,
                        remotePage.pageNumber,
                    )
                },
        )
        rebound.forEach(::applyManifestAttempts)
        bindDeferredTeacherReviewsAfterExplicitMapping(
            page.workbookToken,
            candidate.localBookId,
            rebound,
        )
        failedPageTokens.removeAll(rebound.map(TeacherPageSyncRecord::pageToken).toSet())
        publishTeacherCursor()
        session?.let(::tickTeacher)
        notifyUiChanged()
        return true
    }

    @Synchronized
    fun onDataRootReplaced() {
        store.resetCurrentPair()
        currentPresence = null
        seededStudentBooks.clear()
        failedPageTokens.clear()
        failedTeacherReviewKeys.clear()
        manualRunning = false
        requestCooldownUntilElapsedMs = 0L
        lastRequestedPageToken = null
        preferManualPageNext = false
        manifestDueAtElapsedMs = if (telegramActive) nowElapsedMs() else Long.MAX_VALUE
        manifestBatchesRemaining = if (telegramActive) {
            requiredManifestBatchCount(store.studentPages().size)
        } else {
            0
        }
        RemoteStudentCursorBus.clear(RemoteStudentCursorTransport.TELEGRAM)
        notifyUiChanged()
    }

    @Synchronized
    fun close() {
        listeners.clear()
        RemoteStudentCursorBus.clear(RemoteStudentCursorTransport.TELEGRAM)
    }

    private fun tickStudent(currentSession: RemotePageSyncSession) {
        if (!telegramOnline) return
        if (store.studentGeneration() == 0L) {
            store.beginStudentGeneration()
            seededStudentBooks.clear()
        }
        reconcileStudentInventory()
        val outstanding = store.outstandingStudentManifest()
        if (outstanding != null) {
            when (outboundState(RemoteReviewEnvelopeType.PAGE_SYNC_MANIFEST.name, outstanding.transferId)) {
                RemotePageSyncOutboundState.ACKNOWLEDGED -> {
                    store.acknowledgeOutstandingStudentManifest(outstanding.transferId)
                    if (manifestBatchesRemaining <= 0) {
                        manifestBatchesRemaining = requiredManifestBatchCount(store.studentPages().size)
                    }
                    manifestBatchesRemaining = (manifestBatchesRemaining - 1).coerceAtLeast(0)
                    manifestDueAtElapsedMs = if (manifestBatchesRemaining > 0) {
                        safeAdd(nowElapsedMs(), MANIFEST_INTERVAL_MS)
                    } else {
                        Long.MAX_VALUE
                    }
                }
                RemotePageSyncOutboundState.PENDING,
                RemotePageSyncOutboundState.SENT,
                -> return
                RemotePageSyncOutboundState.NONE -> {
                    // A reservation does not persist a byte snapshot. Never rebuild a possibly
                    // different manifest under the same transfer id after a crash/journal loss.
                    store.clearOutstandingStudentManifest(outstanding.transferId)
                    manifestDueAtElapsedMs = nowElapsedMs()
                    return
                }
                RemotePageSyncOutboundState.FAILED -> {
                    // A terminal uploader failure may remain visible for many ticks. Back off the
                    // replacement transfer so a permanent 4xx or local file error cannot create a
                    // new Telegram document and disk file every second.
                    store.clearOutstandingStudentManifest(outstanding.transferId)
                    manifestDueAtElapsedMs = safeAdd(nowElapsedMs(), SEND_RETRY_MS)
                    return
                }
            }
        }
        if (nowElapsedMs() < manifestDueAtElapsedMs) return
        currentPresence?.takeIf(StudentStudyPresence::active)?.let { presence ->
            refreshStudentPage(requireNotNull(presence.bookId), requireNotNull(presence.pageNumber) - 1)
        }
        if (manifestBatchesRemaining <= 0) {
            manifestBatchesRemaining = requiredManifestBatchCount(store.studentPages().size)
        }
        val reservation = store.reserveStudentManifest(randomTransferId("manifest"), nowEpochMs())
        val manifest = buildManifest(reservation)
        if (runCatching { sendEnvelope(manifest).isDurablyAccepted() }.getOrDefault(false)) {
            lastManifestSentAtElapsedMs = nowElapsedMs()
            // Do not advance the inventory window until the gateway durably acknowledges this
            // document. A terminal uploader failure must retry without losing that window.
            manifestDueAtElapsedMs = Long.MAX_VALUE
        } else {
            store.clearOutstandingStudentManifest(reservation.transferId)
            manifestDueAtElapsedMs = safeAdd(nowElapsedMs(), SEND_RETRY_MS)
        }
    }

    private fun buildManifest(reservation: StudentManifestReservation): PageSyncManifestEnvelope {
        val presence = currentPresence?.takeIf(StudentStudyPresence::active)
        val currentToken = presence?.let { p ->
            pageToken(
                requireNotNull(session).pairId,
                requireNotNull(p.bookId),
                requireNotNull(p.pageNumber) - 1,
                reservation.syncGeneration,
            )
        }
        val pagesByToken = store.studentPages().associateBy(StudentPageSyncRecord::pageToken)
        val ordered = selectManifestPageTokens(
            pagesByToken.keys,
            currentToken,
            reservation.windowOrdinal,
        ).mapNotNull(pagesByToken::get)
        val entries = ordered.map { page ->
            PageSyncManifestEntry(
                pageToken = page.pageToken,
                workbookToken = page.workbookToken,
                contentSha256 = page.contentSha256,
                studentLayerSha256 = page.studentLayerSha256,
                pageNumber = page.pageNumber + 1,
                attemptNos = page.attemptNos,
                submittedAttemptNos = page.submittedAttemptNos,
                revision = page.sourceRevision,
                lastChangedEpochMs = page.lastChangedAtEpochMs,
                approxBytes = page.approximateBytes.coerceAtMost(RemoteReviewLimits.MAX_PAGE_SYNC_APPROX_BYTES),
            )
        }
        val current = currentToken?.let { token -> ordered.firstOrNull { it.pageToken == token } }
        val cursorAttempt = presence?.attemptNo?.takeIf { attempt -> current?.attemptNos?.contains(attempt) == true }
        return PageSyncManifestEnvelope(
            transferId = reservation.transferId,
            createdAtEpochMs = reservation.createdAtEpochMs,
            syncGeneration = reservation.syncGeneration,
            sequence = reservation.sequence,
            currentCursor = current?.let { page ->
                PageSyncCursor(
                    sequence = reservation.sequence,
                    pageToken = page.pageToken,
                    pageNumber = page.pageNumber + 1,
                    currentAttemptNo = cursorAttempt,
                    revision = page.sourceRevision,
                )
            },
            entries = entries,
            inventoryPageCount = store.studentPages().size,
        )
    }

    private fun tickTeacher(currentSession: RemotePageSyncSession) {
        if (!telegramOnline) return
        if (sendOnePendingTeacherReview()) return
        if (nowElapsedMs() < requestCooldownUntilElapsedMs) return
        val active = store.teacherPages().firstOrNull { it.requestTransferId != null }
        if (active != null) {
            val transferId = requireNotNull(active.requestTransferId)
            when (outboundState(RemoteReviewEnvelopeType.PAGE_SYNC_REQUEST.name, transferId)) {
                RemotePageSyncOutboundState.PENDING,
                RemotePageSyncOutboundState.SENT,
                -> return
                RemotePageSyncOutboundState.ACKNOWLEDGED -> {
                    val now = nowEpochMs()
                    val acknowledgedAt = active.requestTransportAcknowledgedAtEpochMs
                        ?: store.markTeacherRequestTransportAcknowledged(
                            active.pageToken,
                            transferId,
                            now,
                        )?.requestTransportAcknowledgedAtEpochMs
                        ?: now
                    if (now >= acknowledgedAt && now - acknowledgedAt < APPLICATION_ACK_RECOVERY_MS) return
                    store.clearTeacherRequest(active.pageToken, transferId)
                    requestCooldownUntilElapsedMs = safeAdd(nowElapsedMs(), SEND_RETRY_MS)
                    return
                }
                RemotePageSyncOutboundState.NONE,
                RemotePageSyncOutboundState.FAILED,
                -> {
                    store.clearTeacherRequest(active.pageToken, transferId)
                    requestCooldownUntilElapsedMs = safeAdd(nowElapsedMs(), SEND_RETRY_MS)
                    return
                }
            }
        }
        val pending = store.pendingTeacherPages().filterNot(TeacherPageSyncRecord::mappingRequired)
        if (pending.isEmpty()) {
            if (manualRunning && store.teacherInventoryComplete()) {
                manualRunning = false
                notifyUiChanged()
            }
            return
        }
        val automaticTokens = automaticPageTokens()
        val next = selectNextTeacherPage(
            pending,
            automaticTokens,
            manualRunning,
            failedPageTokens,
            lastRequestedPageToken,
            preferManualPageNext,
        ) ?: return
        val requesterRevision = if (next.forceCheckpoint) 0L else next.appliedRevision
        val transferId = randomTransferId("page_request")
        val reserved = store.reserveTeacherRequest(
            next.pageToken,
            transferId,
            nowEpochMs(),
            next.sourceRevision,
            requesterRevision,
        ) ?: return
        lastRequestedPageToken = next.pageToken
        if (manualRunning) preferManualPageNext = next.pageToken in automaticTokens
        if (runCatching { sendEnvelope(reserved.toRequestEnvelope()).isDurablyAccepted() }.getOrDefault(false)) {
            failedPageTokens -= next.pageToken
            notifyUiChanged()
        } else {
            requestCooldownUntilElapsedMs = safeAdd(nowElapsedMs(), SEND_RETRY_MS)
        }
    }

    private fun TeacherPageSyncRecord.toRequestEnvelope() = PageSyncRequestEnvelope(
        transferId = requireNotNull(requestTransferId),
        createdAtEpochMs = requireNotNull(requestCreatedAtEpochMs),
        syncGeneration = syncGeneration,
        pageToken = pageToken,
        pageNumber = pageNumber + 1,
        attemptNo = null,
        requesterRevision = requesterRevision,
    )

    private fun sendOnePendingTeacherReview(): Boolean {
        if (nowElapsedMs() < nextTeacherReviewSendAtElapsedMs) return false
        val pendingReviews = store.pendingTeacherReviews()
        val inFlight = pendingReviews.firstOrNull(PendingTeacherReviewRecord::inFlight)
        if (inFlight != null) {
            val pending = inFlight
            if (pending.inFlightSyncGeneration != store.teacherManifestGeneration() ||
                store.teacherPage(pending.inFlightPageToken.orEmpty()) == null
            ) {
                store.expirePendingTeacherReview(pending.key, nowEpochMs())
                return true
            }
            val state = outboundState(
                RemoteReviewEnvelopeType.PAGE_ANNOTATION.name,
                requireNotNull(pending.inFlightTransferId),
            )
            when (state) {
                RemotePageSyncOutboundState.PENDING,
                RemotePageSyncOutboundState.SENT,
                -> return false
                RemotePageSyncOutboundState.ACKNOWLEDGED -> {
                    // The receiver transport-ACKs only after its semantic ACK is durably queued.
                    // Retaining this one logical transfer cannot create an offline burst.
                    val now = nowEpochMs()
                    val acknowledgedAt = pending.transportAcknowledgedAtEpochMs
                        ?: store.markPendingTeacherReviewTransportAcknowledged(
                            pending.key,
                            requireNotNull(pending.inFlightTransferId),
                            now,
                        )?.transportAcknowledgedAtEpochMs
                        ?: now
                    if (now >= acknowledgedAt && now - acknowledgedAt < APPLICATION_ACK_RECOVERY_MS) return false
                    store.expirePendingTeacherReview(pending.key, nowEpochMs())
                    nextTeacherReviewSendAtElapsedMs = safeAdd(nowElapsedMs(), SEND_RETRY_MS)
                    return true
                }
                RemotePageSyncOutboundState.FAILED -> {
                    store.expirePendingTeacherReview(pending.key, nowEpochMs())
                    nextTeacherReviewSendAtElapsedMs = safeAdd(nowElapsedMs(), SEND_RETRY_MS)
                    return true
                }
                RemotePageSyncOutboundState.NONE -> {
                    val rebuilt = buildTeacherReviewEnvelope(pending) ?: run {
                        store.expirePendingTeacherReview(pending.key, nowEpochMs())
                        failedTeacherReviewKeys += pending.key
                        nextTeacherReviewSendAtElapsedMs = safeAdd(nowElapsedMs(), SEND_RETRY_MS)
                        return true
                    }
                    if (pageAnnotationSha256Hex(rebuilt.copyDecodedPayloadBytes()) != pending.inFlightPayloadSha256 ||
                        rebuilt.resultLayerSha256 != pending.inFlightResultLayerSha256 ||
                        !runCatching { sendEnvelope(rebuilt).isDurablyAccepted() }.getOrDefault(false)
                    ) {
                        store.expirePendingTeacherReview(pending.key, nowEpochMs())
                        failedTeacherReviewKeys += pending.key
                        nextTeacherReviewSendAtElapsedMs = safeAdd(nowElapsedMs(), SEND_RETRY_MS)
                    }
                    return true
                }
            }
        }
        failedTeacherReviewKeys.retainAll(
            pendingReviews.mapTo(linkedSetOf(), PendingTeacherReviewRecord::key),
        )
        val selection = selectTransmittableTeacherReview(
            pendingReviews,
            store.teacherPages(),
            failedTeacherReviewKeys,
        ) ?: return false
        val pending = selection.pending
        val manifestPage = selection.page
        val transferId = stableTransferId(
            "teacher_review",
            "${pending.intentId}:${pending.retryCount}:${manifestPage.syncGeneration}:${manifestPage.pageToken}",
        )
        val revision = reserveTeacherReviewRevision(manifestPage.pageToken, transferId)
        val envelope = buildTeacherReviewEnvelope(
            pending,
            manifestPage,
            transferId,
            revision,
        ) ?: run {
            failedTeacherReviewKeys += pending.key
            nextTeacherReviewSendAtElapsedMs = safeAdd(nowElapsedMs(), SEND_RETRY_MS)
            return true
        }
        failedTeacherReviewKeys -= pending.key
        val payloadSha = pageAnnotationSha256Hex(envelope.copyDecodedPayloadBytes())
        store.reservePendingTeacherReview(
            pending.key,
            manifestPage.syncGeneration,
            manifestPage.pageToken,
            transferId,
            revision,
            payloadSha,
            envelope.resultLayerSha256,
            nowEpochMs(),
        )
        if (runCatching { sendEnvelope(envelope).isDurablyAccepted() }.getOrDefault(false)) {
            nextTeacherReviewSendAtElapsedMs = safeAdd(nowElapsedMs(), TEACHER_REVIEW_INTERVAL_MS)
        } else nextTeacherReviewSendAtElapsedMs = safeAdd(nowElapsedMs(), SEND_RETRY_MS)
        return true
    }

    private fun buildTeacherReviewEnvelope(pending: PendingTeacherReviewRecord): PageAnnotationEnvelope? {
        val page = store.teacherPage(pending.inFlightPageToken.orEmpty()) ?: return null
        return buildTeacherReviewEnvelope(
            pending,
            page,
            requireNotNull(pending.inFlightTransferId),
            pending.inFlightSourceRevision,
        )
    }

    private fun buildTeacherReviewEnvelope(
        pending: PendingTeacherReviewRecord,
        manifestPage: TeacherPageSyncRecord,
        transferId: String,
        sourceRevision: Long,
    ): PageAnnotationEnvelope? = runCatching {
        val book = library.book(pending.bookId)
        require(book.contentSha256.lowercase() == pending.contentSha256)
        val artifact = requireNotNull(
            annotationStore.teacherReviewPublicationArtifact(
                book.id,
                pending.pageNumber,
                pending.attemptNo,
                pending.intentId,
            ),
        )
        val payload = RemoteTeacherPageReviewCodec.encode(
            pageNumber = pending.pageNumber + 1,
            attemptNo = pending.attemptNo,
            publishedTeacherCheckpoint = artifact.copyCheckpointBytes(),
            markGroups = artifact.markGroups,
        )
        PageAnnotationEnvelope.fromDecodedPayload(
            transferId = transferId,
            createdAtEpochMs = pending.queuedAtEpochMs,
            syncGeneration = manifestPage.syncGeneration,
            purpose = PageAnnotationPurpose.TEACHER_REVIEW,
            responseToTransferId = null,
            pageToken = manifestPage.pageToken,
            pageNumber = pending.pageNumber + 1,
            attemptNos = listOf(pending.attemptNo),
            kind = PageAnnotationKind.CHECKPOINT,
            baseRevision = 0L,
            sourceRevision = sourceRevision,
            deltaOriginDeviceId = null,
            baseOriginCursor = 0L,
            sourceOriginCursor = 0L,
            compression = PageAnnotationCompression.GZIP,
            decodedPayloadBytes = payload,
            resultLayerSha256 = artifact.intent.resultLayerSha256,
        )
    }.getOrNull()

    private fun receiveStudentPage(envelope: PageAnnotationEnvelope): RemotePageSyncIncomingResult {
        if (envelope.purpose != PageAnnotationPurpose.STUDENT_PAGE ||
            envelope.syncGeneration != store.teacherManifestGeneration()
        ) return RemotePageSyncIncomingResult.DROP
        val record = store.teacherPage(envelope.pageToken) ?: return RemotePageSyncIncomingResult.DROP
        if (record.localBookId == null || record.pageNumber + 1 != envelope.pageNumber) {
            return RemotePageSyncIncomingResult.DROP
        }
        val activeCorrelation = record.requestTransferId == envelope.responseToTransferId
        val completedCorrelation = record.lastCompletedRequestTransferId == envelope.responseToTransferId &&
            record.lastCompletedAnnotationTransferId == envelope.transferId
        if (!activeCorrelation && !completedCorrelation) return RemotePageSyncIncomingResult.DROP
        if (envelope.sourceRevision <= record.appliedRevision) {
            if (envelope.sourceRevision == record.appliedRevision &&
                envelope.resultLayerSha256 != record.appliedStudentLayerSha256
            ) return rejectStudentAnnotation(envelope, "REVISION_DIGEST_MISMATCH", false)
            return acknowledgeAnnotation(envelope, PageSyncAckDisposition.DUPLICATE)
        }
        if (!activeCorrelation) return RemotePageSyncIncomingResult.DROP
        if (envelope.sourceRevision < record.requestedSourceRevision) {
            return rejectStudentAnnotation(envelope, "OUTDATED_RESPONSE", true)
        }
        if (envelope.kind == PageAnnotationKind.DELTA && envelope.baseRevision != record.requesterRevision) {
            return rejectStudentAnnotation(envelope, "BASE_REVISION_MISMATCH", true)
        }
        val applied = runCatching {
            val layerSha = when (envelope.kind) {
                PageAnnotationKind.DELTA -> annotationStore.applyEncodedStudentDelta(
                    localBookId = record.localBookId,
                    pageNumber = record.pageNumber,
                    encodedOperations = RemotePageDeltaCodec.decode(envelope.copyDecodedPayloadBytes()),
                    expectedOriginDeviceId = requireNotNull(envelope.deltaOriginDeviceId),
                    baseOriginCursor = envelope.baseOriginCursor,
                    sourceOriginCursor = envelope.sourceOriginCursor,
                    allowedAttemptNos = envelope.attemptNos,
                    expectedResultLayerSha256 = envelope.resultLayerSha256,
                ).layerSha256
                PageAnnotationKind.CHECKPOINT -> annotationStore.applyStudentLayerCheckpoint(
                    localBookId = record.localBookId,
                    pageNumber = record.pageNumber,
                    checkpointBytes = envelope.copyDecodedPayloadBytes(),
                    expectedResultLayerSha256 = envelope.resultLayerSha256,
                    allowedAttemptNos = envelope.attemptNos,
                ).layerSha256
            }
            applyObservedAttempts(record, envelope.attemptNos)
            layerSha
        }.getOrNull() ?: return rejectStudentAnnotation(envelope, "APPLY_FAILED", true)
        if (applied != envelope.resultLayerSha256) {
            return rejectStudentAnnotation(envelope, "RESULT_DIGEST_MISMATCH", true)
        }
        store.recordTeacherPageApplied(
            record.pageToken,
            envelope.sourceRevision,
            envelope.resultLayerSha256,
            envelope.attemptNos,
            requireNotNull(envelope.responseToTransferId),
            envelope.transferId,
        )
        failedPageTokens -= envelope.pageToken
        requestCooldownUntilElapsedMs = safeAdd(nowElapsedMs(), intervalSeconds.toLong() * 1_000L)
        RemoteStudentPageAppliedBus.publish(
            RemoteStudentPageApplied(record.localBookId, record.pageNumber, envelope.sourceRevision, envelope.transferId),
        )
        publishTeacherCursor()
        notifyUiChanged()
        return acknowledgeAnnotation(envelope, PageSyncAckDisposition.APPLIED)
    }

    private fun receiveTeacherReview(envelope: PageAnnotationEnvelope): RemotePageSyncIncomingResult {
        if (envelope.purpose != PageAnnotationPurpose.TEACHER_REVIEW ||
            envelope.syncGeneration != store.studentGeneration()
        ) return RemotePageSyncIncomingResult.DROP
        val page = store.studentPage(envelope.pageToken) ?: return RemotePageSyncIncomingResult.DROP
        if (page.pageNumber + 1 != envelope.pageNumber) return RemotePageSyncIncomingResult.DROP
        val attemptNo = envelope.attemptNos.single()
        if (attemptNo !in page.submittedAttemptNos) {
            return acknowledgeAnnotation(envelope, PageSyncAckDisposition.REJECTED, "ATTEMPT_NOT_SUBMITTED")
        }
        val appliedReview = store.appliedTeacherReview(envelope.pageToken, attemptNo)
        if (appliedReview != null && envelope.sourceRevision < appliedReview.sourceRevision) {
            return acknowledgeAnnotation(envelope, PageSyncAckDisposition.DUPLICATE)
        }
        if (appliedReview != null && envelope.sourceRevision == appliedReview.sourceRevision) {
            return if (
                appliedReview.payloadSha256 == envelope.payloadSha256 &&
                appliedReview.resultLayerSha256 == envelope.resultLayerSha256
            ) {
                acknowledgeAnnotation(envelope, PageSyncAckDisposition.DUPLICATE)
            } else {
                acknowledgeAnnotation(
                    envelope,
                    PageSyncAckDisposition.REJECTED,
                    "REVISION_PAYLOAD_MISMATCH",
                )
            }
        }
        val payload = runCatching {
            val decoded = RemoteTeacherPageReviewCodec.decode(envelope.copyDecodedPayloadBytes())
            require(decoded.pageNumber == envelope.pageNumber && decoded.attemptNo == attemptNo)
            require(library.attempts(page.bookId, page.pageNumber).any { it.attemptNo == attemptNo && it.locked })
            val layer = annotationStore.applyPublishedTeacherLayerCheckpoint(
                localBookId = page.bookId,
                pageNumber = page.pageNumber,
                attemptNo = attemptNo,
                checkpointBytes = decoded.copyPublishedTeacherCheckpoint(),
                expectedResultLayerSha256 = envelope.resultLayerSha256,
            )
            require(layer.layerSha256 == envelope.resultLayerSha256)
            library.upsertMarkGroupAttemptsFromSync(
                bookId = page.bookId,
                pageNumber = page.pageNumber,
                attemptNo = attemptNo,
                incoming = decoded.markGroups.map { remote ->
                    remote.copy(bookId = page.bookId, pageNumber = page.pageNumber)
                },
            )
            store.recordTeacherReviewApplied(
                envelope.pageToken,
                attemptNo,
                envelope.sourceRevision,
                envelope.payloadSha256,
                envelope.resultLayerSha256,
            )
            decoded
        }.getOrNull() ?: return acknowledgeAnnotation(
            envelope,
            PageSyncAckDisposition.REJECTED,
            "REVIEW_APPLY_FAILED",
        )
        RemoteReviewFeedbackBus.publish(
            RemoteTeacherFeedbackApplied(
                bookId = page.bookId,
                pageNumber = page.pageNumber,
                attemptNo = attemptNo,
                transferId = envelope.transferId,
                basedOnStudentRevision = page.sourceRevision,
            ),
        )
        payload.markGroups.forEach { group ->
            if (group.hiddenAtEpochMillis != null) return@forEach
            val latest = group.marks.lastOrNull { it.attemptNo == attemptNo && it.hiddenAtEpochMillis == null }
                ?: return@forEach
            RemoteGradeAppliedBus.publish(
                RemoteGradeApplied(
                    page.bookId,
                    page.pageNumber,
                    attemptNo,
                    group.id,
                    latest.color == MarkColor.BLUE,
                ),
            )
        }
        return acknowledgeAnnotation(envelope, PageSyncAckDisposition.APPLIED)
    }

    private fun receiveTeacherAck(envelope: PageSyncAckEnvelope): RemotePageSyncIncomingResult {
        if (envelope.sourceType == PageSyncAckSourceType.ANNOTATION) {
            val accepted = envelope.disposition != PageSyncAckDisposition.REJECTED
            val pending = store.pendingTeacherReviews().firstOrNull {
                it.inFlightSyncGeneration == envelope.syncGeneration &&
                    it.inFlightPageToken == envelope.pageToken &&
                    it.inFlightTransferId == envelope.sourceTransferId &&
                    it.inFlightSourceRevision == envelope.sourceRevision
            }
            if (store.resolvePendingTeacherReview(
                    envelope.syncGeneration,
                    envelope.pageToken,
                    envelope.sourceTransferId,
                    envelope.sourceRevision,
                    accepted,
                    nowEpochMs(),
                )
            ) {
                pending?.let { matched ->
                    if (accepted) failedTeacherReviewKeys -= matched.key
                    else failedTeacherReviewKeys += matched.key
                }
                if (accepted && pending != null) {
                    annotationStore.removeTeacherReviewPublishIntent(
                        pending.bookId,
                        pending.pageNumber,
                        pending.attemptNo,
                        pending.intentId,
                    )
                }
                nextTeacherReviewSendAtElapsedMs = safeAdd(
                    nowElapsedMs(),
                    if (accepted) TEACHER_REVIEW_INTERVAL_MS else SEND_RETRY_MS,
                )
                notifyUiChanged()
                return RemotePageSyncIncomingResult.ACKNOWLEDGE
            }
            return RemotePageSyncIncomingResult.DROP
        }
        if (envelope.sourceType != PageSyncAckSourceType.REQUEST) return RemotePageSyncIncomingResult.DROP
        val record = store.teacherPage(envelope.pageToken) ?: return RemotePageSyncIncomingResult.DROP
        if (record.syncGeneration != envelope.syncGeneration || record.pageNumber + 1 != envelope.pageNumber ||
            record.requestTransferId != envelope.sourceTransferId
        ) return RemotePageSyncIncomingResult.DROP
        if (envelope.disposition == PageSyncAckDisposition.REJECTED) {
            failedPageTokens += record.pageToken
        }
        if (envelope.disposition == PageSyncAckDisposition.REJECTED &&
            envelope.reasonCode == "PAGE_UNAVAILABLE"
        ) {
            store.removeTeacherPage(record.pageToken)
            failedPageTokens -= record.pageToken
            publishTeacherCursor()
            notifyUiChanged()
            return RemotePageSyncIncomingResult.ACKNOWLEDGE
        }
        store.clearTeacherRequest(
            record.pageToken,
            envelope.sourceTransferId,
            forceCheckpoint = envelope.disposition == PageSyncAckDisposition.REJECTED,
        )
        requestCooldownUntilElapsedMs = safeAdd(nowElapsedMs(), SEND_RETRY_MS)
        notifyUiChanged()
        return RemotePageSyncIncomingResult.ACKNOWLEDGE
    }

    private fun buildStudentAnnotationResponse(
        request: PageSyncRequestEnvelope,
        page: StudentPageSyncRecord,
    ): PageAnnotationEnvelope {
        val canDelta = request.requesterRevision > 0L &&
            request.requesterRevision == page.acknowledgedRevision &&
            request.requesterRevision < page.sourceRevision &&
            page.acknowledgedOriginCursor < page.originDeviceHighWater &&
            page.attemptNos.isNotEmpty()
        if (canDelta) {
            val operations = annotationStore.encodedOperationsAfter(
                page.bookId,
                page.pageNumber,
                library.deviceId,
                page.acknowledgedOriginCursor,
                includeTeacherDrafts = false,
            )
            val lastClock = operations.lastOrNull()?.let(annotationStore::operationCursor)?.logicalClock
            val decoded = runCatching { RemotePageDeltaCodec.encode(operations) }.getOrNull()
            if (decoded != null && decoded.size <= RemoteReviewLimits.PAGE_ANNOTATION_DELTA_TARGET_BYTES &&
                lastClock == page.originDeviceHighWater
            ) {
                return PageAnnotationEnvelope.fromDecodedPayload(
                    transferId = stableTransferId(
                        "page_delta",
                        "${request.transferId}:${page.sourceRevision}:${page.studentLayerSha256}",
                    ),
                    createdAtEpochMs = nowEpochMs(),
                    syncGeneration = page.syncGeneration,
                    purpose = PageAnnotationPurpose.STUDENT_PAGE,
                    responseToTransferId = request.transferId,
                    pageToken = page.pageToken,
                    pageNumber = page.pageNumber + 1,
                    attemptNos = page.attemptNos,
                    kind = PageAnnotationKind.DELTA,
                    baseRevision = request.requesterRevision,
                    sourceRevision = page.sourceRevision,
                    deltaOriginDeviceId = library.deviceId,
                    baseOriginCursor = page.acknowledgedOriginCursor,
                    sourceOriginCursor = page.originDeviceHighWater,
                    compression = PageAnnotationCompression.GZIP,
                    decodedPayloadBytes = decoded,
                    resultLayerSha256 = page.studentLayerSha256,
                )
            }
        }
        val export = annotationStore.exportStudentLayerCheckpoint(page.bookId, page.pageNumber, library.deviceId)
        require(export.layerSha256 == page.studentLayerSha256)
        return PageAnnotationEnvelope.fromDecodedPayload(
            transferId = stableTransferId(
                "page_checkpoint",
                "${request.transferId}:${page.sourceRevision}:${page.studentLayerSha256}",
            ),
            createdAtEpochMs = nowEpochMs(),
            syncGeneration = page.syncGeneration,
            purpose = PageAnnotationPurpose.STUDENT_PAGE,
            responseToTransferId = request.transferId,
            pageToken = page.pageToken,
            pageNumber = page.pageNumber + 1,
            attemptNos = page.attemptNos,
            kind = PageAnnotationKind.CHECKPOINT,
            baseRevision = 0L,
            sourceRevision = page.sourceRevision,
            deltaOriginDeviceId = null,
            baseOriginCursor = 0L,
            sourceOriginCursor = 0L,
            compression = PageAnnotationCompression.GZIP,
            decodedPayloadBytes = export.copyCheckpointBytes(),
            resultLayerSha256 = export.layerSha256,
        )
    }

    private fun rejectStudentAnnotation(
        envelope: PageAnnotationEnvelope,
        reasonCode: String,
        forceCheckpoint: Boolean,
    ): RemotePageSyncIncomingResult {
        failedPageTokens += envelope.pageToken
        val result = acknowledgeAnnotation(envelope, PageSyncAckDisposition.REJECTED, reasonCode)
        if (result == RemotePageSyncIncomingResult.ACKNOWLEDGE) {
            store.clearTeacherRequest(
                envelope.pageToken,
                envelope.responseToTransferId,
                forceCheckpoint = forceCheckpoint,
            )
            requestCooldownUntilElapsedMs = safeAdd(nowElapsedMs(), SEND_RETRY_MS)
        }
        notifyUiChanged()
        return result
    }

    private fun acknowledgeAnnotation(
        envelope: PageAnnotationEnvelope,
        disposition: PageSyncAckDisposition,
        reasonCode: String? = null,
    ): RemotePageSyncIncomingResult {
        val ack = pageSyncAck(
            envelope.syncGeneration,
            PageSyncAckSourceType.ANNOTATION,
            envelope.transferId,
            envelope.pageToken,
            envelope.pageNumber,
            envelope.sourceRevision,
            disposition,
            reasonCode,
        )
        return if (runCatching { sendEnvelope(ack).isDurablyAccepted() }.getOrDefault(false)) {
            RemotePageSyncIncomingResult.ACKNOWLEDGE
        } else RemotePageSyncIncomingResult.RETAIN
    }

    private fun pageSyncAck(
        syncGeneration: Long,
        sourceType: PageSyncAckSourceType,
        sourceTransferId: String,
        pageToken: String,
        pageNumber: Int,
        sourceRevision: Long,
        disposition: PageSyncAckDisposition,
        reasonCode: String?,
    ) = PageSyncAckEnvelope(
        transferId = stableTransferId(
            "page_ack",
            "$syncGeneration:$sourceType:$sourceTransferId:$disposition:${reasonCode.orEmpty()}",
        ),
        createdAtEpochMs = nowEpochMs(),
        syncGeneration = syncGeneration,
        sourceType = sourceType,
        sourceTransferId = sourceTransferId,
        pageToken = pageToken,
        pageNumber = pageNumber,
        sourceRevision = sourceRevision,
        disposition = disposition,
        reasonCode = reasonCode,
    )

    private fun refreshStudentPage(bookId: String, pageNumber: Int): StudentPageSyncRecord? {
        val currentSession = session?.takeIf { it.role == RemoteReviewRole.STUDENT } ?: return null
        val generation = store.studentGeneration().takeIf { it > 0L } ?: return null
        val state = library.state
        val book = state.books.firstOrNull {
            it.id == bookId && it.studentId == state.selectedStudentId
        } ?: return null
        if (pageNumber !in 0 until book.pageCount || book.contentSha256.length != 64) return null
        val attempts = library.attempts(bookId, pageNumber)
        val snapshot = annotationStore.loadPage(bookId, pageNumber)
        val attemptNos = (attempts.map(Attempt::attemptNo) + snapshot.activeStrokes.asSequence()
            .filter { it.authorId == "student" && it.attemptNo > 0 }
            .map { it.attemptNo })
            .distinct().sorted()
        if (attemptNos.size > RemoteReviewLimits.MAX_PAGE_SYNC_ATTEMPTS_PER_PAGE) return null
        val submitted = attempts.asSequence().filter(Attempt::locked).map(Attempt::attemptNo)
            .filter { it in attemptNos }.distinct().sorted().toList()
        val stats = annotationStore.pageOperationSyncStats(
            bookId,
            pageNumber,
            library.deviceId,
            afterLogicalClock = 0L,
            includeTeacherDrafts = false,
        )
        val token = pageToken(currentSession.pairId, bookId, pageNumber, generation)
        val layerSha = annotationStore.studentLayerSha256(bookId, pageNumber)
        val previous = store.studentPage(token)
        val newFingerprint = pageStateFingerprint(layerSha, attemptNos, submitted)
        val historicalChangedAt = maxOf(
            stats.lastMutationEpochMillis,
            attempts.mapNotNull(Attempt::lockedAtEpochMillis).maxOrNull() ?: 0L,
            attempts.maxOfOrNull(Attempt::startedAtEpochMillis) ?: 0L,
            snapshot.activeStrokes.asSequence()
                .filter { it.authorId == "student" }
                .maxOfOrNull { it.createdAtEpochMillis } ?: 0L,
        )
        val changedAt = resolveStudentPageChangedAt(
            previousFingerprint = previous?.stateFingerprint,
            previousChangedAtEpochMs = previous?.lastChangedAtEpochMs ?: 0L,
            currentFingerprint = newFingerprint,
            historicalChangedAtEpochMs = historicalChangedAt,
            nowEpochMs = nowEpochMs(),
        )
        return store.updateStudentPage(
            pageToken = token,
            workbookToken = workbookToken(currentSession.pairId, bookId),
            bookId = bookId,
            contentSha256 = book.contentSha256.lowercase(),
            studentLayerSha256 = layerSha,
            workbookLabel = book.title,
            pageNumber = pageNumber,
            attemptNos = attemptNos,
            submittedAttemptNos = submitted,
            originDeviceHighWater = stats.originDeviceHighWater,
            lastChangedAtEpochMs = changedAt,
            approximateBytes = stats.logByteCount.coerceAtLeast(stats.pendingEncodedByteCount),
        )
    }

    private fun seedStudentBook(bookId: String) {
        if (!seededStudentBooks.add(bookId)) return
        library.attemptsForSync(bookId).map(Attempt::pageNumber).distinct().forEach { refreshStudentPage(bookId, it) }
    }

    /** Seeds the complete attempted-page catalog once per fallback generation. */
    private fun seedAllStudentBooks() {
        val state = library.state
        state.books.asSequence()
            .filter { it.studentId == state.selectedStudentId }
            .forEach { seedStudentBook(it.id) }
    }

    /** A removed/re-imported book changes opaque workbook identity and therefore starts a generation. */
    private fun reconcileStudentInventory() {
        val state = library.state
        val visibleBookIds = state.books.asSequence()
            .filter { it.studentId == state.selectedStudentId }
            .map { it.id }
            .toSet()
        if (store.studentPages().any { it.bookId !in visibleBookIds }) {
            store.closeStudentGeneration()
            store.beginStudentGeneration()
            seededStudentBooks.clear()
            currentPresence?.takeIf(StudentStudyPresence::active)
                ?.takeIf { requireNotNull(it.bookId) in visibleBookIds }
                ?.let { presence ->
                refreshStudentPage(
                    requireNotNull(presence.bookId),
                    requireNotNull(presence.pageNumber) - 1,
                )
            }
            if (currentPresence?.bookId !in visibleBookIds) currentPresence = null
            seedAllStudentBooks()
            manifestBatchesRemaining = requiredManifestBatchCount(store.studentPages().size)
            manifestDueAtElapsedMs = nowElapsedMs()
        } else {
            seedAllStudentBooks()
        }
    }

    private fun isVisibleStudentBook(bookId: String): Boolean {
        val state = library.state
        return state.books.any { it.id == bookId && it.studentId == state.selectedStudentId }
    }

    private fun resolveLocalWorkbook(
        token: String,
        contentSha256: String,
        allowAutomaticMapping: Boolean = true,
    ) = run {
        val candidates = library.booksByContentSha256(contentSha256)
        val selectedStudentId = library.state.selectedStudentId
        store.mappedLocalBookId(token, contentSha256)?.let { id ->
            // Once a pair token is mapped it stays pinned across teacher-side student selection.
            // Moving it to another identical PDF requires the explicit confirmation UI.
            candidates.firstOrNull { it.id == id }
                ?.let { return@run it }
        }
        if (!allowAutomaticMapping) return@run null
        // A Telegram pair has no trustworthy local student id. Never let a unique book owned by a
        // different student become an implicit target; duplicate tokens in the same manifest are
        // arbitrated separately by localClaims.
        candidates.filter { it.studentId == selectedStudentId }.singleOrNull()
            ?.takeUnless { candidate ->
                store.localBookClaimedByDifferentWorkbook(token, candidate.id, contentSha256)
            }
    }

    private fun applyManifestAttempts(page: TeacherPageSyncRecord) {
        val bookId = page.localBookId ?: return
        page.attemptNos.forEach { attemptNo ->
            val existing = library.attempts(bookId, page.pageNumber).firstOrNull { it.attemptNo == attemptNo }
            val started = existing?.startedAtEpochMillis ?: page.lastChangedAtEpochMs
            val locked = attemptNo in page.submittedAttemptNos
            library.upsertAttemptFromSync(
                bookId,
                page.pageNumber,
                Attempt(
                    bookId = bookId,
                    pageNumber = page.pageNumber,
                    attemptNo = attemptNo,
                    locked = locked,
                    startedAtEpochMillis = started,
                    lockedAtEpochMillis = if (locked) maxOf(started, page.lastChangedAtEpochMs) else null,
                ),
            )
        }
    }

    private fun applyObservedAttempts(page: TeacherPageSyncRecord, attemptNos: List<Int>) {
        val bookId = page.localBookId ?: return
        attemptNos.forEach { attemptNo ->
            if (library.attempts(bookId, page.pageNumber).any { it.attemptNo == attemptNo }) return@forEach
            library.upsertAttemptFromSync(
                bookId,
                page.pageNumber,
                Attempt(
                    bookId = bookId,
                    pageNumber = page.pageNumber,
                    attemptNo = attemptNo,
                    locked = false,
                    startedAtEpochMillis = nowEpochMs(),
                ),
            )
        }
    }

    private fun publishTeacherCursor() {
        val cursor = store.teacherCursor() ?: run {
            RemoteStudentCursorBus.clear(RemoteStudentCursorTransport.TELEGRAM)
            return
        }
        val page = store.teacherPage(cursor.pageToken) ?: return
        val bookId = page.localBookId ?: run {
            RemoteStudentCursorBus.clear(RemoteStudentCursorTransport.TELEGRAM)
            return
        }
        RemoteStudentCursorBus.publish(
            RemoteStudentCursor(
                bookId,
                cursor.pageNumber,
                cursor.attemptNo,
                cursor.sourceRevision,
                page.appliedRevision >= cursor.sourceRevision &&
                    page.appliedStudentLayerSha256 == page.studentLayerSha256,
                RemoteStudentCursorTransport.TELEGRAM,
                nowElapsedMs(),
            ),
        )
    }

    private fun automaticPageTokens(): List<String> {
        val pages = store.teacherPages().filterNot(TeacherPageSyncRecord::mappingRequired)
        if (pages.isEmpty()) return emptyList()
        val cursorToken = store.teacherCursor()?.pageToken
        return buildList {
            cursorToken?.takeIf { token -> pages.any { it.pageToken == token } }?.let(::add)
            pages.asSequence().map(TeacherPageSyncRecord::pageToken).filterNot(::contains)
                .take(MAX_AUTOMATIC_PAGES - size).forEach(::add)
        }
    }

    private fun buildUiState(): RemotePageSyncUiState {
        if (session?.role != RemoteReviewRole.TEACHER) {
            return RemotePageSyncUiState(connected = telegramOnline, isTeacher = false)
        }
        val automatic = automaticPageTokens()
        val activeToken = store.teacherPages().firstOrNull { it.requestTransferId != null }?.pageToken
        val visible = store.pendingTeacherPages().filter { page ->
            page.mappingRequired || page.pageToken !in automatic || page.pageToken in failedPageTokens
        }
        val pages = visible.map { page ->
            RemotePageSyncPageUi(
                pageToken = page.pageToken,
                workbookToken = page.workbookToken,
                workbookLabel = page.workbookLabel,
                pageNumber = page.pageNumber + 1,
                attemptNos = page.attemptNos,
                approxBytes = page.approximateBytes,
                lastChangedEpochMs = page.lastChangedAtEpochMs,
                status = when {
                    page.mappingRequired -> RemotePageSyncPageStatus.MAPPING_REQUIRED
                    page.pageToken == activeToken -> RemotePageSyncPageStatus.SYNCING
                    !telegramOnline -> RemotePageSyncPageStatus.DEVICE_OFFLINE
                    page.pageToken in failedPageTokens -> RemotePageSyncPageStatus.FAILED
                    else -> RemotePageSyncPageStatus.WAITING
                },
            )
        }
        return RemotePageSyncUiState(
            connected = telegramOnline,
            isTeacher = true,
            pendingPages = remotePageSyncPagesLatestFirst(pages),
            remainingApproxBytes = pages.sumOf(RemotePageSyncPageUi::approxBytes),
            intervalSeconds = intervalSeconds,
            running = manualRunning,
            activePageNumber = store.teacherPage(activeToken.orEmpty())?.pageNumber?.plus(1),
            inventoryPageCount = store.teacherExpectedInventoryPageCount(),
            discoveredPageCount = store.teacherDiscoveredInventoryPageCount(),
            inventoryComplete = store.teacherInventoryComplete(),
        )
    }

    private fun queueTeacherEvent(event: TeacherReviewPublished) {
        val book = runCatching { library.book(event.bookId) }.getOrNull() ?: return
        if (event.pageNumber !in 0 until book.pageCount || book.contentSha256.length != 64) return
        val artifact = annotationStore.teacherReviewPublicationArtifact(
            book.id,
            event.pageNumber,
            event.attemptNo,
            event.publicationId,
        ) ?: return
        val currentPairId = session?.pairId?.takeIf { it == store.currentPairId() }
        // A live callback is asynchronous and therefore is not itself peer provenance. Legacy or
        // unowned durable intents stay held; the teacher can explicitly publish them again.
        val ownership = recoveredTeacherReviewOwnership(artifact.intent, currentPairId)
        store.queueTeacherReview(
            PendingTeacherReviewRecord(
                intentId = artifact.intent.publicationId,
                bookId = book.id,
                contentSha256 = book.contentSha256.lowercase(),
                workbookToken = ownership.workbookToken,
                deferredWorkbookBinding = ownership.deferredWorkbookBinding,
                deferredAfterManifestGeneration = ownership.deferredAfterManifestGeneration,
                deferredAfterManifestSequence = ownership.deferredAfterManifestSequence,
                pageNumber = event.pageNumber,
                attemptNo = event.attemptNo,
                queuedAtEpochMs = artifact.intent.updatedAtEpochMillis,
            ),
        )
    }

    private fun drainTeacherPublishIntents(): Boolean {
        var drained = false
        // A process can die after the page and catalog commits but before the publication journal
        // is promoted. Reconcile only preparations whose two exact durable layers both match.
        annotationStore.teacherReviewPublicationPreparations().forEach { prepared ->
            val book = runCatching { library.book(prepared.bookId) }.getOrNull() ?: return@forEach
            val exactGroups = exactTeacherReviewMarkGroups(
                book.id,
                prepared.pageNumber,
                prepared.attemptNo,
            )
            val promoted = runCatching {
                annotationStore.promotePreparedTeacherReviewPublication(
                    bookId = prepared.bookId,
                    pageNumber = prepared.pageNumber,
                    attemptNo = prepared.attemptNo,
                    publicationId = prepared.publicationId,
                    currentMarkGroups = exactGroups,
                )
            }.getOrNull()
            if (promoted != null) {
                // The LAN service performs its reconnect repair once per socket generation. If
                // this recovery promotion happens after that scan, emit the same exact durable
                // publication event as the Reader button so an already-READY link sends it now.
                LanSyncBus.teacherReviewPublished(
                    LanTeacherReviewPublication(
                        promoted.bookId,
                        promoted.pageNumber,
                        promoted.attemptNo,
                        promoted.publicationId,
                    ),
                )
                drained = true
            }
        }
        annotationStore.teacherReviewPublishIntents().forEach { intent ->
            if (intent.publicationId.isNotEmpty() &&
                store.isTeacherPublicationCompleted(
                    intent.bookId,
                    intent.pageNumber,
                    intent.attemptNo,
                    intent.publicationId,
                )
            ) {
                annotationStore.removeTeacherReviewPublishIntent(
                    intent.bookId,
                    intent.pageNumber,
                    intent.attemptNo,
                    intent.publicationId,
                )
                drained = true
                return@forEach
            }
            val book = runCatching { library.book(intent.bookId) }.getOrNull() ?: return@forEach
            val completeIntent = if (intent.publicationId.isEmpty() || intent.markGroupsSha256.isEmpty()) {
                val exactGroups = exactTeacherReviewMarkGroups(
                    book.id,
                    intent.pageNumber,
                    intent.attemptNo,
                )
                runCatching {
                    annotationStore.recordTeacherReviewPublishIntent(intent, exactGroups)
                }.getOrNull() ?: return@forEach
            } else intent
            val artifact = annotationStore.teacherReviewPublicationArtifact(
                intent.bookId,
                intent.pageNumber,
                intent.attemptNo,
                completeIntent.publicationId,
            ) ?: return@forEach
            val ownership = recoveredTeacherReviewOwnership(
                completeIntent,
                session?.pairId?.takeIf { it == store.currentPairId() },
            )
            store.queueTeacherReview(
                PendingTeacherReviewRecord(
                    intentId = artifact.intent.publicationId,
                    bookId = intent.bookId,
                    contentSha256 = book.contentSha256.lowercase(),
                    workbookToken = ownership.workbookToken,
                    deferredWorkbookBinding = ownership.deferredWorkbookBinding,
                    deferredAfterManifestGeneration = ownership.deferredAfterManifestGeneration,
                    deferredAfterManifestSequence = ownership.deferredAfterManifestSequence,
                    pageNumber = intent.pageNumber,
                    attemptNo = intent.attemptNo,
                    queuedAtEpochMs = intent.updatedAtEpochMillis,
                ),
            )
            drained = true
        }
        bindDeferredTeacherReviews(store.teacherPages(), store.teacherInventoryComplete())
        return drained
    }

    private fun resolvePublishedReviewWorkbookToken(
        localBookId: String,
        contentSha256: String,
        pageNumber: Int,
        attemptNo: Int,
    ): String? {
        if (!store.teacherInventoryComplete()) return null
        val identityMatches = store.teacherPages().asSequence()
            .filter { page ->
                page.contentSha256 == contentSha256 && page.pageNumber == pageNumber &&
                    attemptNo in page.submittedAttemptNos && page.localBookId == localBookId
            }
            .toList()
        val token = identityMatches.map(TeacherPageSyncRecord::workbookToken).distinct().singleOrNull()
        return token
    }

    private fun bindDeferredTeacherReviews(
        pages: List<TeacherPageSyncRecord>,
        completeInventory: Boolean,
    ) {
        store.pendingTeacherReviews().forEach { pending ->
            val token = resolveDeferredReviewWorkbookToken(
                pending,
                pages,
                completeInventory,
                store.teacherManifestGeneration(),
                store.teacherManifestSequence(),
            ) ?: return@forEach
            store.bindDeferredTeacherReviewWorkbook(pending.key, pending.intentId, token)
        }
    }

    /** The user selected this exact remote workbook token, so ambiguity inference is unnecessary. */
    private fun bindDeferredTeacherReviewsAfterExplicitMapping(
        workbookToken: String,
        localBookId: String,
        pages: List<TeacherPageSyncRecord>,
    ) {
        store.pendingTeacherReviews().forEach { pending ->
            if (!pending.deferredWorkbookBinding || pending.workbookToken != null ||
                pending.inFlight || pending.bookId != localBookId
            ) return@forEach
            val exact = pages.any { page ->
                page.workbookToken == workbookToken && page.contentSha256 == pending.contentSha256 &&
                    page.pageNumber == pending.pageNumber && pending.attemptNo in page.submittedAttemptNos
            }
            if (exact) {
                store.bindDeferredTeacherReviewWorkbook(pending.key, pending.intentId, workbookToken)
            }
        }
    }

    private fun exactTeacherReviewMarkGroups(
        bookId: String,
        pageNumber: Int,
        attemptNo: Int,
    ): List<MarkGroup> = library.markGroupsForSync(bookId).asSequence()
        .filter { it.pageNumber == pageNumber }
        .mapNotNull { group ->
            val exact = group.marks.filter { it.attemptNo == attemptNo }
            exact.takeIf(List<*>::isNotEmpty)?.let { group.copy(marks = exact) }
        }
        .toList()

    private fun scheduleManifestAtRateBoundary() {
        manifestBatchesRemaining = maxOf(
            manifestBatchesRemaining,
            requiredManifestBatchCount(store.studentPages().size),
        )
        val now = nowElapsedMs()
        val boundary = lastManifestSentAtElapsedMs?.let { safeAdd(it, MANIFEST_INTERVAL_MS) } ?: now
        manifestDueAtElapsedMs = minOf(manifestDueAtElapsedMs, boundary)
    }

    private fun notifyUiChanged() {
        val state = buildUiState()
        listeners.forEach { listener -> runCatching { listener(state) } }
    }

    private fun TelegramEnqueueResult.isDurablyAccepted(): Boolean =
        this == TelegramEnqueueResult.ENQUEUED || this == TelegramEnqueueResult.ALREADY_PENDING ||
            this == TelegramEnqueueResult.ALREADY_DELIVERED

    private fun randomTransferId(prefix: String): String =
        "${prefix}_${UUID.randomUUID().toString().replace("-", "")}"

    private fun stableTransferId(prefix: String, material: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
        return "${prefix}_${digest.take(48)}"
    }

    private fun safeAdd(left: Long, right: Long): Long = if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private companion object {
        const val MAX_AUTOMATIC_PAGES = 3
        // Forty-eight rows fit below the two MiB document limit even when each row contains the
        // protocol maximum of 4,096 attempt numbers twice. Larger inventories rotate by window.
        const val MAX_MANIFEST_PAGES = REMOTE_MANIFEST_PAGE_WINDOW + 1
        const val MANIFEST_INTERVAL_MS = 60_000L
        const val SEND_RETRY_MS = 30_000L
        const val TEACHER_REVIEW_INTERVAL_MS = 60_000L
        const val APPLICATION_ACK_RECOVERY_MS = 2 * 60_000L
    }
}

internal fun requiredManifestBatchCount(pageCount: Int): Int {
    if (pageCount <= 0) return 1
    return maxOf(
        1,
        (pageCount + REMOTE_MANIFEST_PAGE_WINDOW - 1) / REMOTE_MANIFEST_PAGE_WINDOW,
    )
}

private const val REMOTE_MANIFEST_PAGE_WINDOW = 47

/** Stable windowing is independent of page recency and of a changing current-page cursor. */
internal fun selectManifestPageTokens(
    pageTokens: Collection<String>,
    currentPageToken: String?,
    windowOrdinal: Long,
): List<String> {
    require(windowOrdinal >= 0L)
    val stable = pageTokens.asSequence().filter(String::isNotBlank).distinct().sorted().toList()
    if (stable.isEmpty()) return emptyList()
    val start = (((windowOrdinal % stable.size) * REMOTE_MANIFEST_PAGE_WINDOW) % stable.size).toInt()
    val rotated = if (start == 0) stable else stable.drop(start) + stable.take(start)
    val window = rotated.take(REMOTE_MANIFEST_PAGE_WINDOW)
    return buildList {
        currentPageToken?.takeIf { it in stable && it !in window }?.let(::add)
        addAll(window)
    }
}

internal data class TransmittableTeacherReview(
    val pending: PendingTeacherReviewRecord,
    val page: TeacherPageSyncRecord,
)

internal data class RecoveredTeacherReviewOwnership(
    val workbookToken: String?,
    val deferredWorkbookBinding: Boolean,
    val deferredAfterManifestGeneration: Long,
    val deferredAfterManifestSequence: Long,
)

/** A recovered publication can become live only for the exact pair captured before its commit. */
internal fun recoveredTeacherReviewOwnership(
    intent: TeacherReviewPublishIntent,
    currentPairId: String?,
): RecoveredTeacherReviewOwnership {
    if (intent.remotePairId == null || intent.remotePairId != currentPairId) {
        return RecoveredTeacherReviewOwnership(null, false, 0L, 0L)
    }
    val token = intent.remoteWorkbookToken
    return RecoveredTeacherReviewOwnership(
        workbookToken = token,
        deferredWorkbookBinding = token == null,
        deferredAfterManifestGeneration = if (token == null) intent.remoteManifestGeneration else 0L,
        deferredAfterManifestSequence = if (token == null) intent.remoteManifestSequence else 0L,
    )
}

/** Binds a newly published, not-yet-advertised review only when a manifest has one exact target. */
internal fun resolveDeferredReviewWorkbookToken(
    pending: PendingTeacherReviewRecord,
    pages: List<TeacherPageSyncRecord>,
    completeInventory: Boolean,
    manifestGeneration: Long,
    manifestSequence: Long,
): String? {
    if (!completeInventory || !pending.deferredWorkbookBinding || pending.workbookToken != null ||
        pending.inFlight
    ) {
        return null
    }
    val hasNewerManifestEvidence = manifestGeneration > pending.deferredAfterManifestGeneration ||
        manifestGeneration == pending.deferredAfterManifestGeneration &&
        manifestSequence > pending.deferredAfterManifestSequence
    if (!hasNewerManifestEvidence) return null
    val identityMatches = pages.asSequence()
        .filter { page ->
            page.contentSha256 == pending.contentSha256 && page.pageNumber == pending.pageNumber &&
                pending.attemptNo in page.submittedAttemptNos && page.localBookId == pending.bookId
        }
        .toList()
    val token = identityMatches.map(TeacherPageSyncRecord::workbookToken).distinct().singleOrNull()
    return token
}

/** Picks the newest review that has an exact, submitted remote page without letting poison entries block it. */
internal fun selectTransmittableTeacherReview(
    pendingReviews: List<PendingTeacherReviewRecord>,
    pages: List<TeacherPageSyncRecord>,
    failedKeys: Set<String> = emptySet(),
): TransmittableTeacherReview? {
    fun select(failed: Boolean): TransmittableTeacherReview? = pendingReviews.asSequence()
        .filterNot(PendingTeacherReviewRecord::inFlight)
        .filter { (it.key in failedKeys) == failed }
        .mapNotNull { pending ->
            pages.firstOrNull { page ->
                pending.workbookToken != null && page.workbookToken == pending.workbookToken &&
                    page.localBookId == pending.bookId && page.contentSha256 == pending.contentSha256 &&
                    page.pageNumber == pending.pageNumber && pending.attemptNo in page.submittedAttemptNos
            }?.let { page -> TransmittableTeacherReview(pending, page) }
        }
        .firstOrNull()
    return select(failed = false) ?: select(failed = true)
}

/** Current/recent pages stay first, but one failed page cannot starve the remaining queue. */
internal fun selectNextTeacherPage(
    pending: List<TeacherPageSyncRecord>,
    automaticTokens: List<String>,
    manualRunning: Boolean,
    failedPageTokens: Set<String>,
    lastServedPageToken: String? = null,
    preferManual: Boolean = false,
): TeacherPageSyncRecord? {
    val automaticOrder = automaticTokens.rotateAfter(lastServedPageToken)
    val queueOrder = if (manualRunning && preferManual) {
        listOf(false, true)
    } else {
        listOf(true, false)
    }
    fun select(automatic: Boolean, failed: Boolean): TeacherPageSyncRecord? {
        val candidates = pending.filter {
            (it.pageToken in automaticTokens) == automatic &&
                (it.pageToken in failedPageTokens) == failed
        }
        return if (automatic) {
            automaticOrder.firstNotNullOfOrNull { token ->
                candidates.firstOrNull { it.pageToken == token }
            }
        } else if (manualRunning) candidates.firstOrNull() else null
    }
    for (failed in listOf(false, true)) {
        queueOrder.forEach { automatic -> select(automatic, failed)?.let { return it } }
    }
    return null
}

private fun <T> List<T>.rotateAfter(value: T?): List<T> {
    if (isEmpty() || value == null) return this
    val index = indexOf(value)
    if (index < 0 || index == lastIndex) return this
    return drop(index + 1) + take(index + 1)
}

/** One local workbook may represent at most one remote workbook token within a manifest. */
internal fun canAssignLocalWorkbook(
    workbookToken: String,
    localBookId: String,
    claimsByLocalBookId: Map<String, String>,
): Boolean = claimsByLocalBookId[localBookId]?.let { it == workbookToken } ?: true

/** Initial book seeding preserves real history; only a newly observed mutation receives `now`. */
internal fun resolveStudentPageChangedAt(
    previousFingerprint: String?,
    previousChangedAtEpochMs: Long,
    currentFingerprint: String,
    historicalChangedAtEpochMs: Long,
    nowEpochMs: Long,
): Long = when {
    previousFingerprint == null -> historicalChangedAtEpochMs.coerceAtLeast(0L)
    previousFingerprint == currentFingerprint -> previousChangedAtEpochMs.coerceAtLeast(0L)
    else -> maxOf(previousChangedAtEpochMs, historicalChangedAtEpochMs, nowEpochMs)
}
