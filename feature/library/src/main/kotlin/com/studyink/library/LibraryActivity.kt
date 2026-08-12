package com.studyink.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.studyink.annotation.storage.BookImportRepository
import com.studyink.annotation.storage.BookImportScheduler
import com.studyink.annotation.storage.ImportSession
import com.studyink.annotation.storage.ImportState
import com.studyink.annotation.storage.LibraryBook
import com.studyink.annotation.storage.LibraryBookStatus
import com.studyink.annotation.storage.LibraryFolder
import com.studyink.annotation.storage.LibraryRepository
import com.studyink.annotation.storage.ManagedAssetRepository
import com.studyink.annotation.storage.RoomLearningRepository
import com.studyink.progress.ProgressActivity
import com.studyink.reader.SampleLearningContent
import java.io.FileInputStream
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class LibraryActivity : ComponentActivity() {
    private lateinit var library: LibraryRepository
    private lateinit var assets: ManagedAssetRepository
    private lateinit var imports: BookImportRepository
    private var folderId by mutableStateOf(LibraryRepository.ROOT_ID)
    private var folders by mutableStateOf<List<LibraryFolder>>(emptyList())
    private var books by mutableStateOf<List<LibraryBook>>(emptyList())
    private var folderName by mutableStateOf("")
    private var importStatus by mutableStateOf<ImportSession?>(null)
    private var renameTarget by mutableStateOf<LibraryFolder?>(null)
    private var renameValue by mutableStateOf("")
    private var folderJob: Job? = null
    private var booksJob: Job? = null
    private val teacher by lazy {
        intent.getBooleanExtra(EXTRA_TEACHER, false) && LibraryTeacherAccess.isValid()
    }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = LibraryRepository.open(this)
        assets = ManagedAssetRepository.open(this)
        imports = BookImportRepository.open(this)
        lifecycleScope.launch {
            library.ensureRoot()
            ensureSampleBook()
            observeFolder()
        }
        setContent { MaterialTheme { LibraryScreen() } }
    }

    private fun observeFolder() {
        folderJob?.cancel()
        booksJob?.cancel()
        folderJob = lifecycleScope.launch {
            library.observeFolders(folderId).collectLatest { folders = it }
        }
        booksJob = lifecycleScope.launch {
            library.observeBooks(folderId).collectLatest { books = it }
        }
    }

    private suspend fun ensureSampleBook() {
        if (library.hasBook("sample-book")) return
        val seed = SampleLearningContent.createSeed(this)
        val learning = RoomLearningRepository.open(this)
        try {
            learning.ensureContent(seed)
        } finally {
            learning.close()
        }
        val file = SampleLearningContent.ensurePdf(this)
        val asset = FileInputStream(file).use { assets.importStream(it, file.name, "application/pdf") }
        library.registerRevisionSource(seed.bookRevision.revisionId.value, asset.assetId)
        library.registerBook(
            seed.bookRevision.bookId,
            seed.bookRevision.title,
            seed.bookRevision.revisionId.value,
            LibraryRepository.ROOT_ID,
        )
    }

    @Composable
    private fun LibraryScreen() {
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    if (teacher) "선생 책장" else "내 책장",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                if (!teacher) {
                    Button(onClick = {
                        startActivity(
                            Intent().setClassName(packageName, "com.studyink.teacher.TeacherModeGateActivity")
                        )
                    }) { Text("선생 모드") }
                }
            }
            if (folderId != LibraryRepository.ROOT_ID) {
                Button(onClick = { openFolder(LibraryRepository.ROOT_ID) }) { Text("모든 책") }
            }
            if (teacher) TeacherActions()
            ImportStatus()
            LazyColumn {
                items(folders, key = { it.id }) { folder -> FolderRow(folder) }
                items(books, key = { it.id }) { book -> BookRow(book) }
            }
        }
        RenameFolderDialog()
    }

    @Composable
    private fun TeacherActions() {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                label = { Text("새 폴더") },
                modifier = Modifier.weight(1f),
            )
            Button(onClick = {
                lifecycleScope.launch {
                    runCatching { library.createFolder(folderId, folderName) }
                    folderName = ""
                }
            }) { Text("추가") }
            Button(onClick = {
                picker.launch(
                    arrayOf(
                        "application/pdf",
                        "application/zip",
                        "application/octet-stream",
                        "application/vnd.maternote.book+zip",
                    )
                )
            }) { Text("가져오기") }
        }
    }

    @Composable
    private fun ImportStatus() {
        val session = importStatus ?: return
        Text("가져오기: ${session.state} ${session.errorCode.orEmpty()}")
        if (session.state == ImportState.WAITING_USER_CONFIRMATION) {
            Button(onClick = {
                lifecycleScope.launch {
                    imports.confirm(session.id)
                    BookImportScheduler.enqueue(this@LibraryActivity, session.id)
                }
            }) { Text("확인 후 가져오기") }
        }
    }

    @Composable
    private fun FolderRow(folder: LibraryFolder) {
        Row(
            Modifier.fillMaxWidth().clickable { openFolder(folder.id) }.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("📁 ${folder.name}", Modifier.weight(1f))
            if (teacher) {
                TextButton(onClick = {
                    renameTarget = folder
                    renameValue = folder.name
                }) { Text("이름") }
                if (folderId != LibraryRepository.ROOT_ID) {
                    TextButton(onClick = {
                        lifecycleScope.launch { library.moveFolder(folder.id, LibraryRepository.ROOT_ID) }
                    }) { Text("루트로 이동") }
                }
                TextButton(onClick = {
                    lifecycleScope.launch { library.trashFolder(folder.id, moveContentsToParent = true) }
                }) { Text("삭제") }
            }
        }
    }

    @Composable
    private fun BookRow(book: LibraryBook) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(book.title, Modifier.weight(1f).clickable { openBook(book) })
            if (teacher && folderId != LibraryRepository.ROOT_ID) {
                OutlinedButton(onClick = {
                    lifecycleScope.launch { library.moveBook(book.id, LibraryRepository.ROOT_ID) }
                }) { Text("루트로 이동") }
            }
            if (teacher) {
                OutlinedButton(onClick = {
                    lifecycleScope.launch { library.setBookStatus(book.id, LibraryBookStatus.ARCHIVED) }
                }) { Text("보관") }
                OutlinedButton(onClick = {
                    lifecycleScope.launch { library.setBookStatus(book.id, LibraryBookStatus.TRASHED) }
                }) { Text("휴지통") }
            }
        }
    }

    @Composable
    private fun RenameFolderDialog() {
        val target = renameTarget ?: return
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("폴더 이름 변경") },
            text = {
                OutlinedTextField(renameValue, { renameValue = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    lifecycleScope.launch { library.renameFolder(target.id, renameValue) }
                    renameTarget = null
                }) { Text("저장") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("취소") }
            },
        )
    }

    private fun openFolder(id: String) {
        folderId = id
        observeFolder()
    }

    private fun startImport(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        lifecycleScope.launch {
            val id = imports.create(uri, folderId)
            launch { imports.observe(id).collectLatest { importStatus = it } }
            BookImportScheduler.enqueue(this@LibraryActivity, id)
        }
    }

    private fun openBook(book: LibraryBook) {
        lifecycleScope.launch {
            val id = library.currentDocumentAssetId(book.id)
            val uri = Uri.fromFile(assets.open(id).file)
            startActivity(
                ProgressActivity.intent(
                    this@LibraryActivity,
                    book.currentRevisionId,
                    book.title,
                    uri.toString(),
                )
            )
        }
    }

    override fun onDestroy() {
        assets.close()
        imports.close()
        library.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TEACHER = "teacher"

        fun teacherIntent(context: Context) =
            Intent(context, LibraryActivity::class.java).putExtra(EXTRA_TEACHER, true)
    }
}

object LibraryTeacherAccess {
    @Volatile var isValid: () -> Boolean = { false }
}
