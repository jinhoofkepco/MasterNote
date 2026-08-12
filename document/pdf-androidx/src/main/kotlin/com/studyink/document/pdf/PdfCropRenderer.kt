package com.studyink.document.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

data class NormalizedPageRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f)
        require(left < right && top < bottom)
    }
}

data class CropRenderResult(val file: File, val width: Int, val height: Int)

/** Renders from the PDF page itself; screen zoom, rotation, and visible viewport do not affect output. */
object PdfCropRenderer {
    fun render(source: File, pageIndex: Int, bounds: NormalizedPageRect, output: File, maxLongEdge: Int = 2048): CropRenderResult {
        require(maxLongEdge in 256..4096)
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                require(pageIndex in 0 until renderer.pageCount)
                renderer.openPage(pageIndex).use { page ->
                    val cropWidth = page.width * (bounds.right - bounds.left)
                    val cropHeight = page.height * (bounds.bottom - bounds.top)
                    val scale = minOf(maxLongEdge / cropWidth, maxLongEdge / cropHeight).coerceAtLeast(1f)
                    val width = (cropWidth * scale).roundToInt().coerceAtLeast(1)
                    val height = (cropHeight * scale).roundToInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    val matrix = Matrix().apply {
                        setScale(scale, scale)
                        postTranslate(-page.width * bounds.left * scale, -page.height * bounds.top * scale)
                    }
                    page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    output.parentFile?.mkdirs()
                    FileOutputStream(output).use { stream ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
                        stream.fd.sync()
                    }
                    bitmap.recycle()
                    return CropRenderResult(output, width, height)
                }
            }
        }
    }
}
