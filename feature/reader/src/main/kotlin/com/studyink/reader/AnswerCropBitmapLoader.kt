package com.studyink.reader

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.studyink.core.model.AnswerPdfCrop
import com.studyink.library.data.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Renders one answer-PDF crop and keeps a small disposable preview cache. */
internal class AnswerCropBitmapLoader private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val diskDirectory = File(applicationContext.cacheDir, CACHE_DIRECTORY)
    private val memoryCache = object : LruCache<String, Bitmap>(MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    suspend fun load(
        bookId: String,
        crop: AnswerPdfCrop,
        maximumWidthPixels: Int,
        maximumHeightPixels: Int,
    ): Bitmap = withContext(Dispatchers.IO) {
        require(maximumWidthPixels > 0 && maximumHeightPixels > 0)
        // Restore can replace LibraryRepository's singleton while this disposable cache survives.
        val repository = LibraryRepository.get(applicationContext)
        val book = repository.book(bookId)
        val answerFile = repository.answerPdfFile(book)
        val key = cacheKey(answerFile, crop, maximumWidthPixels, maximumHeightPixels)
        synchronized(memoryCache) { memoryCache.get(key) }?.let { return@withContext it }

        val cachedFile = File(diskDirectory, "$key.png")
        decodeCached(cachedFile)?.let { cached ->
            synchronized(memoryCache) { memoryCache.put(key, cached) }
            cachedFile.setLastModified(System.currentTimeMillis())
            return@withContext cached
        }

        val rendered = synchronized(RENDER_LOCK) {
            render(answerFile, crop, maximumWidthPixels, maximumHeightPixels)
        }
        synchronized(memoryCache) { memoryCache.put(key, rendered) }
        runCatching { writeCached(cachedFile, rendered) }
        rendered
    }

    fun trimMemory(level: Int) {
        synchronized(memoryCache) {
            when {
                level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> memoryCache.evictAll()
                level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> memoryCache.trimToSize(MEMORY_CACHE_BYTES / 2)
            }
        }
    }

    private fun render(
        answerFile: File,
        requestedCrop: AnswerPdfCrop,
        maximumWidthPixels: Int,
        maximumHeightPixels: Int,
    ): Bitmap {
        ParcelFileDescriptor.open(answerFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                require(requestedCrop.answerPage in 0 until renderer.pageCount) {
                    "답안 페이지가 PDF 범위를 벗어납니다."
                }
                renderer.openPage(requestedCrop.answerPage).use { page ->
                    val pageBounds = RectF(0f, 0f, page.width.toFloat(), page.height.toFloat())
                    val crop = RectF(
                        max(requestedCrop.left, pageBounds.left),
                        max(requestedCrop.top, pageBounds.top),
                        min(requestedCrop.right, pageBounds.right),
                        min(requestedCrop.bottom, pageBounds.bottom),
                    )
                    require(crop.width() >= MINIMUM_CROP_POINTS && crop.height() >= MINIMUM_CROP_POINTS) {
                        "저장된 답안 영역이 PDF 페이지 밖에 있습니다."
                    }
                    val fittingScale = min(
                        maximumWidthPixels / crop.width(),
                        maximumHeightPixels / crop.height(),
                    )
                    val pixelLimitScale = sqrt(MAXIMUM_BITMAP_PIXELS / (crop.width() * crop.height()))
                    val scale = min(fittingScale, pixelLimitScale)
                    val cropLeftPixels = floor(crop.left * scale)
                    val cropTopPixels = floor(crop.top * scale)
                    val cropRightPixels = ceil(crop.right * scale)
                    val cropBottomPixels = ceil(crop.bottom * scale)
                    val width = (cropRightPixels - cropLeftPixels).roundToInt().coerceAtLeast(1)
                    val height = (cropBottomPixels - cropTopPixels).roundToInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        val transform = Matrix().apply {
                            setScale(scale, scale)
                            postTranslate(-cropLeftPixels, -cropTopPixels)
                        }
                        page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        return bitmap
                    } catch (error: Throwable) {
                        bitmap.recycle()
                        throw error
                    }
                }
            }
        }
    }

    private fun decodeCached(file: File): Bitmap? {
        if (!file.isFile || file.length() <= 0L) return null
        return BitmapFactory.decodeFile(file.absolutePath) ?: run {
            file.delete()
            null
        }
    }

    private fun writeCached(destination: File, bitmap: Bitmap) {
        check(diskDirectory.mkdirs() || diskDirectory.isDirectory)
        val temporary = File.createTempFile("answer-crop-", ".tmp", diskDirectory)
        try {
            FileOutputStream(temporary).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.fd.sync()
            }
            if (!temporary.renameTo(destination)) {
                if (!destination.isFile) error("답안 미리보기를 저장하지 못했습니다.")
                temporary.delete()
            }
            pruneDiskCache()
        } finally {
            temporary.delete()
        }
    }

    private fun pruneDiskCache() {
        val now = System.currentTimeMillis()
        diskDirectory.listFiles { file -> file.isFile && file.extension == "tmp" }
            ?.filter { now - it.lastModified() > STALE_TEMP_FILE_AGE_MILLIS }
            ?.forEach(File::delete)
        val files = diskDirectory.listFiles { file -> file.isFile && file.extension == "png" }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
        var retainedBytes = 0L
        files.forEachIndexed { index, file ->
            retainedBytes += file.length().coerceAtLeast(0L)
            if (index >= MAXIMUM_DISK_FILES || retainedBytes > MAXIMUM_DISK_BYTES) file.delete()
        }
    }

    private fun cacheKey(
        file: File,
        crop: AnswerPdfCrop,
        maximumWidthPixels: Int,
        maximumHeightPixels: Int,
    ): String {
        val source = listOf(
            file.canonicalPath,
            file.length().toString(),
            file.lastModified().toString(),
            crop.answerPage.toString(),
            crop.left.toBits().toString(),
            crop.top.toBits().toString(),
            crop.right.toBits().toString(),
            crop.bottom.toBits().toString(),
            maximumWidthPixels.toString(),
            maximumHeightPixels.toString(),
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
    }

    companion object {
        private const val CACHE_DIRECTORY = "answer-crops"
        private const val MEMORY_CACHE_BYTES = 16 * 1024 * 1024
        private const val MAXIMUM_DISK_FILES = 24
        private const val MAXIMUM_DISK_BYTES = 64L * 1024L * 1024L
        private const val STALE_TEMP_FILE_AGE_MILLIS = 60L * 60L * 1_000L
        private const val MAXIMUM_BITMAP_PIXELS = 2_400_000f
        private const val MINIMUM_CROP_POINTS = 1f
        private val RENDER_LOCK = Any()

        @Volatile private var instance: AnswerCropBitmapLoader? = null

        fun get(context: Context): AnswerCropBitmapLoader = instance ?: synchronized(this) {
            instance ?: AnswerCropBitmapLoader(context).also { instance = it }
        }
    }
}
