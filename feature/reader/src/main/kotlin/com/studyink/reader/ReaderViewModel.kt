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
import com.studyink.core.model.TEACHER_PAGE_REVIEW_ATTEMPT_NO
import com.studyink.library.data.LibraryRepository
import com.studyink.sync.lan.LanSyncBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
): Int = when (workflow) {
    ReaderWorkflow.STUDY -> attempts.lastOrNull { !it.locked }?.attemptNo
        ?: (attempts.maxOfOrNull { it.attemptNo } ?: 0) + 1

    ReaderWorkflow.REVIEW -> selectedAttemptNo?.takeIf { requested ->
        requested == TEACHER_PAGE_REVIEW_ATTEMPT_NO ||
            attempts.any { it.attemptNo == requested && it.locked }
    } ?: attempts.lastOrNull { it.locked }?.attemptNo
        ?: TEACHER_PAGE_REVIEW_ATTEMPT_NO

    ReaderWorkflow.LIVE_MONITOR -> selectedAttemptNo?.takeIf { requested ->
        requested > TEACHER_PAGE_REVIEW_ATTEMPT_NO &&
            (attempts.any { it.attemptNo == requested } || requested in observedStudentAttemptNos)
    } ?: attempts.maxOfOrNull { it.attemptNo }
        ?: observedStudentAttemptNos.maxOrNull()
        ?: TEACHER_PAGE_REVIEW_ATTEMPT_NO
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
) {
    val isTeacherPageTarget: Boolean
        get() = role != ReaderRole.STUDENT &&
            attemptNo == TEACHER_PAGE_REVIEW_ATTEMPT_NO

    val attemptDisplayLabel: String
        get() = if (isTeacherPageTarget) "페이지 표시" else "${attemptNo}회"

    val canSubmitNow: Boolean
        get() = documentReady && storageAvailable && capabilities.canSubmit

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

internal fun ReaderUiState.matchesRemotePage(bookId: String, pageNumber: Int): Boolean =
    this.bookId == bookId && this.pageNumber == pageNumber

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val store = PageOperationLogStore(application)
    private val library = LibraryRepository.get(application)
    private val mutationMutex = Mutex()
    private var document = AnnotationDocument(AnnotationSnapshot.empty("unopened"))
    private var loadGeneration = 0L
    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()
    private val syncListener = object : LanSyncBus.Listener {
        override fun onRemoteOperation(bookId: String, pageNumber: Int) {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                val state = _uiState.value
                if (!state.matchesRemotePage(bookId, pageNumber)) return@launch
                openBook(
                    bookId = bookId,
                    pageNumber = pageNumber,
                    role = state.role,
                    selectedAttemptNo = state.attemptNo.takeUnless {
                        state.workflow == ReaderWorkflow.LIVE_MONITOR
                    },
                    workflow = state.workflow,
                )
            }
        }

        override fun onRemoteAttempt(bookId: String, pageNumber: Int) {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                val state = _uiState.value
                if (
                    !state.matchesRemotePage(bookId, pageNumber) ||
                    state.workflow != ReaderWorkflow.LIVE_MONITOR
                ) return@launch
                openBook(
                    bookId = bookId,
                    pageNumber = pageNumber,
                    role = state.role,
                    selectedAttemptNo = null,
                    workflow = state.workflow,
                )
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

        override fun onRemotePageChanged(bookId: String, pageNumber: Int) {
            viewModelScope.launch(Dispatchers.Main.immediate) {
                val latest = _uiState.value
                if (latest.bookId == bookId && latest.capabilities.showsStudentLocation) {
                    _uiState.value = latest.copy(studentPageNumber = pageNumber)
                }
            }
        }
    }

    init { LanSyncBus.addListener(syncListener) }

    override fun onCleared() {
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
    ) {
        val book = library.book(bookId)
        val target = pageNumber.coerceIn(0, book.pageCount - 1)
        val resolvedWorkflow = if (role == ReaderRole.STUDENT) ReaderWorkflow.STUDY else workflow
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
            val result = runCatching { store.loadPage(book.id, target) }
            mutationMutex.withLock {
                if (generation != loadGeneration) return@withLock
                result.onSuccess { loaded ->
                    document = AnnotationDocument(loaded)
                    val attempts = library.attempts(book.id, target)
                    val observedStudentAttemptNos = loaded.activeStrokes.asSequence()
                        .filter { it.authorId == "student" && it.attemptNo > TEACHER_PAGE_REVIEW_ATTEMPT_NO }
                        .map { it.attemptNo }
                        .toSet()
                    val attemptNo = resolveReaderAttemptNo(
                        workflow = resolvedWorkflow,
                        selectedAttemptNo = selectedAttemptNo,
                        attempts = attempts,
                        observedStudentAttemptNos = observedStudentAttemptNos,
                    )
                    _uiState.value = ReaderUiState(
                        snapshot = loaded,
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
                    )
                    LanSyncBus.pageChanged(book.id, target, loaded.revision)
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
        mutate(onComplete) { state ->
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
            document.addStroke(
                stroke.copy(
                    authorId = authorId,
                    attemptNo = attemptNo,
                    deviceId = library.deviceId,
                )
            )
        }
    }

    fun erase(
        page: Int,
        path: List<PagePoint>,
        radius: Float,
        wholeStroke: Boolean,
        onComplete: (() -> Unit)? = null,
    ) {
        val forceTeacherSync = _uiState.value.role != ReaderRole.STUDENT
        mutate(onComplete, forceSync = forceTeacherSync) { state ->
            document.erase(
                page = page,
                path = path,
                radius = radius,
                wholeStroke = wholeStroke,
                authorId = if (state.role == ReaderRole.STUDENT) "student" else "teacher",
                attemptNo = state.attemptNo,
                deviceId = library.deviceId,
            )
        }
    }

    fun undo() = mutate(syncTeacherAttempt = true) { document.undo(library.deviceId) }
    fun redo() = mutate(syncTeacherAttempt = true) { document.redo(library.deviceId) }

    fun submit(onSubmitted: (nextPage: Int) -> Unit) {
        val state = _uiState.value
        if (!state.canSubmitNow) return
        viewModelScope.launch(Dispatchers.IO) {
            mutationMutex.withLock {
                val current = _uiState.value
                if (
                    !current.canSubmitNow ||
                    current.bookId != state.bookId ||
                    current.pageNumber != state.pageNumber ||
                    current.attemptNo != state.attemptNo
                ) return@withLock
                val attempt = library.writableAttempt(state.bookId, state.pageNumber, create = false)
                    ?: return@withLock
                val snapshot = document.snapshot()
                if (snapshot.bookId != state.bookId || snapshot.pageNumber != state.pageNumber) return@withLock
                store.writeCheckpoint(snapshot)
                library.lockAttempt(state.bookId, state.pageNumber, attempt.attemptNo)
                val next = (state.pageNumber + 1).coerceAtMost(state.pageCount - 1)
                withContext(Dispatchers.Main.immediate) { onSubmitted(next) }
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
        block: (ReaderUiState) -> AnnotationChange?,
    ) {
        viewModelScope.launch(Dispatchers.Default) {
            mutationMutex.withLock {
                val before = _uiState.value
                if (!before.documentReady || !before.storageAvailable) return@withLock
                val beforeSnapshot = document.snapshot()
                try {
                    val change = block(before) ?: return@withLock
                    withContext(Dispatchers.IO) { store.append(change) }
                    _uiState.value = before.copy(
                        snapshot = change.snapshot,
                        canUndo = document.canUndo,
                        canRedo = document.canRedo,
                    )
                    if (
                        before.role == ReaderRole.STUDENT ||
                        forceSync ||
                        (syncTeacherAttempt && before.shouldForceSyncTeacherUndoRedo)
                    ) {
                        LanSyncBus.operationWritten(before.bookId, before.pageNumber)
                    }
                } catch (error: Throwable) {
                    document = AnnotationDocument(beforeSnapshot)
                    _uiState.value = before.copy(dataError = "필기를 저장하지 못했습니다. 기존 저장 내용은 유지됩니다.")
                }
            }
            withContext(Dispatchers.Main.immediate) { onComplete?.invoke() }
        }
    }
}
