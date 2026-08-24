package com.studyink.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.studyink.document.pdf.PdfViewportAdapter
import kotlin.math.max

internal object RemoteFeedbackPainter {
    fun drawLayer(
        canvas: Canvas,
        page: RemoteReviewRect,
        strokes: List<RemoteFeedbackStroke>,
        paint: Paint,
    ) {
        if (page.width <= 0f || page.height <= 0f) return
        val saveCount = canvas.save()
        canvas.clipRect(page.left, page.top, page.right, page.bottom)
        strokes.forEach { stroke -> drawStroke(canvas, page, stroke, paint) }
        canvas.restoreToCount(saveCount)
    }

    fun drawStroke(
        canvas: Canvas,
        page: RemoteReviewRect,
        stroke: RemoteFeedbackStroke,
        paint: Paint,
    ) {
        if (stroke.points.isEmpty()) return
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = stroke.colorArgb
        paint.alpha = when (stroke.tool) {
            RemoteFeedbackStrokeTool.PEN -> Color.alpha(stroke.colorArgb)
            RemoteFeedbackStrokeTool.HIGHLIGHTER -> minOf(Color.alpha(stroke.colorArgb), HIGHLIGHTER_ALPHA)
        }
        paint.strokeWidth = RemoteReviewGeometry.widthToView(stroke.widthFraction, page)
        val first = RemoteReviewGeometry.normalizedToView(stroke.points.first(), page)
        if (stroke.points.size == 1) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(first.x, first.y, paint.strokeWidth / 2f, paint)
            return
        }
        val path = Path().apply { moveTo(first.x, first.y) }
        for (index in 1 until stroke.points.size) {
            val mapped = RemoteReviewGeometry.normalizedToView(stroke.points[index], page)
            path.lineTo(mapped.x, mapped.y)
        }
        canvas.drawPath(path, paint)
    }

    private const val HIGHLIGHTER_ALPHA = 96
}

/**
 * Full-page snapshot editor used on the teacher device. The bitmap is a read-only background;
 * every gesture is stored in [RemoteReviewEditor]'s independent feedback layer.
 */
class RemoteReviewCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val editor = RemoteReviewEditor()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var pageBitmap: Bitmap? = null
    private var activePointerId = NO_POINTER
    private var activeGestureTool = RemoteReviewTool.PEN
    private var activeGestureSnapshot: RemotePageSnapshotRef? = null
    private var gestureDownX = 0f
    private var gestureDownY = 0f
    private var maxGestureTravelPixels = 0f
    private val gesturePoints = mutableListOf<RemoteNormalizedPoint>()
    private val gradeTapSlopPixels = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    var selectedTool: RemoteReviewTool = RemoteReviewTool.PEN
        set(value) { field = value; invalidate() }
    var penColorArgb: Int = DEFAULT_TEACHER_PEN_COLOR
    var penWidthFraction: Float = RemoteReviewEditor.DEFAULT_PEN_WIDTH_FRACTION
    var highlighterColorArgb: Int = DEFAULT_HIGHLIGHTER_COLOR
    var highlighterWidthFraction: Float = RemoteReviewEditor.DEFAULT_HIGHLIGHTER_WIDTH_FRACTION
    var eraserRadiusFraction: Float = RemoteReviewEditor.DEFAULT_ERASER_RADIUS_FRACTION
    /** Remote review is a static page, so finger and S Pen drawing are both safe here. */
    var acceptFingerInput: Boolean = true
    var onStateChanged: (RemoteReviewState) -> Unit = {}
    var onGradeTap: (RemoteReviewGradeTap) -> Unit = {}

    val reviewState: RemoteReviewState get() = editor.state

    init {
        setBackgroundColor(Color.rgb(45, 47, 52))
        isClickable = true
        contentDescription = "원격 첨삭 필기 영역"
    }

    /** The caller retains ownership of [bitmap] and must not recycle it while this view is open. */
    fun showSnapshot(
        snapshot: RemotePageSnapshotRef,
        bitmap: Bitmap,
        initialFeedback: RemoteTeacherFeedback? = null,
        discardUnpublishedChanges: Boolean = false,
    ): RemoteSnapshotOpenResult {
        val result = editor.openSnapshot(snapshot, initialFeedback, discardUnpublishedChanges)
        if (result == RemoteSnapshotOpenResult.OPENED || result == RemoteSnapshotOpenResult.ALREADY_OPEN) {
            pageBitmap = bitmap
            invalidate()
            publishState()
        }
        return result
    }

    fun clearSnapshot(discardUnpublishedChanges: Boolean = false): Boolean {
        val cleared = editor.clearSnapshot(discardUnpublishedChanges)
        if (cleared) {
            cancelGesture()
            pageBitmap = null
            invalidate()
            publishState()
        }
        return cleared
    }

    fun undo(): Boolean = editor.undo().also { changed ->
        if (changed) {
            invalidate()
            publishState()
        }
    }

    fun buildFeedback(
        feedbackId: String = java.util.UUID.randomUUID().toString(),
        createdAtEpochMillis: Long = System.currentTimeMillis(),
    ): RemoteTeacherFeedback? = editor.buildFeedback(feedbackId, createdAtEpochMillis)

    fun acknowledgePublished(feedback: RemoteTeacherFeedback): Boolean =
        editor.acknowledgePublished(feedback).also { accepted -> if (accepted) publishState() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val page = currentPageRect()
        val bitmap = pageBitmap
        if (bitmap != null && page.width > 0f && page.height > 0f) {
            canvas.drawBitmap(bitmap, null, RectF(page.left, page.top, page.right, page.bottom), bitmapPaint)
        }
        RemoteFeedbackPainter.drawLayer(canvas, page, editor.state.strokes, paint)
        drawActiveGesture(canvas, page)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || editor.state.snapshot == null) return false
        val actionIndex = event.actionIndex.coerceIn(0, event.pointerCount - 1)
        val stylus = event.getToolType(actionIndex) == MotionEvent.TOOL_TYPE_STYLUS ||
            event.getToolType(actionIndex) == MotionEvent.TOOL_TYPE_ERASER
        if (activePointerId == NO_POINTER && !stylus && !acceptFingerInput) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val point = RemoteReviewGeometry.viewToNormalized(event.x, event.y, currentPageRect())
                    ?: return false
                activePointerId = event.getPointerId(actionIndex)
                activeGestureTool = if (event.getToolType(actionIndex) == MotionEvent.TOOL_TYPE_ERASER) {
                    RemoteReviewTool.ERASER
                } else {
                    selectedTool
                }
                activeGestureSnapshot = editor.state.snapshot
                gestureDownX = event.x
                gestureDownY = event.y
                maxGestureTravelPixels = 0f
                gesturePoints.clear()
                gesturePoints += point.copy(pressure = event.pressure.coerceIn(0f, 1f))
                parent.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                collectEventPoints(event)
                invalidate()
                return activePointerId != NO_POINTER
            }
            MotionEvent.ACTION_UP -> {
                val releasePoint = activePointerNormalizedPoint(event)
                collectEventPoints(event)
                finishGesture(releasePoint)
                return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                cancelGesture()
                return true
            }
        }
        return activePointerId != NO_POINTER
    }

    private fun collectEventPoints(event: MotionEvent) {
        val index = event.findPointerIndex(activePointerId)
        if (index < 0) return
        for (history in 0 until event.historySize) {
            collectViewPoint(
                event.getHistoricalX(index, history),
                event.getHistoricalY(index, history),
                event.getHistoricalPressure(index, history),
            )
        }
        collectViewPoint(event.getX(index), event.getY(index), event.getPressure(index))
    }

    private fun activePointerNormalizedPoint(event: MotionEvent): RemoteNormalizedPoint? {
        val index = event.findPointerIndex(activePointerId)
        if (index < 0) return null
        return RemoteReviewGeometry.viewToNormalized(
            event.getX(index),
            event.getY(index),
            currentPageRect(),
        )
    }

    private fun collectViewPoint(x: Float, y: Float, pressure: Float) {
        if (activeGestureTool == RemoteReviewTool.GRADE) {
            val dx = x - gestureDownX
            val dy = y - gestureDownY
            maxGestureTravelPixels = max(
                maxGestureTravelPixels,
                kotlin.math.sqrt(dx * dx + dy * dy),
            )
        }
        val mapped = RemoteReviewGeometry.viewToNormalized(x, y, currentPageRect()) ?: return
        val point = mapped.copy(pressure = pressure.coerceIn(0f, 1f))
        val previous = gesturePoints.lastOrNull()
        if (previous == null || kotlin.math.abs(previous.x - point.x) + kotlin.math.abs(previous.y - point.y) > POINT_EPSILON) {
            gesturePoints += point
        }
    }

    private fun finishGesture(releasePoint: RemoteNormalizedPoint?) {
        val completedTool = activeGestureTool
        val gradeTap = if (completedTool == RemoteReviewTool.GRADE) {
            resolveRemoteReviewGradeTap(
                snapshotAtDown = activeGestureSnapshot,
                currentSnapshot = editor.state.snapshot,
                releasePoint = releasePoint,
                maxTravelPixels = maxGestureTravelPixels,
                tapSlopPixels = gradeTapSlopPixels,
            )
        } else {
            null
        }
        when (completedTool) {
            RemoteReviewTool.PEN -> editor.addStroke(
                tool = RemoteFeedbackStrokeTool.PEN,
                colorArgb = penColorArgb,
                widthFraction = penWidthFraction,
                points = gesturePoints,
            )
            RemoteReviewTool.HIGHLIGHTER -> editor.addStroke(
                tool = RemoteFeedbackStrokeTool.HIGHLIGHTER,
                colorArgb = highlighterColorArgb,
                widthFraction = highlighterWidthFraction,
                points = gesturePoints,
            )
            // Erase calculation deliberately runs once, only after ACTION_UP.
            RemoteReviewTool.ERASER -> editor.erase(gesturePoints, eraserRadiusFraction)
            // Grade selection is a host action, never an edit to the correction stroke layer.
            RemoteReviewTool.GRADE -> Unit
        }
        cancelGesture()
        if (completedTool != RemoteReviewTool.GRADE) publishState()
        gradeTap?.let(onGradeTap)
    }

    private fun cancelGesture() {
        activePointerId = NO_POINTER
        activeGestureSnapshot = null
        maxGestureTravelPixels = 0f
        gesturePoints.clear()
        parent.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }

    private fun drawActiveGesture(canvas: Canvas, page: RemoteReviewRect) {
        if (gesturePoints.isEmpty()) return
        val preview = when (activeGestureTool) {
            RemoteReviewTool.PEN -> RemoteFeedbackStroke(
                tool = RemoteFeedbackStrokeTool.PEN,
                colorArgb = penColorArgb,
                widthFraction = penWidthFraction,
                points = gesturePoints,
            )
            RemoteReviewTool.HIGHLIGHTER -> RemoteFeedbackStroke(
                tool = RemoteFeedbackStrokeTool.HIGHLIGHTER,
                colorArgb = highlighterColorArgb,
                widthFraction = highlighterWidthFraction,
                points = gesturePoints,
            )
            RemoteReviewTool.ERASER -> {
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = Color.rgb(39, 110, 255)
                paint.alpha = 90
                paint.strokeWidth = RemoteReviewGeometry.widthToView(eraserRadiusFraction * 2f, page)
                val first = RemoteReviewGeometry.normalizedToView(gesturePoints.first(), page)
                val path = Path().apply { moveTo(first.x, first.y) }
                for (index in 1 until gesturePoints.size) {
                    val mapped = RemoteReviewGeometry.normalizedToView(gesturePoints[index], page)
                    path.lineTo(mapped.x, mapped.y)
                }
                if (gesturePoints.size == 1) canvas.drawCircle(first.x, first.y, paint.strokeWidth / 2f, paint)
                else canvas.drawPath(path, paint)
                return
            }
            RemoteReviewTool.GRADE -> return
        }
        RemoteFeedbackPainter.drawStroke(canvas, page, preview, paint)
    }

    private fun currentPageRect(): RemoteReviewRect {
        val snapshot = editor.state.snapshot ?: return RemoteReviewRect(0f, 0f, 0f, 0f)
        return RemoteReviewGeometry.fitCenter(
            containerWidth = width.toFloat(),
            containerHeight = height.toFloat(),
            pageWidth = snapshot.imageWidthPx.toFloat(),
            pageHeight = snapshot.imageHeightPx.toFloat(),
        )
    }

    private fun publishState() = onStateChanged(editor.state)

    private companion object {
        const val NO_POINTER = -1
        const val POINT_EPSILON = 0.00005f
        const val DEFAULT_TEACHER_PEN_COLOR = 0xFFD94747.toInt()
        const val DEFAULT_HIGHLIGHTER_COLOR = 0x66FFE45C
    }
}

/** Pure tap qualification kept outside the View so snapshot binding and drag rejection are tested. */
internal fun resolveRemoteReviewGradeTap(
    snapshotAtDown: RemotePageSnapshotRef?,
    currentSnapshot: RemotePageSnapshotRef?,
    releasePoint: RemoteNormalizedPoint?,
    maxTravelPixels: Float,
    tapSlopPixels: Float,
): RemoteReviewGradeTap? {
    if (snapshotAtDown == null || snapshotAtDown != currentSnapshot) return null
    val point = releasePoint ?: return null
    if (
        !point.x.isFinite() || !point.y.isFinite() ||
        point.x !in 0f..1f || point.y !in 0f..1f ||
        !maxTravelPixels.isFinite() || !tapSlopPixels.isFinite() ||
        maxTravelPixels < 0f || tapSlopPixels < 0f || maxTravelPixels > tapSlopPixels
    ) return null
    return RemoteReviewGradeTap(
        snapshot = snapshotAtDown,
        anchor = point.copy(pressure = 1f),
    )
}

/**
 * Drop-in teacher review surface. Transport supplies a decoded bitmap and receives an immutable
 * payload callback; it acknowledges only after the payload is durably queued.
 */
class RemoteReviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    val canvasView = RemoteReviewCanvasView(context)
    private val status = TextView(context)
    private val toolButtons = linkedMapOf<RemoteReviewTool, TextView>()
    private val undoButton: TextView
    private val publishButton: TextView

    var onPublishRequested: (RemoteTeacherFeedback) -> Unit = {}
    var onStateChanged: (RemoteReviewState) -> Unit = {}
    var onGradeTap: (RemoteReviewGradeTap) -> Unit = {}

    val reviewState: RemoteReviewState get() = canvasView.reviewState
    val hasUnpublishedChanges: Boolean get() = reviewState.hasUnpublishedChanges

    init {
        addView(canvasView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
            background = roundedBackground(0xE6FFFFFF.toInt(), 14f)
            elevation = dp(4f).toFloat()
        }
        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val actionScroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                actionRow,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
            )
        }
        toolbar.addView(
            actionScroller,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        fun toolButton(label: String, tool: RemoteReviewTool): TextView = actionButton(label).also { button ->
            toolButtons[tool] = button
            button.setOnClickListener {
                canvasView.selectedTool = tool
                refreshControls(canvasView.reviewState)
            }
            actionRow.addView(button)
        }
        toolButton("펜", RemoteReviewTool.PEN)
        toolButton("형광펜", RemoteReviewTool.HIGHLIGHTER)
        toolButton("지우개", RemoteReviewTool.ERASER)
        toolButton("채점", RemoteReviewTool.GRADE)
        undoButton = actionButton("실행취소").also { button ->
            button.setOnClickListener { canvasView.undo() }
            actionRow.addView(button)
        }
        publishButton = actionButton("첨삭 보내기").also { button ->
            button.setOnClickListener {
                canvasView.buildFeedback()?.let(onPublishRequested)
            }
            actionRow.addView(button)
        }
        status.apply {
            textSize = 12f
            setTextColor(Color.rgb(55, 61, 70))
            gravity = Gravity.END
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4f), dp(2f), dp(4f), 0)
        }
        toolbar.addView(
            status,
            LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        addView(
            toolbar,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
                leftMargin = dp(12f)
                topMargin = dp(12f)
                rightMargin = dp(12f)
            },
        )
        canvasView.onStateChanged = { state ->
            refreshControls(state)
            onStateChanged(state)
        }
        canvasView.onGradeTap = { tap -> onGradeTap(tap) }
        refreshControls(canvasView.reviewState)
    }

    fun showSnapshot(
        snapshot: RemotePageSnapshotRef,
        bitmap: Bitmap,
        initialFeedback: RemoteTeacherFeedback? = null,
        discardUnpublishedChanges: Boolean = false,
    ): RemoteSnapshotOpenResult = canvasView.showSnapshot(
        snapshot,
        bitmap,
        initialFeedback,
        discardUnpublishedChanges,
    )

    fun clearSnapshot(discardUnpublishedChanges: Boolean = false): Boolean =
        canvasView.clearSnapshot(discardUnpublishedChanges)

    fun setTool(tool: RemoteReviewTool) {
        canvasView.selectedTool = tool
        refreshControls(canvasView.reviewState)
    }

    fun undo(): Boolean = canvasView.undo()

    fun buildFeedback(
        feedbackId: String = java.util.UUID.randomUUID().toString(),
        createdAtEpochMillis: Long = System.currentTimeMillis(),
    ): RemoteTeacherFeedback? = canvasView.buildFeedback(feedbackId, createdAtEpochMillis)

    /** Call only after Telegram (or a fallback transport) has durably accepted this payload. */
    fun acknowledgePublished(feedback: RemoteTeacherFeedback): Boolean =
        canvasView.acknowledgePublished(feedback)

    private fun refreshControls(state: RemoteReviewState) {
        toolButtons.forEach { (tool, button) ->
            val selected = tool == canvasView.selectedTool
            button.setTextColor(if (selected) Color.WHITE else Color.rgb(52, 57, 65))
            button.background = roundedBackground(
                if (selected) 0xFF315C96.toInt() else Color.TRANSPARENT,
                9f,
            )
        }
        undoButton.isEnabled = state.canUndo
        undoButton.alpha = if (state.canUndo) 1f else 0.4f
        publishButton.isEnabled = state.hasUnpublishedChanges
        publishButton.alpha = if (state.hasUnpublishedChanges) 1f else 0.4f
        status.text = when {
            state.snapshot == null -> "받은 페이지 없음"
            state.hasUnpublishedChanges -> "${state.snapshot.pageNumber + 1}쪽 · 전송 대기"
            else -> "${state.snapshot.pageNumber + 1}쪽 · 저장됨"
        }
    }

    private fun actionButton(label: String) = TextView(context).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        minWidth = dp(54f)
        minHeight = dp(42f)
        setPadding(dp(9f), dp(7f), dp(9f), dp(7f))
        contentDescription = label
        isClickable = true
        isFocusable = true
    }

    private fun roundedBackground(color: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()
}

/**
 * Read-only student layer placed above the PDF/ink render and below input chrome. It knows nothing
 * about local annotations and therefore cannot alter student strokes, attempts, or marks.
 */
class RemoteFeedbackOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var viewport: PdfViewportAdapter? = null
        set(value) { field = value; invalidate() }
    var feedback: RemoteTeacherFeedback? = null
        set(value) { field = value; invalidate() }
    var activePage: Int = 0
        set(value) { field = value; invalidate() }
    /** Useful when the layer is shown over a decoded image instead of PdfViewportAdapter. */
    var explicitPageBounds: RectF? = null
        set(value) { field = value?.let(::RectF); invalidate() }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val layer = feedback ?: return
        if (layer.pageNumber != activePage) return
        val bounds = explicitPageBounds ?: viewport?.activePageBounds() ?: return
        val page = RemoteReviewRect(bounds.left, bounds.top, bounds.right, bounds.bottom)
        RemoteFeedbackPainter.drawLayer(canvas, page, layer.strokes, paint)
    }
}
