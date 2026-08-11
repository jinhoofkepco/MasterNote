package com.studyink.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

internal object ReaderPdfFixtures {
    fun textPdf(directory: File): File = writePdf(File(directory, "baseline-text-v1.pdf")) { document ->
        repeat(3) { index ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(840, 1188, index + 1).create())
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(30, 40, 60)
                textSize = 28f
            }
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawText("MasterNote text fixture v1 · page ${index + 1}", 64f, 100f, paint)
            repeat(16) { row -> page.canvas.drawText("Reader and ink regression line ${row + 1}", 64f, 180f + row * 48f, paint) }
            document.finishPage(page)
        }
    }

    fun scannedPdf(directory: File): File = writePdf(File(directory, "baseline-scan-v1.pdf")) { document ->
        val scan = Bitmap.createBitmap(1600, 2400, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(scan)
        canvas.drawColor(Color.rgb(244, 241, 232))
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 94, 104); strokeWidth = 3f }
        repeat(70) { row ->
            val y = 60f + row * 32f
            canvas.drawLine(50f, y, 1550f, y, line)
        }
        val page = document.startPage(PdfDocument.PageInfo.Builder(840, 1188, 1).create())
        page.canvas.drawBitmap(scan, null, android.graphics.Rect(0, 0, 840, 1188), null)
        document.finishPage(page)
        scan.recycle()
    }

    fun longPdf(directory: File): File = writePdf(File(directory, "baseline-long-120p-v1.pdf")) { document ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 26f }
        repeat(120) { index ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(840, 1188, index + 1).create())
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawText("MasterNote long fixture v1 · ${index + 1}/120", 64f, 100f, paint)
            document.finishPage(page)
        }
    }

    private inline fun writePdf(file: File, draw: (PdfDocument) -> Unit): File {
        val document = PdfDocument()
        try {
            draw(document)
            FileOutputStream(file).use(document::writeTo)
        } finally {
            document.close()
        }
        return file
    }
}
