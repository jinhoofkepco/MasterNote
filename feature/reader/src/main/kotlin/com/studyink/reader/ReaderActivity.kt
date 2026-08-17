package com.studyink.reader

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.strokes.Stroke
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.studyink.core.model.MarkColor
import com.studyink.core.model.PagePoint
import com.studyink.document.pdf.PdfViewportAdapter
import com.studyink.document.pdf.ReaderPdfFragment
import com.studyink.library.data.LibraryRepository
import kotlinx.coroutines.launch

class ReaderActivity : FragmentActivity(), ReaderPdfFragment.Listener {
    private val viewModel: ReaderViewModel by viewModels()
    private val viewport = PdfViewportAdapter()
    private lateinit var dryInkView: DryInkView
    private lateinit var wetInkView: InProgressStrokesView
    private lateinit var inputView: InkInputView
    private lateinit var pdfFragment: ReaderPdfFragment
    private lateinit var topChrome: ComposeView
    private lateinit var paletteAnchor: ComposeView
    private lateinit var bookId: String
    private lateinit var teacherAccess: TeacherAccessController

    private var selectedTool by mutableStateOf(ReaderTool.PEN)
    private var selectedPenColor by mutableStateOf(0xFF17233C.toInt())
    private var selectedPenWidthDp by mutableStateOf(3.2f)
    private var selectedPenOpacity by mutableStateOf(1f)
    private var latestState by mutableStateOf(ReaderUiState())
    private var currentPage by mutableStateOf(0)
    private var loadedPageCount by mutableStateOf(1)
    private var role by mutableStateOf(ReaderRole.STUDENT)
    private var stylusMenuExpanded by mutableStateOf(false)
    private var topMenuExpanded by mutableStateOf(false)
    private var pinDialogVisible by mutableStateOf(false)
    private var selectedMarkGroupId by mutableStateOf<String?>(null)
    private var movingMarkGroupId: String? = null
    private var requestedTeacherRole: ReaderRole? = null
    private var stylusButtonPressed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: run { finish(); return }
        currentPage = intent.getIntExtra(EXTRA_PAGE_NUMBER, 0)
        teacherAccess = TeacherAccessController(this)
        val requestedRole = intent.getStringExtra(EXTRA_ROLE)
            ?.let { runCatching { ReaderRole.valueOf(it) }.getOrNull() }
            ?: ReaderRole.STUDENT
        if (requestedRole != ReaderRole.STUDENT && !teacherAccess.isSessionAuthenticated()) {
            role = ReaderRole.STUDENT
            requestedTeacherRole = requestedRole
            pinDialogVisible = true
        } else {
            role = requestedRole
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(225, 226, 231)) }
        setContentView(root)
        val fragmentContainer = FragmentContainerView(this).apply { id = PDF_CONTAINER_ID }
        root.addView(fragmentContainer, FrameLayout.LayoutParams(MATCH, MATCH))

        dryInkView = DryInkView(this).also {
            it.viewport = viewport
            root.addView(it, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        viewport.onViewportChanged = { dryInkView.postInvalidateOnAnimation() }
        wetInkView = InProgressStrokesView(this).also {
            it.eagerInit()
            it.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
                override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                    wetInkView.postDelayed({ wetInkView.removeFinishedStrokes(strokes.keys) }, 80L)
                }
            })
            root.addView(it, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        inputView = InkInputView(this).also { input ->
            input.viewport = viewport
            input.wetInkView = wetInkView
            input.tool = selectedTool
            input.penColorArgb = selectedPenColor
            input.penWidthDp = selectedPenWidthDp
            input.penOpacity = selectedPenOpacity
            input.onStylusContact = {
                if (stylusMenuExpanded) {
                    stylusMenuExpanded = false
                    dryInkView.eraserPreview = null
                }
            }
            input.onStroke = { stroke -> viewModel.addStroke(stroke) }
            input.onEraserPreview = { preview -> dryInkView.eraserPreview = preview }
            input.onHoverPreview = { preview -> dryInkView.hoverPreview = preview }
            input.onErase = { page, path, radius, whole ->
                viewModel.erase(page, path, radius, whole) { dryInkView.eraserPreview = null }
            }
            input.onTeacherTap =(::handleTeacherTap)
            input.onTeacherLongPress =(::handleTeacherLongPress)
            input.findScrollableMarkGroup = dryInkView::scrollableMarkGroupAt
            input.onDragMarkHistory = dryInkView::dragMarkHistory
            input.onEndMarkHistoryDrag = dryInkView::endMarkHistoryDrag
            root.addView(input, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        topChrome = ComposeView(this).also { composeView ->
            composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            root.addView(composeView, FrameLayout.LayoutParams(MATCH, dp(TOP_CHROME_HEIGHT), Gravity.TOP))
        }
        paletteAnchor = ComposeView(this).also { composeView ->
            composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            root.addView(composeView, FrameLayout.LayoutParams(dp(1), dp(1), Gravity.TOP or Gravity.START))
        }
        applySystemBarInsets(root)
        renderChrome()

        pdfFragment = supportFragmentManager.findFragmentByTag(PDF_FRAGMENT_TAG) as? ReaderPdfFragment
            ?: ReaderPdfFragment().also { fragment ->
                supportFragmentManager.beginTransaction().replace(PDF_CONTAINER_ID, fragment, PDF_FRAGMENT_TAG).commitNow()
            }
        pdfFragment.listener = this

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    latestState = state
                    currentPage = state.pageNumber
                    loadedPageCount = state.pageCount
                    dryInkView.snapshot = state.snapshot
                    dryInkView.activePage = state.pageNumber
                    dryInkView.visibleAttemptNo = state.attemptNo
                    dryInkView.showTeacherDrafts = state.role != ReaderRole.STUDENT
                    dryInkView.markGroups = state.marks
                    inputView.teacherTapEnabled = state.capabilities.canGrade
                    inputView.isEnabled = state.capabilities.canWrite && state.storageAvailable
                }
            }
        }

        if (savedInstanceState == null) {
            val book = LibraryRepository.get(this).book(bookId)
            pdfFragment.documentUri = Uri.fromFile(LibraryRepository.get(this).pdfFile(book))
        }
    }

    override fun onPdfViewReady(view: androidx.pdf.view.PdfView) {
        viewport.attach(view)
        dryInkView.invalidate()
    }

    override fun onDocumentReady(uri: Uri, pageWidths: Map<Int, Float>, pageCount: Int) {
        viewport.setPageWidths(pageWidths)
        loadedPageCount = pageCount
        showPage(currentPage)
    }

    override fun onDocumentError(error: Throwable) {
        viewModel.reportDocumentError()
    }

    private fun showPage(pageNumber: Int, attemptNo: Int? = null) {
        val target = pageNumber.coerceIn(0, (loadedPageCount - 1).coerceAtLeast(0))
        currentPage = target
        dryInkView.activePage = target
        viewport.showPage(target)
        viewModel.openBook(bookId, target, role, attemptNo)
    }

    private fun submitCurrentPage() {
        viewModel.submit { nextPage ->
            topMenuExpanded = false
            showPage(nextPage)
        }
    }

    private fun toggleTeacherMode() {
        if (role != ReaderRole.STUDENT) {
            role = ReaderRole.STUDENT
            TeacherAccessController.invalidateSession()
            showPage(currentPage)
            return
        }
        if (teacherAccess.isSessionAuthenticated()) {
            role = ReaderRole.TEACHER_TABLET
            showPage(currentPage, latestAttemptForPage())
        } else {
            requestedTeacherRole = ReaderRole.TEACHER_TABLET
            pinDialogVisible = true
        }
    }

    private fun latestAttemptForPage(): Int? = LibraryRepository.get(this)
        .attempts(bookId, currentPage).maxOfOrNull { it.attemptNo }

    private fun changeAttempt(delta: Int) {
        val attempts = LibraryRepository.get(this).attempts(bookId, currentPage).map { it.attemptNo }
        val index = attempts.indexOf(latestState.attemptNo)
        val next = (index + delta).coerceIn(0, attempts.lastIndex)
        if (next in attempts.indices) viewModel.selectAttempt(attempts[next])
    }

    private fun handleTeacherTap(page: Int, point: PagePoint, tapCount: Int) {
        if (!latestState.capabilities.canGrade || page != currentPage) return
        movingMarkGroupId?.let { groupId ->
            viewModel.moveMarkGroup(groupId, point)
            movingMarkGroupId = null
            return
        }
        val threshold = viewport.viewWidthToCanonical(page, dp(28f))
        val group = latestState.marks.minByOrNull { mark ->
            kotlin.math.hypot(mark.anchor.x - point.x, mark.anchor.y - point.y)
        }?.takeIf { mark -> kotlin.math.hypot(mark.anchor.x - point.x, mark.anchor.y - point.y) <= threshold }
        viewModel.addGrade(point, if (tapCount >= 2) MarkColor.RED else MarkColor.BLUE, group?.id)
    }

    private fun handleTeacherLongPress(page: Int, point: PagePoint) {
        if (!latestState.capabilities.canGrade || page != currentPage) return
        val threshold = viewport.viewWidthToCanonical(page, dp(32f))
        selectedMarkGroupId = latestState.marks.minByOrNull { mark ->
            kotlin.math.hypot(mark.anchor.x - point.x, mark.anchor.y - point.y)
        }?.takeIf { mark -> kotlin.math.hypot(mark.anchor.x - point.x, mark.anchor.y - point.y) <= threshold }?.id
    }

    private fun selectTool(tool: ReaderTool) {
        selectedTool = tool
        inputView.tool = tool
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

    private fun renderChrome() {
        topChrome.setContent {
            ReaderTopChrome(
                state = latestState,
                expanded = topMenuExpanded,
                onToggleExpanded = { topMenuExpanded = !topMenuExpanded },
                onPrevious = { showPage(currentPage - 1) },
                onNext = { showPage(currentPage + 1) },
                onExitToLibrary = { finish() },
                onSubmit =(::submitCurrentPage),
                onTeacherMode =(::toggleTeacherMode),
                onPreviousAttempt = { changeAttempt(-1) },
                onNextAttempt = { changeAttempt(1) },
                onPublish = { viewModel.publishTeacherInk() },
                onDismissDataError = viewModel::dismissDataError,
            )
            if (pinDialogVisible) {
                TeacherPinDialog(
                    setup = !teacherAccess.hasPin,
                    onCancel = { pinDialogVisible = false; requestedTeacherRole = null },
                    onConfirm = { pin, remember ->
                        val valid = if (!teacherAccess.hasPin) {
                            teacherAccess.setPin(pin) && teacherAccess.verify(pin, remember)
                        } else teacherAccess.verify(pin, remember)
                        if (valid) {
                            pinDialogVisible = false
                            role = requestedTeacherRole ?: ReaderRole.TEACHER_TABLET
                            requestedTeacherRole = null
                            showPage(currentPage, latestAttemptForPage())
                        }
                        valid
                    },
                )
            }
            selectedMarkGroupId?.let { groupId ->
                MarkEditDialog(
                    onBlue = { viewModel.changeGrade(groupId, MarkColor.BLUE); selectedMarkGroupId = null },
                    onRed = { viewModel.changeGrade(groupId, MarkColor.RED); selectedMarkGroupId = null },
                    onMove = { movingMarkGroupId = groupId; selectedMarkGroupId = null },
                    onHide = { viewModel.hideMarkGroup(groupId); selectedMarkGroupId = null },
                    onCancel = { selectedMarkGroupId = null },
                )
            }
        }
        paletteAnchor.setContent {
            StylusToolMenu(
                expanded = stylusMenuExpanded,
                state = latestState,
                selectedTool = selectedTool,
                selectedColorArgb = selectedPenColor,
                selectedWidthDp = selectedPenWidthDp,
                selectedOpacity = selectedPenOpacity,
                onSelectTool =(::selectTool),
                onSelectColor =(::selectPenColor),
                onSelectWidth =(::selectPenWidth),
                onSelectOpacity =(::selectPenOpacity),
                onResetZoom = viewport::resetZoom,
                onUndo = viewModel::undo,
                onRedo = viewModel::redo,
                onDismissRequest = { stylusMenuExpanded = false },
            )
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (handleStylusButton(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (stylusMenuExpanded && event.isStylusMenuDismissContact()) stylusMenuExpanded = false
        if (handleStylusButton(event)) return true
        return super.dispatchTouchEvent(event)
    }

    private fun MotionEvent.isStylusMenuDismissContact(): Boolean {
        if (actionMasked != MotionEvent.ACTION_DOWN || pointerCount == 0) return false
        val type = getToolType(actionIndex.coerceIn(0, pointerCount - 1))
        return type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun handleStylusButton(event: MotionEvent): Boolean {
        val isStylus = event.isFromSource(InputDevice.SOURCE_STYLUS) || (0 until event.pointerCount).any { index ->
            event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER
        }
        if (!isStylus) return false
        val mask = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY
        val isPressed = event.buttonState and mask != 0
        val pressEvent = event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS && event.actionButton and mask != 0
        val releaseEvent = event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE
        if ((isPressed || pressEvent) && !stylusButtonPressed) {
            stylusButtonPressed = true
            if (stylusMenuExpanded) stylusMenuExpanded = false else showStylusMenu(event.rawX, event.rawY)
            return true
        }
        if (releaseEvent || !isPressed) stylusButtonPressed = false
        return pressEvent || releaseEvent
    }

    private fun showStylusMenu(rawX: Float, rawY: Float) {
        stylusMenuExpanded = false
        paletteAnchor.updateFrameLayoutParams {
            leftMargin = rawX.toInt().coerceIn(dp(8), (paletteAnchor.rootView.width - dp(8)).coerceAtLeast(dp(8)))
            topMargin = rawY.toInt().coerceIn(dp(8), (paletteAnchor.rootView.height - dp(8)).coerceAtLeast(dp(8)))
        }
        paletteAnchor.post { stylusMenuExpanded = true }
    }

    private fun applySystemBarInsets(root: FrameLayout) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val bars: Insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            topChrome.updateFrameLayoutParams { topMargin = bars.top }
            listOf(dryInkView, wetInkView, inputView).forEach { view ->
                view.updateFrameLayoutParams { bottomMargin = bars.bottom }
            }
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private inline fun View.updateFrameLayoutParams(block: FrameLayout.LayoutParams.() -> Unit) {
        layoutParams = (layoutParams as FrameLayout.LayoutParams).apply(block)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        private const val PDF_FRAGMENT_TAG = "reader-pdf"
        private const val PDF_CONTAINER_ID = 0x5100
        private const val TOP_CHROME_HEIGHT = 64
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val EXTRA_BOOK_ID = "bookId"
        private const val EXTRA_PAGE_NUMBER = "pageNumber"
        private const val EXTRA_ROLE = "role"

        fun intent(context: Context, bookId: String, pageNumber: Int, role: ReaderRole = ReaderRole.STUDENT) =
            Intent(context, ReaderActivity::class.java)
                .putExtra(EXTRA_BOOK_ID, bookId)
                .putExtra(EXTRA_PAGE_NUMBER, pageNumber)
                .putExtra(EXTRA_ROLE, role.name)
    }
}
