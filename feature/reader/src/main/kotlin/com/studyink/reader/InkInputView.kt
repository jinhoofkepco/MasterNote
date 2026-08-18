package com.studyink.reader

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeTool
import com.studyink.document.pdf.PdfViewportAdapter

enum class ReaderTool { PAN, PEN, HIGHLIGHTER, PARTIAL_ERASER, WHOLE_ERASER, GRADE }

class InkInputView(context: Context) : View(context) {
    lateinit var viewport: PdfViewportAdapter
    lateinit var wetInkView: InProgressStrokesView
    var tool: ReaderTool = ReaderTool.PEN
    var penColorArgb: Int = 0xFF17233C.toInt()
    var penWidthDp: Float = 3.2f
    var penOpacity: Float = 1f
    var onStroke: (StrokeAsset) -> Unit = {}
    var onStylusContact: () -> Unit = {}
    var onEraserPreview: (EraserPreview?) -> Unit = {}
    var onErase: (Int, List<PagePoint>, Float, Boolean) -> Unit = { _, _, _, _ -> }
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
    private var strokeWidthCanonical = 4f
    private var eraserRadiusCanonical = 18f
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

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        contentDescription = "필기 입력 영역"
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
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
        onStylusContact()
        parent.requestDisallowInterceptTouchEvent(true)
        requestUnbufferedDispatch(event)
        currentPage = mapped.pageNumber
        currentPointer = event.getPointerId(event.actionIndex)
        activeTool = when {
            event.getToolType(event.actionIndex) == MotionEvent.TOOL_TYPE_ERASER -> ReaderTool.PARTIAL_ERASER
            tool == ReaderTool.PAN -> ReaderTool.PEN
            else -> tool
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
            val color = if (activeTool == ReaderTool.HIGHLIGHTER) 0x66FFE45C else penColorWithOpacity()
            val brush = Brush.createWithColorIntArgb(
                StockBrushes.marker(), color, if (activeTool == ReaderTool.HIGHLIGHTER) dp(18f) else dp(penWidthDp), 0.1f
            )
            wetInkView.startStroke(event, currentPointer, brush, Matrix(), Matrix())
        } else {
            onEraserPreview(EraserPreview(currentPage, currentPoints.toList(), eraserRadiusCanonical))
        }
        return true
    }

    private fun move(event: MotionEvent): Boolean {
        if (currentPointer < 0) return false
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
        collectHistory(event)
        if (activeTool == ReaderTool.PEN || activeTool == ReaderTool.HIGHLIGHTER) {
            wetInkView.addToStroke(event, currentPointer, null)
        } else {
            onEraserPreview(EraserPreview(currentPage, currentPoints.toList(), eraserRadiusCanonical))
        }
        return true
    }

    private fun finish(event: MotionEvent): Boolean {
        if (currentPointer < 0) return false
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
        collectPoint(event.x, event.y, event.pressure)
        if (activeTool == ReaderTool.PEN || activeTool == ReaderTool.HIGHLIGHTER) {
            wetInkView.finishStroke(event, currentPointer)
            if (currentPoints.isNotEmpty()) {
                onStroke(
                    StrokeAsset(
                        pageNumber = currentPage,
                        tool = if (activeTool == ReaderTool.PEN) StrokeTool.PEN else StrokeTool.HIGHLIGHTER,
                        colorArgb = if (activeTool == ReaderTool.PEN) penColorWithOpacity() else 0x66FFE45C,
                        width = strokeWidthCanonical,
                        points = currentPoints.toList(),
                    )
                )
            }
        } else {
            onErase(currentPage, currentPoints.toList(), eraserRadiusCanonical, activeTool == ReaderTool.WHOLE_ERASER)
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

    private fun cancel(event: MotionEvent) {
        cancelGradeLongPress()
        markHistoryGroupId?.let(onEndMarkHistoryDrag)
        if (currentPointer >= 0 && (activeTool == ReaderTool.PEN || activeTool == ReaderTool.HIGHLIGHTER)) {
            runCatching { wetInkView.cancelStroke(event, currentPointer) }
        }
        onEraserPreview(null)
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

    private fun reset() {
        cancelGradeLongPress()
        markHistoryGroupId = null
        draggingMarkHistory = false
        markedAttemptTarget = null
        markedAttemptInteraction = false
        maxTravelPixels = 0f
        longPressTriggered = false
        currentPointer = -1
        currentPage = -1
        currentPoints.clear()
        parent.requestDisallowInterceptTouchEvent(false)
    }

    private fun MotionEvent.isStylusEvent(): Boolean {
        val index = actionIndex.coerceIn(0, pointerCount - 1)
        val type = getToolType(index)
        return type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER
    }

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
    }
}
