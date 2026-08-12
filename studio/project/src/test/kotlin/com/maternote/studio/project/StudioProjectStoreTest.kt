package com.maternote.studio.project

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class StudioProjectStoreTest {
    @Test fun projectRoundTrips() {
        val expected = StudioProject.fixture("Test book")
        val file = Files.createTempDirectory("mnproj").resolve("book.mnproj").toFile()
        StudioProjectStore().write(file, expected)
        assertEquals(expected, StudioProjectStore().read(file))
    }
}
