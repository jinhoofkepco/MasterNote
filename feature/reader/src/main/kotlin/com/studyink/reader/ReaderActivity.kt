package com.studyink.reader

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
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
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class ReaderActivity : FragmentActivity(), ReaderPdfFragment.Listener {
    private val viewModel: ReaderViewModel by viewModels()
    private val viewport = PdfViewportAdapter()
    private lateinit var dryInkView: DryInkView
    private lateinit var wetInkView: InProgressStrokesView
    private lateinit var inputView: InkInputView
    private lateinit var pdfFragment: ReaderPdfFragment
    private lateinit var topBar: ComposeView
    private lateinit var paletteAnchor: ComposeView
    private var selectedTool by mutableStateOf(ReaderTool.PEN)
    private var selectedPenColor by mutableStateOf(0xFF17233C.toInt())
    private var selectedPenWidthDp by mutableStateOf(3.2f)
    private var selectedPenOpacity by mutableStateOf(1f)
    private var latestState by mutableStateOf(ReaderUiState())
    private var currentPage by mutableStateOf(0)
    private var loadedPageCount by mutableStateOf(1)
    private var stylusMenuExpanded by mutableStateOf(false)
    private var stylusButtonPressed = false
    private var pendingUri: Uri? = null

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
        viewport.onViewportChanged = { dryInkView.postInvalidateOnAnimation() }
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
                    selectedTool = ReaderTool.PEN
                    it.tool = ReaderTool.PEN
                    dryInkView.eraserPreview = null
                }
            }
            it.onStroke = { stroke -> viewModel.addStroke(stroke) }
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
        applySystemBarInsets(root, fragmentContainer)
        refreshChrome()

        pdfFragment = supportFragmentManager.findFragmentByTag(PDF_FRAGMENT_TAG) as? ReaderPdfFragment
            ?: ReaderPdfFragment().also { fragment ->
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
                }
            }
        }

        if (savedInstanceState == null) showDocument(Uri.fromFile(ensureSamplePdf()))
    }

    override fun onPdfViewReady(view: androidx.pdf.view.PdfView) {
        viewport.attach(view)
        dryInkView.invalidate()
    }

    override fun onDocumentReady(uri: Uri, pageWidths: Map<Int, Float>, pageCount: Int) {
        viewport.setPageWidths(pageWidths)
        loadedPageCount = pageCount
        currentPage = 0
        dryInkView.activePage = 0
        viewport.showPage(0)
        val label = uri.lastPathSegment?.substringAfterLast('/') ?: "PDF 문서"
        viewModel.loadDocument(documentId(uri), label, pageCount)
        dryInkView.invalidate()
        Toast.makeText(this, "$pageCount 페이지를 열었습니다", Toast.LENGTH_SHORT).show()
    }

    override fun onDocumentError(error: Throwable) {
        Toast.makeText(this, "PDF를 열 수 없습니다: ${error.message}", Toast.LENGTH_LONG).show()
    }

    private fun showDocument(uri: Uri) {
        pendingUri = uri
        pdfFragment.documentUri = uri
    }

    private fun selectTool(tool: ReaderTool) {
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
        val target = pageNumber.coerceIn(0, (loadedPageCount - 1).coerceAtLeast(0))
        currentPage = target
        dryInkView.activePage = target
        viewport.showPage(target)
    }

    private fun refreshChrome() {
        topBar.setContent {
            TopReaderBar(
                state = latestState,
                onOpenPdf = { openPdf.launch(arrayOf("application/pdf")) },
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
                onDismissRequest = {},
            )
        }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (handleStylusButton(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (stylusMenuExpanded && event.isPageStylusDown()) {
            stylusMenuExpanded = false
            selectTool(ReaderTool.PEN)
        }
        if (handleStylusButton(event)) return true
        return super.dispatchTouchEvent(event)
    }

    private fun MotionEvent.isPageStylusDown(): Boolean {
        if (actionMasked != MotionEvent.ACTION_DOWN || pointerCount == 0) return false
        val buttonMask = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY
        if (buttonState and buttonMask != 0) return false
        val index = actionIndex.coerceIn(0, pointerCount - 1)
        val type = getToolType(index)
        return type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun handleStylusButton(event: MotionEvent): Boolean {
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
                    bottomMargin = bars.bottom
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
        val file = File(filesDir, "study-ink-sample.pdf")
        if (file.exists() && file.length() > 0L) return file
        val document = PdfDocument()
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(25, 39, 70); textSize = 34f }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(55, 62, 77); textSize = 20f }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(205, 210, 220); strokeWidth = 2f }
        repeat(3) { index ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(840, 1188, index + 1).create())
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)
            canvas.drawText("Study Ink Practice  ${index + 1}", 72f, 100f, titlePaint)
            canvas.drawText("Write an answer with the pen. Try both erasers, undo, zoom, and reopen the app.", 72f, 150f, textPaint)
            canvas.drawText("${index + 3} + ${index + 5} =", 96f, 260f, titlePaint)
            for (line in 0 until 12) {
                val y = 360f + line * 58f
                canvas.drawLine(72f, y, 768f, y, linePaint)
            }
            canvas.drawText("Page ${index + 1} / 3", 650f, 1120f, textPaint)
            document.finishPage(page)
        }
        FileOutputStream(file).use(document::writeTo)
        document.close()
        return file
    }

    private fun documentId(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PDF_FRAGMENT_TAG = "reader-pdf"
        private const val PDF_CONTAINER_ID = 0x5100
        private const val TOP_BAR_HEIGHT = 52
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
