package com.studyink.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View

/**
 * Full-screen, in-app rectangular selector. The host supplies an optional active-page limit and
 * receives a defensive copy of the final rectangle in this view's coordinate system.
 *
 * This view performs no bitmap/PDF work. Cropping and canonical conversion belong to the caller's
 * background workflow after [onSelectionConfirmed] fires.
 */
class PageRegionSelectionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var onSelectionConfirmed: (RectF) -> Unit = {}
    var onSelectionCancelled: () -> Unit = {}
    var onSelectionChanged: (RectF?) -> Unit = {}

    private val density = resources.displayMetrics.density
    private val dimPaint = Paint().apply { color = Color.argb(166, 20, 25, 32) }
    private val limitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(120, 255, 255, 255)
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.rgb(44, 108, 232)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.rgb(44, 108, 232)
    }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(15f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val hintTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(15f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(dp(2f), 0f, dp(1f), Color.argb(190, 0, 0, 0))
    }

    private var selectionLimit: RectF? = null
    private var selection: AssistantUiRect? = null
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var resizeOrigin: AssistantUiRect? = null
    private var activeHandle = AssistantSelectionHandle.NONE
    private var gesture = Gesture.NONE
    private val cancelButton = RectF()
    private val confirmButton = RectF()

    init {
        visibility = GONE
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "문제 영역 선택"
    }

    /** Makes the selector visible and clears any previous gesture state. */
    fun beginSelection(
        limitInView: RectF? = null,
        initialSelectionInView: RectF? = null,
    ) {
        parent?.requestDisallowInterceptTouchEvent(false)
        selectionLimit = limitInView?.normalizedCopy()
        selection = initialSelectionInView?.normalizedCopy()?.let { initial ->
            intersect(initial, resolvedLimit())?.toAssistantRect()
        }
        resetGesture()
        visibility = VISIBLE
        requestFocus()
        publishSelection()
        invalidate()
    }

    /** Changes the active-page limit without starting or completing a selection. */
    fun updateSelectionLimit(limitInView: RectF?) {
        selectionLimit = limitInView?.normalizedCopy()
        selection = selection?.let { current ->
            intersect(current.toRectF(), resolvedLimit())?.toAssistantRect()
        }
        publishSelection()
        invalidate()
    }

    fun currentSelectionInView(): RectF? = selection?.toRectF()

    fun confirmSelection(): Boolean {
        val selected = selection?.takeIf(::isConfirmable) ?: return false
        val output = selected.toRectF()
        visibility = GONE
        resetGesture()
        parent?.requestDisallowInterceptTouchEvent(false)
        onSelectionConfirmed(output)
        return true
    }

    fun cancelSelection() {
        if (visibility != VISIBLE) return
        visibility = GONE
        resetGesture()
        parent?.requestDisallowInterceptTouchEvent(false)
        onSelectionCancelled()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        val horizontalMargin = dp(16f)
        val top = dp(16f)
        val buttonWidth = dp(84f)
        val buttonHeight = dp(48f)
        cancelButton.set(horizontalMargin, top, horizontalMargin + buttonWidth, top + buttonHeight)
        confirmButton.set(
            width - horizontalMargin - buttonWidth,
            top,
            width - horizontalMargin,
            top + buttonHeight,
        )
        selection = selection?.let { current ->
            intersect(current.toRectF(), resolvedLimit())?.toAssistantRect()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val limit = resolvedLimit()
        val selected = selection?.toRectF()
        drawOutsideDim(canvas, selected)
        canvas.drawRect(limit, limitPaint)
        if (selected != null) {
            canvas.drawRoundRect(selected, dp(4f), dp(4f), borderPaint)
            drawHandle(canvas, selected.left, selected.top)
            drawHandle(canvas, selected.right, selected.top)
            drawHandle(canvas, selected.left, selected.bottom)
            drawHandle(canvas, selected.right, selected.bottom)
        }
        drawButton(canvas, cancelButton, "취소", enabled = true, destructive = true)
        drawButton(canvas, confirmButton, "선택", enabled = selection?.let(::isConfirmable) == true)
        val hintY = cancelButton.bottom + dp(28f)
        canvas.drawText("손가락이나 S Pen으로 설명할 영역을 드래그하세요", width / 2f, hintY, hintTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (visibility != VISIBLE || !isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!acceptsSelectionTool(event, event.actionIndex)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                gesture = when {
                    cancelButton.contains(event.x, event.y) -> Gesture.CANCEL_BUTTON
                    confirmButton.contains(event.x, event.y) -> Gesture.CONFIRM_BUTTON
                    resolvedLimit().contains(event.x, event.y) -> beginDrag(event.x, event.y)
                    else -> Gesture.BLOCKING
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (gesture) {
                    Gesture.DRAW -> updateDraw(event.x, event.y)
                    Gesture.RESIZE -> updateResize(event.x, event.y)
                    else -> Unit
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                when (gesture) {
                    Gesture.DRAW -> updateDraw(event.x, event.y)
                    Gesture.RESIZE -> updateResize(event.x, event.y)
                    Gesture.CANCEL_BUTTON -> if (cancelButton.contains(event.x, event.y)) cancelSelection()
                    Gesture.CONFIRM_BUTTON -> if (confirmButton.contains(event.x, event.y)) confirmSelection()
                    Gesture.BLOCKING, Gesture.NONE -> Unit
                }
                performClick()
                resetGesture()
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                resetGesture()
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return gesture != Gesture.NONE
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun beginDrag(x: Float, y: Float): Gesture {
        val current = selection
        if (current != null) {
            val handle = assistantSelectionHandleAt(current, x, y, dp(HANDLE_HIT_RADIUS_DP))
            if (handle != AssistantSelectionHandle.NONE) {
                activeHandle = handle
                resizeOrigin = current
                return Gesture.RESIZE
            }
        }
        dragStartX = x
        dragStartY = y
        selection = normalizedAssistantRect(x, y, x, y, resolvedLimit().toAssistantRect())
        publishSelection()
        return Gesture.DRAW
    }

    private fun updateDraw(x: Float, y: Float) {
        selection = normalizedAssistantRect(
            dragStartX,
            dragStartY,
            x,
            y,
            resolvedLimit().toAssistantRect(),
        )
        publishSelection()
        invalidate()
    }

    private fun updateResize(x: Float, y: Float) {
        val origin = resizeOrigin ?: return
        selection = resizeAssistantSelection(
            original = origin,
            handle = activeHandle,
            pointerX = x,
            pointerY = y,
            limit = resolvedLimit().toAssistantRect(),
            minimumSize = dp(MINIMUM_SELECTION_DP),
        )
        publishSelection()
        invalidate()
    }

    private fun publishSelection() {
        onSelectionChanged(selection?.toRectF())
    }

    private fun isConfirmable(rect: AssistantUiRect): Boolean =
        rect.width >= dp(MINIMUM_SELECTION_DP) && rect.height >= dp(MINIMUM_SELECTION_DP)

    private fun resolvedLimit(): RectF {
        val viewBounds = RectF(0f, 0f, width.toFloat().coerceAtLeast(0f), height.toFloat().coerceAtLeast(0f))
        val requested = selectionLimit ?: return viewBounds
        return intersect(viewBounds, requested) ?: viewBounds
    }

    private fun drawOutsideDim(canvas: Canvas, selection: RectF?) {
        if (selection == null) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            return
        }
        canvas.drawRect(0f, 0f, width.toFloat(), selection.top, dimPaint)
        canvas.drawRect(0f, selection.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, selection.top, selection.left, selection.bottom, dimPaint)
        canvas.drawRect(selection.right, selection.top, width.toFloat(), selection.bottom, dimPaint)
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        val radius = dp(HANDLE_RADIUS_DP)
        canvas.drawCircle(x, y, radius, handlePaint)
        canvas.drawCircle(x, y, radius, handleBorderPaint)
    }

    private fun drawButton(
        canvas: Canvas,
        bounds: RectF,
        label: String,
        enabled: Boolean,
        destructive: Boolean = false,
    ) {
        buttonPaint.color = when {
            !enabled -> Color.argb(180, 119, 126, 139)
            destructive -> Color.argb(236, 69, 75, 84)
            else -> Color.argb(245, 44, 108, 232)
        }
        canvas.drawRoundRect(bounds, dp(12f), dp(12f), buttonPaint)
        val metrics = buttonTextPaint.fontMetrics
        val baseline = bounds.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label, bounds.centerX(), baseline, buttonTextPaint)
    }

    private fun resetGesture() {
        gesture = Gesture.NONE
        resizeOrigin = null
        activeHandle = AssistantSelectionHandle.NONE
    }

    private fun acceptsSelectionTool(event: MotionEvent, pointerIndex: Int): Boolean = when (
        event.getToolType(pointerIndex.coerceIn(0, event.pointerCount - 1))
    ) {
        MotionEvent.TOOL_TYPE_FINGER,
        MotionEvent.TOOL_TYPE_STYLUS,
        MotionEvent.TOOL_TYPE_ERASER,
        -> true
        else -> false
    }

    private fun RectF.normalizedCopy(): RectF = RectF(
        minOf(left, right),
        minOf(top, bottom),
        maxOf(left, right),
        maxOf(top, bottom),
    )

    private fun RectF.toAssistantRect(): AssistantUiRect = AssistantUiRect(left, top, right, bottom)
    private fun AssistantUiRect.toRectF(): RectF = RectF(left, top, right, bottom)

    private fun intersect(first: RectF, second: RectF): RectF? {
        val left = maxOf(first.left, second.left)
        val top = maxOf(first.top, second.top)
        val right = minOf(first.right, second.right)
        val bottom = minOf(first.bottom, second.bottom)
        return RectF(left, top, right, bottom).takeIf { it.width() >= 0f && it.height() >= 0f }
    }

    private fun dp(value: Float): Float = value * density
    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private enum class Gesture { NONE, DRAW, RESIZE, CANCEL_BUTTON, CONFIRM_BUTTON, BLOCKING }

    private companion object {
        const val MINIMUM_SELECTION_DP = 24f
        const val HANDLE_RADIUS_DP = 7f
        const val HANDLE_HIT_RADIUS_DP = 22f
    }
}
