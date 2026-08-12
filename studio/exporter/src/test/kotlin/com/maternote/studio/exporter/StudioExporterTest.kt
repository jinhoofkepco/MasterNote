package com.maternote.studio.exporter

import com.maternote.packageformat.validator.PackageValidator
import com.maternote.studio.project.StudioProject
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StudioExporterTest {
    @Test fun exportIsDeterministicAndStrictlyValid() {
        val directory = Files.createTempDirectory("studio-export").toFile()
        val project = StudioProject.fixture("Three Page Book")
        val first = StudioExporter.exportFixture(project, directory.resolve("first.mnote"))
        val second = StudioExporter.exportFixture(project, directory.resolve("second.mnote"))
        assertContentEquals(first.file.readBytes(), second.file.readBytes())
        val (manifest, report) = PackageValidator().validatePackage(first.file, strictStudio = true)
        assertTrue(report.isValid)
        assertEquals(3, manifest?.pages?.size)
        assertEquals(2, manifest?.activities?.size)
    }

    @Test fun snapshotDoesNotChangeWhenSourceBytesChange() {
        val bytes = "original".encodeToByteArray()
        val snapshot = ExportSnapshot.capture(
            StudioProject.fixture("Immutable"),
            listOf(ExportAssetSnapshot("main-document", "pdf", "application/pdf", bytes)),
        )
        bytes.fill(0)
        assertEquals(sha("original".encodeToByteArray()), sha(snapshot.assets.single().bytes))
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toList()
}
