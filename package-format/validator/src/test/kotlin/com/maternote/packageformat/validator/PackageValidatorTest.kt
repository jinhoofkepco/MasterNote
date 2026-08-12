package com.maternote.packageformat.validator

import com.maternote.packageformat.model.*
import com.maternote.packageformat.codec.PackageJson
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*

class PackageValidatorTest {
    private fun valid() = PackageManifest("maternote.book", FormatVersion(1, 0), "pkg", "now", CreatedBy("test", "1"), listOf("document.pdf", "activity.basic"), book = BookDefinition("b", "r", revisionNumber = 1, title = "Book"), assets = listOf(AssetDefinition("pdf", "assets/${"a".repeat(64)}.pdf", "application/pdf", "a".repeat(64), 1)), document = DocumentDefinition("pdf", "pdf"), pages = listOf(PageDefinition("p", PageSource("pdfPage", 0), 100, 100)), activities = listOf(ActivityDefinition("a", "A", 0, pageIds = listOf("p"))))
    @Test fun validManifestPasses() = assertTrue(PackageValidator().validateManifest(valid()).isValid)
    @Test fun unknownRequiredCapabilityFails() = assertEquals(ValidationCode.PKG_UNSUPPORTED_CAPABILITY, PackageValidator().validateManifest(valid().copy(requiredCapabilities = listOf("future.required"))).errors.single().code)
    @Test fun unknownOptionalCapabilityWarns() { val r = PackageValidator().validateManifest(valid().copy(optionalCapabilities = listOf("future.optional"))); assertTrue(r.isValid); assertEquals(1, r.warnings.size) }
    @Test fun danglingPageFails() = assertTrue(PackageValidator().validateManifest(valid().copy(activities = listOf(ActivityDefinition("a", "A", 0, pageIds = listOf("missing"))))).errors.any { it.code == ValidationCode.PKG_INVALID_REFERENCE })
    @Test fun duplicatePageFails() = assertTrue(PackageValidator().validateManifest(valid().copy(pages = valid().pages + valid().pages)).errors.any { it.code == ValidationCode.PKG_DUPLICATE_ID })
    @Test fun traversalEntryIsRejected() {
        val (_, report) = PackageValidator().validatePackage(packageFile("../database" to byteArrayOf(1)))
        assertTrue(report.errors.any { it.code == ValidationCode.PKG_ZIP_PATH_TRAVERSAL })
    }
    @Test fun nestedArchiveIsRejected() {
        val (_, report) = PackageValidator().validatePackage(packageFile("assets/child.zip" to byteArrayOf(1)))
        assertTrue(report.errors.any { it.code == ValidationCode.PKG_NESTED_ARCHIVE })
    }
    @Test fun configuredEntryLimitIsEnforcedWithoutLargeFixture() {
        val (_, report) = PackageValidator().validatePackage(packageFile(), ZipLimits(maxEntries = 1))
        assertTrue(report.errors.any { it.code == ValidationCode.PKG_ZIP_LIMIT_EXCEEDED })
    }
    @Test fun changedAssetIsRejectedByHash() {
        val (_, report) = PackageValidator().validatePackage(packageFile(assetBytes = "changed".encodeToByteArray()))
        assertTrue(report.errors.any { it.code == ValidationCode.PKG_ASSET_HASH_MISMATCH })
    }

    private fun packageFile(
        extra: Pair<String, ByteArray>? = null,
        assetBytes: ByteArray = "asset".encodeToByteArray(),
    ) = Files.createTempFile("package", ".mnote").toFile().also { file ->
        val expected = "asset".encodeToByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(expected).joinToString("") { "%02x".format(it) }
        val manifest = valid().copy(
            assets = listOf(AssetDefinition("pdf", "assets/$hash.pdf", "application/pdf", hash, expected.size.toLong())),
        )
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            listOf(
                "manifest.json" to PackageJson.encode(manifest).encodeToByteArray(),
                manifest.assets.single().path to assetBytes,
            ).plus(listOfNotNull(extra)).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}
