package com.studyink.reader

import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeId
import com.studyink.core.model.StrokeTool
import com.studyink.monitor.core.RemoteTeacherFeedbackApplied
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteFeedbackRoutingTest {
    @Test
    fun onlyStudentReaderOnExactOpenBookAndPageAcceptsFeedbackWakeup() {
        val event = RemoteTeacherFeedbackApplied(
            bookId = "book-a",
            pageNumber = 4,
            attemptNo = 2,
            transferId = "feedback-transfer",
            basedOnStudentRevision = 8L,
        )
        val openStudent = ReaderUiState(
            bookId = "book-a",
            pageNumber = 4,
            role = ReaderRole.STUDENT,
        )

        assertTrue(openStudent.acceptsRemoteTeacherFeedback(event))
        assertFalse(openStudent.copy(pageNumber = 5).acceptsRemoteTeacherFeedback(event))
        assertFalse(openStudent.copy(bookId = "book-b").acceptsRemoteTeacherFeedback(event))
        assertFalse(openStudent.copy(role = ReaderRole.TEACHER_PHONE).acceptsRemoteTeacherFeedback(event))
    }

    @Test fun onlyNewestTaggedTelegramLayerCrossesStudentAttemptBoundary() {
        val studentAttemptTwo = stroke("student-2", "student", 2)
        val ordinaryLanTeacher = stroke(
            id = "lan-teacher",
            author = "teacher",
            attempt = 1,
            device = "teacher-tablet",
            item = "ordinary-review",
            publishedAt = 300L,
        )
        val olderRemoteAttemptTwo = stroke(
            id = "remote-old",
            author = "teacher",
            attempt = 2,
            device = "telegram-teacher-20",
            item = "remote-review:page-old",
            publishedAt = 200L,
            clock = 2L,
        )
        val latestRemoteAttemptOne = stroke(
            id = "remote-new",
            author = "teacher",
            attempt = 1,
            device = "telegram-teacher-20",
            item = "remote-review:page-new",
            publishedAt = 400L,
            clock = 3L,
        )
        val all = listOf(studentAttemptTwo, ordinaryLanTeacher, olderRemoteAttemptTwo, latestRemoteAttemptOne)

        assertEquals(
            listOf("student-2", "remote-new"),
            visibleReaderStrokes(all, 2, showLatestRemoteFeedbackAcrossAttempts = true)
                .map { it.id.value },
        )
        assertEquals(
            listOf("student-2", "remote-old"),
            visibleReaderStrokes(all, 2, showLatestRemoteFeedbackAcrossAttempts = false)
                .map { it.id.value },
        )
    }

    private fun stroke(
        id: String,
        author: String,
        attempt: Int,
        device: String = "local",
        item: String? = null,
        publishedAt: Long? = null,
        clock: Long = 1L,
    ) = StrokeAsset(
        id = StrokeId(id),
        pageNumber = 0,
        tool = StrokeTool.PEN,
        colorArgb = 0xff000000.toInt(),
        width = 2f,
        points = listOf(PagePoint(1f, 1f), PagePoint(2f, 2f)),
        authorId = author,
        attemptNo = attempt,
        deviceId = device,
        itemId = item,
        publishedAtEpochMillis = publishedAt,
        logicalClock = clock,
    )
}
