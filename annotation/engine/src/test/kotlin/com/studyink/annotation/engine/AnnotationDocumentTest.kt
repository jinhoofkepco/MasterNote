package com.studyink.annotation.engine

import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.AssetOperation
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeTool
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationDocumentTest {
    @Test
    fun remoteTeacherOperationNeverEntersStudentUndo() {
        val document = AnnotationDocument(AnnotationSnapshot.empty("book", 0))
        val student = stroke("student")
        document.addStroke(student)
        val teacher = stroke("teacher")
        document.applyRemote(
            AssetOperation(
                removedStrokeIds = emptySet(),
                addedStrokeIds = setOf(teacher.id),
                logicalClock = 4,
                deviceId = "teacher-phone",
            ),
            listOf(teacher),
        )

        val undone = requireNotNull(document.undo("student-tablet"))
        assertFalse(undone.snapshot.activeStrokeIds.contains(student.id))
        assertTrue(undone.snapshot.activeStrokeIds.contains(teacher.id))
    }

    @Test
    fun studentEraserCannotTouchTeacherStroke() {
        val student = stroke("student")
        val teacher = stroke("teacher")
        val initial = AnnotationSnapshot(
            bookId = "book",
            pageNumber = 0,
            revision = 2,
            assets = listOf(student, teacher).associateBy { it.id },
            activeStrokeIds = setOf(student.id, teacher.id),
        )
        val document = AnnotationDocument(initial)
        val change = requireNotNull(document.erase(
            page = 0,
            path = listOf(PagePoint(50f, 45f), PagePoint(50f, 55f)),
            radius = 8f,
            wholeStroke = true,
            authorId = "student",
            attemptNo = 1,
            deviceId = "student-tablet",
        ))
        assertFalse(change.snapshot.activeStrokeIds.contains(student.id))
        assertTrue(change.snapshot.activeStrokeIds.contains(teacher.id))
    }

    private fun stroke(author: String) = StrokeAsset(
        pageNumber = 0,
        tool = StrokeTool.PEN,
        colorArgb = 0xff000000.toInt(),
        width = 4f,
        points = listOf(PagePoint(0f, 50f), PagePoint(100f, 50f)),
        authorId = author,
        attemptNo = 1,
        deviceId = "$author-device",
    )
}
