package com.studyink.reader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studyink.annotation.engine.AnnotationDocument
import com.studyink.annotation.storage.AtomicAnnotationStore
import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ReaderUiState(
    val snapshot: AnnotationSnapshot = AnnotationSnapshot.empty("sample"),
    val documentLabel: String = "예제 학습지",
    val pageCount: Int = 1,
    val busy: Boolean = false,
    val status: String = "준비됨",
)

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val store = AtomicAnnotationStore(application)
    private val mutationMutex = Mutex()
    private var document = AnnotationDocument(AnnotationSnapshot.empty("sample"))
    private var documentLoadGeneration = 0L
    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    fun loadDocument(uri: Uri, label: String, pageCount: Int) {
        val generation = ++documentLoadGeneration
        _uiState.value = ReaderUiState(
            snapshot = AnnotationSnapshot.empty("loading-$generation"),
            documentLabel = label,
            pageCount = pageCount,
            busy = true,
            status = "PDF 필기 불러오는 중…",
        )
        viewModelScope.launch(Dispatchers.IO) {
            val documentId = DocumentIdentity.create(getApplication(), uri)
            val loaded = store.load(documentId)
            mutationMutex.withLock {
                if (generation != documentLoadGeneration) return@withLock
                document = AnnotationDocument(loaded)
                _uiState.value = ReaderUiState(
                    snapshot = loaded,
                    documentLabel = label,
                    pageCount = pageCount,
                    status = "자동 저장 켜짐 · 필기 ${loaded.activeStrokeIds.size}개 복원",
                )
            }
        }
    }

    fun addStroke(stroke: StrokeAsset, onComplete: (() -> Unit)? = null) = mutate("저장 중…", onComplete) {
        document.addStroke(stroke)
    }

    fun erase(
        page: Int,
        path: List<PagePoint>,
        radius: Float,
        wholeStroke: Boolean,
        onComplete: (() -> Unit)? = null,
    ) = mutate(if (wholeStroke) "선 지우는 중…" else "부분 지우개 계산 중…", onComplete) {
        document.erase(page, path, radius, wholeStroke)
    }

    fun undo() = mutate("되돌리는 중…") { document.undo() }
    fun redo() = mutate("다시 실행 중…") { document.redo() }

    private fun mutate(
        progressText: String,
        onComplete: (() -> Unit)? = null,
        block: () -> AnnotationSnapshot,
    ) {
        viewModelScope.launch(Dispatchers.Default) {
            mutationMutex.withLock {
                _uiState.value = _uiState.value.copy(busy = true, status = progressText)
                val snapshot = block()
                withContext(Dispatchers.IO) { store.save(snapshot) }
                _uiState.value = _uiState.value.copy(
                    snapshot = snapshot,
                    busy = false,
                    status = "자동 저장됨 · 리비전 ${snapshot.revision}",
                )
            }
            withContext(Dispatchers.Main.immediate) { onComplete?.invoke() }
        }
    }
}
