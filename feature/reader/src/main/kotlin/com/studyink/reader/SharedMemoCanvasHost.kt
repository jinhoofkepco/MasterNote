package com.studyink.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.hypot

/**
 * Two transparent layers share one paper and camera, but never share an editing gesture.
 * Stylus edits the selected layer; ink-mode fingers navigate. Construction-mode fingers edit
 * until a second finger explicitly switches the entire physical gesture into navigation.
 */
internal class SharedMemoCanvasHost(context: Context) : FrameLayout(context) {
    val viewport = SharedMemoViewport()
    var geometryLayer: View? = null
        set(value) { if (field !== value) cancelOwnedGesture(); field = value }
    var inkLayer: View? = null
        set(value) { if (field !== value) cancelOwnedGesture(); field = value }
    var geometryMode: Boolean = false
        set(value) { if (field != value) cancelOwnedGesture(); field = value }
    var canChangeViewport: () -> Boolean = { true }
    var onBeforeViewportChange: () -> Unit = {}
    var onViewportChanged: () -> Unit = {}
    var onOwnerChanged: (View) -> Unit = {}
    val hasOwnedGesture: Boolean get() = owner != null

    private var owner: View? = null
    private var ownerPointerId = -1
    private var ownerIsStylus = false
    private var physicalGesture = false
    private var suppressed = false
    private var navigating = false
    private var lastOwnerEvent: MotionEvent? = null
    private var lastNavigation: NavigationSample? = null
    private var pendingSize = false
    private var pendingReset = false
    private var pendingFit = false
    private val paperPaint = Paint().apply { color = Color.rgb(255, 254, 247) }
    private val surroundPaint = Paint().apply { color = Color.rgb(229, 234, 239) }
    private val retrySize = object : Runnable {
        override fun run() {
            if ((pendingSize || pendingReset || pendingFit) && isAttachedToWindow && hasViewportSize() &&
                !applyViewportSize()) postDelayed(this, 32L)
        }
    }

    init {
        clipChildren = true
        clipToPadding = true
        isMotionEventSplittingEnabled = false
        setWillNotDraw(false)
        viewport.onChanged = {
            invalidate()
            geometryLayer?.invalidate()
            inkLayer?.invalidate()
            onViewportChanged()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), surroundPaint)
        canvas.drawRect(viewport.paperBounds, paperPaint)
        super.onDraw(canvas)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cancelOwnedGesture()
        pendingSize = w > 0 && h > 0
        removeCallbacks(retrySize)
        if (pendingSize && !applyViewportSize()) postDelayed(retrySize, 32L)
    }

    private fun applyViewportSize(): Boolean {
        if (!hasViewportSize() || !canChangeViewport()) return false
        onBeforeViewportChange()
        applyPendingViewport()
        return true
    }

    /** Persistence owners can release deferred resize / new-memo reset after durable wet ink settles. */
    fun resumePendingResize() {
        if ((pendingSize || pendingReset || pendingFit) && applyViewportSize()) removeCallbacks(retrySize)
    }

    /** Toolbar actions use the same cancellation and durable-wet-ink gate as pinch gestures. */
    fun zoomBy(factor: Float): Boolean {
        cancelOwnedGesture()
        return changeViewport { viewport.zoom(factor, width / 2f, height / 2f) }
    }

    fun resetViewport(): Boolean {
        cancelOwnedGesture()
        pendingFit = false
        if (!hasViewportSize() || !canChangeViewport()) {
            pendingReset = true
            removeCallbacks(retrySize)
            postDelayed(retrySize, 32L)
            return false
        }
        pendingReset = false
        return changeViewport { viewport.reset() }
    }

    fun fitContent(): Boolean {
        cancelOwnedGesture()
        pendingReset = false
        if (!hasViewportSize() || !canChangeViewport()) {
            pendingFit = true
            removeCallbacks(retrySize)
            postDelayed(retrySize, 32L)
            return false
        }
        pendingFit = false
        return changeViewport { viewport.fitContent() }
    }

    private inline fun changeViewport(change: () -> Unit): Boolean {
        if (!hasViewportSize() || !canChangeViewport()) return false
        onBeforeViewportChange()
        applyPendingViewport()
        change()
        return true
    }

    private fun hasViewportSize(): Boolean = width > 0 && height > 0

    private fun applyPendingViewport() {
        if (pendingSize) {
            viewport.updateSize(width, height)
            pendingSize = false
        }
        if (pendingReset) {
            viewport.reset()
            pendingReset = false
        }
        if (pendingFit) {
            viewport.fitContent()
            pendingFit = false
        }
        removeCallbacks(retrySize)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginGesture(event)
            MotionEvent.ACTION_CANCEL -> {
                cancelOwnedGesture()
                finishPhysicalGesture()
                return true
            }
        }
        if (!physicalGesture) return true
        if (!suppressed) {
            if (owner != null && !ownerIsStylus && fingerCount(event) >= 2) {
                cancelEditingOwner()
                navigating = true
                lastNavigation = navigationSample(event)
            }
            if (navigating) navigate(event) else dispatchEditing(event)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) finishPhysicalGesture()
        return true
    }

    private fun beginGesture(event: MotionEvent) {
        cancelOwnedGesture()
        finishPhysicalGesture()
        physicalGesture = true
        parent?.requestDisallowInterceptTouchEvent(true)
        ownerIsStylus = isStylus(event.getToolType(0))
        val bounds = editingBounds()
        if ((!geometryMode && !ownerIsStylus) ||
            (!bounds.containsInclusive(event.x, event.y) && !ownerIsStylus)) {
            navigating = true
            lastNavigation = navigationSample(event)
            return
        }
        if (!bounds.containsInclusive(event.x, event.y)) {
            suppressed = true
            return
        }
        owner = (if (geometryMode) geometryLayer else inkLayer)?.takeIf { it.visibility == VISIBLE }
        ownerPointerId = event.getPointerId(0)
        owner?.let(onOwnerChanged)
        if (owner == null) suppressed = true
    }

    private fun dispatchEditing(source: MotionEvent) {
        val target = owner ?: return
        val index = source.findPointerIndex(ownerPointerId)
        if (index < 0) {
            cancelOwnedGesture()
            return
        }
        val action = when (source.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> source.actionMasked
            MotionEvent.ACTION_POINTER_DOWN -> return // Palm contact never cancels a stylus stroke.
            MotionEvent.ACTION_POINTER_UP -> if (source.actionIndex == index) MotionEvent.ACTION_UP else return
            else -> return
        }
        val bounds = editingBounds()
        val x = source.getX(index)
        val y = source.getY(index)
        if (!bounds.containsInclusive(x, y)) {
            val previous = lastOwnerEvent
            if (previous != null) {
                val end = boundaryPoint(bounds, previous.x, previous.y, x, y)
                val clipped = singlePointerEvent(source, index, MotionEvent.ACTION_UP, withHistory = false)
                clipped.setLocation(end.first, end.second)
                dispatchTo(target, clipped)
                clipped.recycle()
            } else cancelEditingOwner()
            clearOwner()
            suppressed = true
            return
        }
        val event = singlePointerEvent(source, index, action)
        dispatchTo(target, event)
        lastOwnerEvent?.recycle()
        lastOwnerEvent = MotionEvent.obtainNoHistory(event)
        event.recycle()
        if (action == MotionEvent.ACTION_UP) {
            clearOwner()
            // A stylus can lift while a palm remains. That palm cannot start panning or editing.
            suppressed = true
        }
    }

    private fun navigate(event: MotionEvent) {
        val sample = navigationSample(event)
        if (event.actionMasked == MotionEvent.ACTION_MOVE && sample != null) {
            val previous = lastNavigation
            if (previous != null && previous.ids == sample.ids) {
                val factor = if (previous.span > 0f && sample.span > 0f) sample.span / previous.span else 1f
                if (factor != 1f || sample.x != previous.x || sample.y != previous.y) {
                    changeViewport {
                        viewport.transform(factor, previous.x, previous.y, sample.x, sample.y)
                    }
                }
            }
        }
        // POINTER_UP excludes the lifted finger, avoiding a jump when one-finger pan continues.
        lastNavigation = sample
    }

    /** Cancel once and swallow all remaining events until the next physical DOWN. */
    fun cancelOwnedGesture() {
        cancelEditingOwner()
        navigating = false
        lastNavigation = null
        if (physicalGesture) suppressed = true
    }

    private fun cancelEditingOwner() {
        val target = owner
        val previous = lastOwnerEvent
        if (target != null && previous != null) {
            val cancel = MotionEvent.obtainNoHistory(previous)
            cancel.action = MotionEvent.ACTION_CANCEL
            dispatchTo(target, cancel)
            cancel.recycle()
        }
        clearOwner()
    }

    private fun clearOwner() {
        owner = null
        ownerPointerId = -1
        lastOwnerEvent?.recycle()
        lastOwnerEvent = null
    }

    private fun finishPhysicalGesture() {
        clearOwner()
        physicalGesture = false
        suppressed = false
        navigating = false
        ownerIsStylus = false
        lastNavigation = null
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        val target = (if (geometryMode) geometryLayer else inkLayer)?.takeIf { it.visibility == VISIBLE }
            ?: return true
        val transformed = MotionEvent.obtain(event)
        transformed.offsetLocation(-target.left.toFloat(), -target.top.toFloat())
        target.dispatchGenericMotionEvent(transformed)
        transformed.recycle()
        return true
    }

    private fun dispatchTo(target: View, source: MotionEvent) {
        val transformed = MotionEvent.obtain(source)
        transformed.offsetLocation(-target.left.toFloat(), -target.top.toFloat())
        target.dispatchTouchEvent(transformed)
        transformed.recycle()
    }

    private fun editingBounds(): RectF = if (geometryMode) {
        RectF(0f, 0f, width.toFloat(), height.toFloat())
    } else viewport.paperBounds.apply {
        if (!intersect(0f, 0f, width.toFloat(), height.toFloat())) setEmpty()
    }

    private fun fingerCount(event: MotionEvent): Int = (0 until event.pointerCount).count {
        event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER &&
            !(event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.actionIndex == it)
    }

    private fun navigationSample(event: MotionEvent): NavigationSample? {
        val indices = (0 until event.pointerCount).filter {
            event.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER &&
                !(event.actionMasked == MotionEvent.ACTION_POINTER_UP && event.actionIndex == it)
        }
        if (indices.isEmpty()) return null
        val chosen = indices.take(2)
        val x = chosen.sumOf { event.getX(it).toDouble() }.toFloat() / chosen.size
        val y = chosen.sumOf { event.getY(it).toDouble() }.toFloat() / chosen.size
        val span = if (chosen.size == 2) hypot(event.getX(chosen[0]) - event.getX(chosen[1]),
            event.getY(chosen[0]) - event.getY(chosen[1])) else 0f
        return NavigationSample(chosen.map(event::getPointerId), x, y, span)
    }

    override fun onDetachedFromWindow() {
        cancelOwnedGesture()
        finishPhysicalGesture()
        removeCallbacks(retrySize)
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (pendingSize || pendingReset || pendingFit) {
            removeCallbacks(retrySize)
            post(retrySize)
        }
    }

    private data class NavigationSample(val ids: List<Int>, val x: Float, val y: Float, val span: Float)

    private companion object {
        fun isStylus(tool: Int) = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
        fun RectF.containsInclusive(x: Float, y: Float) = !isEmpty && x >= left && x <= right && y >= top && y <= bottom

        fun boundaryPoint(bounds: RectF, startX: Float, startY: Float, endX: Float, endY: Float): Pair<Float, Float> {
            val dx = endX - startX
            val dy = endY - startY
            var t = 1f
            if (dx > 0f) t = minOf(t, (bounds.right - startX) / dx)
            if (dx < 0f) t = minOf(t, (bounds.left - startX) / dx)
            if (dy > 0f) t = minOf(t, (bounds.bottom - startY) / dy)
            if (dy < 0f) t = minOf(t, (bounds.top - startY) / dy)
            t = t.coerceIn(0f, 1f)
            return (startX + dx * t).coerceIn(bounds.left, bounds.right) to
                (startY + dy * t).coerceIn(bounds.top, bounds.bottom)
        }

        /** Public MotionEvent API only; preserve stylus pressure/history without forwarding palms. */
        fun singlePointerEvent(source: MotionEvent, index: Int, action: Int, withHistory: Boolean = true): MotionEvent {
            val props = MotionEvent.PointerProperties().also { source.getPointerProperties(index, it) }
            val coords = MotionEvent.PointerCoords()
            val history = if (withHistory && action == MotionEvent.ACTION_MOVE) source.historySize else 0
            if (history > 0) source.getHistoricalPointerCoords(index, 0, coords)
            else source.getPointerCoords(index, coords)
            val result = MotionEvent.obtain(source.downTime,
                if (history > 0) source.getHistoricalEventTime(0) else source.eventTime,
                action, 1, arrayOf(props), arrayOf(coords), source.metaState, source.buttonState,
                source.xPrecision, source.yPrecision, source.deviceId, source.edgeFlags, source.source, source.flags)
            for (position in 1 until history) {
                source.getHistoricalPointerCoords(index, position, coords)
                result.addBatch(source.getHistoricalEventTime(position), arrayOf(coords), source.metaState)
            }
            if (history > 0) {
                source.getPointerCoords(index, coords)
                result.addBatch(source.eventTime, arrayOf(coords), source.metaState)
            }
            return result
        }
    }
}
