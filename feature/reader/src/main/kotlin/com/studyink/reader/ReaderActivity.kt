package com.studyink.reader

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.strokes.Stroke
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.studyink.document.pdf.PdfViewportAdapter
import com.studyink.document.pdf.ReaderPdfFragment
import com.studyink.document.pdf.SinglePagePdfView
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.io.File
import com.studyink.annotation.storage.RoomTeacherRepository
import com.studyink.core.model.ReviewDecision
import com.studyink.core.model.ReviewId
import com.studyink.core.model.ReviewSession

class ReaderActivity : FragmentActivity(), ReaderPdfFragment.Listener {
    private val viewModel: ReaderViewModel by viewModels()
    private val launchArgs by lazy { ReaderLaunchArgs.from(intent) }
    private val readerScene by lazy { ReaderSceneIntentCodec.from(intent) }
    private val viewport = PdfViewportAdapter()
    private lateinit var dryInkView: DryInkView
    private lateinit var wetInkView: InProgressStrokesView
    private lateinit var inputView: InkInputView
    private lateinit var pdfFragment: ReaderPdfFragment
    private lateinit var topBar: ComposeView
    private lateinit var paletteAnchor: ComposeView
    private var reviewPane: ComposeView? = null
    private var selectedTool by mutableStateOf(ReaderTool.PEN)
    private var selectedPenColor by mutableStateOf(0xFF17233C.toInt())
    private var selectedPenWidthDp by mutableStateOf(3.2f)
    private var selectedPenOpacity by mutableStateOf(1f)
    private var latestState by mutableStateOf(ReaderUiState())
    private var currentPage by mutableStateOf(0)
    private var loadedPageCount by mutableStateOf(1)
    private var stylusMenuExpanded by mutableStateOf(false)
    private var stylusButtonPressed = false
    private var appliedInitialAttemptId: String? = null
    private var appliedSceneKey: String? = null
    private var reviewSession by mutableStateOf<ReviewSession?>(null)
    private var reviewSummary by mutableStateOf("")
    private var reviewPublishing by mutableStateOf(false)
    private var reviewError by mutableStateOf<String?>(null)
    private var reviewSaveJob: Job? = null
    private var reviewRepository: RoomTeacherRepository? = null
    private var reviewExpanded = false

    private val openPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            showDocument(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (readerScene?.requiresTeacherAccess() == true && !TeacherRouteAccess.session.isValid()) {
            finish()
            return
        }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(225, 226, 231)) }
        setContentView(root)

        val fragmentContainer = FragmentContainerView(this).apply { id = PDF_CONTAINER_ID }
        root.addView(fragmentContainer, FrameLayout.LayoutParams(MATCH, MATCH).apply {
            topMargin = dp(TOP_BAR_HEIGHT)
        })

        dryInkView = DryInkView(this).also {
            it.viewport = viewport
            root.addView(it, contentLayoutParams())
        }
        viewport.onViewportChanged = {
            dryInkView.postInvalidateOnAnimation()
            viewport.state()?.let { ReaderRemoteBridge.sink?.onViewportChanged(it) }
        }
        wetInkView = InProgressStrokesView(this).also {
            it.eagerInit()
            it.addFinishedStrokesListener(
                object : InProgressStrokesFinishedListener {
                    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                        handOffFinishedWetInk(strokes)
                    }
                }
            )
            root.addView(it, contentLayoutParams())
        }
        inputView = InkInputView(this).also {
            it.viewport = viewport
            it.wetInkView = wetInkView
            it.tool = selectedTool
            it.penColorArgb = selectedPenColor
            it.penWidthDp = selectedPenWidthDp
            it.penOpacity = selectedPenOpacity
            it.onStylusContact = {
                if (stylusMenuExpanded) {
                    stylusMenuExpanded = false
                    dryInkView.eraserPreview = null
                }
            }
            it.onStroke = { stroke -> viewModel.addStroke(stroke) }
            it.onStrokePreview = { id, page, points, time ->
                ReaderRemoteBridge.sink?.onStrokePreview(id, page, points, time)
            }
            it.onStrokePreviewFinished = { id -> ReaderRemoteBridge.sink?.onStrokeFinished(id) }
            it.onEraserPreview = { preview -> dryInkView.eraserPreview = preview }
            it.onErase = { page, path, radius, whole ->
                viewModel.erase(page, path, radius, whole) { dryInkView.eraserPreview = null }
            }
            root.addView(it, contentLayoutParams())
        }

        topBar = ComposeView(this).also { composeView ->
            composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            root.addView(composeView, FrameLayout.LayoutParams(MATCH, dp(TOP_BAR_HEIGHT), Gravity.TOP))
        }
        paletteAnchor = ComposeView(this).also { composeView ->
            composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            root.addView(
                composeView,
                FrameLayout.LayoutParams(dp(1), dp(1), Gravity.TOP or Gravity.START)
            )
        }
        setupReviewPane(root, fragmentContainer)
        applySystemBarInsets(root, fragmentContainer)
        refreshChrome()

        pdfFragment = supportFragmentManager.findFragmentByTag(PDF_FRAGMENT_TAG) as? ReaderPdfFragment
            ?: ReaderPdfFragment().also { fragment ->
                fragment.listener = this
                supportFragmentManager.beginTransaction()
                    .replace(PDF_CONTAINER_ID, fragment, PDF_FRAGMENT_TAG)
                    .commitNow()
            }
        pdfFragment.listener = this

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    latestState = state
                    dryInkView.snapshot = state.snapshot
                    inputView.visibility = if (state.readOnly) View.INVISIBLE else View.VISIBLE
                    refreshReviewPane()
                    val session = state.attemptSession
                    if (session != null && appliedInitialAttemptId != session.attempt.attemptId.value) {
                        appliedInitialAttemptId = session.attempt.attemptId.value
                        showPage(state.initialPageNumber)
                    } else if (state.scene != null) {
                        val key = "${state.scene.documentRevisionId.value}:${state.scene.initialPageId.value}:${state.scene.interactionPolicy}"
                        if (appliedSceneKey != key) {
                            appliedSceneKey = key
                            showPage(state.initialPageNumber)
                        }
                    }
                }
            }
        }

        if (savedInstanceState == null) {
            showDocument(Uri.fromFile(ensureSamplePdf()))
        }
    }

    override fun onPdfViewReady(view: SinglePagePdfView) {
        viewport.attach(view)
        dryInkView.invalidate()
    }

    override fun onDocumentReady(uri: Uri, pageWidths: Map<Int, Float>, pageCount: Int) {
        viewport.setPageWidths(pageWidths)
        loadedPageCount = pageCount
        currentPage = 0
        dryInkView.activePage = 0
        viewport.showPage(0)
        val label = documentLabel(uri)
        viewModel.loadDocument(uri, label, pageCount, launchArgs, readerScene)
        dryInkView.invalidate()
        Toast.makeText(this, "$pageCount 페이지를 열었습니다", Toast.LENGTH_SHORT).show()
    }

    override fun onDocumentError(error: Throwable) {
        Toast.makeText(this, "PDF를 열 수 없습니다: ${error.message}", Toast.LENGTH_LONG).show()
    }

    private fun showDocument(uri: Uri) {
        stylusMenuExpanded = false
        viewModel.flushAsync()
        pdfFragment.documentUri = uri
    }

    private fun selectTool(tool: ReaderTool) {
        if (latestState.readOnly) return
        selectedTool = tool
        inputView.tool = tool
        inputView.visibility = View.VISIBLE
        dryInkView.eraserPreview = null
    }

    private fun selectPenColor(colorArgb: Int) {
        selectedPenColor = colorArgb
        selectedTool = ReaderTool.PEN
        inputView.penColorArgb = colorArgb
        inputView.tool = ReaderTool.PEN
    }

    private fun selectPenWidth(widthDp: Float) {
        selectedPenWidthDp = widthDp
        selectedTool = ReaderTool.PEN
        inputView.penWidthDp = widthDp
        inputView.tool = ReaderTool.PEN
    }

    private fun selectPenOpacity(opacity: Float) {
        selectedPenOpacity = opacity.coerceIn(0.15f, 1f)
        selectedTool = ReaderTool.PEN
        inputView.penOpacity = selectedPenOpacity
        inputView.tool = ReaderTool.PEN
    }

    private fun showPage(pageNumber: Int) {
        viewModel.flushAsync()
        val target = pageNumber.coerceIn(0, (loadedPageCount - 1).coerceAtLeast(0))
        currentPage = target
        ReaderRemoteBridge.sink?.onPageChanged(target)
        dryInkView.activePage = target
        viewport.showPage(target)
        viewModel.onPageSelected(target)
        reviewSession?.takeIf { it.review.status == com.studyink.core.model.ReviewStatus.DRAFT }
            ?.pages?.firstOrNull { it.pageNumber == target }
            ?.let { page ->
                lifecycleScope.launch {
                    runCatching { reviewRepository().updateReviewResumePage(page.reviewId, page.pageId) }
                }
            }
        refreshReviewPane()
    }

    private fun refreshChrome() {
        topBar.setContent {
            TopReaderBar(
                state = latestState,
                onOpenPdf = if (launchArgs == null && readerScene == null) {
                    { openPdf.launch(arrayOf("application/pdf")) }
                } else {
                    null
                },
                onSubmit = if (latestState.scene == null && latestState.attemptSession != null && !latestState.readOnly) {
                    {
                        viewModel.submit { submissionId ->
                            setResult(
                                RESULT_OK,
                                android.content.Intent().putExtra("submissionId", submissionId.value),
                            )
                            finish()
                        }
                    }
                } else {
                    null
                },
                onAnswer = if (latestState.scene?.requiresTeacherAccess() == true) ::openAnswerViewer else null,
            )
        }
        paletteAnchor.setContent {
            StylusToolMenu(
                expanded = stylusMenuExpanded,
                state = latestState,
                selectedTool = selectedTool,
                selectedColorArgb = selectedPenColor,
                selectedWidthDp = selectedPenWidthDp,
                selectedOpacity = selectedPenOpacity,
                currentPage = currentPage,
                pageCount = loadedPageCount,
                onSelectTool = ::selectTool,
                onSelectColor = ::selectPenColor,
                onSelectWidth = ::selectPenWidth,
                onSelectOpacity = ::selectPenOpacity,
                onPreviousPage = { showPage(currentPage - 1) },
                onNextPage = { showPage(currentPage + 1) },
                onResetZoom = viewport::resetZoom,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onDismissRequest = { stylusMenuExpanded = false },
            )
        }
        refreshReviewPane()
    }

    private fun openAnswerViewer() {
        val revision = latestState.scene?.documentRevisionId?.value
            ?: latestState.attemptSession?.attempt?.revisionId?.value
            ?: return
        val activity = latestState.attemptSession?.attempt?.activityId?.value
        val page = latestState.currentPageId?.value ?: latestState.scene?.initialPageId?.value
        val target = android.content.Intent().setClassName(packageName, "com.studyink.teacher.AnswerViewerActivity")
            .putExtra("com.studyink.answer.REVISION", revision)
            .putExtra("com.studyink.answer.PAGE", page)
            .putExtra("com.studyink.answer.TEACHER", com.studyink.annotation.storage.RoomTeacherRepository.DEFAULT_TEACHER_ID)
        activity?.let { target.putExtra("com.studyink.answer.ACTIVITY", it) }
        startActivity(target)
    }

    private fun setupReviewPane(root: FrameLayout, fragmentContainer: View) {
        val reviewId = readerScene?.reviewIdOrNull() ?: return
        val expanded = resources.configuration.screenWidthDp >= 720
        reviewExpanded = expanded
        reviewPane = ComposeView(this).also { pane ->
            pane.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            root.addView(
                pane,
                FrameLayout.LayoutParams(
                    if (expanded) dp(320) else MATCH,
                    if (expanded) MATCH else dp(250),
                    if (expanded) Gravity.END else Gravity.BOTTOM,
                ),
            )
        }
        listOf(fragmentContainer, dryInkView, wetInkView, inputView).forEach { content ->
            content.updateFrameLayoutParams {
                if (expanded) rightMargin = dp(320) else bottomMargin = dp(250)
            }
        }
        lifecycleScope.launch {
            runCatching { reviewRepository().getReview(reviewId) }
                .onSuccess {
                    reviewSession = it
                    reviewSummary = it.review.summaryText
                }
                .onFailure { reviewError = it.message }
            refreshReviewPane()
        }
    }

    private fun refreshReviewPane() {
        reviewPane?.setContent {
            TeacherReviewSupportingPane(
                session = reviewSession,
                currentPage = currentPage,
                summary = reviewSummary,
                layerSources = latestState.scene?.visibleLayerSources.orEmpty(),
                layerVisibility = latestState.layerVisibility,
                publishing = reviewPublishing,
                error = reviewError,
                onSummaryChange = ::updateReviewSummary,
                onToggleLayer = viewModel::setLayerVisibility,
                onTogglePageChecked = ::toggleCurrentReviewPage,
                onEvaluate = ::evaluateAnswer,
                onPublish = ::publishReview,
            )
        }
    }

    private fun updateReviewSummary(text: String) {
        val session = reviewSession ?: return
        if (session.review.status != com.studyink.core.model.ReviewStatus.DRAFT) return
        reviewSummary = text
        reviewSaveJob?.cancel()
        reviewSaveJob = lifecycleScope.launch {
            delay(500)
            runCatching { reviewRepository().updateSummary(session.review.reviewId, text) }
                .onFailure { reviewError = it.message }
        }
    }

    private fun toggleCurrentReviewPage(checked: Boolean) {
        val session = reviewSession ?: return
        val page = session.pages.firstOrNull { it.pageNumber == currentPage } ?: return
        lifecycleScope.launch {
            runCatching { reviewRepository().markPageChecked(session.review.reviewId, page.pageId, checked) }
                .onSuccess { reviewSession = reviewRepository().getReview(session.review.reviewId) }
                .onFailure { reviewError = it.message }
            refreshReviewPane()
        }
    }

    private fun evaluateAnswer(fieldId: String, verdict: com.studyink.core.model.AnswerVerdict) {
        val session = reviewSession ?: return
        lifecycleScope.launch {
            runCatching { reviewRepository().updateAnswerEvaluation(session.review.reviewId, fieldId, verdict, "") }
                .onFailure { reviewError = it.message }
        }
    }

    private fun publishReview(decision: ReviewDecision) {
        val session = reviewSession ?: return
        if (reviewPublishing) return
        reviewPublishing = true
        refreshReviewPane()
        lifecycleScope.launch {
            runCatching {
                viewModel.flush()
                reviewSaveJob?.join()
                reviewRepository().updateSummary(session.review.reviewId, reviewSummary)
                reviewRepository().publishReview(session.review.reviewId, decision)
            }.onSuccess {
                Toast.makeText(this@ReaderActivity, "검토를 게시했습니다", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            }.onFailure {
                reviewPublishing = false
                reviewError = it.message
                refreshReviewPane()
            }
        }
    }

    private suspend fun reviewRepository(): RoomTeacherRepository = reviewRepository
        ?: RoomTeacherRepository.open(this).also { reviewRepository = it }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (handleStylusButton(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (stylusMenuExpanded && event.isMenuDismissContact()) {
            stylusMenuExpanded = false
        }
        if (handleStylusButton(event)) return true
        return super.dispatchTouchEvent(event)
    }

    private fun MotionEvent.isMenuDismissContact(): Boolean {
        if (actionMasked != MotionEvent.ACTION_DOWN || pointerCount == 0) return false
        val buttonMask = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY
        if (buttonState and buttonMask != 0) return false
        return when (getToolType(actionIndex.coerceIn(0, pointerCount - 1))) {
            MotionEvent.TOOL_TYPE_FINGER,
            MotionEvent.TOOL_TYPE_STYLUS,
            MotionEvent.TOOL_TYPE_ERASER -> true
            else -> false
        }
    }

    private fun handleStylusButton(event: MotionEvent): Boolean {
        if (latestState.readOnly) return false
        val isStylus = event.isFromSource(InputDevice.SOURCE_STYLUS) ||
            (0 until event.pointerCount).any { index ->
                val type = event.getToolType(index)
                type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER
            }
        if (!isStylus) return false

        val buttonMask = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY
        val isPressed = event.buttonState and buttonMask != 0
        val isPressEvent = event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS &&
            event.actionButton and buttonMask != 0
        val isReleaseEvent = event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE

        if ((isPressed || isPressEvent) && !stylusButtonPressed) {
            stylusButtonPressed = true
            if (stylusMenuExpanded) stylusMenuExpanded = false
            else showStylusMenu(event.rawX, event.rawY)
            return true
        }
        if (isReleaseEvent || !isPressed) stylusButtonPressed = false
        return isPressEvent || isReleaseEvent
    }

    private fun showStylusMenu(rawX: Float, rawY: Float) {
        stylusMenuExpanded = false
        paletteAnchor.updateFrameLayoutParams {
            leftMargin = rawX.toInt().coerceIn(dp(8), (paletteAnchor.rootView.width - dp(8)).coerceAtLeast(dp(8)))
            topMargin = rawY.toInt().coerceIn(dp(TOP_BAR_HEIGHT), (paletteAnchor.rootView.height - dp(8)).coerceAtLeast(dp(TOP_BAR_HEIGHT)))
        }
        paletteAnchor.post { stylusMenuExpanded = true }
    }

    private fun handOffFinishedWetInk(strokes: Map<InProgressStrokeId, Stroke>) {
        // The dry snapshot is normally visible within one frame. A short overlap avoids a wet/dry gap.
        wetInkView.postDelayed({ wetInkView.removeFinishedStrokes(strokes.keys) }, 80L)
    }

    override fun onStop() {
        viewModel.flushAsync()
        super.onStop()
    }

    override fun onPause() {
        val session = reviewSession
        if (session?.review?.status == com.studyink.core.model.ReviewStatus.DRAFT) {
            reviewSaveJob?.cancel()
            reviewSaveJob = lifecycleScope.launch {
                runCatching { reviewRepository().updateSummary(session.review.reviewId, reviewSummary) }
            }
        }
        super.onPause()
    }

    override fun onDestroy() {
        val repository = reviewRepository
        val saveJob = reviewSaveJob
        if (repository != null && saveJob?.isActive == true) {
            saveJob.invokeOnCompletion { repository.close() }
        } else {
            repository?.close()
        }
        super.onDestroy()
    }

    private fun contentLayoutParams() = FrameLayout.LayoutParams(MATCH, MATCH).apply {
        topMargin = dp(TOP_BAR_HEIGHT)
    }

    private fun applySystemBarInsets(root: FrameLayout, fragmentContainer: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val bars: Insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            topBar.updateFrameLayoutParams {
                topMargin = bars.top
                height = dp(TOP_BAR_HEIGHT)
            }
            listOf(fragmentContainer, dryInkView, wetInkView, inputView).forEach { content ->
                content.updateFrameLayoutParams {
                    topMargin = bars.top + dp(TOP_BAR_HEIGHT)
                    bottomMargin = bars.bottom + if (reviewPane != null && !reviewExpanded) dp(250) else 0
                    rightMargin = if (reviewPane != null && reviewExpanded) dp(320) else 0
                }
            }
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private inline fun View.updateFrameLayoutParams(block: FrameLayout.LayoutParams.() -> Unit) {
        layoutParams = (layoutParams as FrameLayout.LayoutParams).apply(block)
    }

    private fun ensureSamplePdf(): File {
        return SampleLearningContent.ensurePdf(this)
    }

    private fun documentLabel(uri: Uri): String {
        if (uri.scheme == "content") {
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) return cursor.getString(0)
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "PDF 문서"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PDF_FRAGMENT_TAG = "reader-pdf"
        private const val PDF_CONTAINER_ID = 0x5100
        private const val TOP_BAR_HEIGHT = 52
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}

private fun ReaderScene.reviewIdOrNull(): ReviewId? = visibleLayerSources.firstNotNullOfOrNull { source ->
    when (source) {
        is EditableLiveLayer -> (source.target as? LiveLayerTarget.TeacherFeedback)?.reviewId
        is ReadOnlySnapshot -> (source.target as? SnapshotTarget.PublishedReview)?.reviewId
        else -> null
    }
}
