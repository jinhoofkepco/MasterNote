package com.studyink.reader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import com.studyink.core.model.MarkColor
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ReaderActivity : FragmentActivity(), ReaderPdfFragment.Listener {
    private val viewModel: ReaderViewModel by viewModels()
    private val viewport = PdfViewportAdapter()
    private lateinit var pdfContainer: FragmentContainerView
    private lateinit var dryInkView: DryInkView
    private lateinit var wetInkView: InProgressStrokesView
    private lateinit var inputView: InkInputView
    private lateinit var pdfFragment: ReaderPdfFragment
    private lateinit var topChrome: ComposeView
    private lateinit var stylusMenuOverlay: StylusMenuOverlayView
    private lateinit var messageOverlayHost: LinearLayout
    private lateinit var parentMessageOverlay: ParentMessageOverlayView
    private lateinit var studentStatusOverlay: ParentMessageOverlayView
    private lateinit var parentMessageSpeaker: ParentMessageSpeaker
    private lateinit var studentVoiceController: StudentVoiceMessageController
    private lateinit var remoteMonitorGateway: RemoteMonitorGateway
    private lateinit var peerChatAnnouncementStore: PeerChatAnnouncementStore
    private lateinit var bookId: String
    private lateinit var teacherAccess: TeacherAccessController

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        ensureS23StripStartsExpanded()

        val readerBackgroundColor = ReaderPaperBackdropDrawable.NAVIGATION_BAR_COLOR
        val root = FrameLayout(this).apply {
            background = ReaderPaperBackdropDrawable(resources.displayMetrics.density)
        }
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
            if (bottom != oldBottom) updateMessageOverlayPosition(bottom)
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
                    inputView.isEnabled = state.documentReady &&
                        state.capabilities.canWrite &&
                        state.storageAvailable &&
                        !state.submissionInProgress
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

    override fun onStart() {
        super.onStart()
        readerStarted = true
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
        parentMessageSubscription?.close()
        preferenceSubscription?.close()
        peerChatSubscription?.close()
        if (::studentVoiceController.isInitialized) studentVoiceController.close()
        if (::parentMessageSpeaker.isInitialized) parentMessageSpeaker.close()
        super.onDestroy()
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
            listOf(fragmentContainer, dryInkView, wetInkView, inputView).forEach { view ->
                view.updateFrameLayoutParams { bottomMargin = bars.bottom }
            }
            updateMessageOverlayPosition(root.height)
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
