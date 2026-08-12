package com.studyink.annotation.storage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import kotlin.math.max

internal object ImageZipImporter {
    fun orderedPageNames(file: File): List<String> = ZipFile(file).use { zip ->
        val entries = zip.entries().toList().filterNot { it.isDirectory }
        val imageNames = entries.map { it.name.replace('\\', '/') }.filter { it.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "webp") }
        val explicit = zip.getEntry("page-order.json")?.let { entry ->
            zip.getInputStream(entry).bufferedReader().use { it.readText() }.let { json -> Regex("\"([^\"]+\\.(?:png|jpe?g|webp))\"", RegexOption.IGNORE_CASE).findAll(json).map { it.groupValues[1] }.toList() }
        }
        if (explicit != null) { require(explicit.size == imageNames.size && explicit.toSet() == imageNames.toSet()) { "IMPORT_INVALID_PAGE_ORDER" }; explicit }
        else imageNames.sortedWith(Comparator(::naturalCompare))
    }

    fun materialize(file: File, output: File): Int {
        val names = orderedPageNames(file); require(names.isNotEmpty()) { "IMPORT_EMPTY_IMAGE_ZIP" }
        ZipFile(file).use { zip ->
            val pdf = PdfDocument()
            try {
                names.forEachIndexed { index, name ->
                    val entry = requireNotNull(zip.getEntry(name)); val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }; require(bounds.outWidth > 0 && bounds.outHeight > 0) { "IMPORT_BROKEN_IMAGE" }
                    var sample = 1; while (max(bounds.outWidth / sample, bounds.outHeight / sample) > 2_048 * 2) sample *= 2
                    val bitmap = zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: error("IMPORT_BROKEN_IMAGE")
                    try {
                        val page = pdf.startPage(PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, index + 1).create())
                        page.canvas.drawColor(Color.WHITE); page.canvas.drawBitmap(bitmap, 0f, 0f, null); pdf.finishPage(page)
                    } finally { bitmap.recycle() }
                }
                output.parentFile?.mkdirs(); FileOutputStream(output).use { pdf.writeTo(it); it.fd.sync() }
            } finally { pdf.close() }
        }
        return names.size
    }

    fun naturalCompare(a: String, b: String): Int {
        val parts = Regex("(\\d+|\\D+)"); val aa=parts.findAll(a.lowercase()).map { it.value }.toList(); val bb=parts.findAll(b.lowercase()).map { it.value }.toList()
        for(i in 0 until minOf(aa.size,bb.size)){val x=aa[i];val y=bb[i];val c=if(x.all(Char::isDigit)&&y.all(Char::isDigit)) x.trimStart('0').ifEmpty{"0"}.length.compareTo(y.trimStart('0').ifEmpty{"0"}.length).takeIf{it!=0}?:x.toLongOrNull()?.compareTo(y.toLongOrNull()?:0)?:x.compareTo(y) else x.compareTo(y);if(c!=0)return c}
        return aa.size.compareTo(bb.size)
    }
}
