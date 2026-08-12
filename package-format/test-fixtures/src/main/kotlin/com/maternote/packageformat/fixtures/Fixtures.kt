package com.maternote.packageformat.fixtures

import com.maternote.packageformat.model.*

object Fixtures {
    fun threePageBook(asset: AssetDefinition) = PackageManifest(
        packageId = "fixture-package", createdAt = "2026-08-12T00:00:00Z", createdBy = CreatedBy("Maternote Studio", "0.1.0"),
        requiredCapabilities = listOf("document.pdf", "activity.basic"), book = BookDefinition("fixture-book", "fixture-revision-1", revisionNumber = 1, title = "Fixture Book"),
        assets = listOf(asset), document = DocumentDefinition("pdf", asset.assetId),
        pages = (1..3).map { PageDefinition("page-$it", PageSource("pdfPage", it - 1), 1000, 1414) },
        activities = listOf(ActivityDefinition("activity-1", "Unit 1", 0, pageIds = listOf("page-1", "page-2")), ActivityDefinition("activity-2", "Unit 2", 1, pageIds = listOf("page-3"))),
    )
}
