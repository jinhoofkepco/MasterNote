package com.studyink.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.Mark
import com.studyink.core.model.MarkColor
import com.studyink.core.model.MarkGroup
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeTool
import com.studyink.core.model.TEACHER_PAGE_REVIEW_ATTEMPT_NO
import com.studyink.core.model.resultBundleGrid
import com.studyink.document.pdf.InkViewport
import kotlin.math.hypot
import kotlin.math.max

data class EraserPreview(
    val gestureId: Long,
    val pageNumber: Int,
    val path: List<PagePoint>,
    val radius: Float,
)

/**
 * Transient quick-shape ink shown after the AndroidX wet stroke has been detached.
 *
 * It is deliberately the same point-list geometry as [StrokeAsset]. Nothing here is persisted;
 * the owning [InkInputView] keeps it alive only until the single final stroke is durable.
 */
data class QuickShapePreview(
    val pageNumber: Int,
    val path: List<PagePoint>,
    val colorArgb: Int,
    val width: Float,
)

/** Immutable eraser request emitted once, after the S Pen leaves the screen. */
data class EraserGesture(
    val id: Long,
    val page: Int,
    val path: List<PagePoint>,
    val radius: Float,
    val whole: Boolean,
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
    var viewport: InkViewport? = null
        set(value) { field = value; invalidate() }
    var snapshot: AnnotationSnapshot = AnnotationSnapshot.empty("unopened")
        set(value) {
            // The reader republishes its whole state for unrelated changes such as the live
            // connection badge. Rebuilding the per-page stroke cache for those costs O(strokes)
            // on the main thread for nothing.
            if (field === value) return
            field = value
            rebuildPageCache()
            invalidate()
        }
    var eraserPreview: EraserPreview? = null
        set(value) { field = value; invalidate() }
    var quickShapePreview: QuickShapePreview? = null
        set(value) { field = value; invalidate() }
    var hoverPreview: StylusHoverPreview? = null
        set(value) { field = value; invalidate() }
    var activePage: Int = 0
        set(value) { field = value; clearMarkHitCache(); invalidate() }
    var visibleAttemptNo: Int = 1
        set(value) { field = value; clearMarkHitCache(); invalidate() }
    var showTeacherDrafts: Boolean = false
        set(value) { field = value; rebuildPageCache(); invalidate() }
    var markGroups: List<MarkGroup> = emptyList()
        set(value) { field = value; clearMarkHitCache(); invalidate() }
    var pressedMarkGroupId: String? = null
        set(value) { field = value; invalidate() }

    private var cachedPageStrokes: Map<Int, List<StrokeAsset>> = emptyMap()
    private val pageIsolationPaper = ReaderPaperBackdropDrawable(resources.displayMetrics.density)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    /**
     * Halo drawn under a teacher correction the student has actually received. The trace itself
     * still uses the pen the teacher wrote with, so writing feels identical on the teacher device
     * and only publishing adds this marker.
     */
    private val publishedGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
    private val markCellHits = mutableListOf<MarkCellHit>()

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val adapter = viewport ?: return
        drawPageIsolationMask(canvas, adapter.activePageBounds())
        val visibleBounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val active = visibleReaderStrokes(
            strokes = cachedPageStrokes[activePage].orEmpty(),
            visibleAttemptNo = visibleAttemptNo,
        ).filter { stroke -> isOnScreen(adapter, stroke, visibleBounds) }
        active.forEach { drawStroke(canvas, adapter, it, false) }

        quickShapePreview?.takeIf { it.pageNumber == activePage }?.let { preview ->
            drawQuickShapePreview(canvas, adapter, preview)
        }
        eraserPreview?.let { preview ->
            drawEraserPath(canvas, adapter, preview)
        }
        drawMarks(canvas, adapter)
        drawHover(canvas)
    }

    /**
     * AndroidX PdfView's SINGLE_PAGE constant means one page per row, not one page per viewport.
     * Keep its sharp tiled renderer and zoom support, but cover every PDF pixel outside the page
     * selected by MasterNote. Drawing the same paper layer used behind PdfView preserves the
     * textured margins instead of exposing the vertically adjacent PDF pages.
     */
    private fun drawPageIsolationMask(canvas: Canvas, page: RectF?) {
        pageIsolationPaper.bounds = android.graphics.Rect(0, 0, width, height)
        val saveCount = canvas.save()
        if (page != null) {
            canvas.clipOutRect(page)
        }
        pageIsolationPaper.draw(canvas)
        canvas.restoreToCount(saveCount)
    }

    private fun rebuildPageCache() {
        cachedPageStrokes = snapshot.activeStrokes.asSequence()
            .filter { stroke ->
                stroke.authorId != "teacher" || showTeacherDrafts || stroke.publishedAtEpochMillis != null
            }
            .groupBy(StrokeAsset::pageNumber)
    }

    private fun isOnScreen(adapter: InkViewport, stroke: StrokeAsset, viewportBounds: RectF): Boolean {
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

    private fun drawStroke(canvas: Canvas, adapter: InkViewport, stroke: StrokeAsset, preview: Boolean) {
        if (stroke.points.isEmpty()) return
        val published = !preview &&
            stroke.authorId == "teacher" &&
            stroke.publishedAtEpochMillis != null
        paint.color = if (preview) Color.rgb(39, 110, 255) else stroke.colorArgb
        paint.alpha = when {
            preview -> 190
            stroke.tool == StrokeTool.HIGHLIGHTER -> 95
            else -> Color.alpha(stroke.colorArgb)
        }
        paint.strokeWidth = max(1f, adapter.canonicalWidthToView(stroke.pageNumber, stroke.width))
        if (published) {
            publishedGlowPaint.color = stroke.colorArgb
            publishedGlowPaint.alpha = PUBLISHED_INK_GLOW_ALPHA
            publishedGlowPaint.strokeWidth = paint.strokeWidth * PUBLISHED_INK_GLOW_WIDTH_SCALE
        }
        val first = adapter.canonicalToView(stroke.pageNumber, stroke.points.first()) ?: return
        if (stroke.points.size == 1) {
            if (published) {
                canvas.drawCircle(first.x, first.y, publishedGlowPaint.strokeWidth / 2f, publishedGlowPaint)
            }
            canvas.drawCircle(first.x, first.y, paint.strokeWidth / 2f, paint)
            return
        }
        val path = Path().apply { moveTo(first.x, first.y) }
        stroke.points.drop(1).forEach { point ->
            adapter.canonicalToView(stroke.pageNumber, point)?.let { path.lineTo(it.x, it.y) }
        }
        if (published) canvas.drawPath(path, publishedGlowPaint)
        canvas.drawPath(path, paint)
    }

    private fun drawEraserPath(canvas: Canvas, adapter: InkViewport, preview: EraserPreview) {
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

    private fun drawQuickShapePreview(
        canvas: Canvas,
        adapter: InkViewport,
        preview: QuickShapePreview,
    ) {
        if (preview.path.isEmpty()) return
        paint.color = preview.colorArgb
        paint.alpha = Color.alpha(preview.colorArgb)
        paint.strokeWidth = max(1f, adapter.canonicalWidthToView(preview.pageNumber, preview.width))
        val first = adapter.canonicalToView(preview.pageNumber, preview.path.first()) ?: return
        if (preview.path.size == 1) {
            canvas.drawCircle(first.x, first.y, paint.strokeWidth / 2f, paint)
            return
        }
        val path = Path().apply { moveTo(first.x, first.y) }
        preview.path.drop(1).forEach { point ->
            adapter.canonicalToView(preview.pageNumber, point)?.let { path.lineTo(it.x, it.y) }
        }
        canvas.drawPath(path, paint)
    }

    private fun drawMarks(canvas: Canvas, adapter: InkViewport) {
        val groups = markGroups.asSequence()
            .filter { it.pageNumber == activePage && it.hiddenAtEpochMillis == null }
            .mapNotNull { group ->
                marksForVisibleTarget(group).takeIf(List<*>::isNotEmpty)?.let { group to it }
            }
            .sortedWith(
                compareBy<Pair<MarkGroup, List<Mark>>>(
                    { (group, _) -> group.anchor.y },
                    { (group, _) -> group.anchor.x },
                    { (group, _) -> group.id },
                )
            )
            .toList()
        val historyWidth = dp(readerTokens.markHistoryCellWidthDp)
        val historyHeight = dp(readerTokens.markHistoryCellHeightDp)
        val slotWidth = dp(readerTokens.markCurrentCellWidthDp)
        val slotHeight = dp(readerTokens.markCurrentCellHeightDp)
        val horizontalGap = dp(readerTokens.markHorizontalGapDp)
        val verticalGap = dp(readerTokens.markVerticalGapDp)
        val activePageBounds = adapter.activePageBounds()
            ?: RectF(0f, 0f, width.toFloat(), height.toFloat())
        val occupiedBundles = mutableListOf<MarkBundleBox>()
        markHistoryBounds.clear()
        markHistoryAnchors.clear()
        markCellHits.clear()
        groups.forEach { (group, targetMarks) ->
            val anchor = adapter.canonicalToView(activePage, group.anchor) ?: return@forEach
            val latestByAttempt = latestMarksByAttempt(targetMarks)
            val current = latestByAttempt.firstOrNull { it.attemptNo == visibleAttemptNo }
                ?: Mark(visibleAttemptNo, MarkColor.GRAY)
            val displayMarks = latestByAttempt.filter { it.attemptNo < visibleAttemptNo } + current
            val grid = resultBundleGrid(displayMarks.size)
            val bundleWidth = grid.columns * slotWidth + (grid.columns - 1) * horizontalGap
            val bundleHeight = grid.rows * slotHeight + (grid.rows - 1) * verticalGap
            val edgePadding = dp(readerTokens.markPageEdgePaddingDp)
            val aligned = alignedMarkBundleOrigin(
                anchorX = anchor.x,
                anchorY = anchor.y,
                pageLeft = activePageBounds.left,
                pageTop = activePageBounds.top,
                pageRight = activePageBounds.right,
                pageBottom = activePageBounds.bottom,
                bundleWidth = bundleWidth,
                bundleHeight = bundleHeight,
                horizontalSnap = dp(readerTokens.markHorizontalSnapDp),
                verticalSnap = dp(readerTokens.markVerticalSnapDp),
                edgePadding = edgePadding,
            )
            val origin = nonOverlappingMarkBundleOrigin(
                candidate = aligned,
                bundleWidth = bundleWidth,
                bundleHeight = bundleHeight,
                minY = activePageBounds.top + edgePadding,
                maxY = activePageBounds.bottom - edgePadding - bundleHeight,
                verticalStep = dp(readerTokens.markVerticalSnapDp),
                separation = verticalGap,
                occupied = occupiedBundles,
            )
            val occupied = MarkBundleBox(
                origin.x,
                origin.y,
                origin.x + bundleWidth,
                origin.y + bundleHeight,
            )
            occupiedBundles += occupied

            displayMarks.zip(grid.cells).forEach { (mark, gridCell) ->
                val isCurrent = mark.attemptNo == visibleAttemptNo
                val slotLeft = origin.x + gridCell.column * (slotWidth + horizontalGap)
                val slotTop = origin.y + gridCell.row * (slotHeight + verticalGap)
                val visualWidth = if (isCurrent) slotWidth else historyWidth
                val visualHeight = if (isCurrent) slotHeight else historyHeight
                val left = slotLeft + (slotWidth - visualWidth) / 2f
                // Small historical cells sit on the same baseline as the active result rather
                // than floating at different heights within a row.
                val top = slotTop + slotHeight - visualHeight
                val visualBounds = RectF(left, top, left + visualWidth, top + visualHeight)
                markPaint.color = mark.color.toArgb()
                markPaint.alpha = when {
                    isCurrent && mark.color == MarkColor.GRAY -> readerTokens.markPlaceholderAlpha
                    isCurrent -> readerTokens.markAlpha
                    else -> readerTokens.markHistoryAlpha
                }
                canvas.drawRoundRect(
                    visualBounds,
                    dp(if (isCurrent) readerTokens.markCornerDp else readerTokens.markHistoryCornerDp),
                    dp(if (isCurrent) readerTokens.markCornerDp else readerTokens.markHistoryCornerDp),
                    markPaint,
                )
                // The faded historical result remains an easy S Pen target even though its visible
                // rectangle is deliberately narrow.
                val hitBounds = RectF(slotLeft, slotTop, slotLeft + slotWidth, slotTop + slotHeight)
                markCellHits += MarkCellHit(group.id, mark.attemptNo, hitBounds, origin.x, origin.y)
            }
            val groupBounds = RectF(
                origin.x - dp(readerTokens.markHitPaddingDp),
                origin.y - dp(readerTokens.markHitPaddingDp),
                origin.x + bundleWidth + dp(readerTokens.markHitPaddingDp),
                origin.y + bundleHeight + dp(readerTokens.markHitPaddingDp),
            )
            markHistoryBounds[group.id] = groupBounds
            markHistoryAnchors[group.id] = android.graphics.PointF(origin.x, origin.y)
            if (pressedMarkGroupId == group.id) {
                canvas.drawRoundRect(groupBounds, dp(6f), dp(6f), markFocusPaint)
            }
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

    // Every attempt is now visible in the compact grid; older attempts are opened by tapping
    // their own S Pen hit cell. Horizontal history paging belongs to the top page summary.
    fun scrollableMarkGroupAt(viewX: Float, viewY: Float): String? = null

    fun dragMarkHistory(groupId: String, deltaX: Float) {
        // Kept as a compatibility callback for InkInputView. Per-question bundles no longer hide
        // old attempts behind a three-item viewport, so there is nothing to page here.
    }

    fun endMarkHistoryDrag(groupId: String) {
        // See dragMarkHistory().
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

    private fun clearMarkHitCache() {
        markHistoryBounds.clear()
        markHistoryAnchors.clear()
        markCellHits.clear()
    }

    private fun latestMarksByAttempt(marks: List<Mark>): List<Mark> {
        val latest = linkedMapOf<Int, Mark>()
        marks.forEach { mark ->
            val previous = latest[mark.attemptNo]
            if (previous == null || mark.gradedAtEpochMillis >= previous.gradedAtEpochMillis) {
                latest[mark.attemptNo] = mark
            }
        }
        return latest.values.sortedBy(Mark::attemptNo)
    }

    /** Page-level teacher marks and student-attempt history must never leak into each other. */
    private fun marksForVisibleTarget(group: MarkGroup) = group.marks.filter { mark ->
        mark.hiddenAtEpochMillis == null && if (visibleAttemptNo == TEACHER_PAGE_REVIEW_ATTEMPT_NO) {
            mark.attemptNo == TEACHER_PAGE_REVIEW_ATTEMPT_NO
        } else {
            mark.attemptNo != TEACHER_PAGE_REVIEW_ATTEMPT_NO
        }
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

/** Keeps every ink source, including Telegram teacher feedback, on its exact student attempt. */
internal fun visibleReaderStrokes(
    strokes: List<StrokeAsset>,
    visibleAttemptNo: Int,
): List<StrokeAsset> = strokes.filter { stroke -> stroke.attemptNo == visibleAttemptNo }
