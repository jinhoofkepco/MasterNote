package com.studyink.monitor.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.studyink.annotation.storage.PageOperationLogStore
import com.studyink.library.data.LibraryRepository
import java.io.File
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.Semaphore

/**
 * Serial, off-screen PDF + student-ink renderer used by submission delivery and `/화면`.
 *
 * It never observes Reader views, mark groups, hover state, or teacher assets. Consequently a
 * captured document cannot accidentally contain menus, grading squares, another attempt, or a
 * teacher draft. This is blocking I/O and must be called from a worker thread.
 */
class MasterNotePageRenderer(
    context: Context,
    private val limits: PageRenderLimits = PageRenderLimits(),
) {
    private val applicationContext = context.applicationContext

    fun render(request: PageRenderRequest, destinationDirectory: File): RenderedPage = withRenderPermit {
        val library = LibraryRepository.get(applicationContext)
        val book = library.book(request.bookId)
        require(request.pageNumber in 0 until book.pageCount) { "Page is outside the workbook" }

        if (request.purpose == PageRenderPurpose.LOCKED_SUBMISSION) {
            val lockedAttempt = library.attempts(request.bookId, request.pageNumber)
                .firstOrNull { it.attemptNo == request.attemptNo }
                ?: throw PageRenderSourceChangedException("The submitted attempt no longer exists")
            if (!lockedAttempt.locked ||
                lockedAttempt.lockedAtEpochMillis != request.expectedLockedAtEpochMillis
            ) {
                throw PageRenderSourceChangedException("The submitted attempt lock no longer matches the request")
            }
        }

        val snapshot = PageOperationLogStore.get(applicationContext)
            .loadPage(request.bookId, request.pageNumber)
        check(snapshot.bookId == request.bookId && snapshot.pageNumber == request.pageNumber) {
            "Annotation store returned another page"
        }
        val studentStrokes = selectStudentStrokes(snapshot, request.pageNumber, request.attemptNo)
        val pdfFile = library.pdfFile(book)
        val studentName = library.state.students
            .firstOrNull { it.id == book.studentId }
            ?.displayName
            ?: "학생"

        renderResolvedPage(
            request = request,
            pdfFile = pdfFile,
            bookPageCount = book.pageCount,
            studentStrokes = studentStrokes,
            annotationRevision = snapshot.revision,
            studentId = book.studentId,
            studentDisplayName = studentName,
            bookTitle = book.title,
            destinationDirectory = destinationDirectory,
        )
    }

    private fun renderResolvedPage(
        request: PageRenderRequest,
        pdfFile: File,
        bookPageCount: Int,
        studentStrokes: List<com.studyink.core.model.StrokeAsset>,
        annotationRevision: Long,
        studentId: String,
        studentDisplayName: String,
        bookTitle: String,
        destinationDirectory: File,
    ): RenderedPage {
        check(destinationDirectory.mkdirs() || destinationDirectory.isDirectory) {
            "Cannot create page-render destination directory"
        }
        val output = File.createTempFile(
            "masternote-page-",
            ".${limits.imageFormat.extension}",
            destinationDirectory,
        )
        var bitmap: Bitmap? = null
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size: RenderSize? = null
            var rasterStrokeCount = 0
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    check(renderer.pageCount == bookPageCount) {
                        "The workbook PDF page count changed"
                    }
                    require(request.pageNumber in 0 until renderer.pageCount) {
                        "Page is outside the rendered PDF"
                    }
                    renderer.openPage(request.pageNumber).use { page ->
                        val renderSize = calculateRenderSize(page.width, page.height, limits)
                        size = renderSize
                        bitmap = Bitmap.createBitmap(
                            renderSize.width,
                            renderSize.height,
                            Bitmap.Config.ARGB_8888,
                        )
                        val targetBitmap = checkNotNull(bitmap)
                        val canvas = Canvas(targetBitmap)
                        canvas.drawColor(Color.WHITE)
                        val scale = renderSize.width.toFloat() / page.width.toFloat()
                        page.render(
                            targetBitmap,
                            null,
                            Matrix().apply { setScale(scale, scale) },
                            PdfRenderer.Page.RENDER_MODE_FOR_PRINT,
                        )

                        rasterStrokeCount = drawStudentStrokes(
                            canvas,
                            rasterizeStudentStrokes(studentStrokes, renderSize.width),
                        )
                        FileOutputStream(output).use { fileOutput ->
                            val digestOutput = DigestOutputStream(fileOutput, digest)
                            val compressed = targetBitmap.compress(
                                limits.imageFormat.toBitmapFormat(),
                                limits.jpegQuality,
                                digestOutput,
                            )
                            check(compressed) { "Could not encode the rendered workbook page" }
                            digestOutput.flush()
                            fileOutput.fd.sync()
                        }
                    }
                }
            }
            val completedSize = checkNotNull(size)
            val displayBase = safeTelegramDisplayName(bookTitle, "문제집")
            return RenderedPage(
                request = request,
                file = output,
                displayFileName = "${displayBase}_${request.pageNumber + 1}쪽" +
                    request.attemptNo?.let { "_${it}회" }.orEmpty() +
                    ".${limits.imageFormat.extension}",
                mimeType = limits.imageFormat.mimeType,
                widthPixels = completedSize.width,
                heightPixels = completedSize.height,
                sha256 = digest.digest().toHex(),
                byteCount = output.length(),
                annotationRevision = annotationRevision,
                renderedStudentStrokeCount = rasterStrokeCount,
                studentId = studentId,
                studentDisplayName = studentDisplayName,
                bookTitle = bookTitle,
                renderedAtEpochMillis = System.currentTimeMillis(),
            )
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            bitmap?.recycle()
        }
    }

    private fun drawStudentStrokes(canvas: Canvas, strokes: Sequence<RasterStroke>): Int {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        var count = 0
        strokes.forEach { stroke ->
            count++
            paint.color = stroke.colorArgb
            paint.alpha = stroke.alpha
            paint.strokeWidth = stroke.widthPixels
            val first = stroke.points.first()
            if (stroke.points.size == 1) {
                // A zero-length path is invisible. A filled circle preserves a pen tap without
                // allocating a second Path or bitmap.
                val previousStyle = paint.style
                paint.style = Paint.Style.FILL
                canvas.drawCircle(first.x, first.y, stroke.widthPixels / 2f, paint)
                paint.style = previousStyle
            } else {
                path.rewind()
                path.moveTo(first.x, first.y)
                for (index in 1 until stroke.points.size) {
                    val point = stroke.points[index]
                    path.lineTo(point.x, point.y)
                }
                canvas.drawPath(path, paint)
            }
        }
        return count
    }

    private fun PageRenderImageFormat.toBitmapFormat(): Bitmap.CompressFormat = when (this) {
        PageRenderImageFormat.PNG -> Bitmap.CompressFormat.PNG
        PageRenderImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private inline fun <T> withRenderPermit(block: () -> T): T {
        var acquired = false
        try {
            try {
                PROCESS_RENDER_PERMIT.acquire()
                acquired = true
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Page rendering was interrupted").apply { initCause(error) }
            }
            return block()
        } finally {
            if (acquired) PROCESS_RENDER_PERMIT.release()
        }
    }

    companion object {
        /** One ARGB page bitmap process-wide, even if two queues accidentally construct renderers. */
        private val PROCESS_RENDER_PERMIT = Semaphore(1, true)

        @Volatile
        private var instance: MasterNotePageRenderer? = null

        fun get(context: Context): MasterNotePageRenderer = instance ?: synchronized(this) {
            instance ?: MasterNotePageRenderer(context.applicationContext).also { instance = it }
        }
    }
}
