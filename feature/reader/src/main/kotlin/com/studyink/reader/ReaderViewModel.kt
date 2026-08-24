package com.studyink.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studyink.annotation.engine.AnnotationChange
import com.studyink.annotation.engine.AnnotationDocument
import com.studyink.annotation.storage.CorruptAnnotationDataException
import com.studyink.annotation.storage.PageOperationLogStore
import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.Attempt
import com.studyink.core.model.MarkColor
import com.studyink.core.model.MarkGroup
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StudentActivitySample
import com.studyink.core.model.summariseActivity
import com.studyink.core.model.trimmedTo
import com.studyink.core.model.TEACHER_PAGE_REVIEW_ATTEMPT_NO
import com.studyink.library.data.LibraryRepository
import com.studyink.monitor.core.RemoteReviewFeedbackBus
import com.studyink.monitor.core.RemoteTeacherFeedbackApplied
import com.studyink.monitor.core.HybridLinkDecision
import com.studyink.monitor.core.HybridLinkStatusBus
import com.studyink.monitor.core.RemoteGradeAppliedBus
import com.studyink.monitor.core.RemotePeerChatStateBus
import com.studyink.monitor.telegram.RemoteMonitorGateway
import com.studyink.monitor.telegram.RemoteReviewPeerStatus
import com.studyink.sync.lan.LanConnectionState
import com.studyink.sync.lan.LanSyncBus
import com.studyink.sync.lan.StudentLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class ReaderRole { STUDENT, TEACHER_TABLET, TEACHER_PHONE }

/**
 * Describes why the reader was opened independently from the physical device/role.
 *
 * REVIEW deliberately owns a page-level teacher target when a page has no submitted attempt. This
 * keeps teacher preparation and grading data out of the student's future attempt number space.
 */
enum class ReaderWorkflow {
    STUDY,
    REVIEW,
    LIVE_MONITOR;

    companion object {
        fun defaultFor(role: ReaderRole): ReaderWorkflow = when (role) {
            ReaderRole.STUDENT -> STUDY
            ReaderRole.TEACHER_TABLET -> REVIEW
            ReaderRole.TEACHER_PHONE -> LIVE_MONITOR
        }
    }
}

internal fun resolveReaderAttemptNo(
    workflow: ReaderWorkflow,
    selectedAttemptNo: Int?,
    attempts: List<Attempt>,
    observedStudentAttemptNos: Set<Int> = emptySet(),
    liveStudentAttemptNo: Int? = null,
): Int = when (workflow) {
    // Submitting locks the attempt without opening the next one. Targeting the unwritten next
    // number would hide the work that was just submitted, and a student has no attempt picker to
    // get back to it. An open attempt is selected only after its stroke evidence is durable; this
    // also keeps a failed first append from leaving an empty N+1 over the submitted page. The next
    // successful stroke moves the view onto N+1.
    ReaderWorkflow.STUDY -> attempts.lastOrNull { attempt ->
        !attempt.locked && (
            attempt.attemptNo in observedStudentAttemptNos ||
                attempts.none(Attempt::locked)
        )
    }?.attemptNo
        ?: attempts.lastOrNull(Attempt::locked)?.attemptNo
        ?: attempts.maxOfOrNull { it.attemptNo }
        ?: 1

    ReaderWorkflow.REVIEW -> selectedAttemptNo?.takeIf { requested ->
        requested == TEACHER_PAGE_REVIEW_ATTEMPT_NO ||
            attempts.any { it.attemptNo == requested && it.locked }
    } ?: attempts.lastOrNull { it.locked }?.attemptNo
        ?: TEACHER_PAGE_REVIEW_ATTEMPT_NO

    ReaderWorkflow.LIVE_MONITOR -> {
        val liveEvidence = sequenceOf(
            liveStudentAttemptNo?.takeIf { it > TEACHER_PAGE_REVIEW_ATTEMPT_NO },
            observedStudentAttemptNos.maxOrNull(),
        ).filterNotNull().maxOrNull()
        // A teacher who opens one submission from the stack stays on it. Live evidence decides
        // what to show only when nothing was picked, which is what keeps the default view tracking
        // the student. Ranking it above the pick made every attempt but the newest unreachable.
        selectedAttemptNo?.takeIf { requested ->
            requested > TEACHER_PAGE_REVIEW_ATTEMPT_NO &&
                (attempts.any { it.attemptNo == requested } || requested in observedStudentAttemptNos)
        }
            ?: liveEvidence
            // Attempt metadata is a fallback only. writableAttempt() is persisted and broadcast
            // before the first annotation append, so a failed append can leave an empty open N+1
            // in the catalog while PAGE_STATE and durable stroke evidence still point at N.
            ?: attempts.lastOrNull(Attempt::locked)?.attemptNo
            ?: attempts.lastOrNull { !it.locked }?.attemptNo
            ?: TEACHER_PAGE_REVIEW_ATTEMPT_NO
    }
}

data class ReaderCapabilities(
    val canWrite: Boolean,
    val canGrade: Boolean,
    val canBrowseAttempts: Boolean,
    val canSubmit: Boolean,
    val showsStudentLocation: Boolean,
    val canPublishTeacherInk: Boolean,
) {
    companion object {
        fun forRole(role: ReaderRole) = when (role) {
            ReaderRole.STUDENT -> ReaderCapabilities(true, false, false, true, false, false)
            ReaderRole.TEACHER_TABLET -> ReaderCapabilities(true, true, true, false, false, false)
            ReaderRole.TEACHER_PHONE -> ReaderCapabilities(true, true, true, false, true, true)
        }

        fun forSession(role: ReaderRole, workflow: ReaderWorkflow, attemptNo: Int): ReaderCapabilities {
            val base = forRole(role)
            return base.copy(
                showsStudentLocation = role == ReaderRole.TEACHER_PHONE &&
                    workflow == ReaderWorkflow.LIVE_MONITOR,
                // A page-level preparation layer is intentionally private to the teacher. There
                // is no student attempt to publish it into yet.
                canPublishTeacherInk = role == ReaderRole.TEACHER_PHONE &&
                    workflow == ReaderWorkflow.LIVE_MONITOR &&
                    attemptNo > TEACHER_PAGE_REVIEW_ATTEMPT_NO,
            )
        }
    }
}

data class ReaderUiState(
    val snapshot: AnnotationSnapshot = AnnotationSnapshot.empty("unopened"),
    val bookId: String = "",
    val bookTitle: String = "",
    val pageCount: Int = 0,
    val documentReady: Boolean = false,
    val pageNumber: Int = 0,
    val attemptNo: Int = 1,
    val role: ReaderRole = ReaderRole.STUDENT,
    val workflow: ReaderWorkflow = ReaderWorkflow.STUDY,
    val capabilities: ReaderCapabilities = ReaderCapabilities.forRole(ReaderRole.STUDENT),
    val marks: List<MarkGroup> = emptyList(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val dataError: String? = null,
    val storageAvailable: Boolean = true,
    val studentPageNumber: Int? = null,
    val studentAttemptNo: Int? = null,
    /**
     * Whether the visible page is currently owned by the student's remote cursor. LIVE_MONITOR is
     * still active while this is false; only automatic page following is paused for local browsing.
     */
    val isFollowingStudent: Boolean = false,
    val currentAttemptWritable: Boolean = false,
    /**
     * The student is looking at an attempt they already submitted. Distinct from
     * [currentAttemptWritable], which is also false on a page that has no attempt at all.
     */
    val currentAttemptSubmitted: Boolean = false,
    /** Durable document edits queued or running for this reader process. */
    val pendingDocumentMutations: Int = 0,
    /** Freezes student input from the moment submit is accepted until navigation completes. */
    val submissionInProgress: Boolean = false,
    /**
     * The attempt on screen was chosen from the stack rather than derived. Live refreshes keep it
     * instead of snapping back to the student's newest ink; changing page clears it.
     */
    val attemptPinned: Boolean = false,
    /** Whether the paired device is actually reachable right now, not merely whether live mode is on. */
    val liveConnection: LanConnectionState = LanConnectionState.IDLE,
    /** LAN-first/Telegram-fallback decision for this exact book. */
    val hybridLink: HybridLinkDecision? = null,
    /** Durable peer chat messages not yet opened in the conversation screen. */
    val telegramUnreadCount: Int = 0,
    /** Ten second buckets of the student's writing, newest last. Teacher live monitoring only. */
    val activitySamples: List<StudentActivitySample> = emptyList(),
    /**
     * Every attempt this page holds, ascending. The top chrome needs the submissions themselves,
     * not only the ones that already carry marks, so an ungraded submission still gets a slot.
     */
    val pageAttemptNos: List<Int> = emptyList(),
) {
    val isTeacherPageTarget: Boolean
        get() = role != ReaderRole.STUDENT &&
            attemptNo == TEACHER_PAGE_REVIEW_ATTEMPT_NO

    val attemptDisplayLabel: String
        get() = if (isTeacherPageTarget) "페이지 표시" else "${attemptNo}회"

    val canSubmitNow: Boolean
        get() = documentReady && storageAvailable && capabilities.canSubmit &&
            currentAttemptWritable && pendingDocumentMutations == 0 && !submissionInProgress

    val canPublishTeacherInkNow: Boolean
        get() = documentReady && storageAvailable && capabilities.canPublishTeacherInk

    val shouldForceSyncTeacherUndoRedo: Boolean
        get() = role != ReaderRole.STUDENT && !isTeacherPageTarget
}

internal data class ReaderAnnotationTarget(
    val bookId: String,
    val pageNumber: Int,
    val attemptNo: Int,
) {
    fun matches(state: ReaderUiState): Boolean =
        state.bookId == bookId &&
            state.pageNumber == pageNumber &&
            state.attemptNo == attemptNo
}

internal data class PendingMarkMove(
    val groupId: String,
    val target: ReaderAnnotationTarget,
) {
    fun canApply(state: ReaderUiState): Boolean =
        target.matches(state) && state.documentReady && state.storageAvailable
}

internal fun ReaderUiState.annotationTarget() = ReaderAnnotationTarget(bookId, pageNumber, attemptNo)

internal fun ReaderUiState.canMutateStudentAttempt(writableAttemptNo: Int?): Boolean =
    role != ReaderRole.STUDENT ||
        currentAttemptWritable && writableAttemptNo != null && writableAttemptNo == attemptNo

internal fun ReaderUiState.matchesRemotePage(bookId: String, pageNumber: Int): Boolean =
    this.bookId == bookId && this.pageNumber == pageNumber

internal fun ReaderUiState.acceptsRemoteTeacherFeedback(event: RemoteTeacherFeedbackApplied): Boolean =
    role == ReaderRole.STUDENT && matchesRemotePage(event.bookId, event.pageNumber)

internal fun ReaderUiState.shouldFollowRemoteStudentPage(
    bookId: String,
    pageNumber: Int,
    attemptNo: Int?,
): Boolean =
    this.bookId == bookId &&
        capabilities.showsStudentLocation &&
        isFollowingStudent &&
        (this.pageNumber != pageNumber || attemptNo != null && this.attemptNo != attemptNo)

internal data class LiveMonitorTarget(
    val pageNumber: Int,
    val attemptNo: Int?,
)

/**
 * Resolves the annotation target separately from the student's retained location.
 *
 * A live reader may keep showing where the student is while the teacher browses another page. The
 * retained cursor only owns the visible target while following is explicitly enabled.
 */
internal fun resolveLiveMonitorTarget(
    requestedPageNumber: Int,
    liveStudentAttemptNo: Int?,
    stickyStudentLocation: StudentLocation?,
    followRemoteStudent: Boolean,
): LiveMonitorTarget = if (followRemoteStudent && stickyStudentLocation != null) {
    LiveMonitorTarget(
        pageNumber = stickyStudentLocation.pageNumber,
        attemptNo = stickyStudentLocation.attemptNo ?: liveStudentAttemptNo,
    )
} else {
    LiveMonitorTarget(
        pageNumber = requestedPageNumber,
        attemptNo = liveStudentAttemptNo.takeIf { followRemoteStudent },
    )
}

/** Attempts the student is inking on this page, recovered from the strokes we already hold. */
internal fun AnnotationSnapshot.studentAttemptNos(): Set<Int> = assets.values.asSequence()
    .filter { it.authorId == "student" && it.attemptNo > TEACHER_PAGE_REVIEW_ATTEMPT_NO }
    .map { it.attemptNo }
    .toSet()

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val store = PageOperationLogStore.get(application)
    private val library = LibraryRepository.get(application)
    private val remoteMonitorGateway = RemoteMonitorGateway.get(application)
    private val mutationMutex = Mutex()
    private val pendingDocumentMutations = AtomicInteger(0)
    private val submissionGate = AtomicBoolean(false)
    private var document = AnnotationDocument(AnnotationSnapshot.empty("unopened"))
    @Volatile private var loadGeneration = 0L
    private var pendingRemoteRefresh: Job? = null
    private var activitySampler: Job? = null
    private var lastSampledClock = 0L
    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()
    private val _remoteFeedbackArrivals = MutableSharedFlow<RemoteTeacherFeedbackApplied>(
        extraBufferCapacity = 4,
    )
    val remoteFeedbackArrivals: SharedFlow<RemoteTeacherFeedbackApplied> =
        _remoteFeedbackArrivals.asSharedFlow()
    private val remoteFeedbackSubscription = RemoteReviewFeedbackBus.subscribe { event ->
        viewModelScope.launch(Dispatchers.Main.immediate) {
            val state = _uiState.value
            if (!state.acceptsRemoteTeacherFeedback(event)) return@launch
            // The app coordinator has already committed the teacher operations to PageOperationLogStore.
            // Reuse the same coalesced, in-place reload as LAN operations; this leaves the PDF,
            // attempt selection, and student stroke data untouched.
            refreshCurrentPage(event.bookId, event.pageNumber, state.studentAttemptNo)
            _remoteFeedbackArrivals.tryEmit(event)
        }
    }
    private val hybridLinkSubscription = HybridLinkStatusBus.subscribe { status ->
        viewModelScope.launch(Dispatchers.Main.immediate) {
            val latest = _uiState.value
            if (latest.bookId != status.bookId) return@launch
            _uiState.value = latest.copy(hybridLink = status.decision)
        }
    }
    private val remoteGradeSubscription = RemoteGradeAppliedBus.subscribe { event ->
        viewModelScope.launch(Dispatchers.Main.immediate) {
            val latest = _uiState.value
            if (latest.bookId != event.bookId || latest.pageNumber != event.pageNumber) return@launch
            _uiState.value = latest.copy(marks = library.markGroups(event.bookId, event.pageNumber))
        }
    }
    private val peerChatSubscription = RemotePeerChatStateBus.subscribe { chatState ->
        viewModelScope.launch(Dispatchers.Main.immediate) {
            val peer = remoteMonitorGateway.remoteReviewPeerStatus() as? RemoteReviewPeerStatus.Connected
                ?: return@launch
            if (chatState.scope.pairId != peer.pairId) return@launch
            _uiState.update { latest -> latest.copy(telegramUnreadCount = chatState.unreadCount) }
        }
    }
    private val syncListener = object : LanSyncBus.Listener {
        override fun onConnectionStateChanged(bookId: String, state: LanConnectionState) {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                val latest = _uiState.value
                if (latest.bookId != bookId) return@launch
                _uiState.value = latest.copy(liveConnection = state)
            }
        }

        override fun onRemoteOperation(bookId: String, pageNumber: Int) {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                val state = _uiState.value
                if (!state.matchesRemotePage(bookId, pageNumber)) return@launch
                refreshCurrentPage(bookId, pageNumber, state.studentAttemptNo)
            }
        }

        override fun onRemoteAttempt(bookId: String, pageNumber: Int) {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                val state = _uiState.value
                if (
                    !state.matchesRemotePage(bookId, pageNumber) ||
                    state.workflow != ReaderWorkflow.LIVE_MONITOR
                ) return@launch
                refreshCurrentPage(bookId, pageNumber, state.studentAttemptNo)
            }
        }

        override fun onRemoteMarkGroup(bookId: String, pageNumber: Int) {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                val latest = _uiState.value
                if (latest.matchesRemotePage(bookId, pageNumber)) {
                    // markGroups is an in-memory catalog read. Keeping the read and state copy in
                    // this non-suspending main-thread block preserves LAN callback ordering.
                    _uiState.value = latest.copy(marks = library.markGroups(bookId, pageNumber))
                }
            }
        }

        override fun onRemoteStudentLocationChanged(location: StudentLocation) {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                val latest = _uiState.value
                if (
                    latest.bookId != location.bookId ||
                    !latest.capabilities.showsStudentLocation
                ) return@launch
                val shouldFollow = latest.shouldFollowRemoteStudentPage(
                    location.bookId,
                    location.pageNumber,
                    location.attemptNo,
                )
                _uiState.value = latest.copy(
                    studentPageNumber = location.pageNumber,
                    studentAttemptNo = location.attemptNo,
                )
                if (!shouldFollow) return@launch
                if (latest.pageNumber != location.pageNumber) {
                    openBook(
                        bookId = location.bookId,
                        pageNumber = location.pageNumber,
                        role = latest.role,
                        selectedAttemptNo = null,
                        confirmedPageCount = latest.pageCount.takeIf { it > 0 },
                        workflow = ReaderWorkflow.LIVE_MONITOR,
                        liveStudentAttemptNo = location.attemptNo,
                        followRemoteStudent = true,
                    )
                } else {
                    refreshCurrentPage(
                        location.bookId,
                        location.pageNumber,
                        location.attemptNo,
                    )
                }
            }
        }
    }

    init { LanSyncBus.addListener(syncListener) }

    override fun onCleared() {
        stopActivitySampling()
        remoteFeedbackSubscription.close()
        hybridLinkSubscription.close()
        remoteGradeSubscription.close()
        peerChatSubscription.close()
        LanSyncBus.removeListener(syncListener)
        val state = _uiState.value
        if (state.bookId.isNotBlank() && state.storageAvailable) {
            runCatching {
                val valid = library.attempts(state.bookId, state.pageNumber)
                    .mapTo(mutableSetOf(TEACHER_PAGE_REVIEW_ATTEMPT_NO)) { it.attemptNo }
                store.garbageCollectOrphans(document.snapshot(), valid)
            }
        }
        super.onCleared()
    }

    fun openBook(
        bookId: String,
        pageNumber: Int,
        role: ReaderRole,
        selectedAttemptNo: Int? = null,
        confirmedPageCount: Int? = null,
        workflow: ReaderWorkflow = ReaderWorkflow.defaultFor(role),
        liveStudentAttemptNo: Int? = null,
        pinAttempt: Boolean = false,
        followRemoteStudent: Boolean = role != ReaderRole.STUDENT &&
            workflow == ReaderWorkflow.LIVE_MONITOR,
    ) {
        val book = library.book(bookId)
        val resolvedWorkflow = if (role == ReaderRole.STUDENT) ReaderWorkflow.STUDY else workflow
        val shouldFollowStudent = role != ReaderRole.STUDENT &&
            resolvedWorkflow == ReaderWorkflow.LIVE_MONITOR &&
            followRemoteStudent
        val stickyStudentLocation = if (
            role != ReaderRole.STUDENT && resolvedWorkflow == ReaderWorkflow.LIVE_MONITOR
        ) {
            LanSyncBus.remoteStudentLocation(bookId)
        } else {
            null
        }
        val liveTarget = resolveLiveMonitorTarget(
            requestedPageNumber = pageNumber,
            liveStudentAttemptNo = liveStudentAttemptNo,
            stickyStudentLocation = stickyStudentLocation,
            followRemoteStudent = shouldFollowStudent,
        )
        val resolvedLiveAttemptNo = liveTarget.attemptNo
        val target = liveTarget.pageNumber
            .coerceIn(0, book.pageCount - 1)
        val beforeLoad = _uiState.value
        val readyPageCount = confirmedPageCount
            ?: beforeLoad.pageCount.takeIf { beforeLoad.bookId == bookId && it > 0 }
        val generation = ++loadGeneration
        _uiState.value = beforeLoad.copy(
            snapshot = AnnotationSnapshot.empty(book.id, target),
            bookId = book.id,
            bookTitle = book.title,
            pageCount = readyPageCount ?: 0,
            // PDF geometry can stay ready while the page annotation target changes. Input must
            // remain disabled until this page's snapshot and attempt have been resolved together.
            documentReady = false,
            pageNumber = target,
            role = role,
            workflow = resolvedWorkflow,
            isFollowingStudent = shouldFollowStudent,
            capabilities = ReaderCapabilities.forSession(
                role,
                resolvedWorkflow,
                selectedAttemptNo ?: beforeLoad.attemptNo,
            ),
            marks = emptyList(),
            canUndo = false,
            canRedo = false,
        )
        viewModelScope.launch(Dispatchers.IO) {
            mutationMutex.withLock {
                if (generation != loadGeneration) return@withLock
                // Read inside the lock. A LAN operation appended between an earlier read and this
                // block would otherwise be discarded by the older snapshot landing on top of it.
                val result = runCatching { store.loadPageState(book.id, target) }
                // The read can be slow for a large operation log. A manual page request or a newer
                // student cursor may have superseded this load while it was on disk; in that case
                // neither the stale UI state nor its follow=true subscription may be published.
                if (generation != loadGeneration) return@withLock
                result.onSuccess { loaded ->
                    val snapshot = loaded.snapshot
                    // A PAGE_STATE can arrive while a manually selected page is loading. The
                    // visible target stays manual, but the location badge must retain the newest
                    // cursor rather than the one captured before the disk read began.
                    val retainedStudentLocation = if (
                        resolvedWorkflow == ReaderWorkflow.LIVE_MONITOR
                    ) {
                        LanSyncBus.remoteStudentLocation(book.id) ?: stickyStudentLocation
                    } else {
                        null
                    }
                    document = AnnotationDocument(
                        initial = snapshot,
                        operationClockHighWater = loaded.operationClockHighWater,
                    )
                    val attempts = library.attempts(book.id, target)
                    val attemptNo = resolveReaderAttemptNo(
                        workflow = resolvedWorkflow,
                        selectedAttemptNo = selectedAttemptNo,
                        attempts = attempts,
                        observedStudentAttemptNos = snapshot.studentAttemptNos(),
                        liveStudentAttemptNo = resolvedLiveAttemptNo,
                    )
                    _uiState.value = ReaderUiState(
                        snapshot = snapshot,
                        bookId = book.id,
                        bookTitle = book.title,
                        pageCount = readyPageCount ?: 0,
                        documentReady = readyPageCount != null,
                        pageNumber = target,
                        attemptNo = attemptNo,
                        role = role,
                        workflow = resolvedWorkflow,
                        capabilities = ReaderCapabilities.forSession(role, resolvedWorkflow, attemptNo),
                        marks = library.markGroups(book.id, target),
                        studentPageNumber = if (resolvedWorkflow == ReaderWorkflow.LIVE_MONITOR) {
                            retainedStudentLocation?.pageNumber ?: _uiState.value.studentPageNumber
                        } else {
                            null
                        },
                        studentAttemptNo = if (resolvedWorkflow == ReaderWorkflow.LIVE_MONITOR) {
                            retainedStudentLocation?.attemptNo ?: _uiState.value.studentAttemptNo
                        } else {
                            null
                        },
                        isFollowingStudent = shouldFollowStudent,
                        currentAttemptWritable = role == ReaderRole.STUDENT &&
                            attempts.any { it.attemptNo == attemptNo && !it.locked },
                        currentAttemptSubmitted = role == ReaderRole.STUDENT &&
                            attempts.any { it.attemptNo == attemptNo && it.locked },
                        pendingDocumentMutations = pendingDocumentMutations.get(),
                        submissionInProgress = submissionGate.get(),
                        attemptPinned = pinAttempt && attemptNo == selectedAttemptNo,
                        liveConnection = LanSyncBus.connectionState(book.id),
                        hybridLink = HybridLinkStatusBus.current(book.id)?.decision,
                        telegramUnreadCount = _uiState.value.telegramUnreadCount,
                        pageAttemptNos = attempts.map { it.attemptNo },
                    )
                    if (_uiState.value.capabilities.showsStudentLocation) {
                        startActivitySampling()
                    } else {
                        stopActivitySampling()
                    }
                    LanSyncBus.pageChanged(
                        bookId = book.id,
                        pageNumber = target,
                        revision = snapshot.revision,
                        attemptNo = attemptNo,
                        followRemoteStudent = shouldFollowStudent,
                    )
                }.onFailure { error ->
                    val message = if (error is CorruptAnnotationDataException) {
                        "이 페이지의 필기 파일이 손상되어 안전하게 격리했습니다. 원본은 덮어쓰지 않았습니다."
                    } else {
                        "이 페이지의 필기를 열 수 없습니다."
                    }
                    _uiState.value = _uiState.value.copy(
                        bookId = book.id,
                        bookTitle = book.title,
                        pageCount = readyPageCount ?: 0,
                        documentReady = readyPageCount != null,
                        pageNumber = target,
                        role = role,
                        workflow = resolvedWorkflow,
                        capabilities = ReaderCapabilities.forSession(
                            role,
                            resolvedWorkflow,
                            selectedAttemptNo ?: beforeLoad.attemptNo,
                        ),
                        dataError = message,
                        storageAvailable = false,
                    )
                }
            }
        }
    }

    /**
     * Re-materializes the page the reader is already on, in place.
     *
     * A stroke arriving over LAN is not a page change. Routing it through [openBook] cleared the
     * snapshot and dropped `documentReady` synchronously for every operation in a flush burst,
     * while the generation guard let only the last reload of the burst repaint - so live monitoring
     * blanked far more often than it drew. Nothing here touches `documentReady`, `pageCount` or the
     * PDF, and no page event is published, which also stops the SUBSCRIBE round trip that the peer
     * used to make per received stroke.
     */
    private fun refreshCurrentPage(
        bookId: String,
        pageNumber: Int,
        liveStudentAttemptNo: Int? = null,
    ) {
        val generation = loadGeneration
        // Erasing emits a burst of operations, and each one used to cost a full page reload plus an
        // O(strokes) cache rebuild on the main thread. That cost grows with the page, which is why a
        // long live session ended in "not responding". The body below re-reads the whole page state
        // anyway, so collapsing a burst into one pass is equivalent and bounded.
        pendingRemoteRefresh?.cancel()
        pendingRemoteRefresh = viewModelScope.launch(Dispatchers.IO) {
            delay(REMOTE_REFRESH_COALESCE_MILLIS)
            mutationMutex.withLock {
                // A page load started after this refresh was queued supersedes it.
                if (generation != loadGeneration) return@withLock
                val stateAtLoad = _uiState.value
                if (!stateAtLoad.matchesRemotePage(bookId, pageNumber)) return@withLock
                val loadedPage = runCatching { store.loadPageState(bookId, pageNumber) }.getOrNull()
                    ?: return@withLock
                val loaded = loadedPage.snapshot
                val snapshotChanged = loaded.revision != stateAtLoad.snapshot.revision ||
                    loaded.appliedOperationIds != stateAtLoad.snapshot.appliedOperationIds
                if (snapshotChanged) {
                    // A full reload is the missed-event fallback. Incremental remote application can
                    // preserve undo in the future; until then only reset it when annotation changed.
                    document = AnnotationDocument(
                        initial = loaded,
                        operationClockHighWater = loadedPage.operationClockHighWater,
                    )
                }
                val latest = _uiState.value
                if (
                    generation != loadGeneration ||
                    !latest.matchesRemotePage(bookId, pageNumber)
                ) return@withLock
                val attempts = library.attempts(bookId, pageNumber)
                val attemptNo = resolveReaderAttemptNo(
                    workflow = latest.workflow,
                    // A pinned attempt survives incoming ink. Without it the teacher is pulled
                    // back to the student's newest attempt on the very next stroke they receive.
                    selectedAttemptNo = latest.attemptNo.takeUnless {
                        latest.workflow == ReaderWorkflow.LIVE_MONITOR && !latest.attemptPinned
                    },
                    attempts = attempts,
                    observedStudentAttemptNos = loaded.studentAttemptNos(),
                    liveStudentAttemptNo = if (
                        latest.workflow == ReaderWorkflow.LIVE_MONITOR &&
                        latest.isFollowingStudent
                    ) {
                        liveStudentAttemptNo ?: latest.studentAttemptNo
                    } else {
                        null
                    },
                )
                _uiState.value = latest.copy(
                    snapshot = if (snapshotChanged) loaded else latest.snapshot,
                    attemptNo = attemptNo,
                    capabilities = ReaderCapabilities.forSession(latest.role, latest.workflow, attemptNo),
                    marks = library.markGroups(bookId, pageNumber),
                    studentAttemptNo = if (latest.workflow == ReaderWorkflow.LIVE_MONITOR) {
                        liveStudentAttemptNo ?: latest.studentAttemptNo
                    } else {
                        null
                    },
                    currentAttemptWritable = latest.role == ReaderRole.STUDENT &&
                        attempts.any { it.attemptNo == attemptNo && !it.locked },
                    currentAttemptSubmitted = latest.role == ReaderRole.STUDENT &&
                        attempts.any { it.attemptNo == attemptNo && it.locked },
                    pageAttemptNos = attempts.map { it.attemptNo },
                    canUndo = if (snapshotChanged) false else latest.canUndo,
                    canRedo = if (snapshotChanged) false else latest.canRedo,
                )
            }
        }
    }

    fun dismissDataError() {
        _uiState.value = _uiState.value.copy(dataError = null)
    }

    fun reportDocumentError() {
        _uiState.value = _uiState.value.copy(
            dataError = "교재 PDF를 열 수 없습니다. 책장으로 돌아가 파일을 다시 확인해 주세요.",
            storageAvailable = false,
        )
    }

    fun addStroke(stroke: StrokeAsset, onComplete: (() -> Unit)? = null) {
        // writableAttempt opens a new attempt once the previous one is submitted, so the number a
        // stroke is written into is not always the one on screen. Report it back to mutate.
        var writtenAttemptNo: Int? = null
        mutate(onComplete, attemptNoAfterChange = { writtenAttemptNo }) { state ->
            val authorId = if (state.role == ReaderRole.STUDENT) "student" else "teacher"
            val attemptNo = when {
                state.role == ReaderRole.STUDENT -> library
                    .writableAttempt(state.bookId, state.pageNumber, create = true)
                    ?.attemptNo
                    ?: error("풀이 회차를 만들 수 없습니다.")

                state.isTeacherPageTarget -> TEACHER_PAGE_REVIEW_ATTEMPT_NO

                state.workflow == ReaderWorkflow.LIVE_MONITOR && state.snapshot.activeStrokes.any {
                    it.authorId == "student" && it.attemptNo == state.attemptNo
                } -> state.attemptNo

                else -> library.attempts(state.bookId, state.pageNumber)
                    .firstOrNull { it.attemptNo == state.attemptNo }
                    ?.attemptNo
                    ?: error("학생 풀이 회차가 없습니다.")
            }
            writtenAttemptNo = attemptNo
            document.addStroke(
                stroke.copy(
                    authorId = authorId,
                    attemptNo = attemptNo,
                    deviceId = library.deviceId,
                )
            )
        }
    }

    internal fun erase(
        target: ReaderAnnotationTarget,
        page: Int,
        path: List<PagePoint>,
        radius: Float,
        wholeStroke: Boolean,
        onComplete: (() -> Unit)? = null,
    ) {
        val forceTeacherSync = _uiState.value.role != ReaderRole.STUDENT
        mutate(onComplete, forceSync = forceTeacherSync) { state ->
            // The target is captured when the S Pen first touches down. A live teacher can be
            // moved to another page/attempt while the eraser is still travelling, so committing
            // against whatever happens to be current on ACTION_UP would erase the wrong layer.
            if (!target.matches(state) || page != target.pageNumber) return@mutate null
            // A student may be looking at a submitted attempt. Erasing must not reach back into
            // it; only an attempt that is still open can be edited.
            val attemptNo = if (state.role == ReaderRole.STUDENT) {
                library.writableAttempt(state.bookId, state.pageNumber, create = false)?.attemptNo
                    ?.takeIf { state.canMutateStudentAttempt(it) }
                    ?: return@mutate null
            } else {
                state.attemptNo
            }
            if (attemptNo != target.attemptNo) return@mutate null
            document.erase(
                page = page,
                path = path,
                radius = radius,
                wholeStroke = wholeStroke,
                authorId = if (state.role == ReaderRole.STUDENT) "student" else "teacher",
                attemptNo = attemptNo,
                deviceId = library.deviceId,
            )
        }
    }

    fun undo() = mutate(syncTeacherAttempt = true) { state ->
        val writable = if (state.role == ReaderRole.STUDENT) {
            library.writableAttempt(state.bookId, state.pageNumber, create = false)?.attemptNo
        } else {
            null
        }
        if (!state.canMutateStudentAttempt(writable)) return@mutate null
        document.undo(library.deviceId)
    }

    fun redo() = mutate(syncTeacherAttempt = true) { state ->
        val writable = if (state.role == ReaderRole.STUDENT) {
            library.writableAttempt(state.bookId, state.pageNumber, create = false)?.attemptNo
        } else {
            null
        }
        if (!state.canMutateStudentAttempt(writable)) return@mutate null
        document.redo(library.deviceId)
    }

    fun submit(onSubmitted: (nextPage: Int) -> Unit) {
        val state = _uiState.value
        if (!state.canSubmitNow || !submissionGate.compareAndSet(false, true)) return
        _uiState.update { latest -> latest.copy(submissionInProgress = true) }
        viewModelScope.launch(Dispatchers.IO) {
            var submitted = false
            try {
                mutationMutex.withLock {
                    val current = _uiState.value
                    if (
                        !current.documentReady ||
                        !current.storageAvailable ||
                        !current.capabilities.canSubmit ||
                        !current.currentAttemptWritable ||
                        pendingDocumentMutations.get() != 0 ||
                        current.bookId != state.bookId ||
                        current.pageNumber != state.pageNumber ||
                        current.attemptNo != state.attemptNo
                    ) return@withLock
                    val attempt = library.writableAttempt(state.bookId, state.pageNumber, create = false)
                        ?: return@withLock
                    if (!current.canMutateStudentAttempt(attempt.attemptNo)) return@withLock
                    val snapshot = document.snapshot()
                    if (snapshot.bookId != state.bookId || snapshot.pageNumber != state.pageNumber) return@withLock
                    store.writeCheckpoint(snapshot)
                    library.lockAttempt(state.bookId, state.pageNumber, attempt.attemptNo)
                    submitted = true
                    val next = (state.pageNumber + 1).coerceAtMost(state.pageCount - 1)
                    withContext(Dispatchers.Main.immediate) { onSubmitted(next) }
                }
            } finally {
                submissionGate.set(false)
                _uiState.update { latest ->
                    latest.copy(
                        submissionInProgress = false,
                        dataError = if (!submitted && latest.dataError == null) {
                            "필기 저장이 끝난 뒤 다시 제출해 주세요."
                        } else {
                            latest.dataError
                        },
                    )
                }
            }
        }
    }

    fun selectAttempt(attemptNo: Int) {
        val state = _uiState.value
        if (!state.capabilities.canBrowseAttempts) return
        val pageTarget = state.role != ReaderRole.STUDENT &&
            attemptNo == TEACHER_PAGE_REVIEW_ATTEMPT_NO
        if (!pageTarget && library.attempts(state.bookId, state.pageNumber).none { it.attemptNo == attemptNo }) return
        openBook(
            bookId = state.bookId,
            pageNumber = state.pageNumber,
            role = state.role,
            selectedAttemptNo = attemptNo,
            workflow = state.workflow,
            pinAttempt = true,
            followRemoteStudent = false,
        )
    }

    /** Leaves local browsing and makes the retained student cursor authoritative again. */
    fun resumeStudentFollow() {
        val state = _uiState.value
        if (
            !state.capabilities.showsStudentLocation ||
            state.bookId.isBlank() ||
            state.pageCount <= 0
        ) return
        val stickyStudentLocation = LanSyncBus.remoteStudentLocation(state.bookId)
        openBook(
            bookId = state.bookId,
            pageNumber = stickyStudentLocation?.pageNumber ?: state.pageNumber,
            role = state.role,
            selectedAttemptNo = null,
            confirmedPageCount = state.pageCount,
            workflow = ReaderWorkflow.LIVE_MONITOR,
            liveStudentAttemptNo = stickyStudentLocation?.attemptNo,
            followRemoteStudent = true,
        )
    }

    fun addGrade(anchor: PagePoint, color: MarkColor, groupId: String? = null) {
        val state = _uiState.value
        if (!state.capabilities.canGrade || !state.documentReady) return
        val observedStudentAttempt = state.workflow == ReaderWorkflow.LIVE_MONITOR &&
            state.snapshot.activeStrokes.any {
                it.authorId == "student" && it.attemptNo == state.attemptNo
            }
        runCatching {
            library.addMark(
                bookId = state.bookId,
                pageNumber = state.pageNumber,
                attemptNo = state.attemptNo,
                anchor = anchor,
                color = color,
                groupId = groupId,
                allowObservedStudentAttempt = observedStudentAttempt,
            )
        }.onSuccess {
            _uiState.value = state.copy(marks = library.markGroups(state.bookId, state.pageNumber))
        }.onFailure {
            _uiState.value = state.copy(dataError = "채점할 학생 풀이 회차를 아직 불러오지 못했습니다.")
        }
    }

    fun changeGrade(groupId: String, color: MarkColor) {
        val state = _uiState.value
        if (!state.capabilities.canGrade) return
        library.changeLatestMarkColor(groupId, state.attemptNo, color)
        _uiState.value = state.copy(marks = library.markGroups(state.bookId, state.pageNumber))
    }

    fun hideMarkGroup(groupId: String) {
        val state = _uiState.value
        if (!state.capabilities.canGrade) return
        library.hideMarkGroup(groupId)
        _uiState.value = state.copy(marks = library.markGroups(state.bookId, state.pageNumber))
    }

    internal fun moveMarkGroup(move: PendingMarkMove, anchor: PagePoint) {
        val state = _uiState.value
        if (!state.capabilities.canGrade || !move.canApply(state)) return
        val targetGroup = library.markGroups(move.target.bookId, move.target.pageNumber)
            .firstOrNull { group ->
                group.id == move.groupId && group.marks.any { mark ->
                    mark.attemptNo == move.target.attemptNo && mark.hiddenAtEpochMillis == null
                }
            } ?: return
        library.moveMarkGroup(targetGroup.id, anchor)
        val latest = _uiState.value
        if (move.target.matches(latest)) {
            _uiState.value = latest.copy(
                marks = library.markGroups(move.target.bookId, move.target.pageNumber),
            )
        }
    }

    fun publishTeacherInk(onComplete: (() -> Unit)? = null) {
        if (!_uiState.value.canPublishTeacherInkNow) return
        mutate(onComplete, forceSync = true) { state ->
            if (!state.canPublishTeacherInkNow) return@mutate null
            document.publishTeacherDrafts(state.attemptNo, library.deviceId)
        }
    }

    private fun mutate(
        onComplete: (() -> Unit)? = null,
        forceSync: Boolean = false,
        syncTeacherAttempt: Boolean = false,
        /** Read after [block] has run, when the change decided its own attempt number. */
        attemptNoAfterChange: (() -> Int?)? = null,
        block: (ReaderUiState) -> AnnotationChange?,
    ) {
        val pending = pendingDocumentMutations.incrementAndGet()
        _uiState.update { latest -> latest.copy(pendingDocumentMutations = pending) }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                mutationMutex.withLock {
                    val before = _uiState.value
                    if (!before.documentReady || !before.storageAvailable || before.submissionInProgress) {
                        return@withLock
                    }
                    val beforeSnapshot = document.snapshot()
                    val beforeClockHighWater = document.operationClockHighWater
                    try {
                        val change = block(before) ?: return@withLock
                        val persisted = withContext(Dispatchers.IO) { store.append(change) }
                    val attemptNo = attemptNoAfterChange?.invoke() ?: before.attemptNo
                    val mergedConcurrentOperation = persisted.appliedOperationIds !=
                        change.snapshot.appliedOperationIds
                    val mergedPage = if (mergedConcurrentOperation) {
                        withContext(Dispatchers.IO) {
                            store.loadPageState(before.bookId, before.pageNumber)
                        }
                    } else {
                        null
                    }
                    val persistedSnapshot = mergedPage?.snapshot ?: persisted
                    if (mergedPage != null) {
                        document = AnnotationDocument(
                            initial = persistedSnapshot,
                            operationClockHighWater = mergedPage.operationClockHighWater,
                        )
                    }
                    val latest = _uiState.value
                    if (latest.bookId == before.bookId && latest.pageNumber == before.pageNumber) {
                        _uiState.value = latest.copy(
                            snapshot = persistedSnapshot,
                            attemptNo = attemptNo,
                            capabilities = if (attemptNo == latest.attemptNo) {
                                latest.capabilities
                            } else {
                                ReaderCapabilities.forSession(latest.role, latest.workflow, attemptNo)
                            },
                            currentAttemptWritable = if (latest.role == ReaderRole.STUDENT) {
                                library.attempts(latest.bookId, latest.pageNumber)
                                    .any { it.attemptNo == attemptNo && !it.locked }
                            } else {
                                false
                            },
                            currentAttemptSubmitted = if (latest.role == ReaderRole.STUDENT) {
                                library.attempts(latest.bookId, latest.pageNumber)
                                    .any { it.attemptNo == attemptNo && it.locked }
                            } else {
                                false
                            },
                            // The first stroke after a submission opens a new attempt, which has to
                            // appear in the chrome stack straight away.
                            pageAttemptNos = library.attempts(latest.bookId, latest.pageNumber)
                                .map { it.attemptNo },
                            canUndo = if (mergedConcurrentOperation) false else document.canUndo,
                            canRedo = if (mergedConcurrentOperation) false else document.canRedo,
                        )
                    }
                    if (
                        before.role == ReaderRole.STUDENT ||
                        forceSync ||
                        (syncTeacherAttempt && before.shouldForceSyncTeacherUndoRedo)
                    ) {
                        LanSyncBus.operationWritten(before.bookId, before.pageNumber)
                    }
                    if (before.role == ReaderRole.STUDENT && attemptNo != before.attemptNo) {
                        // The first stroke after submission opens N+1 without a page navigation.
                        // Publish that cursor explicitly so a live teacher follows the new attempt
                        // even when ATTEMPT_UPSERT and OPERATION arrive in either order.
                        LanSyncBus.pageChanged(
                            bookId = before.bookId,
                            pageNumber = before.pageNumber,
                            revision = persistedSnapshot.revision,
                            attemptNo = attemptNo,
                        )
                    }
                    } catch (error: Throwable) {
                        document = AnnotationDocument(
                            initial = beforeSnapshot,
                            operationClockHighWater = beforeClockHighWater,
                        )
                        val latest = _uiState.value
                        if (latest.bookId == before.bookId && latest.pageNumber == before.pageNumber) {
                            _uiState.value = latest.copy(
                                dataError = "필기를 저장하지 못했습니다. 기존 저장 내용은 유지됩니다.",
                            )
                        }
                    }
                }
            } finally {
                val remaining = pendingDocumentMutations.decrementAndGet().coerceAtLeast(0)
                _uiState.update { latest -> latest.copy(pendingDocumentMutations = remaining) }
                withContext(Dispatchers.Main.immediate) { onComplete?.invoke() }
            }
        }
    }

    /**
     * Samples what the student has written, once every [ACTIVITY_SAMPLE_MILLIS].
     *
     * Deliberately a reader of state that already exists rather than a hook in the drawing or sync
     * path: it wakes on a timer, diffs the strokes the teacher has already received, and goes back
     * to sleep. Nothing here runs while a stroke is being drawn, erased or transmitted, so it
     * cannot slow those down however busy the page gets.
     */
    private fun startActivitySampling() {
        if (activitySampler?.isActive == true) return
        activitySampler = viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                delay(ACTIVITY_SAMPLE_MILLIS)
                val state = _uiState.value
                if (!state.capabilities.showsStudentLocation) continue
                val fresh = state.snapshot.activeStrokes.filter { stroke ->
                    stroke.authorId == "student" && stroke.logicalClock > lastSampledClock
                }
                lastSampledClock = state.snapshot.activeStrokes
                    .maxOfOrNull(StrokeAsset::logicalClock) ?: lastSampledClock
                val sample = summariseActivity(
                    startedAtEpochMillis = System.currentTimeMillis(),
                    pageNumber = state.pageNumber,
                    strokes = fresh.map(StrokeAsset::points),
                )
                val latest = _uiState.value
                if (!latest.capabilities.showsStudentLocation) continue
                _uiState.value = latest.copy(
                    activitySamples = (latest.activitySamples + sample).trimmedTo(ACTIVITY_SAMPLE_WINDOW),
                )
            }
        }
    }

    private fun stopActivitySampling() {
        activitySampler?.cancel()
        activitySampler = null
    }

    private companion object {
        /** Burst window for incoming LAN operations. Long enough to swallow an erase stroke's
         *  fragment operations, short enough to still read as live. */
        const val REMOTE_REFRESH_COALESCE_MILLIS = 70L
        /** One bucket per ten seconds, as requested for the concentration read-out. */
        const val ACTIVITY_SAMPLE_MILLIS = 10_000L
        /** Sixty buckets is the last ten minutes, which is all the graph shows. */
        const val ACTIVITY_SAMPLE_WINDOW = 60
    }
}
