package com.studyink.document.pdf

import android.annotation.SuppressLint
import android.graphics.PointF
import android.graphics.RectF
import android.util.SparseArray
import android.view.View
import androidx.pdf.PdfPoint
import androidx.pdf.view.PdfView
import com.studyink.core.model.CANONICAL_PAGE_WIDTH
import com.studyink.core.model.PagePoint
import kotlin.math.abs
import kotlin.math.roundToInt

data class CanonicalPdfPoint(val pageNumber: Int, val point: PagePoint)

class PdfViewportAdapter {
    private var pdfView: PdfView? = null
    private var pageWidths: Map<Int, Float> = emptyMap()
    private var pageLocations = SparseArray<RectF>()
    private var activePageNumber = 0
    private var settlePosted = false

    var onViewportChanged: () -> Unit = {}

    private val viewportChangedListener = object : PdfView.OnViewportChangedListener {
        override fun onViewportChanged(
            firstVisiblePage: Int,
            visiblePagesCount: Int,
            pageLocations: SparseArray<RectF>,
            zoomLevel: Float,
        ) {
            this@PdfViewportAdapter.pageLocations = SparseArray<RectF>(pageLocations.size()).also { copy ->
                for (index in 0 until pageLocations.size()) {
                    copy.put(pageLocations.keyAt(index), RectF(pageLocations.valueAt(index)))
                }
            }
            onViewportChanged()
            if (pdfView?.gestureState == PdfView.GESTURE_STATE_IDLE) scheduleActivePageSettle()
        }
    }

    private val gestureStateChangedListener = object : PdfView.OnGestureStateChangedListener {
        override fun onGestureStateChanged(newState: Int) {
            if (newState == PdfView.GESTURE_STATE_IDLE) scheduleActivePageSettle()
        }
    }

    @SuppressLint("RestrictedApi")
    fun attach(view: PdfView) {
        pdfView?.removeOnViewportChangedListener(viewportChangedListener)
        pdfView?.removeOnGestureStateChangedListener(gestureStateChangedListener)
        pdfView = view
        pageWidths = emptyMap()
        pageLocations.clear()
        activePageNumber = 0
        settlePosted = false
        view.pagesPerRow = PdfView.SINGLE_PAGE
        view.verticalAlignment = PdfView.VERTICAL_ALIGNMENT_CENTER
        view.overScrollMode = View.OVER_SCROLL_NEVER
        hideBuiltInPageControls(view)
        view.addOnViewportChangedListener(viewportChangedListener)
        view.addOnGestureStateChangedListener(gestureStateChangedListener)
    }

    fun setPageWidths(widths: Map<Int, Float>) {
        pageWidths = widths
    }

    fun viewToCanonical(x: Float, y: Float): CanonicalPdfPoint? {
        val pdfPoint = pdfView?.viewToPdfPoint(x, y) ?: return null
        val width = pageWidths[pdfPoint.pageNum] ?: return null
        return CanonicalPdfPoint(
            pdfPoint.pageNum,
            PagePoint(pdfPoint.x / width * CANONICAL_PAGE_WIDTH, pdfPoint.y / width * CANONICAL_PAGE_WIDTH),
        )
    }

    fun canonicalToView(pageNumber: Int, point: PagePoint): PointF? {
        val width = pageWidths[pageNumber] ?: return null
        return pdfView?.pdfToViewPoint(
            PdfPoint(pageNumber, point.x / CANONICAL_PAGE_WIDTH * width, point.y / CANONICAL_PAGE_WIDTH * width)
        )
    }

    fun canonicalWidthToView(pageNumber: Int, width: Float): Float {
        val pageWidth = pageWidths[pageNumber] ?: return width
        return width / CANONICAL_PAGE_WIDTH * pageWidth * (pdfView?.zoom ?: 1f)
    }

    fun viewWidthToCanonical(pageNumber: Int, widthPixels: Float): Float {
        val pageWidth = pageWidths[pageNumber] ?: return widthPixels
        return widthPixels / (pdfView?.zoom ?: 1f) / pageWidth * CANONICAL_PAGE_WIDTH
    }

    fun activePage(): Int = activePageNumber

    fun activePageBounds(): RectF? = pageLocations[activePageNumber]?.let(::RectF)

    fun showPage(pageNumber: Int) {
        activePageNumber = pageNumber
        pdfView?.let { view ->
            hideBuiltInPageControls(view)
            view.scrollToPage(pageNumber)
            view.postDelayed({ hideBuiltInPageControls(view) }, 300L)
            view.postDelayed(::scheduleActivePageSettle, 320L)
        }
        onViewportChanged()
    }

    fun resetZoom() {
        pdfView?.let { view ->
            view.zoom = view.minZoom
            view.post {
                view.scrollToPage(activePageNumber)
                scheduleActivePageSettle()
            }
        }
    }

    private fun scheduleActivePageSettle() {
        val view = pdfView ?: return
        if (pageWidths[activePageNumber] == null || settlePosted) return
        settlePosted = true
        view.post {
            settlePosted = false
            if (pdfView !== view) return@post
            settleActivePage(view)
        }
    }

    private fun settleActivePage(view: PdfView) {
        val page = pageLocations[activePageNumber]
        if (page == null) {
            view.scrollToPage(activePageNumber)
            return
        }
        val viewportHeight = view.height.toFloat()
        if (viewportHeight <= 0f) return

        val verticalCorrection = if (page.height() <= viewportHeight) {
            page.top - (viewportHeight - page.height()) / 2f
        } else {
            when {
                page.top > 0f -> page.top
                page.bottom < viewportHeight -> page.bottom - viewportHeight
                else -> 0f
            }
        }
        if (abs(verticalCorrection) >= 1f) {
            view.scrollBy(0, verticalCorrection.roundToInt())
        }
    }

    @SuppressLint("RestrictedApi")
    private fun hideBuiltInPageControls(view: PdfView) {
        // PdfViewerFragment currently enables its internal fast scroller after PdfView creation.
        // Disable that renderer as well as its public visibility state so navigation stays in our menu.
        runCatching {
            view.javaClass.methods
                .firstOrNull { it.name.startsWith("setEnableDefaultFastScrollerRendering") }
                ?.invoke(view, false)
        }
        view.fastScrollVisibility = PdfView.FastScrollVisibility.ALWAYS_HIDE
        view.fastScroller?.hide()
        view.fastScroller = null
        view.updateFastScrollVisibility()
        view.invalidate()
    }
}
