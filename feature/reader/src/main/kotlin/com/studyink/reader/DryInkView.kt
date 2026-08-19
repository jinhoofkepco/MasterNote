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
import com.studyink.core.model.MarkColor
import com.studyink.core.model.MarkGroup
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeTool
import com.studyink.document.pdf.PdfViewportAdapter
import kotlin.math.hypot
import kotlin.math.max

data class EraserPreview(
    val pageNumber: Int,
    val path: List<PagePoint>,
    val radius: Float,
)

data class StylusHoverPreview(
    val x: Float,
    val y: Float,
    val colorArgb: Int,
    val widthPixels: Float,
    val eraser: Boolean,
)

class DryInkView(context: Context) : View(context) {
    private val readerTokens = readerCanvasTokens()
    var viewport: PdfViewportAdapter? = null
        set(value) { field = value; invalidate() }
    var snapshot: AnnotationSnapshot = AnnotationSnapshot.empty("unopened")
        set(value) { field = value; rebuildPageCache(); invalidate() }
    var eraserPreview: EraserPreview? = null
        set(value) { field = value; invalidate() }
    var hoverPreview: StylusHoverPreview? = null
        set(value) { field = value; invalidate() }
    var activePage: Int = 0
        set(value) { field = value; invalidate() }
    var visibleAttemptNo: Int = 1
        set(value) { field = value; invalidate() }
    var showTeacherDrafts: Boolean = false
        set(value) { field = value; rebuildPageCache(); invalidate() }
    var markGroups: List<MarkGroup> = emptyList()
        set(value) { field = value; invalidate() }
    var pressedMarkGroupId: String? = null
        set(value) { field = value; invalidate() }

    private var cachedPageStrokes: Map<Int, List<StrokeAsset>> = emptyMap()
    private val pageMaskPaint = Paint().apply { color = Color.rgb(225, 226, 231) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = readerTokens.markAlpha
    }
    private val markFocusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = readerTokens.markFocusArgb
    }
    private val markHistoryBounds = mutableMapOf<String, RectF>()
    private val markHistoryAnchors = mutableMapOf<String, android.graphics.PointF>()
    private val markHistoryCounts = mutableMapOf<String, Int>()
    private val markCellHits = mutableListOf<MarkCellHit>()
    private val markHistoryOffsets = mutableMapOf<String, Int>()
    private val markHistoryDragRemainders = mutableMapOf<String, Float>()

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val adapter = viewport ?: return
        drawPageMask(canvas, adapter.activePageBounds())
        val visibleBounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val active = cachedPageStrokes[activePage].orEmpty().filter { stroke ->
            stroke.attemptNo == visibleAttemptNo && isOnScreen(adapter, stroke, visibleBounds)
        }
        active.forEach { drawStroke(canvas, adapter, it, false) }

        eraserPreview?.let { preview ->
            EraseEngine.partialErasePreviewSegments(
                active, preview.pageNumber, preview.path, preview.radius
            ).forEach { drawStroke(canvas, adapter, it, true) }
            drawEraserPath(canvas, adapter, preview)
        }
        drawMarks(canvas, adapter)
        drawHover(canvas)
    }

    private fun rebuildPageCache() {
        cachedPageStrokes = snapshot.activeStrokes.asSequence()
            .filter { stroke ->
                stroke.authorId != "teacher" || showTeacherDrafts || stroke.publishedAtEpochMillis != null
            }
            .groupBy(StrokeAsset::pageNumber)
    }

    private fun isOnScreen(adapter: PdfViewportAdapter, stroke: StrokeAsset, viewportBounds: RectF): Boolean {
        val topLeft = adapter.canonicalToView(stroke.pageNumber, PagePoint(stroke.bounds.left, stroke.bounds.top))
            ?: return false
        val bottomRight = adapter.canonicalToView(stroke.pageNumber, PagePoint(stroke.bounds.right, stroke.bounds.bottom))
            ?: return false
        val padding = max(2f, adapter.canonicalWidthToView(stroke.pageNumber, stroke.width))
        val bounds = RectF(
            minOf(topLeft.x, bottomRight.x) - padding,
            minOf(topLeft.y, bottomRight.y) - padding,
            maxOf(topLeft.x, bottomRight.x) + padding,
            maxOf(topLeft.y, bottomRight.y) + padding,
        )
        return RectF.intersects(bounds, viewportBounds)
    }

    private fun drawPageMask(canvas: Canvas, page: RectF?) {
        if (page == null) {
            canvas.drawColor(pageMaskPaint.color)
            return
        }
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

    private fun drawMarks(canvas: Canvas, adapter: PdfViewportAdapter) {
        val groups = markGroups.filter { it.pageNumber == activePage && it.hiddenAtEpochMillis == null }
            .sortedBy { it.anchor.y }
        val cell = dp(readerTokens.markCellDp)
        val gap = dp(readerTokens.markGapDp)
        markHistoryBounds.clear()
        markHistoryAnchors.clear()
        markHistoryCounts.clear()
        markCellHits.clear()
        groups.forEachIndexed { groupIndex, group ->
            val anchor = adapter.canonicalToView(activePage, group.anchor) ?: return@forEachIndexed
            val history = group.marks.filter { it.hiddenAtEpochMillis == null }.toMutableList()
            if (history.none { it.attemptNo == visibleAttemptNo }) {
                history += com.studyink.core.model.Mark(visibleAttemptNo, MarkColor.GRAY)
            }
            val maxOffset = (history.size - 3).coerceAtLeast(0)
            val offset = (markHistoryOffsets[group.id] ?: 0).coerceIn(0, maxOffset)
            markHistoryOffsets[group.id] = offset
            val end = (history.size - offset).coerceAtLeast(0)
            val start = (end - 3).coerceAtLeast(0)
            val visibleHistory = history.subList(start, end)
            visibleHistory.forEachIndexed { markIndex, mark ->
                markPaint.color = mark.color.toArgb()
                markPaint.alpha = readerTokens.markAlpha
                val left = anchor.x + markIndex * (cell + gap)
                val cellBounds = RectF(left, anchor.y, left + cell, anchor.y + cell)
                canvas.drawRoundRect(
                    cellBounds,
                    dp(readerTokens.markCornerDp),
                    dp(readerTokens.markCornerDp),
                    markPaint,
                )
                markCellHits += MarkCellHit(group.id, mark.attemptNo, cellBounds, anchor.x, anchor.y)
            }
            val historyWidth = visibleHistory.size * cell + (visibleHistory.size - 1).coerceAtLeast(0) * gap
            val groupBounds = RectF(
                anchor.x - dp(readerTokens.markHitPaddingDp),
                anchor.y - dp(readerTokens.markHitPaddingDp),
                anchor.x + historyWidth + dp(readerTokens.markHitPaddingDp),
                anchor.y + cell + dp(readerTokens.markHitPaddingDp),
            )
            markHistoryBounds[group.id] = groupBounds
            markHistoryAnchors[group.id] = android.graphics.PointF(anchor.x, anchor.y)
            markHistoryCounts[group.id] = history.size
            if (pressedMarkGroupId == group.id) {
                canvas.drawRoundRect(groupBounds, dp(6f), dp(6f), markFocusPaint)
            }
            val current = history.lastOrNull { it.attemptNo == visibleAttemptNo }?.color ?: MarkColor.GRAY
            markPaint.color = current.toArgb()
            markPaint.alpha = readerTokens.markAlpha
            val barTop = dp(readerTokens.markBarTopDp) + groupIndex * (cell + gap)
            val barRight = width - dp(readerTokens.markBarRightInsetDp)
            canvas.drawRoundRect(
                barRight - dp(readerTokens.markBarWidthDp),
                barTop,
                barRight,
                barTop + cell,
                dp(readerTokens.markCornerDp),
                dp(readerTokens.markCornerDp),
                markPaint,
            )
        }
    }

    fun markGroupAt(viewX: Float, viewY: Float): String? = markHistoryBounds.entries
        .asSequence()
        .filter { (_, bounds) -> bounds.contains(viewX, viewY) }
        .minByOrNull { (groupId, _) ->
            markHistoryAnchors[groupId]?.let { hypot(it.x - viewX, it.y - viewY) } ?: Float.MAX_VALUE
        }
        ?.key

    fun markedAttemptAt(viewX: Float, viewY: Float): Int? = markCellHits.asSequence()
        .filter { it.bounds.contains(viewX, viewY) }
        .minByOrNull { hypot(it.anchorX - viewX, it.anchorY - viewY) }
        ?.attemptNo

    fun scrollableMarkGroupAt(viewX: Float, viewY: Float): String? = markGroupAt(viewX, viewY)
        ?.takeIf { (markHistoryCounts[it] ?: 0) > 3 }

    fun dragMarkHistory(groupId: String, deltaX: Float) {
        val group = markGroups.firstOrNull { it.id == groupId } ?: return
        val visibleMarkCount = group.marks.count { it.hiddenAtEpochMillis == null } +
            if (group.marks.none { it.hiddenAtEpochMillis == null && it.attemptNo == visibleAttemptNo }) 1 else 0
        val maxOffset = (visibleMarkCount - 3).coerceAtLeast(0)
        if (maxOffset == 0) return
        var remainder = (markHistoryDragRemainders[groupId] ?: 0f) + deltaX
        val threshold = dp(12f)
        var offset = (markHistoryOffsets[groupId] ?: 0).coerceIn(0, maxOffset)
        while (remainder >= threshold) {
            offset = (offset + 1).coerceAtMost(maxOffset)
            remainder -= threshold
        }
        while (remainder <= -threshold) {
            offset = (offset - 1).coerceAtLeast(0)
            remainder += threshold
        }
        markHistoryOffsets[groupId] = offset
        markHistoryDragRemainders[groupId] = remainder
        invalidate()
    }

    fun endMarkHistoryDrag(groupId: String) {
        markHistoryDragRemainders.remove(groupId)
    }

    private fun drawHover(canvas: Canvas) {
        val hover = hoverPreview ?: return
        paint.style = Paint.Style.STROKE
        paint.color = if (hover.eraser) Color.rgb(39, 110, 255) else hover.colorArgb
        paint.alpha = 220
        paint.strokeWidth = dp(1.5f)
        val radius = if (hover.eraser) hover.widthPixels / 2f else max(dp(4f), hover.widthPixels / 2f + dp(3f))
        canvas.drawCircle(hover.x, hover.y, radius, paint)
    }

    private fun MarkColor.toArgb(): Int = when (this) {
        MarkColor.BLUE -> readerTokens.markBlueArgb
        MarkColor.RED -> readerTokens.markRedArgb
        MarkColor.GRAY -> readerTokens.markGrayArgb
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private data class MarkCellHit(
        val groupId: String,
        val attemptNo: Int,
        val bounds: RectF,
        val anchorX: Float,
        val anchorY: Float,
    )
}
