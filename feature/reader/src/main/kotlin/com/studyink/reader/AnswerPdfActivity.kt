package com.studyink.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.SparseArray
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
import com.studyink.core.model.AnswerPdfCrop
import com.studyink.core.model.AnswerPdfViewport
import com.studyink.document.pdf.ReaderPdfFragment
import com.studyink.library.data.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/** Full answer PDF used to browse and select one persistent answer-region crop. */
class AnswerPdfActivity : FragmentActivity(), ReaderPdfFragment.Listener {
    private val repository by lazy { LibraryRepository.get(this) }

    private lateinit var rootHost: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var pageLabel: TextView
    private lateinit var mappingLabel: TextView
    private lateinit var mappingButton: TextView
    private lateinit var selectionView: PageRegionSelectionView
    private lateinit var pdfFragment: ReaderPdfFragment

    private var pdfView: PdfView? = null
    private var bookId = ""
    private var problemPage = 0
    private var answerPageCount = 0
    private var currentAnswerPage = 0

    private var requestedViewport: AnswerPdfViewport? = null
    private var requestedPage = 0
    private var focusCrop: AnswerPdfCrop? = null
    private var restorePhase = RestorePhase.WAITING_FOR_CONTENT
    private var firstContentLoaded = false
    private var restoreTargetPage = 0
    private var restoreTargetZoom: Float? = null

    private var firstVisiblePage = 0
    private var visiblePagesCount = 0
    private var visiblePageLocations = SparseArray<RectF>()
    private var selectionAnswerPage: Int? = null
    private var viewportSaveJob: Job? = null

    private val firstContentLoadListener = PdfView.OnFirstContentLoadListener {
        firstContentLoaded = true
        tryStartInitialRestore()
    }

    private val viewportListener = object : PdfView.OnViewportChangedListener {
        override fun onViewportChanged(
            firstVisiblePage: Int,
            visiblePagesCount: Int,
            pageLocations: SparseArray<RectF>,
            zoomLevel: Float,
        ) {
            this@AnswerPdfActivity.firstVisiblePage = firstVisiblePage
            this@AnswerPdfActivity.visiblePagesCount = visiblePagesCount
            visiblePageLocations = pageLocations.deepCopy()

            when (restorePhase) {
                RestorePhase.WAITING_FOR_TARGET -> {
                    if (isRestoreTargetVisible(firstVisiblePage, visiblePagesCount, zoomLevel)) {
                        completeInitialRestore()
                    }
                }
                RestorePhase.COMPLETE -> processVisibleViewport()
                RestorePhase.WAITING_FOR_CONTENT,
                RestorePhase.APPLYING,
                -> Unit
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookId = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty()
        problemPage = intent.getIntExtra(EXTRA_PROBLEM_PAGE, -1)
        val book = runCatching { repository.book(bookId) }.getOrNull()
        if (book == null || problemPage !in 0 until book.pageCount) {
            Toast.makeText(this, "문제 페이지를 찾을 수 없습니다.", Toast.LENGTH_LONG).show()
            closeWithoutAnimation(saveViewport = false)
            return
        }
        val answerFile = runCatching { repository.answerPdfFile(book) }.getOrElse { error ->
            Toast.makeText(
                this,
                error.message ?: "교재 화면에서 답안 PDF를 먼저 연결하세요.",
                Toast.LENGTH_LONG,
            ).show()
            closeWithoutAnimation(saveViewport = false)
            return
        }

        answerPageCount = book.answerPdfPageCount
        restoreRequest(savedInstanceState)
        currentAnswerPage = requestedPage.coerceIn(0, (answerPageCount - 1).coerceAtLeast(0))

        buildUi()
        onBackPressedDispatcher.addCallback(this) {
            if (selectionView.isShown) {
                selectionView.cancelSelection()
            } else {
                closeWithoutAnimation()
            }
        }
        attachPdfFragment(Uri.fromFile(answerFile), savedInstanceState == null)
    }

    private fun restoreRequest(savedInstanceState: Bundle?) {
        val savedViewport = savedInstanceState?.readViewport()
        if (savedViewport != null) {
            requestedViewport = savedViewport
            requestedPage = savedViewport.answerPage
            focusCrop = null
            return
        }
        if (savedInstanceState != null) {
            requestedPage = savedInstanceState.getInt(
                STATE_ANSWER_PAGE,
                repository.lastViewedAnswerPage(bookId),
            )
            requestedViewport = null
            focusCrop = null
            return
        }

        focusCrop = if (intent.getBooleanExtra(EXTRA_FOCUS_EXISTING_CROP, false)) {
            repository.answerCropForProblem(bookId, problemPage)
        } else {
            null
        }
        requestedViewport = if (focusCrop == null) {
            repository.lastAnswerPdfViewport(bookId)
                ?: repository.answerViewportForProblem(bookId, problemPage)
        } else {
            null
        }
        requestedPage = focusCrop?.answerPage
            ?: requestedViewport?.answerPage
            ?: repository.lastViewedAnswerPage(bookId)
    }

    private fun buildUi() {
        rootHost = FrameLayout(this).apply { setBackgroundColor(Color.rgb(232, 233, 238)) }
        setContentView(rootHost)

        val container = androidx.fragment.app.FragmentContainerView(this).apply {
            id = PDF_CONTAINER_ID
        }
        rootHost.addView(
            container,
            FrameLayout.LayoutParams(MATCH, MATCH).apply { topMargin = dp(TOP_BAR_HEIGHT_DP) },
        )

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(1), dp(5), dp(1))
            setBackgroundColor(Color.rgb(250, 248, 242))
        }
        topBar.addView(
            compactButton("닫기") { closeWithoutAnimation() },
            LinearLayout.LayoutParams(dp(52), dp(44)),
        )
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        pageLabel = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(Color.rgb(42, 45, 48))
        }
        mappingLabel = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 9f
            setTextColor(Color.rgb(92, 94, 98))
        }
        labels.addView(pageLabel, LinearLayout.LayoutParams(MATCH, 0, 1f))
        labels.addView(mappingLabel, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
        topBar.addView(labels, LinearLayout.LayoutParams(0, MATCH, 1f))
        mappingButton = compactButton("연결", ::beginCropSelection)
        topBar.addView(mappingButton, LinearLayout.LayoutParams(dp(66), dp(44)))
        rootHost.addView(
            topBar,
            FrameLayout.LayoutParams(MATCH, dp(TOP_BAR_HEIGHT_DP), Gravity.TOP),
        )

        selectionView = PageRegionSelectionView(this).also { selector ->
            selector.contentDescription = "답안 영역 선택"
            selector.hintText = "저장할 답 영역을 드래그하세요"
            selector.confirmButtonText = "저장"
            selector.compactControls = true
            selector.onSelectionConfirmed = ::saveCropSelection
            selector.onSelectionCancelled = ::finishSelectionMode
        }
        rootHost.addView(selectionView, FrameLayout.LayoutParams(MATCH, MATCH))

        ViewCompat.setOnApplyWindowInsetsListener(rootHost) { _, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            (topBar.layoutParams as FrameLayout.LayoutParams).also { params ->
                params.leftMargin = safe.left
                params.topMargin = safe.top
                params.rightMargin = safe.right
                topBar.layoutParams = params
            }
            (container.layoutParams as FrameLayout.LayoutParams).also { params ->
                params.leftMargin = safe.left
                params.topMargin = safe.top + dp(TOP_BAR_HEIGHT_DP)
                params.rightMargin = safe.right
                params.bottomMargin = safe.bottom
                container.layoutParams = params
            }
            insets
        }
        ViewCompat.requestApplyInsets(rootHost)
        updateLabels()
    }

    private fun compactButton(label: String, action: () -> Unit): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 12f
        setTextColor(Color.rgb(39, 43, 48))
        isClickable = true
        isFocusable = true
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(7).toFloat()
            setColor(Color.rgb(235, 232, 224))
            setStroke(dp(1), Color.rgb(213, 209, 199))
        }
        setOnClickListener { action() }
    }

    private fun attachPdfFragment(documentUri: Uri, setDocument: Boolean) {
        val existing = supportFragmentManager.findFragmentByTag(PDF_TAG) as? ReaderPdfFragment
        if (existing != null) {
            pdfFragment = existing
            pdfFragment.loadAllPageWidths = false
            pdfFragment.listener = this
        } else {
            pdfFragment = ReaderPdfFragment().apply {
                loadAllPageWidths = false
                listener = this@AnswerPdfActivity
            }
            supportFragmentManager.beginTransaction()
                .replace(PDF_CONTAINER_ID, pdfFragment, PDF_TAG)
                .commitNow()
        }
        if (setDocument || pdfFragment.documentUri == null) {
            pdfFragment.documentUri = documentUri
        }
    }

    @SuppressLint("RestrictedApi")
    override fun onPdfViewReady(view: PdfView) {
        if (pdfView === view) return
        pdfView?.removeOnViewportChangedListener(viewportListener)
        pdfView?.removeOnFirstContentLoadListener(firstContentLoadListener)
        pdfView = view
        firstContentLoaded = false
        restorePhase = RestorePhase.WAITING_FOR_CONTENT
        view.setBackgroundColor(Color.TRANSPARENT)
        view.pagesPerRow = PdfView.SINGLE_PAGE
        view.verticalAlignment = PdfView.VERTICAL_ALIGNMENT_CENTER
        view.overScrollMode = View.OVER_SCROLL_NEVER
        view.alpha = 0f
        view.isEnabled = false
        view.addOnViewportChangedListener(viewportListener)
        view.addOnFirstContentLoadListener(firstContentLoadListener)
    }

    override fun onDocumentReady(uri: Uri, pageWidths: Map<Int, Float>, pageCount: Int) {
        answerPageCount = pageCount
        requestedPage = requestedPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        currentAnswerPage = currentAnswerPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        updateLabels()
        tryStartInitialRestore()
    }

    override fun onDocumentError(error: Throwable) {
        Toast.makeText(this, "답안 PDF를 열 수 없습니다: ${error.message}", Toast.LENGTH_LONG).show()
        closeWithoutAnimation(saveViewport = false)
    }

    private fun tryStartInitialRestore() {
        val view = pdfView ?: return
        if (
            restorePhase != RestorePhase.WAITING_FOR_CONTENT ||
            !firstContentLoaded ||
            answerPageCount <= 0
        ) return

        restoreTargetPage = requestedPage.coerceIn(0, answerPageCount - 1)
        val viewport = focusCrop?.toFocusedViewport(view)
            ?: requestedViewport?.copy(answerPage = restoreTargetPage)
        restoreTargetZoom = viewport?.zoomScale?.coerceIn(view.minZoom, view.maxZoom)
        restorePhase = RestorePhase.APPLYING

        view.post {
            if (restorePhase != RestorePhase.APPLYING || pdfView !== view) return@post
            if (viewport != null) {
                view.zoom = requireNotNull(restoreTargetZoom)
                view.scrollToPosition(PdfPoint(viewport.answerPage, viewport.pdfX, viewport.pdfY))
            } else {
                view.scrollToPage(restoreTargetPage)
            }
            restorePhase = RestorePhase.WAITING_FOR_TARGET
            view.post {
                if (
                    restorePhase == RestorePhase.WAITING_FOR_TARGET &&
                    isRestoreTargetVisible(view.firstVisiblePage, view.visiblePagesCount, view.zoom)
                ) {
                    completeInitialRestore()
                }
            }
        }
    }

    private fun AnswerPdfCrop.toFocusedViewport(view: PdfView): AnswerPdfViewport {
        val availableWidth = (view.width * CROP_FOCUS_WIDTH_FRACTION).coerceAtLeast(1f)
        val availableHeight = (view.height * CROP_FOCUS_HEIGHT_FRACTION).coerceAtLeast(1f)
        val fitZoom = min(availableWidth / (right - left), availableHeight / (bottom - top))
            .coerceIn(view.minZoom, view.maxZoom)
        return AnswerPdfViewport(
            answerPage = answerPage,
            pdfX = (left + right) / 2f,
            pdfY = (top + bottom) / 2f,
            zoomScale = fitZoom,
        )
    }

    private fun isRestoreTargetVisible(first: Int, count: Int, zoom: Float): Boolean {
        if (count <= 0 || restoreTargetPage !in first until (first + count)) return false
        val expectedZoom = restoreTargetZoom ?: return true
        val tolerance = maxOf(RESTORE_ZOOM_EPSILON, expectedZoom * RESTORE_ZOOM_EPSILON_FRACTION)
        return abs(zoom - expectedZoom) <= tolerance
    }

    private fun completeInitialRestore() {
        val view = pdfView ?: return
        if (restorePhase == RestorePhase.COMPLETE) return
        restorePhase = RestorePhase.COMPLETE
        view.alpha = 1f
        view.isEnabled = selectionView.visibility != View.VISIBLE
        processVisibleViewport()
    }

    private fun processVisibleViewport() {
        val view = pdfView ?: return
        if (visiblePagesCount <= 0) return
        val visibleEnd = (firstVisiblePage + visiblePagesCount).coerceAtMost(answerPageCount)
        val viewportCenterY = view.height / 2f
        val nearest = (firstVisiblePage until visibleEnd)
            .filter { visiblePageLocations[it] != null }
            .minByOrNull { page -> abs(visiblePageLocations[page].centerY() - viewportCenterY) }
            ?: firstVisiblePage
        currentAnswerPage = nearest.coerceIn(0, (answerPageCount - 1).coerceAtLeast(0))
        updateLabels()
        captureCurrentViewport()?.let(::scheduleViewportSave)
    }

    private fun updateLabels() {
        pageLabel.text = if (answerPageCount > 0) {
            "답안 ${currentAnswerPage + 1} / $answerPageCount"
        } else {
            "답안 불러오는 중"
        }
        val crop = runCatching { repository.answerCropForProblem(bookId, problemPage) }.getOrNull()
        val mapped = runCatching { repository.answerPageForProblem(bookId, problemPage) }.getOrNull()
        mappingLabel.text = when {
            crop != null -> "문제 ${problemPage + 1}쪽 · 답 영역 저장됨"
            mapped == null -> "문제 ${problemPage + 1}쪽 · 아직 연결 안 됨"
            mapped == currentAnswerPage -> "문제 ${problemPage + 1}쪽과 연결됨"
            else -> "문제 ${problemPage + 1}쪽 → 답안 ${mapped + 1}쪽"
        }
        if (::mappingButton.isInitialized) {
            mappingButton.text = if (crop == null) "연결" else "다시 연결"
        }
    }

    private fun beginCropSelection() {
        val view = pdfView ?: return
        if (restorePhase != RestorePhase.COMPLETE || answerPageCount <= 0) {
            Toast.makeText(this, "답안이 표시된 뒤 다시 눌러 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val page = activeVisiblePage() ?: run {
            Toast.makeText(this, "저장할 답안 페이지를 화면에 보여 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val pageBounds = visiblePageLocations[page] ?: return
        val limit = pdfRectToSelectorRect(pageBounds)
        if (limit.width() < dp(12).toFloat() || limit.height() < dp(12).toFloat()) {
            Toast.makeText(this, "답안 페이지를 조금 더 크게 보여 주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        selectionAnswerPage = page
        topBar.visibility = View.INVISIBLE
        view.isEnabled = false
        val existingSelection = repository.answerCropForProblem(bookId, problemPage)
            ?.takeIf { it.answerPage == page }
            ?.let(::cropToSelectorRect)
            ?.intersectedWith(limit)
        selectionView.beginSelection(limit, existingSelection)
    }

    private fun activeVisiblePage(): Int? {
        if (visiblePagesCount <= 0) return null
        val end = (firstVisiblePage + visiblePagesCount).coerceAtMost(answerPageCount)
        return currentAnswerPage.takeIf { it in firstVisiblePage until end }
            ?: (firstVisiblePage until end).firstOrNull { visiblePageLocations[it] != null }
    }

    private fun pdfRectToSelectorRect(pdfViewRect: RectF): RectF {
        val pdfLocation = IntArray(2).also { pdfView?.getLocationOnScreen(it) }
        val selectorLocation = IntArray(2).also { selectionView.getLocationOnScreen(it) }
        val offsetX = (pdfLocation[0] - selectorLocation[0]).toFloat()
        val offsetY = (pdfLocation[1] - selectorLocation[1]).toFloat()
        return RectF(pdfViewRect).apply { offset(offsetX, offsetY) }
    }

    private fun cropToSelectorRect(crop: AnswerPdfCrop): RectF? {
        val view = pdfView ?: return null
        val topLeft = view.pdfToViewPoint(PdfPoint(crop.answerPage, crop.left, crop.top)) ?: return null
        val bottomRight = view.pdfToViewPoint(PdfPoint(crop.answerPage, crop.right, crop.bottom)) ?: return null
        val viewRect = RectF(
            minOf(topLeft.x, bottomRight.x),
            minOf(topLeft.y, bottomRight.y),
            maxOf(topLeft.x, bottomRight.x),
            maxOf(topLeft.y, bottomRight.y),
        )
        return pdfRectToSelectorRect(viewRect)
    }

    private fun saveCropSelection(selectionInSelector: RectF) {
        val view = pdfView
        val expectedPage = selectionAnswerPage
        if (view == null || expectedPage == null) {
            finishSelectionMode()
            return
        }
        val pdfLocation = IntArray(2).also { view.getLocationOnScreen(it) }
        val selectorLocation = IntArray(2).also { selectionView.getLocationOnScreen(it) }
        val offsetX = (selectorLocation[0] - pdfLocation[0]).toFloat()
        val offsetY = (selectorLocation[1] - pdfLocation[1]).toFloat()
        val inset = minOf(
            SELECTION_EDGE_INSET_PX,
            selectionInSelector.width() / 4f,
            selectionInSelector.height() / 4f,
        )
        val topLeft = view.viewToPdfPoint(
            selectionInSelector.left + offsetX + inset,
            selectionInSelector.top + offsetY + inset,
        )
        val bottomRight = view.viewToPdfPoint(
            selectionInSelector.right + offsetX - inset,
            selectionInSelector.bottom + offsetY - inset,
        )
        if (
            topLeft == null || bottomRight == null ||
            topLeft.pageNum != expectedPage || bottomRight.pageNum != expectedPage
        ) {
            finishSelectionMode()
            Toast.makeText(this, "한 답안 페이지 안에서 영역을 선택해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        val left = minOf(topLeft.x, bottomRight.x)
        val top = minOf(topLeft.y, bottomRight.y)
        val right = maxOf(topLeft.x, bottomRight.x)
        val bottom = maxOf(topLeft.y, bottomRight.y)
        runCatching {
            repository.saveAnswerCropMapping(
                bookId = bookId,
                problemPage = problemPage,
                answerPage = expectedPage,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
            )
        }.onSuccess {
            currentAnswerPage = expectedPage
            setResult(
                Activity.RESULT_OK,
                Intent()
                    .putExtra(RESULT_EXTRA_BOOK_ID, bookId)
                    .putExtra(RESULT_EXTRA_PROBLEM_PAGE, problemPage),
            )
            finishSelectionMode()
            updateLabels()
            Toast.makeText(this, "답 영역을 저장했습니다.", Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
            finishSelectionMode()
            Toast.makeText(this, error.message ?: "답 영역을 저장하지 못했습니다.", Toast.LENGTH_LONG).show()
        }
    }

    private fun finishSelectionMode() {
        selectionAnswerPage = null
        topBar.visibility = View.VISIBLE
        pdfView?.isEnabled = restorePhase == RestorePhase.COMPLETE
    }

    private fun captureCurrentViewport(): AnswerPdfViewport? {
        val view = pdfView ?: return null
        if (restorePhase != RestorePhase.COMPLETE || answerPageCount <= 0) return null
        val page = activeVisiblePage() ?: return null
        val bounds = visiblePageLocations[page] ?: return null
        if (bounds.width() <= 0f || bounds.height() <= 0f) return null
        val insetX = minOf(1f, bounds.width() / 4f)
        val insetY = minOf(1f, bounds.height() / 4f)
        val viewX = (view.width / 2f).coerceIn(bounds.left + insetX, bounds.right - insetX)
        val viewY = (view.height / 2f).coerceIn(bounds.top + insetY, bounds.bottom - insetY)
        val point = view.viewToPdfPoint(viewX, viewY)?.takeIf { it.pageNum == page } ?: return null
        return AnswerPdfViewport(
            answerPage = point.pageNum,
            pdfX = point.x,
            pdfY = point.y,
            zoomScale = view.zoom,
        )
    }

    private fun scheduleViewportSave(viewport: AnswerPdfViewport) {
        viewportSaveJob?.cancel()
        viewportSaveJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(VIEWPORT_SAVE_DELAY_MILLIS)
            runCatching { viewport.persist() }
        }
    }

    private fun AnswerPdfViewport.persist() {
        repository.saveLastAnswerPdfViewport(
            bookId = bookId,
            answerPage = answerPage,
            pdfX = pdfX,
            pdfY = pdfY,
            zoomScale = zoomScale,
        )
    }

    @Suppress("DEPRECATION")
    private fun closeWithoutAnimation(saveViewport: Boolean = true) {
        if (saveViewport) saveCurrentViewportNow()
        finish()
        overridePendingTransition(0, 0)
    }

    private fun saveCurrentViewportNow() {
        viewportSaveJob?.cancel()
        viewportSaveJob = null
        captureCurrentViewport()?.let { viewport -> runCatching { viewport.persist() } }
    }

    override fun onStop() {
        saveCurrentViewportNow()
        super.onStop()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        if (::selectionView.isInitialized && selectionView.visibility == View.VISIBLE) {
            selectionView.cancelSelection()
        }
        super.onConfigurationChanged(newConfig)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val viewport = captureCurrentViewport()
        if (viewport != null) {
            outState.writeViewport(viewport)
        } else {
            outState.putInt(STATE_ANSWER_PAGE, currentAnswerPage)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        viewportSaveJob?.cancel()
        pdfView?.removeOnViewportChangedListener(viewportListener)
        pdfView?.removeOnFirstContentLoadListener(firstContentLoadListener)
        if (::pdfFragment.isInitialized) pdfFragment.listener = null
        super.onDestroy()
    }

    private fun SparseArray<RectF>.deepCopy(): SparseArray<RectF> =
        SparseArray<RectF>(size()).also { copy ->
            for (index in 0 until size()) {
                copy.put(keyAt(index), RectF(valueAt(index)))
            }
        }

    private fun RectF.intersectedWith(other: RectF): RectF? {
        val intersection = RectF(this)
        return intersection.takeIf { it.intersect(other) && it.width() > 0f && it.height() > 0f }
    }

    private fun Bundle.writeViewport(viewport: AnswerPdfViewport) {
        putBoolean(STATE_HAS_VIEWPORT, true)
        putInt(STATE_VIEWPORT_PAGE, viewport.answerPage)
        putFloat(STATE_VIEWPORT_X, viewport.pdfX)
        putFloat(STATE_VIEWPORT_Y, viewport.pdfY)
        putFloat(STATE_VIEWPORT_ZOOM, viewport.zoomScale)
    }

    private fun Bundle.readViewport(): AnswerPdfViewport? {
        if (!getBoolean(STATE_HAS_VIEWPORT, false)) return null
        return runCatching {
            AnswerPdfViewport(
                answerPage = getInt(STATE_VIEWPORT_PAGE),
                pdfX = getFloat(STATE_VIEWPORT_X),
                pdfY = getFloat(STATE_VIEWPORT_Y),
                zoomScale = getFloat(STATE_VIEWPORT_ZOOM),
            )
        }.getOrNull()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private enum class RestorePhase {
        WAITING_FOR_CONTENT,
        APPLYING,
        WAITING_FOR_TARGET,
        COMPLETE,
    }

    companion object {
        private const val EXTRA_BOOK_ID = "com.studyink.answer.BOOK_ID"
        private const val EXTRA_PROBLEM_PAGE = "com.studyink.answer.PROBLEM_PAGE"
        private const val EXTRA_FOCUS_EXISTING_CROP = "com.studyink.answer.FOCUS_EXISTING_CROP"
        const val RESULT_EXTRA_BOOK_ID = "com.studyink.answer.RESULT_BOOK_ID"
        const val RESULT_EXTRA_PROBLEM_PAGE = "com.studyink.answer.RESULT_PROBLEM_PAGE"

        private const val STATE_ANSWER_PAGE = "answer.currentPage"
        private const val STATE_HAS_VIEWPORT = "answer.hasViewport"
        private const val STATE_VIEWPORT_PAGE = "answer.viewport.page"
        private const val STATE_VIEWPORT_X = "answer.viewport.x"
        private const val STATE_VIEWPORT_Y = "answer.viewport.y"
        private const val STATE_VIEWPORT_ZOOM = "answer.viewport.zoom"
        private const val PDF_CONTAINER_ID = 0x41A1
        private const val PDF_TAG = "answer-pdf"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val TOP_BAR_HEIGHT_DP = 46
        private const val VIEWPORT_SAVE_DELAY_MILLIS = 700L
        private const val RESTORE_ZOOM_EPSILON = 0.02f
        private const val RESTORE_ZOOM_EPSILON_FRACTION = 0.015f
        private const val CROP_FOCUS_WIDTH_FRACTION = 0.88f
        private const val CROP_FOCUS_HEIGHT_FRACTION = 0.86f
        private const val SELECTION_EDGE_INSET_PX = 0.5f

        fun intent(
            context: Context,
            bookId: String,
            problemPage: Int,
            focusExistingCrop: Boolean = false,
        ): Intent = Intent(context, AnswerPdfActivity::class.java)
            .putExtra(EXTRA_BOOK_ID, bookId)
            .putExtra(EXTRA_PROBLEM_PAGE, problemPage)
            .putExtra(EXTRA_FOCUS_EXISTING_CROP, focusExistingCrop)
    }
}
