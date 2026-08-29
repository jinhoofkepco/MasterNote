package com.studyink.reader

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.SparseArray
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.pdf.PdfPoint
import androidx.pdf.view.PdfView
import com.studyink.core.model.AnswerPdfViewport
import com.studyink.document.pdf.ReaderPdfFragment
import com.studyink.library.data.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Read-only answer PDF. Browsing never changes a problem mapping until the teacher confirms it. */
class AnswerPdfActivity : FragmentActivity(), ReaderPdfFragment.Listener {
    private val repository by lazy { LibraryRepository.get(this) }
    private lateinit var pdfFragment: ReaderPdfFragment
    private lateinit var pageLabel: TextView
    private lateinit var mappingLabel: TextView
    private var pdfView: PdfView? = null
    private var bookId = ""
    private var problemPage = 0
    private var answerPageCount = 0
    private var currentAnswerPage = 0
    private var initialAnswerPage = 0
    private var initialViewport: AnswerPdfViewport? = null
    private var pendingInitialPage: Int? = null
    private var pendingViewportRestore: AnswerPdfViewport? = null
    private var initialRestoreApplied = false
    private var restoreCommandIssued = false
    private var visiblePageLocations = SparseArray<RectF>()
    private var lastViewedSaveJob: Job? = null

    private val viewportListener = object : PdfView.OnViewportChangedListener {
        override fun onViewportChanged(
            firstVisiblePage: Int,
            visiblePagesCount: Int,
            pageLocations: SparseArray<RectF>,
            zoomLevel: Float,
        ) {
            val view = pdfView ?: return
            visiblePageLocations = SparseArray<RectF>(pageLocations.size()).also { copy ->
                for (index in 0 until pageLocations.size()) {
                    copy.put(pageLocations.keyAt(index), RectF(pageLocations.valueAt(index)))
                }
            }
            if (pendingInitialPage != null) {
                applyPendingInitialPosition(view, pageLocations)
                if (pendingInitialPage != null) return
            }
            val viewportCenterY = view.height / 2f
            val visibleEnd = firstVisiblePage + visiblePagesCount
            val nearest = (firstVisiblePage until visibleEnd)
                .filter { pageLocations[it] != null }
                .minByOrNull { page -> abs(pageLocations[page].centerY() - viewportCenterY) }
                ?: firstVisiblePage
            updateCurrentAnswerPage(nearest)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookId = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty()
        problemPage = intent.getIntExtra(EXTRA_PROBLEM_PAGE, -1)
        val book = runCatching { repository.book(bookId) }.getOrNull()
        if (book == null || problemPage !in 0 until book.pageCount) {
            Toast.makeText(this, "문제 페이지를 찾을 수 없습니다.", Toast.LENGTH_LONG).show()
            closeWithoutAnimation()
            return
        }
        val answerFile = runCatching { repository.answerPdfFile(book) }.getOrElse { error ->
            Toast.makeText(
                this,
                error.message ?: "교재 화면에서 답안 PDF를 먼저 연결하세요.",
                Toast.LENGTH_LONG,
            ).show()
            closeWithoutAnimation()
            return
        }
        initialViewport = repository.answerViewportForProblem(bookId, problemPage)
        initialAnswerPage = initialViewport?.answerPage
            ?: repository.answerPageForProblem(bookId, problemPage)
            ?: repository.lastViewedAnswerPage(bookId)
        onBackPressedDispatcher.addCallback(this) { closeWithoutAnimation() }
        buildUi()
        pdfFragment = supportFragmentManager.findFragmentByTag(PDF_TAG) as? ReaderPdfFragment
            ?: ReaderPdfFragment().also { fragment ->
                supportFragmentManager.beginTransaction()
                    .replace(PDF_CONTAINER_ID, fragment, PDF_TAG)
                    .commitNow()
            }
        pdfFragment.listener = this
        if (savedInstanceState == null) {
            pdfFragment.documentUri = Uri.fromFile(answerFile)
        } else {
            currentAnswerPage = savedInstanceState.getInt(STATE_ANSWER_PAGE, initialAnswerPage)
            initialAnswerPage = currentAnswerPage
            initialViewport = null
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(232, 233, 238)) }
        setContentView(root)
        root.addView(
            androidx.fragment.app.FragmentContainerView(this).apply { id = PDF_CONTAINER_ID },
            FrameLayout.LayoutParams(MATCH, MATCH).apply { topMargin = dp(76) },
        )
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setBackgroundColor(Color.rgb(250, 248, 242))
        }
        bar.addView(button("닫기", ::closeWithoutAnimation), LinearLayout.LayoutParams(dp(62), dp(48)))
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        pageLabel = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.rgb(42, 45, 48))
        }
        mappingLabel = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 10f
            setTextColor(Color.rgb(92, 94, 98))
        }
        labels.addView(pageLabel, LinearLayout.LayoutParams(MATCH, 0, 1f))
        labels.addView(mappingLabel, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
        bar.addView(labels, LinearLayout.LayoutParams(0, MATCH, 1f))
        bar.addView(button("연결") { saveMapping() }, LinearLayout.LayoutParams(dp(82), dp(48)))
        root.addView(bar, FrameLayout.LayoutParams(MATCH, dp(76), Gravity.TOP))
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        updateLabels()
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    @SuppressLint("RestrictedApi")
    override fun onPdfViewReady(view: PdfView) {
        pdfView?.removeOnViewportChangedListener(viewportListener)
        pdfView = view
        view.setBackgroundColor(Color.TRANSPARENT)
        view.pagesPerRow = PdfView.SINGLE_PAGE
        view.verticalAlignment = PdfView.VERTICAL_ALIGNMENT_CENTER
        view.overScrollMode = View.OVER_SCROLL_NEVER
        view.isEnabled = false
        view.addOnViewportChangedListener(viewportListener)
        restoreInitialPositionWhenReady()
    }

    override fun onDocumentReady(uri: Uri, pageWidths: Map<Int, Float>, pageCount: Int) {
        answerPageCount = pageCount
        initialAnswerPage = initialAnswerPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        currentAnswerPage = initialAnswerPage
        updateLabels()
        restoreInitialPositionWhenReady()
    }

    override fun onDocumentError(error: Throwable) {
        Toast.makeText(this, "답안 PDF를 열 수 없습니다: ${error.message}", Toast.LENGTH_LONG).show()
        closeWithoutAnimation()
    }

    private fun restoreInitialPositionWhenReady() {
        val view = pdfView ?: return
        if (answerPageCount <= 0 || initialRestoreApplied) return
        initialRestoreApplied = true
        val targetPage = initialAnswerPage.coerceIn(0, answerPageCount - 1)
        currentAnswerPage = targetPage
        pendingInitialPage = targetPage
        pendingViewportRestore = initialViewport?.copy(answerPage = targetPage)
        view.post {
            pendingViewportRestore?.let { saved ->
                view.zoom = saved.zoomScale.coerceIn(view.minZoom, view.maxZoom)
            }
            visiblePageLocations.clear()
            restoreCommandIssued = true
            view.scrollToPage(targetPage)
        }
    }

    private fun applyPendingInitialPosition(view: PdfView, pageLocations: SparseArray<RectF>) {
        if (!restoreCommandIssued) return
        val targetPage = pendingInitialPage ?: return
        if (pageLocations[targetPage] == null) return
        val saved = pendingViewportRestore
        if (saved == null) {
            pendingInitialPage = null
            view.isEnabled = true
            return
        }
        val point = view.pdfToViewPoint(PdfPoint(saved.answerPage, saved.pdfX, saved.pdfY)) ?: return
        pendingInitialPage = null
        pendingViewportRestore = null
        view.post {
            view.scrollBy(
                (point.x - view.width / 2f).roundToInt(),
                (point.y - view.height / 2f).roundToInt(),
            )
            view.isEnabled = true
        }
    }

    private fun updateCurrentAnswerPage(page: Int) {
        if (answerPageCount <= 0) return
        val target = page.coerceIn(0, answerPageCount - 1)
        if (currentAnswerPage == target) return
        currentAnswerPage = target
        updateLabels()
        scheduleLastViewedSave()
    }

    private fun updateLabels() {
        pageLabel.text = if (answerPageCount > 0) {
            "답안 ${currentAnswerPage + 1} / $answerPageCount"
        } else {
            "답안 불러오는 중"
        }
        val mapped = runCatching { repository.answerPageForProblem(bookId, problemPage) }.getOrNull()
        mappingLabel.text = when (mapped) {
            null -> "문제 ${problemPage + 1}쪽 · 아직 연결 안 됨"
            currentAnswerPage -> "문제 ${problemPage + 1}쪽과 연결됨"
            else -> "문제 ${problemPage + 1}쪽 → 답안 ${mapped + 1}쪽"
        }
    }

    private fun saveMapping() {
        if (answerPageCount <= 0) return
        val viewport = captureCurrentViewport()
        if (viewport == null) {
            Toast.makeText(this, "답안 페이지가 화면에 보일 때 연결해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            repository.saveAnswerViewportMapping(
                bookId = bookId,
                problemPage = problemPage,
                answerPage = viewport.answerPage,
                pdfX = viewport.pdfX,
                pdfY = viewport.pdfY,
                zoomScale = viewport.zoomScale,
            )
            repository.saveLastViewedAnswerPage(bookId, viewport.answerPage)
        }.onSuccess {
            currentAnswerPage = viewport.answerPage
            updateLabels()
            Toast.makeText(
                this,
                "문제 ${problemPage + 1}쪽의 답안 위치와 확대 상태를 저장했습니다.",
                Toast.LENGTH_SHORT,
            ).show()
        }.onFailure { error ->
            Toast.makeText(this, error.message ?: "답안 페이지를 연결하지 못했습니다.", Toast.LENGTH_LONG).show()
        }
    }

    private fun captureCurrentViewport(): AnswerPdfViewport? {
        val view = pdfView ?: return null
        val pageBounds = visiblePageLocations[currentAnswerPage] ?: return null
        if (pageBounds.width() <= 0f || pageBounds.height() <= 0f) return null
        val insetX = minOf(1f, pageBounds.width() / 4f)
        val insetY = minOf(1f, pageBounds.height() / 4f)
        val viewX = (view.width / 2f).coerceIn(pageBounds.left + insetX, pageBounds.right - insetX)
        val viewY = (view.height / 2f).coerceIn(pageBounds.top + insetY, pageBounds.bottom - insetY)
        val point = view.viewToPdfPoint(viewX, viewY) ?: return null
        return AnswerPdfViewport(
            answerPage = point.pageNum,
            pdfX = point.x,
            pdfY = point.y,
            zoomScale = view.zoom,
        )
    }

    @Suppress("DEPRECATION")
    private fun closeWithoutAnimation() {
        finish()
        overridePendingTransition(0, 0)
    }

    private fun scheduleLastViewedSave() {
        lastViewedSaveJob?.cancel()
        lastViewedSaveJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(LAST_VIEWED_SAVE_DELAY_MILLIS)
            runCatching { repository.saveLastViewedAnswerPage(bookId, currentAnswerPage) }
        }
    }

    override fun onStop() {
        lastViewedSaveJob?.cancel()
        val page = currentAnswerPage
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { repository.saveLastViewedAnswerPage(bookId, page) }
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_ANSWER_PAGE, currentAnswerPage)
        super.onSaveInstanceState(outState)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_BOOK_ID = "com.studyink.answer.BOOK_ID"
        private const val EXTRA_PROBLEM_PAGE = "com.studyink.answer.PROBLEM_PAGE"
        private const val STATE_ANSWER_PAGE = "answer.currentPage"
        private const val PDF_CONTAINER_ID = 0x41A1
        private const val PDF_TAG = "answer-pdf"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val LAST_VIEWED_SAVE_DELAY_MILLIS = 500L

        fun intent(context: Context, bookId: String, problemPage: Int): Intent =
            Intent(context, AnswerPdfActivity::class.java)
                .putExtra(EXTRA_BOOK_ID, bookId)
                .putExtra(EXTRA_PROBLEM_PAGE, problemPage)
    }
}
