package com.studyink.reader

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studyink.annotation.engine.AnnotationDocument
import com.studyink.annotation.storage.RoomAnnotationStore
import com.studyink.annotation.storage.RoomLearningRepository
import com.studyink.annotation.storage.RoomTeacherRepository
import com.studyink.annotation.storage.SubmitAttemptUseCase
import com.studyink.core.model.AnnotationMutation
import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.AttemptId
import com.studyink.core.model.AttemptSession
import com.studyink.core.model.PageId
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.SubmissionId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    val readOnly: Boolean = false,
    val submissionId: SubmissionId? = null,
    val scene: ReaderScene? = null,
    val layerVisibility: List<Boolean> = emptyList(),
    val currentPageId: PageId? = null,
)

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private sealed interface Command {
        data class LoadDocument(
            val generation: Long,
            val uri: Uri,
            val label: String,
            val pageCount: Int,
            val launchArgs: ReaderLaunchArgs?,
            val scene: ReaderScene?,
        ) : Command

        data class Mutate(
            val progressText: String,
            val onComplete: (() -> Unit)?,
            val block: AnnotationDocument.() -> AnnotationMutation?,
        ) : Command

        data class Flush(val completion: CompletableDeferred<Unit>) : Command
        data class PreparePage(val attemptId: AttemptId, val pageId: PageId) : Command
        data class PersistResumePage(val attemptId: AttemptId, val pageId: PageId) : Command
        data class Submit(val onSubmitted: (SubmissionId) -> Unit) : Command
    }

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val pendingOperations = AtomicInteger(0)
    private var store: RoomAnnotationStore? = null
    private var learningStore: RoomLearningRepository? = null
    private var teacherStore: RoomTeacherRepository? = null
    private var document = AnnotationDocument(AnnotationSnapshot.empty("sample"))
    private var readOnlySceneSnapshot = AnnotationSnapshot.empty("sample")
    private var sceneSourceSnapshots: List<Pair<ReaderLayerSource, AnnotationSnapshot>> = emptyList()
    private var sceneVisibility: MutableList<Boolean> = mutableListOf()
    private var activeAttemptId: AttemptId? = null
    private var activeSession: AttemptSession? = null
    private var activeScene: ReaderScene? = null
    private var scenePages: List<com.studyink.core.model.ActivityPage> = emptyList()
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
                        runCatching {
                            if (!_uiState.value.readOnly && activeScene == null) persistSelectedPageOrThrow()
                            annotationStore().flush()
                        }.fold(
                            onSuccess = { command.completion.complete(Unit) },
                            onFailure = command.completion::completeExceptionally,
                        )
                    }
                    is Command.PreparePage -> runCatching {
                        learningRepository().prepareAttemptPage(command.attemptId, command.pageId)
                    }
                    is Command.PersistResumePage -> runCatching {
                        learningRepository().updateResumePage(command.attemptId, command.pageId)
                    }
                    is Command.Submit -> handleSubmit(command)
                }
            }
        }
    }

    fun loadDocument(
        uri: Uri,
        label: String,
        pageCount: Int,
        launchArgs: ReaderLaunchArgs? = null,
        scene: ReaderScene? = null,
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
        check(commands.trySend(Command.LoadDocument(generation, uri, label, pageCount, launchArgs, scene)).isSuccess)
    }

    fun onPageSelected(pageNumber: Int) {
        if (activeScene != null) {
            selectedPageId = scenePages.firstOrNull { it.pageNumber == pageNumber }?.pageId
            _uiState.value = _uiState.value.copy(currentPageId = selectedPageId)
            return
        }
        val session = activeSession ?: return
        val page = session.pages.firstOrNull { it.pageNumber == pageNumber } ?: return
        selectedPageId = page.pageId
        _uiState.value = _uiState.value.copy(currentPageId = page.pageId)
        if (_uiState.value.readOnly) return
        publishRemotePageSnapshot(page.pageId, pageNumber)
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

    fun setLayerVisibility(index: Int, visible: Boolean) {
        if (index !in sceneVisibility.indices) return
        sceneVisibility[index] = visible
        val editable = document.snapshot()
        val composed = composeSceneSnapshot(editable)
        readOnlySceneSnapshot = composeReadOnlySnapshot(editable.documentId)
        _uiState.value = _uiState.value.copy(snapshot = composed, layerVisibility = sceneVisibility.toList())
    }

    fun submit(onSubmitted: (SubmissionId) -> Unit) {
        if (_uiState.value.readOnly || activeAttemptId == null) return
        _uiState.value = _uiState.value.copy(busy = true, status = "제출 준비 중…")
        check(commands.trySend(Command.Submit(onSubmitted)).isSuccess)
    }

    suspend fun flush() {
        val completion = CompletableDeferred<Unit>()
        commands.send(Command.Flush(completion))
        completion.await()
    }

    fun flushAsync() {
        viewModelScope.launch {
            runCatching { flush() }.onFailure { error ->
                _uiState.value = _uiState.value.copy(status = "저장 실패: ${error.message}")
            }
        }
    }

    private fun enqueueMutation(
        progressText: String,
        onComplete: (() -> Unit)? = null,
        block: AnnotationDocument.() -> AnnotationMutation?,
    ) {
        if (_uiState.value.readOnly) {
            onComplete?.invoke()
            return
        }
        val pending = pendingOperations.incrementAndGet()
        _uiState.value = _uiState.value.copy(
            pendingSaveOperations = pending,
            status = progressText,
        )
        check(commands.trySend(Command.Mutate(progressText, onComplete, block)).isSuccess)
    }

    private suspend fun handleLoad(command: Command.LoadDocument) {
        val loadResult = runCatching {
            val launchArgs = command.launchArgs
            val documentId = withContext(Dispatchers.IO) {
                DocumentIdentity.create(getApplication(), command.uri)
            }
            command.scene?.let { scene ->
                val sceneLoad = loadScene(documentId, scene)
                return@runCatching Triple(sceneLoad, sceneLoad.session, sceneLoad.initialPageId)
            }
            val session = launchArgs?.let { args ->
                learningRepository().getAttemptSession(args.attemptId).also {
                    check(it.attempt.profileId == args.profileId)
                    check(it.attempt.activityId == args.activityId)
                    check(it.documentId == documentId) { "Activity PDF does not match its book revision" }
                }
            }
            val initialPageId = launchArgs?.initialPageId
                ?.takeIf { requested -> session?.pages?.any { it.pageId == requested } == true }
                ?: session?.initialPageId
            if (session != null && initialPageId != null && launchArgs.submissionId == null) {
                learningRepository().prepareAttemptPage(session.attempt.attemptId, initialPageId)
            }
            val loaded = if (launchArgs?.submissionId != null) {
                    annotationStore().loadSubmission(documentId, launchArgs.submissionId.value)
                } else {
                    annotationStore().load(documentId, session?.attempt?.attemptId?.value)
                }
            Triple(SceneLoad(loaded, loaded, AnnotationSnapshot.empty(documentId), session, requireNotNull(initialPageId ?: session?.initialPageId ?: PageId("$documentId:page:0"))), session, initialPageId)
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
        val (sceneLoad, session, initialPageId) = loadResult
        val loaded = sceneLoad.snapshot
        document = AnnotationDocument(sceneLoad.editableSnapshot)
        readOnlySceneSnapshot = sceneLoad.readOnlySnapshot
        if (command.scene == null) {
            sceneSourceSnapshots = emptyList()
            sceneVisibility = mutableListOf()
        }
        activeScene = command.scene
        activeSession = session
        activeAttemptId = session?.attempt?.attemptId.takeIf { command.scene == null }
        selectedPageId = initialPageId
        val initialPageNumber = (if (command.scene != null) scenePages else session?.pages.orEmpty())
            .firstOrNull { it.pageId == initialPageId }?.pageNumber ?: 0
        _uiState.value = ReaderUiState(
            snapshot = loaded,
            documentLabel = command.label,
            pageCount = command.pageCount,
            status = "자동 저장 켜짐 · 필기 ${loaded.activeStrokeIds.size}개 복원",
            pendingSaveOperations = pendingOperations.get(),
            attemptSession = session,
            initialPageNumber = initialPageNumber,
            readOnly = command.scene?.let { it.interactionPolicy != ReaderInteractionPolicy.EDIT }
                ?: (command.launchArgs?.submissionId != null ||
                    (session != null && session.attempt.status != com.studyink.core.model.AttemptStatus.IN_PROGRESS)),
            submissionId = command.launchArgs?.submissionId,
            scene = command.scene,
            layerVisibility = sceneVisibility.toList(),
            currentPageId = initialPageId,
        )
    }

    private data class SceneLoad(
        val snapshot: AnnotationSnapshot,
        val editableSnapshot: AnnotationSnapshot,
        val readOnlySnapshot: AnnotationSnapshot,
        val session: AttemptSession?,
        val initialPageId: PageId,
    )

    private suspend fun loadScene(documentId: String, scene: ReaderScene): SceneLoad {
        var session: AttemptSession? = null
        var pages: List<com.studyink.core.model.ActivityPage> = emptyList()
        var editable = AnnotationSnapshot.empty(documentId)
        val readOnlyParts = mutableListOf<AnnotationSnapshot>()
        val loadedSources = mutableListOf<Pair<ReaderLayerSource, AnnotationSnapshot>>()
        for (source in scene.visibleLayerSources) {
            val loaded = when (source) {
                is EditableLiveLayer -> loadLiveTarget(documentId, source.target).also {
                    session = it.second ?: session
                    if (it.third.isNotEmpty()) pages = it.third
                }.first
                is ReadOnlyLiveLayer -> loadLiveTarget(documentId, source.target).also {
                    session = it.second ?: session
                    if (it.third.isNotEmpty()) pages = it.third
                }.first
                is ReadOnlySnapshot -> when (val target = source.target) {
                    is SnapshotTarget.StudentSubmission -> {
                        val submission = learningRepository().getSubmission(target.submissionId)
                        learningRepository().getAttemptSession(submission.attemptId).let {
                            check(it.documentId == documentId)
                            session = it
                            pages = it.pages
                        }
                        annotationStore().loadSubmission(documentId, target.submissionId.value)
                    }
                    is SnapshotTarget.PublishedReview -> {
                        val review = teacherRepository().getReview(target.reviewId)
                        check(review.documentId == documentId)
                        pages = review.pages.map { com.studyink.core.model.ActivityPage(it.pageId, it.pageNumber, it.pageNumber) }
                        annotationStore().loadPublishedReview(documentId, target.reviewId.value)
                    }
                }
                is ReadOnlyRemoteLayer -> {
                    val replicaStore = com.studyink.annotation.storage.RoomRemoteReplicaStore.open(getApplication())
                    val replicaPages = try { replicaStore.pages(source.remoteSessionId) } finally { replicaStore.close() }
                    pages = replicaPages.map {
                        com.studyink.core.model.ActivityPage(com.studyink.core.model.PageId(it.pageId), it.pageNumber, it.pageNumber)
                    }
                    annotationStore().loadRemoteReplica(documentId, source.remoteSessionId)
                }
            }
            loadedSources += source to loaded
            if (source is EditableLiveLayer) editable = loaded
            else if (source.visibleByDefault) readOnlyParts += loaded
        }
        require(pages.isNotEmpty()) { "ReaderScene has no pages" }
        scenePages = pages
        sceneSourceSnapshots = loadedSources
        sceneVisibility = scene.visibleLayerSources.map(ReaderLayerSource::visibleByDefault).toMutableList()
        val readOnly = mergeSnapshots(documentId, readOnlyParts)
        return SceneLoad(mergeSnapshots(documentId, listOf(readOnly, editable)), editable, readOnly, session, scene.initialPageId)
    }

    private suspend fun loadLiveTarget(
        documentId: String,
        target: LiveLayerTarget,
    ): Triple<AnnotationSnapshot, AttemptSession?, List<com.studyink.core.model.ActivityPage>> = when (target) {
        is LiveLayerTarget.TeacherPreparation -> {
            val preparation = teacherRepository().getPreparationSession(target.teacherId, target.revisionId)
            check(preparation.documentId == documentId)
            val layers = teacherRepository().observePreparedPages(target.teacherId, target.revisionId)
                .first().map { it.prepLayerId.value }
            Triple(annotationStore().loadLayers(documentId, layers), null, preparation.pages)
        }
        is LiveLayerTarget.StudentAttempt -> {
            val session = learningRepository().getAttemptSession(target.attemptId)
            check(session.documentId == documentId)
            Triple(annotationStore().load(documentId, target.attemptId.value), session, session.pages)
        }
        is LiveLayerTarget.TeacherFeedback -> {
            val review = teacherRepository().getReview(target.reviewId)
            check(review.documentId == documentId)
            val layers = review.pages.mapNotNull { it.feedbackLayerId?.value }
            Triple(
                annotationStore().loadLayers(documentId, layers),
                null,
                review.pages.map { com.studyink.core.model.ActivityPage(it.pageId, it.pageNumber, it.pageNumber) },
            )
        }
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
            snapshot = composeSceneSnapshot(mutation.snapshot),
            busy = false,
            status = "저장 대기 중…",
        )

        runCatching {
            val prep = activeScene?.editableLayerSource?.target as? LiveLayerTarget.TeacherPreparation
            val feedback = activeScene?.editableLayerSource?.target as? LiveLayerTarget.TeacherFeedback
            if (prep != null) {
                val pageId = requireNotNull(scenePages.firstOrNull { it.pageNumber == mutation.operation.pageNumber }?.pageId)
                val prepPage = teacherRepository().getOrCreatePrepLayer(prep.teacherId, prep.revisionId, pageId)
                annotationStore().applyMutationToLayer(mutation, prepPage.prepLayerId.value)
            } else if (feedback != null) {
                val pageId = requireNotNull(scenePages.firstOrNull { it.pageNumber == mutation.operation.pageNumber }?.pageId)
                val layerId = teacherRepository().getOrCreateFeedbackLayer(feedback.reviewId, pageId)
                annotationStore().applyMutationToLayer(mutation, layerId.value)
            } else {
                val attempt = activeAttemptId
                val remote = ReaderRemoteBridge.sink?.outboxRequest()
                if (attempt != null && remote != null) {
                    annotationStore().applyMutationAndEnqueueRemote(mutation, attempt.value, remote)
                } else {
                    annotationStore().applyMutation(mutation, attempt?.value)
                }
            }
        }
            .onSuccess {
                val savedAt = System.currentTimeMillis()
                val pageId = scenePages.firstOrNull { it.pageNumber == mutation.operation.pageNumber }?.pageId
                    ?: activeSession?.pages?.firstOrNull { it.pageNumber == mutation.operation.pageNumber }?.pageId
                pageId?.let { publishRemotePageSnapshot(it, mutation.operation.pageNumber) }
                finishMutation(command, "자동 저장됨 · 리비전 ${mutation.snapshot.revision}", savedAt)
            }
            .onFailure { error ->
                document = AnnotationDocument(before)
                _uiState.value = _uiState.value.copy(
                    snapshot = composeSceneSnapshot(before),
                )
                finishMutation(command, "저장 실패 · 변경 취소: ${error.message}")
            }
    }

    private fun publishRemotePageSnapshot(pageId: PageId, pageNumber: Int) {
        val snapshot = document.snapshot()
        ReaderRemoteBridge.sink?.onPageSnapshot(
            pageId.value,
            pageNumber,
            snapshot.pageRevisions[pageNumber] ?: 0L,
            snapshot.activeStrokes.filter { it.pageNumber == pageNumber },
        )
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

    private suspend fun handleSubmit(command: Command.Submit) {
        val attemptId = activeAttemptId ?: return
        resumePageJob?.cancel()
        val result = runCatching {
            SubmitAttemptUseCase(learningRepository()).invoke(attemptId) {
                persistSelectedPageOrThrow()
                annotationStore().flush()
            }
        }
        result.onSuccess { submissionId ->
            _uiState.value = _uiState.value.copy(
                busy = false,
                readOnly = true,
                submissionId = submissionId,
                status = "제출 완료",
            )
            withContext(Dispatchers.Main.immediate) { command.onSubmitted(submissionId) }
        }.onFailure { error ->
            _uiState.value = _uiState.value.copy(
                busy = false,
                status = "제출 실패: ${error.message}",
            )
        }
    }

    private suspend fun annotationStore(): RoomAnnotationStore {
        store?.let { return it }
        return RoomAnnotationStore.open(getApplication()).also { store = it }
    }

    private suspend fun learningRepository(): RoomLearningRepository {
        learningStore?.let { return it }
        return RoomLearningRepository.open(getApplication()).also { learningStore = it }
    }

    private suspend fun teacherRepository(): RoomTeacherRepository {
        teacherStore?.let { return it }
        return RoomTeacherRepository.open(getApplication()).also { teacherStore = it }
    }

    private suspend fun persistSelectedPageOrThrow() {
        val attemptId = activeAttemptId ?: return
        val pageId = selectedPageId ?: return
        learningRepository().updateResumePage(attemptId, pageId)
    }

    private fun composeReadOnlySnapshot(documentId: String): AnnotationSnapshot = mergeSnapshots(
        documentId,
        sceneSourceSnapshots.mapIndexedNotNull { index, (source, snapshot) ->
            snapshot.takeIf { source !is EditableLiveLayer && sceneVisibility.getOrNull(index) == true }
        },
    )

    private fun composeSceneSnapshot(editable: AnnotationSnapshot): AnnotationSnapshot {
        if (activeScene == null) return editable
        val parts = sceneSourceSnapshots.mapIndexedNotNull { index, (source, snapshot) ->
            if (sceneVisibility.getOrNull(index) != true) null
            else if (source is EditableLiveLayer) editable else snapshot
        }
        return mergeSnapshots(editable.documentId, parts)
    }

    override fun onCleared() {
        store?.close()
        learningStore?.close()
        teacherStore?.close()
        super.onCleared()
    }

    private companion object {
        const val RESUME_PAGE_DEBOUNCE_MILLIS = 750L
    }
}

internal fun mergeSnapshots(documentId: String, snapshots: List<AnnotationSnapshot>): AnnotationSnapshot {
    val assets = linkedMapOf<com.studyink.core.model.StrokeId, StrokeAsset>()
    val active = linkedSetOf<com.studyink.core.model.StrokeId>()
    val pageRevisions = linkedMapOf<Int, Long>()
    snapshots.forEach { snapshot ->
        check(snapshot.documentId == documentId)
        assets.putAll(snapshot.assets)
        active += snapshot.activeStrokeIds
        snapshot.pageRevisions.forEach { (page, revision) ->
            pageRevisions[page] = pageRevisions.getOrDefault(page, 0L) + revision
        }
    }
    return AnnotationSnapshot(
        documentId = documentId,
        revision = snapshots.sumOf(AnnotationSnapshot::revision),
        pageRevisions = pageRevisions,
        assets = assets,
        activeStrokeIds = active,
    )
}
