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

enum class ReaderTool { PAN, PEN, HIGHLIGHTER, PARTIAL_ERASER, WHOLE_ERASER }

class InkInputView(context: Context) : View(context) {
    lateinit var viewport: PdfViewportAdapter
    lateinit var wetInkView: InProgressStrokesView
    var tool: ReaderTool = ReaderTool.PEN
    var penColorArgb: Int = 0xFF17233C.toInt()
    var penWidthDp: Float = 3.2f
    var penOpacity: Float = 1f
    var teacherTapEnabled: Boolean = false
    var onStroke: (StrokeAsset) -> Unit = {}
    var onStylusContact: () -> Unit = {}
    var onEraserPreview: (EraserPreview?) -> Unit = {}
    var onErase: (Int, List<PagePoint>, Float, Boolean) -> Unit = { _, _, _, _ -> }
    var onTeacherTap: (Int, PagePoint, Int) -> Unit = { _, _, _ -> }
    var onTeacherLongPress: (Int, PagePoint) -> Unit = { _, _ -> }
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
    private var downAtMillis = 0L
    private var pendingSingleTap: Runnable? = null
    private var lastTapAtMillis = 0L
    private var lastTapPoint: PagePoint? = null
    private var markHistoryGroupId: String? = null
    private var markHistoryLastX = 0f
    private var markHistoryTravel = 0f

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
        downAtMillis = event.eventTime
        currentPoints.clear()
        currentPoints += mapped.point.copy(pressure = event.pressure.coerceIn(0f, 1f))
        markHistoryGroupId = findScrollableMarkGroup(event.x, event.y)
        if (markHistoryGroupId != null) {
            markHistoryLastX = event.x
            markHistoryTravel = 0f
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
        markHistoryGroupId?.let { groupId ->
            val pointerIndex = event.findPointerIndex(currentPointer)
            if (pointerIndex >= 0) {
                val x = event.getX(pointerIndex)
                val delta = x - markHistoryLastX
                markHistoryTravel += kotlin.math.abs(delta)
                markHistoryLastX = x
                onDragMarkHistory(groupId, delta)
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
        markHistoryGroupId?.let { groupId ->
            val mapped = viewport.viewToCanonical(event.x, event.y)
            val held = event.eventTime - downAtMillis
            if (teacherTapEnabled && mapped?.pageNumber == currentPage && markHistoryTravel < dp(6f)) {
                if (held >= 550L) onTeacherLongPress(currentPage, mapped.point)
                else queueTeacherTap(currentPage, mapped.point, event.eventTime)
            }
            onEndMarkHistoryDrag(groupId)
            reset()
            return true
        }
        collectPoint(event.x, event.y, event.pressure)
        val isTap = teacherTapEnabled && currentPoints.size <= 3 && pathDistance() <= 4f
        val isLongPress = isTap && event.eventTime - downAtMillis >= 550L
        if ((activeTool == ReaderTool.PEN || activeTool == ReaderTool.HIGHLIGHTER) && (isTap || isLongPress)) {
            runCatching { wetInkView.cancelStroke(event, currentPointer) }
            val point = currentPoints.lastOrNull()
            if (point != null) {
                if (isLongPress) onTeacherLongPress(currentPage, point) else queueTeacherTap(currentPage, point, event.eventTime)
            }
        } else if (activeTool == ReaderTool.PEN || activeTool == ReaderTool.HIGHLIGHTER) {
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

    private fun queueTeacherTap(page: Int, point: PagePoint, eventTime: Long) {
        val previous = lastTapPoint
        val doubleTap = previous != null && eventTime - lastTapAtMillis <= 320L &&
            kotlin.math.hypot(previous.x - point.x, previous.y - point.y) <= 22f
        if (doubleTap) {
            pendingSingleTap?.let(::removeCallbacks)
            pendingSingleTap = null
            lastTapPoint = null
            onTeacherTap(page, point, 2)
        } else {
            lastTapAtMillis = eventTime
            lastTapPoint = point
            pendingSingleTap = Runnable {
                onTeacherTap(page, point, 1)
                pendingSingleTap = null
                lastTapPoint = null
            }.also { postDelayed(it, 330L) }
        }
    }

    private fun pathDistance(): Float {
        if (currentPoints.size < 2) return 0f
        var distance = 0f
        for (index in 1 until currentPoints.size) {
            distance += kotlin.math.hypot(
                currentPoints[index].x - currentPoints[index - 1].x,
                currentPoints[index].y - currentPoints[index - 1].y,
            )
        }
        return distance
    }

    private fun cancel(event: MotionEvent) {
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
        markHistoryGroupId = null
        markHistoryTravel = 0f
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
}
