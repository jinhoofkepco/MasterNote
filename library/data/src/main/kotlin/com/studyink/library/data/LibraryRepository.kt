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
import com.studyink.core.model.PagePoint
import com.studyink.core.model.PageBounds
import com.studyink.core.model.Student
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

    fun pdfFile(book: Book): File = File(booksDirectory, book.pdfRelativePath).also {
        require(it.isFile) { "교재 PDF 사본이 없습니다." }
    }

    @Synchronized
    fun attempts(bookId: String, pageNumber: Int): List<Attempt> = catalog.attempts
        .filter { it.bookId == bookId && it.pageNumber == pageNumber }
        .sortedBy(Attempt::attemptNo)

    @Synchronized
    fun writableAttempt(bookId: String, pageNumber: Int, create: Boolean): Attempt? {
        val attempts = attempts(bookId, pageNumber)
        attempts.lastOrNull { !it.locked }?.let { return it }
        if (!create) return null
        val next = Attempt(bookId, pageNumber, (attempts.maxOfOrNull(Attempt::attemptNo) ?: 0) + 1)
        catalog = catalog.copy(attempts = catalog.attempts + next)
        persist()
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
        return locked
    }

    @Synchronized
    fun markGroups(bookId: String, pageNumber: Int): List<MarkGroup> = catalog.markGroups
        .filter { it.bookId == bookId && it.pageNumber == pageNumber && it.hiddenAtEpochMillis == null }
        .sortedBy { it.anchor.y }

    @Synchronized
    fun addMark(
        bookId: String,
        pageNumber: Int,
        attemptNo: Int,
        anchor: PagePoint,
        color: MarkColor,
        groupId: String? = null,
    ): MarkGroup {
        val mark = Mark(attemptNo = attemptNo, color = color)
        val existing = groupId?.let { id -> catalog.markGroups.firstOrNull { it.id == id } }
        val updated = existing?.copy(marks = existing.marks + mark)
            ?: MarkGroup(bookId = bookId, pageNumber = pageNumber, anchor = anchor, marks = listOf(mark))
        catalog = catalog.copy(markGroups = if (existing == null) {
            catalog.markGroups + updated
        } else {
            catalog.markGroups.map { if (it.id == existing.id) updated else it }
        })
        persist()
        return updated
    }

    @Synchronized
    fun changeLatestMarkColor(groupId: String, attemptNo: Int, color: MarkColor) {
        catalog = catalog.copy(markGroups = catalog.markGroups.map { group ->
            if (group.id != groupId) group else group.copy(
                marks = group.marks.mapIndexed { index, mark ->
                    val lastIndex = group.marks.indexOfLast { it.attemptNo == attemptNo && it.hiddenAtEpochMillis == null }
                    if (index == lastIndex) mark.copy(color = color) else mark
                }
            )
        })
        persist()
    }

    @Synchronized
    fun moveMarkGroup(groupId: String, anchor: PagePoint) {
        catalog = catalog.copy(markGroups = catalog.markGroups.map { group ->
            if (group.id == groupId && group.hiddenAtEpochMillis == null) group.copy(anchor = anchor) else group
        })
        persist()
    }

    @Synchronized
    fun hideMarkGroup(groupId: String) {
        val now = System.currentTimeMillis()
        catalog = catalog.copy(markGroups = catalog.markGroups.map {
            if (it.id == groupId) it.copy(hiddenAtEpochMillis = now) else it
        })
        persist()
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
    }
}

private fun encodeCatalog(catalog: LibraryCatalog): JSONObject {
    return JSONObject().put("formatVersion", 2).put("selectedStudentId", catalog.selectedStudentId)
        .put("students", JSONArray().apply { catalog.students.forEach { put(it.toJson()) } })
        .put("books", JSONArray().apply { catalog.books.forEach { put(it.toJson()) } })
        .put("attempts", JSONArray().apply { catalog.attempts.forEach { put(it.toJson()) } })
        .put("markGroups", JSONArray().apply { catalog.markGroups.forEach { put(it.toJson()) } })
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
private fun MarkGroup.toJson() = JSONObject().put("id", id).put("bookId", bookId).put("page", pageNumber)
    .put("anchor", JSONArray().put(anchor.x).put(anchor.y)).put("createdAt", createdAtEpochMillis)
    .put("hiddenAt", hiddenAtEpochMillis ?: JSONObject.NULL).put("marks", JSONArray().apply {
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
    val groups = root.getJSONArray("markGroups").objects {
        val group = this
        val anchor = getJSONArray("anchor")
        MarkGroup(
            id = getString("id"), bookId = getString("bookId"), pageNumber = getInt("page"),
            anchor = PagePoint(anchor.getDouble(0).toFloat(), anchor.getDouble(1).toFloat()),
            marks = getJSONArray("marks").objects { Mark(
                attemptNo = getInt("attemptNo"), color = MarkColor.valueOf(getString("color")),
                gradedAtEpochMillis = getLong("gradedAt"), hiddenAtEpochMillis = nullableLong("hiddenAt"),
            ) },
            createdAtEpochMillis = group.getLong("createdAt"), hiddenAtEpochMillis = group.nullableLong("hiddenAt"),
        )
    }
    return LibraryCatalog(students, root.getString("selectedStudentId"), books, attempts, groups)
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private inline fun <T> JSONArray.objects(block: JSONObject.() -> T): List<T> = buildList {
    for (index in 0 until length()) add(getJSONObject(index).block())
}
private fun JSONObject.nullableLong(name: String): Long? = if (isNull(name)) null else getLong(name)
private fun JSONObject.nullableString(name: String): String? = if (isNull(name)) null else getString(name)
