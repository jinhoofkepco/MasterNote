package com.studyink.reader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studyink.annotation.engine.AnnotationDocument
import com.studyink.annotation.storage.RoomAnnotationStore
import com.studyink.core.model.AnnotationMutation
import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
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
)

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private sealed interface Command {
        data class LoadDocument(
            val generation: Long,
            val uri: Uri,
            val label: String,
            val pageCount: Int,
        ) : Command

        data class Mutate(
            val progressText: String,
            val onComplete: (() -> Unit)?,
            val block: AnnotationDocument.() -> AnnotationMutation?,
        ) : Command

        data class Flush(val completion: CompletableDeferred<Unit>) : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val pendingOperations = AtomicInteger(0)
    private var store: RoomAnnotationStore? = null
    private var document = AnnotationDocument(AnnotationSnapshot.empty("sample"))
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
                        runCatching { annotationStore().flush() }
                        command.completion.complete(Unit)
                    }
                }
            }
        }
    }

    fun loadDocument(uri: Uri, label: String, pageCount: Int) {
        val generation = ++documentLoadGeneration
        _uiState.value = ReaderUiState(
            snapshot = AnnotationSnapshot.empty("loading-$generation"),
            documentLabel = label,
            pageCount = pageCount,
            busy = true,
            status = "PDF 필기 불러오는 중…",
            pendingSaveOperations = pendingOperations.get(),
        )
        check(commands.trySend(Command.LoadDocument(generation, uri, label, pageCount)).isSuccess)
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
        val loaded = runCatching {
            val documentId = withContext(Dispatchers.IO) {
                DocumentIdentity.create(getApplication(), command.uri)
            }
            annotationStore().load(documentId)
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
        document = AnnotationDocument(loaded)
        _uiState.value = ReaderUiState(
            snapshot = loaded,
            documentLabel = command.label,
            pageCount = command.pageCount,
            status = "자동 저장 켜짐 · 필기 ${loaded.activeStrokeIds.size}개 복원",
            pendingSaveOperations = pendingOperations.get(),
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

        runCatching { annotationStore().applyMutation(mutation) }
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
}
