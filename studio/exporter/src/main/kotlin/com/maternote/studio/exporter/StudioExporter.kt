package com.maternote.studio.exporter

import com.maternote.packageformat.codec.PackageJson
import com.maternote.packageformat.model.ActivityDefinition
import com.maternote.packageformat.model.AssetDefinition
import com.maternote.packageformat.model.BookDefinition
import com.maternote.packageformat.model.CreatedBy
import com.maternote.packageformat.model.DocumentDefinition
import com.maternote.packageformat.model.PackageManifest
import com.maternote.packageformat.model.PageDefinition
import com.maternote.packageformat.model.PageSource
import com.maternote.packageformat.validator.PackageValidationReport
import com.maternote.packageformat.validator.PackageValidator
import com.maternote.studio.project.StudioProject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ExportAssetSnapshot(
    val assetId: String,
    val extension: String,
    val mimeType: String,
    val bytes: ByteArray,
) {
    fun detached() = copy(bytes = bytes.copyOf())
}

data class ExportSnapshot(
    val project: StudioProject,
    val revisionNumber: Int,
    val previousRevisionId: String?,
    val createdAt: String,
    val assets: List<ExportAssetSnapshot>,
) {
    companion object {
        fun capture(
            project: StudioProject,
            assets: List<ExportAssetSnapshot>,
            revisionNumber: Int = 1,
            previousRevisionId: String? = null,
        ) = ExportSnapshot(
            project = project.copy(
                pages = project.pages.toList(),
                activities = project.activities.map { it.copy(pageIds = it.pageIds.toList()) },
            ),
            revisionNumber = revisionNumber,
            previousRevisionId = previousRevisionId,
            createdAt = Instant.ofEpochMilli(project.updatedAtEpochMillis).toString(),
            assets = assets.map(ExportAssetSnapshot::detached),
        )
    }
}

data class ExportResult(val file: File, val report: PackageValidationReport, val manifest: PackageManifest)

object StudioExporter {
    private const val DOCUMENT_ASSET_ID = "main-document"

    @JvmStatic
    fun exportFixture(project: StudioProject, output: File): ExportResult = export(
        ExportSnapshot.capture(
            project,
            listOf(ExportAssetSnapshot(DOCUMENT_ASSET_ID, "pdf", "application/pdf", blankPdf(project.pages.size))),
        ),
        output,
    )

    fun export(snapshot: ExportSnapshot, output: File): ExportResult {
        val manifest = manifest(snapshot)
        val preflight = PackageValidator().validateManifest(manifest, strictStudio = true)
        check(preflight.isValid) { preflight.errors.joinToString { "${it.code}:${it.path}" } }
        output.parentFile?.mkdirs()
        val temporary = File(output.parentFile, "${output.name}.tmp")
        ZipOutputStream(FileOutputStream(temporary).buffered()).use { zip ->
            val entries = buildMap<String, ByteArray> {
                put("manifest.json", PackageJson.encode(manifest).encodeToByteArray())
                snapshot.assets.forEach { asset -> put(assetPath(asset), asset.bytes) }
            }
            entries.toSortedMap().forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name).apply { time = FIXED_ZIP_TIME })
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        val (decoded, report) = PackageValidator().validatePackage(temporary, strictStudio = true)
        check(report.isValid && decoded == manifest) { report.errors.joinToString { "${it.code}:${it.path}" } }
        if (output.exists()) check(output.delete()) { "Cannot replace ${output.name}" }
        check(temporary.renameTo(output)) { "Cannot commit ${output.name}" }
        return ExportResult(output, report, manifest)
    }

    private fun manifest(snapshot: ExportSnapshot): PackageManifest {
        val project = snapshot.project
        val assets = snapshot.assets.map { asset ->
            val hash = sha256(asset.bytes)
            AssetDefinition(asset.assetId, "assets/$hash.${asset.extension}", asset.mimeType, hash, asset.bytes.size.toLong())
        }
        require(assets.any { it.assetId == DOCUMENT_ASSET_ID }) { "main-document asset required" }
        return PackageManifest(
            packageId = UUID.nameUUIDFromBytes("${project.bookId}:${project.currentRevisionDraftId}".encodeToByteArray()).toString(),
            createdAt = snapshot.createdAt,
            createdBy = CreatedBy("Maternote Studio", "0.1.0"),
            requiredCapabilities = listOf("document.pdf", "activity.basic"),
            book = BookDefinition(
                project.bookId,
                project.currentRevisionDraftId,
                snapshot.previousRevisionId,
                snapshot.revisionNumber,
                project.title,
            ),
            assets = assets,
            document = DocumentDefinition("pdf", DOCUMENT_ASSET_ID),
            pages = project.pages.sortedBy { it.position }.mapIndexed { index, page ->
                PageDefinition(page.stablePageId, PageSource("pdfPage", index), page.width, page.height)
            },
            activities = project.activities.sortedBy { it.position }.map { activity ->
                ActivityDefinition(activity.activityId, activity.title, activity.position, pageIds = activity.pageIds)
            },
        )
    }

    private fun assetPath(asset: ExportAssetSnapshot): String =
        "assets/${sha256(asset.bytes)}.${asset.extension}"

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun blankPdf(pageCount: Int): ByteArray {
        require(pageCount > 0)
        val objects = mutableListOf<String>()
        val pageObjectNumbers = (0 until pageCount).map { 3 + it * 2 }
        objects += "<< /Type /Catalog /Pages 2 0 R >>"
        objects += "<< /Type /Pages /Kids [${pageObjectNumbers.joinToString(" ") { "$it 0 R" }}] /Count $pageCount >>"
        repeat(pageCount) { index ->
            val pageNumber = 3 + index * 2
            objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 1000 1414] /Contents ${pageNumber + 1} 0 R >>"
            objects += "<< /Length 0 >>\nstream\n\nendstream"
        }
        val builder = StringBuilder("%PDF-1.4\n")
        val offsets = mutableListOf(0)
        objects.forEachIndexed { index, value ->
            offsets += builder.length
            builder.append(index + 1).append(" 0 obj\n").append(value).append("\nendobj\n")
        }
        val xref = builder.length
        builder.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.drop(1).forEach { builder.append(it.toString().padStart(10, '0')).append(" 00000 n \n") }
        builder.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return builder.toString().encodeToByteArray()
    }

    private const val FIXED_ZIP_TIME = 315_532_800_000L
}
