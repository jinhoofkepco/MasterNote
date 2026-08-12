package com.studyink.document.pdf

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfCropRendererInstrumentedTest {
    @Test
    fun render_usesCanonicalPageBoundsInsteadOfScreenViewport() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "crop-source.pdf")
        val output = File(context.cacheDir, "crop-result.png")
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(200, 200, 1).create())
            page.canvas.drawRect(0f, 0f, 100f, 200f, Paint().apply { color = Color.RED })
            page.canvas.drawRect(100f, 0f, 200f, 200f, Paint().apply { color = Color.BLUE })
            document.finishPage(page)
            FileOutputStream(source).use(document::writeTo)
        } finally {
            document.close()
        }

        val result = PdfCropRenderer.render(
            source = source,
            pageIndex = 0,
            bounds = NormalizedPageRect(0.5f, 0f, 1f, 1f),
            output = output,
            maxLongEdge = 256,
        )
        val bitmap = android.graphics.BitmapFactory.decodeFile(output.path)

        assertEquals(128, result.width)
        assertEquals(256, result.height)
        val center = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        assertTrue("expected blue crop but was ${Integer.toHexString(center)}", Color.blue(center) > 200)
        assertTrue(Color.red(center) < 50)
        bitmap.recycle()
    }
}
