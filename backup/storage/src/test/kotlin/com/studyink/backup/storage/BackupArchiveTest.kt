package com.studyink.backup.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupArchiveTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun roundTrip_validatesAndExtractsAllProtectedFiles() {
        val archiveRoot = validArchiveRoot()
        val document = File(archiveRoot, "data/books/book-1/document.pdf").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        }
        File(archiveRoot, CATALOG_PATH).writeText(
            catalogWithBook("book-1/document.pdf", BackupArchive.sha256(document.readBytes())),
        )
        BackupArchive.writeManifest(
            archiveRoot = archiveRoot,
            createdAtEpochMillis = 1234L,
            sourcePackageName = "com.studyink.app.debug",
            sourceVersionName = "test",
            sourceVersionCode = 7L,
            sourceGeneration = 42L,
        )
        val zipBytes = ByteArrayOutputStream().also { BackupArchive.writeZip(archiveRoot, it) }.toByteArray()
        val extraction = temporaryFolder.newFolder("extracted")

        val validated = BackupArchive.validate(ByteArrayInputStream(zipBytes), extraction)

        assertEquals(1234L, validated.manifest.createdAtEpochMillis)
        assertEquals(42L, validated.manifest.sourceGeneration)
        assertEquals("device-1", validated.identity.deviceId)
        assertEquals(3, validated.manifest.files.size)
        assertArrayEquals(document.readBytes(), File(extraction, "data/books/book-1/document.pdf").readBytes())
    }

    @Test
    fun changedFile_afterManifestCreation_isRejectedByHash() {
        val archiveRoot = validArchiveRoot()
        BackupArchive.writeManifest(
            archiveRoot, 1L, "package", "version", 1L, 0L,
        )
        File(archiveRoot, CATALOG_PATH).appendText("tampered")
        val zipBytes = ByteArrayOutputStream().also { BackupArchive.writeZip(archiveRoot, it) }.toByteArray()

        val error = runCatching { BackupArchive.validate(ByteArrayInputStream(zipBytes)) }.exceptionOrNull()

        assertTrue(error is BackupValidationException)
        assertTrue(error!!.message!!.contains("해시"))
    }

    @Test
    fun zipSlipPath_isRejectedWithoutWritingOutsideExtractionRoot() {
        val bytes = rawZip("../outside.txt" to "owned".toByteArray())
        val extraction = temporaryFolder.newFolder("safe")
        val outside = File(extraction.parentFile, "outside.txt")

        val error = runCatching {
            BackupArchive.validate(ByteArrayInputStream(bytes), extraction)
        }.exceptionOrNull()

        assertTrue(error is BackupValidationException)
        assertTrue(!outside.exists())
    }

    @Test
    fun duplicateCaseInsensitivePath_isRejected() {
        val bytes = rawZip(
            "data/catalog-v2.json" to byteArrayOf(1),
            "data/CATALOG-V2.JSON" to byteArrayOf(2),
        )

        val error = runCatching { BackupArchive.validate(ByteArrayInputStream(bytes)) }.exceptionOrNull()

        assertTrue(error is BackupValidationException)
        assertTrue(error!!.message!!.contains("중복"))
    }

    @Test
    fun sha256_hasCanonicalLowercaseEncoding() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            BackupArchive.sha256("abc".toByteArray()),
        )
    }

    @Test
    fun catalogPdfReference_mustExistInProtectedFileList() {
        val archiveRoot = validArchiveRoot()
        File(archiveRoot, CATALOG_PATH).writeText(catalogWithBook("book-1/document.pdf", "0".repeat(64)))
        BackupArchive.writeManifest(archiveRoot, 1L, "package", "version", 1L, 0L)
        val zipBytes = ByteArrayOutputStream().also { BackupArchive.writeZip(archiveRoot, it) }.toByteArray()

        val error = runCatching { BackupArchive.validate(ByteArrayInputStream(zipBytes)) }.exceptionOrNull()

        assertTrue(error is BackupValidationException)
        assertTrue(error!!.message!!.contains("PDF가 백업에 없습니다"))
    }

    @Test
    fun catalogPdfHash_mustMatchManifestProtectedBytes() {
        val archiveRoot = validArchiveRoot()
        val relativePath = "book-1/document.pdf"
        File(archiveRoot, "data/books/$relativePath").apply {
            parentFile!!.mkdirs()
            writeBytes("real-pdf".toByteArray())
        }
        File(archiveRoot, CATALOG_PATH).writeText(catalogWithBook(relativePath, "0".repeat(64)))
        BackupArchive.writeManifest(archiveRoot, 1L, "package", "version", 1L, 0L)
        val zipBytes = ByteArrayOutputStream().also { BackupArchive.writeZip(archiveRoot, it) }.toByteArray()

        val error = runCatching { BackupArchive.validate(ByteArrayInputStream(zipBytes)) }.exceptionOrNull()

        assertTrue(error is BackupValidationException)
        assertTrue(error!!.message!!.contains("PDF 해시"))
    }

    @Test
    fun catalogAnswerReference_mustExistInProtectedFileList() {
        val archiveRoot = validArchiveRoot()
        val relativePath = "book-1/document.pdf"
        val pdfBytes = "real-pdf".toByteArray()
        File(archiveRoot, "data/books/$relativePath").apply {
            parentFile!!.mkdirs()
            writeBytes(pdfBytes)
        }
        File(archiveRoot, CATALOG_PATH).writeText(
            catalogWithBook(
                pdfPath = relativePath,
                pdfHash = BackupArchive.sha256(pdfBytes),
                answerPath = "book-1/answers.json",
            ),
        )
        BackupArchive.writeManifest(archiveRoot, 1L, "package", "version", 1L, 0L)
        val zipBytes = ByteArrayOutputStream().also { BackupArchive.writeZip(archiveRoot, it) }.toByteArray()

        val error = runCatching { BackupArchive.validate(ByteArrayInputStream(zipBytes)) }.exceptionOrNull()

        assertTrue(error is BackupValidationException)
        assertTrue(error!!.message!!.contains("정답 파일"))
    }

    @Test
    fun roundTrip_includesAttachedAnswerPdf() {
        val archiveRoot = validArchiveRoot()
        val documentBytes = "problem-pdf".toByteArray()
        val answerBytes = "answer-pdf".toByteArray()
        File(archiveRoot, "data/books/book-1/document.pdf").apply {
            parentFile!!.mkdirs()
            writeBytes(documentBytes)
        }
        File(archiveRoot, "data/books/book-1/answer-test.pdf").writeBytes(answerBytes)
        File(archiveRoot, CATALOG_PATH).writeText(
            catalogWithBook(
                pdfPath = "book-1/document.pdf",
                pdfHash = BackupArchive.sha256(documentBytes),
                answerPdfPath = "book-1/answer-test.pdf",
            ),
        )
        BackupArchive.writeManifest(archiveRoot, 1L, "package", "version", 1L, 0L)
        val zipBytes = ByteArrayOutputStream().also { BackupArchive.writeZip(archiveRoot, it) }.toByteArray()
        val extraction = temporaryFolder.newFolder("answer-extracted")

        BackupArchive.validate(ByteArrayInputStream(zipBytes), extraction)

        assertArrayEquals(
            answerBytes,
            File(extraction, "data/books/book-1/answer-test.pdf").readBytes(),
        )
    }

    @Test
    fun catalogAnswerPdfReference_mustExistInProtectedFileList() {
        val archiveRoot = validArchiveRoot()
        val pdfBytes = "problem-pdf".toByteArray()
        File(archiveRoot, "data/books/book-1/document.pdf").apply {
            parentFile!!.mkdirs()
            writeBytes(pdfBytes)
        }
        File(archiveRoot, CATALOG_PATH).writeText(
            catalogWithBook(
                pdfPath = "book-1/document.pdf",
                pdfHash = BackupArchive.sha256(pdfBytes),
                answerPdfPath = "book-1/answer-missing.pdf",
            ),
        )
        BackupArchive.writeManifest(archiveRoot, 1L, "package", "version", 1L, 0L)
        val zipBytes = ByteArrayOutputStream().also { BackupArchive.writeZip(archiveRoot, it) }.toByteArray()

        val error = runCatching { BackupArchive.validate(ByteArrayInputStream(zipBytes)) }.exceptionOrNull()

        assertTrue(error is BackupValidationException)
        assertTrue(error!!.message!!.contains("답안 PDF"))
    }

    @Test
    fun legacyCatalogWithoutPdfHash_isUpgradedInSnapshotBeforeManifest() {
        val archiveRoot = validArchiveRoot()
        val relativePath = "book-1/document.pdf"
        val pdfBytes = "legacy-pdf".toByteArray()
        File(archiveRoot, "data/books/$relativePath").apply {
            parentFile!!.mkdirs()
            writeBytes(pdfBytes)
        }
        File(archiveRoot, CATALOG_PATH).writeText(catalogWithBook(relativePath, "", omitHash = true))

        BackupArchive.populateMissingCatalogHashes(File(archiveRoot, "data"))
        BackupArchive.writeManifest(archiveRoot, 1L, "package", "version", 1L, 0L)
        val zipBytes = ByteArrayOutputStream().also { BackupArchive.writeZip(archiveRoot, it) }.toByteArray()

        val validated = BackupArchive.validate(ByteArrayInputStream(zipBytes))

        assertEquals(3, validated.manifest.files.size)
        assertTrue(File(archiveRoot, CATALOG_PATH).readText().contains(BackupArchive.sha256(pdfBytes)))
    }

    private fun validArchiveRoot(): File {
        val root = temporaryFolder.newFolder("archive-${System.nanoTime()}")
        File(root, CATALOG_PATH).apply {
            parentFile!!.mkdirs()
            writeText(
                """{"formatVersion":2,"selectedStudentId":"student-1","students":[],"books":[],"attempts":[],"markGroups":[]}""",
            )
        }
        BackupArchive.writeIdentity(
            File(root, IDENTITY_PATH),
            ArchiveIdentity("device-1", "0".repeat(64)),
        )
        return root
    }

    private fun rawZip(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (path, bytes) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun catalogWithBook(
        pdfPath: String,
        pdfHash: String,
        answerPath: String? = null,
        answerPdfPath: String? = null,
        omitHash: Boolean = false,
    ): String {
        val answerValue = answerPath?.let { "\"$it\"" } ?: "null"
        val answerPdfValue = answerPdfPath?.let { "\"$it\"" } ?: "null"
        val hashField = if (omitHash) "" else "\"contentSha256\":\"$pdfHash\","
        return """{"formatVersion":2,"selectedStudentId":"student-1","students":[],"books":[{"id":"book-1","studentId":"student-1","title":"Book","pageCount":1,"pdfPath":"$pdfPath",$hashField"answerPath":$answerValue,"answerPdfPath":$answerPdfValue,"answerPdfPageCount":${if (answerPdfPath == null) 0 else 1},"answerPageMappings":[],"lastViewedAnswerPage":0,"createdAt":1,"hiddenAt":null}],"attempts":[],"markGroups":[]}"""
    }
}
