package com.studyink.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.studyink.annotation.engine.EraseEngine
import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeTool
import com.studyink.document.pdf.PdfViewportAdapter
import kotlin.math.max

data class EraserPreview(
    val pageNumber: Int,
    val path: List<PagePoint>,
    val radius: Float,
)

class DryInkView(context: Context) : View(context) {
    var viewport: PdfViewportAdapter? = null
        set(value) { field = value; invalidate() }
    var snapshot: AnnotationSnapshot = AnnotationSnapshot.empty("sample")
        set(value) { field = value; invalidate() }
    var eraserPreview: EraserPreview? = null
        set(value) { field = value; invalidate() }
    var activePage: Int = 0
        set(value) { field = value; invalidate() }

    private val pageMaskPaint = Paint().apply { color = Color.rgb(225, 226, 231) }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val adapter = viewport ?: return
        drawPageMask(canvas, adapter.activePageBounds())
        val active = snapshot.activeStrokes.filter { it.pageNumber == activePage }
        active.forEach { drawStroke(canvas, adapter, it, false) }

        val preview = eraserPreview ?: return
        EraseEngine.partialErasePreviewSegments(
            active, preview.pageNumber, preview.path, preview.radius
        ).forEach { drawStroke(canvas, adapter, it, true) }
        drawEraserPath(canvas, adapter, preview)
    }

    private fun drawPageMask(canvas: Canvas, page: RectF?) {
        if (page == null) return
        canvas.drawRect(0f, 0f, width.toFloat(), page.top.coerceAtLeast(0f), pageMaskPaint)
        canvas.drawRect(0f, page.bottom.coerceAtMost(height.toFloat()), width.toFloat(), height.toFloat(), pageMaskPaint)
        canvas.drawRect(0f, page.top, page.left.coerceAtLeast(0f), page.bottom, pageMaskPaint)
        canvas.drawRect(page.right.coerceAtMost(width.toFloat()), page.top, width.toFloat(), page.bottom, pageMaskPaint)
    }

    private fun drawStroke(canvas: Canvas, adapter: PdfViewportAdapter, stroke: StrokeAsset, preview: Boolean) {
        if (stroke.points.isEmpty()) return
        paint.color = if (preview) Color.rgb(39, 110, 255) else stroke.colorArgb
        paint.alpha = when {
            preview -> 190
            stroke.tool == StrokeTool.HIGHLIGHTER -> 95
            else -> Color.alpha(stroke.colorArgb)
        }
        paint.strokeWidth = max(1f, adapter.canonicalWidthToView(stroke.pageNumber, stroke.width))
        val first = adapter.canonicalToView(stroke.pageNumber, stroke.points.first()) ?: return
        if (stroke.points.size == 1) {
            canvas.drawCircle(first.x, first.y, paint.strokeWidth / 2f, paint)
            return
        }
        val path = Path().apply { moveTo(first.x, first.y) }
        stroke.points.drop(1).forEach { point ->
            adapter.canonicalToView(stroke.pageNumber, point)?.let { path.lineTo(it.x, it.y) }
        }
        canvas.drawPath(path, paint)
    }

    private fun drawEraserPath(canvas: Canvas, adapter: PdfViewportAdapter, preview: EraserPreview) {
        if (preview.path.isEmpty()) return
        paint.color = Color.rgb(39, 110, 255)
        paint.alpha = 95
        paint.strokeWidth = max(2f, adapter.canonicalWidthToView(preview.pageNumber, preview.radius * 2f))
        val first = adapter.canonicalToView(preview.pageNumber, preview.path.first()) ?: return
        val path = Path().apply { moveTo(first.x, first.y) }
        preview.path.drop(1).forEach { point ->
            adapter.canonicalToView(preview.pageNumber, point)?.let { path.lineTo(it.x, it.y) }
        }
        if (preview.path.size == 1) canvas.drawCircle(first.x, first.y, paint.strokeWidth / 2f, paint)
        else canvas.drawPath(path, paint)
    }
}
