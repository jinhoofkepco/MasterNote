package com.studyink.backup.storage

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal const val BACKUP_FORMAT = "com.studyink.masternote.backup"
internal const val BACKUP_FORMAT_VERSION = 1
internal const val MANIFEST_PATH = "manifest.json"
internal const val CATALOG_PATH = "data/catalog-v2.json"
internal const val IDENTITY_PATH = "identity/device.json"

internal data class ArchiveDigest(
    val path: String,
    val size: Long,
    val sha256: String,
)

internal data class ArchiveManifest(
    val createdAtEpochMillis: Long,
    val sourcePackageName: String,
    val sourceVersionName: String,
    val sourceVersionCode: Long,
    val sourceGeneration: Long,
    val files: List<ArchiveDigest>,
)

internal data class ArchiveIdentity(
    val deviceId: String,
    val androidIdSha256: String?,
)

internal data class ValidatedArchive(
    val manifest: ArchiveManifest,
    val identity: ArchiveIdentity,
    val totalBytes: Long,
)

class BackupValidationException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

internal object BackupArchive {
    private const val MAX_ENTRY_COUNT = 20_000
    private const val MAX_ENTRY_BYTES = 4L * 1024L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 8L * 1024L * 1024L * 1024L
    private const val MAX_MANIFEST_BYTES = 2L * 1024L * 1024L
    private const val MAX_IDENTITY_BYTES = 64L * 1024L
    private const val MAX_CATALOG_BYTES = 64L * 1024L * 1024L
    private const val COPY_BUFFER_BYTES = 128 * 1024
    private val SHA_256 = Regex("[0-9a-f]{64}")

    fun writeIdentity(file: File, identity: ArchiveIdentity) {
        file.parentFile?.mkdirs()
        val json = JSONObject()
            .put("deviceId", identity.deviceId)
            .put("androidIdSha256", identity.androidIdSha256 ?: JSONObject.NULL)
        writeDurably(file, json.toString().toByteArray(Charsets.UTF_8))
    }

    fun readIdentity(file: File): ArchiveIdentity {
        if (!file.isFile || file.length() > MAX_IDENTITY_BYTES) invalid("백업 기기 정보가 올바르지 않습니다.")
        return parseIdentity(file.readBytes())
    }

    /** Upgrades legacy format-2 catalogs that predate the persisted PDF hash field. */
    fun populateMissingCatalogHashes(dataRoot: File) {
        val catalogFile = File(dataRoot, "catalog-v2.json")
        val catalog = try {
            JSONObject(catalogFile.readText(Charsets.UTF_8))
        } catch (error: Throwable) {
            throw BackupValidationException("책장 데이터를 백업용으로 준비할 수 없습니다.", error)
        }
        val books = catalog.getJSONArray("books")
        var changed = false
        for (index in 0 until books.length()) {
            val book = books.getJSONObject(index)
            val id = requireValidBookId(book.getString("id"))
            val pdfPath = requireValidCatalogRelativePath(book.getString("pdfPath"))
            if (pdfPath != "$id/document.pdf") invalid("교재 PDF 경로가 교재 ID와 일치하지 않습니다: $pdfPath")
            val pdfFile = safeDestination(File(dataRoot, "books").canonicalFile, pdfPath)
            if (!pdfFile.isFile) invalid("교재 PDF가 없습니다: $pdfPath")
            if (!book.isNull("answerPdfPath")) {
                val answerPdfPath = requireValidAnswerPdfPath(
                    id,
                    requireValidCatalogRelativePath(book.getString("answerPdfPath")),
                )
                val answerPdfFile = safeDestination(File(dataRoot, "books").canonicalFile, answerPdfPath)
                if (!answerPdfFile.isFile) invalid("답안 PDF가 없습니다: $answerPdfPath")
            }
            if (book.optString("contentSha256").isBlank()) {
                book.put("contentSha256", sha256(pdfFile))
                changed = true
            }
        }
        if (changed) writeDurably(catalogFile, catalog.toString().toByteArray(Charsets.UTF_8))
    }

    fun writeManifest(
        archiveRoot: File,
        createdAtEpochMillis: Long,
        sourcePackageName: String,
        sourceVersionName: String,
        sourceVersionCode: Long,
        sourceGeneration: Long,
    ): ArchiveManifest {
        val canonicalRoot = archiveRoot.canonicalFile
        val files = canonicalRoot.walkTopDown()
            .filter { file ->
                file.isFile && file.relativeTo(canonicalRoot).invariantSeparatorsPath != MANIFEST_PATH
            }
            .map { file ->
                val relative = file.relativeTo(canonicalRoot).invariantSeparatorsPath
                requireValidContentPath(relative)
                ArchiveDigest(relative, file.length(), sha256(file))
            }
            .sortedBy(ArchiveDigest::path)
            .toList()
        val manifest = ArchiveManifest(
            createdAtEpochMillis = createdAtEpochMillis,
            sourcePackageName = sourcePackageName,
            sourceVersionName = sourceVersionName,
            sourceVersionCode = sourceVersionCode,
            sourceGeneration = sourceGeneration,
            files = files,
        )
        writeDurably(File(canonicalRoot, MANIFEST_PATH), manifest.toJson().toString().toByteArray(Charsets.UTF_8))
        return manifest
    }

    fun writeZip(archiveRoot: File, output: OutputStream) {
        val canonicalRoot = archiveRoot.canonicalFile
        val files = canonicalRoot.walkTopDown()
            .filter(File::isFile)
            .sortedBy { it.relativeTo(canonicalRoot).invariantSeparatorsPath }
            .toList()
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.setLevel(Deflater.BEST_SPEED)
            files.forEach { file ->
                val path = file.relativeTo(canonicalRoot).invariantSeparatorsPath
                requireValidEntryPath(path)
                zip.putNextEntry(ZipEntry(path).apply { time = file.lastModified() })
                FileInputStream(file).use { input -> input.copyTo(zip, COPY_BUFFER_BYTES) }
                zip.closeEntry()
            }
            zip.finish()
        }
    }

    fun validate(
        input: InputStream,
        extractionRoot: File? = null,
        maximumTotalBytes: Long = MAX_TOTAL_BYTES,
    ): ValidatedArchive {
        require(maximumTotalBytes >= 0L) { "maximumTotalBytes must not be negative" }
        val effectiveMaximumTotalBytes = minOf(maximumTotalBytes, MAX_TOTAL_BYTES)
        val canonicalExtractionRoot = extractionRoot?.canonicalFile?.apply {
            check(mkdirs() || isDirectory) { "복원 임시 폴더를 만들 수 없습니다." }
        }
        val seenPaths = linkedSetOf<String>()
        val calculatedFiles = linkedMapOf<String, ArchiveDigest>()
        var manifestBytes: ByteArray? = null
        var identityBytes: ByteArray? = null
        var catalogBytes: ByteArray? = null
        var totalBytes = 0L
        var entryCount = 0

        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > MAX_ENTRY_COUNT) invalid("백업 파일 항목이 너무 많습니다.")
                    if (entry.isDirectory) invalid("디렉터리 ZIP 항목은 허용되지 않습니다: ${entry.name}")
                    val path = requireValidEntryPath(entry.name)
                    if (!seenPaths.add(path.lowercase())) invalid("중복된 백업 경로입니다: $path")

                    val captureLimit = when (path) {
                        MANIFEST_PATH -> MAX_MANIFEST_BYTES
                        IDENTITY_PATH -> MAX_IDENTITY_BYTES
                        CATALOG_PATH -> MAX_CATALOG_BYTES
                        else -> 0L
                    }
                    val capture = if (captureLimit > 0L) ByteArrayOutputStream() else null
                    val destination = canonicalExtractionRoot?.let { safeDestination(it, path) }
                    destination?.parentFile?.let { parent ->
                        if (!parent.mkdirs() && !parent.isDirectory) invalid("복원 폴더를 만들 수 없습니다: $path")
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var entryBytes = 0L
                    val sink = destination?.let(::FileOutputStream)
                    try {
                        sink.use { output ->
                            val buffer = ByteArray(COPY_BUFFER_BYTES)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                entryBytes += count
                                totalBytes += count
                                if (entryBytes > MAX_ENTRY_BYTES) invalid("백업 항목이 너무 큽니다: $path")
                                if (totalBytes > effectiveMaximumTotalBytes) invalid("백업 전체 용량이 너무 큽니다.")
                                if (capture != null) {
                                    if (entryBytes > captureLimit) invalid("백업 메타데이터가 너무 큽니다: $path")
                                    capture.write(buffer, 0, count)
                                }
                                output?.write(buffer, 0, count)
                                digest.update(buffer, 0, count)
                            }
                            output?.fd?.sync()
                        }
                    } finally {
                        zip.closeEntry()
                    }
                    if (destination != null && entry.time >= 0L) {
                        destination.setLastModified(entry.time)
                    }
                    if (entry.size >= 0L && entry.size != entryBytes) invalid("ZIP 크기 정보가 일치하지 않습니다: $path")
                    when (path) {
                        MANIFEST_PATH -> manifestBytes = capture!!.toByteArray()
                        IDENTITY_PATH -> identityBytes = capture!!.toByteArray()
                        CATALOG_PATH -> catalogBytes = capture!!.toByteArray()
                        else -> Unit
                    }
                    if (path != MANIFEST_PATH) {
                        requireValidContentPath(path)
                        calculatedFiles[path] = ArchiveDigest(path, entryBytes, digest.digest().toHex())
                    }
                }
            }
        } catch (error: BackupValidationException) {
            throw error
        } catch (error: Throwable) {
            throw BackupValidationException("백업 ZIP을 읽을 수 없습니다.", error)
        }

        val manifest = manifestBytes?.let(::parseManifest) ?: invalid("백업 manifest.json이 없습니다.")
        val identity = identityBytes?.let(::parseIdentity) ?: invalid("백업 기기 정보가 없습니다.")
        validateDigests(manifest, calculatedFiles)
        validateCatalog(catalogBytes ?: invalid("백업 책장 데이터가 없습니다."), calculatedFiles)
        return ValidatedArchive(manifest, identity, totalBytes)
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun validateDigests(
        manifest: ArchiveManifest,
        calculatedFiles: Map<String, ArchiveDigest>,
    ) {
        val declared = manifest.files.associateBy(ArchiveDigest::path)
        if (declared.size != manifest.files.size) invalid("manifest에 중복 경로가 있습니다.")
        if (declared.keys != calculatedFiles.keys) invalid("manifest와 ZIP 파일 목록이 일치하지 않습니다.")
        declared.forEach { (path, expected) ->
            val actual = calculatedFiles.getValue(path)
            if (expected.size != actual.size || !expected.sha256.equals(actual.sha256, ignoreCase = true)) {
                invalid("백업 파일 해시가 일치하지 않습니다: $path")
            }
        }
    }

    private fun validateCatalog(bytes: ByteArray, archiveFiles: Map<String, ArchiveDigest>) {
        try {
            val catalog = JSONObject(bytes.toString(Charsets.UTF_8))
            if (catalog.getInt("formatVersion") != 2) invalid("지원하지 않는 책장 형식입니다.")
            catalog.getString("selectedStudentId")
            catalog.getJSONArray("students")
            val books = catalog.getJSONArray("books")
            for (index in 0 until books.length()) {
                val book = books.getJSONObject(index)
                val id = requireValidBookId(book.getString("id"))
                val pdfPath = requireValidCatalogRelativePath(book.getString("pdfPath"))
                if (pdfPath != "$id/document.pdf") invalid("교재 PDF 경로가 교재 ID와 일치하지 않습니다: $pdfPath")
                val pdfArchivePath = "data/books/$pdfPath"
                val pdfDigest = archiveFiles[pdfArchivePath]
                    ?: invalid("교재 PDF가 백업에 없습니다: $pdfPath")
                val expectedPdfHash = book.getString("contentSha256").lowercase()
                if (!SHA_256.matches(expectedPdfHash) || expectedPdfHash != pdfDigest.sha256.lowercase()) {
                    invalid("교재 PDF 해시가 책장 정보와 일치하지 않습니다: $pdfPath")
                }
                if (!book.isNull("answerPath")) {
                    val answerPath = requireValidCatalogRelativePath(book.getString("answerPath"))
                    if (answerPath != "$id/answers.json") invalid("정답 경로가 교재 ID와 일치하지 않습니다: $answerPath")
                    if (archiveFiles["data/books/$answerPath"] == null) {
                        invalid("정답 파일이 백업에 없습니다: $answerPath")
                    }
                }
                if (!book.isNull("answerPdfPath")) {
                    val answerPdfPath = requireValidAnswerPdfPath(
                        id,
                        requireValidCatalogRelativePath(book.getString("answerPdfPath")),
                    )
                    if (archiveFiles["data/books/$answerPdfPath"] == null) {
                        invalid("답안 PDF가 백업에 없습니다: $answerPdfPath")
                    }
                }
            }
            catalog.getJSONArray("attempts")
            catalog.getJSONArray("markGroups")
        } catch (error: BackupValidationException) {
            throw error
        } catch (error: Throwable) {
            throw BackupValidationException("책장 데이터가 올바르지 않습니다.", error)
        }
    }

    private fun parseIdentity(bytes: ByteArray): ArchiveIdentity {
        try {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            val deviceId = json.getString("deviceId")
            if (deviceId.isBlank() || deviceId.length > 256) invalid("백업 deviceId가 올바르지 않습니다.")
            val fingerprint = if (json.isNull("androidIdSha256")) null else json.getString("androidIdSha256")
            if (fingerprint != null && !SHA_256.matches(fingerprint)) invalid("백업 기기 지문이 올바르지 않습니다.")
            return ArchiveIdentity(deviceId, fingerprint)
        } catch (error: BackupValidationException) {
            throw error
        } catch (error: Throwable) {
            throw BackupValidationException("백업 기기 정보를 읽을 수 없습니다.", error)
        }
    }

    private fun parseManifest(bytes: ByteArray): ArchiveManifest {
        try {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            if (json.getString("format") != BACKUP_FORMAT || json.getInt("formatVersion") != BACKUP_FORMAT_VERSION) {
                invalid("지원하지 않는 MasterNote 백업 형식입니다.")
            }
            val fileArray = json.getJSONArray("files")
            if (fileArray.length() > MAX_ENTRY_COUNT - 1) invalid("manifest 파일 목록이 너무 큽니다.")
            val files = buildList {
                for (index in 0 until fileArray.length()) {
                    val file = fileArray.getJSONObject(index)
                    val path = requireValidContentPath(file.getString("path"))
                    val size = file.getLong("size")
                    val hash = file.getString("sha256").lowercase()
                    if (size < 0L || size > MAX_ENTRY_BYTES || !SHA_256.matches(hash)) {
                        invalid("manifest 파일 정보가 올바르지 않습니다: $path")
                    }
                    add(ArchiveDigest(path, size, hash))
                }
            }
            return ArchiveManifest(
                createdAtEpochMillis = json.getLong("createdAtEpochMillis").also {
                    if (it < 0L) invalid("manifest 생성 시각이 올바르지 않습니다.")
                },
                sourcePackageName = json.getString("sourcePackageName"),
                sourceVersionName = json.getString("sourceVersionName"),
                sourceVersionCode = json.getLong("sourceVersionCode"),
                sourceGeneration = json.optLong("sourceGeneration", 0L).also {
                    if (it < 0L) invalid("manifest 데이터 세대가 올바르지 않습니다.")
                },
                files = files,
            )
        } catch (error: BackupValidationException) {
            throw error
        } catch (error: Throwable) {
            throw BackupValidationException("백업 manifest를 읽을 수 없습니다.", error)
        }
    }

    private fun ArchiveManifest.toJson() = JSONObject()
        .put("format", BACKUP_FORMAT)
        .put("formatVersion", BACKUP_FORMAT_VERSION)
        .put("createdAtEpochMillis", createdAtEpochMillis)
        .put("sourcePackageName", sourcePackageName)
        .put("sourceVersionName", sourceVersionName)
        .put("sourceVersionCode", sourceVersionCode)
        .put("sourceGeneration", sourceGeneration)
        .put("files", JSONArray().apply {
            files.forEach { file ->
                put(JSONObject().put("path", file.path).put("size", file.size).put("sha256", file.sha256))
            }
        })

    private fun requireValidEntryPath(rawPath: String): String {
        if (rawPath.isBlank() || rawPath.length > 1024 || rawPath.endsWith('/')) invalid("잘못된 ZIP 경로입니다.")
        if ('\\' in rawPath || '\u0000' in rawPath || ':' in rawPath || rawPath.startsWith('/')) {
            invalid("안전하지 않은 ZIP 경로입니다: $rawPath")
        }
        val segments = rawPath.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) invalid("안전하지 않은 ZIP 경로입니다: $rawPath")
        if (rawPath != MANIFEST_PATH) requireValidContentPath(rawPath)
        return rawPath
    }

    private fun requireValidContentPath(path: String): String {
        if (path == IDENTITY_PATH) return path
        if (!path.startsWith("data/") || path.length <= "data/".length) invalid("허용되지 않은 백업 경로입니다: $path")
        return path
    }

    private fun requireValidCatalogRelativePath(path: String): String {
        if (path.isBlank() || path.length > 1024 || path.startsWith('/') || '\\' in path || ':' in path || '\u0000' in path) {
            invalid("안전하지 않은 책장 파일 경로입니다: $path")
        }
        if (path.split('/').any { it.isBlank() || it == "." || it == ".." }) {
            invalid("안전하지 않은 책장 파일 경로입니다: $path")
        }
        return path
    }

    private fun requireValidBookId(id: String): String {
        if (id.isBlank() || id.length > 256 || !Regex("[A-Za-z0-9._-]+").matches(id)) {
            invalid("안전하지 않은 교재 ID입니다: $id")
        }
        return id
    }

    private fun requireValidAnswerPdfPath(bookId: String, path: String): String {
        val prefix = "$bookId/answer-"
        if (!path.startsWith(prefix) || !path.endsWith(".pdf") || path.length <= prefix.length + 4) {
            invalid("답안 PDF 경로가 교재 ID와 일치하지 않습니다: $path")
        }
        if ('/' in path.removePrefix("$bookId/")) {
            invalid("답안 PDF 경로가 교재 폴더를 벗어납니다: $path")
        }
        return path
    }

    private fun safeDestination(root: File, path: String): File {
        val destination = File(root, path).canonicalFile
        val prefix = root.path + File.separator
        if (!destination.path.startsWith(prefix)) invalid("ZIP 경로가 복원 폴더를 벗어납니다: $path")
        return destination
    }

    private fun writeDurably(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    private fun invalid(message: String): Nothing = throw BackupValidationException(message)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
