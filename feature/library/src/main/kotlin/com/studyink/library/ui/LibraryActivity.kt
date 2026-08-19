package com.studyink.library.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.studyink.core.model.Book
import com.studyink.library.data.LibraryRepository
import com.studyink.library.data.LibraryState
import com.studyink.reader.ReaderActivity
import com.studyink.reader.ReaderDebugSessionStore
import com.studyink.reader.ReaderRole
import com.studyink.sync.lan.LanSyncService
import com.studyink.sync.lan.LanSyncBus
import com.studyink.sync.lan.PairingPayload
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LibraryActivity : ComponentActivity(), LanSyncBus.Listener {
    private val repository by lazy { LibraryRepository.get(this) }
    private var state by mutableStateOf<LibraryState?>(null)
    private var selectedBook by mutableStateOf<Book?>(null)
    private var importing by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)
    private var renameTarget by mutableStateOf<Book?>(null)
    private var answerTargetBookId: String? = null
    private var pendingSyncStart: (() -> Unit)? = null
    private var pairingUri by mutableStateOf<String?>(null)
    private var qrTargetBookId: String? = null

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
                    startActivity(ReaderActivity.intent(this, targetBookId, 0, ReaderRole.TEACHER_PHONE))
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = repository.state
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    LibraryScreen(
                        state = state ?: return@Surface,
                        selectedBook = selectedBook,
                        importing = importing,
                        onSelectStudent = { id ->
                            repository.selectStudent(id)
                            state = repository.state
                            selectedBook = null
                        },
                        onImport = { importPdf.launch(arrayOf("application/pdf")) },
                        onSelectBook = { selectedBook = it },
                        onBackToBooks = { selectedBook = null },
                        onOpenPage = { book, page -> startActivity(ReaderActivity.intent(this, book.id, page)) },
                        onRename = { renameTarget = it },
                        onImportAnswers = { book ->
                            answerTargetBookId = book.id
                            importAnswers.launch(arrayOf("application/json", "text/json", "text/plain"))
                        },
                        onStartStudentSync = { book ->
                            pairingUri = null
                            startSyncSession { LanSyncService.startStudent(this, book.id) }
                        },
                        onStartTeacherSync = { book ->
                            startSyncSession {
                                LanSyncService.startTeacher(this, book.id)
                                startActivity(ReaderActivity.intent(this, book.id, 0, ReaderRole.TEACHER_PHONE))
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
                        PairingQrDialog(uri = uri, onDismiss = { pairingUri = null })
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
                }
            }
        }
        if (savedInstanceState == null) {
            ReaderDebugSessionStore.load(this)?.takeIf { session ->
                repository.state.books.any { it.id == session.bookId }
            }?.let { session ->
                startActivity(ReaderActivity.intent(this, session.bookId, session.pageNumber, session.role))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        state = repository.state
    }

    override fun onStart() {
        super.onStart()
        LanSyncBus.addListener(this)
    }

    override fun onStop() {
        LanSyncBus.removeListener(this)
        super.onStop()
    }

    override fun onPairingReady(bookId: String, pairingUri: String) {
        runOnUiThread {
            if (selectedBook?.id == bookId) this.pairingUri = pairingUri
        }
    }

    override fun onSessionIssue(message: String) {
        runOnUiThread { errorMessage = message }
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

@Composable
private fun LibraryScreen(
    state: LibraryState,
    selectedBook: Book?,
    importing: Boolean,
    onSelectStudent: (String) -> Unit,
    onImport: () -> Unit,
    onSelectBook: (Book) -> Unit,
    onBackToBooks: () -> Unit,
    onOpenPage: (Book, Int) -> Unit,
    onRename: (Book) -> Unit,
    onImportAnswers: (Book) -> Unit,
    onStartStudentSync: (Book) -> Unit,
    onStartTeacherSync: (Book) -> Unit,
    onScanTeacherQr: (Book) -> Unit,
    onStopSync: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("내 책장", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.students, key = { it.id }) { student ->
                if (student.id == state.selectedStudentId) {
                    Button(onClick = { onSelectStudent(student.id) }) { Text(student.displayName) }
                } else {
                    OutlinedButton(onClick = { onSelectStudent(student.id) }) { Text(student.displayName) }
                }
            }
        }
        if (selectedBook == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onImport, enabled = !importing) { Text(if (importing) "가져오는 중" else "PDF 교재 가져오기") }
            }
            val books = state.books.filter { it.studentId == state.selectedStudentId }
            if (books.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("PDF 교재를 가져오면 이곳에 표시됩니다.")
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(books, key = { it.id }) { book ->
                        Card(Modifier.fillMaxWidth().clickable { onSelectBook(book) }) {
                            Row(
                                Modifier.fillMaxWidth().padding(18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column {
                                    Text(book.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text("${book.pageCount}쪽", style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { onRename(book) }) { Text("이름 바꾸기") }
                            }
                        }
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBackToBooks) { Text("교재 목록") }
                Text(selectedBook.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { onImportAnswers(selectedBook) }) { Text("정답 JSON") }
                OutlinedButton(onClick = { onStartStudentSync(selectedBook) }) { Text("학생 기기") }
                OutlinedButton(onClick = { onStartTeacherSync(selectedBook) }) { Text("선생 폰") }
                OutlinedButton(onClick = { onScanTeacherQr(selectedBook) }) { Text("QR 연결") }
                TextButton(onClick = onStopSync) { Text("연결 종료") }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(selectedBook.pageCount) { page ->
                    Card(Modifier.fillMaxWidth().clickable { onOpenPage(selectedBook, page) }) {
                        Text("${page + 1}쪽", Modifier.padding(18.dp), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

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
