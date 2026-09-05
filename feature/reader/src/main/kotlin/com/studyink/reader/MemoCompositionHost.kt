package com.studyink.reader

import android.content.Context
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout

/**
 * Two independent editing surfaces. Ownership is latched on physical DOWN and never crosses panes.
 * Leaving the original pane finishes at the boundary, then consumes the gesture until physical UP.
 * Thus a stroke cannot acquire a spurious straight connection when the pen re-enters the memo.
 */
internal class MemoCompositionHost(context: Context) : LinearLayout(context) {
    private var owner: View? = null
    private var gestureFinished = false
    private var lastX = 0f
    private var lastY = 0f
    var onOwnerChanged: (View) -> Unit = {}
    val hasOwnedGesture: Boolean get() = owner != null

    init {
        orientation = HORIZONTAL
        clipChildren = true
        clipToPadding = true
        isMotionEventSplittingEnabled = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The editor / divider / ink composition adapts as one unit. Two-child hosts keep
        // their caller-supplied layout, including the independent input-routing fixtures.
        if (childCount == 3) {
            val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
            val availableHeight = MeasureSpec.getSize(heightMeasureSpec)
            val vertical = availableWidth < 560f * resources.displayMetrics.density &&
                availableHeight > availableWidth
            val nextOrientation = if (vertical) VERTICAL else HORIZONTAL
            if (orientation != nextOrientation) {
                cancelOwnedGesture()
                orientation = nextOrientation
            }
            for (index in listOf(0, 2)) {
                val params = getChildAt(index).layoutParams as LayoutParams
                params.width = if (vertical) LayoutParams.MATCH_PARENT else 0
                params.height = if (vertical) 0 else LayoutParams.MATCH_PARENT
                params.weight = if (index == 0) .56f else .44f
            }
            val divider = getChildAt(1).layoutParams as LayoutParams
            val thickness = (resources.displayMetrics.density + .5f).toInt().coerceAtLeast(1)
            divider.width = if (vertical) LayoutParams.MATCH_PARENT else thickness
            divider.height = if (vertical) thickness else LayoutParams.MATCH_PARENT
            divider.weight = 0f
            // With the editor and divider GONE, LinearLayout distributes all space to ink.
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancelOwnedGesture()
            owner = (childCount - 1 downTo 0).asSequence().map(::getChildAt)
                .firstOrNull { it.visibility == VISIBLE && paneBounds(it).contains(event.x, event.y) }
            gestureFinished = false
            lastX = event.x
            lastY = event.y
            owner?.let(onOwnerChanged)
        }
        val target = owner ?: return true
        if (!gestureFinished) {
            val bounds = paneBounds(target)
            val outside = (0 until event.pointerCount).any { !bounds.contains(event.getX(it), event.getY(it)) }
            if (outside && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                if (event.pointerCount == 1) {
                    val end = boundaryPoint(bounds, lastX, lastY, event.x, event.y)
                    val clipped = MotionEvent.obtainNoHistory(event)
                    clipped.action = MotionEvent.ACTION_UP
                    clipped.setLocation(end.first, end.second)
                    dispatchTo(target, clipped)
                    clipped.recycle()
                } else {
                    val cancel = MotionEvent.obtainNoHistory(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    dispatchTo(target, cancel)
                    cancel.recycle()
                }
                gestureFinished = true
            } else {
                dispatchTo(target, event)
                lastX = event.x
                lastY = event.y
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            owner = null
            gestureFinished = false
        }
        return true
    }

    fun cancelOwnedGesture() {
        val target = owner
        if (target != null && !gestureFinished) {
            val now = android.os.SystemClock.uptimeMillis()
            val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, lastX, lastY, 0)
            dispatchTo(target, cancel)
            cancel.recycle()
        }
        owner = null
        gestureFinished = false
    }

    private fun dispatchTo(target: View, source: MotionEvent) {
        val transformed = MotionEvent.obtain(source)
        transformed.offsetLocation(-target.left.toFloat(), -target.top.toFloat())
        target.dispatchTouchEvent(transformed)
        transformed.recycle()
    }

    private fun paneBounds(view: View) = RectF(
        view.left.toFloat(), view.top.toFloat(), view.right.toFloat(), view.bottom.toFloat(),
    )

    override fun onDetachedFromWindow() {
        cancelOwnedGesture()
        super.onDetachedFromWindow()
    }

    companion object {
        internal fun boundaryPoint(bounds: RectF, startX: Float, startY: Float, endX: Float, endY: Float): Pair<Float, Float> {
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
    }
}
