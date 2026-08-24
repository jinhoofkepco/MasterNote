package com.studyink.library.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.studyink.backup.storage.BackupInspection
import com.studyink.backup.storage.BackupResult
import com.studyink.backup.storage.RestoreResult
import com.studyink.core.model.Book
import com.studyink.core.model.MarkColor
import com.studyink.core.model.MasterNoteDataCommitBus
import com.studyink.monitor.core.RemoteMonitorMaintenanceBus
import com.studyink.core.model.Student
import com.studyink.core.model.TEACHER_PAGE_REVIEW_ATTEMPT_NO
import com.studyink.core.model.resultBundleGrid
import com.studyink.library.data.AttemptProgressStatus
import com.studyink.library.data.AttemptProgressSummary
import com.studyink.library.data.AttemptGradeSummary
import com.studyink.library.data.LibraryContext
import com.studyink.library.data.LibraryPerspective
import com.studyink.sync.lan.LanConnectionState
import com.studyink.library.data.LibraryRepository
import com.studyink.library.data.LibraryState
import com.studyink.library.data.PageGradeSnapshot
import com.studyink.library.data.PageProgressStatus
import com.studyink.library.data.PageProgressSummary
import com.studyink.library.data.ProblemGradeSummary
import com.studyink.reader.ReaderActivity
import com.studyink.reader.ReaderDebugSessionStore
import com.studyink.reader.ReaderRole
import com.studyink.reader.ReaderWorkflow
import com.studyink.monitor.telegram.RemoteMonitorGateway
import com.studyink.monitor.telegram.RemoteReviewPeerStatus
import com.studyink.sync.lan.LanSyncService
import com.studyink.sync.lan.LanSyncBus
import com.studyink.sync.lan.PairingPayload
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private const val STATE_SELECTED_BOOK_ID = "library.selectedBookId"
private const val STATE_PERSPECTIVE = "library.perspective"
private const val KEY_AUTO_START_STUDENT_SYNC = "autoStartStudentSync"
private const val RESTORE_SYNC_STOP_TIMEOUT_MILLIS = 10_000L
private const val RESTORE_COMMIT_QUIET_MILLIS = 750L

private data class RestoreBackupCandidate(
    val uri: Uri,
    val inspection: BackupInspection,
)

class LibraryActivity : ComponentActivity(), LanSyncBus.Listener {
    private val repository by lazy { LibraryRepository.get(this) }
    private var state by mutableStateOf<LibraryState?>(null)
    private var selectedBook by mutableStateOf<Book?>(null)
    private var perspective by mutableStateOf(LibraryPerspective.STUDENT)
    private var progressRevision by mutableStateOf(0)
    private var importing by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)
    private var renameTarget by mutableStateOf<Book?>(null)
    private var answerTargetBookId: String? = null
    private var pendingSyncStart: (() -> Unit)? = null
    private var pairingUri by mutableStateOf<String?>(null)
    private var qrTargetBookId: String? = null
    private var showPairingQrOnReady = false
    private var autoStartStudentSync by mutableStateOf(true)
    private var backupStatus by mutableStateOf(MasterNoteBackupStatus(dirty = true))
    private var inspectingBackup by mutableStateOf(false)
    private var restoreBackupCandidate by mutableStateOf<RestoreBackupCandidate?>(null)
    private var restoreWorkflowRunning by mutableStateOf(false)
    private var handledRestoreRevision = 0L
    private var backupStatusSubscription: AutoCloseable? = null
    private val studentSyncPreferences by lazy {
        getSharedPreferences("masternote-student-sync", MODE_PRIVATE)
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingSyncStart?.invoke()
        pendingSyncStart = null
    }

    private val scanPairingQr = registerForActivityResult(ScanContract()) { result ->
        val targetBookId = qrTargetBookId.also { qrTargetBookId = null } ?: return@registerForActivityResult
        val value = result.contents ?: return@registerForActivityResult
        runCatching { PairingPayload.parse(Uri.parse(value)) }
            .onSuccess {
                startSyncSession {
                    LanSyncService.startTeacherPairing(this, targetBookId, value)
                    startActivity(
                        ReaderActivity.intent(
                            context = this,
                            bookId = targetBookId,
                            pageNumber = 0,
                            role = ReaderRole.TEACHER_PHONE,
                            workflow = ReaderWorkflow.LIVE_MONITOR,
                        )
                    )
                }
            }
            .onFailure { errorMessage = "MasterNote 연결 QR이 아닙니다." }
    }

    private val importPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        importing = true
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { repository.importPdf(repository.state.selectedStudentId, uri) }
                .onSuccess { book ->
                    withContext(Dispatchers.Main) {
                        state = repository.state
                        selectedBook = book
                        importing = false
                    }
                }
                .onFailure { error ->
                    withContext(Dispatchers.Main) {
                        errorMessage = error.message ?: "교재를 가져오지 못했습니다."
                        importing = false
                    }
                }
        }
    }

    private val importAnswers = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val bookId = answerTargetBookId.also { answerTargetBookId = null } ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { repository.importAnswerSource(bookId, uri) }
                .onSuccess { withContext(Dispatchers.Main) { state = repository.state; selectedBook = repository.book(bookId) } }
                .onFailure { error -> withContext(Dispatchers.Main) {
                    errorMessage = error.message ?: "정답 JSON을 가져오지 못했습니다."
                } }
        }
    }

    private val selectBackupToRestore = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        inspectingBackup = true
        lifecycleScope.launch {
            runCatching { MasterNoteBackupCoordinator.inspect(uri) }
                .onSuccess { inspection ->
                    restoreBackupCandidate = RestoreBackupCandidate(uri, inspection)
                }
                .onFailure { error ->
                    errorMessage = error.message ?: "선택한 백업 파일을 확인하지 못했습니다."
                }
            inspectingBackup = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MasterNoteBackupCoordinator.initialize(applicationContext)
        backupStatus = MasterNoteBackupCoordinator.currentStatus()
        handledRestoreRevision = backupStatus.restoreRevision
        state = repository.state
        autoStartStudentSync = studentSyncPreferences.getBoolean(KEY_AUTO_START_STUDENT_SYNC, true)
        perspective = savedInstanceState?.getString(STATE_PERSPECTIVE)
            ?.let { saved -> runCatching { LibraryPerspective.valueOf(saved) }.getOrNull() }
            ?: LibraryPerspective.STUDENT
        selectedBook = savedInstanceState?.getString(STATE_SELECTED_BOOK_ID)
            ?.let { savedId -> repository.state.books.firstOrNull { it.id == savedId } }
        val returnedFromReader = applyReaderReturnTarget(intent)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = LibraryBackground) {
                    LibraryScreen(
                        state = state ?: return@Surface,
                        selectedBook = selectedBook,
                        perspective = perspective,
                        progressRevision = progressRevision,
                        importing = importing,
                        backupStatus = backupStatus,
                        inspectingBackup = inspectingBackup,
                        progressForBook = { bookId ->
                            repository.pageProgressSummaries(
                                LibraryContext(repository.state.selectedStudentId, perspective),
                                bookId,
                            )
                        },
                        onSelectStudent = { id ->
                            repository.selectStudent(id)
                            state = repository.state
                            selectedBook = null
                        },
                        onImport = { importPdf.launch(arrayOf("application/pdf")) },
                        onBackupNow = ::createBackupNow,
                        onSelectBackup = {
                            selectBackupToRestore.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/octet-stream",
                                    "application/x-zip-compressed",
                                )
                            )
                        },
                        onSelectBook = { book ->
                            selectedBook = book
                            maybeAutoStartStudentSync(book)
                        },
                        onSelectPerspective = { perspective = it },
                        onBackToBooks = { selectedBook = null },
                        onOpenPage = { book, page, selectedPerspective, expanded, attemptNo ->
                            val role = when (selectedPerspective) {
                                LibraryPerspective.STUDENT -> ReaderRole.STUDENT
                                LibraryPerspective.TEACHER -> if (expanded) {
                                    ReaderRole.TEACHER_TABLET
                                } else {
                                    ReaderRole.TEACHER_PHONE
                                }
                            }
                            val readerWorkflow = when (selectedPerspective) {
                                LibraryPerspective.STUDENT -> ReaderWorkflow.STUDY
                                LibraryPerspective.TEACHER -> {
                                    val opensCurrentStudentWork = role == ReaderRole.TEACHER_PHONE &&
                                        attemptNo != null &&
                                        repository.attempts(book.id, page).any { attempt ->
                                            attempt.attemptNo == attemptNo && !attempt.locked
                                        }
                                    if (opensCurrentStudentWork) {
                                        ReaderWorkflow.LIVE_MONITOR
                                    } else {
                                        ReaderWorkflow.REVIEW
                                    }
                                }
                            }
                            startActivity(
                                ReaderActivity.intent(
                                    context = this,
                                    bookId = book.id,
                                    pageNumber = page,
                                    role = role,
                                    attemptNo = attemptNo,
                                    workflow = readerWorkflow,
                                    // A page card is an explicit teacher navigation request. Keep
                                    // LIVE sync active, but do not let the retained student cursor
                                    // replace the page the teacher just chose.
                                    followRemoteStudent = false,
                                )
                            )
                        },
                        onRename = { renameTarget = it },
                        onImportAnswers = { book ->
                            answerTargetBookId = book.id
                            importAnswers.launch(arrayOf("application/json", "text/json", "text/plain"))
                        },
                        onStartStudentSync = { book ->
                            val running = LanSyncBus.pairingUri(book.id)
                                ?.takeIf { LanSyncBus.connectionState(book.id) != LanConnectionState.IDLE }
                            if (running != null) {
                                // Usually already auto-started. Show that session's code rather than
                                // restarting and dropping a teacher who may be connected to it.
                                pairingUri = running
                            } else {
                                pairingUri = null
                                showPairingQrOnReady = true
                                startSyncSession { LanSyncService.startStudent(this, book.id) }
                            }
                        },
                        onStartTeacherSync = { book ->
                            startSyncSession {
                                LanSyncService.startTeacher(this, book.id)
                                startActivity(
                                    ReaderActivity.intent(
                                        context = this,
                                        bookId = book.id,
                                        pageNumber = 0,
                                        role = ReaderRole.TEACHER_PHONE,
                                        workflow = ReaderWorkflow.LIVE_MONITOR,
                                    )
                                )
                            }
                        },
                        onScanTeacherQr = { book ->
                            qrTargetBookId = book.id
                            scanPairingQr.launch(
                                ScanOptions()
                                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    .setPrompt("학생 기기의 연결 QR을 비춰 주세요")
                                    .setBeepEnabled(false)
                                    .setOrientationLocked(false),
                            )
                        },
                        onStopSync = { LanSyncService.stop(this) },
                    )
                    errorMessage?.let { message ->
                        AlertDialog(
                            onDismissRequest = { errorMessage = null },
                            title = { Text("확인 필요") },
                            text = { Text(message) },
                            confirmButton = { TextButton(onClick = { errorMessage = null }) { Text("확인") } },
                        )
                    }
                    pairingUri?.let { uri ->
                        PairingQrDialog(
                            uri = uri,
                            autoStart = autoStartStudentSync,
                            onAutoStartChange = ::updateAutoStartStudentSync,
                            onDismiss = { pairingUri = null },
                        )
                    }
                    renameTarget?.let { book ->
                        RenameBookDialog(
                            book = book,
                            onDismiss = { renameTarget = null },
                            onSave = { title ->
                                repository.renameBook(book.id, title)
                                state = repository.state
                                selectedBook = repository.book(book.id)
                                renameTarget = null
                            },
                        )
                    }
                    restoreBackupCandidate?.let { candidate ->
                        RestoreBackupDialog(
                            candidate = candidate,
                            onDismiss = { restoreBackupCandidate = null },
                            onConfirm = { restoreBackup(candidate) },
                        )
                    }
                    if (restoreWorkflowRunning || backupStatus.isRestoring) {
                        AlertDialog(
                            onDismissRequest = {},
                            title = { Text("안전하게 복원하는 중") },
                            text = {
                                Text(
                                    when {
                                        backupStatus.isRestoring -> "검증한 백업 데이터로 교체하고 있습니다."
                                        backupStatus.isBackingUp -> "현재 필기와 문제집을 먼저 별도 보관소에 백업하고 있습니다."
                                        else -> "실시간 연결을 안전하게 종료하고 있습니다."
                                    }
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {}, enabled = false) { Text("작업 중") }
                            },
                        )
                    }
                }
            }
        }
        if (savedInstanceState == null && !returnedFromReader) {
            ReaderDebugSessionStore.load(this)?.takeIf { session ->
                repository.state.books.any { it.id == session.bookId }
            }?.let { session ->
                startActivity(
                    ReaderActivity.intent(
                        context = this,
                        bookId = session.bookId,
                        pageNumber = session.pageNumber,
                        role = session.role,
                        attemptNo = session.attemptNo,
                        workflow = session.workflow,
                    )
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyReaderReturnTarget(intent)
    }

    private fun applyReaderReturnTarget(source: Intent): Boolean {
        val targetBookId = source.getStringExtra(ReaderActivity.EXTRA_RETURN_LIBRARY_BOOK_ID)
            ?: return false
        val teacherView = source.getBooleanExtra(
            ReaderActivity.EXTRA_RETURN_LIBRARY_TEACHER_VIEW,
            false,
        )
        source.removeExtra(ReaderActivity.EXTRA_RETURN_LIBRARY_BOOK_ID)
        source.removeExtra(ReaderActivity.EXTRA_RETURN_LIBRARY_TEACHER_VIEW)
        val book = repository.state.books.firstOrNull { it.id == targetBookId } ?: return true
        repository.selectStudent(book.studentId)
        state = repository.state
        selectedBook = book
        perspective = if (teacherView) LibraryPerspective.TEACHER else LibraryPerspective.STUDENT
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SELECTED_BOOK_ID, selectedBook?.id)
        outState.putString(STATE_PERSPECTIVE, perspective.name)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        state = repository.state
        progressRevision += 1
    }

    override fun onStart() {
        super.onStart()
        if (RemoteMonitorGateway.get(this).preferences().monitoringEnabled) {
            startForegroundService(
                Intent().setClassName(packageName, "com.studyink.app.RemoteMonitorService"),
            )
        }
        backupStatusSubscription?.close()
        backupStatusSubscription = MasterNoteBackupCoordinator.addListener(::onBackupStatusChanged)
        LanSyncBus.addListener(this)
    }

    override fun onStop() {
        backupStatusSubscription?.close()
        backupStatusSubscription = null
        LanSyncBus.removeListener(this)
        super.onStop()
    }

    private fun createBackupNow() {
        if (backupStatus.busy || inspectingBackup) return
        lifecycleScope.launch {
            when (val result = MasterNoteBackupCoordinator.createManualBackup()) {
                is BackupResult.Success -> Unit
                is BackupResult.Failure -> {
                    errorMessage = result.message
                }
            }
        }
    }

    private fun onBackupStatusChanged(updated: MasterNoteBackupStatus) {
        backupStatus = updated
        if (updated.restoreRevision <= handledRestoreRevision) return
        handledRestoreRevision = updated.restoreRevision
        restoreWorkflowRunning = false
        selectedBook = null
        // This also covers rotation/Activity replacement while the Application-owned restore task
        // was running. Every currently visible LibraryActivity drops any pre-swap repository state.
        recreate()
    }

    private fun restoreBackup(candidate: RestoreBackupCandidate) {
        if (backupStatus.busy || inspectingBackup) return
        restoreBackupCandidate = null
        restoreWorkflowRunning = true
        lifecycleScope.launch {
            if (!stopLanSyncAndAwaitQuiet()) {
                restoreWorkflowRunning = false
                errorMessage = "실시간 연결이 완전히 종료되지 않아 복원을 중단했습니다. 현재 데이터는 변경하지 않았습니다."
                return@launch
            }
            try {
                // A restore is destructive. Do not touch the live data unless the current installation
                // has first been published to the uninstall-safe Downloads folder. Protecting the
                // selected URI also prevents retention cleanup from deleting an older chosen backup.
                when (val safetyBackup = MasterNoteBackupCoordinator.createManualBackup(candidate.uri)) {
                    is BackupResult.Failure -> {
                        restoreWorkflowRunning = false
                        errorMessage = "복원 전 안전 백업에 실패했습니다. 현재 데이터는 변경하지 않았습니다.\n${safetyBackup.message}"
                        return@launch
                    }

                    is BackupResult.Success -> Unit
                }

                // A /화면 request freezes a live book/page identity. Once the safety backup has
                // succeeded, discard it before swapping roots so it cannot be reinterpreted
                // against unrelated restored content with coincidentally identical identifiers.
                val remoteMonitor = RemoteMonitorGateway.get(this@LibraryActivity)
                remoteMonitor.pendingScreenRequests().forEach { request ->
                    remoteMonitor.acknowledgeScreenRequest(request.updateId)
                }
                when (val result = MasterNoteBackupCoordinator.restoreReplace(candidate.uri)) {
                    is RestoreResult.Success -> {
                        // The coordinator increments restoreRevision. The status listener recreates
                        // whichever LibraryActivity is currently visible, including one replaced while
                        // this Application-owned IO operation was still running.
                        restoreWorkflowRunning = false
                    }

                    is RestoreResult.Failure -> {
                        restoreWorkflowRunning = false
                        errorMessage = result.message
                    }
                }
            } finally {
                RemoteMonitorMaintenanceBus.resume()
                restartRemoteMonitorIfEnabled()
            }
        }
    }

    private suspend fun stopLanSyncAndAwaitQuiet(): Boolean {
        val rendererQuiet = withContext(Dispatchers.IO) {
            RemoteMonitorMaintenanceBus.pauseAndAwait(RESTORE_SYNC_STOP_TIMEOUT_MILLIS)
        }
        if (!rendererQuiet) {
            RemoteMonitorMaintenanceBus.resume()
            return false
        }
        val activeBookIds = repository.state.books.map(Book::id).distinct()
        // No Telegram command may start a page render while the live data root is being swapped.
        RemoteMonitorGateway.get(this).stop()
        stopService(
            Intent().setClassName(packageName, "com.studyink.app.RemoteMonitorService"),
        )
        LanSyncService.stop(this)
        val quiet = withTimeoutOrNull(RESTORE_SYNC_STOP_TIMEOUT_MILLIS) {
            while (activeBookIds.any { LanSyncBus.connectionState(it) != LanConnectionState.IDLE }) {
                delay(50L)
            }

            // IDLE is published at the start of service teardown. Require a quiet durable-commit
            // window too, so an operation already executing on the service IO thread cannot land
            // between the safety snapshot and the destructive swap.
            var observedGeneration = MasterNoteDataCommitBus.currentGeneration()
            while (true) {
                delay(RESTORE_COMMIT_QUIET_MILLIS)
                val currentGeneration = MasterNoteDataCommitBus.currentGeneration()
                if (currentGeneration == observedGeneration) break
                observedGeneration = currentGeneration
            }
            true
        } ?: false
        if (!quiet) {
            RemoteMonitorMaintenanceBus.resume()
            restartRemoteMonitorIfEnabled()
        }
        return quiet
    }

    private fun restartRemoteMonitorIfEnabled() {
        val remoteMonitor = RemoteMonitorGateway.get(this)
        if (!remoteMonitor.preferences().monitoringEnabled &&
            remoteMonitor.remoteReviewPeerStatus() is RemoteReviewPeerStatus.Unconfigured
        ) return
        startForegroundService(
            Intent().setClassName(packageName, "com.studyink.app.RemoteMonitorService"),
        )
    }

    override fun onPairingReady(bookId: String, pairingUri: String) {
        runOnUiThread {
            // An auto-started session must not throw its code on screen every time a book is opened.
            if (!showPairingQrOnReady) return@runOnUiThread
            showPairingQrOnReady = false
            if (selectedBook?.id == bookId) this.pairingUri = pairingUri
        }
    }

    private fun updateAutoStartStudentSync(enabled: Boolean) {
        autoStartStudentSync = enabled
        studentSyncPreferences.edit().putBoolean(KEY_AUTO_START_STUDENT_SYNC, enabled).apply()
    }

    /**
     * Makes a student device reachable as soon as its book is open, so nobody has to remember to
     * start sharing first. Skipped when a session already exists for this book, so re-entering the
     * book never drops one a teacher is using, and skipped when the notification permission is still
     * missing rather than raising a system prompt out of nowhere.
     */
    private fun maybeAutoStartStudentSync(book: Book) {
        if (!autoStartStudentSync || perspective != LibraryPerspective.STUDENT) return
        if (LanSyncBus.connectionState(book.id) != LanConnectionState.IDLE) return
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        LanSyncService.startStudent(this, book.id)
    }

    override fun onRemoteAttempt(bookId: String, pageNumber: Int) {
        refreshSyncedProgress(bookId)
    }

    override fun onRemoteMarkGroup(bookId: String, pageNumber: Int) {
        refreshSyncedProgress(bookId)
    }

    override fun onSessionIssue(message: String) {
        runOnUiThread { errorMessage = message }
    }

    private fun refreshSyncedProgress(bookId: String) {
        runOnUiThread {
            if (repository.state.books.any { it.id == bookId }) {
                state = repository.state
                progressRevision += 1
            }
        }
    }

    private fun startSyncSession(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingSyncStart = action
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }
}

private val LibraryBackground = Color(0xFFF2EEE5)
private val PaperIvory = Color(0xFFFFFCF5)
private val PaperInk = Color(0xFF403D36)
private val PaperMutedInk = Color(0xFF777066)
private val PaperHighlight = Color(0xFFFFFFFF)
private val PaperStroke = Color(0xFFD9D1C3)
private val PaperFiber = Color(0xFF9C907E)
private val PaperWarmFiber = Color(0xFFC6B79F)
private val PaperYellow = Color(0xFFF2C94C)
private val PaperYellowSoft = Color(0xFFFFF2B8)
private val ReviewTealSoft = Color(0xFFDDF3ED)
private val ProgressNeutral = Color(0xFFD8D0C5)
private val ProgressWorking = Color(0xFFF2C94C)
private val ProgressSubmitted = Color(0xFFEF8D3D)
private val ProgressReview = Color(0xFF4F83CC)
private val ProgressTeal = Color(0xFF25A58F)
private val GradeCorrect = Color(0xFF5B8FE6)
private val GradeWrong = Color(0xFFEA7378)
private val GradeUnanswered = Color(0xFFBFC2C7)
private val GradePageLevel = Color(0xFF27A38E)

private data class PaperSpeck(
    val center: Offset,
    val radius: Float,
    val color: Color,
)

private data class PaperFiberStroke(
    val start: Offset,
    val end: Offset,
    val strokeWidth: Float,
    val color: Color,
)

private data class PaperMottle(
    val center: Offset,
    val radius: Float,
    val brush: Brush,
)

/** Stable pseudo-random unit value so the paper never shimmers between redraws. */
private fun paperNoise(index: Int, channel: Int): Float {
    val raw = sin((index + 1) * 12.9898 + (channel + 1) * 78.233) * 43_758.5453
    return (raw - floor(raw)).toFloat()
}

@Composable
private fun LibraryScreen(
    state: LibraryState,
    selectedBook: Book?,
    perspective: LibraryPerspective,
    progressRevision: Int,
    importing: Boolean,
    backupStatus: MasterNoteBackupStatus,
    inspectingBackup: Boolean,
    progressForBook: (String) -> List<PageProgressSummary>,
    onSelectStudent: (String) -> Unit,
    onSelectPerspective: (LibraryPerspective) -> Unit,
    onImport: () -> Unit,
    onBackupNow: () -> Unit,
    onSelectBackup: () -> Unit,
    onSelectBook: (Book) -> Unit,
    onBackToBooks: () -> Unit,
    onOpenPage: (Book, Int, LibraryPerspective, Boolean, Int?) -> Unit,
    onRename: (Book) -> Unit,
    onImportAnswers: (Book) -> Unit,
    onStartStudentSync: (Book) -> Unit,
    onStartTeacherSync: (Book) -> Unit,
    onScanTeacherQr: (Book) -> Unit,
    onStopSync: () -> Unit,
) {
    PaperBackdrop {
        BoxWithConstraints(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 1120.dp)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            val expanded = maxWidth >= 600.dp
            val outerHorizontal = if (expanded) 24.dp else 12.dp
            val outerVertical = if (expanded) 20.dp else 10.dp
            val visibleBooks = remember(state.books, state.selectedStudentId) {
                state.books.filter { it.studentId == state.selectedStudentId }
            }
            val visibleSelectedBook = selectedBook?.takeIf { it.studentId == state.selectedStudentId }
            val summariesByBook = remember(visibleBooks, progressRevision) {
                visibleBooks.associate { it.id to progressForBook(it.id) }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = outerHorizontal, vertical = outerVertical),
                verticalArrangement = Arrangement.spacedBy(if (expanded) 16.dp else 10.dp),
            ) {
                LibraryAppHeader(
                    selectedBook = visibleSelectedBook,
                    studentName = state.students.firstOrNull { it.id == state.selectedStudentId }?.displayName
                        ?: "학생",
                    perspective = perspective,
                )

                if (expanded) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        LibraryContextPanel(
                            modifier = Modifier.width(224.dp),
                            state = state,
                            perspective = perspective,
                            onSelectStudent = onSelectStudent,
                            onSelectPerspective = onSelectPerspective,
                        )
                        LibraryMainContent(
                            modifier = Modifier.weight(1f),
                            books = visibleBooks,
                            selectedBook = visibleSelectedBook,
                            perspective = perspective,
                            expanded = true,
                            importing = importing,
                            backupStatus = backupStatus,
                            inspectingBackup = inspectingBackup,
                            summariesByBook = summariesByBook,
                            onImport = onImport,
                            onBackupNow = onBackupNow,
                            onSelectBackup = onSelectBackup,
                            onSelectBook = onSelectBook,
                            onBackToBooks = onBackToBooks,
                            onOpenPage = onOpenPage,
                            onRename = onRename,
                            onImportAnswers = onImportAnswers,
                            onStartStudentSync = onStartStudentSync,
                            onStartTeacherSync = onStartTeacherSync,
                            onScanTeacherQr = onScanTeacherQr,
                            onStopSync = onStopSync,
                        )
                    }
                } else {
                    LibraryContextPanel(
                        modifier = Modifier.fillMaxWidth(),
                        state = state,
                        perspective = perspective,
                        onSelectStudent = onSelectStudent,
                        onSelectPerspective = onSelectPerspective,
                    )
                    LibraryMainContent(
                        modifier = Modifier.weight(1f),
                        books = visibleBooks,
                        selectedBook = visibleSelectedBook,
                        perspective = perspective,
                        expanded = false,
                        importing = importing,
                        backupStatus = backupStatus,
                        inspectingBackup = inspectingBackup,
                        summariesByBook = summariesByBook,
                        onImport = onImport,
                        onBackupNow = onBackupNow,
                        onSelectBackup = onSelectBackup,
                        onSelectBook = onSelectBook,
                        onBackToBooks = onBackToBooks,
                        onOpenPage = onOpenPage,
                        onRename = onRename,
                        onImportAnswers = onImportAnswers,
                        onStartStudentSync = onStartStudentSync,
                        onStartTeacherSync = onStartTeacherSync,
                        onScanTeacherQr = onScanTeacherQr,
                        onStopSync = onStopSync,
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryAppHeader(
    selectedBook: Book?,
    studentName: String,
    perspective: LibraryPerspective,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = if (selectedBook == null) "MASTERNOTE" else "$studentName · ${perspective.label}",
                color = PaperMutedInk,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = selectedBook?.title ?: "내 책장",
                color = PaperInk,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selectedBook != null) {
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                color = if (perspective == LibraryPerspective.STUDENT) PaperYellowSoft else ReviewTealSoft,
                border = paperEdge(),
            ) {
                Text(
                    text = if (perspective == LibraryPerspective.STUDENT) "학습" else "검토",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = PaperInk,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun LibraryContextPanel(
    modifier: Modifier,
    state: LibraryState,
    perspective: LibraryPerspective,
    onSelectStudent: (String) -> Unit,
    onSelectPerspective: (LibraryPerspective) -> Unit,
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    Surface(
        modifier = modifier,
        shape = shape,
        color = PaperIvory.copy(alpha = 0.88f),
        border = paperEdge(),
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .paperSurfaceTexture(intensity = 0.72f, seed = 401)
                .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BoxWithConstraints(
                modifier = Modifier.weight(1f),
            ) {
                val visibleSlots = state.students.size.coerceIn(1, 2)
                val studentChipWidth = (maxWidth - 2.dp * (visibleSlots - 1)) / visibleSlots
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(state.students, key = Student::id) { student ->
                        StudentPaperChip(
                            modifier = Modifier.width(studentChipWidth),
                            name = student.displayName,
                            selected = student.id == state.selectedStudentId,
                            onClick = { onSelectStudent(student.id) },
                        )
                    }
                }
            }
            Spacer(
                Modifier
                    .width(1.dp)
                    .height(24.dp)
                    .background(PaperStroke.copy(alpha = 0.7f)),
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                PerspectivePaperChip(
                    modifier = Modifier.weight(1f),
                    label = "학생",
                    selected = perspective == LibraryPerspective.STUDENT,
                    onClick = { onSelectPerspective(LibraryPerspective.STUDENT) },
                )
                PerspectivePaperChip(
                    modifier = Modifier.weight(1f),
                    label = "선생님",
                    selected = perspective == LibraryPerspective.TEACHER,
                    onClick = { onSelectPerspective(LibraryPerspective.TEACHER) },
                )
            }
        }
    }
}

@Composable
private fun LibraryMainContent(
    modifier: Modifier,
    books: List<Book>,
    selectedBook: Book?,
    perspective: LibraryPerspective,
    expanded: Boolean,
    importing: Boolean,
    backupStatus: MasterNoteBackupStatus,
    inspectingBackup: Boolean,
    summariesByBook: Map<String, List<PageProgressSummary>>,
    onImport: () -> Unit,
    onBackupNow: () -> Unit,
    onSelectBackup: () -> Unit,
    onSelectBook: (Book) -> Unit,
    onBackToBooks: () -> Unit,
    onOpenPage: (Book, Int, LibraryPerspective, Boolean, Int?) -> Unit,
    onRename: (Book) -> Unit,
    onImportAnswers: (Book) -> Unit,
    onStartStudentSync: (Book) -> Unit,
    onStartTeacherSync: (Book) -> Unit,
    onScanTeacherQr: (Book) -> Unit,
    onStopSync: () -> Unit,
) {
    if (selectedBook == null) {
        BookShelfContent(
            modifier = modifier,
            books = books,
            expanded = expanded,
            importing = importing,
            backupStatus = backupStatus,
            inspectingBackup = inspectingBackup,
            // Teacher devices also need this entry to pair their dedicated bot and open the
            // remote-review inbox. Student-only controls remain gated inside each setup screen.
            showTelegramSetup = true,
            summariesByBook = summariesByBook,
            onImport = onImport,
            onBackupNow = onBackupNow,
            onSelectBackup = onSelectBackup,
            onSelectBook = onSelectBook,
            onRename = onRename,
        )
    } else {
        BookPageContent(
            modifier = modifier,
            book = selectedBook,
            summaries = summariesByBook[selectedBook.id].orEmpty(),
            perspective = perspective,
            expanded = expanded,
            onBackToBooks = onBackToBooks,
            onOpenPage = onOpenPage,
            onImportAnswers = onImportAnswers,
            onStartStudentSync = onStartStudentSync,
            onStartTeacherSync = onStartTeacherSync,
            onScanTeacherQr = onScanTeacherQr,
            onStopSync = onStopSync,
        )
    }
}

@Composable
private fun BookShelfContent(
    modifier: Modifier,
    books: List<Book>,
    expanded: Boolean,
    importing: Boolean,
    backupStatus: MasterNoteBackupStatus,
    inspectingBackup: Boolean,
    showTelegramSetup: Boolean,
    summariesByBook: Map<String, List<PageProgressSummary>>,
    onImport: () -> Unit,
    onBackupNow: () -> Unit,
    onSelectBackup: () -> Unit,
    onSelectBook: (Book) -> Unit,
    onRename: (Book) -> Unit,
) {
    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("문제집", color = PaperInk, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${books.size}권", color = PaperMutedInk, style = MaterialTheme.typography.labelMedium)
            }
            val importButtonShape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
            Button(
                modifier = Modifier
                    .clip(importButtonShape)
                    .paperSurfaceTexture(intensity = 0.42f, seed = 607, overlay = true),
                onClick = onImport,
                enabled = !importing,
                shape = importButtonShape,
                colors = ButtonDefaults.buttonColors(containerColor = PaperYellow, contentColor = PaperInk),
            ) {
                Text(if (importing) "가져오는 중" else "+ PDF 가져오기")
            }
        }
        BackupControls(
            expanded = expanded,
            status = backupStatus,
            inspectingBackup = inspectingBackup,
            showTelegramSetup = showTelegramSetup,
            onBackupNow = onBackupNow,
            onSelectBackup = onSelectBackup,
        )
        if (books.isEmpty()) {
            EmptyLibraryNotice(Modifier.weight(1f))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (expanded) 2 else 1),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (expanded) 12.dp else 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 3.dp, horizontal = 1.dp),
            ) {
                gridItems(books, key = { it.id }) { book ->
                    CompactBookItem(
                        book = book,
                        summaries = summariesByBook[book.id].orEmpty(),
                        expanded = expanded,
                        onOpen = { onSelectBook(book) },
                        onRename = { onRename(book) },
                    )
                }
            }
        }
    }
}

@Composable
private fun BackupControls(
    expanded: Boolean,
    status: MasterNoteBackupStatus,
    inspectingBackup: Boolean,
    showTelegramSetup: Boolean,
    onBackupNow: () -> Unit,
    onSelectBackup: () -> Unit,
) {
    val context = LocalContext.current
    val controls: @Composable (Modifier) -> Unit = { modifier ->
        Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = onBackupNow,
                enabled = !status.busy && !inspectingBackup,
                border = paperEdge(),
            ) {
                Text("지금 백업", maxLines = 1)
            }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = onSelectBackup,
                enabled = !status.busy && !inspectingBackup,
                border = paperEdge(),
            ) {
                Text("백업 복원", maxLines = 1)
            }
            if (showTelegramSetup) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        context.startActivity(
                            Intent().setClassName(
                                context.packageName,
                                "com.studyink.app.TelegramSetupActivity",
                            ),
                        )
                    },
                    enabled = !status.busy && !inspectingBackup,
                    border = paperEdge(),
                ) {
                    Text("Telegram", maxLines = 1)
                }
            }
        }
    }
    val statusContent: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = backupStatusLabel(status, inspectingBackup),
                color = if (status.error == null) PaperMutedInk else GradeWrong,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "앱 삭제 후에도 유지 · 파일 앱 > Download/MasterNote Backups",
                color = PaperMutedInk.copy(alpha = 0.78f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (expanded) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            controls(Modifier.width(420.dp))
            statusContent(Modifier.weight(1f))
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            controls(Modifier.fillMaxWidth())
            statusContent(Modifier.fillMaxWidth())
        }
    }
}

private fun backupStatusLabel(status: MasterNoteBackupStatus, inspectingBackup: Boolean): String = when {
    inspectingBackup -> "백업 파일을 확인하는 중…"
    status.isRestoring -> "백업을 복원하는 중…"
    status.isBackingUp -> "필기와 문제집을 백업하는 중…"
    status.error != null -> status.error
    status.message != null -> status.message
    status.lastBackupAtEpochMillis != null -> {
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(status.lastBackupAtEpochMillis))
        if (status.dirty) "저장 후 변경 있음 · 최근 백업 $time" else "최근 백업 $time"
    }
    else -> "아직 외부 백업이 없습니다."
}

private enum class PageFilter {
    ALL,
    IN_PROGRESS,
    SUBMITTED,
    REVIEW,
    TEACHER_MARKED,
    NOT_SUBMITTED,
    ;

    fun labelFor(perspective: LibraryPerspective): String = when (this) {
        ALL -> "전체"
        IN_PROGRESS -> "풀이 중"
        SUBMITTED -> if (perspective == LibraryPerspective.TEACHER) "채점 필요" else "제출됨"
        REVIEW -> "채점 중"
        TEACHER_MARKED -> "표시 있음"
        NOT_SUBMITTED -> "미제출"
    }
}

@Composable
private fun BookPageContent(
    modifier: Modifier,
    book: Book,
    summaries: List<PageProgressSummary>,
    perspective: LibraryPerspective,
    expanded: Boolean,
    onBackToBooks: () -> Unit,
    onOpenPage: (Book, Int, LibraryPerspective, Boolean, Int?) -> Unit,
    onImportAnswers: (Book) -> Unit,
    onStartStudentSync: (Book) -> Unit,
    onStartTeacherSync: (Book) -> Unit,
    onScanTeacherQr: (Book) -> Unit,
    onStopSync: () -> Unit,
) {
    var filter by remember(book.id, perspective) { mutableStateOf(PageFilter.ALL) }
    val progress = remember(book.id, summaries) {
        if (summaries.size == book.pageCount) summaries else {
            val byPage = summaries.associateBy(PageProgressSummary::pageNumber)
            List(book.pageCount) { page -> byPage.getValue(page) }
        }
    }
    val filters = if (perspective == LibraryPerspective.STUDENT) {
        listOf(PageFilter.ALL, PageFilter.IN_PROGRESS, PageFilter.SUBMITTED, PageFilter.REVIEW)
    } else {
        listOf(PageFilter.ALL, PageFilter.SUBMITTED, PageFilter.TEACHER_MARKED, PageFilter.NOT_SUBMITTED)
    }
    val filtered = remember(progress, filter, perspective) {
        progress.filter { summary ->
            val displayStatus = summary.statusFor(perspective)
            when (filter) {
                PageFilter.ALL -> true
                PageFilter.IN_PROGRESS -> displayStatus == PageProgressStatus.IN_PROGRESS
                PageFilter.SUBMITTED -> displayStatus == PageProgressStatus.SUBMITTED
                PageFilter.REVIEW -> displayStatus == PageProgressStatus.REVIEW_IN_PROGRESS
                PageFilter.TEACHER_MARKED -> summary.pageLevelTeacherMarkCount > 0 ||
                    displayStatus == PageProgressStatus.TEACHER_MARKED ||
                    displayStatus == PageProgressStatus.REVIEW_IN_PROGRESS
                PageFilter.NOT_SUBMITTED -> displayStatus == PageProgressStatus.NOT_STARTED ||
                    displayStatus == PageProgressStatus.IN_PROGRESS
            }
        }
    }

    Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            contentPadding = PaddingValues(vertical = 1.dp),
        ) {
            item { OutlinedButton(onClick = onBackToBooks) { Text("‹ 교재 목록") } }
            if (perspective == LibraryPerspective.STUDENT) {
                item { OutlinedButton(onClick = { onStartStudentSync(book) }) { Text("학생 기기") } }
            } else {
                item { OutlinedButton(onClick = { onImportAnswers(book) }) { Text("정답 JSON") } }
                item { OutlinedButton(onClick = { onStartTeacherSync(book) }) { Text("실시간 보기") } }
                item { OutlinedButton(onClick = { onScanTeacherQr(book) }) { Text("QR 연결") } }
            }
            item { TextButton(onClick = onStopSync) { Text("연결 종료") } }
        }

        PageProgressOverview(progress = progress, perspective = perspective, expanded = expanded)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            filters.forEach { item ->
                FilterPaperChip(
                    modifier = Modifier.weight(1f),
                    label = item.labelFor(perspective),
                    selected = filter == item,
                    onClick = { filter = item },
                )
            }
        }

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("해당하는 페이지가 없어요.", color = PaperMutedInk)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (expanded) 12.dp else 8.dp),
                horizontalArrangement = Arrangement.spacedBy(if (expanded) 12.dp else 8.dp),
                contentPadding = PaddingValues(vertical = 2.dp, horizontal = 1.dp),
            ) {
                gridItems(filtered, key = PageProgressSummary::pageNumber) { summary ->
                    ProgressPageItem(
                        summary = summary,
                        perspective = perspective,
                        expanded = expanded,
                        onOpen = {
                            onOpenPage(
                                book,
                                summary.pageNumber,
                                perspective,
                                expanded,
                                if (perspective == LibraryPerspective.TEACHER) {
                                    if (
                                        filter == PageFilter.TEACHER_MARKED &&
                                        summary.pageLevelTeacherMarkCount > 0
                                    ) {
                                        TEACHER_PAGE_REVIEW_ATTEMPT_NO
                                    } else {
                                        summary.latestSubmittedAttemptNo
                                            ?: summary.latestAttemptNo
                                            ?: TEACHER_PAGE_REVIEW_ATTEMPT_NO
                                    }
                                } else {
                                    null
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PageProgressOverview(
    progress: List<PageProgressSummary>,
    perspective: LibraryPerspective,
    expanded: Boolean,
) {
    val statuses = progress.map { it.statusFor(perspective) }
    val started = statuses.count { it != PageProgressStatus.NOT_STARTED }
    val submitted = statuses.count { it == PageProgressStatus.SUBMITTED }
    val reviewing = statuses.count { it == PageProgressStatus.REVIEW_IN_PROGRESS }
    val teacherMarked = statuses.count { it == PageProgressStatus.TEACHER_MARKED }
    val metrics = if (perspective == LibraryPerspective.STUDENT) {
        listOf(
            Triple("진행", started, ProgressWorking),
            Triple("제출", submitted + reviewing, ProgressSubmitted),
            Triple("채점 중", reviewing, ProgressTeal),
        )
    } else {
        listOf(
            Triple("채점 필요", submitted, ProgressSubmitted),
            Triple("표시 있음", reviewing + teacherMarked, ProgressReview),
            Triple(
                "미제출",
                statuses.count { it == PageProgressStatus.NOT_STARTED || it == PageProgressStatus.IN_PROGRESS },
                ProgressNeutral,
            ),
        )
    }
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(15.dp)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = PaperIvory.copy(alpha = 0.84f),
        border = paperEdge(),
        shadowElevation = 2.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(if (expanded) 72.dp else 64.dp)
                .paperSurfaceTexture(
                    intensity = 0.65f,
                    seed = if (perspective == LibraryPerspective.STUDENT) 419 else 421,
                )
                .padding(horizontal = 9.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            metrics.forEach { (label, count, accent) ->
                ReviewMetric(label, count, accent, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ReviewMetric(label: String, count: Int, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = 0.13f))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = PaperMutedInk,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text("$count", color = PaperInk, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FilterPaperChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            color = if (selected) PaperYellowSoft else PaperIvory.copy(alpha = 0.82f),
            border = paperEdge(),
            shadowElevation = if (selected) 2.dp else 0.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    label,
                    modifier = Modifier.padding(horizontal = 6.dp),
                    color = PaperInk,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PaperBackdrop(content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawWithCache {
                val oneDp = 1.dp.toPx()
                val widthDp = size.width / oneDp
                val heightDp = size.height / oneDp
                val areaDp = widthDp * heightDp
                val speckCount = (areaDp / 4_100f).roundToInt().coerceIn(110, 390)
                val fiberCount = (areaDp / 11_500f).roundToInt().coerceIn(42, 150)

                val paperWash = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFFF9F6EF),
                        LibraryBackground,
                        Color(0xFFEDE7DC),
                    ),
                    start = Offset(-size.width * 0.08f, -size.height * 0.06f),
                    end = Offset(size.width * 1.06f, size.height * 1.04f),
                )
                val mottles = List(8) { index ->
                    val center = Offset(
                        x = paperNoise(index, 17) * size.width,
                        y = paperNoise(index, 23) * size.height,
                    )
                    val radius = max(size.width, size.height) * (0.13f + paperNoise(index, 29) * 0.15f)
                    val light = paperNoise(index, 31) > 0.46f
                    val centerColor = if (light) {
                        PaperHighlight.copy(alpha = 0.030f + paperNoise(index, 37) * 0.022f)
                    } else {
                        PaperWarmFiber.copy(alpha = 0.018f + paperNoise(index, 41) * 0.015f)
                    }
                    PaperMottle(
                        center = center,
                        radius = radius,
                        brush = Brush.radialGradient(
                            colors = listOf(centerColor, Color.Transparent),
                            center = center,
                            radius = radius,
                        ),
                    )
                }
                val specks = List(speckCount) { index ->
                    val isLight = paperNoise(index, 47) > 0.62f
                    PaperSpeck(
                        center = Offset(
                            x = paperNoise(index, 53) * size.width,
                            y = paperNoise(index, 59) * size.height,
                        ),
                        radius = (0.22f + paperNoise(index, 61) * 0.72f) * oneDp,
                        color = if (isLight) {
                            PaperHighlight.copy(alpha = 0.045f + paperNoise(index, 67) * 0.045f)
                        } else {
                            PaperFiber.copy(alpha = 0.022f + paperNoise(index, 71) * 0.032f)
                        },
                    )
                }
                val fibers = List(fiberCount) { index ->
                    val start = Offset(
                        x = paperNoise(index, 73) * size.width,
                        y = paperNoise(index, 79) * size.height,
                    )
                    val length = (5f + paperNoise(index, 83) * 24f) * oneDp
                    val slope = (paperNoise(index, 89) - 0.5f) * 0.72f
                    val light = paperNoise(index, 97) > 0.7f
                    PaperFiberStroke(
                        start = start,
                        end = Offset(
                            x = (start.x + length).coerceAtMost(size.width),
                            y = (start.y + length * slope).coerceIn(0f, size.height),
                        ),
                        strokeWidth = (0.28f + paperNoise(index, 101) * 0.52f) * oneDp,
                        color = if (light) {
                            PaperHighlight.copy(alpha = 0.055f + paperNoise(index, 103) * 0.050f)
                        } else {
                            PaperWarmFiber.copy(alpha = 0.027f + paperNoise(index, 107) * 0.033f)
                        },
                    )
                }

                onDrawBehind {
                    drawRect(brush = paperWash)
                    mottles.forEach { mottle ->
                        drawCircle(
                            brush = mottle.brush,
                            radius = mottle.radius,
                            center = mottle.center,
                        )
                    }
                    fibers.forEach { fiber ->
                        drawLine(
                            color = fiber.color,
                            start = fiber.start,
                            end = fiber.end,
                            strokeWidth = fiber.strokeWidth,
                        )
                    }
                    specks.forEach { speck ->
                        drawCircle(
                            color = speck.color,
                            radius = speck.radius,
                            center = speck.center,
                        )
                    }
                }
            }
    ) { content() }
}

/**
 * A low-contrast, clipped-by-the-parent paper layer for tactile surfaces.
 * Points and fibers are built only when the measured size changes.
 */
private fun Modifier.paperSurfaceTexture(
    intensity: Float = 1f,
    seed: Int = 0,
    overlay: Boolean = false,
): Modifier = drawWithCache {
    val oneDp = 1.dp.toPx()
    val areaDp = (size.width / oneDp) * (size.height / oneDp)
    val speckCount = (areaDp / 1_650f).roundToInt().coerceIn(7, 44)
    val fiberCount = (areaDp / 5_800f).roundToInt().coerceIn(2, 14)
    val grainChannel = (seed and 0x3ff) * 181
    val surfaceWash = Brush.linearGradient(
        colors = listOf(
            PaperHighlight.copy(alpha = 0.060f * intensity),
            PaperHighlight.copy(alpha = 0f),
            PaperStroke.copy(alpha = 0.032f * intensity),
        ),
        start = Offset.Zero,
        end = Offset(size.width, size.height),
    )
    val specks = List(speckCount) { index ->
        val light = paperNoise(index, 113 + grainChannel) > 0.68f
        PaperSpeck(
            center = Offset(
                x = paperNoise(index, 127 + grainChannel) * size.width,
                y = paperNoise(index, 131 + grainChannel) * size.height,
            ),
            radius = (0.18f + paperNoise(index, 137 + grainChannel) * 0.48f) * oneDp,
            color = if (light) {
                PaperHighlight.copy(
                    alpha = (0.050f + paperNoise(index, 139 + grainChannel) * 0.035f) * intensity,
                )
            } else {
                PaperFiber.copy(
                    alpha = (0.018f + paperNoise(index, 149 + grainChannel) * 0.025f) * intensity,
                )
            },
        )
    }
    val fibers = List(fiberCount) { index ->
        val start = Offset(
            x = paperNoise(index, 151 + grainChannel) * size.width,
            y = paperNoise(index, 157 + grainChannel) * size.height,
        )
        val length = (4f + paperNoise(index, 163 + grainChannel) * 12f) * oneDp
        val slope = (paperNoise(index, 167 + grainChannel) - 0.5f) * 0.62f
        PaperFiberStroke(
            start = start,
            end = Offset(
                x = (start.x + length).coerceAtMost(size.width),
                y = (start.y + length * slope).coerceIn(0f, size.height),
            ),
            strokeWidth = (0.24f + paperNoise(index, 173 + grainChannel) * 0.34f) * oneDp,
            color = PaperWarmFiber.copy(
                alpha = (0.022f + paperNoise(index, 179 + grainChannel) * 0.024f) * intensity,
            ),
        )
    }

    onDrawWithContent {
        if (!overlay) {
            drawRect(brush = surfaceWash)
            fibers.forEach { fiber ->
                drawLine(fiber.color, fiber.start, fiber.end, fiber.strokeWidth)
            }
            specks.forEach { speck ->
                drawCircle(speck.color, speck.radius, speck.center)
            }
        }
        drawContent()
        if (overlay) {
            drawRect(brush = surfaceWash)
            fibers.forEach { fiber ->
                drawLine(fiber.color, fiber.start, fiber.end, fiber.strokeWidth)
            }
            specks.forEach { speck ->
                drawCircle(speck.color, speck.radius, speck.center)
            }
        }
    }
}

@Composable
private fun StudentPaperChip(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ThinSelectorChip(
        modifier = modifier,
        label = name,
        selected = selected,
        onClick = onClick,
    )
}

@Composable
private fun PerspectivePaperChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ThinSelectorChip(
        modifier = modifier,
        label = label,
        selected = selected,
        onClick = onClick,
    )
}

@Composable
private fun ThinSelectorChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp),
            shape = shape,
            color = if (selected) PaperYellowSoft else PaperIvory,
            contentColor = PaperInk,
            border = paperEdge(),
            shadowElevation = if (selected) 2.dp else 1.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .paperSurfaceTexture(
                        intensity = if (selected) 0.72f else 0.86f,
                        seed = label.hashCode(),
                    )
                    .padding(horizontal = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    label,
                    color = PaperInk,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CompactBookItem(
    book: Book,
    summaries: List<PageProgressSummary>,
    expanded: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(13.dp)
    val progressed = summaries.count { it.status != PageProgressStatus.NOT_STARTED }
    val fraction = if (book.pageCount == 0) 0f else progressed.toFloat() / book.pageCount
    val spineColor = when (book.id.hashCode().and(3)) {
        0 -> PaperYellow
        1 -> ProgressTeal
        2 -> ProgressSubmitted
        else -> ProgressReview
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
        shape = shape,
        color = PaperIvory,
        contentColor = PaperInk,
        border = paperEdge(),
        tonalElevation = 0.dp,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (expanded) 104.dp else 72.dp)
                .paperSurfaceTexture(seed = book.id.hashCode())
                .padding(start = 0.dp, end = 5.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(7.dp)
                    .fillMaxHeight()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(topEnd = 7.dp, bottomEnd = 7.dp))
                    .background(spineColor),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = book.title,
                    color = PaperInk,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (progressed == 0) {
                        "${book.pageCount}쪽 · 아직 시작 전"
                    } else {
                        "${book.pageCount}쪽 · ${progressed}쪽 진행"
                    },
                    color = PaperMutedInk,
                    style = MaterialTheme.typography.labelSmall,
                )
                PaperProgressBar(fraction)
            }
            TextButton(onClick = onRename) {
                Text("이름", color = PaperMutedInk, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun PaperProgressBar(fraction: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .background(PaperStroke.copy(alpha = 0.55f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(PaperYellow),
        )
    }
}

@Composable
private fun ProgressPageItem(
    summary: PageProgressSummary,
    perspective: LibraryPerspective,
    expanded: Boolean,
    onOpen: () -> Unit,
) {
    val displayStatus = summary.statusFor(perspective)
    val gradeSnapshot = summary.gradeSnapshotFor(perspective)
    val accent = when {
        gradeSnapshot == null -> displayStatus.accentColor
        gradeSnapshot.pageLevel -> GradePageLevel
        gradeSnapshot.wrongCount > 0 -> GradeWrong
        gradeSnapshot.unansweredCount > 0 -> GradeUnanswered
        gradeSnapshot.correctCount > 0 -> GradeCorrect
        else -> displayStatus.accentColor
    }
    val resultLabel = gradeSnapshot?.conciseLabel()?.let { label ->
        if (
            perspective == LibraryPerspective.TEACHER &&
            !gradeSnapshot.pageLevel &&
            summary.pageLevelTeacherMarkCount > 0
        ) {
            "$label · 쪽표시 ${summary.pageLevelTeacherMarkCount}"
        } else {
            label
        }
    } ?: displayStatus.labelFor(perspective)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 2.dp,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(13.dp),
                clip = false,
            ),
        onClick = onOpen,
        enabled = true,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(13.dp),
        color = if (
            displayStatus == PageProgressStatus.IN_PROGRESS &&
            perspective == LibraryPerspective.STUDENT
        ) {
            PaperYellowSoft.copy(alpha = 0.74f)
        } else {
            PaperIvory.copy(alpha = 0.94f)
        },
        contentColor = PaperInk,
        border = paperEdge(),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = if (expanded) 112.dp else 90.dp)
                .paperSurfaceTexture(intensity = 0.76f, seed = summary.pageNumber + 1),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(
                        horizontal = if (expanded) 11.dp else 7.dp,
                        vertical = if (expanded) 11.dp else 8.dp,
                    ),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${summary.pageNumber + 1}쪽",
                    style = if (expanded) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        resultLabel,
                        color = if (gradeSnapshot?.wrongCount?.let { it > 0 } == true) GradeWrong else PaperInk,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (gradeSnapshot != null) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    gradeSnapshot?.let { snapshot ->
                        PageGradeBundle(
                            snapshot = snapshot,
                            expanded = expanded,
                        )
                    }
                }
            }
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(accent),
            )
        }
    }
}

@Composable
private fun PageGradeBundle(
    snapshot: PageGradeSnapshot,
    expanded: Boolean,
) {
    if (snapshot.cells.isEmpty()) return
    // The approved eight-problem bundle is 4 x 2. Slightly tall cells keep its outer silhouette
    // close to a square; the same aspect-aware rule grows naturally for any problem count.
    val cellAspect = 1.75f
    val grid = remember(snapshot.cells.size) { resultBundleGrid(snapshot.cells.size) }
    val columns = grid.columns
    val rows = grid.rows
    val gap = if (expanded) 2.dp else 1.5.dp
    val preferredCellWidth = if (expanded) 9.dp else 6.5.dp
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val availableForCells = (maxWidth - gap * (columns - 1)).coerceAtLeast(1.dp)
        val cellWidth = minOf(preferredCellWidth, availableForCells / columns).coerceAtLeast(2.dp)
        val cellHeight = cellWidth * cellAspect
        val bundleWidth = cellWidth * columns + gap * (columns - 1)
        val bundleHeight = cellHeight * rows + gap * (rows - 1)
        Canvas(Modifier.width(bundleWidth).height(bundleHeight)) {
            val cellWidthPx = cellWidth.toPx()
            val cellHeightPx = cellHeight.toPx()
            val gapPx = gap.toPx()
            val radius = (cellWidthPx * 0.42f).coerceAtLeast(1f)
            snapshot.cells.forEachIndexed { index, grade ->
                val gridCell = grid.cells[index]
                val topLeft = Offset(
                    gridCell.column * (cellWidthPx + gapPx),
                    gridCell.row * (cellHeightPx + gapPx),
                )
                val color = grade.color.gradeColor()
                drawRoundRect(
                    color = color.copy(alpha = if (grade.color == MarkColor.GRAY) 0.58f else 0.94f),
                    topLeft = topLeft,
                    size = androidx.compose.ui.geometry.Size(cellWidthPx, cellHeightPx),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                )
                drawRoundRect(
                    color = PaperHighlight.copy(alpha = 0.30f),
                    topLeft = Offset(topLeft.x + cellWidthPx * 0.14f, topLeft.y + cellHeightPx * 0.08f),
                    size = androidx.compose.ui.geometry.Size(cellWidthPx * 0.52f, cellHeightPx * 0.08f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                )
                grade.previousColors.takeLast(2).forEachIndexed { historyIndex, previous ->
                    val historySize = cellWidthPx * 0.24f
                    drawRoundRect(
                        color = previous.gradeColor().copy(alpha = 0.34f),
                        topLeft = Offset(
                            topLeft.x + cellWidthPx - historySize - cellWidthPx * 0.10f -
                                historyIndex * (historySize + cellWidthPx * 0.06f),
                            topLeft.y + cellHeightPx - historySize - cellWidthPx * 0.10f,
                        ),
                        size = androidx.compose.ui.geometry.Size(historySize, historySize),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(historySize * 0.34f),
                    )
                }
                if (grade.pageLevel) {
                    drawRoundRect(
                        color = GradePageLevel.copy(alpha = 0.92f),
                        topLeft = topLeft,
                        size = androidx.compose.ui.geometry.Size(cellWidthPx, cellHeightPx),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                        style = Stroke(width = (cellWidthPx * 0.12f).coerceAtLeast(1f)),
                    )
                }
            }
        }
    }
}

private fun PageGradeSnapshot.conciseLabel(): String = when {
    pageLevel -> "쪽 표시 ${cells.size}"
    wrongCount > 0 -> "오답 $wrongCount"
    unansweredCount == 0 && correctCount > 0 -> "전부 정답"
    correctCount + wrongCount > 0 -> "미채점 $unansweredCount"
    else -> "아직 미채점"
}

private fun MarkColor.gradeColor(): Color = when (this) {
    MarkColor.BLUE -> GradeCorrect
    MarkColor.RED -> GradeWrong
    MarkColor.GRAY -> GradeUnanswered
}

private val LibraryPerspective.label: String
    get() = when (this) {
        LibraryPerspective.STUDENT -> "학생 화면"
        LibraryPerspective.TEACHER -> "선생님 검토"
    }

private val PageProgressStatus.accentColor: Color
    get() = when (this) {
        PageProgressStatus.NOT_STARTED -> ProgressNeutral
        PageProgressStatus.IN_PROGRESS -> ProgressWorking
        PageProgressStatus.SUBMITTED -> ProgressSubmitted
        PageProgressStatus.REVIEW_IN_PROGRESS -> ProgressReview
        PageProgressStatus.TEACHER_MARKED -> ProgressReview
    }

private fun PageProgressStatus.labelFor(perspective: LibraryPerspective): String = when (this) {
    PageProgressStatus.NOT_STARTED -> if (perspective == LibraryPerspective.TEACHER) "미제출" else "미시작"
    PageProgressStatus.IN_PROGRESS -> "풀이 중"
    PageProgressStatus.SUBMITTED -> if (perspective == LibraryPerspective.TEACHER) "채점 필요" else "제출됨"
    PageProgressStatus.REVIEW_IN_PROGRESS -> if (perspective == LibraryPerspective.TEACHER) "표시 있음" else "채점 중"
    PageProgressStatus.TEACHER_MARKED -> "표시 있음"
}

@Composable
private fun EmptyLibraryNotice(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.widthIn(max = 420.dp),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = PaperIvory.copy(alpha = 0.78f),
            border = paperEdge(),
            tonalElevation = 0.dp,
            shadowElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier
                    .paperSurfaceTexture(intensity = 0.82f, seed = 701)
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Canvas(Modifier.size(26.dp)) {
                    drawCircle(PaperYellowSoft)
                    drawCircle(PaperYellow, style = Stroke(width = 1.dp.toPx()))
                }
                Text("아직 교재가 없어요", color = PaperInk, fontWeight = FontWeight.SemiBold)
                Text(
                    "PDF 교재를 가져오면 제목별로 이곳에 표시됩니다.",
                    color = PaperMutedInk,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun paperEdge() = BorderStroke(
    width = 1.dp,
    brush = Brush.verticalGradient(
        colors = listOf(PaperHighlight.copy(alpha = 0.92f), PaperStroke.copy(alpha = 0.86f)),
    ),
)

private object LibraryPreviewFixtures {
    val students = listOf(
        Student(id = "student-1", displayName = "학생 1", createdAtEpochMillis = 1L),
        Student(id = "student-2", displayName = "학생 2", createdAtEpochMillis = 2L),
    )
    val books = listOf(
        Book(
            id = "book-math",
            studentId = students[0].id,
            title = "초등 수학 5-2 · 분수와 소수",
            pageCount = 32,
            pdfRelativePath = "preview/math.pdf",
            createdAtEpochMillis = 3L,
        ),
        Book(
            id = "book-reading",
            studentId = students[0].id,
            title = "영어 독해 · 문장 구조와 어휘 연습",
            pageCount = 24,
            pdfRelativePath = "preview/reading.pdf",
            createdAtEpochMillis = 4L,
        ),
    )
    val state = LibraryState(students = students, selectedStudentId = students[0].id, books = books)

    fun progress(book: Book): List<PageProgressSummary> = List(book.pageCount) { page ->
        val attemptStatuses = when {
            page % 11 == 5 -> listOf(AttemptProgressStatus.SUBMITTED, AttemptProgressStatus.IN_PROGRESS)
            page % 7 == 3 -> listOf(AttemptProgressStatus.REVIEW_IN_PROGRESS)
            page % 5 == 2 -> listOf(AttemptProgressStatus.SUBMITTED)
            page < 9 -> listOf(AttemptProgressStatus.IN_PROGRESS)
            else -> emptyList()
        }
        val attempts = attemptStatuses.mapIndexed { index, status ->
            AttemptProgressSummary(
                attemptNo = index + 1,
                status = status,
                markCount = if (status == AttemptProgressStatus.REVIEW_IN_PROGRESS) 2 else 0,
                startedAtEpochMillis = 1_000L + page * 100L + index,
                submittedAtEpochMillis = if (status == AttemptProgressStatus.IN_PROGRESS) null else 2_000L + page,
                latestMarkAtEpochMillis = if (status == AttemptProgressStatus.REVIEW_IN_PROGRESS) 3_000L + page else null,
            )
        }
        val latest = attempts.lastOrNull()
        val pageLevelTeacherMarkCount = if (attempts.isEmpty() && page % 9 == 8) 2 else 0
        val previewProblemCount = when {
            attempts.isNotEmpty() -> 5 + page % 7
            pageLevelTeacherMarkCount > 0 -> pageLevelTeacherMarkCount
            else -> 0
        }
        val problemGrades = List(previewProblemCount) { problem ->
            val attemptNo = latest?.attemptNo ?: TEACHER_PAGE_REVIEW_ATTEMPT_NO
            ProblemGradeSummary(
                groupId = "preview-$page-$problem",
                pageLevel = attemptNo == TEACHER_PAGE_REVIEW_ATTEMPT_NO,
                history = listOf(
                    AttemptGradeSummary(
                        attemptNo = attemptNo,
                        color = when {
                            problem % 5 == 2 -> MarkColor.RED
                            problem % 7 == 4 -> MarkColor.GRAY
                            else -> MarkColor.BLUE
                        },
                        gradedAtEpochMillis = 3_000L + page * 100L + problem,
                    ),
                ),
            )
        }
        PageProgressSummary(
            pageNumber = page,
            status = when (latest?.status) {
                null -> PageProgressStatus.NOT_STARTED
                AttemptProgressStatus.IN_PROGRESS -> PageProgressStatus.IN_PROGRESS
                AttemptProgressStatus.SUBMITTED -> PageProgressStatus.SUBMITTED
                AttemptProgressStatus.REVIEW_IN_PROGRESS -> PageProgressStatus.REVIEW_IN_PROGRESS
            },
            attempts = attempts,
            latestAttemptNo = latest?.attemptNo,
            attemptCount = attempts.size,
            submittedAttemptCount = attempts.count { it.status != AttemptProgressStatus.IN_PROGRESS },
            markCount = attempts.sumOf(AttemptProgressSummary::markCount),
            pageLevelTeacherMarkCount = pageLevelTeacherMarkCount,
            latestActivityAtEpochMillis = latest?.latestMarkAtEpochMillis ?: latest?.submittedAtEpochMillis
                ?: latest?.startedAtEpochMillis ?: if (pageLevelTeacherMarkCount > 0) 4_000L + page else null,
            problemGrades = problemGrades,
        )
    }
}

@Composable
private fun LibraryDevicePreview(
    selectedBook: Book?,
    perspective: LibraryPerspective,
) {
    MaterialTheme {
        LibraryScreen(
            state = LibraryPreviewFixtures.state,
            selectedBook = selectedBook,
            perspective = perspective,
            progressRevision = 0,
            importing = false,
            backupStatus = MasterNoteBackupStatus(
                dirty = false,
                lastBackupAtEpochMillis = 1_786_000_000_000L,
                lastBackupName = "MasterNote_preview.mnbak.zip",
            ),
            inspectingBackup = false,
            progressForBook = { bookId ->
                LibraryPreviewFixtures.books.firstOrNull { it.id == bookId }
                    ?.let(LibraryPreviewFixtures::progress)
                    .orEmpty()
            },
            onSelectStudent = {},
            onSelectPerspective = {},
            onImport = {},
            onBackupNow = {},
            onSelectBackup = {},
            onSelectBook = {},
            onBackToBooks = {},
            onOpenPage = { _, _, _, _, _ -> },
            onRename = {},
            onImportAnswers = {},
            onStartStudentSync = {},
            onStartTeacherSync = {},
            onScanTeacherQr = {},
            onStopSync = {},
        )
    }
}

@Preview(name = "Galaxy S23 Ultra · 책장", widthDp = 412, heightDp = 884, showBackground = true)
@Composable
private fun GalaxyS23UltraShelfPreview() = LibraryDevicePreview(
    selectedBook = null,
    perspective = LibraryPerspective.STUDENT,
)

@Preview(name = "Galaxy S23 Ultra · 학생 페이지", widthDp = 412, heightDp = 884, showBackground = true)
@Composable
private fun GalaxyS23UltraStudentPagesPreview() = LibraryDevicePreview(
    selectedBook = LibraryPreviewFixtures.books.first(),
    perspective = LibraryPerspective.STUDENT,
)

@Preview(name = "Galaxy Tab S11 · 선생님 검토", widthDp = 800, heightDp = 1280, showBackground = true)
@Composable
private fun GalaxyTabS11TeacherPagesPreview() = LibraryDevicePreview(
    selectedBook = LibraryPreviewFixtures.books.first(),
    perspective = LibraryPerspective.TEACHER,
)

@Composable
private fun RenameBookDialog(book: Book, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var title by remember(book.id) { mutableStateOf(book.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("단원명 바꾸기") },
        text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { if (title.isNotBlank()) onSave(title) }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun RestoreBackupDialog(
    candidate: RestoreBackupCandidate,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val inspection = candidate.inspection
    val createdAt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(inspection.createdAtEpochMillis))
    val size = formatBackupSize(inspection.totalBytes)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("이 백업으로 복원할까요?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("$createdAt · $size · ${inspection.fileCount}개 파일")
                Text("MasterNote ${inspection.sourceVersionName.ifBlank { "버전 정보 없음" }}")
                if (!inspection.identityMatchesThisDevice) {
                    Text(
                        "다른 기기에서 만든 백업입니다. 책과 필기는 복원하지만 이 기기의 연결 ID는 유지합니다.",
                        color = PaperMutedInk,
                    )
                }
                Text(
                    "현재 문제집·필기·회차·채점 데이터가 선택한 백업으로 교체됩니다. " +
                        "먼저 현재 상태를 Download/MasterNote Backups에 안전 백업한 뒤 복원합니다.",
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
        confirmButton = { Button(onClick = onConfirm) { Text("안전 백업 후 복원") } },
    )
}

private fun formatBackupSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
