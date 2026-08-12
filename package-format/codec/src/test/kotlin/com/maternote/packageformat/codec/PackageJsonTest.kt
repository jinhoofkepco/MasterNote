package com.maternote.packageformat.codec

import com.maternote.packageformat.model.*
import kotlin.test.*

class PackageJsonTest {
    private val manifest = PackageManifest(
        packageId = "pkg", createdAt = "2026-08-12T00:00:00Z", createdBy = CreatedBy("test", "1"),
        requiredCapabilities = listOf("document.pdf", "activity.basic"),
        book = BookDefinition("book", "rev", revisionNumber = 1, title = "Book"),
        assets = listOf(AssetDefinition("pdf", "assets/${"a".repeat(64)}.pdf", "application/pdf", "a".repeat(64), 1)),
        document = DocumentDefinition("pdf", "pdf"), pages = listOf(PageDefinition("p1", PageSource("pdfPage", 0), 1000, 1400)),
        activities = listOf(ActivityDefinition("a1", "Unit", 0, pageIds = listOf("p1"))),
    )
    @Test fun roundTrip() = assertEquals(manifest, PackageJson.decode(PackageJson.encode(manifest)))
    @Test fun readerAcceptsUnknownMinorField() = assertEquals("pkg", PackageJson.decode(PackageJson.encode(manifest).dropLast(1) + ",\"future\":1}").packageId)
    @Test fun strictRejectsUnknownField() = assertFails { PackageJson.decode(PackageJson.encode(manifest).dropLast(1) + ",\"future\":1}", true) }
}
