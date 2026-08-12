package com.studyink.reader

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.studyink.core.model.ActivityPage
import com.studyink.core.model.BookRevision
import com.studyink.core.model.BookRevisionId
import com.studyink.core.model.LearnerProfile
import com.studyink.core.model.LearningActivity
import com.studyink.core.model.LearningActivityId
import com.studyink.core.model.LearningActivitySeed
import com.studyink.core.model.LearningContentSeed
import com.studyink.core.model.PageId
import com.studyink.core.model.ProfileId
import com.studyink.core.model.SubmissionMode
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

object SampleLearningContent {
    const val PROFILE_ID = "default-student"
    const val REVISION_ID = "sample-book-r1"

    fun ensurePdf(context: Context): File {
        val file = File(context.filesDir, "study-ink-sample.pdf")
        if (file.exists() && file.length() > 0L) return file
        val document = PdfDocument()
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(25, 39, 70)
            textSize = 34f
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(55, 62, 77)
            textSize = 20f
        }
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(205, 210, 220)
            strokeWidth = 2f
        }
        repeat(3) { index ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(840, 1188, index + 1).create())
            page.canvas.apply {
                drawColor(Color.WHITE)
                drawText("Study Ink Practice  ${index + 1}", 72f, 100f, titlePaint)
                drawText("Write an answer with the pen. Try erase, undo, zoom, and reopen.", 72f, 150f, textPaint)
                drawText("${index + 3} + ${index + 5} =", 96f, 260f, titlePaint)
                repeat(12) { line ->
                    val y = 360f + line * 58f
                    drawLine(72f, y, 768f, y, linePaint)
                }
                drawText("Page ${index + 1} / 3", 650f, 1120f, textPaint)
            }
            document.finishPage(page)
        }
        FileOutputStream(file).use(document::writeTo)
        document.close()
        return file
    }

    fun createSeed(context: Context): LearningContentSeed {
        val file = ensurePdf(context)
        val documentId = DocumentIdentity.create(context, Uri.fromFile(file))
        val pageIds = (0..2).map { PageId("$documentId:page:$it") }
        val now = file.lastModified().coerceAtLeast(1L)
        val revisionId = BookRevisionId(REVISION_ID)
        return LearningContentSeed(
            profile = LearnerProfile(ProfileId(PROFILE_ID), "학생", now),
            bookRevision = BookRevision(
                revisionId = revisionId,
                bookId = "sample-book",
                documentId = documentId,
                revisionNumber = 1,
                contentHash = file.sha256(),
                title = "테스트 학습지",
                createdAtEpochMillis = now,
            ),
            activities = listOf(
                activity("sample-unit-1", "Unit 1", 0, revisionId, listOf(pageIds[0] to 0)),
                activity("sample-unit-2", "Unit 2", 1, revisionId, listOf(pageIds[1] to 1)),
                activity("sample-unit-3", "Unit 3", 2, revisionId, listOf(pageIds[2] to 2)),
                activity(
                    "sample-review",
                    "전체 복습",
                    3,
                    revisionId,
                    pageIds.mapIndexed { index, pageId -> pageId to index },
                ),
            ),
        )
    }

    private fun activity(
        id: String,
        title: String,
        sortOrder: Int,
        revisionId: BookRevisionId,
        pages: List<Pair<PageId, Int>>,
    ) = LearningActivitySeed(
        activity = LearningActivity(
            LearningActivityId(id),
            revisionId,
            title,
            sortOrder,
            SubmissionMode.INK_AND_STRUCTURED,
        ),
        pages = pages.mapIndexed { pageOrder, (pageId, pageNumber) ->
            ActivityPage(pageId, pageNumber, pageOrder)
        },
    )

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(this).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
