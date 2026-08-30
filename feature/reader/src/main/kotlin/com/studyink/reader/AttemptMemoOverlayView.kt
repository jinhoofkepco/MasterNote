package com.studyink.reader

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.strokes.Stroke
import com.studyink.annotation.engine.AnnotationChange
import com.studyink.annotation.engine.AnnotationDocument
import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.CANONICAL_PAGE_WIDTH
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeId
import com.studyink.core.model.StrokeTool
import com.studyink.document.pdf.CanonicalPdfPoint
import com.studyink.document.pdf.InkViewport
import com.studyink.memo.core.MemoAnchor
import com.studyink.memo.core.MemoStroke
import com.studyink.memo.core.MemoTarget
import com.studyink.memo.core.MemoTool
import com.studyink.memo.core.StudentMemo
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Attempt-scoped memo UI. It deliberately owns no repository or transport lifecycle.
 *
 * The host supplies durable, compare-and-set callbacks. Local edits remain in a bounded serial
 * queue until committed; a failed write restores the last durable editor checkpoint. Place this
 * view above page ink and below [StylusMenuOverlayView].
 */
internal class AttemptMemoOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private data class InkSave(
        val target: MemoTarget,
        val memoId: String,
        val snapshot: AnnotationSnapshot,
        val strokes: List<MemoStroke>,
        val checkpoint: AnnotationDocument.Checkpoint,
    )

    private data class InkSaveBinding(
        val token: Any,
        val generation: Long,
        val target: MemoTarget,
        val memoId: String,
        val queue: LatestSnapshotSerialQueue<InkSave, StudentMemo>,
    )

    var pageViewport: InkViewport? = null
        set(value) {
            field = value
            iconLayer.pageViewport = value
        }

    var onReplaceStrokes: ((MemoTarget, String, Long, List<MemoStroke>) -> StudentMemo)? = null
    var onMoveMemo: ((MemoTarget, String, Long, MemoAnchor) -> StudentMemo)? = null
    var onDeleteMemo: ((MemoTarget, String, Long) -> StudentMemo)? = null
    var onEditorVisibilityChanged: (Boolean) -> Unit = {}
    var onUndoStateChanged: (canUndo: Boolean, canRedo: Boolean) -> Unit = { _, _ -> }
    var onStylusContact: () -> Unit = {}
    var onWorkActivity: () -> Unit = {}
    var onPersistenceError: (Throwable) -> Unit = {}

    private val density = resources.displayMetrics.density
    private val iconLayer = MemoIconLayer(context).apply {
        onOpen =(::openMemo)
        onMove =(::moveMemo)
        onDelete =(::deleteMemo)
    }
    private val modalHost = FrameLayout(context).apply {
        visibility = GONE
        isClickable = true
        isFocusable = true
        setBackgroundColor(Color.argb(38, 25, 31, 43))
        contentDescription = "메모 편집 배경"
    }
    private val card = FrameLayout(context).apply {
        isClickable = true
        isFocusable = true
        background = roundedBackground(
            fill = Color.rgb(255, 253, 245),
            stroke = Color.argb(95, 44, 51, 65),
            radiusDp = 12f,
        )
        elevation = dp(8f)
        clipToOutline = true
    }
    private val headerTitle = TextView(context).apply {
        text = "메모"
        textSize = 13f
        gravity = Gravity.CENTER_VERTICAL
        setTextColor(Color.rgb(47, 48, 45))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(dpInt(14f), 0, 0, 0)
    }
    private val minimizeButton = TextView(context).apply {
        text = "—"
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(Color.rgb(50, 77, 127))
        isClickable = true
        isFocusable = true
        contentDescription = "메모 줄이기"
        setOnClickListener { minimizeEditor() }
    }
    private val header = FrameLayout(context).apply {
        setBackgroundColor(Color.rgb(246, 242, 230))
        addView(headerTitle, LayoutParams(MATCH, MATCH).apply { marginEnd = dpInt(48f) })
        addView(minimizeButton, LayoutParams(dpInt(48f), MATCH, Gravity.END))
    }
    private val scroll = ScrollView(context).apply {
        isFillViewport = true
        overScrollMode = OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = true
        contentDescription = "메모 필기 영역"
    }
    private val canvasHost = MemoCanvasHost(context)
    private val memoViewport = FixedMemoInkViewport(canvasHost)
    private val dryInk = DryInkView(context).apply {
        viewport = memoViewport
        activePage = MEMO_PAGE_NUMBER
        visibleAttemptNo = 1
        showTeacherDrafts = false
        markGroups = emptyList()
    }
    private val wetInk = InProgressStrokesView(context).apply {
        eagerInit()
        addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                finishedWetStrokeIds += strokes.keys
                postDelayed({
                    if (!persistenceInProgress) releaseFinishedWetInk()
                }, WET_INK_HOLD_MILLIS)
            }
        })
    }
    private val inkInput = InkInputView(context).apply {
        viewport = memoViewport
        wetInkView = wetInk
        tool = ReaderTool.PEN
        penColorArgb = DEFAULT_PEN_COLOR_ARGB
        penWidthDp = DEFAULT_PEN_WIDTH_DP
        penOpacity = 1f
        onStylusContact = { this@AttemptMemoOverlayView.onStylusContact() }
        onWorkActivity = { this@AttemptMemoOverlayView.onWorkActivity() }
        canStartErase = { canEditActiveMemo() }
        onStroke =(::addStroke)
        onEraserPreview = { preview -> dryInk.eraserPreview = preview }
        onHoverPreview = { preview -> dryInk.hoverPreview = preview }
        onErase =(::erase)
    }

    private var target: MemoTarget? = null
    private var memos: List<StudentMemo> = emptyList()
    private var studentWritable = false
    private var activeMemo: StudentMemo? = null
    private var document: AnnotationDocument? = null
    private var blockingCommitInProgress = false
    private var inkSaveBinding: InkSaveBinding? = null
    private var durableCheckpoint: AnnotationDocument.Checkpoint? = null
    private var operationGeneration = 0L
    private var persistenceExecutor: ExecutorService = newPersistenceExecutor()
    private val finishedWetStrokeIds = linkedSetOf<InProgressStrokeId>()

    private val persistenceInProgress: Boolean
        get() = blockingCommitInProgress || inkSaveBinding?.queue?.isBusy == true

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        iconLayer.visibility = GONE
        addView(iconLayer, LayoutParams(MATCH, MATCH))
        canvasHost.setBackgroundColor(Color.rgb(255, 254, 249))
        canvasHost.addView(dryInk, LayoutParams(MATCH, MATCH))
        canvasHost.addView(wetInk, LayoutParams(MATCH, MATCH))
        canvasHost.addView(inkInput, LayoutParams(MATCH, MATCH))
        scroll.addView(canvasHost, FrameLayout.LayoutParams(MATCH, WRAP))
        card.addView(scroll, LayoutParams(MATCH, MATCH).apply { topMargin = dpInt(HEADER_HEIGHT_DP) })
        card.addView(header, LayoutParams(MATCH, dpInt(HEADER_HEIGHT_DP), Gravity.TOP))
        modalHost.addView(card, LayoutParams(1, 1, Gravity.CENTER))
        addView(modalHost, LayoutParams(MATCH, MATCH))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** Binds only active memos for one exact page attempt. */
    fun showMemos(target: MemoTarget, memos: List<StudentMemo>, studentWritable: Boolean) {
        require(memos.all { it.target == target && !it.deleted }) { "Memo overlay target mismatch" }
        val targetChanged = this.target != target
        this.target = target
        this.memos = memos.sortedWith(compareBy(StudentMemo::createdAtEpochMillis, StudentMemo::id))
        this.studentWritable = studentWritable
        iconLayer.bind(
            target,
            this.memos,
            canMoveOrDelete = studentWritable && !persistenceInProgress,
        )

        if (targetChanged) {
            closeForTargetChange()
            return
        }
        val opened = activeMemo ?: return updateInputEnabled()
        // Repository change notifications can arrive before the UI completion for our own save.
        // The queue owns the authoritative base until it drains, so never rebind a stale host copy.
        if (inkSaveBinding?.queue?.isBusy == true) return updateInputEnabled()
        val refreshed = this.memos.firstOrNull { it.id == opened.id }
        if (refreshed == null) {
            minimizeEditor()
        } else if (refreshed.digestSha256 != opened.digestSha256 &&
            !inkInput.hasActiveGesture && !blockingCommitInProgress
        ) {
            loadEditor(refreshed)
        } else {
            activeMemo = refreshed
            updateInputEnabled()
        }
    }

    /** Updates permissions without rebinding a possibly stale host-side memo cache. */
    fun updateStudentWritable(target: MemoTarget, studentWritable: Boolean) {
        if (this.target != target) return
        if (this.studentWritable && !studentWritable) cancelActiveGesture()
        this.studentWritable = studentWritable
        iconLayer.bind(
            target,
            memos,
            canMoveOrDelete = studentWritable && !persistenceInProgress,
        )
        updateInputEnabled()
        publishUndoState()
    }

    fun clearMemos() {
        target = null
        memos = emptyList()
        studentWritable = false
        iconLayer.clear()
        closeForTargetChange()
    }

    fun notifyPageViewportChanged() = iconLayer.invalidate()

    /** Converts a point in this overlay's coordinates into a normalized problem-page anchor. */
    fun pageAnchorAt(viewX: Float, viewY: Float): MemoAnchor? {
        val adapter = pageViewport ?: return null
        val boundTarget = target ?: return null
        if (adapter.activePage() != boundTarget.pageNumber) return null
        val page = adapter.activePageBounds() ?: return null
        return memoAnchorAt(
            viewX,
            viewY,
            MemoUiBounds(page.left, page.top, page.right, page.bottom),
        )
    }

    fun openMemo(memoId: String): Boolean {
        if (persistenceInProgress) return false
        val memo = memos.firstOrNull { it.id == memoId } ?: return false
        loadEditor(memo)
        modalHost.visibility = VISIBLE
        iconLayer.visibility = GONE
        updateCardSize(width, height)
        onEditorVisibilityChanged(true)
        return true
    }

    fun minimizeEditor(): Boolean {
        if (modalHost.visibility != VISIBLE || persistenceInProgress) return false
        cancelActiveGesture()
        activeMemo = null
        document = null
        detachInkSaveBinding()
        durableCheckpoint = null
        dryInk.snapshot = AnnotationSnapshot.empty(MEMO_EMPTY_BOOK_ID, MEMO_PAGE_NUMBER)
        modalHost.visibility = GONE
        iconLayer.visibility = if (memos.isEmpty()) GONE else VISIBLE
        onUndoStateChanged(false, false)
        onEditorVisibilityChanged(false)
        return true
    }

    val editorVisible: Boolean get() = modalHost.visibility == VISIBLE
    val activeMemoId: String? get() = activeMemo?.id
    val hasActiveGesture: Boolean get() = inkInput.hasActiveGesture
    val canUndo: Boolean get() = canEditActiveMemo() && document?.canUndo == true
    val canRedo: Boolean get() = canEditActiveMemo() && document?.canRedo == true

    fun setTool(tool: ReaderTool) {
        inkInput.tool = when (tool) {
            ReaderTool.PAN, ReaderTool.GRADE -> ReaderTool.PEN
            else -> tool
        }
    }

    fun setPenColor(colorArgb: Int) {
        inkInput.penColorArgb = colorArgb
    }

    fun setPenWidth(widthDp: Float) {
        if (widthDp.isFinite() && widthDp > 0f) inkInput.penWidthDp = widthDp
    }

    fun setPenOpacity(opacity: Float) {
        inkInput.penOpacity = opacity.coerceIn(0.15f, 1f)
    }

    fun undo(): Boolean {
        val current = activeMemo ?: return false
        if (!canEditActiveMemo()) return false
        val currentDocument = document ?: return false
        val change = currentDocument.undo(MEMO_DEVICE_ID) ?: return false
        return persistChange(current, change, currentDocument.checkpoint())
    }

    fun redo(): Boolean {
        val current = activeMemo ?: return false
        if (!canEditActiveMemo()) return false
        val currentDocument = document ?: return false
        val change = currentDocument.redo(MEMO_DEVICE_ID) ?: return false
        return persistChange(current, change, currentDocument.checkpoint())
    }

    fun cancelActiveGesture(): Boolean {
        val cancelled = inkInput.cancelActiveGesture()
        dryInk.eraserPreview = null
        dryInk.hoverPreview = null
        return cancelled
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateCardSize(width, height)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = editorVisible

    /** A collapsed full-screen sibling must never become Samsung's hover target over page ink. */
    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        if (editorVisible) super.dispatchHoverEvent(event) else false

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val handled = super.dispatchGenericMotionEvent(event)
        return handled || editorVisible
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (persistenceExecutor.isShutdown) persistenceExecutor = newPersistenceExecutor()
    }

    override fun onDetachedFromWindow() {
        operationGeneration += 1L
        blockingCommitInProgress = false
        detachInkSaveBinding()
        durableCheckpoint = null
        cancelActiveGesture()
        releaseFinishedWetInk()
        persistenceExecutor.shutdown()
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        iconLayer.cancelTransientInteractions()
        super.onConfigurationChanged(newConfig)
    }

    private fun updateCardSize(parentWidth: Int, parentHeight: Int) {
        if (parentWidth <= 0 || parentHeight <= 0) return
        val params = card.layoutParams as LayoutParams
        params.width = max(dpInt(MIN_CARD_SIZE_DP), (parentWidth * CARD_FRACTION).roundToInt())
            .coerceAtMost(parentWidth)
        params.height = max(dpInt(MIN_CARD_SIZE_DP), (parentHeight * CARD_FRACTION).roundToInt())
            .coerceAtMost(parentHeight)
        params.gravity = Gravity.CENTER
        card.layoutParams = params
    }

    private fun loadEditor(memo: StudentMemo) {
        detachInkSaveBinding()
        val preserveScroll = activeMemo?.id == memo.id
        val previousScrollY = scroll.scrollY
        activeMemo = memo
        val snapshot = memo.toAnnotationSnapshot()
        document = AnnotationDocument(snapshot).also { durableCheckpoint = it.checkpoint() }
        dryInk.snapshot = snapshot
        dryInk.visibleAttemptNo = memo.target.attemptNo
        dryInk.eraserPreview = null
        dryInk.hoverPreview = null
        if (preserveScroll) {
            scroll.post { scroll.scrollTo(0, previousScrollY) }
        } else {
            scroll.scrollTo(0, 0)
        }
        headerTitle.text = "메모 · ${memo.target.attemptNo}회"
        updateInputEnabled()
        publishUndoState()
    }

    private fun closeForTargetChange() {
        operationGeneration += 1L
        blockingCommitInProgress = false
        detachInkSaveBinding()
        cancelActiveGesture()
        releaseFinishedWetInk()
        activeMemo = null
        document = null
        durableCheckpoint = null
        modalHost.visibility = GONE
        iconLayer.visibility = if (memos.isEmpty()) GONE else VISIBLE
        onUndoStateChanged(false, false)
        onEditorVisibilityChanged(false)
    }

    private fun updateInputEnabled() {
        inkInput.isEnabled = canEditActiveMemo()
        minimizeButton.isEnabled = !persistenceInProgress
    }

    private fun canEditActiveMemo(): Boolean =
        studentWritable && activeMemo != null && onReplaceStrokes != null && !blockingCommitInProgress

    private fun addStroke(stroke: StrokeAsset) {
        val memo = activeMemo ?: return
        if (!canEditActiveMemo()) return
        val currentDocument = document ?: return
        val owned = stroke.copy(
            pageNumber = MEMO_PAGE_NUMBER,
            authorId = MEMO_AUTHOR_ID,
            attemptNo = memo.target.attemptNo,
            deviceId = MEMO_DEVICE_ID,
            itemId = memo.id,
        )
        val change = currentDocument.addStroke(owned)
        persistChange(memo, change, currentDocument.checkpoint())
    }

    private fun erase(gesture: EraserGesture) {
        val memo = activeMemo
        val currentDocument = document
        if (memo == null || currentDocument == null || !canEditActiveMemo()) {
            dryInk.eraserPreview = null
            return
        }
        val change = currentDocument.erase(
            page = MEMO_PAGE_NUMBER,
            path = gesture.path,
            radius = gesture.radius,
            wholeStroke = gesture.whole,
            authorId = MEMO_AUTHOR_ID,
            attemptNo = memo.target.attemptNo,
            deviceId = MEMO_DEVICE_ID,
        )
        if (change == null) {
            dryInk.eraserPreview = null
            return
        }
        persistChange(memo, change, currentDocument.checkpoint())
        dryInk.eraserPreview = null
    }

    private fun persistChange(
        base: StudentMemo,
        change: AnnotationChange,
        checkpoint: AnnotationDocument.Checkpoint,
    ): Boolean {
        if (onReplaceStrokes == null) {
            return rollbackInkQueue(IllegalStateException("Memo writer is unavailable"))
        }
        val request = InkSave(
            target = base.target,
            memoId = base.id,
            snapshot = change.snapshot,
            strokes = change.snapshot.toMemoStrokes(),
            checkpoint = checkpoint,
        )
        val binding = inkSaveBinding ?: createInkSaveBinding(base).also { inkSaveBinding = it }
        if (binding == null || binding.target != request.target || binding.memoId != request.memoId ||
            !binding.queue.offer(request)
        ) {
            return rollbackInkQueue(IllegalStateException("Memo persistence worker is unavailable"))
        }
        refreshPersistenceUi()
        return true
    }

    private fun createInkSaveBinding(base: StudentMemo): InkSaveBinding? {
        val save = onReplaceStrokes ?: return null
        if (base.deleted) return null
        if (persistenceExecutor.isShutdown) persistenceExecutor = newPersistenceExecutor()
        val token = Any()
        val generation = operationGeneration
        val view = WeakReference(this)
        val queue = LatestSnapshotSerialQueue(
            executor = persistenceExecutor,
            initialBase = base,
            persist = { request: InkSave, currentBase: StudentMemo ->
                require(request.target == currentBase.target && request.memoId == currentBase.id &&
                    !currentBase.deleted
                ) { "Memo persistence target changed" }
                save(request.target, request.memoId, currentBase.revision, request.strokes).also { committed ->
                    require(committed.target == request.target && committed.id == request.memoId &&
                        !committed.deleted && committed.revision >= currentBase.revision &&
                        committed.anchor == currentBase.anchor && committed.strokes == request.strokes
                    ) { "Memo writer returned another memo" }
                }
            },
            observer = object : LatestSnapshotSerialQueue.Observer<InkSave, StudentMemo> {
                override fun onPersisted(value: InkSave, committedBase: StudentMemo) {
                    view.get()?.post {
                        view.get()?.completeInkSave(token, generation, value, committedBase)
                    }
                }

                override fun onFailure(error: Throwable) {
                    view.get()?.post { view.get()?.failInkSave(token, generation, error) }
                }
            },
        )
        return InkSaveBinding(token, generation, base.target, base.id, queue)
    }

    private fun completeInkSave(
        token: Any,
        generation: Long,
        request: InkSave,
        committed: StudentMemo,
    ) {
        val binding = inkSaveBinding ?: return
        if (binding.token !== token || binding.generation != generation || generation != operationGeneration ||
            !isAttachedToWindow || !binding.queue.isLatestCommitted(request)
        ) {
            return
        }
        activeMemo = committed
        replaceMemo(committed)
        durableCheckpoint = request.checkpoint
        if (!binding.queue.isBusy) {
            dryInk.snapshot = request.snapshot
            finishPersistenceUi()
        } else {
            refreshPersistenceUi()
        }
    }

    private fun failInkSave(token: Any, generation: Long, error: Throwable) {
        val binding = inkSaveBinding ?: return
        if (binding.token !== token || binding.generation != generation || generation != operationGeneration ||
            !isAttachedToWindow
        ) return
        rollbackInkQueue(error)
    }

    private fun detachInkSaveBinding() {
        inkSaveBinding?.queue?.detachObserver()
        inkSaveBinding = null
    }

    private fun rollbackInkQueue(error: Throwable): Boolean {
        detachInkSaveBinding()
        cancelActiveGesture()
        val currentDocument = document
        val checkpoint = durableCheckpoint
        val snapshot = if (currentDocument != null && checkpoint != null) {
            runCatching { currentDocument.restore(checkpoint) }.getOrNull()
        } else {
            null
        } ?: activeMemo?.toAnnotationSnapshot()?.also { restored ->
            document = AnnotationDocument(restored).also { durableCheckpoint = it.checkpoint() }
        } ?: AnnotationSnapshot.empty(MEMO_EMPTY_BOOK_ID, MEMO_PAGE_NUMBER)
        activeMemo?.let(::replaceMemo)
        dryInk.snapshot = snapshot
        dryInk.eraserPreview = null
        finishPersistenceUi()
        onPersistenceError(error)
        return false
    }

    private fun moveMemo(memo: StudentMemo, anchor: MemoAnchor): Boolean {
        if (!studentWritable || memo.target != target || persistenceInProgress) return false
        val move = onMoveMemo ?: return false
        val generation = beginBlockingCommit() ?: return false
        val scheduled = submitPersistence(
            generation = generation,
            task = { move(memo.target, memo.id, memo.revision, anchor) },
        ) { result ->
            blockingCommitInProgress = false
            result.fold(
                onSuccess = { committed ->
                    if (committed.id != memo.id || committed.target != memo.target || committed.deleted) {
                        onPersistenceError(IllegalStateException("Memo move returned another memo"))
                    } else {
                        replaceMemo(committed)
                    }
                },
                onFailure = onPersistenceError,
            )
            finishCommitUi()
        }
        if (!scheduled) {
            blockingCommitInProgress = false
            finishCommitUi()
            onPersistenceError(IllegalStateException("Memo persistence worker is unavailable"))
        }
        return scheduled
    }

    private fun deleteMemo(memo: StudentMemo): Boolean {
        if (!studentWritable || memo.target != target || persistenceInProgress) return false
        val delete = onDeleteMemo ?: return false
        val generation = beginBlockingCommit() ?: return false
        val scheduled = submitPersistence(
            generation = generation,
            task = { delete(memo.target, memo.id, memo.revision) },
        ) { result ->
            blockingCommitInProgress = false
            result.fold(
                onSuccess = { tombstone ->
                    if (tombstone.id != memo.id || tombstone.target != memo.target || !tombstone.deleted) {
                        onPersistenceError(IllegalStateException("Memo delete did not return its tombstone"))
                    } else {
                        memos = memos.filterNot { it.id == memo.id }
                        if (activeMemo?.id == memo.id) minimizeEditor()
                    }
                },
                onFailure = onPersistenceError,
            )
            finishCommitUi()
        }
        if (!scheduled) {
            blockingCommitInProgress = false
            finishCommitUi()
            onPersistenceError(IllegalStateException("Memo persistence worker is unavailable"))
        }
        return scheduled
    }

    private fun replaceMemo(replacement: StudentMemo) {
        memos = (memos.filterNot { it.id == replacement.id } + replacement)
            .sortedWith(compareBy(StudentMemo::createdAtEpochMillis, StudentMemo::id))
    }

    private fun publishUndoState() {
        onUndoStateChanged(canUndo, canRedo)
    }

    private fun beginBlockingCommit(): Long? {
        if (persistenceInProgress) return null
        if (persistenceExecutor.isShutdown) persistenceExecutor = newPersistenceExecutor()
        blockingCommitInProgress = true
        updateInputEnabled()
        target?.let { current ->
            iconLayer.bind(current, memos, canMoveOrDelete = false)
        }
        publishUndoState()
        return operationGeneration
    }

    private fun finishCommitUi() {
        updateInputEnabled()
        target?.let { current ->
            iconLayer.bind(
                current,
                memos,
                canMoveOrDelete = studentWritable && !persistenceInProgress,
            )
        }
        releaseFinishedWetInk()
        publishUndoState()
    }

    private fun refreshPersistenceUi() {
        updateInputEnabled()
        target?.let { current ->
            iconLayer.bind(current, memos, canMoveOrDelete = false)
        }
        publishUndoState()
    }

    private fun finishPersistenceUi() {
        updateInputEnabled()
        target?.let { current ->
            iconLayer.bind(
                current,
                memos,
                canMoveOrDelete = studentWritable && !persistenceInProgress,
            )
        }
        releaseFinishedWetInk()
        publishUndoState()
    }

    private fun releaseFinishedWetInk() {
        if (finishedWetStrokeIds.isEmpty()) return
        val finished = finishedWetStrokeIds.toSet()
        finishedWetStrokeIds.clear()
        wetInk.removeFinishedStrokes(finished)
    }

    private fun <T> submitPersistence(
        generation: Long,
        task: () -> T,
        completion: (Result<T>) -> Unit,
    ): Boolean = runCatching {
        persistenceExecutor.execute {
            val result = runCatching(task)
            post {
                if (generation == operationGeneration && isAttachedToWindow) completion(result)
            }
        }
    }.isSuccess

    private fun roundedBackground(fill: Int, stroke: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp)
        setStroke(dpInt(1f), stroke)
    }

    private fun dp(value: Float): Float = value * density
    private fun dpInt(value: Float): Int = dp(value).roundToInt()

    private fun newPersistenceExecutor(): ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MasterNote-MemoPersistence").apply { isDaemon = true }
    }

    private companion object {
        const val CARD_FRACTION = 0.8f
        const val MIN_CARD_SIZE_DP = 240f
        const val HEADER_HEIGHT_DP = 42f
        const val WET_INK_HOLD_MILLIS = 80L
        const val MEMO_PAGE_NUMBER = 0
        const val MEMO_AUTHOR_ID = "student"
        const val MEMO_DEVICE_ID = "memo-local"
        const val MEMO_EMPTY_BOOK_ID = "memo-empty"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}

/** Full-height memo sheet. ScrollView supplies the width and this view fixes the non-zoomable ratio. */
private class MemoCanvasHost(context: Context) : FrameLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val height = (width * MEMO_CANVAS_ASPECT_RATIO).roundToInt().coerceAtLeast(1)
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }
}

/** Linear, fixed-scale viewport for the memo sheet. There is intentionally no zoom state. */
private class FixedMemoInkViewport(private val canvas: View) : InkViewport {
    override fun viewToCanonical(x: Float, y: Float): CanonicalPdfPoint? {
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        if (width <= 0f || height <= 0f || x !in 0f..width || y !in 0f..height) return null
        return CanonicalPdfPoint(
            MEMO_PAGE_NUMBER,
            PagePoint(
                x = x / width * CANONICAL_PAGE_WIDTH,
                y = y / height * MEMO_CANONICAL_HEIGHT,
            ),
        )
    }

    override fun canonicalToView(pageNumber: Int, point: PagePoint): PointF? {
        if (pageNumber != MEMO_PAGE_NUMBER || canvas.width <= 0 || canvas.height <= 0) return null
        return PointF(
            point.x / CANONICAL_PAGE_WIDTH * canvas.width,
            point.y / MEMO_CANONICAL_HEIGHT * canvas.height,
        )
    }

    override fun canonicalWidthToView(pageNumber: Int, width: Float): Float =
        if (pageNumber == MEMO_PAGE_NUMBER && canvas.width > 0) {
            width / CANONICAL_PAGE_WIDTH * canvas.width
        } else {
            width
        }

    override fun viewWidthToCanonical(pageNumber: Int, widthPixels: Float): Float =
        if (pageNumber == MEMO_PAGE_NUMBER && canvas.width > 0) {
            widthPixels / canvas.width * CANONICAL_PAGE_WIDTH
        } else {
            widthPixels
        }

    override fun activePage(): Int = MEMO_PAGE_NUMBER

    override fun activePageBounds(): RectF? = if (canvas.width > 0 && canvas.height > 0) {
        RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat())
    } else {
        null
    }

    private companion object { const val MEMO_PAGE_NUMBER = 0 }
}

/** Page overlay that owns input only when a memo icon (or explicit move mode) is hit. */
private class MemoIconLayer(context: Context) : View(context) {
    var pageViewport: InkViewport? = null
        set(value) { field = value; invalidate() }
    var onOpen: (String) -> Unit = {}
    var onMove: (StudentMemo, MemoAnchor) -> Boolean = { _, _ -> false }
    var onDelete: (StudentMemo) -> Boolean = { false }

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val iconFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 231, 119) }
    private val iconOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        color = Color.argb(180, 112, 88, 28)
    }
    private val foldFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(247, 205, 76) }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(79, 65, 29)
        textSize = sp(10f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val moveOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.rgb(42, 105, 202)
    }
    private var target: MemoTarget? = null
    private var memos: List<StudentMemo> = emptyList()
    private var canMoveOrDelete = false
    private var activeMemoId: String? = null
    private var movingMemoId: String? = null
    private var movingAnchor: MemoAnchor? = null
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var longPressed = false
    private var longPress: Runnable? = null
    private var actionsPopup: PopupWindow? = null

    init {
        isClickable = true
        contentDescription = "회차 메모"
    }

    fun bind(target: MemoTarget, memos: List<StudentMemo>, canMoveOrDelete: Boolean) {
        val nextMemos = memos.filterNot(StudentMemo::deleted)
        if (this.target != target || this.memos != nextMemos) cancelTransientInteractions()
        this.target = target
        this.memos = nextMemos
        this.canMoveOrDelete = canMoveOrDelete
        movingMemoId = movingMemoId?.takeIf { id -> this.memos.any { it.id == id } }
        visibility = if (this.memos.isEmpty()) GONE else VISIBLE
        invalidate()
    }

    fun clear() {
        cancelTransientInteractions()
        target = null
        memos = emptyList()
        activeMemoId = null
        movingMemoId = null
        movingAnchor = null
        visibility = GONE
        invalidate()
    }

    fun cancelTransientInteractions() {
        cancelScheduledLongPress()
        actionsPopup?.dismiss()
        actionsPopup = null
        activeMemoId = null
        movingMemoId = null
        movingAnchor = null
        moved = false
        longPressed = false
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val adapter = pageViewport ?: return
        val boundTarget = target ?: return
        if (adapter.activePage() != boundTarget.pageNumber) return
        val page = adapter.activePageBounds()?.toMemoBounds() ?: return
        val centers = displayCenters(page)
        memos.forEach { memo ->
            val center = centers[memo.id] ?: return@forEach
            drawIcon(canvas, center, moving = memo.id == movingMemoId)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (visibility != VISIBLE) return false
        val page = resolvedPageBounds() ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pendingMove = movingMemoId?.let(::memoById)
                if (pendingMove != null && !page.contains(event.x, event.y)) {
                    movingMemoId = null
                    movingAnchor = null
                    activeMemoId = null
                    invalidate()
                    return false
                }
                val centers = displayCenters(page)
                val hit = pendingMove ?: memoAt(event.x, event.y, centers) ?: return false
                activeMemoId = hit.id
                downX = event.x
                downY = event.y
                moved = false
                longPressed = false
                if (pendingMove != null) {
                    movingAnchor = memoAnchorAt(event.x, event.y, page)
                    invalidate()
                } else if (canMoveOrDelete) {
                    centers[hit.id]?.let { scheduleLongPress(hit, it) }
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val id = activeMemoId ?: return false
                if (!moved && (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)) {
                    moved = true
                    cancelScheduledLongPress()
                }
                if (movingMemoId == id) {
                    movingAnchor = memoAnchorAt(event.x, event.y, page)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val memo = activeMemoId?.let(::memoById)
                cancelScheduledLongPress()
                if (memo != null && movingMemoId == memo.id) {
                    val anchor = movingAnchor ?: memo.anchor
                    if (onMove(memo, anchor)) {
                        movingMemoId = null
                        movingAnchor = null
                    } else {
                        movingAnchor = memo.anchor
                    }
                    invalidate()
                } else if (memo != null && !moved && !longPressed) {
                    onOpen(memo.id)
                }
                finishGesture()
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                cancelScheduledLongPress()
                movingAnchor = movingMemoId?.let(::memoById)?.anchor
                invalidate()
                finishGesture()
                return true
            }
        }
        return activeMemoId != null
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        cancelTransientInteractions()
        super.onDetachedFromWindow()
    }

    private fun memoAt(
        x: Float,
        y: Float,
        centers: Map<String, MemoUiPoint>,
    ): StudentMemo? = memos.asReversed()
        .firstOrNull { memo ->
            centers[memo.id]?.let { center ->
                memoIconHit(x, y, center, dp(ICON_HIT_RADIUS_DP))
            } == true
        }

    private fun memoById(id: String): StudentMemo? = memos.firstOrNull { it.id == id }

    private fun resolvedPageBounds(): MemoUiBounds? {
        val adapter = pageViewport ?: return null
        val boundTarget = target ?: return null
        if (adapter.activePage() != boundTarget.pageNumber) return null
        return adapter.activePageBounds()?.toMemoBounds()
    }

    private fun scheduleLongPress(memo: StudentMemo, center: MemoUiPoint) {
        cancelScheduledLongPress()
        longPress = Runnable {
            if (activeMemoId != memo.id || moved || !canMoveOrDelete) return@Runnable
            longPressed = true
            showActions(memo, center)
        }.also { postDelayed(it, LONG_PRESS_MILLIS) }
    }

    private fun displayCenters(page: MemoUiBounds): Map<String, MemoUiPoint> =
        spreadMemoIconCenters(
            anchors = memos.map { memo ->
                MemoUiAnchor(
                    id = memo.id,
                    anchor = if (memo.id == movingMemoId) movingAnchor ?: memo.anchor else memo.anchor,
                )
            },
            page = page,
            minimumSeparation = dp(ICON_CENTER_SEPARATION_DP),
            edgePadding = dp(ICON_DRAW_SIZE_DP) / 2f + dp(2f),
        )

    private fun cancelScheduledLongPress() {
        longPress?.let(::removeCallbacks)
        longPress = null
    }

    private fun showActions(memo: StudentMemo, center: MemoUiPoint) {
        actionsPopup?.dismiss()
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.rgb(255, 254, 249))
                cornerRadius = dp(9f)
                setStroke(dp(1f).roundToInt(), Color.argb(100, 42, 50, 64))
            }
            elevation = dp(7f)
        }
        val popup = PopupWindow(row, dp(ACTION_POPUP_WIDTH_DP).roundToInt(), dp(ACTION_POPUP_HEIGHT_DP).roundToInt(), true).apply {
            isOutsideTouchable = true
            elevation = dp(7f)
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
        }
        row.addView(action("이동") {
            movingMemoId = memo.id
            movingAnchor = memo.anchor
            popup.dismiss()
            invalidate()
        }, LinearLayout.LayoutParams(0, MATCH, 1f))
        row.addView(action("삭제") {
            popup.dismiss()
            if (onDelete(memo)) {
                movingMemoId = null
                movingAnchor = null
            }
        }, LinearLayout.LayoutParams(0, MATCH, 1f))
        actionsPopup = popup
        val screen = IntArray(2)
        getLocationOnScreen(screen)
        val popupWidth = dp(ACTION_POPUP_WIDTH_DP)
        val popupHeight = dp(ACTION_POPUP_HEIGHT_DP)
        val x = (screen[0] + center.x - popupWidth / 2f).roundToInt()
            .coerceIn(screen[0], (screen[0] + width - popupWidth).roundToInt().coerceAtLeast(screen[0]))
        val y = (screen[1] + center.y - popupHeight - dp(ICON_DRAW_SIZE_DP)).roundToInt()
            .coerceAtLeast(screen[1])
        popup.showAtLocation(this, Gravity.NO_GRAVITY, x, y)
    }

    private fun action(label: String, action: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(if (label == "삭제") Color.rgb(166, 48, 48) else Color.rgb(45, 73, 126))
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
    }

    private fun drawIcon(canvas: Canvas, center: MemoUiPoint, moving: Boolean) {
        val half = dp(ICON_DRAW_SIZE_DP) / 2f
        val left = center.x - half
        val top = center.y - half
        val right = center.x + half
        val bottom = center.y + half
        val radius = dp(5f)
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, iconFill)
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, iconOutline)
        val fold = dp(9f)
        val foldPath = Path().apply {
            moveTo(right - fold, top)
            lineTo(right, top + fold)
            lineTo(right - fold, top + fold)
            close()
        }
        canvas.drawPath(foldPath, foldFill)
        val metrics = labelPaint.fontMetrics
        val baseline = center.y - (metrics.ascent + metrics.descent) / 2f + dp(2f)
        canvas.drawText("메모", center.x, baseline, labelPaint)
        if (moving) canvas.drawCircle(center.x, center.y, half + dp(3f), moveOutline)
    }

    private fun finishGesture() {
        activeMemoId = null
        moved = false
        longPressed = false
        parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun RectF.toMemoBounds() = MemoUiBounds(left, top, right, bottom)
    private fun dp(value: Float): Float = value * density
    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )

    private companion object {
        const val ICON_DRAW_SIZE_DP = 34f
        const val ICON_HIT_RADIUS_DP = 25f
        const val ICON_CENTER_SEPARATION_DP = 38f
        const val ACTION_POPUP_WIDTH_DP = 128f
        const val ACTION_POPUP_HEIGHT_DP = 46f
        const val LONG_PRESS_MILLIS = 550L
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}

private fun StudentMemo.toAnnotationSnapshot(): AnnotationSnapshot {
    val assets = strokes.mapIndexed { index, stroke ->
        stroke.toStrokeAsset(this, logicalClock = index + 1L)
    }.associateBy(StrokeAsset::id)
    return AnnotationSnapshot(
        bookId = "memo:${target.bookId}:$id",
        pageNumber = 0,
        revision = revision,
        assets = assets,
        activeStrokeIds = assets.keys,
    )
}

private fun MemoStroke.toStrokeAsset(owner: StudentMemo, logicalClock: Long) = StrokeAsset(
    id = StrokeId(id),
    pageNumber = 0,
    tool = when (tool) {
        MemoTool.PEN -> StrokeTool.PEN
        MemoTool.HIGHLIGHTER -> StrokeTool.HIGHLIGHTER
    },
    colorArgb = colorArgb,
    width = widthFraction * CANONICAL_PAGE_WIDTH,
    points = points.map { it.toCanonicalMemoPoint() },
    authorId = "student",
    attemptNo = owner.target.attemptNo,
    logicalClock = logicalClock,
    deviceId = "memo-local",
    itemId = owner.id,
    createdAtEpochMillis = createdAtEpochMillis,
)

private fun AnnotationSnapshot.toMemoStrokes(): List<MemoStroke> = activeStrokes.map { stroke ->
    MemoStroke(
        id = stroke.id.value,
        tool = when (stroke.tool) {
            StrokeTool.PEN -> MemoTool.PEN
            StrokeTool.HIGHLIGHTER -> MemoTool.HIGHLIGHTER
        },
        colorArgb = stroke.colorArgb,
        widthFraction = (stroke.width / CANONICAL_PAGE_WIDTH).coerceIn(
            1f / CANONICAL_PAGE_WIDTH,
            1f,
        ),
        points = stroke.points.map { it.toMemoPoint() },
        createdAtEpochMillis = stroke.createdAtEpochMillis,
    )
}
