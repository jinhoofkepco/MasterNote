package com.maternote.packageformat.validator

import com.maternote.packageformat.codec.PackageJson
import com.maternote.packageformat.model.*
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

enum class ValidationCode {
    PKG_INVALID_MANIFEST, PKG_UNSUPPORTED_MAJOR, PKG_UNSUPPORTED_CAPABILITY, PKG_DUPLICATE_ID,
    PKG_MISSING_ASSET, PKG_ASSET_HASH_MISMATCH, PKG_PAGE_INDEX_OUT_OF_RANGE, PKG_INVALID_REFERENCE,
    PKG_ZIP_PATH_TRAVERSAL, PKG_ZIP_LIMIT_EXCEEDED, PKG_DUPLICATE_ENTRY, PKG_NESTED_ARCHIVE,
}
data class ValidationIssue(val code: ValidationCode, val path: String, val detail: String)
data class PackageValidationReport(
    val errors: List<ValidationIssue>, val warnings: List<ValidationIssue>, val detectedCapabilities: Set<String>,
    val assetCount: Int, val pageCount: Int, val activityCount: Int,
) { val isValid get() = errors.isEmpty() }
data class ZipLimits(
    val maxEntries: Int = 10_000, val maxManifestBytes: Long = 5L * 1024 * 1024,
    val maxSingleAssetBytes: Long = 1L * 1024 * 1024 * 1024,
    val maxExpandedBytes: Long = 2L * 1024 * 1024 * 1024,
    val maxCompressionRatio: Double = 200.0,
)

class PackageValidator(
    private val supportedMajor: Int = 1,
    private val capabilities: Set<String> = setOf("document.pdf", "document.imageSequence", "activity.basic", "answer.document", "teaching.resource"),
) {
    fun validateManifest(m: PackageManifest, strictStudio: Boolean = false): PackageValidationReport {
        val errors = mutableListOf<ValidationIssue>(); val warnings = mutableListOf<ValidationIssue>()
        fun error(code: ValidationCode, path: String, detail: String) { errors += ValidationIssue(code, path, detail) }
        if (m.format != "maternote.book" || m.formatVersion.major != supportedMajor) error(ValidationCode.PKG_UNSUPPORTED_MAJOR, "formatVersion", "supported major is $supportedMajor")
        (m.requiredCapabilities - capabilities).forEach { error(ValidationCode.PKG_UNSUPPORTED_CAPABILITY, "requiredCapabilities", it) }
        (m.optionalCapabilities - capabilities).forEach { warnings += ValidationIssue(ValidationCode.PKG_UNSUPPORTED_CAPABILITY, "optionalCapabilities", it) }
        fun duplicates(values: List<String>, path: String) = values.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { error(ValidationCode.PKG_DUPLICATE_ID, path, it) }
        duplicates(m.assets.map { it.assetId }, "assets"); duplicates(m.pages.map { it.pageId }, "pages"); duplicates(m.activities.map { it.activityId }, "activities")
        val assets = m.assets.associateBy { it.assetId }; val pages = m.pages.associateBy { it.pageId }
        if (m.document.assetId !in assets) error(ValidationCode.PKG_MISSING_ASSET, "document.assetId", m.document.assetId)
        m.book.coverAssetId?.let { id -> if (assets[id]?.mimeType?.startsWith("image/") != true) error(ValidationCode.PKG_INVALID_REFERENCE, "book.coverAssetId", id) }
        m.assets.forEach { a ->
            if (!a.sha256.matches(Regex("[0-9a-f]{64}")) || !a.path.matches(Regex("assets/[0-9a-f]{64}\\.[A-Za-z0-9]+"))) error(ValidationCode.PKG_INVALID_REFERENCE, "assets.${a.assetId}", "hash-addressed path required")
            if (a.byteSize < 0) error(ValidationCode.PKG_INVALID_REFERENCE, "assets.${a.assetId}.byteSize", "negative")
        }
        m.pages.forEach { p ->
            if (p.canonicalWidth <= 0 || p.canonicalHeight <= 0) error(ValidationCode.PKG_INVALID_REFERENCE, "pages.${p.pageId}", "invalid canonical size")
            p.source.assetId?.let { if (it !in assets) error(ValidationCode.PKG_MISSING_ASSET, "pages.${p.pageId}.source", it) }
            if (p.source.type == "pdfPage" && (p.source.pageIndex ?: -1) < 0) error(ValidationCode.PKG_PAGE_INDEX_OUT_OF_RANGE, "pages.${p.pageId}", "negative page index")
        }
        m.activities.forEach { a ->
            if (a.pageIds.isEmpty()) error(ValidationCode.PKG_INVALID_REFERENCE, "activities.${a.activityId}", "empty activity")
            a.pageIds.filterNot { it in pages }.forEach { error(ValidationCode.PKG_INVALID_REFERENCE, "activities.${a.activityId}.pageIds", it) }
        }
        val answerDocs = m.answerDocuments.associateBy { it.answerDocumentId }
        m.answerDocuments.forEach { if (it.assetId !in assets) error(ValidationCode.PKG_MISSING_ASSET, "answerDocuments.${it.answerDocumentId}", it.assetId) }
        m.answerLinks.forEach { if (it.answerDocumentId !in answerDocs || (it.problemPageId != null && it.problemPageId !in pages)) error(ValidationCode.PKG_INVALID_REFERENCE, "answerLinks.${it.linkId}", "missing target") }
        val resources = m.teachingResources.associateBy { it.resourceId }
        m.teachingResources.forEach { it.imageAssetId?.let { id -> if (id !in assets) error(ValidationCode.PKG_MISSING_ASSET, "resources.${it.resourceId}", id) } }
        m.pageResourceLinks.forEach { if (it.pageId !in pages || it.resourceId !in resources) error(ValidationCode.PKG_INVALID_REFERENCE, "pageResourceLinks.${it.linkId}", "missing target") }
        if (m.book.previousRevisionId == m.book.revisionId) error(ValidationCode.PKG_INVALID_REFERENCE, "book.previousRevisionId", "same as revisionId")
        if (strictStudio) m.assets.filter { asset -> asset.assetId != m.document.assetId && m.book.coverAssetId != asset.assetId && m.pages.none { it.source.assetId == asset.assetId } && m.answerDocuments.none { it.assetId == asset.assetId } && m.teachingResources.none { it.imageAssetId == asset.assetId } }.forEach { warnings += ValidationIssue(ValidationCode.PKG_INVALID_REFERENCE, "assets.${it.assetId}", "unused") }
        return PackageValidationReport(errors, warnings, (m.requiredCapabilities + m.optionalCapabilities).toSet(), m.assets.size, m.pages.size, m.activities.size)
    }

    fun validatePackage(file: File, limits: ZipLimits = ZipLimits(), strictStudio: Boolean = false): Pair<PackageManifest?, PackageValidationReport> {
        val inventoryIssues = mutableListOf<ValidationIssue>(); var manifestText: String? = null
        ZipFile(file).use { zip ->
            val entries = zip.entries().toList()
            if (entries.size > limits.maxEntries) inventoryIssues += ValidationIssue(ValidationCode.PKG_ZIP_LIMIT_EXCEEDED, "zip", "entry count")
            val names = mutableSetOf<String>(); var expanded = 0L
            entries.forEach { entry ->
                val name = entry.name.replace('\\', '/')
                if (!names.add(name)) inventoryIssues += ValidationIssue(ValidationCode.PKG_DUPLICATE_ENTRY, name, "duplicate")
                if (name.startsWith('/') || name.split('/').any { it == ".." }) inventoryIssues += ValidationIssue(ValidationCode.PKG_ZIP_PATH_TRAVERSAL, name, "unsafe path")
                if (name != "manifest.json" && (name.endsWith(".zip", true) || name.endsWith(".mnote", true))) inventoryIssues += ValidationIssue(ValidationCode.PKG_NESTED_ARCHIVE, name, "nested archive")
                val size = entry.size.coerceAtLeast(0); expanded += size
                if (size > limits.maxSingleAssetBytes || expanded > limits.maxExpandedBytes) inventoryIssues += ValidationIssue(ValidationCode.PKG_ZIP_LIMIT_EXCEEDED, name, "expanded size")
                if (entry.compressedSize > 0 && size.toDouble() / entry.compressedSize > limits.maxCompressionRatio) inventoryIssues += ValidationIssue(ValidationCode.PKG_ZIP_LIMIT_EXCEEDED, name, "compression ratio")
                if (name == "manifest.json") {
                    if (size > limits.maxManifestBytes) inventoryIssues += ValidationIssue(ValidationCode.PKG_ZIP_LIMIT_EXCEEDED, name, "manifest size")
                    else manifestText = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                }
            }
            if (entries.count { it.name == "manifest.json" } != 1) inventoryIssues += ValidationIssue(ValidationCode.PKG_INVALID_MANIFEST, "manifest.json", "exactly one required")
            val manifest = runCatching { manifestText?.let(PackageJson::decode) }.getOrNull()
            if (manifest == null) return null to PackageValidationReport(inventoryIssues + ValidationIssue(ValidationCode.PKG_INVALID_MANIFEST, "manifest.json", "decode failed"), emptyList(), emptySet(), 0, 0, 0)
            val semantic = validateManifest(manifest, strictStudio)
            val hashErrors = manifest.assets.mapNotNull { asset ->
                val entry = zip.getEntry(asset.path) ?: return@mapNotNull ValidationIssue(ValidationCode.PKG_MISSING_ASSET, asset.path, "entry absent")
                val digest = MessageDigest.getInstance("SHA-256")
                zip.getInputStream(entry).use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != asset.sha256 || entry.size != asset.byteSize) ValidationIssue(ValidationCode.PKG_ASSET_HASH_MISMATCH, asset.path, actual) else null
            }
            return manifest to semantic.copy(errors = inventoryIssues + semantic.errors + hashErrors)
        }
    }
}
