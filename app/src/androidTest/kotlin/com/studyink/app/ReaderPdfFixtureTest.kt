package com.studyink.app

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderPdfFixtureTest {
    @Test
    fun deterministicBaselineFixturesCoverTextScanAndLongDocuments() {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val text = ReaderPdfFixtures.textPdf(directory)
        val scan = ReaderPdfFixtures.scannedPdf(directory)
        val long = ReaderPdfFixtures.longPdf(directory)

        assertEquals(3, text.pageCount())
        assertEquals(1, scan.pageCount())
        assertEquals(120, long.pageCount())
        assertTrue("스캔 PDF는 비어 있으면 안 됩니다", scan.length() > 0L)
    }

    private fun java.io.File.pageCount(): Int =
        ParcelFileDescriptor.open(this, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer -> renderer.pageCount }
        }
}
