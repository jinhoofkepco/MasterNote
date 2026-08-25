package com.studyink.library.data

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.studyink.core.model.Attempt
import com.studyink.core.model.AnswerItem
import com.studyink.core.model.AnswerSource
import com.studyink.core.model.Book
import com.studyink.core.model.Mark
import com.studyink.core.model.MarkColor
import com.studyink.core.model.MarkGroup
import com.studyink.core.model.MasterNoteDataCommitBus
import com.studyink.core.model.PagePoint
import com.studyink.core.model.PageBounds
import com.studyink.core.model.Student
import com.studyink.core.model.TEACHER_PAGE_REVIEW_ATTEMPT_NO
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.security.MessageDigest

data class LibraryState(
    val students: List<Student>,
    val selectedStudentId: String,
    val books: List<Book>,
)

private data class LibraryCatalog(
    val students: List<Student>,
    val selectedStudentId: String,
    val books: List<Book>,
    val attempts: List<Attempt>,
    val markGroups: List<MarkGroup>,
) {
    fun toLibraryState() = LibraryState(
        students.filter { it.hiddenAtEpochMillis == null },
        selectedStudentId,
        books.filter { it.hiddenAtEpochMillis == null },
    )
}

/** Small metadata catalog. PDF bytes are always owned by the app and never addressed by URI. */
class LibraryRepository private constructor(private val context: Context) {
    private val root = File(context.filesDir, "masternote").apply { mkdirs() }
    private val booksDirectory = File(root, "books").apply { mkdirs() }
    private val catalogFile = AtomicFile(File(root, "catalog-v2.json"))
    private val preferences = context.getSharedPreferences("masternote-device", Context.MODE_PRIVATE)
    private var catalog = loadCatalog()
    val state: LibraryState @Synchronized get() = catalog.toLibraryState()
    val deviceId: String = preferences.getString("deviceId", null)
        ?: UUID.randomUUID().toString().also { preferences.edit().putString("deviceId", it).apply() }

    /**
     * Runs [block] while catalog and owned-PDF mutations are excluded from this repository.
     * Backup code must finish reading [root] before the block returns.
     */
    @Synchronized
    fun <T> withStableDataRoot(block: (File) -> T): T = block(root)

    @Synchronized
    fun selectStudent(studentId: String) {
        require(catalog.students.any { it.id == studentId && it.hiddenAtEpochMillis == null })
        catalog = catalog.copy(selectedStudentId = studentId)
        persist()
    }

    @Synchronized
    fun addStudent(displayName: String): Student {
        val normalized = displayName.trim()
        require(normalized.isNotEmpty())
        val student = Student(displayName = normalized)
        catalog = catalog.copy(students = catalog.students + student)
        persist()
        return student
    }

    @Synchronized
    fun importPdf(studentId: String, source: Uri, requestedTitle: String? = null): Book {
        require(catalog.students.any { it.id == studentId && it.hiddenAtEpochMillis == null })
        val bookId = UUID.randomUUID().toString()
        val directory = File(booksDirectory, bookId).apply { check(mkdirs()) }
        val staging = File(directory, "document.pdf.staging")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(source).use { input ->
                requireNotNull(input) { "선택한 PDF를 읽을 수 없습니다." }
                FileOutputStream(staging).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            val pageCount = probePageCount(staging)
            require(pageCount > 0) { "페이지가 없는 PDF입니다." }
            val destination = File(directory, PDF_FILE)
            check(staging.renameTo(destination)) { "PDF 사본을 확정하지 못했습니다." }
            val title = requestedTitle?.trim().takeUnless { it.isNullOrEmpty() }
                ?: source.displayName().substringBeforeLast('.').ifBlank { "새 교재" }
            val book = Book(
                id = bookId,
                studentId = studentId,
                title = title,
                pageCount = pageCount,
                pdfRelativePath = "$bookId/$PDF_FILE",
                contentSha256 = digest.digest().toHex(),
            )
            catalog = catalog.copy(books = catalog.books + book)
            persist()
            return book
        } catch (error: Throwable) {
            staging.delete()
            directory.deleteRecursively()
            throw error
        }
    }

    @Synchronized
    fun renameBook(bookId: String, title: String) {
        val normalized = title.trim()
        require(normalized.isNotEmpty())
        catalog = catalog.copy(books = catalog.books.map { if (it.id == bookId) it.copy(title = normalized) else it })
        persist()
    }

    @Synchronized
    fun importAnswerSource(bookId: String, source: Uri): AnswerSource {
        val book = book(bookId)
        val directory = File(booksDirectory, book.id)
        val staging = File(directory, "answers.json.staging")
        context.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "정답 JSON을 읽을 수 없습니다." }
            FileOutputStream(staging).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_ANSWER_JSON_BYTES) { "정답 JSON이 너무 큽니다." }
                    output.write(buffer, 0, count)
                }
                output.flush()
                output.fd.sync()
            }
        }
        try {
            val root = JSONObject(staging.readText(Charsets.UTF_8))
            val itemsJson = root.getJSONArray("items")
            val ids = mutableSetOf<String>()
            val items = buildList {
                for (index in 0 until itemsJson.length()) {
                    val item = itemsJson.getJSONObject(index)
                    val id = item.getString("id")
                    require(id.isNotBlank() && ids.add(id)) { "정답 항목 ID가 비었거나 중복됩니다." }
                    val page = item.getInt("page")
                    require(page in 1..book.pageCount) { "정답 항목의 페이지가 교재 범위를 벗어납니다." }
                    val box = item.getJSONArray("bbox")
                    require(box.length() == 4) { "정답 항목 bbox는 숫자 4개여야 합니다." }
                    val bounds = PageBounds(
                        box.getDouble(0).toFloat(), box.getDouble(1).toFloat(),
                        box.getDouble(2).toFloat(), box.getDouble(3).toFloat(),
                    )
                    require(bounds.left >= 0f && bounds.right <= 1000f && bounds.left < bounds.right &&
                        bounds.top >= 0f && bounds.top < bounds.bottom) { "정답 항목 bbox가 올바르지 않습니다." }
                    add(AnswerItem(id, page, bounds, if (item.isNull("answer")) null else item.getString("answer")))
                }
            }
            val parsed = AnswerSource(root.getString("sourceId").also { require(it.isNotBlank()) }, items)
            val destination = File(directory, ANSWERS_FILE)
            if (destination.exists()) {
                check(destination.renameTo(File(directory, "answers-${System.currentTimeMillis()}.json.hidden")))
            }
            check(staging.renameTo(destination)) { "정답 JSON을 확정하지 못했습니다." }
            catalog = catalog.copy(books = catalog.books.map {
                if (it.id == bookId) it.copy(answerSourceRelativePath = "${book.id}/$ANSWERS_FILE") else it
            })
            persist()
            return parsed
        } catch (error: Throwable) {
            staging.delete()
            throw IllegalArgumentException("정답 JSON 형식이 올바르지 않습니다: ${error.message}", error)
        }
    }

    @Synchronized
    fun hideBook(bookId: String) {
        val now = System.currentTimeMillis()
        catalog = catalog.copy(books = catalog.books.map {
            if (it.id == bookId && it.hiddenAtEpochMillis == null) it.copy(hiddenAtEpochMillis = now) else it
        })
        persist()
    }

    @Synchronized
    fun book(bookId: String): Book = catalog.books.firstOrNull { it.id == bookId }
        ?: error("교재를 찾을 수 없습니다.")

    /**
     * Resolves a transport workbook fingerprint without trusting a remote title or local UUID.
     * Callers must require exactly one result; duplicate imports are deliberately not guessed.
     */
    @Synchronized
    fun booksByContentSha256(contentSha256: String): List<Book> {
        val normalized = contentSha256.trim().lowercase()
        if (normalized.length != 64 || normalized.any { it !in "0123456789abcdef" }) return emptyList()
        return catalog.books.filter {
            it.hiddenAtEpochMillis == null && it.contentSha256.lowercase() == normalized
        }
    }

    fun pdfFile(book: Book): File = File(booksDirectory, book.pdfRelativePath).also {
        require(it.isFile) { "교재 PDF 사본이 없습니다." }
    }

    @Synchronized
    fun attempts(bookId: String, pageNumber: Int): List<Attempt> = catalog.attempts
        .filter { it.bookId == bookId && it.pageNumber == pageNumber }
        .sortedBy(Attempt::attemptNo)

    /** Transport snapshot for reconnect recovery, ordered deterministically by page and attempt. */
    @Synchronized
    fun attemptsForSync(bookId: String): List<Attempt> {
        book(bookId)
        return catalog.attempts
            .filter { it.bookId == bookId }
            .sortedWith(compareBy(Attempt::pageNumber, Attempt::attemptNo))
    }

    /**
     * Returns one summary for every page without changing catalog format or stored entities.
     * Page indices stay zero-based to match ReaderActivity and the annotation store.
     */
    @Synchronized
    fun pageProgressSummaries(bookId: String): List<PageProgressSummary> {
        val book = book(bookId)
        return projectBookPageProgress(
            pageCount = book.pageCount,
            attempts = catalog.attempts.filter { it.bookId == bookId },
            markGroups = catalog.markGroups.filter { it.bookId == bookId },
        )
    }

    /** Ensures a student/perspective selection cannot accidentally open another student's book. */
    @Synchronized
    fun pageProgressSummaries(
        libraryContext: LibraryContext,
        bookId: String,
    ): List<PageProgressSummary> {
        val book = book(bookId)
        require(book.studentId == libraryContext.studentId) { "선택한 학생의 교재가 아닙니다." }
        return pageProgressSummaries(bookId)
    }

    @Synchronized
    fun pageProgressSummary(bookId: String, pageNumber: Int): PageProgressSummary {
        val book = book(bookId)
        require(pageNumber in 0 until book.pageCount) { "페이지가 교재 범위를 벗어납니다." }
        return pageProgressSummaries(bookId)[pageNumber]
    }

    @Synchronized
    fun writableAttempt(bookId: String, pageNumber: Int, create: Boolean): Attempt? {
        val attempts = attempts(bookId, pageNumber)
        attempts.lastOrNull { !it.locked }?.let { return it }
        if (!create) return null
        val next = Attempt(bookId, pageNumber, (attempts.maxOfOrNull(Attempt::attemptNo) ?: 0) + 1)
        catalog = catalog.copy(attempts = catalog.attempts + next)
        persist()
        LibraryAttemptBus.attemptChanged(next)
        return next
    }

    @Synchronized
    fun lockAttempt(bookId: String, pageNumber: Int, attemptNo: Int): Attempt {
        val existing = catalog.attempts.firstOrNull {
            it.bookId == bookId && it.pageNumber == pageNumber && it.attemptNo == attemptNo
        } ?: error("제출할 풀이가 없습니다.")
        if (existing.locked) return existing
        val locked = existing.copy(locked = true, lockedAtEpochMillis = System.currentTimeMillis())
        catalog = catalog.copy(attempts = catalog.attempts.map { if (it == existing) locked else it })
        persist()
        LibraryAttemptBus.attemptChanged(locked)
        return locked
    }

    @Synchronized
    fun markGroups(bookId: String, pageNumber: Int): List<MarkGroup> = catalog.markGroups
        .filter { it.bookId == bookId && it.pageNumber == pageNumber && it.hiddenAtEpochMillis == null }
        .sortedBy { it.anchor.y }

    /** Transport snapshot for reconnect recovery, including hidden group tombstones. */
    @Synchronized
    fun markGroupsForSync(bookId: String): List<MarkGroup> {
        book(bookId)
        return catalog.markGroups
            .filter { it.bookId == bookId }
            .sortedWith(compareBy(MarkGroup::pageNumber, MarkGroup::id))
    }

    @Synchronized
    fun addMark(
        bookId: String,
        pageNumber: Int,
        attemptNo: Int,
        anchor: PagePoint,
        color: MarkColor,
        groupId: String? = null,
        allowObservedStudentAttempt: Boolean = false,
    ): MarkGroup {
        val targetBook = book(bookId)
        require(pageNumber in 0 until targetBook.pageCount) { "채점 대상 페이지가 교재 범위를 벗어납니다." }
        require(
            isValidMarkAttemptTarget(bookId, pageNumber, attemptNo, catalog.attempts) ||
                allowObservedStudentAttempt && attemptNo > TEACHER_PAGE_REVIEW_ATTEMPT_NO
        ) {
            "채점 대상 풀이 회차가 없습니다."
        }
        val mark = Mark(attemptNo = attemptNo, color = color)
        val existing = groupId?.let { id -> catalog.markGroups.firstOrNull { it.id == id } }
        require(existing == null || existing.bookId == bookId && existing.pageNumber == pageNumber) {
            "다른 페이지의 채점 표시를 변경할 수 없습니다."
        }
        require(existing == null || isCompatibleMarkGroupTarget(existing, attemptNo)) {
            "페이지 표시와 학생 풀이 채점을 같은 표시 묶음에 섞을 수 없습니다."
        }
        val updated = existing?.copy(
            marks = existing.marks + mark,
            syncRevision = existing.nextSyncRevision(),
            lastModifiedByDeviceId = deviceId,
        ) ?: MarkGroup(
            bookId = bookId,
            pageNumber = pageNumber,
            anchor = anchor,
            marks = listOf(mark),
            syncRevision = 1L,
            lastModifiedByDeviceId = deviceId,
        )
        catalog = catalog.copy(markGroups = if (existing == null) {
            catalog.markGroups + updated
        } else {
            catalog.markGroups.map { if (it.id == existing.id) updated else it }
        })
        persist()
        LibraryMarkGroupBus.markGroupChanged(updated)
        return updated
    }

    /**
     * Durably commits one teacher-grade draft without duplicating a replayed draft mutation.
     *
     * A changed group advances its sync revision exactly once and emits one local mark event only
     * after the catalog write succeeds. Replaying an already materialized state performs neither
     * a catalog write nor an event emission.
     */
    @Synchronized
    fun commitTeacherGradeDraft(
        bookId: String,
        pageNumber: Int,
        attemptNo: Int,
        groupId: String,
        anchor: PagePoint,
        color: MarkColor,
        hidden: Boolean,
        appendMark: Boolean,
        createdAtEpochMillis: Long,
        updatedAtEpochMillis: Long,
    ): MarkGroup = commitTeacherGradeDrafts(
        listOf(
            TeacherGradeDraftCommitInput(
                bookId,
                pageNumber,
                attemptNo,
                groupId,
                anchor,
                color,
                hidden,
                appendMark,
                createdAtEpochMillis,
                updatedAtEpochMillis,
            ),
        ),
    ).single()

    /** Validates and folds the entire publish batch before one catalog write and any bus event. */
    @Synchronized
    fun previewTeacherGradeDrafts(
        drafts: List<TeacherGradeDraftCommitInput>,
    ): List<MarkGroup> = foldTeacherGradeDrafts(drafts).markGroups

    /** Validates and folds the entire publish batch before one catalog write and any bus event. */
    @Synchronized
    fun commitTeacherGradeDrafts(
        drafts: List<TeacherGradeDraftCommitInput>,
    ): List<MarkGroup> {
        if (drafts.isEmpty()) return emptyList()
        val previousCatalog = catalog
        val folded = foldTeacherGradeDrafts(drafts)
        if (folded.markGroups == previousCatalog.markGroups) return folded.committed
        catalog = previousCatalog.copy(markGroups = folded.markGroups)
        try {
            persist()
        } catch (error: Throwable) {
            catalog = previousCatalog
            throw error
        }
        folded.changedById.values.forEach(LibraryMarkGroupBus::markGroupChanged)
        return folded.committed
    }

    private fun foldTeacherGradeDrafts(
        drafts: List<TeacherGradeDraftCommitInput>,
    ): TeacherGradeDraftBatchFold {
        var workingGroups = catalog.markGroups
        val committed = ArrayList<MarkGroup>(drafts.size)
        val changedById = linkedMapOf<String, MarkGroup>()
        drafts.forEach { draft ->
            val targetBook = catalog.books.firstOrNull { it.id == draft.bookId }
                ?: error("교재를 찾을 수 없습니다.")
            val result = mergeTeacherGradeDraftCommit(
                markGroups = workingGroups,
                attempts = catalog.attempts,
                bookId = draft.bookId,
                pageNumber = draft.pageNumber,
                pageCount = targetBook.pageCount,
                attemptNo = draft.attemptNo,
                groupId = draft.groupId,
                anchor = draft.anchor,
                color = draft.color,
                hidden = draft.hidden,
                appendMark = draft.appendMark,
                createdAtEpochMillis = draft.createdAtEpochMillis,
                updatedAtEpochMillis = draft.updatedAtEpochMillis,
                deviceId = deviceId,
            )
            workingGroups = result.markGroups
            committed += result.committedGroup
            if (result.changed) changedById[result.committedGroup.id] = result.committedGroup
        }
        return TeacherGradeDraftBatchFold(workingGroups, committed, changedById)
    }

    private data class TeacherGradeDraftBatchFold(
        val markGroups: List<MarkGroup>,
        val committed: List<MarkGroup>,
        val changedById: Map<String, MarkGroup>,
    )

    @Synchronized
    fun changeLatestMarkColor(groupId: String, attemptNo: Int, color: MarkColor) {
        val existing = catalog.markGroups.firstOrNull { it.id == groupId } ?: return
        val lastIndex = existing.marks.indexOfLast {
            it.attemptNo == attemptNo && it.hiddenAtEpochMillis == null
        }
        if (lastIndex < 0 || existing.marks[lastIndex].color == color) return
        val updated = existing.copy(
            marks = existing.marks.mapIndexed { index, mark ->
                if (index == lastIndex) mark.copy(color = color) else mark
            },
            syncRevision = existing.nextSyncRevision(),
            lastModifiedByDeviceId = deviceId,
        )
        catalog = catalog.copy(markGroups = catalog.markGroups.map { group ->
            if (group.id == groupId) updated else group
        })
        persist()
        LibraryMarkGroupBus.markGroupChanged(updated)
    }

    @Synchronized
    fun moveMarkGroup(groupId: String, anchor: PagePoint) {
        val existing = catalog.markGroups.firstOrNull {
            it.id == groupId && it.hiddenAtEpochMillis == null
        } ?: return
        if (existing.anchor == anchor) return
        val updated = existing.copy(
            anchor = anchor,
            syncRevision = existing.nextSyncRevision(),
            lastModifiedByDeviceId = deviceId,
        )
        catalog = catalog.copy(markGroups = catalog.markGroups.map { group ->
            if (group.id == groupId) updated else group
        })
        persist()
        LibraryMarkGroupBus.markGroupChanged(updated)
    }

    @Synchronized
    fun hideMarkGroup(groupId: String) {
        val existing = catalog.markGroups.firstOrNull {
            it.id == groupId && it.hiddenAtEpochMillis == null
        } ?: return
        val now = System.currentTimeMillis()
        val updated = existing.copy(
            hiddenAtEpochMillis = now,
            syncRevision = existing.nextSyncRevision(),
            lastModifiedByDeviceId = deviceId,
        )
        catalog = catalog.copy(markGroups = catalog.markGroups.map {
            if (it.id == groupId) updated else it
        })
        persist()
        LibraryMarkGroupBus.markGroupChanged(updated)
    }

    /**
     * Applies a full peer snapshot by identity. Replaying the same snapshot is a no-op, and this
     * path intentionally does not notify [LibraryMarkGroupBus] so received data cannot echo.
     */
    @Synchronized
    fun upsertMarkGroupFromSync(
        bookId: String,
        pageNumber: Int,
        incoming: MarkGroup,
    ): Boolean {
        val targetBook = book(bookId)
        val result = mergeRemoteMarkGroup(
            markGroups = catalog.markGroups,
            bookId = bookId,
            pageNumber = pageNumber,
            pageCount = targetBook.pageCount,
            attempts = catalog.attempts,
            incoming = incoming,
        )
        if (!result.changed) return false
        catalog = catalog.copy(markGroups = result.markGroups)
        persist()
        return true
    }

    /**
     * Applies only one student-attempt slice from a remote teacher review.
     *
     * Telegram review envelopes version the selected attempt independently from the group's
     * full-state LAN revision. Consequently, an older full-group revision may still contain the
     * newest review for [attemptNo]. Other attempts are retained verbatim, while group-level
     * metadata follows the normal deterministic sync ordering. This received-data path does not
     * notify [LibraryMarkGroupBus], preventing a review from echoing back to its sender.
     */
    @Synchronized
    fun upsertMarkGroupAttemptFromSync(
        bookId: String,
        pageNumber: Int,
        attemptNo: Int,
        incoming: MarkGroup,
    ): Boolean = upsertMarkGroupAttemptsFromSync(
        bookId = bookId,
        pageNumber = pageNumber,
        attemptNo = attemptNo,
        incoming = listOf(incoming),
    )

    /**
     * Atomically applies every exact-attempt group carried by one teacher-review envelope.
     *
     * The entire batch is validated and folded before the catalog is installed. A changed batch
     * produces one durable catalog write and no [LibraryMarkGroupBus] event; an invalid group,
     * identity collision, or persistence failure leaves the in-memory catalog unchanged.
     */
    @Synchronized
    fun upsertMarkGroupAttemptsFromSync(
        bookId: String,
        pageNumber: Int,
        attemptNo: Int,
        incoming: List<MarkGroup>,
    ): Boolean {
        val targetBook = book(bookId)
        val result = mergeRemoteMarkGroupAttempts(
            markGroups = catalog.markGroups,
            bookId = bookId,
            pageNumber = pageNumber,
            pageCount = targetBook.pageCount,
            attempts = catalog.attempts,
            attemptNo = attemptNo,
            incoming = incoming,
        )
        val previousCatalog = catalog
        return applyRemoteMarkGroupAttemptBatch(
            result = result,
            install = { markGroups -> catalog = previousCatalog.copy(markGroups = markGroups) },
            rollback = { catalog = previousCatalog },
            persist = ::persist,
        )
    }

    /** Idempotent peer upsert. Received attempts are not emitted back onto [LibraryAttemptBus]. */
    @Synchronized
    fun upsertAttemptFromSync(
        bookId: String,
        pageNumber: Int,
        incoming: Attempt,
    ): Boolean {
        val targetBook = book(bookId)
        val result = mergeRemoteAttempt(
            attempts = catalog.attempts,
            bookId = bookId,
            pageNumber = pageNumber,
            pageCount = targetBook.pageCount,
            incoming = incoming,
        )
        if (!result.changed) return false
        catalog = catalog.copy(attempts = result.attempts)
        persist()
        return true
    }

    private fun probePageCount(file: File): Int {
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return PdfRenderer(descriptor).use(PdfRenderer::getPageCount)
    }

    private fun Uri.displayName(): String {
        if (scheme == "content") {
            context.contentResolver.query(this, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) return cursor.getString(0)
            }
        }
        return lastPathSegment?.substringAfterLast('/') ?: "새 교재.pdf"
    }

    private fun loadCatalog(): LibraryCatalog {
        if (!catalogFile.baseFile.exists()) {
            val students = listOf(
                Student(id = "student-1", displayName = "학생 1"),
                Student(id = "student-2", displayName = "학생 2"),
            )
            return LibraryCatalog(students, students.first().id, emptyList(), emptyList(), emptyList()).also(::writeCatalog)
        }
        return decodeCatalog(JSONObject(catalogFile.readFully().toString(Charsets.UTF_8)))
    }

    private fun persist() {
        writeCatalog(catalog)
    }

    private fun writeCatalog(value: LibraryCatalog) {
        val stream = catalogFile.startWrite()
        try {
            stream.write(encodeCatalog(value).toString().toByteArray(Charsets.UTF_8))
            stream.flush()
            catalogFile.finishWrite(stream)
        } catch (error: Throwable) {
            catalogFile.failWrite(stream)
            throw error
        }
        MasterNoteDataCommitBus.recordDurableCommit()
    }

    companion object {
        private const val CATALOG_FORMAT = 2
        private const val PDF_FILE = "document.pdf"
        private const val ANSWERS_FILE = "answers.json"
        private const val MAX_ANSWER_JSON_BYTES = 5L * 1024L * 1024L

        @Volatile private var instance: LibraryRepository? = null

        fun get(context: Context): LibraryRepository = instance ?: synchronized(this) {
            instance ?: LibraryRepository(context.applicationContext).also { instance = it }
        }

        /**
         * Drops the process singleton after a validated restore has replaced the data root.
         * Existing holders are refreshed under their repository lock before a new instance may be
         * created, so a still-finishing Activity cannot write a stale pre-restore catalog.
         */
        @Synchronized
        fun resetForRestore() {
            instance?.let { current ->
                synchronized(current) {
                    current.catalog = current.loadCatalog()
                    instance = null
                }
            }
        }
    }
}

internal fun isValidMarkAttemptTarget(
    bookId: String,
    pageNumber: Int,
    attemptNo: Int,
    attempts: List<Attempt>,
): Boolean = attemptNo == TEACHER_PAGE_REVIEW_ATTEMPT_NO || attempts.any {
    it.bookId == bookId && it.pageNumber == pageNumber && it.attemptNo == attemptNo
}

internal fun isCompatibleMarkGroupTarget(group: MarkGroup, attemptNo: Int): Boolean {
    val pageLevel = attemptNo == TEACHER_PAGE_REVIEW_ATTEMPT_NO
    return group.marks.all { mark ->
        (mark.attemptNo == TEACHER_PAGE_REVIEW_ATTEMPT_NO) == pageLevel
    }
}

private fun MarkGroup.nextSyncRevision(): Long {
    check(syncRevision < Long.MAX_VALUE) { "채점 표시 변경 번호가 한도를 초과했습니다." }
    return syncRevision + 1L
}

internal data class MarkGroupUpsertResult(
    val markGroups: List<MarkGroup>,
    val changed: Boolean,
)

internal data class AttemptUpsertResult(
    val attempts: List<Attempt>,
    val changed: Boolean,
)

internal fun mergeRemoteAttempt(
    attempts: List<Attempt>,
    bookId: String,
    pageNumber: Int,
    pageCount: Int,
    incoming: Attempt,
): AttemptUpsertResult {
    require(pageNumber in 0 until pageCount) { "풀이 회차 페이지가 교재 범위를 벗어납니다." }
    require(incoming.bookId == bookId && incoming.pageNumber == pageNumber) {
        "다른 교재 또는 페이지의 풀이 회차를 동기화할 수 없습니다."
    }
    require(incoming.attemptNo > TEACHER_PAGE_REVIEW_ATTEMPT_NO) {
        "학생 풀이 회차 번호가 올바르지 않습니다."
    }
    require(incoming.startedAtEpochMillis >= 0L) { "풀이 시작 시간이 올바르지 않습니다." }
    val lockedAt = incoming.lockedAtEpochMillis
    require(
        if (incoming.locked) {
            lockedAt != null && lockedAt >= incoming.startedAtEpochMillis
        } else {
            lockedAt == null
        }
    ) { "풀이 제출 상태가 올바르지 않습니다." }

    val existing = attempts.firstOrNull {
        it.bookId == bookId && it.pageNumber == pageNumber && it.attemptNo == incoming.attemptNo
    }
    if (existing == incoming || existing?.locked == true && !incoming.locked) {
        return AttemptUpsertResult(attempts, changed = false)
    }
    return AttemptUpsertResult(
        attempts = if (existing == null) {
            attempts + incoming
        } else {
            attempts.map { if (it == existing) incoming else it }
        },
        changed = true,
    )
}

/** Pure merge used by the repository and JVM tests; all peer-controlled fields are checked here. */
internal fun mergeRemoteMarkGroup(
    markGroups: List<MarkGroup>,
    bookId: String,
    pageNumber: Int,
    pageCount: Int,
    attempts: List<Attempt>,
    incoming: MarkGroup,
): MarkGroupUpsertResult {
    require(pageNumber in 0 until pageCount) { "채점 대상 페이지가 교재 범위를 벗어납니다." }
    require(incoming.bookId == bookId && incoming.pageNumber == pageNumber) {
        "다른 교재 또는 페이지의 채점 표시를 동기화할 수 없습니다."
    }
    require(incoming.id.isNotBlank() && incoming.id.length <= 256) { "채점 표시 ID가 올바르지 않습니다." }
    require(incoming.createdAtEpochMillis >= 0L && (incoming.hiddenAtEpochMillis ?: 0L) >= 0L) {
        "채점 표시 시간이 올바르지 않습니다."
    }
    require(incoming.syncRevision >= 0L && incoming.lastModifiedByDeviceId.length <= 256) {
        "채점 표시 변경 정보가 올바르지 않습니다."
    }
    require(incoming.syncRevision == 0L || incoming.lastModifiedByDeviceId.isNotBlank()) {
        "채점 표시 변경 기기가 비어 있습니다."
    }
    require(
        incoming.anchor.x.isFinite() && incoming.anchor.x in 0f..1000f &&
            incoming.anchor.y.isFinite() && incoming.anchor.y in 0f..1_000_000f &&
            incoming.anchor.pressure.isFinite() && incoming.anchor.pressure >= 0f
    ) { "채점 표시 위치가 올바르지 않습니다." }
    require(incoming.marks.isNotEmpty() && incoming.marks.size <= 4_096) {
        "채점 표시 이력이 올바르지 않습니다."
    }
    val includesPageTarget = incoming.marks.any {
        it.attemptNo == TEACHER_PAGE_REVIEW_ATTEMPT_NO
    }
    require(!includesPageTarget || incoming.marks.all {
        it.attemptNo == TEACHER_PAGE_REVIEW_ATTEMPT_NO
    }) { "페이지 표시와 학생 풀이 채점을 같은 표시 묶음에 섞을 수 없습니다." }
    require(incoming.marks.all { mark ->
        mark.gradedAtEpochMillis >= 0L && (mark.hiddenAtEpochMillis ?: 0L) >= 0L &&
            isValidMarkAttemptTarget(bookId, pageNumber, mark.attemptNo, attempts)
    }) { "채점 대상 풀이 회차가 없습니다." }

    val existing = markGroups.firstOrNull { it.id == incoming.id }
    require(existing == null || existing.bookId == bookId && existing.pageNumber == pageNumber) {
        "같은 ID의 채점 표시가 다른 교재 또는 페이지에 있습니다."
    }
    if (existing == incoming) return MarkGroupUpsertResult(markGroups, changed = false)
    if (existing != null && incoming.compareSyncOrder(existing) <= 0) {
        return MarkGroupUpsertResult(markGroups, changed = false)
    }
    return MarkGroupUpsertResult(
        markGroups = if (existing == null) {
            markGroups + incoming
        } else {
            markGroups.map { if (it.id == incoming.id) incoming else it }
        },
        changed = true,
    )
}

/** Executes the batch's only durable side effect; received groups are deliberately not emitted. */
internal fun applyRemoteMarkGroupAttemptBatch(
    result: MarkGroupUpsertResult,
    install: (List<MarkGroup>) -> Unit,
    rollback: () -> Unit,
    persist: () -> Unit,
): Boolean {
    if (!result.changed) return false
    install(result.markGroups)
    try {
        persist()
    } catch (error: Throwable) {
        rollback()
        throw error
    }
    return true
}

/**
 * Pure preflight and fold for one exact-attempt teacher-review envelope.
 *
 * Every payload is checked against the original catalog before any fold result is returned. IDs
 * must also be unique within the envelope, so input order can never decide an identity collision.
 */
internal fun mergeRemoteMarkGroupAttempts(
    markGroups: List<MarkGroup>,
    bookId: String,
    pageNumber: Int,
    pageCount: Int,
    attempts: List<Attempt>,
    attemptNo: Int,
    incoming: List<MarkGroup>,
): MarkGroupUpsertResult {
    require(pageNumber in 0 until pageCount) { "채점 대상 페이지가 교재 범위를 벗어납니다." }
    require(attemptNo > TEACHER_PAGE_REVIEW_ATTEMPT_NO) { "학생 풀이 회차 번호가 올바르지 않습니다." }
    require(isValidMarkAttemptTarget(bookId, pageNumber, attemptNo, attempts)) {
        "채점 대상 풀이 회차가 없습니다."
    }
    val incomingIds = HashSet<String>()
    require(incoming.all { incomingIds.add(it.id) }) {
        "한 번의 채점 동기화에 같은 표시 ID가 중복되어 있습니다."
    }

    incoming.forEach { group ->
        mergeRemoteMarkGroupAttempt(
            markGroups = markGroups,
            bookId = bookId,
            pageNumber = pageNumber,
            pageCount = pageCount,
            attempts = attempts,
            attemptNo = attemptNo,
            incoming = group,
        )
    }

    var folded = MarkGroupUpsertResult(markGroups = markGroups, changed = false)
    incoming.forEach { group ->
        val next = mergeRemoteMarkGroupAttempt(
            markGroups = folded.markGroups,
            bookId = bookId,
            pageNumber = pageNumber,
            pageCount = pageCount,
            attempts = attempts,
            attemptNo = attemptNo,
            incoming = group,
        )
        folded = MarkGroupUpsertResult(
            markGroups = next.markGroups,
            changed = folded.changed || next.changed,
        )
    }
    return folded
}

/**
 * Pure exact-attempt merge for delayed teacher reviews.
 *
 * The envelope that carries this payload owns ordering for [attemptNo], so its marks are applied
 * even when [incoming]'s full-group sync revision is older. The full-group ordering is consulted
 * only for anchor/visibility/creator metadata. This keeps independently delivered attempt reviews
 * from deleting one another.
 */
internal fun mergeRemoteMarkGroupAttempt(
    markGroups: List<MarkGroup>,
    bookId: String,
    pageNumber: Int,
    pageCount: Int,
    attempts: List<Attempt>,
    attemptNo: Int,
    incoming: MarkGroup,
): MarkGroupUpsertResult {
    require(pageNumber in 0 until pageCount) { "채점 대상 페이지가 교재 범위를 벗어납니다." }
    require(attemptNo > TEACHER_PAGE_REVIEW_ATTEMPT_NO) { "학생 풀이 회차 번호가 올바르지 않습니다." }
    require(isValidMarkAttemptTarget(bookId, pageNumber, attemptNo, attempts)) {
        "채점 대상 풀이 회차가 없습니다."
    }
    require(incoming.bookId == bookId && incoming.pageNumber == pageNumber) {
        "다른 교재 또는 페이지의 채점 표시를 동기화할 수 없습니다."
    }
    require(incoming.id.isNotBlank() && incoming.id.length <= 256) { "채점 표시 ID가 올바르지 않습니다." }
    require(incoming.createdAtEpochMillis >= 0L && (incoming.hiddenAtEpochMillis ?: 0L) >= 0L) {
        "채점 표시 시간이 올바르지 않습니다."
    }
    require(incoming.syncRevision >= 0L && incoming.lastModifiedByDeviceId.length <= 256) {
        "채점 표시 변경 정보가 올바르지 않습니다."
    }
    require(incoming.syncRevision == 0L || incoming.lastModifiedByDeviceId.isNotBlank()) {
        "채점 표시 변경 기기가 비어 있습니다."
    }
    require(
        incoming.anchor.x.isFinite() && incoming.anchor.x in 0f..1000f &&
            incoming.anchor.y.isFinite() && incoming.anchor.y in 0f..1_000_000f &&
            incoming.anchor.pressure.isFinite() && incoming.anchor.pressure >= 0f
    ) { "채점 표시 위치가 올바르지 않습니다." }
    require(incoming.marks.isNotEmpty() && incoming.marks.size <= 4_096) {
        "채점 표시 이력이 올바르지 않습니다."
    }
    require(incoming.marks.all { mark ->
        mark.attemptNo == attemptNo && mark.gradedAtEpochMillis >= 0L &&
            (mark.hiddenAtEpochMillis ?: 0L) >= 0L
    }) { "선택한 풀이 회차의 채점 표시만 동기화할 수 있습니다." }

    val existing = markGroups.firstOrNull { it.id == incoming.id }
    require(existing == null || existing.bookId == bookId && existing.pageNumber == pageNumber) {
        "같은 ID의 채점 표시가 다른 교재 또는 페이지에 있습니다."
    }
    require(existing == null || isCompatibleMarkGroupTarget(existing, attemptNo)) {
        "페이지 표시와 학생 풀이 채점을 같은 표시 묶음에 섞을 수 없습니다."
    }

    if (existing == null) {
        return MarkGroupUpsertResult(markGroups + incoming, changed = true)
    }

    val mergedMarks = replaceAttemptSlice(
        existing = existing.marks,
        attemptNo = attemptNo,
        replacement = incoming.marks,
    )
    require(mergedMarks.size <= 4_096) { "채점 표시 이력이 너무 큽니다." }
    val metadataSource = if (incoming.compareGlobalSyncOrder(existing) > 0) incoming else existing
    val merged = metadataSource.copy(
        marks = mergedMarks,
        syncRevision = maxOf(existing.syncRevision, incoming.syncRevision),
    )
    if (merged == existing) return MarkGroupUpsertResult(markGroups, changed = false)
    return MarkGroupUpsertResult(
        markGroups = markGroups.map { if (it.id == existing.id) merged else it },
        changed = true,
    )
}

private fun replaceAttemptSlice(
    existing: List<Mark>,
    attemptNo: Int,
    replacement: List<Mark>,
): List<Mark> {
    val insertionIndex = existing.indexOfFirst { it.attemptNo == attemptNo }
    if (insertionIndex < 0) return existing + replacement
    return buildList(existing.size - existing.count { it.attemptNo == attemptNo } + replacement.size) {
        existing.forEachIndexed { index, mark ->
            if (index == insertionIndex) addAll(replacement)
            if (mark.attemptNo != attemptNo) add(mark)
        }
    }
}

private fun MarkGroup.compareGlobalSyncOrder(other: MarkGroup): Int {
    syncRevision.compareTo(other.syncRevision).takeIf { it != 0 }?.let { return it }
    lastModifiedByDeviceId.compareTo(other.lastModifiedByDeviceId).takeIf { it != 0 }?.let { return it }
    return globalSyncStateKey().compareTo(other.globalSyncStateKey())
}

private fun MarkGroup.globalSyncStateKey(): String = buildString {
    append(createdAtEpochMillis).append('|')
    append(hiddenAtEpochMillis ?: -1L).append('|')
    append(anchor.x.toRawBits()).append(',')
    append(anchor.y.toRawBits()).append(',')
    append(anchor.pressure.toRawBits())
}

private fun MarkGroup.compareSyncOrder(other: MarkGroup): Int {
    syncRevision.compareTo(other.syncRevision).takeIf { it != 0 }?.let { return it }
    lastModifiedByDeviceId.compareTo(other.lastModifiedByDeviceId).takeIf { it != 0 }?.let { return it }
    return syncStateKey().compareTo(other.syncStateKey())
}

private fun MarkGroup.syncStateKey(): String = buildString {
        append(createdAtEpochMillis).append('|')
        append(hiddenAtEpochMillis ?: -1L).append('|')
        append(anchor.x.toRawBits()).append(',')
        append(anchor.y.toRawBits()).append(',')
        append(anchor.pressure.toRawBits()).append('|')
        marks.forEach { mark ->
            append(mark.attemptNo).append(',')
            append(mark.color.name).append(',')
            append(mark.gradedAtEpochMillis).append(',')
            append(mark.hiddenAtEpochMillis ?: -1L).append(';')
        }
    }

private fun encodeCatalog(catalog: LibraryCatalog): JSONObject {
    return JSONObject().put("formatVersion", 2).put("selectedStudentId", catalog.selectedStudentId)
        .put("students", JSONArray().apply { catalog.students.forEach { put(it.toJson()) } })
        .put("books", JSONArray().apply { catalog.books.forEach { put(it.toJson()) } })
        .put("attempts", JSONArray().apply { catalog.attempts.forEach { put(it.toJson()) } })
        .put("markGroups", JSONArray().apply { catalog.markGroups.forEach { put(it.toCatalogJson()) } })
}

private fun Student.toJson() = JSONObject().put("id", id).put("displayName", displayName)
    .put("createdAt", createdAtEpochMillis).put("hiddenAt", hiddenAtEpochMillis ?: JSONObject.NULL)
private fun Book.toJson() = JSONObject().put("id", id).put("studentId", studentId).put("title", title)
    .put("pageCount", pageCount).put("pdfPath", pdfRelativePath)
    .put("contentSha256", contentSha256)
    .put("answerPath", answerSourceRelativePath ?: JSONObject.NULL)
    .put("createdAt", createdAtEpochMillis).put("hiddenAt", hiddenAtEpochMillis ?: JSONObject.NULL)
private fun Attempt.toJson() = JSONObject().put("bookId", bookId).put("page", pageNumber)
    .put("attemptNo", attemptNo).put("locked", locked).put("startedAt", startedAtEpochMillis)
    .put("lockedAt", lockedAtEpochMillis ?: JSONObject.NULL)
internal fun MarkGroup.toCatalogJson() = JSONObject().put("id", id).put("bookId", bookId).put("page", pageNumber)
    .put("anchor", JSONArray().put(anchor.x).put(anchor.y).put(anchor.pressure))
    .put("createdAt", createdAtEpochMillis)
    .put("hiddenAt", hiddenAtEpochMillis ?: JSONObject.NULL)
    .put("syncRevision", syncRevision)
    .put("lastModifiedByDeviceId", lastModifiedByDeviceId)
    .put("marks", JSONArray().apply {
        marks.forEach { mark -> put(JSONObject().put("attemptNo", mark.attemptNo).put("color", mark.color.name)
            .put("gradedAt", mark.gradedAtEpochMillis).put("hiddenAt", mark.hiddenAtEpochMillis ?: JSONObject.NULL)) }
    })

private fun decodeCatalog(root: JSONObject): LibraryCatalog {
    require(root.getInt("formatVersion") == 2) { "지원하지 않는 책장 데이터입니다." }
    val students = root.getJSONArray("students").objects { Student(
        id = getString("id"), displayName = getString("displayName"), createdAtEpochMillis = getLong("createdAt"),
        hiddenAtEpochMillis = nullableLong("hiddenAt"),
    ) }
    val books = root.getJSONArray("books").objects { Book(
        id = getString("id"), studentId = getString("studentId"), title = getString("title"),
        pageCount = getInt("pageCount"), pdfRelativePath = getString("pdfPath"),
        contentSha256 = optString("contentSha256"),
        answerSourceRelativePath = nullableString("answerPath"), createdAtEpochMillis = getLong("createdAt"),
        hiddenAtEpochMillis = nullableLong("hiddenAt"),
    ) }
    val attempts = root.getJSONArray("attempts").objects { Attempt(
        bookId = getString("bookId"), pageNumber = getInt("page"), attemptNo = getInt("attemptNo"),
        locked = getBoolean("locked"), startedAtEpochMillis = getLong("startedAt"),
        lockedAtEpochMillis = nullableLong("lockedAt"),
    ) }
    val groups = root.getJSONArray("markGroups").objects(JSONObject::toCatalogMarkGroup)
    return LibraryCatalog(students, root.getString("selectedStudentId"), books, attempts, groups)
}

internal fun JSONObject.toCatalogMarkGroup(): MarkGroup {
    val group = this
    val anchor = getJSONArray("anchor")
    return MarkGroup(
        id = getString("id"), bookId = getString("bookId"), pageNumber = getInt("page"),
        anchor = PagePoint(
            x = anchor.getDouble(0).toFloat(),
            y = anchor.getDouble(1).toFloat(),
            pressure = if (anchor.length() >= 3) anchor.getDouble(2).toFloat() else 1f,
        ),
        marks = getJSONArray("marks").objects { Mark(
            attemptNo = getInt("attemptNo"), color = MarkColor.valueOf(getString("color")),
            gradedAtEpochMillis = getLong("gradedAt"), hiddenAtEpochMillis = nullableLong("hiddenAt"),
        ) },
        createdAtEpochMillis = group.getLong("createdAt"), hiddenAtEpochMillis = group.nullableLong("hiddenAt"),
        syncRevision = group.optLong("syncRevision", 0L),
        lastModifiedByDeviceId = group.optString("lastModifiedByDeviceId", ""),
    )
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private inline fun <T> JSONArray.objects(block: JSONObject.() -> T): List<T> = buildList {
    for (index in 0 until length()) add(getJSONObject(index).block())
}
private fun JSONObject.nullableLong(name: String): Long? = if (isNull(name)) null else getLong(name)
private fun JSONObject.nullableString(name: String): String? = if (isNull(name)) null else getString(name)
