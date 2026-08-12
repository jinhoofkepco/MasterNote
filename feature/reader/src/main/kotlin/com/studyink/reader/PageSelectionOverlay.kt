package com.studyink.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.studyink.document.pdf.PdfViewportAdapter
import kotlin.math.abs

internal class PageSelectionOverlay(context: Context) : View(context) {
    lateinit var viewport: PdfViewportAdapter
    var onSelected: ((RectF) -> Unit)? = null
    private var start: PointF? = null
    private var current: PointF? = null
    private val fill = Paint().apply { color = Color.argb(48, 90, 70, 220); style = Paint.Style.FILL }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 70, 220); style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density * 2 }

    override fun onDraw(canvas: Canvas) {
        val a = start ?: return
        val b = current ?: return
        val rect = RectF(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
        canvas.drawRect(rect, fill); canvas.drawRect(rect, border)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (viewport.viewToNormalized(event.x, event.y) == null) return false
                start = PointF(event.x, event.y); current = PointF(event.x, event.y); invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> { current = PointF(event.x, event.y); invalidate(); return true }
            MotionEvent.ACTION_UP -> {
                val a = start ?: return false
                val p1 = viewport.viewToNormalized(a.x, a.y); val p2 = viewport.viewToNormalized(event.x, event.y)
                start = null; current = null; invalidate()
                if (p1 != null && p2 != null && abs(event.x - a.x) > 24 && abs(event.y - a.y) > 24) onSelected?.invoke(RectF(minOf(p1.x, p2.x), minOf(p1.y, p2.y), maxOf(p1.x, p2.x), maxOf(p1.y, p2.y)))
                return true
            }
            MotionEvent.ACTION_CANCEL -> { start = null; current = null; invalidate(); return true }
        }
        return false
    }
}
