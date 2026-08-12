package com.studyink.teacher

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.studyink.annotation.storage.AnswerBookmark
import com.studyink.annotation.storage.AnswerRepository
import com.studyink.annotation.storage.ManagedAssetRepository
import com.studyink.document.pdf.PdfViewportAdapter
import com.studyink.document.pdf.ReaderPdfFragment
import com.studyink.document.pdf.SinglePagePdfView
import com.studyink.document.pdf.PdfViewportState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Read-only answer overlay. It owns a separate PDF view, so the problem Reader remains intact. */
class AnswerViewerActivity : FragmentActivity(), ReaderPdfFragment.Listener {
    private val viewport = PdfViewportAdapter()
    private lateinit var pdfFragment: ReaderPdfFragment
    private lateinit var pageLabel: TextView
    private var documentId = ""
    private var pageCount = 1
    private var currentPage = 0
    private var bookmarkJob: Job? = null
    private var restoreBookmark: AnswerBookmark? = null
    private lateinit var answerRepository: AnswerRepository
    private lateinit var assetRepository: ManagedAssetRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!TeacherSession.controller.isValid()) {
            finish()
            return
        }
        answerRepository = AnswerRepository.open(this)
        assetRepository = ManagedAssetRepository.open(this)
        buildUi()
        pdfFragment = supportFragmentManager.findFragmentByTag(PDF_TAG) as? ReaderPdfFragment
            ?: ReaderPdfFragment().also {
                supportFragmentManager.beginTransaction().replace(PDF_CONTAINER_ID, it, PDF_TAG).commitNow()
            }
        pdfFragment.listener = this
        if (savedInstanceState == null) resolveAndOpen()
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(232, 233, 238)) }
        setContentView(root)
        root.addView(androidx.fragment.app.FragmentContainerView(this).apply { id = PDF_CONTAINER_ID }, FrameLayout.LayoutParams(MATCH, MATCH).apply { topMargin = dp(56) })
        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            setBackgroundColor(Color.WHITE)
        }
        fun button(label: String, click: () -> Unit) = android.widget.Button(this).apply { text = label; setOnClickListener { click() } }
        bar.addView(button("닫기", ::finish))
        bar.addView(button("‹") { showPage(currentPage - 1) })
        pageLabel = TextView(this).apply { gravity = Gravity.CENTER; textSize = 16f }
        bar.addView(pageLabel, android.widget.LinearLayout.LayoutParams(0, MATCH, 1f))
        bar.addView(button("›") { showPage(currentPage + 1) })
        root.addView(bar, FrameLayout.LayoutParams(MATCH, dp(56), Gravity.TOP))
    }

    private fun resolveAndOpen() {
        lifecycleScope.launch {
            runCatching {
                val revision = requireNotNull(intent.getStringExtra(EXTRA_REVISION))
                val teacher = intent.getStringExtra(EXTRA_TEACHER) ?: DEFAULT_TEACHER
                val location = answerRepository.resolveAnswerLocation(
                    teacher, revision, intent.getStringExtra(EXTRA_ACTIVITY), intent.getStringExtra(EXTRA_PAGE), null,
                )
                documentId = location.answerDocumentId
                currentPage = location.pageIndex
                restoreBookmark = location.bookmark
                val document = answerRepository.observeDocuments(revision).first().first { it.id == documentId }
                val asset = assetRepository.open(document.assetId)
                check(document.type.name == "PDF") { "이미지 ZIP Viewer는 다음 보강 단계에서 지원합니다" }
                pdfFragment.documentUri = Uri.fromFile(asset.file)
            }.onFailure {
                Toast.makeText(this@AnswerViewerActivity, "정답지를 열 수 없습니다: ${it.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onPdfViewReady(view: SinglePagePdfView) {
        viewport.attach(view)
        viewport.onViewportChanged = { scheduleBookmark() }
    }

    override fun onDocumentReady(uri: Uri, pageWidths: Map<Int, Float>, pageCount: Int) {
        viewport.setPageWidths(pageWidths)
        this.pageCount = pageCount
        val restored = restoreBookmark
        if (restored == null) showPage(currentPage) else {
            currentPage = restored.pageIndex.coerceIn(0, pageCount - 1)
            pageLabel.text = "정답 ${currentPage + 1} / $pageCount"
            viewport.restore(PdfViewportState(currentPage, restored.normalizedCenterX, restored.normalizedCenterY, restored.zoomScale, 1f, 1f))
            restoreBookmark = null
        }
    }

    override fun onDocumentError(error: Throwable) {
        Toast.makeText(this, "정답 PDF 오류: ${error.message}", Toast.LENGTH_LONG).show()
    }

    private fun showPage(page: Int) {
        currentPage = page.coerceIn(0, pageCount - 1)
        viewport.showPage(currentPage)
        pageLabel.text = "정답 ${currentPage + 1} / $pageCount"
        scheduleBookmark()
    }

    private fun scheduleBookmark() {
        if (documentId.isBlank()) return
        bookmarkJob?.cancel()
        bookmarkJob = lifecycleScope.launch {
            delay(700)
            persistBookmark()
        }
    }

    private suspend fun persistBookmark() {
        val state = viewport.state() ?: return
        runCatching {
            answerRepository.saveBookmark(
                intent.getStringExtra(EXTRA_TEACHER) ?: DEFAULT_TEACHER,
                documentId,
                AnswerBookmark(state.pageNumber, state.normalizedCenterX, state.normalizedCenterY, state.zoomScale.coerceAtLeast(1f)),
            )
        }
    }

    override fun onStop() {
        lifecycleScope.launch { persistBookmark() }
        super.onStop()
    }

    override fun onDestroy() {
        if (::answerRepository.isInitialized) answerRepository.close()
        if (::assetRepository.isInitialized) assetRepository.close()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_REVISION = "com.studyink.answer.REVISION"
        const val EXTRA_ACTIVITY = "com.studyink.answer.ACTIVITY"
        const val EXTRA_PAGE = "com.studyink.answer.PAGE"
        const val EXTRA_TEACHER = "com.studyink.answer.TEACHER"
        private const val DEFAULT_TEACHER = "default-teacher"
        private const val PDF_CONTAINER_ID = 0x31A1
        private const val PDF_TAG = "answer-pdf"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
