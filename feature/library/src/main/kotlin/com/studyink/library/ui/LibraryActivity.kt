package com.studyink.library.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

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
                Surface(Modifier.fillMaxSize(), color = LibraryBackground) {
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
    PaperBackdrop {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 720.dp)
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "내 책장",
                    color = PaperInk,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "읽을 교재를 골라 바로 이어서 공부해요.",
                    color = PaperMutedInk,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 2.dp),
            ) {
                items(state.students, key = { it.id }) { student ->
                    StudentPaperChip(
                        name = student.displayName,
                        selected = student.id == state.selectedStudentId,
                        onClick = { onSelectStudent(student.id) },
                    )
                }
            }
            if (selectedBook == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "교재",
                        color = PaperInk,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val importButtonShape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
                    Button(
                        modifier = Modifier
                            .clip(importButtonShape)
                            .paperSurfaceTexture(intensity = 0.42f, seed = 607, overlay = true),
                        onClick = onImport,
                        enabled = !importing,
                        shape = importButtonShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = PaperYellow,
                            contentColor = PaperInk,
                        ),
                    ) {
                        Text(if (importing) "가져오는 중" else "+ PDF 가져오기")
                    }
                }
                val books = state.books.filter { it.studentId == state.selectedStudentId }
                if (books.isEmpty()) {
                    EmptyLibraryNotice(Modifier.weight(1f))
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 3.dp),
                    ) {
                        items(books, key = { it.id }) { book ->
                            CompactBookItem(
                                book = book,
                                onOpen = { onSelectBook(book) },
                                onRename = { onRename(book) },
                            )
                        }
                    }
                }
            } else {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    item { OutlinedButton(onClick = onBackToBooks) { Text("교재 목록") } }
                    item { OutlinedButton(onClick = { onImportAnswers(selectedBook) }) { Text("정답 JSON") } }
                    item { OutlinedButton(onClick = { onStartStudentSync(selectedBook) }) { Text("학생 기기") } }
                    item { OutlinedButton(onClick = { onStartTeacherSync(selectedBook) }) { Text("선생 폰") } }
                    item { OutlinedButton(onClick = { onScanTeacherQr(selectedBook) }) { Text("QR 연결") } }
                    item { TextButton(onClick = onStopSync) { Text("연결 종료") } }
                }
                Text(
                    text = selectedBook.title,
                    color = PaperInk,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                    contentPadding = PaddingValues(vertical = 3.dp),
                ) {
                    items(selectedBook.pageCount) { page ->
                        CompactPageItem(
                            page = page,
                            onOpen = { onOpenPage(selectedBook, page) },
                        )
                    }
                }
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
private fun StudentPaperChip(name: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        color = if (selected) PaperYellowSoft else PaperIvory,
        contentColor = PaperInk,
        border = paperEdge(),
        shadowElevation = if (selected) 3.dp else 1.dp,
    ) {
        Text(
            text = name,
            modifier = Modifier
                .paperSurfaceTexture(
                    intensity = if (selected) 0.72f else 0.88f,
                    seed = name.hashCode(),
                )
                .padding(horizontal = 13.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
    }
}

@Composable
private fun CompactBookItem(book: Book, onOpen: () -> Unit, onRename: () -> Unit) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(13.dp)
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
                .heightIn(min = 58.dp)
                .paperSurfaceTexture(seed = book.id.hashCode())
                .padding(start = 12.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 5.dp, height = 30.dp),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRoundRect(
                        color = PaperYellow,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width / 2f),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
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
                    text = "${book.pageCount}쪽",
                    color = PaperMutedInk,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(onClick = onRename) {
                Text("이름 변경", color = PaperMutedInk, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun CompactPageItem(page: Int, onOpen: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpen,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = PaperIvory.copy(alpha = 0.96f),
        contentColor = PaperInk,
        border = paperEdge(),
        tonalElevation = 0.dp,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .paperSurfaceTexture(intensity = 0.86f, seed = page + 1)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("${page + 1}쪽", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("열기", color = PaperMutedInk, style = MaterialTheme.typography.labelMedium)
        }
    }
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
