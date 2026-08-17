package com.studyink.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studyink.annotation.engine.AnnotationChange
import com.studyink.annotation.engine.AnnotationDocument
import com.studyink.annotation.storage.CorruptAnnotationDataException
import com.studyink.annotation.storage.PageOperationLogStore
import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.MarkColor
import com.studyink.core.model.MarkGroup
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
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
    }
}

data class ReaderUiState(
    val snapshot: AnnotationSnapshot = AnnotationSnapshot.empty("unopened"),
    val bookId: String = "",
    val bookTitle: String = "",
    val pageCount: Int = 1,
    val pageNumber: Int = 0,
    val attemptNo: Int = 1,
    val role: ReaderRole = ReaderRole.STUDENT,
    val capabilities: ReaderCapabilities = ReaderCapabilities.forRole(ReaderRole.STUDENT),
    val marks: List<MarkGroup> = emptyList(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val dataError: String? = null,
    val storageAvailable: Boolean = true,
    val studentPageNumber: Int? = null,
)

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
            val state = _uiState.value
            if (state.bookId == bookId && state.pageNumber == pageNumber) {
                openBook(bookId, pageNumber, state.role, state.attemptNo)
            }
        }

        override fun onRemotePageChanged(bookId: String, pageNumber: Int) {
            val state = _uiState.value
            if (state.bookId == bookId && state.capabilities.showsStudentLocation) {
                _uiState.value = state.copy(studentPageNumber = pageNumber)
            }
        }
    }

    init { LanSyncBus.addListener(syncListener) }

    override fun onCleared() {
        LanSyncBus.removeListener(syncListener)
        val state = _uiState.value
        if (state.bookId.isNotBlank() && state.storageAvailable) {
            runCatching {
                val valid = library.attempts(state.bookId, state.pageNumber).mapTo(mutableSetOf()) { it.attemptNo }
                store.garbageCollectOrphans(document.snapshot(), valid)
            }
        }
        super.onCleared()
    }

    fun openBook(bookId: String, pageNumber: Int, role: ReaderRole, selectedAttemptNo: Int? = null) {
        val book = library.book(bookId)
        val target = pageNumber.coerceIn(0, book.pageCount - 1)
        val generation = ++loadGeneration
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { store.loadPage(book.id, target) }
            mutationMutex.withLock {
                if (generation != loadGeneration) return@withLock
                result.onSuccess { loaded ->
                    document = AnnotationDocument(loaded)
                    val attempts = library.attempts(book.id, target)
                    val attemptNo = selectedAttemptNo?.takeIf { role != ReaderRole.STUDENT }
                        ?: attempts.lastOrNull { !it.locked }?.attemptNo
                        ?: (attempts.maxOfOrNull { it.attemptNo } ?: 0) + 1
                    _uiState.value = ReaderUiState(
                        snapshot = loaded,
                        bookId = book.id,
                        bookTitle = book.title,
                        pageCount = book.pageCount,
                        pageNumber = target,
                        attemptNo = attemptNo,
                        role = role,
                        capabilities = ReaderCapabilities.forRole(role),
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
                        pageCount = book.pageCount,
                        pageNumber = target,
                        role = role,
                        capabilities = ReaderCapabilities.forRole(role),
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
            val attemptNo = if (state.role == ReaderRole.STUDENT) {
                library.writableAttempt(state.bookId, state.pageNumber, create = true)?.attemptNo
                    ?: error("풀이 회차를 만들 수 없습니다.")
            } else {
                library.attempts(state.bookId, state.pageNumber)
                    .firstOrNull { it.attemptNo == state.attemptNo }?.attemptNo
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
        mutate(onComplete) { state ->
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

    fun undo() = mutate { document.undo(library.deviceId) }
    fun redo() = mutate { document.redo(library.deviceId) }

    fun submit(onSubmitted: (nextPage: Int) -> Unit) {
        val state = _uiState.value
        if (!state.capabilities.canSubmit) return
        viewModelScope.launch(Dispatchers.IO) {
            mutationMutex.withLock {
                val attempt = library.writableAttempt(state.bookId, state.pageNumber, create = false)
                    ?: return@withLock
                store.writeCheckpoint(document.snapshot())
                library.lockAttempt(state.bookId, state.pageNumber, attempt.attemptNo)
                val next = (state.pageNumber + 1).coerceAtMost(state.pageCount - 1)
                withContext(Dispatchers.Main.immediate) { onSubmitted(next) }
            }
        }
    }

    fun selectAttempt(attemptNo: Int) {
        val state = _uiState.value
        if (!state.capabilities.canBrowseAttempts) return
        if (library.attempts(state.bookId, state.pageNumber).none { it.attemptNo == attemptNo }) return
        _uiState.value = state.copy(attemptNo = attemptNo)
    }

    fun addGrade(anchor: PagePoint, color: MarkColor, groupId: String? = null) {
        val state = _uiState.value
        if (!state.capabilities.canGrade) return
        library.addMark(state.bookId, state.pageNumber, state.attemptNo, anchor, color, groupId)
        _uiState.value = state.copy(marks = library.markGroups(state.bookId, state.pageNumber))
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

    fun moveMarkGroup(groupId: String, anchor: PagePoint) {
        val state = _uiState.value
        if (!state.capabilities.canGrade) return
        library.moveMarkGroup(groupId, anchor)
        _uiState.value = state.copy(marks = library.markGroups(state.bookId, state.pageNumber))
    }

    fun publishTeacherInk(onComplete: (() -> Unit)? = null) =
        mutate(onComplete, forceSync = true) { state -> document.publishTeacherDrafts(state.attemptNo, library.deviceId) }

    private fun mutate(
        onComplete: (() -> Unit)? = null,
        forceSync: Boolean = false,
        block: (ReaderUiState) -> AnnotationChange?,
    ) {
        viewModelScope.launch(Dispatchers.Default) {
            mutationMutex.withLock {
                val before = _uiState.value
                val beforeSnapshot = document.snapshot()
                try {
                    val change = block(before) ?: return@withLock
                    withContext(Dispatchers.IO) { store.append(change) }
                    _uiState.value = before.copy(
                        snapshot = change.snapshot,
                        attemptNo = change.addedAssets.firstOrNull()?.attemptNo ?: before.attemptNo,
                        canUndo = document.canUndo,
                        canRedo = document.canRedo,
                    )
                    if (before.role == ReaderRole.STUDENT || forceSync) {
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
