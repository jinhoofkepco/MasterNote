package com.studyink.reader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
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
import com.studyink.assistant.core.AssistantPageKey
import com.studyink.assistant.core.AssistantRepositoryProvider
import com.studyink.assistant.core.StudentExplanationLayer
import com.studyink.assistant.core.StudentExplanationLayerBus
import com.studyink.assistant.core.StudentExplanationTarget
import com.studyink.core.model.AnswerPdfCrop
import com.studyink.core.model.MarkColor
import com.studyink.core.model.PageBounds
import com.studyink.core.model.PagePoint
import com.studyink.core.model.TEACHER_PAGE_REVIEW_ATTEMPT_NO
import com.studyink.document.pdf.PdfViewportAdapter
import com.studyink.document.pdf.ReaderPdfFragment
import com.studyink.library.data.LibraryRepository
import com.studyink.monitor.core.ParentMessage
import com.studyink.monitor.core.RemotePeerChatDirection
import com.studyink.monitor.core.RemotePeerChatState
import com.studyink.monitor.core.RemotePeerChatStateBus
import com.studyink.monitor.core.StudentStudyPresence
import com.studyink.monitor.core.StudentStudyPresenceBus
import com.studyink.monitor.core.StudentWorkHeartbeat
import com.studyink.monitor.core.StudentWorkHeartbeatBus
import com.studyink.monitor.core.StudentWorkKind
import com.studyink.monitor.telegram.RemoteMonitorGateway
import com.studyink.monitor.telegram.RemoteMonitorPreferences
import com.studyink.monitor.telegram.RemoteReviewPeerStatus
import com.studyink.monitor.telegram.TelegramEnqueueResult
import com.studyink.sync.lan.LanPeerRole
import com.studyink.sync.lan.LanSyncBus
import com.studyink.sync.lan.LanSyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class ReaderActivity : FragmentActivity(), ReaderPdfFragment.Listener {
    private val viewModel: ReaderViewModel by viewModels()
    private val viewport = PdfViewportAdapter()
    private val assistantRepository by lazy { AssistantRepositoryProvider.get(this) }
    private lateinit var rootHost: FrameLayout
    private lateinit var pdfContainer: FragmentContainerView
    private lateinit var dryInkView: DryInkView
    private lateinit var wetInkView: InProgressStrokesView
    private lateinit var inputView: InkInputView
    private lateinit var pdfFragment: ReaderPdfFragment
    private lateinit var topChrome: ComposeView
    private lateinit var stylusMenuOverlay: StylusMenuOverlayView
    private lateinit var pageRegionSelectionView: PageRegionSelectionView
    private lateinit var studentExplanationOverlay: StudentExplanationOverlayView
    private lateinit var messageOverlayHost: LinearLayout
    private lateinit var parentMessageOverlay: ParentMessageOverlayView
    private lateinit var studentStatusOverlay: ParentMessageOverlayView
    private lateinit var parentMessageSpeaker: ParentMessageSpeaker
    private lateinit var studentVoiceController: StudentVoiceMessageController
    private lateinit var remoteMonitorGateway: RemoteMonitorGateway
    private lateinit var peerChatAnnouncementStore: PeerChatAnnouncementStore
    private lateinit var bookId: String
    private lateinit var teacherAccess: TeacherAccessController
    private val answerCropLoaderDelegate = lazy { AnswerCropBitmapLoader.get(this) }
    private val answerCropLoader: AnswerCropBitmapLoader get() = answerCropLoaderDelegate.value

    private val answerPdfLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val target = launchedAnswerTarget.also { launchedAnswerTarget = null } ?: return@registerForActivityResult
        if (isFinishing || latestState.role == ReaderRole.STUDENT ||
            target.first != latestState.bookId || target.second != latestState.pageNumber
        ) {
            return@registerForActivityResult
        }
        val crop = runCatching {
            LibraryRepository.get(this).answerCropForProblem(target.first, target.second)
        }.getOrNull() ?: return@registerForActivityResult
        showAnswerCropPopup(target.first, target.second, crop)
    }

    private val gptAssistantLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK || isFinishing) return@registerForActivityResult
        val savedBookId = result.data?.getStringExtra(GptAssistantActivity.EXTRA_SAVED_BOOK_ID)
        val savedPageNumber = result.data?.getIntExtra(
            GptAssistantActivity.EXTRA_SAVED_PAGE_NUMBER,
            -1,
        ) ?: -1
        if (savedBookId == latestState.bookId && savedPageNumber == latestState.pageNumber) {
            openTeacherGptResources()
        } else if (savedPageNumber >= 0) {
            Toast.makeText(
                this,
                "GPT 답변은 ${savedPageNumber + 1}쪽에 저장했습니다.",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private val studentExplanationListener = object : StudentExplanationLayerBus.Listener {
        override fun onRemoteLayerApplied(layer: StudentExplanationLayer) {
            runOnUiThread {
                if (readerStarted && layer.target == currentStudentExplanationTarget(latestState)) {
                    refreshStudentExplanationLayer(force = true)
                    studentStatusOverlay.showMessage("선생님 설명이 도착했어요.")
                }
            }
        }
    }

    private var selectedTool by mutableStateOf(ReaderTool.PEN)
    private var selectedPenColor by mutableStateOf(0xFF17233C.toInt())
    private var selectedPenWidthDp by mutableStateOf(3.2f)
    private var selectedPenOpacity by mutableStateOf(1f)
    private var latestState by mutableStateOf(ReaderUiState())
    private var initialPage = 0
    private var initialAttemptNo: Int? = null
    private var initialFollowRemoteStudent = false
    private var workflow = ReaderWorkflow.STUDY
    private var role by mutableStateOf(ReaderRole.STUDENT)
    private var stylusMenuExpanded by mutableStateOf(false)
    private var topMenuExpanded by mutableStateOf(false)
    private var s23StripInitialExpansionApplied = false
    private var pinDialogVisible by mutableStateOf(false)
    private var selectedMarkGroupId by mutableStateOf<String?>(null)
    private var selectedMarkTarget: ReaderAnnotationTarget? = null
    private var pendingMarkMove: PendingMarkMove? = null
    private var requestedTeacherRole: ReaderRole? = null
    private var requestedTeacherWorkflow: ReaderWorkflow? = null
    private var exitOnPinCancel = false
    private var stylusButtonPressed = false
    private var lastStylusButtonPressEventTime = Long.MIN_VALUE
    private var lastStylusRawPosition = Offset.Unspecified
    private var stylusMenuAnchorInHost by mutableStateOf(Offset.Zero)
    private var activeEraserPreviewId: Long? = null
    private val eraserTargets = mutableMapOf<Long, ReaderAnnotationTarget>()
    private var submittedBlurApplied = false
    private var studentActivityVisible by mutableStateOf(false)
    private var displayedPdfPage = -1
    private var remoteMonitorPreferences = RemoteMonitorPreferences()
    private var parentMessageSubscription: AutoCloseable? = null
    private var preferenceSubscription: AutoCloseable? = null
    private var peerChatSubscription: AutoCloseable? = null
    private var peerChatPrimed = false
    private var lastPeerChatMessageId: String? = null
    private val peerChatOverlayDeliveryGate = PeerChatOverlayDeliveryGate()
    private val peerChatAnnouncementMutex = Mutex()
    private var readerStarted = false
    private var readerResumed = false
    private var activeStudentPresence: StudentStudyPresence? = null
    private var lastStudentPageKey: Pair<String, Int>? = null
    private var voiceState = StudentVoiceState.OFF
    private var deferredParentSpeech: String? = null
    private var microphonePermissionWarningShown = false
    private var studentStatusIsProgress = false
    private var studentMessageExpectedChatId: Long? = null
    private var systemBarInsets: Insets = Insets.NONE
    private var teacherResourcesDialog: TeacherPageResourcesDialogController? = null
    private var pendingPromptChoice: TeacherPromptChoice? = null
    private var displayedExplanationTarget: StudentExplanationTarget? = null
    private var loadingExplanationTarget: StudentExplanationTarget? = null
    private var explanationLoadGeneration = 0L
    private var assistantCaptureInProgress = false
    private var answerCropPopup: AnswerCropPopupView? = null
    private var answerPopupTarget: Pair<String, Int>? = null
    private var answerPopupCrop: AnswerPdfCrop? = null
    private var answerPopupLoadGeneration = 0L
    private var answerPopupLoadJob: Job? = null
    private var answerPopupBackCallback: OnBackPressedCallback? = null
    private var launchedAnswerTarget: Pair<String, Int>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pruneStaleGptCaptures()
        bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: run { finish(); return }
        initialPage = intent.getIntExtra(EXTRA_PAGE_NUMBER, 0)
        initialAttemptNo = if (intent.hasExtra(EXTRA_ATTEMPT_NUMBER)) {
            intent.getIntExtra(EXTRA_ATTEMPT_NUMBER, 1).takeIf { it >= TEACHER_PAGE_REVIEW_ATTEMPT_NO }
        } else {
            null
        }
        teacherAccess = TeacherAccessController(this)
        remoteMonitorGateway = RemoteMonitorGateway.get(this)
        peerChatAnnouncementStore = PeerChatAnnouncementStore.get(this)
        val requestedRole = intent.getStringExtra(EXTRA_ROLE)
            ?.let { runCatching { ReaderRole.valueOf(it) }.getOrNull() }
            ?: ReaderRole.STUDENT
        workflow = intent.getStringExtra(EXTRA_WORKFLOW)
            ?.let { runCatching { ReaderWorkflow.valueOf(it) }.getOrNull() }
            ?: ReaderWorkflow.defaultFor(requestedRole)
        if (requestedRole == ReaderRole.STUDENT) workflow = ReaderWorkflow.STUDY
        initialFollowRemoteStudent = intent.getBooleanExtra(
            EXTRA_FOLLOW_REMOTE_STUDENT,
            workflow == ReaderWorkflow.LIVE_MONITOR,
        )
        if (requestedRole != ReaderRole.STUDENT && !teacherAccess.isSessionAuthenticated()) {
            role = ReaderRole.STUDENT
            requestedTeacherRole = requestedRole
            requestedTeacherWorkflow = workflow
            exitOnPinCancel = true
            pinDialogVisible = true
        } else {
            exitOnPinCancel = false
            role = requestedRole
        }
        ensureLanRoleForReader()
        ensureS23StripStartsExpanded()

        val readerBackgroundColor = ReaderPaperBackdropDrawable.NAVIGATION_BAR_COLOR
        rootHost = FrameLayout(this).apply {
            background = ReaderPaperBackdropDrawable(resources.displayMetrics.density)
        }
        val root = rootHost
        setContentView(root)
        @Suppress("DEPRECATION")
        window.navigationBarColor = readerBackgroundColor
        @Suppress("DEPRECATION")
        window.statusBarColor = readerBackgroundColor
        window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightNavigationBars = true
            isAppearanceLightStatusBars = true
        }
        val fragmentContainer = FragmentContainerView(this).apply { id = PDF_CONTAINER_ID }
        pdfContainer = fragmentContainer
        root.addView(fragmentContainer, FrameLayout.LayoutParams(MATCH, MATCH))

        dryInkView = DryInkView(this).also {
            it.viewport = viewport
            root.addView(it, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        viewport.onViewportChanged = {
            dryInkView.postInvalidateOnAnimation()
            if (::studentExplanationOverlay.isInitialized) {
                studentExplanationOverlay.setContentBoundsInView(viewport.activePageBounds())
                studentExplanationOverlay.notifyViewportChanged()
            }
            if (::pageRegionSelectionView.isInitialized &&
                pageRegionSelectionView.visibility == View.VISIBLE
            ) {
                pageRegionSelectionView.updateSelectionLimit(viewport.activePageBounds())
            }
        }
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
                    closeStylusMenu()
                    dryInkView.eraserPreview = null
                }
            }
            input.onWorkActivity = {
                publishStudentHeartbeat(StudentWorkKind.PEN_CONTACT)
            }
            input.onStroke = { stroke -> viewModel.addStroke(stroke) }
            input.canStartErase = { page -> canErase(state = latestState, page = page) }
            input.onEraserPreview = { preview ->
                if (preview == null) {
                    activeEraserPreviewId?.let(eraserTargets::remove)
                    activeEraserPreviewId = null
                    dryInkView.eraserPreview = null
                } else {
                    if (activeEraserPreviewId != preview.gestureId) {
                        activeEraserPreviewId = preview.gestureId
                        eraserTargets[preview.gestureId] = latestState.annotationTarget()
                    }
                    if (eraserTargets[preview.gestureId]?.matches(latestState) == true) {
                        dryInkView.eraserPreview = preview
                    }
                }
            }
            input.onHoverPreview = { preview -> dryInkView.hoverPreview = preview }
            input.onErase = { gesture ->
                val target = eraserTargets.remove(gesture.id)
                if (target == null) {
                    if (dryInkView.eraserPreview?.gestureId == gesture.id) {
                        dryInkView.eraserPreview = null
                    }
                } else {
                    viewModel.erase(
                        target = target,
                        page = gesture.page,
                        path = gesture.path,
                        radius = gesture.radius,
                        wholeStroke = gesture.whole,
                    ) {
                        runOnUiThread {
                            if (dryInkView.eraserPreview?.gestureId == gesture.id) {
                                dryInkView.eraserPreview = null
                            }
                            if (activeEraserPreviewId == gesture.id) activeEraserPreviewId = null
                        }
                    }
                    publishStudentHeartbeat(StudentWorkKind.ERASE_COMMIT)
                }
            }
            input.onGradeTap =(::handleGradeTap)
            input.onGradeLongPress =(::handleGradeLongPress)
            input.findMarkAttempt = { x, y ->
                if (latestState.capabilities.canBrowseAttempts) dryInkView.markedAttemptAt(x, y) else null
            }
            input.onOpenMarkedAttempt = viewModel::selectAttempt
            input.findScrollableMarkGroup = dryInkView::scrollableMarkGroupAt
            input.onDragMarkHistory = dryInkView::dragMarkHistory
            input.onEndMarkHistoryDrag = dryInkView::endMarkHistoryDrag
            root.addView(input, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        studentExplanationOverlay = StudentExplanationOverlayView(this).also { overlay ->
            overlay.viewportAdapter = viewport
            root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        messageOverlayHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = false
            isFocusable = false
        }
        root.addView(
            messageOverlayHost,
            FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply {
                marginStart = dp(PARENT_MESSAGE_SIDE_MARGIN_DP)
                marginEnd = dp(PARENT_MESSAGE_SIDE_MARGIN_DP)
            },
        )
        parentMessageOverlay = ParentMessageOverlayView(this).also { overlay ->
            overlay.maxWidth = dp(PARENT_MESSAGE_MAX_WIDTH_DP)
            messageOverlayHost.addView(overlay, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        studentStatusOverlay = ParentMessageOverlayView(this).also { overlay ->
            overlay.maxWidth = dp(PARENT_MESSAGE_MAX_WIDTH_DP)
            overlay.useStudentStatusStyle()
            messageOverlayHost.addView(
                overlay,
                LinearLayout.LayoutParams(WRAP, WRAP).apply {
                    topMargin = dp(MESSAGE_OVERLAY_GAP_DP)
                },
            )
        }
        root.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom != oldBottom) {
                updateMessageOverlayPosition(bottom)
                updateAnswerPopupBounds(restoreSavedPosition = false)
            }
        }
        messageOverlayHost.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateMessageOverlayPosition(root.height)
        }
        studentVoiceController = StudentVoiceMessageController(
            context = this,
            onTextReady =(::enqueueStudentText),
            onStateChanged =(::onStudentVoiceStateChanged),
            onError = { message -> runOnUiThread { showStudentStatusResult(message) } },
        )
        parentMessageSpeaker = ParentMessageSpeaker(this) { speaking ->
            studentVoiceController.setSuspended(speaking)
            if (!speaking) maybeSpeakDeferredParentMessage()
        }

        topChrome = ComposeView(this).also { composeView ->
            composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            root.addView(composeView, FrameLayout.LayoutParams(MATCH, dp(TOP_CHROME_HEIGHT), Gravity.TOP))
        }
        stylusMenuOverlay = StylusMenuOverlayView(this).also { overlay ->
            overlay.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            // Keep the full-screen Compose host completely out of Android's pointer dispatch
            // while the radial menu is closed. Returning false from a visible top sibling usually
            // lets FrameLayout try the views below, but hover-target transfer differs across
            // Samsung framework versions and a permanently visible host is unnecessary here.
            // INVISIBLE still participates in layout, so its size and screen origin remain ready
            // for the S Pen button anchor calculation.
            overlay.visibility = View.INVISIBLE
            root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        pageRegionSelectionView = PageRegionSelectionView(this).also { selector ->
            selector.onSelectionConfirmed =(::onGptRegionSelected)
            selector.onSelectionCancelled = {
                pendingPromptChoice = null
                updateReaderInputEnabled()
            }
            root.addView(selector, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        applySystemBarInsets(root, fragmentContainer)
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
                    dismissAnswerPopupIfTargetChanged(state)
                    deliverPendingParentMessageIfStudent()
                    ensureS23StripStartsExpanded()
                    activeEraserPreviewId?.takeIf { inputView.hasActiveEraserGesture }?.let { gestureId ->
                        val target = eraserTargets[gestureId]
                        if (target == null || !target.matches(state) || !canErase(state, target.pageNumber)) {
                            cancelActiveEraserInput()
                        }
                    }
                    if (
                        state.documentReady &&
                        state.pageCount > 0 &&
                        displayedPdfPage != state.pageNumber
                    ) {
                        viewport.showPage(state.pageNumber)
                        displayedPdfPage = state.pageNumber
                    }
                    if (selectedMarkGroupId != null && selectedMarkTarget?.matches(state) != true) {
                        clearMarkSelection()
                    }
                    if (pendingMarkMove?.target?.matches(state) == false) {
                        pendingMarkMove = null
                    }
                    dryInkView.snapshot = state.snapshot
                    dryInkView.activePage = state.pageNumber
                    dryInkView.visibleAttemptNo = state.attemptNo
                    dryInkView.showTeacherDrafts = state.role != ReaderRole.STUDENT
                    dryInkView.markGroups = state.marks
                    applySubmittedBlur(state.currentAttemptSubmitted)
                    if (!state.capabilities.canGrade && selectedTool == ReaderTool.GRADE) selectTool(ReaderTool.PEN)
                    pendingPromptChoice?.let { pending ->
                        if (pending.target.page.bookId != state.bookId ||
                            pending.target.page.pageNumber != state.pageNumber
                        ) {
                            pageRegionSelectionView.cancelSelection()
                        }
                    }
                    updateReaderInputEnabled()
                    refreshStudentExplanationLayer()
                    publishStudentPresence(state)
                    updateStudentVoiceEnabled()
                    ReaderDebugSessionStore.save(this@ReaderActivity, state)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.remoteFeedbackArrivals.collect { event ->
                    if (
                        latestState.role == ReaderRole.STUDENT &&
                        latestState.bookId == event.bookId &&
                        latestState.pageNumber == event.pageNumber
                    ) {
                        studentStatusOverlay.showMessage("선생님 첨삭이 도착했어요.")
                    }
                }
            }
        }

        if (savedInstanceState == null) {
            val book = LibraryRepository.get(this).book(bookId)
            pdfFragment.documentUri = Uri.fromFile(LibraryRepository.get(this).pdfFile(book))
        }
    }

    override fun onPdfViewReady(view: androidx.pdf.view.PdfView) {
        // The PDF pages remain fully opaque; only the unused viewport around them reveals paper.
        view.setBackgroundColor(Color.TRANSPARENT)
        viewport.attach(view)
        dryInkView.invalidate()
    }

    override fun onDocumentReady(uri: Uri, pageWidths: Map<Int, Float>, pageCount: Int) {
        viewport.setPageWidths(pageWidths)
        val target = initialPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        viewport.showPage(target)
        displayedPdfPage = target
        val targetAttempt = if (workflow == ReaderWorkflow.REVIEW && role != ReaderRole.STUDENT) {
            reviewableAttemptForPage(target, initialAttemptNo)
        } else {
            initialAttemptNo.takeUnless { role == ReaderRole.STUDENT }
        }
        viewModel.openBook(
            bookId = bookId,
            pageNumber = target,
            role = role,
            selectedAttemptNo = targetAttempt,
            confirmedPageCount = pageCount,
            workflow = if (role == ReaderRole.STUDENT) ReaderWorkflow.STUDY else workflow,
            followRemoteStudent = initialFollowRemoteStudent,
        )
    }

    override fun onDocumentError(error: Throwable) {
        viewModel.reportDocumentError()
    }

    private fun showPage(
        pageNumber: Int,
        attemptNo: Int? = null,
        // A local page request is manual browsing unless the caller explicitly resumes follow.
        // This fail-safe prevents a future navigation button from silently snapping back to the
        // retained student cursor just because the Reader workflow is LIVE_MONITOR.
        followRemoteStudent: Boolean = false,
    ) {
        val state = latestState
        if (!state.documentReady || state.pageCount <= 0) return
        val target = pageNumber.coerceIn(0, state.pageCount - 1)
        dryInkView.activePage = target
        viewport.showPage(target)
        displayedPdfPage = target
        val targetAttempt = attemptNo ?: if (workflow == ReaderWorkflow.REVIEW && role != ReaderRole.STUDENT) {
            reviewableAttemptForPage(target)
        } else {
            null
        }
        viewModel.openBook(
            bookId = bookId,
            pageNumber = target,
            role = role,
            selectedAttemptNo = targetAttempt,
            workflow = if (role == ReaderRole.STUDENT) ReaderWorkflow.STUDY else workflow,
            followRemoteStudent = followRemoteStudent,
        )
    }

    private fun submitCurrentPage() {
        // A stroke is not durable until ACTION_UP. Refuse the submit tap while InkInputView still
        // owns a gesture so the final points can never fall on the far side of the attempt lock.
        if (inputView.hasActiveGesture || latestState.submissionInProgress) return
        val submittedState = latestState
        viewModel.submit { nextPage ->
            publishStudentHeartbeat(StudentWorkKind.SUBMIT, submittedState)
            showPage(nextPage)
        }
    }

    private fun toggleTeacherMode() {
        if (role != ReaderRole.STUDENT) {
            role = ReaderRole.STUDENT
            workflow = ReaderWorkflow.STUDY
            TeacherAccessController.invalidateSession()
            showPage(latestState.pageNumber)
            return
        }
        if (teacherAccess.isSessionAuthenticated()) {
            role = ReaderRole.TEACHER_TABLET
            workflow = ReaderWorkflow.defaultFor(role)
            showPage(latestState.pageNumber)
        } else {
            requestedTeacherRole = ReaderRole.TEACHER_TABLET
            requestedTeacherWorkflow = ReaderWorkflow.defaultFor(ReaderRole.TEACHER_TABLET)
            exitOnPinCancel = false
            pinDialogVisible = true
        }
    }

    private fun reviewableAttemptForPage(
        pageNumber: Int = latestState.pageNumber,
        preferred: Int? = null,
    ): Int {
        val attempts = LibraryRepository.get(this).attempts(bookId, pageNumber)
        return preferred?.takeIf { candidate ->
            candidate == TEACHER_PAGE_REVIEW_ATTEMPT_NO ||
                attempts.any { it.attemptNo == candidate && it.locked }
        } ?: attempts.lastOrNull { it.locked }?.attemptNo
            ?: TEACHER_PAGE_REVIEW_ATTEMPT_NO
    }

    private fun changeAttempt(delta: Int) {
        val storedAttempts = LibraryRepository.get(this).attempts(bookId, latestState.pageNumber)
            .filter { workflow != ReaderWorkflow.REVIEW || it.locked }
            .map { it.attemptNo }
        val attempts = if (workflow == ReaderWorkflow.REVIEW && role != ReaderRole.STUDENT) {
            listOf(TEACHER_PAGE_REVIEW_ATTEMPT_NO) + storedAttempts
        } else {
            storedAttempts
        }
        if (attempts.isEmpty()) return
        val index = attempts.indexOf(latestState.attemptNo)
        if (index < 0) {
            viewModel.selectAttempt(attempts.last())
            return
        }
        val next = (index + delta).coerceIn(0, attempts.lastIndex)
        if (next in attempts.indices) viewModel.selectAttempt(attempts[next])
    }

    private fun handleGradeTap(page: Int, point: PagePoint, tapCount: Int, viewX: Float, viewY: Float) {
        if (!latestState.capabilities.canGrade || selectedTool != ReaderTool.GRADE || page != latestState.pageNumber) return
        pendingMarkMove?.let { move ->
            pendingMarkMove = null
            if (!move.canApply(latestState) || page != move.target.pageNumber) return
            viewModel.moveMarkGroup(move, point)
            return
        }
        viewModel.addGrade(
            point,
            if (tapCount >= 2) MarkColor.RED else MarkColor.BLUE,
            dryInkView.markGroupAt(viewX, viewY),
        )
    }

    private fun handleGradeLongPress(page: Int, point: PagePoint, viewX: Float, viewY: Float) {
        if (!latestState.capabilities.canGrade || selectedTool != ReaderTool.GRADE || page != latestState.pageNumber) return
        selectedMarkGroupId = dryInkView.markGroupAt(viewX, viewY)
        selectedMarkTarget = selectedMarkGroupId?.let { latestState.annotationTarget() }
        dryInkView.pressedMarkGroupId = selectedMarkGroupId
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

    private fun updateReaderInputEnabled() {
        if (!::inputView.isInitialized) return
        val state = latestState
        val selectingAssistantRegion = ::pageRegionSelectionView.isInitialized &&
            pageRegionSelectionView.visibility == View.VISIBLE
        inputView.isEnabled = state.documentReady &&
            state.capabilities.canWrite &&
            state.storageAvailable &&
            !state.submissionInProgress &&
            !selectingAssistantRegion &&
            !assistantCaptureInProgress
    }

    private fun currentStudentExplanationTarget(
        state: ReaderUiState,
    ): StudentExplanationTarget? {
        if (state.role != ReaderRole.STUDENT || !state.documentReady ||
            state.bookId.isBlank() || state.attemptNo <= TEACHER_PAGE_REVIEW_ATTEMPT_NO
        ) {
            return null
        }
        return StudentExplanationTarget(
            page = AssistantPageKey(state.bookId, state.pageNumber),
            attemptNo = state.attemptNo,
        )
    }

    private fun refreshStudentExplanationLayer(force: Boolean = false) {
        if (!::studentExplanationOverlay.isInitialized) return
        val target = currentStudentExplanationTarget(latestState)
        if (target == null) {
            explanationLoadGeneration += 1L
            displayedExplanationTarget = null
            loadingExplanationTarget = null
            studentExplanationOverlay.clearLayer()
            return
        }
        if (!force && (displayedExplanationTarget == target || loadingExplanationTarget == target)) return
        loadingExplanationTarget = target
        val generation = ++explanationLoadGeneration
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    assistantRepository.studentExplanationLayer(target)
                }
            }
            if (generation != explanationLoadGeneration ||
                target != currentStudentExplanationTarget(latestState)
            ) {
                return@launch
            }
            loadingExplanationTarget = null
            result.onSuccess { layer ->
                displayedExplanationTarget = target
                studentExplanationOverlay.setContentBoundsInView(viewport.activePageBounds())
                studentExplanationOverlay.showLayer(target, layer)
            }.onFailure { error ->
                displayedExplanationTarget = null
                Log.w(GPT_ASSISTANT_LOG_TAG, "Unable to load student explanation layer", error)
            }
        }
    }

    private fun openTeacherGptResources() {
        val state = latestState
        if (state.role == ReaderRole.STUDENT || !state.documentReady || state.bookId.isBlank()) {
            Toast.makeText(this, "선생님 문제집 화면에서 사용할 수 있어요.", Toast.LENGTH_SHORT).show()
            return
        }
        val target = TeacherPageAssistantTarget(
            page = AssistantPageKey(state.bookId, state.pageNumber),
            studentAttemptNo = state.attemptNo.takeIf { it > TEACHER_PAGE_REVIEW_ATTEMPT_NO },
        )
        lifecycleScope.launch {
            val content = runCatching {
                withContext(Dispatchers.IO) {
                    val prompts = assistantRepository.promptSlots()
                    val resources = assistantRepository.listTeacherResources(target.page)
                        .mapNotNull { summary ->
                            assistantRepository.teacherResource(target.page, summary.resourceId)
                        }
                    prompts to resources
                }
            }
            if (target.page.bookId != latestState.bookId ||
                target.page.pageNumber != latestState.pageNumber || isFinishing
            ) {
                return@launch
            }
            content.onSuccess { (prompts, resources) ->
                val controller = teacherResourcesDialog ?: TeacherPageResourcesDialogController(
                    context = this@ReaderActivity,
                    onPromptSelected =(::beginGptRegionSelection),
                    onSend =(::publishStudentExplanation),
                ).also { teacherResourcesDialog = it }
                controller.show(target, prompts, resources)
            }.onFailure { error ->
                Log.w(GPT_ASSISTANT_LOG_TAG, "Unable to load page resources", error)
                Toast.makeText(
                    this@ReaderActivity,
                    "GPT 페이지 자료를 불러오지 못했습니다.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun openAnswerPdf() {
        val state = latestState
        if (state.role == ReaderRole.STUDENT || !state.documentReady || state.bookId.isBlank()) {
            Toast.makeText(this, "선생님 문제집 화면에서 사용할 수 있어요.", Toast.LENGTH_SHORT).show()
            return
        }
        val repository = LibraryRepository.get(this)
        val book = runCatching { repository.book(state.bookId) }.getOrNull()
        if (book == null || runCatching { repository.answerPdfFile(book) }.isFailure) {
            Toast.makeText(
                this,
                "교재 화면에서 답안 PDF를 먼저 연결하세요.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val target = state.bookId to state.pageNumber
        if (answerPopupTarget == target && answerCropPopup != null) {
            dismissAnswerCropPopup()
            return
        }
        val crop = runCatching {
            repository.answerCropForProblem(state.bookId, state.pageNumber)
        }.getOrNull()
        if (crop == null) {
            launchFullAnswerPdf(target, focusExistingCrop = false)
        } else {
            showAnswerCropPopup(state.bookId, state.pageNumber, crop)
        }
    }

    private fun launchFullAnswerPdf(
        target: Pair<String, Int>,
        focusExistingCrop: Boolean,
    ) {
        dismissAnswerCropPopup()
        launchedAnswerTarget = target
        answerPdfLauncher.launch(
            AnswerPdfActivity.intent(
                context = this,
                bookId = target.first,
                problemPage = target.second,
                focusExistingCrop = focusExistingCrop,
            ).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION),
        )
    }

    private fun showAnswerCropPopup(bookId: String, problemPage: Int, crop: AnswerPdfCrop) {
        if (rootHost.width <= 0 || rootHost.height <= 0) {
            rootHost.post {
                if (!isFinishing && latestState.bookId == bookId && latestState.pageNumber == problemPage) {
                    showAnswerCropPopup(bookId, problemPage, crop)
                }
            }
            return
        }
        dismissAnswerCropPopup()
        if (stylusMenuExpanded) closeStylusMenu()
        cancelActiveEraserInput()

        val cropAspect = (crop.right - crop.left) / (crop.bottom - crop.top)
        val maximumWidth = (rootHost.width * ANSWER_POPUP_MAX_WIDTH_FRACTION).roundToInt()
            .coerceAtLeast(dp(ANSWER_POPUP_MIN_WIDTH_DP))
        val maximumImageHeight = (rootHost.height * ANSWER_POPUP_MAX_HEIGHT_FRACTION).roundToInt()
            .coerceAtLeast(dp(ANSWER_POPUP_MIN_IMAGE_HEIGHT_DP))
        var imageWidth = maximumWidth.toFloat()
        var imageHeight = imageWidth / cropAspect
        if (imageHeight > maximumImageHeight) {
            imageHeight = maximumImageHeight.toFloat()
            imageWidth = imageHeight * cropAspect
        }
        val popupWidth = imageWidth.roundToInt()
            .coerceIn(dp(ANSWER_POPUP_MIN_WIDTH_DP), maximumWidth)
        val popupImageHeight = imageHeight.roundToInt()
            .coerceIn(dp(ANSWER_POPUP_MIN_IMAGE_HEIGHT_DP), maximumImageHeight)
        val popup = AnswerCropPopupView(this).apply {
            showLoading()
            onClose =(::dismissAnswerCropPopup)
            onOpenPdf = {
                launchFullAnswerPdf(bookId to problemPage, focusExistingCrop = true)
            }
            onPositionChanged = { x, y -> saveAnswerPopupPosition(x, y) }
        }
        val insertionIndex = rootHost.indexOfChild(messageOverlayHost).coerceAtLeast(0)
        rootHost.addView(
            popup,
            insertionIndex,
            FrameLayout.LayoutParams(
                popupWidth,
                popupImageHeight + dp(ANSWER_POPUP_HEADER_HEIGHT_DP),
            ),
        )
        answerCropPopup = popup
        answerPopupTarget = bookId to problemPage
        answerPopupCrop = crop
        answerPopupBackCallback = onBackPressedDispatcher.addCallback(this) {
            dismissAnswerCropPopup()
        }
        popup.post { updateAnswerPopupBounds(restoreSavedPosition = true) }

        val generation = ++answerPopupLoadGeneration
        answerPopupLoadJob = lifecycleScope.launch {
            val result = runCatching {
                answerCropLoader.load(
                    bookId = bookId,
                    crop = crop,
                    maximumWidthPixels = popupWidth,
                    maximumHeightPixels = popupImageHeight,
                )
            }
            if (generation != answerPopupLoadGeneration || answerCropPopup !== popup ||
                answerPopupTarget != (bookId to problemPage) || answerPopupCrop != crop
            ) {
                return@launch
            }
            result.onSuccess(popup::showBitmap).onFailure { error ->
                Log.w(ANSWER_PDF_LOG_TAG, "Unable to render answer crop", error)
                popup.showError()
                Toast.makeText(this@ReaderActivity, "저장된 답안을 불러오지 못했습니다.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dismissAnswerPopupIfTargetChanged(state: ReaderUiState) {
        val target = answerPopupTarget ?: return
        if (state.role == ReaderRole.STUDENT || target.first != state.bookId || target.second != state.pageNumber) {
            dismissAnswerCropPopup()
        }
    }

    private fun dismissAnswerCropPopup() {
        answerPopupLoadGeneration += 1L
        answerPopupLoadJob?.cancel()
        answerPopupLoadJob = null
        answerPopupBackCallback?.remove()
        answerPopupBackCallback = null
        answerCropPopup?.let { popup ->
            popup.clearBitmap()
            if (::rootHost.isInitialized) rootHost.removeView(popup)
        }
        answerCropPopup = null
        answerPopupTarget = null
        answerPopupCrop = null
    }

    private fun answerPopupBounds(): RectF {
        val margin = dp(ANSWER_POPUP_MARGIN_DP).toFloat()
        val left = systemBarInsets.left + margin
        val right = rootHost.width - systemBarInsets.right - margin
        val top = systemBarInsets.top + dp(TOP_CHROME_HEIGHT + ANSWER_POPUP_MARGIN_DP)
        val bottom = rootHost.height - systemBarInsets.bottom - dp(ANSWER_POPUP_MARGIN_DP)
        return RectF(left, top.toFloat(), right, bottom.toFloat())
    }

    private fun updateAnswerPopupBounds(restoreSavedPosition: Boolean) {
        val popup = answerCropPopup ?: return
        if (rootHost.width <= 0 || rootHost.height <= 0 || popup.width <= 0 || popup.height <= 0) return
        val bounds = answerPopupBounds()
        popup.setDragBounds(bounds)
        if (!restoreSavedPosition) return
        val preferences = getSharedPreferences(ANSWER_POPUP_POSITION_PREFS, Context.MODE_PRIVATE)
        val suffix = if (rootHost.width > rootHost.height) "landscape" else "portrait"
        val xFraction = preferences.getFloat("x_$suffix", 1f).coerceIn(0f, 1f)
        val yFraction = preferences.getFloat("y_$suffix", 0.08f).coerceIn(0f, 1f)
        popup.x = bounds.left + (bounds.width() - popup.width).coerceAtLeast(0f) * xFraction
        popup.y = bounds.top + (bounds.height() - popup.height).coerceAtLeast(0f) * yFraction
        popup.setDragBounds(bounds)
    }

    private fun saveAnswerPopupPosition(x: Float, y: Float) {
        val popup = answerCropPopup ?: return
        val bounds = answerPopupBounds()
        val availableX = (bounds.width() - popup.width).coerceAtLeast(1f)
        val availableY = (bounds.height() - popup.height).coerceAtLeast(1f)
        val suffix = if (rootHost.width > rootHost.height) "landscape" else "portrait"
        getSharedPreferences(ANSWER_POPUP_POSITION_PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("x_$suffix", ((x - bounds.left) / availableX).coerceIn(0f, 1f))
            .putFloat("y_$suffix", ((y - bounds.top) / availableY).coerceIn(0f, 1f))
            .apply()
    }

    private fun beginGptRegionSelection(choice: TeacherPromptChoice) {
        if (choice.target.page.bookId != latestState.bookId ||
            choice.target.page.pageNumber != latestState.pageNumber
        ) {
            Toast.makeText(this, "페이지가 바뀌어 다시 선택해야 합니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val pageBounds = viewport.activePageBounds()
        if (pageBounds == null || pageBounds.width() < 2f || pageBounds.height() < 2f) {
            Toast.makeText(this, "페이지가 표시된 뒤 다시 시도해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        teacherResourcesDialog?.dismiss()
        dismissAnswerCropPopup()
        if (stylusMenuExpanded) closeStylusMenu()
        pendingPromptChoice = choice
        pageRegionSelectionView.beginSelection(pageBounds)
        updateReaderInputEnabled()
    }

    private fun onGptRegionSelected(selectionInRoot: RectF) {
        val choice = pendingPromptChoice
        pendingPromptChoice = null
        if (choice == null || choice.target.page.bookId != latestState.bookId ||
            choice.target.page.pageNumber != latestState.pageNumber
        ) {
            updateReaderInputEnabled()
            Toast.makeText(this, "선택 대상이 바뀌어 취소했습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val canonicalBounds = canonicalBoundsForSelection(
            expectedPage = choice.target.page.pageNumber,
            selection = selectionInRoot,
        )
        if (canonicalBounds == null) {
            updateReaderInputEnabled()
            Toast.makeText(this, "문제 영역을 페이지 안에서 다시 선택해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        captureGptRegion(choice, selectionInRoot, canonicalBounds)
    }

    private fun canonicalBoundsForSelection(
        expectedPage: Int,
        selection: RectF,
    ): PageBounds? {
        val insetX = min(0.75f, selection.width() / 4f)
        val insetY = min(0.75f, selection.height() / 4f)
        val points = listOf(
            viewport.viewToCanonical(selection.left + insetX, selection.top + insetY),
            viewport.viewToCanonical(selection.right - insetX, selection.bottom - insetY),
        )
        if (points.any { it == null || it.pageNumber != expectedPage }) return null
        val first = checkNotNull(points[0]).point
        val second = checkNotNull(points[1]).point
        val left = min(first.x, second.x)
        val top = min(first.y, second.y)
        val right = max(first.x, second.x)
        val bottom = max(first.y, second.y)
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite() ||
            right - left <= 0.1f || bottom - top <= 0.1f
        ) {
            return null
        }
        return PageBounds(left, top, right, bottom)
    }

    private fun captureGptRegion(
        choice: TeacherPromptChoice,
        selectionInRoot: RectF,
        canonicalBounds: PageBounds,
    ) {
        if (assistantCaptureInProgress) return
        val rootLocation = IntArray(2).also(rootHost::getLocationInWindow)
        val windowWidth = window.decorView.width
        val windowHeight = window.decorView.height
        val source = Rect(
            (rootLocation[0] + floor(selectionInRoot.left).toInt()).coerceIn(0, windowWidth),
            (rootLocation[1] + floor(selectionInRoot.top).toInt()).coerceIn(0, windowHeight),
            (rootLocation[0] + ceil(selectionInRoot.right).toInt()).coerceIn(0, windowWidth),
            (rootLocation[1] + ceil(selectionInRoot.bottom).toInt()).coerceIn(0, windowHeight),
        )
        if (source.width() < 2 || source.height() < 2) {
            updateReaderInputEnabled()
            Toast.makeText(this, "선택 영역이 너무 작습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val scale = min(1f, GPT_CAPTURE_MAX_EDGE_PX.toFloat() / max(source.width(), source.height()))
        val bitmap = runCatching {
            Bitmap.createBitmap(
                max(2, (source.width() * scale).roundToInt()),
                max(2, (source.height() * scale).roundToInt()),
                Bitmap.Config.ARGB_8888,
            )
        }.getOrElse { error ->
            Log.w(GPT_ASSISTANT_LOG_TAG, "Unable to allocate capture bitmap", error)
            Toast.makeText(this, "화면 캡처 메모리가 부족합니다.", Toast.LENGTH_SHORT).show()
            return
        }

        assistantCaptureInProgress = true
        updateReaderInputEnabled()
        val temporarilyHidden = listOf(
            topChrome,
            messageOverlayHost,
            stylusMenuOverlay,
            studentExplanationOverlay,
            pageRegionSelectionView,
        ).map { view -> view to view.visibility }
        temporarilyHidden.forEach { (view, _) -> view.visibility = View.INVISIBLE }

        // PixelCopy reads the window surface, not the View tree. Wait through one complete draw so
        // the hidden selector/chrome cannot remain in the captured surface from the previous frame.
        rootHost.postOnAnimation {
            rootHost.postOnAnimation {
                val restoreUi = {
                    temporarilyHidden.forEach { (view, visibility) -> view.visibility = visibility }
                    assistantCaptureInProgress = false
                    updateReaderInputEnabled()
                }
                try {
                    PixelCopy.request(
                        window,
                        source,
                        bitmap,
                        { result ->
                            restoreUi()
                            if (result != PixelCopy.SUCCESS) {
                                bitmap.recycle()
                                Log.w(GPT_ASSISTANT_LOG_TAG, "PixelCopy failed: $result")
                                Toast.makeText(
                                    this@ReaderActivity,
                                    "문제 영역을 캡처하지 못했습니다.",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                return@request
                            }
                            if (isDestroyed || isFinishing) {
                                bitmap.recycle()
                                return@request
                            }
                            persistCaptureAndLaunch(choice, canonicalBounds, bitmap)
                        },
                        Handler(Looper.getMainLooper()),
                    )
                } catch (error: Throwable) {
                    restoreUi()
                    bitmap.recycle()
                    Log.w(GPT_ASSISTANT_LOG_TAG, "PixelCopy request failed", error)
                    Toast.makeText(
                        this@ReaderActivity,
                        "문제 영역을 캡처하지 못했습니다.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    private fun persistCaptureAndLaunch(
        choice: TeacherPromptChoice,
        canonicalBounds: PageBounds,
        bitmap: Bitmap,
    ) {
        lifecycleScope.launch {
            val imageFile = runCatching {
                withContext(Dispatchers.IO) {
                    try {
                        val directory = File(cacheDir, GPT_CAPTURE_CACHE_DIRECTORY)
                        check(directory.exists() || directory.mkdirs()) { "캡처 폴더를 만들 수 없습니다." }
                        val file = File(directory, "${UUID.randomUUID()}.png")
                        try {
                            FileOutputStream(file).use { output ->
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                                    "캡처 이미지를 저장할 수 없습니다."
                                }
                                output.flush()
                                output.fd.sync()
                            }
                            check(file.length() in 1L..GPT_CAPTURE_MAX_BYTES) {
                                "선택 영역 이미지가 너무 큽니다."
                            }
                            file
                        } catch (error: Throwable) {
                            file.delete()
                            throw error
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            imageFile.onSuccess { file ->
                val request = GptAssistantRequest(
                    bookId = choice.target.page.bookId,
                    pageNumber = choice.target.page.pageNumber,
                    promptSlotNumber = choice.prompt.slotNumber,
                    promptTitle = choice.prompt.title,
                    promptBody = choice.prompt.body,
                    selectionBounds = canonicalBounds,
                    imagePath = file.absolutePath,
                )
                runCatching {
                    gptAssistantLauncher.launch(GptAssistantActivity.intent(this@ReaderActivity, request))
                }.onFailure { error ->
                    file.delete()
                    Log.w(GPT_ASSISTANT_LOG_TAG, "Unable to open GPT activity", error)
                    Toast.makeText(
                        this@ReaderActivity,
                        "GPT 화면을 열지 못했습니다.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }.onFailure { error ->
                Log.w(GPT_ASSISTANT_LOG_TAG, "Unable to persist capture", error)
                Toast.makeText(
                    this@ReaderActivity,
                    error.message ?: "선택 영역을 저장하지 못했습니다.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun pruneStaleGptCaptures() {
        lifecycleScope.launch(Dispatchers.IO) {
            val directory = File(cacheDir, GPT_CAPTURE_CACHE_DIRECTORY)
            val cutoff = System.currentTimeMillis() - GPT_CAPTURE_MAX_AGE_MS
            directory.listFiles().orEmpty().asSequence()
                .filter(File::isFile)
                .filter { file ->
                    file.extension.equals("png", ignoreCase = true) && file.lastModified() in 1L until cutoff
                }
                .forEach { file -> runCatching { file.delete() } }
        }
    }

    private fun publishStudentExplanation(draft: TeacherExplanationSendDraft) {
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val current = assistantRepository.studentExplanationLayer(draft.target)
                    val existing = current.cards.firstOrNull {
                        it.sourceResourceId == draft.sourceResourceId
                    }
                    val card = if (existing == null) {
                        assistantRepository.newStudentExplanationCard(
                            page = draft.target.page,
                            sourceResourceId = draft.sourceResourceId,
                            sourceResourceRevisionId = draft.sourceResourceRevisionId,
                            title = draft.title,
                            text = draft.text,
                            anchorBounds = draft.anchorBounds,
                        )
                    } else if (
                        existing.sourceResourceRevisionId == draft.sourceResourceRevisionId &&
                        existing.title == draft.title && existing.text == draft.text &&
                        existing.anchorBounds == draft.anchorBounds
                    ) {
                        existing
                    } else {
                        existing.copy(
                            sourceResourceRevisionId = draft.sourceResourceRevisionId,
                            title = draft.title,
                            text = draft.text,
                            anchorBounds = draft.anchorBounds,
                            updatedAtEpochMillis = max(
                                System.currentTimeMillis(),
                                existing.updatedAtEpochMillis + 1L,
                            ),
                        )
                    }
                    val nextCards = if (existing == null) {
                        current.cards + card
                    } else {
                        current.cards.map { prior -> if (prior.cardId == existing.cardId) card else prior }
                    }
                    val layer = assistantRepository.replaceStudentExplanationCards(
                        target = draft.target,
                        cards = nextCards,
                        expectedRevision = current.revision,
                    )
                    layer to (layer.revision != current.revision)
                }
            }
            result.onSuccess { (layer, changed) ->
                if (changed) StudentExplanationLayerBus.localLayerPublished(layer)
                Toast.makeText(
                    this@ReaderActivity,
                    if (changed) "학생 설명을 전송 대기열에 저장했습니다." else "같은 설명이 이미 저장되어 있습니다.",
                    Toast.LENGTH_SHORT,
                ).show()
            }.onFailure { error ->
                Log.w(GPT_ASSISTANT_LOG_TAG, "Unable to publish student explanation", error)
                Toast.makeText(
                    this@ReaderActivity,
                    "학생 설명을 저장하지 못했습니다.",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun renderChrome() {
        topChrome.setContent {
            ReaderTopChrome(
                state = latestState,
                expanded = topMenuExpanded,
                onToggleExpanded = { topMenuExpanded = !topMenuExpanded },
                onPrevious = {
                    showPage(
                        pageNumber = latestState.pageNumber - 1,
                        followRemoteStudent = false,
                    )
                },
                onNext = {
                    showPage(
                        pageNumber = latestState.pageNumber + 1,
                        followRemoteStudent = false,
                    )
                },
                onExitToLibrary =(::returnToBookOverview),
                onSubmit =(::submitCurrentPage),
                onPreviousAttempt = { changeAttempt(-1) },
                onNextAttempt = { changeAttempt(1) },
                onPublish = { viewModel.publishTeacherInk() },
                onDismissDataError = viewModel::dismissDataError,
                onSelectAttempt = viewModel::selectAttempt,
                onShowStudentActivity = { studentActivityVisible = true },
                onResumeStudentFollow = viewModel::resumeStudentFollow,
                onOpenRemoteMonitor =(::openRemoteMonitorSetup),
                onOpenGptAssistant =(::openTeacherGptResources),
                onOpenAnswerPdf =(::openAnswerPdf),
            )
            if (studentActivityVisible && latestState.capabilities.showsStudentLocation) {
                StudentActivityDialog(
                    samples = latestState.activitySamples,
                    role = latestState.role,
                    onDismiss = { studentActivityVisible = false },
                )
            }
            if (pinDialogVisible) {
                TeacherPinDialog(
                    setup = !teacherAccess.hasPin,
                    onCancel = {
                        pinDialogVisible = false
                        requestedTeacherRole = null
                        requestedTeacherWorkflow = null
                        if (exitOnPinCancel) finish()
                        exitOnPinCancel = false
                    },
                    onConfirm = { pin, remember ->
                        val valid = if (!teacherAccess.hasPin) {
                            teacherAccess.setPin(pin) && teacherAccess.verify(pin, remember)
                        } else teacherAccess.verify(pin, remember)
                        if (valid) {
                            pinDialogVisible = false
                            role = requestedTeacherRole ?: ReaderRole.TEACHER_TABLET
                            ensureS23StripStartsExpanded()
                            workflow = requestedTeacherWorkflow ?: ReaderWorkflow.defaultFor(role)
                            ensureLanRoleForReader()
                            requestedTeacherRole = null
                            requestedTeacherWorkflow = null
                            val targetAttempt = if (workflow == ReaderWorkflow.REVIEW) {
                                reviewableAttemptForPage(preferred = initialAttemptNo)
                            } else {
                                initialAttemptNo
                            }
                            showPage(
                                pageNumber = latestState.pageNumber,
                                attemptNo = targetAttempt,
                                followRemoteStudent = initialFollowRemoteStudent,
                            )
                            exitOnPinCancel = false
                        }
                        valid
                    },
                )
            }
            selectedMarkGroupId?.let { groupId ->
                MarkEditDialog(
                    onBlue = {
                        if (selectedMarkTarget?.matches(latestState) == true && latestState.documentReady) {
                            viewModel.changeGrade(groupId, MarkColor.BLUE)
                        }
                        clearMarkSelection()
                    },
                    onRed = {
                        if (selectedMarkTarget?.matches(latestState) == true && latestState.documentReady) {
                            viewModel.changeGrade(groupId, MarkColor.RED)
                        }
                        clearMarkSelection()
                    },
                    onMove = {
                        pendingMarkMove = selectedMarkTarget
                            ?.takeIf { target -> target.matches(latestState) }
                            ?.let { target -> PendingMarkMove(groupId, target) }
                        clearMarkSelection()
                    },
                    onHide = {
                        if (selectedMarkTarget?.matches(latestState) == true && latestState.documentReady) {
                            viewModel.hideMarkGroup(groupId)
                        }
                        clearMarkSelection()
                    },
                    onCancel =(::clearMarkSelection),
                )
            }
        }
        stylusMenuOverlay.setContent {
            StylusToolMenu(
                expanded = stylusMenuExpanded,
                anchorInHost = stylusMenuAnchorInHost,
                state = latestState,
                selectedTool = selectedTool,
                selectedColorArgb = selectedPenColor,
                selectedWidthDp = selectedPenWidthDp,
                selectedOpacity = selectedPenOpacity,
                // Choosing is the end of the interaction: commit and get the menu out of the way.
                // Tapping the pen while it is already selected still opens its settings page, which
                // is handled inside the menu and does not come through here.
                onSelectTool = { tool ->
                    selectTool(tool)
                    closeStylusMenu()
                },
                onSelectColor = { color ->
                    selectPenColor(color)
                    closeStylusMenu()
                },
                onSelectWidth =(::selectPenWidth),
                onSelectOpacity =(::selectPenOpacity),
                onUndo =(::undoCurrentPage),
                onRedo =(::redoCurrentPage),
                onInputRegionChanged = { region ->
                    if (region == null) {
                        stylusMenuOverlay.clearMenuInputRegion()
                    } else {
                        stylusMenuOverlay.updateMenuInputRegion(region)
                    }
                },
            )
        }
    }

    /**
     * Opening a book from the library's default student perspective can leave the phone's single
     * LAN service running as another student server. A teacher Reader must correct that ownership
     * once, otherwise two student servers can sit on the same hotspot forever while the UI falls
     * back to Telegram.
     */
    private fun ensureLanRoleForReader() {
        if (role != ReaderRole.TEACHER_PHONE || workflow != ReaderWorkflow.LIVE_MONITOR) return
        if (LanSyncBus.sessionRole(bookId) == LanPeerRole.TEACHER_CLIENT) return
        LanSyncService.startTeacher(this, bookId)
    }

    private fun undoCurrentPage() {
        val state = latestState
        if (!state.canUndo) return
        viewModel.undo()
        publishStudentHeartbeat(StudentWorkKind.UNDO, state)
    }

    private fun redoCurrentPage() {
        val state = latestState
        if (!state.canRedo) return
        viewModel.redo()
        publishStudentHeartbeat(StudentWorkKind.REDO, state)
    }

    private fun openRemoteMonitorSetup() {
        topMenuExpanded = false
        val targetClass = if (remoteMonitorGateway.remoteReviewPeerStatus() is RemoteReviewPeerStatus.Connected) {
            REMOTE_CHAT_ACTIVITY_CLASS
        } else {
            REMOTE_REVIEW_SETUP_ACTIVITY_CLASS
        }
        val setupIntent = Intent().setClassName(packageName, targetClass)
        runCatching { startActivity(setupIntent) }.onFailure {
            studentStatusOverlay.showMessage("Telegram 연결 화면을 열 수 없습니다.")
        }
    }

    private fun handlePeerChatState(state: RemotePeerChatState) {
        if (!readerStarted) return
        val peer = remoteMonitorGateway.remoteReviewPeerStatus() as? RemoteReviewPeerStatus.Connected
            ?: return
        if (state.scope.pairId != peer.pairId) return
        val latest = state.latestMessage
        if (!peerChatPrimed) {
            peerChatPrimed = true
            lastPeerChatMessageId = latest?.messageId
            val newestUnreadIncoming = state.recentMessages.lastOrNull { message ->
                message.direction == RemotePeerChatDirection.INCOMING && !message.isRead
            }
            if (state.unreadCount > 0 && newestUnreadIncoming != null) {
                offerIncomingPeerChat(
                    state.scope.pairId,
                    newestUnreadIncoming.messageId,
                    newestUnreadIncoming.text,
                )
            }
            return
        }
        if (latest == null || latest.messageId == lastPeerChatMessageId) return
        lastPeerChatMessageId = latest.messageId
        if (latest.direction != RemotePeerChatDirection.INCOMING) return
        offerIncomingPeerChat(state.scope.pairId, latest.messageId, latest.text)
    }

    private fun offerIncomingPeerChat(pairId: String, messageId: String, text: String) {
        peerChatOverlayDeliveryGate.offer(
            pairId = pairId,
            messageId = messageId,
            text = text,
            canDisplayNow = readerResumed,
        )?.let(::claimAndShowIncomingPeerChat)
    }

    private fun deliverPendingPeerChat() {
        val activePairId = (remoteMonitorGateway.remoteReviewPeerStatus()
            as? RemoteReviewPeerStatus.Connected)?.pairId
        peerChatOverlayDeliveryGate.resume(activePairId)?.let(::claimAndShowIncomingPeerChat)
    }

    private fun claimAndShowIncomingPeerChat(delivery: PeerChatOverlayDelivery) {
        lifecycleScope.launch {
            peerChatAnnouncementMutex.withLock {
                val claimed = withContext(Dispatchers.IO) {
                    runCatching {
                        peerChatAnnouncementStore.claim(delivery.pairId, delivery.messageId)
                    }.onFailure { error ->
                        Log.w(REMOTE_MONITOR_LOG_TAG, "Unable to persist peer-chat announcement claim", error)
                    }.getOrDefault(false)
                }
                if (!claimed || !readerStarted || !readerResumed) return@withLock
                val activePairId = (remoteMonitorGateway.remoteReviewPeerStatus()
                    as? RemoteReviewPeerStatus.Connected)?.pairId
                if (activePairId != delivery.pairId) return@withLock
                showClaimedIncomingPeerChat(delivery.text)
            }
        }
    }

    /** Visible and audible side effects are allowed only after a durable announcement claim. */
    private fun showClaimedIncomingPeerChat(text: String) {
        parentMessageOverlay.showMessage(text)
        if (latestState.role == ReaderRole.STUDENT && remoteMonitorPreferences.ttsEnabled) {
            if (voiceState.blocksParentSpeech()) deferredParentSpeech = text
            else parentMessageSpeaker.speak(text)
        }
    }

    /** The S23 exception exists to keep three attempt bundles visible without an extra tap. */
    private fun ensureS23StripStartsExpanded() {
        if (s23StripInitialExpansionApplied) return
        if (
            shouldUseS23UltraTopStrip(
                model = android.os.Build.MODEL.orEmpty(),
                orientation = resources.configuration.orientation,
                role = role,
            )
        ) {
            topMenuExpanded = true
            s23StripInitialExpansionApplied = true
        }
    }

    private fun startRemoteMonitorServiceIfEnabled() {
        if (!remoteMonitorPreferences.monitoringEnabled) return
        runCatching {
            startForegroundService(Intent().setClassName(packageName, REMOTE_MONITOR_SERVICE_CLASS))
        }.onFailure { error ->
            Log.w(REMOTE_MONITOR_LOG_TAG, "Unable to start remote monitor service", error)
        }
    }

    private fun publishStudentPresence(state: ReaderUiState, force: Boolean = false) {
        if (!readerStarted) return
        if (
            state.role != ReaderRole.STUDENT ||
            !state.documentReady ||
            state.bookId.isBlank() ||
            state.pageNumber < 0
        ) {
            publishInactiveStudentPresence()
            return
        }
        val pageNumber = state.pageNumber + 1
        val next = StudentStudyPresence(
            bookId = state.bookId,
            pageNumber = pageNumber,
            attemptNo = state.attemptNo.takeIf { it > 0 },
            active = true,
            updatedAtElapsedMs = SystemClock.elapsedRealtime(),
        )
        val previous = activeStudentPresence
        val changed = previous?.active != true ||
            previous.bookId != next.bookId ||
            previous.pageNumber != next.pageNumber ||
            previous.attemptNo != next.attemptNo
        if (force || changed) StudentStudyPresenceBus.publish(next)
        activeStudentPresence = next

        val pageKey = state.bookId to pageNumber
        val previousPageKey = lastStudentPageKey
        lastStudentPageKey = pageKey
        if (previousPageKey != null && previousPageKey != pageKey) {
            publishStudentHeartbeat(StudentWorkKind.PAGE_CHANGE, state)
        }
    }

    private fun publishInactiveStudentPresence() {
        val previous = activeStudentPresence?.takeIf { it.active } ?: return
        val inactive = previous.copy(
            active = false,
            updatedAtElapsedMs = SystemClock.elapsedRealtime(),
        )
        StudentStudyPresenceBus.publish(inactive)
        activeStudentPresence = inactive
    }

    private fun publishStudentHeartbeat(
        kind: StudentWorkKind,
        state: ReaderUiState = latestState,
    ) {
        if (
            state.role != ReaderRole.STUDENT ||
            !state.documentReady ||
            state.bookId.isBlank() ||
            state.pageNumber < 0
        ) return
        StudentWorkHeartbeatBus.publish(
            StudentWorkHeartbeat(
                atElapsedMs = SystemClock.elapsedRealtime(),
                kind = kind,
                bookId = state.bookId,
                pageNumber = state.pageNumber + 1,
            ),
        )
    }

    private fun handleParentMessage(message: ParentMessage): Boolean {
        if (!readerResumed || role != ReaderRole.STUDENT || latestState.role != ReaderRole.STUDENT) {
            return false
        }
        parentMessageOverlay.showMessage(message.text)
        if (remoteMonitorPreferences.monitoringEnabled && remoteMonitorPreferences.ttsEnabled &&
            voiceState.blocksParentSpeech()
        ) {
            // Keep only the newest message. A late queue of old instructions is worse than
            // dropping them once the student's own voice has safely reached the durable outbox.
            deferredParentSpeech = message.text
        } else if (remoteMonitorPreferences.monitoringEnabled && remoteMonitorPreferences.ttsEnabled) {
            parentMessageSpeaker.speak(message.text)
        }
        return true
    }

    private fun deliverPendingParentMessageIfStudent() {
        if (!readerStarted || latestState.role != ReaderRole.STUDENT) return
        val pending = remoteMonitorGateway.pendingParentMessage() ?: return
        if (handleParentMessage(pending)) {
            remoteMonitorGateway.acknowledgeParentMessage(pending.updateId)
        }
    }

    private fun maybeSpeakDeferredParentMessage() {
        if (
            !readerResumed ||
            latestState.role != ReaderRole.STUDENT ||
            !remoteMonitorPreferences.monitoringEnabled ||
            !remoteMonitorPreferences.ttsEnabled ||
            voiceState.blocksParentSpeech()
        ) return
        val message = deferredParentSpeech ?: return
        deferredParentSpeech = null
        parentMessageSpeaker.speak(message)
    }

    private fun updateStudentVoiceEnabled() {
        if (!::studentVoiceController.isInitialized) return
        val requested = readerResumed &&
            latestState.documentReady &&
            latestState.role == ReaderRole.STUDENT &&
            remoteMonitorPreferences.monitoringEnabled &&
            remoteMonitorPreferences.wakeVoiceEnabled
        val permissionGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (requested && !permissionGranted && !microphonePermissionWarningShown) {
            microphonePermissionWarningShown = true
            studentStatusOverlay.showMessage("‘아빠’ 글 보내기를 사용하려면 Telegram 설정에서 마이크 권한을 허용해 주세요.")
        } else if (!requested || permissionGranted) {
            microphonePermissionWarningShown = false
        }
        studentVoiceController.setEnabled(requested && permissionGranted)
    }

    private fun onStudentVoiceStateChanged(next: StudentVoiceState) {
        voiceState = next
        when (next) {
            StudentVoiceState.WAITING_FOR_MESSAGE -> {
                studentMessageExpectedChatId = remoteMonitorGateway.configuredChatId()
                studentStatusIsProgress = true
                studentStatusOverlay.showPersistentMessage("삐 소리 뒤에 보낼 내용을 말해 주세요.")
            }
            StudentVoiceState.DICTATING -> {
                studentStatusIsProgress = true
                studentStatusOverlay.showPersistentMessage("듣고 있어요. 말이 끝나면 글로 보낼게요.")
            }
            StudentVoiceState.SENDING -> {
                studentStatusIsProgress = true
                studentStatusOverlay.showPersistentMessage("말을 글로 바꿔 Telegram에 저장하고 있어요.")
            }
            StudentVoiceState.OFF,
            StudentVoiceState.LISTENING_FOR_WAKE -> {
                studentMessageExpectedChatId = null
                if (studentStatusIsProgress) {
                    studentStatusIsProgress = false
                    studentStatusOverlay.clearMessage()
                }
                maybeSpeakDeferredParentMessage()
            }
        }
    }

    private fun showStudentStatusResult(message: String) {
        studentStatusIsProgress = false
        studentStatusOverlay.showMessage(message)
    }

    private fun enqueueStudentText(message: StudentVoiceTextMessage) {
        val pageState = latestState
        val text = buildString {
            append("학생 메시지 · ")
            append(pageState.bookTitle.ifBlank { "문제집" })
            append(" · ")
            append(pageState.pageNumber + 1)
            append("쪽\n")
            append(message.text)
        }
        val expectedChatId = studentMessageExpectedChatId ?: Long.MIN_VALUE
        // This is one small journal append, not a network request. Persist it synchronously before
        // returning from the recognition callback so an Activity rotation cannot lose the text.
        val outcome = runCatching {
            remoteMonitorGateway.enqueueText(
                idempotencyKey = message.idempotencyKey,
                text = text,
                expectedChatId = expectedChatId,
            )
        }
        val result = outcome.getOrNull()
        val durable = result == TelegramEnqueueResult.ENQUEUED ||
            result == TelegramEnqueueResult.ALREADY_PENDING
        when {
            outcome.isFailure -> showStudentStatusResult("글 메시지를 저장하지 못했습니다. 다시 시도해 주세요.")
            durable -> showStudentStatusResult("말한 내용을 글로 전송 대기열에 저장했어요.")
            result == TelegramEnqueueResult.QUEUE_FULL ->
                showStudentStatusResult("전송 대기열에 공간이 없습니다. 잠시 뒤 다시 ‘아빠’라고 불러 주세요.")
            result == TelegramEnqueueResult.ALREADY_DELIVERED ->
                showStudentStatusResult("말한 내용이 이미 전송됐어요.")
            else -> showStudentStatusResult("Telegram 연결을 확인한 뒤 다시 말해 주세요.")
        }
        studentVoiceController.markUploadFinished(message.idempotencyKey)
        maybeSpeakDeferredParentMessage()
    }

    private fun StudentVoiceState.blocksParentSpeech(): Boolean =
        this == StudentVoiceState.WAITING_FOR_MESSAGE ||
            this == StudentVoiceState.DICTATING ||
            this == StudentVoiceState.SENDING

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        rememberStylusPosition(event)
        if (handleStylusButton(event, observeHeldState = true)) return true
        if (stylusMenuExpanded && stylusMenuOverlay.isMenuUiAtRaw(event.rawX, event.rawY)) {
            dryInkView.hoverPreview = null
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        rememberStylusPosition(event)
        if (stylusMenuExpanded && event.isStylusContactDown()) {
            val normalStylusInsideMenu = event.pointerCount > 0 &&
                event.getToolType(event.actionIndex.coerceIn(0, event.pointerCount - 1)) ==
                MotionEvent.TOOL_TYPE_STYLUS &&
                stylusMenuOverlay.isMenuUiAtRaw(event.rawX, event.rawY)
            if (!normalStylusInsideMenu) closeStylusMenu()
        }
        if (handleStylusButton(event, observeHeldState = false)) return true
        return super.dispatchTouchEvent(event)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        dismissAnswerCropPopup()
        super.onConfigurationChanged(newConfig)
    }

    override fun onStart() {
        super.onStart()
        readerStarted = true
        StudentExplanationLayerBus.addListener(studentExplanationListener)
        refreshStudentExplanationLayer(force = true)
        remoteMonitorPreferences = remoteMonitorGateway.preferences()
        parentMessageSubscription?.close()
        parentMessageSubscription = remoteMonitorGateway.subscribeParentMessages { message ->
            runOnUiThread {
                if (readerStarted && handleParentMessage(message)) {
                    remoteMonitorGateway.acknowledgeParentMessage(message.updateId)
                }
            }
        }
        preferenceSubscription?.close()
        preferenceSubscription = remoteMonitorGateway.subscribePreferences { preferences ->
            runOnUiThread {
                if (!readerStarted) return@runOnUiThread
                remoteMonitorPreferences = preferences
                if (!preferences.ttsEnabled) {
                    deferredParentSpeech = null
                    parentMessageSpeaker.stop()
                }
                if (preferences.monitoringEnabled) startRemoteMonitorServiceIfEnabled()
                updateStudentVoiceEnabled()
            }
        }
        peerChatPrimed = false
        peerChatSubscription?.close()
        peerChatSubscription = RemotePeerChatStateBus.subscribe { state ->
            runOnUiThread { handlePeerChatState(state) }
        }
        startRemoteMonitorServiceIfEnabled()
        publishStudentPresence(latestState, force = true)
    }

    override fun onResume() {
        super.onResume()
        readerResumed = true
        studentVoiceController.onResume()
        updateStudentVoiceEnabled()
        deliverPendingParentMessageIfStudent()
        deliverPendingPeerChat()
    }

    override fun onPause() {
        readerResumed = false
        if (::studentVoiceController.isInitialized) studentVoiceController.onPause()
        if (::parentMessageSpeaker.isInitialized) parentMessageSpeaker.stop()
        if (::stylusMenuOverlay.isInitialized) closeStylusMenu()
        if (::inputView.isInitialized) cancelActiveEraserInput()
        stylusButtonPressed = false
        lastStylusButtonPressEventTime = Long.MIN_VALUE
        super.onPause()
    }

    override fun onStop() {
        readerStarted = false
        StudentExplanationLayerBus.removeListener(studentExplanationListener)
        publishInactiveStudentPresence()
        parentMessageSubscription?.close()
        parentMessageSubscription = null
        preferenceSubscription?.close()
        preferenceSubscription = null
        peerChatSubscription?.close()
        peerChatSubscription = null
        peerChatOverlayDeliveryGate.clearPending()
        deferredParentSpeech = null
        if (::parentMessageOverlay.isInitialized) parentMessageOverlay.clearMessage()
        if (::studentStatusOverlay.isInitialized) studentStatusOverlay.clearMessage()
        super.onStop()
    }

    override fun onDestroy() {
        explanationLoadGeneration += 1L
        dismissAnswerCropPopup()
        teacherResourcesDialog?.dismiss()
        teacherResourcesDialog = null
        parentMessageSubscription?.close()
        preferenceSubscription?.close()
        peerChatSubscription?.close()
        if (::studentVoiceController.isInitialized) studentVoiceController.close()
        if (::parentMessageSpeaker.isInitialized) parentMessageSpeaker.close()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (answerCropLoaderDelegate.isInitialized()) answerCropLoader.trimMemory(level)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        if (!hasFocus && ::inputView.isInitialized) cancelActiveEraserInput()
        super.onWindowFocusChanged(hasFocus)
    }

    private fun MotionEvent.isStylusContactDown(): Boolean {
        if (actionMasked != MotionEvent.ACTION_DOWN || pointerCount == 0) return false
        val type = getToolType(actionIndex.coerceIn(0, pointerCount - 1))
        return type == MotionEvent.TOOL_TYPE_STYLUS || type == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun handleStylusButton(event: MotionEvent, observeHeldState: Boolean): Boolean {
        val isStylus = event.isFromSource(InputDevice.SOURCE_STYLUS) || (0 until event.pointerCount).any { index ->
            event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS || event.getToolType(index) == MotionEvent.TOOL_TYPE_ERASER
        }
        if (!isStylus) return false
        val pressEvent = event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS &&
            event.stylusSideButtonDown()
        val releaseEvent = event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE
        val held = event.stylusSideButtonDown()
        val newExplicitPress = pressEvent && !stylusButtonPressed &&
            event.eventTime != lastStylusButtonPressEventTime
        val newHeldEdge = observeHeldState && held && !stylusButtonPressed
        if (newExplicitPress || newHeldEdge) {
            lastStylusButtonPressEventTime = event.eventTime
            stylusButtonPressed = true
            when {
                stylusMenuExpanded -> closeStylusMenu()
                inputView.hasActiveGesture -> Unit
                else -> showStylusMenu()
            }
            return true
        }
        if (releaseEvent || (observeHeldState && !held)) stylusButtonPressed = false
        return pressEvent || releaseEvent
    }

    private fun closeStylusMenu() {
        Log.d(PEN_INPUT_LOG_TAG, "menu close")
        stylusMenuExpanded = false
        stylusMenuOverlay.clearMenuInputRegion()
        stylusMenuOverlay.visibility = View.INVISIBLE
    }

    private fun cancelActiveEraserInput() {
        inputView.cancelActiveEraserGesture()
        activeEraserPreviewId?.let(eraserTargets::remove)
        activeEraserPreviewId = null
        dryInkView.eraserPreview = null
    }

    private fun canErase(state: ReaderUiState, page: Int): Boolean =
        state.documentReady &&
            state.storageAvailable &&
            state.capabilities.canWrite &&
            page == state.pageNumber &&
            (state.role != ReaderRole.STUDENT || state.currentAttemptWritable)

    private fun showStylusMenu() {
        Log.d(PEN_INPUT_LOG_TAG, "menu show")
        // Make the already-laid-out host eligible for pointer dispatch before Compose publishes
        // the matching polar input region. Until that region arrives the overlay still returns
        // false, so the page underneath remains the owner of the S Pen stream.
        stylusMenuOverlay.visibility = View.VISIBLE
        val location = IntArray(2)
        stylusMenuOverlay.getLocationOnScreen(location)
        val raw = lastStylusRawPosition.takeIf { it.x.isFinite() && it.y.isFinite() }
        stylusMenuAnchorInHost = if (raw == null) {
            Offset(stylusMenuOverlay.width / 2f, stylusMenuOverlay.height / 2f)
        } else {
            Offset(raw.x - location[0], raw.y - location[1])
        }
        dryInkView.hoverPreview = null
        stylusMenuExpanded = true
    }

    private fun rememberStylusPosition(event: MotionEvent) {
        if (event.pointerCount <= 0) return
        val index = event.actionIndex.coerceIn(0, event.pointerCount - 1)
        val toolType = event.getToolType(index)
        if (toolType != MotionEvent.TOOL_TYPE_STYLUS && toolType != MotionEvent.TOOL_TYPE_ERASER) return
        if (!event.rawX.isFinite() || !event.rawY.isFinite()) return
        // Dedicated button events on some Samsung builds report 0,0. Preserve the last real hover
        // or contact coordinate instead of opening the fan at the status-bar corner.
        if (event.rawX == 0f && event.rawY == 0f && lastStylusRawPosition != Offset.Unspecified) return
        lastStylusRawPosition = Offset(event.rawX, event.rawY)
    }

    /**
     * A handed-in attempt is shown behind a soft blur so it reads as finished rather than editable.
     * The chrome and its banner sit in their own views above this, so they stay sharp.
     */
    private fun applySubmittedBlur(submitted: Boolean) {
        if (submitted == submittedBlurApplied) return
        submittedBlurApplied = submitted
        val effect = if (submitted) {
            val radius = dp(SUBMITTED_BLUR_RADIUS_DP)
            RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
        } else {
            null
        }
        listOf(pdfContainer, dryInkView).forEach { view ->
            runCatching { view.setRenderEffect(effect) }
        }
    }

    private fun applySystemBarInsets(root: FrameLayout, fragmentContainer: FragmentContainerView) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val bars: Insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            systemBarInsets = bars
            topChrome.updateFrameLayoutParams { topMargin = bars.top }
            stylusMenuOverlay.updateFrameLayoutParams {
                topMargin = bars.top
                bottomMargin = bars.bottom
            }
            listOf(
                fragmentContainer,
                dryInkView,
                wetInkView,
                inputView,
                studentExplanationOverlay,
            ).forEach { view ->
                view.updateFrameLayoutParams { bottomMargin = bars.bottom }
            }
            updateMessageOverlayPosition(root.height)
            updateAnswerPopupBounds(restoreSavedPosition = false)
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun updateMessageOverlayPosition(parentHeight: Int) {
        if (!::messageOverlayHost.isInitialized || parentHeight <= 0) return
        val desiredTop = (parentHeight * MESSAGE_OVERLAY_VERTICAL_FRACTION).toInt()
        val safeTop = systemBarInsets.top + dp(TOP_CHROME_HEIGHT + MESSAGE_OVERLAY_SAFE_GAP_DP)
        val safeBottom = parentHeight - systemBarInsets.bottom - dp(MESSAGE_OVERLAY_BOTTOM_MARGIN_DP)
        val maxTop = (safeBottom - messageOverlayHost.measuredHeight).coerceAtLeast(safeTop)
        val top = desiredTop.coerceIn(safeTop, maxTop)
        val params = messageOverlayHost.layoutParams as FrameLayout.LayoutParams
        if (params.topMargin == top) return
        params.topMargin = top
        messageOverlayHost.layoutParams = params
    }

    private inline fun View.updateFrameLayoutParams(block: FrameLayout.LayoutParams.() -> Unit) {
        layoutParams = (layoutParams as FrameLayout.LayoutParams).apply(block)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun clearMarkSelection() {
        selectedMarkGroupId = null
        selectedMarkTarget = null
        dryInkView.pressedMarkGroupId = null
    }

    /**
     * Return to this book's page overview even when Reader was restored directly from a debug
     * session or launched without an existing LibraryActivity underneath it.
     */
    private fun returnToBookOverview() {
        val libraryIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (libraryIntent == null) {
            finish()
            return
        }
        libraryIntent
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_RETURN_LIBRARY_BOOK_ID, bookId)
            .putExtra(EXTRA_RETURN_LIBRARY_TEACHER_VIEW, latestState.role != ReaderRole.STUDENT)
        startActivity(libraryIntent)
        finish()
    }

    companion object {
        private const val PDF_FRAGMENT_TAG = "reader-pdf"
        private const val PDF_CONTAINER_ID = 0x5100
        private const val TOP_CHROME_HEIGHT = 76
        private const val SUBMITTED_BLUR_RADIUS_DP = 5f
        private const val PEN_INPUT_LOG_TAG = "MasterNotePenInput"
        private const val REMOTE_MONITOR_LOG_TAG = "MasterNoteRemoteMonitor"
        private const val GPT_ASSISTANT_LOG_TAG = "MasterNoteGptAssistant"
        private const val ANSWER_PDF_LOG_TAG = "MasterNoteAnswerPdf"
        private const val GPT_CAPTURE_CACHE_DIRECTORY = "gpt-assistant"
        private const val GPT_CAPTURE_MAX_EDGE_PX = 1_800
        private const val GPT_CAPTURE_MAX_BYTES = 8L * 1024L * 1024L
        private const val GPT_CAPTURE_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
        private const val REMOTE_REVIEW_SETUP_ACTIVITY_CLASS =
            "com.studyink.app.RemoteReviewSetupActivity"
        private const val REMOTE_CHAT_ACTIVITY_CLASS = "com.studyink.app.RemotePeerChatActivity"
        private const val REMOTE_MONITOR_SERVICE_CLASS = "com.studyink.app.RemoteMonitorService"
        private const val PARENT_MESSAGE_MAX_WIDTH_DP = 560
        private const val PARENT_MESSAGE_SIDE_MARGIN_DP = 20
        private const val MESSAGE_OVERLAY_GAP_DP = 8
        private const val MESSAGE_OVERLAY_SAFE_GAP_DP = 8
        private const val MESSAGE_OVERLAY_BOTTOM_MARGIN_DP = 12
        private const val MESSAGE_OVERLAY_VERTICAL_FRACTION = 0.30f
        private const val ANSWER_POPUP_POSITION_PREFS = "answer-crop-popup-position"
        private const val ANSWER_POPUP_HEADER_HEIGHT_DP = 44
        private const val ANSWER_POPUP_MARGIN_DP = 8
        private const val ANSWER_POPUP_MIN_WIDTH_DP = 150
        private const val ANSWER_POPUP_MIN_IMAGE_HEIGHT_DP = 96
        private const val ANSWER_POPUP_MAX_WIDTH_FRACTION = 0.72f
        private const val ANSWER_POPUP_MAX_HEIGHT_FRACTION = 0.46f
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val EXTRA_BOOK_ID = "bookId"
        private const val EXTRA_PAGE_NUMBER = "pageNumber"
        private const val EXTRA_ATTEMPT_NUMBER = "attemptNumber"
        private const val EXTRA_ROLE = "role"
        private const val EXTRA_WORKFLOW = "workflow"
        private const val EXTRA_FOLLOW_REMOTE_STUDENT = "followRemoteStudent"
        const val EXTRA_RETURN_LIBRARY_BOOK_ID = "reader.returnLibraryBookId"
        const val EXTRA_RETURN_LIBRARY_TEACHER_VIEW = "reader.returnLibraryTeacherView"

        fun intent(
            context: Context,
            bookId: String,
            pageNumber: Int,
            role: ReaderRole = ReaderRole.STUDENT,
            attemptNo: Int? = null,
            workflow: ReaderWorkflow = ReaderWorkflow.defaultFor(role),
            followRemoteStudent: Boolean = workflow == ReaderWorkflow.LIVE_MONITOR,
        ) =
            Intent(context, ReaderActivity::class.java)
                .putExtra(EXTRA_BOOK_ID, bookId)
                .putExtra(EXTRA_PAGE_NUMBER, pageNumber)
                .putExtra(EXTRA_ROLE, role.name)
                .putExtra(EXTRA_WORKFLOW, workflow.name)
                .putExtra(EXTRA_FOLLOW_REMOTE_STUDENT, followRemoteStudent)
                .apply { attemptNo?.let { putExtra(EXTRA_ATTEMPT_NUMBER, it) } }
    }
}
