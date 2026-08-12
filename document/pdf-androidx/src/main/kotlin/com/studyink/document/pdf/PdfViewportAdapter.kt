package com.studyink.document.pdf

import android.graphics.PointF
import android.graphics.RectF
import com.studyink.core.model.CANONICAL_PAGE_WIDTH
import com.studyink.core.model.PagePoint

data class CanonicalPdfPoint(val pageNumber: Int, val point: PagePoint)
data class PdfViewportState(
    val pageNumber: Int,
    val normalizedCenterX: Float,
    val normalizedCenterY: Float,
    val zoomScale: Float,
    val viewportWidthRatio: Float,
    val viewportHeightRatio: Float,
)

class PdfViewportAdapter {
    private var pdfView: SinglePagePdfView? = null
    private var pageWidths: Map<Int, Float> = emptyMap()

    var onViewportChanged: () -> Unit = {}

    fun attach(view: SinglePagePdfView) {
        pdfView?.onViewportChanged = null
        pdfView = view
        pageWidths = emptyMap()
        view.onViewportChanged = onViewportChanged
    }

    fun setPageWidths(widths: Map<Int, Float>) {
        pageWidths = widths
    }

    fun viewToCanonical(x: Float, y: Float): CanonicalPdfPoint? {
        val view = pdfView ?: return null
        val pdfPoint = view.viewToPdfPoint(x, y) ?: return null
        val pageNumber = view.activePage
        val width = pageWidths[pageNumber] ?: return null
        return CanonicalPdfPoint(
            pageNumber,
            PagePoint(
                pdfPoint.x / width * CANONICAL_PAGE_WIDTH,
                pdfPoint.y / width * CANONICAL_PAGE_WIDTH,
            ),
        )
    }

    fun canonicalToView(pageNumber: Int, point: PagePoint): PointF? {
        val width = pageWidths[pageNumber] ?: return null
        return pdfView?.pdfToViewPoint(
            pageNumber,
            PointF(
                point.x / CANONICAL_PAGE_WIDTH * width,
                point.y / CANONICAL_PAGE_WIDTH * width,
            ),
        )
    }

    fun canonicalWidthToView(pageNumber: Int, width: Float): Float {
        val pageWidth = pageWidths[pageNumber] ?: return width
        val scale = pdfView?.displayScale ?: return width
        return width / CANONICAL_PAGE_WIDTH * pageWidth * scale
    }

    fun viewWidthToCanonical(pageNumber: Int, widthPixels: Float): Float {
        val pageWidth = pageWidths[pageNumber] ?: return widthPixels
        val scale = pdfView?.displayScale ?: return widthPixels
        return widthPixels / scale / pageWidth * CANONICAL_PAGE_WIDTH
    }

    fun activePage(): Int = pdfView?.activePage ?: 0

    fun activePageBounds(): RectF? = pdfView?.pageBounds

    fun viewToNormalized(x: Float, y: Float): PointF? {
        val bounds = pdfView?.pageBounds ?: return null
        if (!bounds.contains(x, y)) return null
        return PointF(((x - bounds.left) / bounds.width()).coerceIn(0f, 1f), ((y - bounds.top) / bounds.height()).coerceIn(0f, 1f))
    }

    fun state(): PdfViewportState? {
        val view = pdfView ?: return null
        val bounds = view.pageBounds ?: return null
        if (bounds.width() <= 0f || bounds.height() <= 0f) return null
        return PdfViewportState(
            view.activePage,
            ((view.width / 2f - bounds.left) / bounds.width()).coerceIn(0f, 1f),
            ((view.height / 2f - bounds.top) / bounds.height()).coerceIn(0f, 1f),
            view.displayScale,
            (view.width / bounds.width()).coerceAtMost(1f),
            (view.height / bounds.height()).coerceAtMost(1f),
        )
    }

    fun showPage(pageNumber: Int) {
        pdfView?.showPage(pageNumber)
        onViewportChanged()
    }

    fun resetZoom() {
        pdfView?.resetZoom()
    }

    fun restore(state: PdfViewportState) {
        val view = pdfView ?: return
        view.showPage(state.pageNumber)
        view.post {
            view.restoreViewport(state.normalizedCenterX, state.normalizedCenterY, state.zoomScale)
        }
    }
}
