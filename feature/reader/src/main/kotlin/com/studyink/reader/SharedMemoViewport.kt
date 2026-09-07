package com.studyink.reader

import android.graphics.PointF
import android.graphics.RectF
import com.studyink.core.model.PagePoint
import com.studyink.document.pdf.CanonicalPdfPoint
import com.studyink.document.pdf.InkViewport
import com.studyink.memo.core.MEMO_MAX_COORDINATE
import com.studyink.memo.core.MEMO_MIN_COORDINATE
import kotlin.math.max
import kotlin.math.min

/**
 * One camera for mathematical construction and the continuous memo working plane.
 *
 * This mapping is a document invariant, not a device-dependent fit to the current geometry:
 * canonical (0, 0) is (-3, 24) cm, and canonical (1000, 2200) is (27, -42) cm.
 * Camera changes never rewrite either construction coordinates or saved handwriting.
 */
internal class SharedMemoViewport : InkViewport {
    private var viewWidth = 0
    private var viewHeight = 0
    private var largestViewportWidth = 0
    private var offsetX = 0f
    private var offsetY = 0f
    var pixelsPerCm: Float = 1f
        private set
    var onChanged: () -> Unit = {}

    private var geometryBounds: RectF? = null
    private var inkBounds: RectF? = null
    /** Math bounds use top=minY, bottom=maxY. Updating content never moves the camera. */
    var geometryWorldBounds: RectF?
        get() = geometryBounds?.let(::RectF)
        set(value) { geometryBounds = safeWorldBounds(value) }
    var inkWorldBounds: RectF?
        get() = inkBounds?.let(::RectF)
        set(value) { inkBounds = safeWorldBounds(value) }

    /** The original sheet remains a reference rectangle, never the writable clipping boundary. */
    val paperBounds: RectF
        get() = RectF(offsetX, offsetY, offsetX + WIDTH.toFloat() * pixelsPerCm,
            offsetY + HEIGHT.toFloat() * pixelsPerCm)

    fun worldToView(x: Double, y: Double): PointF = PointF(
        offsetX + ((x - LEFT) * pixelsPerCm).toFloat(),
        offsetY + ((TOP - y) * pixelsPerCm).toFloat(),
    )

    fun viewToWorld(x: Float, y: Float): PointF = PointF(
        LEFT.toFloat() + (x - offsetX) / pixelsPerCm,
        TOP.toFloat() - (y - offsetY) / pixelsPerCm,
    )

    /** Rotation changes the visible area, never the physical size of a drawn centimeter. */
    fun updateSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == viewWidth && h == viewHeight)) return
        val wasSized = viewWidth > 0 && viewHeight > 0
        val oldCenter = if (wasSized) viewToWorld(viewWidth / 2f, viewHeight / 2f) else null
        val previousScale = pixelsPerCm
        viewWidth = w
        viewHeight = h
        largestViewportWidth = max(largestViewportWidth, w)
        pixelsPerCm = (if (wasSized) previousScale else widthScale()).coerceIn(minScale(), maxScale())
        if (oldCenter == null) {
            offsetX = 0f
            offsetY = 0f
        } else {
            offsetX = w / 2f - (oldCenter.x - LEFT.toFloat()) * pixelsPerCm
            offsetY = h / 2f - (TOP.toFloat() - oldCenter.y) * pixelsPerCm
        }
        constrainPan()
        onChanged()
    }

    /** The familiar full-width, top-of-sheet view. Never fits or moves mathematical objects. */
    fun reset() {
        if (!isSized()) return
        pixelsPerCm = widthScale()
        offsetX = 0f
        offsetY = 0f
        constrainPan()
        onChanged()
    }

    /** Fit actual ink and geometry together without changing either layer's saved coordinates. */
    fun fitContent() {
        if (!isSized()) return
        val content = combinedContentBounds() ?: return reset()
        // A point or a horizontal segment still needs a usable, nonzero framing box.
        val contentWidth = max(content.width(), 1f)
        val contentHeight = max(content.height(), 1f)
        val availableWidth = max(viewWidth - FIT_PADDING_PX * 2f, 1f)
        val availableHeight = max(viewHeight - FIT_PADDING_PX * 2f, 1f)
        pixelsPerCm = min(availableWidth / contentWidth, availableHeight / contentHeight)
            .coerceIn(minScale(), maxScale())
        offsetX = viewWidth / 2f - (content.centerX() - LEFT.toFloat()) * pixelsPerCm
        offsetY = viewHeight / 2f - (TOP.toFloat() - content.centerY()) * pixelsPerCm
        constrainPan()
        onChanged()
    }

    fun zoom(factor: Float, focusX: Float, focusY: Float) =
        transform(factor, focusX, focusY, focusX, focusY)

    fun pan(dx: Float, dy: Float) = transform(1f, 0f, 0f, dx, dy)

    /** Apply a pinch and its centroid movement atomically, with a single redraw notification. */
    internal fun transform(factor: Float, fromX: Float, fromY: Float, toX: Float, toY: Float) {
        if (!isSized() || !factor.isFinite() || factor <= 0f ||
            !fromX.isFinite() || !fromY.isFinite() || !toX.isFinite() || !toY.isFinite()) return
        val nextScale = (pixelsPerCm * factor).coerceIn(minScale(), maxScale())
        val ratio = nextScale / pixelsPerCm
        offsetX = toX - (fromX - offsetX) * ratio
        offsetY = toY - (fromY - offsetY) * ratio
        pixelsPerCm = nextScale
        constrainPan()
        onChanged()
    }

    override fun viewToCanonical(x: Float, y: Float): CanonicalPdfPoint? {
        if (!isSized() || !x.isFinite() || !y.isFinite() ||
            x < 0f || y < 0f || x > viewWidth || y > viewHeight) return null
        val px = (x - offsetX) / (pixelsPerCm * CM_PER_CANONICAL)
        val py = (y - offsetY) / (pixelsPerCm * CM_PER_CANONICAL)
        // The old canonical origin/unit never change. Negative and beyond-sheet positions are
        // stored as memo-format v2 instead of being clamped back to the legacy paper edge.
        val minX = MEMO_MIN_COORDINATE * CANONICAL_WIDTH
        val maxX = MEMO_MAX_COORDINATE * CANONICAL_WIDTH
        val minY = MEMO_MIN_COORDINATE * CANONICAL_HEIGHT
        val maxY = MEMO_MAX_COORDINATE * CANONICAL_HEIGHT
        if (!px.isFinite() || !py.isFinite() || px < minX - EDGE_EPSILON || px > maxX + EDGE_EPSILON ||
            py < minY - EDGE_EPSILON || py > maxY + EDGE_EPSILON) return null
        return CanonicalPdfPoint(PAGE, PagePoint(px.coerceIn(minX, maxX), py.coerceIn(minY, maxY)))
    }

    override fun canonicalToView(pageNumber: Int, point: PagePoint): PointF? =
        if (pageNumber != PAGE || !isSized()) null else PointF(
            offsetX + point.x * CM_PER_CANONICAL * pixelsPerCm,
            offsetY + point.y * CM_PER_CANONICAL * pixelsPerCm,
        )

    override fun canonicalWidthToView(pageNumber: Int, width: Float): Float =
        if (pageNumber == PAGE && isSized()) width * CM_PER_CANONICAL * pixelsPerCm else width

    override fun viewWidthToCanonical(pageNumber: Int, widthPixels: Float): Float =
        if (pageNumber == PAGE && isSized()) widthPixels / (CM_PER_CANONICAL * pixelsPerCm) else widthPixels

    override fun activePage(): Int = PAGE
    override fun activePageBounds(): RectF? =
        if (isSized()) RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat()) else null

    private fun isSized() = viewWidth > 0 && viewHeight > 0
    private fun widthScale() = viewWidth / WIDTH.toFloat()
    private fun minScale(): Float {
        val paperFit = min(widthScale(), viewHeight / HEIGHT.toFloat())
        val content = combinedContentBounds()
        val contentFit = if (content == null) paperFit else
            min(max(viewWidth - FIT_PADDING_PX * 2f, 1f) / max(content.width(), 1f),
                max(viewHeight - FIT_PADDING_PX * 2f, 1f) / max(content.height(), 1f))
        val coordinateSpan = MEMO_MAX_COORDINATE - MEMO_MIN_COORDINATE
        val safeFloor = max(viewWidth / (coordinateSpan * WIDTH.toFloat()),
            viewHeight / (coordinateSpan * HEIGHT.toFloat()))
        return max(safeFloor, min(paperFit / 16f, contentFit))
    }
    // Narrow or intermediate rotation layouts must not lower the zoom ceiling and clamp a
    // camera the user already chose. This also keeps the first pan after rotation from zooming.
    private fun maxScale() = largestViewportWidth / WIDTH.toFloat() * 8f

    private fun constrainPan() {
        // Keep the whole visible viewport writable, including near the generous numeric safety
        // boundary. Pan is no longer tied to existing content or the old paper rectangle.
        val left = MEMO_MIN_COORDINATE * WIDTH.toFloat() * pixelsPerCm
        val right = MEMO_MAX_COORDINATE * WIDTH.toFloat() * pixelsPerCm
        val top = MEMO_MIN_COORDINATE * HEIGHT.toFloat() * pixelsPerCm
        val bottom = MEMO_MAX_COORDINATE * HEIGHT.toFloat() * pixelsPerCm
        offsetX = offsetX.coerceIn(viewWidth - right, -left)
        offsetY = offsetY.coerceIn(viewHeight - bottom, -top)
    }

    private fun combinedContentBounds(): RectF? {
        val geometry = geometryBounds
        val ink = inkBounds
        return when {
            geometry == null -> ink?.let(::RectF)
            ink == null -> RectF(geometry)
            else -> RectF(min(geometry.left, ink.left), min(geometry.top, ink.top),
                max(geometry.right, ink.right), max(geometry.bottom, ink.bottom))
        }
    }

    private fun safeWorldBounds(bounds: RectF?): RectF? {
        if (bounds == null || !bounds.left.isFinite() || !bounds.top.isFinite() ||
            !bounds.right.isFinite() || !bounds.bottom.isFinite()) return null
        return RectF(min(bounds.left, bounds.right), min(bounds.top, bounds.bottom),
            max(bounds.left, bounds.right), max(bounds.top, bounds.bottom))
    }

    companion object {
        const val LEFT = -3.0
        const val TOP = 24.0
        const val WIDTH = 30.0
        const val HEIGHT = 66.0
        const val CANONICAL_WIDTH = 1000f
        const val CANONICAL_HEIGHT = 2200f
        private const val CM_PER_CANONICAL = .03f
        private const val EDGE_EPSILON = 1f
        private const val FIT_PADDING_PX = 24f
        private const val PAGE = 0
    }
}
