package com.studyink.document.pdf

import android.graphics.PointF
import android.graphics.RectF
import com.studyink.core.model.PagePoint

/**
 * The coordinate surface needed by the shared ink renderer and input view.
 *
 * PDF navigation and zoom remain owned by [PdfViewportAdapter]. A fixed canvas such as an
 * attempt memo can implement this small contract without pretending to be a PDF document.
 */
interface InkViewport {
    fun viewToCanonical(x: Float, y: Float): CanonicalPdfPoint?

    fun canonicalToView(pageNumber: Int, point: PagePoint): PointF?

    fun canonicalWidthToView(pageNumber: Int, width: Float): Float

    fun viewWidthToCanonical(pageNumber: Int, widthPixels: Float): Float

    fun activePage(): Int

    fun activePageBounds(): RectF?
}
