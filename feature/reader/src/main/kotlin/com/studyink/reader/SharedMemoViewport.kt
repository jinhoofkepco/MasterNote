package com.studyink.reader

import android.graphics.PointF
import android.graphics.RectF
import com.studyink.core.model.PagePoint
import com.studyink.document.pdf.CanonicalPdfPoint
import com.studyink.document.pdf.InkViewport
import kotlin.math.max
import kotlin.math.min

/**
 * One camera for mathematical construction and the existing normalized memo sheet.
 *
 * This mapping is a document invariant, not a device-dependent fit to the current geometry:
 * canonical (0, 0) is (-3, 24) cm, and canonical (1000, 2200) is (27, -42) cm.
 * Camera changes never rewrite either construction coordinates or saved handwriting.
 */
internal class SharedMemoViewport : InkViewport {
    private var viewWidth = 0
    private var viewHeight = 0
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

    /** Keep the same center on the sheet and the same width-relative magnification on resize. */
    fun updateSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == viewWidth && h == viewHeight)) return
        val wasSized = viewWidth > 0 && viewHeight > 0
        val oldCenter = if (wasSized) viewToWorld(viewWidth / 2f, viewHeight / 2f) else null
        val zoomRatio = if (wasSized) pixelsPerCm / widthScale() else 1f
        viewWidth = w
        viewHeight = h
        pixelsPerCm = (widthScale() * zoomRatio).coerceIn(minScale(), maxScale())
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
        // A fitted point at the very edge may intentionally show a small surround margin.
        constrainPan(FIT_PADDING_PX / pixelsPerCm)
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
        // Tiny float roundoff at a synthetic boundary UP must not discard the stroke endpoint.
        if (px < -EDGE_EPSILON || px > CANONICAL_WIDTH + EDGE_EPSILON ||
            py < -EDGE_EPSILON || py > CANONICAL_HEIGHT + EDGE_EPSILON) return null
        return CanonicalPdfPoint(PAGE, PagePoint(px.coerceIn(0f, CANONICAL_WIDTH),
            py.coerceIn(0f, CANONICAL_HEIGHT)))
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
    override fun activePageBounds(): RectF? = if (isSized()) paperBounds else null

    private fun isSized() = viewWidth > 0 && viewHeight > 0
    private fun widthScale() = viewWidth / WIDTH.toFloat()
    private fun minScale(): Float {
        val paperFit = min(widthScale(), viewHeight / HEIGHT.toFloat())
        if (geometryBounds == null && inkBounds == null) return paperFit
        val navigable = navigableWorldBounds()
        return min(paperFit,
            min(max(viewWidth - FIT_PADDING_PX * 2f, 1f) / navigable.width(),
                max(viewHeight - FIT_PADDING_PX * 2f, 1f) / navigable.height()))
    }
    private fun maxScale() = widthScale() * 8f

    private fun constrainPan(extraMarginCm: Float = 0f) {
        val bounds = navigableWorldBounds()
        if (extraMarginCm > 0f) bounds.inset(-extraMarginCm, -extraMarginCm)
        val left = (bounds.left - LEFT.toFloat()) * pixelsPerCm
        val right = (bounds.right - LEFT.toFloat()) * pixelsPerCm
        val top = (TOP.toFloat() - bounds.bottom) * pixelsPerCm
        val bottom = (TOP.toFloat() - bounds.top) * pixelsPerCm
        offsetX = if (right - left <= viewWidth) (viewWidth - left - right) / 2f
            else offsetX.coerceIn(viewWidth - right, -left)
        // Short paper stays top-aligned. Legacy geometry can extend the navigable world beyond it.
        offsetY = if (bottom - top <= viewHeight) -top
            else offsetY.coerceIn(viewHeight - bottom, -top)
    }

    private fun navigableWorldBounds(): RectF = RectF(LEFT.toFloat(), (TOP - HEIGHT).toFloat(),
        (LEFT + WIDTH).toFloat(), TOP.toFloat()).apply {
        geometryBounds?.let {
            left = min(left, it.left - LEGACY_MARGIN_CM)
            top = min(top, it.top - LEGACY_MARGIN_CM)
            right = max(right, it.right + LEGACY_MARGIN_CM)
            bottom = max(bottom, it.bottom + LEGACY_MARGIN_CM)
        }
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
        private const val EDGE_EPSILON = .001f
        private const val FIT_PADDING_PX = 24f
        private const val LEGACY_MARGIN_CM = 1f
        private const val PAGE = 0
    }
}
