package com.studyink.reader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studyink.annotation.engine.AnnotationDocument
import com.studyink.annotation.storage.RoomAnnotationStore
import com.studyink.annotation.storage.RoomLearningRepository
import com.studyink.core.model.AnnotationMutation
import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.AttemptId
import com.studyink.core.model.AttemptSession
import com.studyink.core.model.PageId
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

data class ReaderUiState(
    val snapshot: AnnotationSnapshot = AnnotationSnapshot.empty("sample"),
    val documentLabel: String = "예제 학습지",
    val pageCount: Int = 1,
    val busy: Boolean = false,
    val status: String = "준비됨",
    val pendingSaveOperations: Int = 0,
    val lastSavedAtEpochMillis: Long? = null,
    val attemptSession: AttemptSession? = null,
    val initialPageNumber: Int = 0,
)

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private sealed interface Command {
        data class LoadDocument(
            val generation: Long,
            val uri: Uri,
            val label: String,
            val pageCount: Int,
            val launchArgs: ReaderLaunchArgs?,
        ) : Command

        data class Mutate(
            val progressText: String,
            val onComplete: (() -> Unit)?,
            val block: AnnotationDocument.() -> AnnotationMutation?,
        ) : Command

        data class Flush(val completion: CompletableDeferred<Unit>) : Command
        data class PreparePage(val attemptId: AttemptId, val pageId: PageId) : Command
        data class PersistResumePage(val attemptId: AttemptId, val pageId: PageId) : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val pendingOperations = AtomicInteger(0)
    private var store: RoomAnnotationStore? = null
    private var learningStore: RoomLearningRepository? = null
    private var document = AnnotationDocument(AnnotationSnapshot.empty("sample"))
    private var activeAttemptId: AttemptId? = null
    private var activeSession: AttemptSession? = null
    private var selectedPageId: PageId? = null
    private var resumePageJob: Job? = null
    private var documentLoadGeneration = 0L
    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.Default) {
            for (command in commands) {
                when (command) {
                    is Command.LoadDocument -> handleLoad(command)
                    is Command.Mutate -> handleMutation(command)
                    is Command.Flush -> {
                        resumePageJob?.cancel()
                        persistSelectedPage()
                        runCatching { annotationStore().flush() }
                        command.completion.complete(Unit)
                    }
                    is Command.PreparePage -> runCatching {
                        learningRepository().prepareAttemptPage(command.attemptId, command.pageId)
                    }
                    is Command.PersistResumePage -> runCatching {
                        learningRepository().updateResumePage(command.attemptId, command.pageId)
                    }
                }
            }
        }
    }

    fun loadDocument(
        uri: Uri,
        label: String,
        pageCount: Int,
        launchArgs: ReaderLaunchArgs? = null,
    ) {
        val generation = ++documentLoadGeneration
        _uiState.value = ReaderUiState(
            snapshot = AnnotationSnapshot.empty("loading-$generation"),
            documentLabel = label,
            pageCount = pageCount,
            busy = true,
            status = "PDF 필기 불러오는 중…",
            pendingSaveOperations = pendingOperations.get(),
        )
        check(commands.trySend(Command.LoadDocument(generation, uri, label, pageCount, launchArgs)).isSuccess)
    }

    fun onPageSelected(pageNumber: Int) {
        val session = activeSession ?: return
        val page = session.pages.firstOrNull { it.pageNumber == pageNumber } ?: return
        selectedPageId = page.pageId
        val attemptId = session.attempt.attemptId
        check(commands.trySend(Command.PreparePage(attemptId, page.pageId)).isSuccess)
        resumePageJob?.cancel()
        resumePageJob = viewModelScope.launch {
            delay(RESUME_PAGE_DEBOUNCE_MILLIS)
            commands.send(Command.PersistResumePage(attemptId, page.pageId))
        }
    }

    fun addStroke(stroke: StrokeAsset, onComplete: (() -> Unit)? = null) =
        enqueueMutation("저장 중…", onComplete) { addStroke(stroke) }

    fun erase(
        page: Int,
        path: List<PagePoint>,
        radius: Float,
        wholeStroke: Boolean,
        onComplete: (() -> Unit)? = null,
    ) = enqueueMutation(
        if (wholeStroke) "선 지우는 중…" else "부분 지우개 계산 중…",
        onComplete,
    ) { erase(page, path, radius, wholeStroke) }

    fun undo() = enqueueMutation("되돌리는 중…") { undo() }
    fun redo() = enqueueMutation("다시 실행 중…") { redo() }

    suspend fun flush() {
        val completion = CompletableDeferred<Unit>()
        commands.send(Command.Flush(completion))
        completion.await()
    }

    fun flushAsync() {
        viewModelScope.launch { flush() }
    }

    private fun enqueueMutation(
        progressText: String,
        onComplete: (() -> Unit)? = null,
        block: AnnotationDocument.() -> AnnotationMutation?,
    ) {
        val pending = pendingOperations.incrementAndGet()
        _uiState.value = _uiState.value.copy(
            pendingSaveOperations = pending,
            status = progressText,
        )
        check(commands.trySend(Command.Mutate(progressText, onComplete, block)).isSuccess)
    }

    private suspend fun handleLoad(command: Command.LoadDocument) {
        val loadResult = runCatching {
            val documentId = withContext(Dispatchers.IO) {
                DocumentIdentity.create(getApplication(), command.uri)
            }
            val session = command.launchArgs?.let { args ->
                learningRepository().getAttemptSession(args.attemptId).also {
                    check(it.attempt.profileId == args.profileId)
                    check(it.attempt.activityId == args.activityId)
                    check(it.documentId == documentId) { "Activity PDF does not match its book revision" }
                }
            }
            val initialPageId = command.launchArgs?.initialPageId
                ?.takeIf { requested -> session?.pages?.any { it.pageId == requested } == true }
                ?: session?.initialPageId
            if (session != null && initialPageId != null) {
                learningRepository().prepareAttemptPage(session.attempt.attemptId, initialPageId)
            }
            Triple(
                annotationStore().load(documentId, session?.attempt?.attemptId?.value),
                session,
                initialPageId,
            )
        }.getOrElse { error ->
            if (command.generation == documentLoadGeneration) {
                _uiState.value = _uiState.value.copy(
                    busy = false,
                    status = "필기 불러오기 실패: ${error.message}",
                )
            }
            return
        }
        if (command.generation != documentLoadGeneration) return
        val (loaded, session, initialPageId) = loadResult
        document = AnnotationDocument(loaded)
        activeSession = session
        activeAttemptId = session?.attempt?.attemptId
        selectedPageId = initialPageId
        val initialPageNumber = session?.pages?.firstOrNull { it.pageId == initialPageId }?.pageNumber ?: 0
        _uiState.value = ReaderUiState(
            snapshot = loaded,
            documentLabel = command.label,
            pageCount = command.pageCount,
            status = "자동 저장 켜짐 · 필기 ${loaded.activeStrokeIds.size}개 복원",
            pendingSaveOperations = pendingOperations.get(),
            attemptSession = session,
            initialPageNumber = initialPageNumber,
        )
    }

    private suspend fun handleMutation(command: Command.Mutate) {
        val before = document.snapshot()
        _uiState.value = _uiState.value.copy(busy = true, status = command.progressText)
        val mutation = runCatching { command.block(document) }
            .getOrElse { error ->
                finishMutation(command, "필기 계산 실패: ${error.message}")
                return
            }

        if (mutation == null) {
            finishMutation(command, "변경 없음")
            return
        }

        _uiState.value = _uiState.value.copy(
            snapshot = mutation.snapshot,
            busy = false,
            status = "저장 대기 중…",
        )

        runCatching { annotationStore().applyMutation(mutation, activeAttemptId?.value) }
            .onSuccess {
                val savedAt = System.currentTimeMillis()
                finishMutation(command, "자동 저장됨 · 리비전 ${mutation.snapshot.revision}", savedAt)
            }
            .onFailure { error ->
                document = AnnotationDocument(before)
                _uiState.value = _uiState.value.copy(snapshot = before)
                finishMutation(command, "저장 실패 · 변경 취소: ${error.message}")
            }
    }

    private suspend fun finishMutation(
        command: Command.Mutate,
        status: String,
        savedAt: Long? = _uiState.value.lastSavedAtEpochMillis,
    ) {
        val pending = pendingOperations.decrementAndGet().coerceAtLeast(0)
        _uiState.value = _uiState.value.copy(
            busy = false,
            status = status,
            pendingSaveOperations = pending,
            lastSavedAtEpochMillis = savedAt,
        )
        withContext(Dispatchers.Main.immediate) { command.onComplete?.invoke() }
    }

    private suspend fun annotationStore(): RoomAnnotationStore {
        store?.let { return it }
        return RoomAnnotationStore.open(getApplication()).also { store = it }
    }

    private suspend fun learningRepository(): RoomLearningRepository {
        learningStore?.let { return it }
        return RoomLearningRepository.open(getApplication()).also { learningStore = it }
    }

    private suspend fun persistSelectedPage() {
        val attemptId = activeAttemptId ?: return
        val pageId = selectedPageId ?: return
        runCatching { learningRepository().updateResumePage(attemptId, pageId) }
    }

    private companion object {
        const val RESUME_PAGE_DEBOUNCE_MILLIS = 750L
    }
}
