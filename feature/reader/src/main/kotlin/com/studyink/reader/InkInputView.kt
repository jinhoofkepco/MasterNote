package com.studyink.reader

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import com.studyink.annotation.engine.QuickShapeRecognition
import com.studyink.annotation.engine.QuickShapeRecognizer
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeTool
import com.studyink.document.pdf.InkViewport

enum class ReaderTool { PAN, PEN, HIGHLIGHTER, PARTIAL_ERASER, WHOLE_ERASER, GRADE }

class InkInputView(context: Context) : View(context) {
    lateinit var viewport: InkViewport
    lateinit var wetInkView: InProgressStrokesView
    var tool: ReaderTool = ReaderTool.PEN
    var penColorArgb: Int = DEFAULT_PEN_COLOR_ARGB
    var penWidthDp: Float = DEFAULT_PEN_WIDTH_DP
    var penOpacity: Float = 1f
    var onStroke: (StrokeAsset) -> Unit = {}
    /**
     * Main-page owner hook used only when a quick-shape preview replaced AndroidX wet ink.
     * The owner invokes the completion after its durable mutation attempt, allowing this view to
     * bridge the preview until dry ink has caught up without publishing a second stroke.
     */
    var onStrokeAwaitingPersistence: ((StrokeAsset, () -> Unit) -> Unit)? = null
    var quickShapeEnabled: Boolean = false
    var onQuickShapePreview: (QuickShapePreview?) -> Unit = {}
    var onStrokeStart: (Int) -> Unit = {}
    var onStylusContact: () -> Unit = {}
    /**
     * Contact activity for the lightweight study-idle monitor. This is emitted only while a pen,
     * highlighter, or permitted eraser gesture is actually touching the page, and is throttled in
     * this view so high-frequency MotionEvents never spill into the rest of the app.
     */
    var onWorkActivity: () -> Unit = {}
    var onEraserPreview: (EraserPreview?) -> Unit = {}
    /** Evaluated before an eraser gesture captures the pointer. */
    var canStartErase: (page: Int) -> Boolean = { true }
    /** Emitted exactly once on ACTION_UP. Actual erase work must not run during DOWN/MOVE. */
    var onErase: (EraserGesture) -> Unit = {}
    var onGradeTap: (Int, PagePoint, Int, Float, Float) -> Unit = { _, _, _, _, _ -> }
    var onGradeLongPress: (Int, PagePoint, Float, Float) -> Unit = { _, _, _, _ -> }
    var findMarkAttempt: (Float, Float) -> Int? = { _, _ -> null }
    var onOpenMarkedAttempt: (Int) -> Unit = {}
    var onHoverPreview: (StylusHoverPreview?) -> Unit = {}
    var findScrollableMarkGroup: (Float, Float) -> String? = { _, _ -> null }
    var onDragMarkHistory: (String, Float) -> Unit = { _, _ -> }
    var onEndMarkHistoryDrag: (String) -> Unit = {}

    private val currentPoints = mutableListOf<PagePoint>()
    private var currentPage = -1
    private var currentPointer = -1
    private var activeTool = ReaderTool.PEN
    private var activeStrokeColorArgb = DEFAULT_PEN_COLOR_ARGB
    private var strokeWidthCanonical = 4f
    private var eraserRadiusCanonical = 18f
    private var nextEraserGestureId = 0L
    private var activeEraserGestureId = NO_ERASER_GESTURE
    private var eraseGestureBlocked = false
    private var pendingSingleTap: Runnable? = null
    private var lastTapAtMillis = 0L
    private var lastTapViewX = 0f
    private var lastTapViewY = 0f
    private var lastTapPoint: PagePoint? = null
    private var longPressRunnable: Runnable? = null
    private var longPressTriggered = false
    private var downViewX = 0f
    private var downViewY = 0f
    private var maxTravelPixels = 0f
    private var markedAttemptTarget: Int? = null
    private var markedAttemptInteraction = false
    private var markHistoryGroupId: String? = null
    private var markHistoryLastX = 0f
    private var draggingMarkHistory = false
    private var lastWorkActivityEventTime = Long.MIN_VALUE
    private val quickShapeSession by lazy {
        QuickShapeSession<QuickShapeRecognition>(
            holdSlopPx = dp(QUICK_SHAPE_HOLD_SLOP_DP),
            rawResumeSlopPx = dp(QUICK_SHAPE_RAW_RESUME_SLOP_DP),
        )
    }
    private var quickShapeActive = false
    private var quickShapeWetInkDetached = false
    private var quickShapeHoldRunnable: Runnable? = null
    private var quickShapeScheduledGeneration = NO_QUICK_SHAPE_GENERATION
    private var quickShapeRecognitionPointCount = 0
    private var quickShapeMinimumDiagonalCanonical = 0f
    private var quickShapeMinViewX = Float.POSITIVE_INFINITY
    private var quickShapeMinViewY = Float.POSITIVE_INFINITY
    private var quickShapeMaxViewX = Float.NEGATIVE_INFINITY
    private var quickShapeMaxViewY = Float.NEGATIVE_INFINITY
    private var nextQuickShapePreviewToken = 0L
    private var activeQuickShapePreviewToken = NO_QUICK_SHAPE_PREVIEW
    private var displayedQuickShapePreviewToken = NO_QUICK_SHAPE_PREVIEW

    val hasActiveGesture: Boolean
        get() = currentPointer >= 0

    val hasActiveEraserGesture: Boolean
        get() = currentPointer >= 0 && activeTool.isEraser()

    /**
     * Abandons an in-flight eraser corridor without emitting [onErase]. Page/attempt changes and
     * Activity focus loss call this so an old path can never be committed after the target moved.
     */
    fun cancelActiveEraserGesture(): Boolean {
        if (currentPointer < 0 || !activeTool.isEraser()) return false
        return cancelActiveGesture()
    }

    /** Cancels any in-flight pen, highlighter, or eraser gesture without emitting a mutation. */
    fun cancelActiveGesture(): Boolean {
        if (currentPointer < 0) return false
        cancelGradeLongPress()
        markHistoryGroupId?.let(onEndMarkHistoryDrag)
        cancelQuickShapeGesture()
        if (
            (activeTool == ReaderTool.PEN || activeTool == ReaderTool.HIGHLIGHTER) &&
            !quickShapeWetInkDetached
        ) {
            wetInkView.cancelUnfinishedStrokes()
        }
        if (activeEraserGestureId != NO_ERASER_GESTURE) onEraserPreview(null)
        onHoverPreview(null)
        reset()
        return true
    }

    /** Clears a post-UP quick-shape bridge when its captured document target is no longer visible. */
    fun clearQuickShapePreview() {
        clearQuickShapePreview(displayedQuickShapePreviewToken)
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        contentDescription = "필기 입력 영역"
    }

    override fun onDetachedFromWindow() {
        if (currentPointer >= 0) cancelActiveGesture()
        cancelQuickShapeHoldTimer()
        if (displayedQuickShapePreviewToken != NO_QUICK_SHAPE_PREVIEW) {
            onQuickShapePreview(null)
            displayedQuickShapePreviewToken = NO_QUICK_SHAPE_PREVIEW
        }
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) {
            if (currentPointer >= 0) cancelActiveGesture()
            return false
        }
        if (currentPointer < 0 && !event.isStylusEvent()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return start(event)
            MotionEvent.ACTION_MOVE -> return move(event)
            MotionEvent.ACTION_UP -> return finish(event)
            MotionEvent.ACTION_CANCEL -> {
                cancel(event)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancel(event)
                return false
            }
        }
        return currentPointer >= 0
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        if (!event.isStylusEvent()) return false
        if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
            onHoverPreview(null)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER || event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
            val page = viewport.activePage()
            val isEraser = event.getToolType(event.actionIndex.coerceIn(0, event.pointerCount - 1)) == MotionEvent.TOOL_TYPE_ERASER ||
                tool == ReaderTool.PARTIAL_ERASER || tool == ReaderTool.WHOLE_ERASER
            val width = if (isEraser) dp(36f) else if (tool == ReaderTool.HIGHLIGHTER) dp(18f) else dp(penWidthDp)
            onHoverPreview(
                StylusHoverPreview(
                    x = event.x,
                    y = event.y,
                    colorArgb = if (tool == ReaderTool.HIGHLIGHTER) 0x66FFE45C else penColorWithOpacity(),
                    widthPixels = if (isEraser) maxOf(width, viewport.canonicalWidthToView(page, eraserRadiusCanonical * 2f)) else width,
                    eraser = isEraser,
                )
            )
            return true
        }
        return false
    }

    private fun start(event: MotionEvent): Boolean {
        val mapped = viewport.viewToCanonical(event.x, event.y) ?: return false
        if (mapped.pageNumber != viewport.activePage()) return false
        val requestedTool = when {
            event.getToolType(event.actionIndex) == MotionEvent.TOOL_TYPE_ERASER -> ReaderTool.PARTIAL_ERASER
            tool == ReaderTool.PAN -> ReaderTool.PEN
            else -> tool
        }
        onStylusContact()
        parent.requestDisallowInterceptTouchEvent(true)
        requestUnbufferedDispatch(event)
        currentPage = mapped.pageNumber
        currentPointer = event.getPointerId(event.actionIndex)
        activeTool = requestedTool
        eraseGestureBlocked = activeTool.isEraser() && !canStartErase(mapped.pageNumber)
        activeEraserGestureId = if (activeTool.isEraser() && !eraseGestureBlocked) {
            newEraserGestureId()
        } else {
            NO_ERASER_GESTURE
        }
        downViewX = event.x
        downViewY = event.y
        maxTravelPixels = 0f
        longPressTriggered = false
        draggingMarkHistory = false
        markedAttemptTarget = null
        markedAttemptInteraction = false
        currentPoints.clear()
        currentPoints += mapped.point.copy(pressure = event.pressure.coerceIn(0f, 1f))
        if (eraseGestureBlocked) return true
        if (activeTool == ReaderTool.GRADE) {
            markHistoryGroupId = findScrollableMarkGroup(event.x, event.y)
            markHistoryLastX = event.x
            longPressRunnable = Runnable {
                if (currentPointer >= 0 && activeTool == ReaderTool.GRADE && maxTravelPixels <= tapTravelThreshold()) {
                    longPressTriggered = true
                    onGradeLongPress(currentPage, currentPoints.first(), downViewX, downViewY)
                }
            }.also { postDelayed(it, LONG_PRESS_MILLIS) }
            return true
        }
        markedAttemptTarget = findMarkAttempt(event.x, event.y)
        if (markedAttemptTarget != null) {
            markedAttemptInteraction = true
            markHistoryGroupId = findScrollableMarkGroup(event.x, event.y)
            markHistoryLastX = event.x
            return true
        }
        strokeWidthCanonical = viewport.viewWidthToCanonical(
            currentPage,
            if (activeTool == ReaderTool.HIGHLIGHTER) dp(18f) else dp(penWidthDp),
        )
        eraserRadiusCanonical = viewport.viewWidthToCanonical(currentPage, dp(18f))

        if (activeTool == ReaderTool.PEN || activeTool == ReaderTool.HIGHLIGHTER) {
            activeStrokeColorArgb = if (activeTool == ReaderTool.HIGHLIGHTER) {
                0x66FFE45C
            } else {
                penColorWithOpacity()
            }
            onStrokeStart(currentPage)
            if (quickShapeEnabled && activeTool == ReaderTool.PEN) beginQuickShapeGesture(event)
            val brush = Brush.createWithColorIntArgb(
                StockBrushes.marker(),
                activeStrokeColorArgb,
                if (activeTool == ReaderTool.HIGHLIGHTER) dp(18f) else dp(penWidthDp),
                0.1f,
            )
            wetInkView.startStroke(event, currentPointer, brush, Matrix(), Matrix())
        } else {
            publishEraserPreview()
        }
        publishWorkActivity(event.eventTime)
        return true
    }

    private fun move(event: MotionEvent): Boolean {
        if (currentPointer < 0) return false
        if (eraseGestureBlocked) return true
        if (activeTool == ReaderTool.GRADE) {
            val pointerIndex = event.findPointerIndex(currentPointer)
            if (pointerIndex >= 0) {
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                updateTravel(x, y)
                if (maxTravelPixels > tapTravelThreshold()) cancelGradeLongPress()
                val delta = x - markHistoryLastX
                markHistoryLastX = x
                markHistoryGroupId?.let { groupId ->
                    if (maxTravelPixels > tapTravelThreshold()) {
                        draggingMarkHistory = true
                        onDragMarkHistory(groupId, delta)
                    }
                }
            }
            return true
        }
        if (markedAttemptInteraction) {
            val pointerIndex = event.findPointerIndex(currentPointer)
            if (pointerIndex >= 0) {
                val x = event.getX(pointerIndex)
                val y = event.getY(pointerIndex)
                updateTravel(x, y)
                val delta = x - markHistoryLastX
                markHistoryLastX = x
                markHistoryGroupId?.let { groupId ->
                    if (maxTravelPixels > tapTravelThreshold()) {
                        draggingMarkHistory = true
                        onDragMarkHistory(groupId, delta)
                    }
                }
            }
            return true
        }
        publishWorkActivity(event.eventTime)
        collectHistory(event)
        if (quickShapeActive) {
            updateQuickShapeViewBounds(event)
            advanceQuickShapeSession(event)
        }
        if (activeTool == ReaderTool.PEN || activeTool == ReaderTool.HIGHLIGHTER) {
            if (!quickShapeWetInkDetached) {
                wetInkView.addToStroke(event, currentPointer, null)
            } else if (quickShapeSession.snapshot.phase != QuickShapePhase.SNAPPED) {
                publishQuickShapePreview(currentPoints)
            }
        } else {
            publishEraserPreview()
        }
        return true
    }

    private fun publishWorkActivity(eventTime: Long) {
        if (activeTool != ReaderTool.PEN && activeTool != ReaderTool.HIGHLIGHTER && !activeTool.isEraser()) {
            return
        }
        if (
            lastWorkActivityEventTime != Long.MIN_VALUE &&
            eventTime - lastWorkActivityEventTime < WORK_ACTIVITY_THROTTLE_MILLIS
        ) return
        lastWorkActivityEventTime = eventTime
        onWorkActivity()
    }

    private fun finish(event: MotionEvent): Boolean {
        if (currentPointer < 0) return false
        if (currentPage >= 0 && currentPage != viewport.activePage()) {
            cancel(event)
            return true
        }
        if (eraseGestureBlocked) {
            reset()
            return true
        }
        if (activeTool == ReaderTool.GRADE) {
            cancelGradeLongPress()
            updateTravel(event.x, event.y)
            val mapped = viewport.viewToCanonical(event.x, event.y)
            if (draggingMarkHistory) {
                markHistoryGroupId?.let(onEndMarkHistoryDrag)
            } else if (!longPressTriggered && maxTravelPixels <= tapTravelThreshold() && mapped?.pageNumber == currentPage) {
                queueGradeTap(currentPage, mapped.point, event.eventTime, event.x, event.y)
            }
            reset()
            return true
        }
        if (markedAttemptInteraction) {
            updateTravel(event.x, event.y)
            if (draggingMarkHistory) {
                markHistoryGroupId?.let(onEndMarkHistoryDrag)
            } else if (maxTravelPixels <= tapTravelThreshold()) {
                markedAttemptTarget?.let(onOpenMarkedAttempt)
            }
            reset()
            return true
        }
        collectHistory(event)
        if (activeTool == ReaderTool.PEN || activeTool == ReaderTool.HIGHLIGHTER) {
            if (quickShapeActive) finishQuickShapeStroke(event) else finishOrdinaryStroke(event)
        } else {
            val gesture = EraserGesture(
                id = activeEraserGestureId,
                page = currentPage,
                path = currentPoints.toList(),
                radius = eraserRadiusCanonical,
                whole = activeTool == ReaderTool.WHOLE_ERASER,
            )
            // Keep the completed corridor visible until the owner reports that the asynchronous
            // erase has completed. reset() deliberately clears input state only.
            publishEraserPreview()
            onErase(gesture)
        }
        reset()
        return true
    }

    private fun queueGradeTap(page: Int, point: PagePoint, eventTime: Long, viewX: Float, viewY: Float) {
        val previous = lastTapPoint
        val doubleTap = previous != null && eventTime - lastTapAtMillis <= 320L &&
            kotlin.math.hypot(lastTapViewX - viewX, lastTapViewY - viewY) <= tapTravelThreshold()
        if (doubleTap) {
            pendingSingleTap?.let(::removeCallbacks)
            pendingSingleTap = null
            lastTapPoint = null
            onGradeTap(page, point, 2, viewX, viewY)
        } else {
            lastTapAtMillis = eventTime
            lastTapViewX = viewX
            lastTapViewY = viewY
            lastTapPoint = point
            pendingSingleTap = Runnable {
                onGradeTap(page, point, 1, viewX, viewY)
                pendingSingleTap = null
                lastTapPoint = null
            }.also { postDelayed(it, 330L) }
        }
    }

    private fun finishOrdinaryStroke(event: MotionEvent) {
        wetInkView.finishStroke(event, currentPointer)
        if (currentPoints.isNotEmpty()) onStroke(newStroke(currentPoints.toList()))
    }

    private fun finishQuickShapeStroke(event: MotionEvent) {
        updateQuickShapeViewBounds(event)
        advanceQuickShapeSession(event)
        settleDueQuickShapeHold(event.eventTime)

        val commit = quickShapeSession.onUp(event.eventTime)
            .filterIsInstance<QuickShapeEffect.Commit<QuickShapeRecognition>>()
            .single()
            .stroke
        cancelQuickShapeHoldTimer()
        val points = when (commit) {
            QuickShapeCommit.Raw -> currentPoints.toList()
            is QuickShapeCommit.Snapped -> commit.candidate.points
        }
        if (!quickShapeWetInkDetached) wetInkView.finishStroke(event, currentPointer)
        if (points.isEmpty()) {
            clearQuickShapePreview(activeQuickShapePreviewToken)
            return
        }

        val stroke = newStroke(points)
        if (!quickShapeWetInkDetached) {
            onStroke(stroke)
            return
        }

        // Keep custom preview ink across the asynchronous fsync/state publication boundary, just
        // as InProgressStrokesView keeps an ordinary finished stroke alive for a short hand-off.
        publishQuickShapePreview(points)
        val previewToken = activeQuickShapePreviewToken
        val releasePreview = {
            postDelayed(
                { clearQuickShapePreview(previewToken) },
                QUICK_SHAPE_COMMIT_HOLD_MILLIS,
            )
            Unit
        }
        val durableOwner = onStrokeAwaitingPersistence
        if (durableOwner == null) {
            onStroke(stroke)
            releasePreview()
        } else {
            try {
                durableOwner(stroke, releasePreview)
            } catch (error: Throwable) {
                releasePreview()
                throw error
            }
        }
    }

    private fun newStroke(points: List<PagePoint>) = StrokeAsset(
        pageNumber = currentPage,
        tool = if (activeTool == ReaderTool.PEN) StrokeTool.PEN else StrokeTool.HIGHLIGHTER,
        colorArgb = activeStrokeColorArgb,
        width = strokeWidthCanonical,
        points = points,
    )

    private fun beginQuickShapeGesture(event: MotionEvent) {
        cancelQuickShapeHoldTimer()
        if (activeQuickShapePreviewToken != NO_QUICK_SHAPE_PREVIEW) {
            clearQuickShapePreview(activeQuickShapePreviewToken)
        }
        quickShapeSession.onDown(event.x, event.y, event.eventTime)
        quickShapeActive = true
        quickShapeWetInkDetached = false
        quickShapeRecognitionPointCount = currentPoints.size
        quickShapeMinimumDiagonalCanonical = viewport.viewWidthToCanonical(
            currentPage,
            dp(QUICK_SHAPE_MINIMUM_SIZE_DP),
        )
        quickShapeMinViewX = event.x
        quickShapeMinViewY = event.y
        quickShapeMaxViewX = event.x
        quickShapeMaxViewY = event.y
        activeQuickShapePreviewToken = nextQuickShapePreviewToken()
    }

    private fun handleQuickShapeEffects(effects: List<QuickShapeEffect<QuickShapeRecognition>>) {
        effects.forEach { effect ->
            when (effect) {
                is QuickShapeEffect.ScheduleHoldTimer -> scheduleQuickShapeHold(effect)
                QuickShapeEffect.CancelHoldTimer -> cancelQuickShapeHoldTimer()
                is QuickShapeEffect.ShowSnappedPreview -> showSnappedQuickShape(effect.candidate)
                QuickShapeEffect.ResumeRawPreview -> resumeRawQuickShapePreview()
                is QuickShapeEffect.Commit -> Unit
                QuickShapeEffect.CleanupPreview -> clearQuickShapePreview(activeQuickShapePreviewToken)
            }
        }
    }

    private fun advanceQuickShapeSession(event: MotionEvent) {
        val pointerIndex = event.findPointerIndex(currentPointer)
        if (pointerIndex < 0) return
        val candidateAvailable = quickShapeCandidateAvailable()
        for (history in 0 until event.historySize) {
            handleQuickShapeEffects(
                quickShapeSession.onMove(
                    x = event.getHistoricalX(pointerIndex, history),
                    y = event.getHistoricalY(pointerIndex, history),
                    eventTimeMs = event.getHistoricalEventTime(history),
                    candidateAvailable = candidateAvailable,
                )
            )
        }
        handleQuickShapeEffects(
            quickShapeSession.onMove(
                x = event.getX(pointerIndex),
                y = event.getY(pointerIndex),
                eventTimeMs = event.eventTime,
                candidateAvailable = candidateAvailable,
            ),
        )
    }

    /** Resolves the exact 2 s boundary even when ACTION_UP is dequeued before its due Runnable. */
    private fun settleDueQuickShapeHold(eventTimeMs: Long) {
        val snapshot = quickShapeSession.snapshot
        val dueAt = snapshot.holdDueAtMs ?: return
        if (snapshot.phase != QuickShapePhase.HOLD_ARMED || eventTimeMs < dueAt) return
        handleQuickShapeEffects(
            quickShapeSession.onHoldTimer(
                timerGeneration = snapshot.timerGeneration,
                nowMs = eventTimeMs,
                candidate = recognizeQuickShapePrefix(),
            )
        )
    }

    private fun recognizeQuickShapePrefix(): QuickShapeRecognition? {
        val prefixSize = quickShapeRecognitionPointCount.coerceIn(0, currentPoints.size)
        return QuickShapeRecognizer.recognize(
            currentPoints.take(prefixSize),
            minimumDiagonal = quickShapeMinimumDiagonalCanonical,
        )
    }

    private fun scheduleQuickShapeHold(effect: QuickShapeEffect.ScheduleHoldTimer) {
        quickShapeHoldRunnable?.let(::removeCallbacks)
        quickShapeHoldRunnable = null
        if (quickShapeScheduledGeneration != effect.generation) {
            quickShapeScheduledGeneration = effect.generation
            quickShapeRecognitionPointCount = currentPoints.size
        }
        var scheduled: Runnable? = null
        scheduled = Runnable {
            if (quickShapeHoldRunnable !== scheduled) return@Runnable
            quickShapeHoldRunnable = null
            if (
                !quickShapeActive || currentPointer < 0 || !isEnabled ||
                currentPage != viewport.activePage()
            ) {
                if (currentPointer >= 0) cancelActiveGesture()
                return@Runnable
            }
            handleQuickShapeEffects(
                quickShapeSession.onHoldTimer(
                    timerGeneration = effect.generation,
                    nowMs = SystemClock.uptimeMillis(),
                    candidate = recognizeQuickShapePrefix(),
                )
            )
        }
        quickShapeHoldRunnable = scheduled
        postDelayed(scheduled, (effect.dueAtMs - SystemClock.uptimeMillis()).coerceAtLeast(0L))
    }

    private fun cancelQuickShapeHoldTimer() {
        quickShapeHoldRunnable?.let(::removeCallbacks)
        quickShapeHoldRunnable = null
        quickShapeScheduledGeneration = NO_QUICK_SHAPE_GENERATION
    }

    private fun showSnappedQuickShape(candidate: QuickShapeRecognition) {
        if (!quickShapeWetInkDetached) {
            wetInkView.cancelUnfinishedStrokes()
            quickShapeWetInkDetached = true
        }
        publishQuickShapePreview(candidate.points)
    }

    private fun resumeRawQuickShapePreview() {
        // currentPoints is an append-only record of the physical contact. Snapping only swaps the
        // visible geometry, so an early-UP downgrade or deliberate post-snap move can restore every
        // raw sample without attempting to reconstruct an Android MotionEvent batch.
        publishQuickShapePreview(currentPoints)
    }

    private fun publishQuickShapePreview(points: List<PagePoint>) {
        if (activeQuickShapePreviewToken == NO_QUICK_SHAPE_PREVIEW || currentPage < 0) return
        displayedQuickShapePreviewToken = activeQuickShapePreviewToken
        onQuickShapePreview(
            QuickShapePreview(
                pageNumber = currentPage,
                path = points.toList(),
                colorArgb = activeStrokeColorArgb,
                width = strokeWidthCanonical,
            )
        )
    }

    private fun clearQuickShapePreview(expectedToken: Long) {
        if (
            expectedToken == NO_QUICK_SHAPE_PREVIEW ||
            displayedQuickShapePreviewToken != expectedToken
        ) return
        displayedQuickShapePreviewToken = NO_QUICK_SHAPE_PREVIEW
        onQuickShapePreview(null)
    }

    private fun cancelQuickShapeGesture() {
        if (!quickShapeActive) return
        quickShapeSession.onCancel()
        cancelQuickShapeHoldTimer()
        clearQuickShapePreview(activeQuickShapePreviewToken)
    }

    private fun updateQuickShapeViewBounds(event: MotionEvent) {
        val pointerIndex = event.findPointerIndex(currentPointer)
        if (pointerIndex < 0) return
        for (history in 0 until event.historySize) {
            includeQuickShapeViewPoint(
                event.getHistoricalX(pointerIndex, history),
                event.getHistoricalY(pointerIndex, history),
            )
        }
        includeQuickShapeViewPoint(event.getX(pointerIndex), event.getY(pointerIndex))
    }

    private fun includeQuickShapeViewPoint(x: Float, y: Float) {
        quickShapeMinViewX = minOf(quickShapeMinViewX, x)
        quickShapeMinViewY = minOf(quickShapeMinViewY, y)
        quickShapeMaxViewX = maxOf(quickShapeMaxViewX, x)
        quickShapeMaxViewY = maxOf(quickShapeMaxViewY, y)
    }

    private fun quickShapeCandidateAvailable(): Boolean {
        if (currentPoints.size < QUICK_SHAPE_MINIMUM_POINT_COUNT) return false
        val diagonal = kotlin.math.hypot(
            quickShapeMaxViewX - quickShapeMinViewX,
            quickShapeMaxViewY - quickShapeMinViewY,
        )
        return diagonal.isFinite() && diagonal >= dp(QUICK_SHAPE_MINIMUM_SIZE_DP)
    }

    private fun nextQuickShapePreviewToken(): Long {
        nextQuickShapePreviewToken = if (nextQuickShapePreviewToken == Long.MAX_VALUE) {
            1L
        } else {
            nextQuickShapePreviewToken + 1L
        }
        return nextQuickShapePreviewToken
    }

    private fun cancel(event: MotionEvent) {
        cancelGradeLongPress()
        markHistoryGroupId?.let(onEndMarkHistoryDrag)
        cancelQuickShapeGesture()
        if (
            currentPointer >= 0 &&
            (activeTool == ReaderTool.PEN || activeTool == ReaderTool.HIGHLIGHTER) &&
            !quickShapeWetInkDetached
        ) {
            runCatching { wetInkView.cancelStroke(event, currentPointer) }
        }
        if (activeEraserGestureId != NO_ERASER_GESTURE) onEraserPreview(null)
        reset()
    }

    private fun collectHistory(event: MotionEvent) {
        val pointerIndex = event.findPointerIndex(currentPointer)
        if (pointerIndex < 0) return
        for (history in 0 until event.historySize) {
            collectPoint(
                event.getHistoricalX(pointerIndex, history),
                event.getHistoricalY(pointerIndex, history),
                event.getHistoricalPressure(pointerIndex, history),
            )
        }
        collectPoint(event.getX(pointerIndex), event.getY(pointerIndex), event.getPressure(pointerIndex))
    }

    private fun collectPoint(x: Float, y: Float, pressure: Float) {
        val mapped = viewport.viewToCanonical(x, y) ?: return
        if (mapped.pageNumber != currentPage) return
        val point = mapped.point.copy(pressure = pressure.coerceIn(0f, 1f))
        val previous = currentPoints.lastOrNull()
        if (previous == null || kotlin.math.abs(previous.x - point.x) + kotlin.math.abs(previous.y - point.y) > 0.15f) {
            currentPoints += point
        }
    }

    private fun publishEraserPreview() {
        if (activeEraserGestureId == NO_ERASER_GESTURE || currentPage < 0) return
        onEraserPreview(
            EraserPreview(
                gestureId = activeEraserGestureId,
                pageNumber = currentPage,
                path = currentPoints.toList(),
                radius = eraserRadiusCanonical,
            )
        )
    }

    private fun newEraserGestureId(): Long {
        nextEraserGestureId = if (nextEraserGestureId == Long.MAX_VALUE) 1L else nextEraserGestureId + 1L
        return nextEraserGestureId
    }

    private fun reset() {
        cancelGradeLongPress()
        cancelQuickShapeHoldTimer()
        markHistoryGroupId = null
        draggingMarkHistory = false
        markedAttemptTarget = null
        markedAttemptInteraction = false
        maxTravelPixels = 0f
        longPressTriggered = false
        currentPointer = -1
        currentPage = -1
        activeEraserGestureId = NO_ERASER_GESTURE
        eraseGestureBlocked = false
        quickShapeActive = false
        quickShapeWetInkDetached = false
        quickShapeRecognitionPointCount = 0
        quickShapeMinimumDiagonalCanonical = 0f
        quickShapeMinViewX = Float.POSITIVE_INFINITY
        quickShapeMinViewY = Float.POSITIVE_INFINITY
        quickShapeMaxViewX = Float.NEGATIVE_INFINITY
        quickShapeMaxViewY = Float.NEGATIVE_INFINITY
        activeQuickShapePreviewToken = NO_QUICK_SHAPE_PREVIEW
        currentPoints.clear()
        parent.requestDisallowInterceptTouchEvent(false)
    }

    private fun MotionEvent.isStylusEvent(): Boolean {
        val index = actionIndex.coerceIn(0, pointerCount - 1)
        val type = getToolType(index)
        return type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun ReaderTool.isEraser(): Boolean =
        this == ReaderTool.PARTIAL_ERASER || this == ReaderTool.WHOLE_ERASER

    private fun penColorWithOpacity(): Int {
        val alpha = (penOpacity.coerceIn(0.15f, 1f) * 255f).toInt().coerceIn(0, 255)
        return (penColorArgb and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun tapTravelThreshold(): Float = dp(8f)

    private fun updateTravel(x: Float, y: Float) {
        maxTravelPixels = maxOf(maxTravelPixels, kotlin.math.hypot(x - downViewX, y - downViewY))
    }

    private fun cancelGradeLongPress() {
        longPressRunnable?.let(::removeCallbacks)
        longPressRunnable = null
    }

    private companion object {
        const val LONG_PRESS_MILLIS = 550L
        const val NO_ERASER_GESTURE = 0L
        const val NO_QUICK_SHAPE_PREVIEW = 0L
        const val NO_QUICK_SHAPE_GENERATION = -1L
        const val QUICK_SHAPE_HOLD_SLOP_DP = 8f
        const val QUICK_SHAPE_RAW_RESUME_SLOP_DP = 12f
        const val QUICK_SHAPE_MINIMUM_SIZE_DP = 24f
        const val QUICK_SHAPE_MINIMUM_POINT_COUNT = 6
        const val QUICK_SHAPE_COMMIT_HOLD_MILLIS = 80L
        const val WORK_ACTIVITY_THROTTLE_MILLIS = 500L
    }
}
