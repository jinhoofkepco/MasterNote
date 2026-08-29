package com.studyink.document.pdf

import android.net.Uri
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.PdfDocument
import androidx.pdf.view.PdfView
import androidx.pdf.viewer.fragment.PdfViewerFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalPdfApi::class)
class ReaderPdfFragment : PdfViewerFragment() {
    interface Listener {
        fun onPdfViewReady(view: PdfView)
        fun onDocumentReady(uri: Uri, pageWidths: Map<Int, Float>, pageCount: Int)
        fun onDocumentError(error: Throwable)
    }

    private var readyPdfView: PdfView? = null

    /**
     * Reader pages need every page width for ink transforms. Lightweight PDF-only screens can
     * disable that eager request and rely on PdfView's progressive page metadata loading.
     */
    var loadAllPageWidths: Boolean = true

    var listener: Listener? = null
        set(value) {
            field = value
            readyPdfView?.let { view -> value?.onPdfViewReady(view) }
        }

    override fun onPdfViewCreated(pdfView: PdfView) {
        super.onPdfViewCreated(pdfView)
        isToolboxVisible = false
        readyPdfView = pdfView
        listener?.onPdfViewReady(pdfView)
    }

    override fun onRequestImmersiveMode(enterImmersive: Boolean) {
        super.onRequestImmersiveMode(enterImmersive)
        // MasterNote supplies its own S Pen tools. The AndroidX PDF toolbox would otherwise
        // reappear as a floating pencil button whenever a zoom gesture ends.
        isToolboxVisible = false
    }

    override fun onLoadDocumentSuccess(document: PdfDocument) {
        super.onLoadDocumentSuccess(document)
        viewLifecycleOwner.lifecycleScope.launch {
            val pageWidths = if (loadAllPageWidths) {
                document.getPageInfos(0 until document.pageCount)
                    .associate { it.pageNum to it.width.toFloat() }
            } else {
                emptyMap()
            }
            listener?.onDocumentReady(document.uri, pageWidths, document.pageCount)
        }
    }

    override fun onLoadDocumentError(error: Throwable) {
        super.onLoadDocumentError(error)
        listener?.onDocumentError(error)
    }

    override fun onDestroyView() {
        readyPdfView = null
        super.onDestroyView()
    }
}
