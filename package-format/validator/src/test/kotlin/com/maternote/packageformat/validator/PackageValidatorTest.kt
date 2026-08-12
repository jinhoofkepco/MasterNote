package com.maternote.packageformat.validator

import com.maternote.packageformat.model.*
import kotlin.test.*

class PackageValidatorTest {
    private fun valid() = PackageManifest("maternote.book", FormatVersion(1, 0), "pkg", "now", CreatedBy("test", "1"), listOf("document.pdf", "activity.basic"), book = BookDefinition("b", "r", revisionNumber = 1, title = "Book"), assets = listOf(AssetDefinition("pdf", "assets/${"a".repeat(64)}.pdf", "application/pdf", "a".repeat(64), 1)), document = DocumentDefinition("pdf", "pdf"), pages = listOf(PageDefinition("p", PageSource("pdfPage", 0), 100, 100)), activities = listOf(ActivityDefinition("a", "A", 0, pageIds = listOf("p"))))
    @Test fun validManifestPasses() = assertTrue(PackageValidator().validateManifest(valid()).isValid)
    @Test fun unknownRequiredCapabilityFails() = assertEquals(ValidationCode.PKG_UNSUPPORTED_CAPABILITY, PackageValidator().validateManifest(valid().copy(requiredCapabilities = listOf("future.required"))).errors.single().code)
    @Test fun unknownOptionalCapabilityWarns() { val r = PackageValidator().validateManifest(valid().copy(optionalCapabilities = listOf("future.optional"))); assertTrue(r.isValid); assertEquals(1, r.warnings.size) }
    @Test fun danglingPageFails() = assertTrue(PackageValidator().validateManifest(valid().copy(activities = listOf(ActivityDefinition("a", "A", 0, pageIds = listOf("missing"))))).errors.any { it.code == ValidationCode.PKG_INVALID_REFERENCE })
    @Test fun duplicatePageFails() = assertTrue(PackageValidator().validateManifest(valid().copy(pages = valid().pages + valid().pages)).errors.any { it.code == ValidationCode.PKG_DUPLICATE_ID })
}
