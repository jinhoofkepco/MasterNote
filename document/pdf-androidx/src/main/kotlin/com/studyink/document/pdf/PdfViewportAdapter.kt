package com.studyink.document.pdf

import android.graphics.PointF
import android.graphics.RectF
import com.studyink.core.model.CANONICAL_PAGE_WIDTH
import com.studyink.core.model.PagePoint

data class CanonicalPdfPoint(val pageNumber: Int, val point: PagePoint)

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

    fun showPage(pageNumber: Int) {
        pdfView?.showPage(pageNumber)
        onViewportChanged()
    }

    fun resetZoom() {
        pdfView?.resetZoom()
    }
}
