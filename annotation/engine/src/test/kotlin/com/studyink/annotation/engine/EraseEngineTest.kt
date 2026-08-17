package com.studyink.annotation.engine

import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EraseEngineTest {
    private val stroke = StrokeAsset(
        pageNumber = 0,
        tool = StrokeTool.PEN,
        colorArgb = 0xff000000.toInt(),
        width = 4f,
        points = listOf(PagePoint(0f, 50f), PagePoint(100f, 50f)),
        authorId = "teacher",
        attemptNo = 4,
        deviceId = "teacher-phone",
    )

    @Test fun partialEraseSplitsAStrokeAndPreservesParent() {
        val result = EraseEngine.partialErase(
            strokes = listOf(stroke),
            pageNumber = 0,
            eraserPath = listOf(PagePoint(50f, 40f), PagePoint(50f, 60f)),
            eraserRadius = 8f,
        )
        assertEquals(setOf(stroke.id), result.removedStrokeIds)
        assertEquals(2, result.fragments.size)
        assertTrue(result.fragments.all { it.parentStrokeId == stroke.id })
        assertTrue(result.fragments.all { it.authorId == "teacher" })
        assertTrue(result.fragments.all { it.attemptNo == 4 })
        assertTrue(result.fragments.all { it.deviceId == "teacher-phone" })
    }

    @Test fun wholeEraseRemovesWithoutFragments() {
        val result = EraseEngine.wholeStrokeErase(
            listOf(stroke), 0, listOf(PagePoint(40f, 50f), PagePoint(60f, 50f)), 5f
        )
        assertEquals(setOf(stroke.id), result.removedStrokeIds)
        assertTrue(result.fragments.isEmpty())
    }

    @Test fun partialErasePreviewOnlyMarksTheTouchedSection() {
        val preview = EraseEngine.partialErasePreviewSegments(
            strokes = listOf(stroke),
            pageNumber = 0,
            eraserPath = listOf(PagePoint(50f, 40f), PagePoint(50f, 60f)),
            eraserRadius = 8f,
        )

        assertTrue(preview.isNotEmpty())
        val markedPoints = preview.flatMap { it.points }
        assertTrue(markedPoints.all { it.x in 38f..62f })
        assertTrue(markedPoints.none { it.x == 0f || it.x == 100f })
    }

    @Test fun eraseOnAnotherPageDoesNothing() {
        val result = EraseEngine.partialErase(
            listOf(stroke), 1, listOf(PagePoint(50f, 50f)), 20f
        )
        assertTrue(result.removedStrokeIds.isEmpty())
    }
}
