package com.studyink.monitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TeacherReviewPublicationProvenanceBusTest {
    @Test
    fun latestOwnerResolvesAndAnOlderCloseCannotRemoveIt() {
        val first = TeacherReviewPublicationProvenanceBus.install {
            TeacherReviewPublicationProvenance("pair_first", "workbook_first", 1L, 2L)
        }
        val secondValue = TeacherReviewPublicationProvenance(
            "pair_second",
            "workbook_second",
            3L,
            4L,
        )
        val second = TeacherReviewPublicationProvenanceBus.install { secondValue }
        try {
            first.close()
            assertEquals(
                secondValue,
                TeacherReviewPublicationProvenanceBus.resolve("book", 1, 2),
            )
        } finally {
            second.close()
        }
        assertNull(TeacherReviewPublicationProvenanceBus.resolve("book", 1, 2))
    }
}
