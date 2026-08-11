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

    var listener: Listener? = null

    override fun onPdfViewCreated(pdfView: PdfView) {
        super.onPdfViewCreated(pdfView)
        isToolboxVisible = false
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
            val infos = document.getPageInfos(0 until document.pageCount)
            listener?.onDocumentReady(document.uri, infos.associate { it.pageNum to it.width.toFloat() }, document.pageCount)
        }
    }

    override fun onLoadDocumentError(error: Throwable) {
        super.onLoadDocumentError(error)
        listener?.onDocumentError(error)
    }
}
