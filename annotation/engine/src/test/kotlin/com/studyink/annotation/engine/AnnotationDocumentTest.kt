package com.studyink.annotation.engine

import com.studyink.core.model.AnnotationOperationType
import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationDocumentTest {
    @Test
    fun addAndPartialEraseAdvanceOnlyTheAffectedPageRevision() {
        val document = AnnotationDocument(AnnotationSnapshot.empty("document"))
        val original = stroke(page = 2)

        val add = document.addStroke(original)
        val replace = requireNotNull(
            document.erase(
                page = 2,
                path = listOf(PagePoint(50f, 35f), PagePoint(50f, 65f)),
                radius = 8f,
                wholeStroke = false,
            )
        )

        assertEquals(AnnotationOperationType.ADD_STROKE, add.operation.operationType)
        assertEquals(AnnotationOperationType.REPLACE_STROKES, replace.operation.operationType)
        assertEquals(1L, replace.operation.baseRevision)
        assertEquals(2L, document.snapshot().revision)
        assertEquals(mapOf(2 to 2L), document.snapshot().pageRevisions)
    }

    @Test
    fun undoAfterPartialEraseRestoresTheImmutableOriginalStroke() {
        val document = AnnotationDocument(AnnotationSnapshot.empty("document"))
        val original = stroke(page = 0)
        document.addStroke(original)
        document.erase(
            page = 0,
            path = listOf(PagePoint(50f, 35f), PagePoint(50f, 65f)),
            radius = 8f,
            wholeStroke = false,
        )

        val undo = requireNotNull(document.undo())

        assertEquals(AnnotationOperationType.REPLACE_STROKES, undo.operation.operationType)
        assertEquals(setOf(original.id), undo.snapshot.activeStrokeIds)
        assertTrue(undo.addedAssets.isEmpty())
    }

    private fun stroke(page: Int) = StrokeAsset(
        pageNumber = page,
        tool = StrokeTool.PEN,
        colorArgb = 0xff000000.toInt(),
        width = 4f,
        points = listOf(PagePoint(0f, 50f), PagePoint(50f, 50f), PagePoint(100f, 50f)),
    )
}
