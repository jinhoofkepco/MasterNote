package com.studyink.document.pdf

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.BundleCompat
import androidx.pdf.ExperimentalPdfApi
import androidx.pdf.PdfDocument
import androidx.pdf.SandboxedPdfLoader
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalPdfApi::class)
class ReaderPdfFragment : Fragment() {
    interface Listener {
        fun onPdfViewReady(view: SinglePagePdfView)
        fun onDocumentReady(uri: Uri, pageWidths: Map<Int, Float>, pageCount: Int)
        fun onDocumentError(error: Throwable)
    }

    var listener: Listener? = null
        set(value) {
            if (field === value) return
            field = value
            val view = pdfView
            if (value != null && view != null) value.onPdfViewReady(view)
            val loaded = loadedDocument
            if (value != null && loaded != null) {
                value.onDocumentReady(loaded.uri, loaded.pageWidths, loaded.pageCount)
            }
        }

    var documentUri: Uri? = null
        set(value) {
            field = value
            if (value != null && pdfView != null) loadDocument(value)
        }

    private lateinit var loader: SandboxedPdfLoader
    private var pdfView: SinglePagePdfView? = null
    private var loadJob: Job? = null
    private var loadGeneration = 0L
    private var loadedDocument: LoadedDocument? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        loader = SandboxedPdfLoader(context.applicationContext, Dispatchers.IO)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (documentUri == null && savedInstanceState != null) {
            documentUri = BundleCompat.getParcelable(savedInstanceState, KEY_DOCUMENT_URI, Uri::class.java)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        documentUri?.let { outState.putParcelable(KEY_DOCUMENT_URI, it) }
        super.onSaveInstanceState(outState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = SinglePagePdfView(requireContext()).also { view ->
        pdfView = view
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        view.onRenderError = { error -> listener?.onDocumentError(error) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val singlePageView = view as SinglePagePdfView
        listener?.onPdfViewReady(singlePageView)
        documentUri?.let(::loadDocument)
    }

    override fun onDestroyView() {
        loadGeneration++
        loadJob?.cancel()
        loadJob = null
        loadedDocument = null
        pdfView?.release()
        pdfView = null
        super.onDestroyView()
    }

    private fun loadDocument(uri: Uri) {
        val targetView = pdfView ?: return
        val generation = ++loadGeneration
        loadJob?.cancel()
        loadedDocument = null
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            var openedDocument: PdfDocument? = null
            try {
                val document = loader.openDocument(uri)
                openedDocument = document
                val infos = document.getPageInfos(0 until document.pageCount)
                if (generation != loadGeneration || pdfView !== targetView) return@launch

                val pageSizes = infos.associate { info ->
                    info.pageNum to PdfPageSize(info.width, info.height)
                }
                targetView.setDocument(document, pageSizes)
                openedDocument = null // Ownership moved to SinglePagePdfView.

                LoadedDocument(
                    uri = document.uri,
                    pageWidths = infos.associate { it.pageNum to it.width.toFloat() },
                    pageCount = document.pageCount,
                ).also { loaded ->
                    loadedDocument = loaded
                    listener?.onDocumentReady(loaded.uri, loaded.pageWidths, loaded.pageCount)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation == loadGeneration) listener?.onDocumentError(error)
            } finally {
                openedDocument?.close()
            }
        }
    }

    private data class LoadedDocument(
        val uri: Uri,
        val pageWidths: Map<Int, Float>,
        val pageCount: Int,
    )

    private companion object {
        const val KEY_DOCUMENT_URI = "reader_document_uri"
    }
}
