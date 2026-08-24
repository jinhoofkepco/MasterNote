package com.studyink.monitor.render

import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeId
import com.studyink.core.model.StrokeTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRenderGeometryTest {
    @Test
    fun `ordinary portrait page uses 1600 px width and retains aspect ratio`() {
        val size = calculateRenderSize(595, 842, PageRenderLimits())

        assertEquals(1600, size.width)
        assertEquals(2265, size.height)
        assertTrue(size.pixelCount <= 4_000_000L)
    }

    @Test
    fun `pathological tall page is reduced below the bitmap memory ceiling`() {
        val size = calculateRenderSize(100, 1000, PageRenderLimits())

        assertTrue(size.width < 1280)
        assertTrue(size.pixelCount <= 4_000_000L)
        assertEquals(10f, size.height.toFloat() / size.width.toFloat(), 0.02f)
    }

    @Test
    fun `only active student strokes from the exact page and attempt are selected`() {
        val selected = stroke("selected", page = 3, attempt = 4, author = "student")
        val teacher = stroke("teacher", page = 3, attempt = 4, author = "teacher")
        val previousAttempt = stroke("previous", page = 3, attempt = 3, author = "student")
        val otherPage = stroke("other-page", page = 2, attempt = 4, author = "student")
        val erased = stroke("erased", page = 3, attempt = 4, author = "student")
        val snapshot = AnnotationSnapshot(
            bookId = "book",
            pageNumber = 3,
            revision = 9L,
            assets = listOf(selected, teacher, previousAttempt, otherPage, erased).associateBy { it.id },
            activeStrokeIds = setOf(selected.id, teacher.id, previousAttempt.id, otherPage.id),
        )

        assertEquals(listOf(selected), selectStudentStrokes(snapshot, pageNumber = 3, attemptNo = 4))
        assertTrue(selectStudentStrokes(snapshot, pageNumber = 3, attemptNo = null).isEmpty())
    }

    @Test
    fun `canonical geometry has a stable 1000 to output width transform`() {
        val pen = stroke(
            id = "pen",
            page = 0,
            attempt = 2,
            author = "student",
            width = 5f,
            points = listOf(PagePoint(100f, 200f), PagePoint(750f, 900f)),
            colorArgb = 0xCC123456.toInt(),
        )
        val highlighter = stroke(
            id = "highlighter",
            page = 0,
            attempt = 2,
            author = "student",
            width = 20f,
            points = listOf(PagePoint(500f, 500f)),
            colorArgb = 0xFFABCDEF.toInt(),
            tool = StrokeTool.HIGHLIGHTER,
        )

        val raster = rasterizeStudentStrokes(listOf(pen, highlighter), outputWidthPixels = 1600).toList()

        assertEquals(2, raster.size)
        assertEquals(RasterPoint(160f, 320f), raster[0].points[0])
        assertEquals(RasterPoint(1200f, 1440f), raster[0].points[1])
        assertEquals(8f, raster[0].widthPixels, 0.001f)
        assertEquals(0xCC, raster[0].alpha)
        assertEquals(RasterPoint(800f, 800f), raster[1].points.single())
        assertEquals(32f, raster[1].widthPixels, 0.001f)
        assertEquals(95, raster[1].alpha)
    }

    @Test
    fun `telegram filename strips separators and control characters`() {
        assertEquals("수학_5_2_문제집", safeTelegramDisplayName(" 수학/5:2\n문제집 ", "문제집"))
        assertEquals("문제집", safeTelegramDisplayName("<>|", "문제집"))
    }

    private fun stroke(
        id: String,
        page: Int,
        attempt: Int,
        author: String,
        width: Float = 4f,
        points: List<PagePoint> = listOf(PagePoint(10f, 20f), PagePoint(30f, 40f)),
        colorArgb: Int = 0xFF102030.toInt(),
        tool: StrokeTool = StrokeTool.PEN,
    ) = StrokeAsset(
        id = StrokeId(id),
        pageNumber = page,
        tool = tool,
        colorArgb = colorArgb,
        width = width,
        points = points,
        authorId = author,
        attemptNo = attempt,
    )
}
