package com.studyink.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TeacherReviewStateDigestTest {
    @Test fun digestIsOrderIndependentButCoversPublicationInkAndGrades() {
        val first = entry(1, "11", "22", "33")
        val second = entry(2, "44", "55", "66")
        val baseline = teacherReviewStateSha256(listOf(first, second))

        assertEquals(
            "150f8192895c13a2bd7b7bcbfe52b1f6c557b28f24870c5ebc4d7f478678138c",
            baseline,
        )
        assertEquals(baseline, teacherReviewStateSha256(listOf(second, first)))
        assertNotEquals(baseline, teacherReviewStateSha256(listOf(first.copy(publicationId = "77".repeat(32)), second)))
        assertNotEquals(baseline, teacherReviewStateSha256(listOf(first.copy(resultLayerSha256 = "77".repeat(32)), second)))
        assertNotEquals(baseline, teacherReviewStateSha256(listOf(first.copy(markGroupsSha256 = "77".repeat(32)), second)))
        assertTrue(teacherReviewStateSha256(emptyList()).matches(Regex("[0-9a-f]{64}")))
    }

    @Test fun invalidOrDuplicateEvidenceIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            entry(0, "11", "22", "33")
        }
        assertThrows(IllegalArgumentException::class.java) {
            entry(1, "AB", "22", "33")
        }
        val first = entry(1, "11", "22", "33")
        assertThrows(IllegalArgumentException::class.java) {
            teacherReviewStateSha256(listOf(first, first.copy(publicationId = "44".repeat(32))))
        }
    }

    @Test fun gradeDigestIsPortableAndIgnoresMetadataSharedByOtherAttempts() {
        val first = markGroup("group_a", "teacher_book", 13, MarkColor.BLUE)
        val second = markGroup("group_b", "teacher_book", 13, MarkColor.RED)
        val baseline = teacherReviewMarkGroupsSha256(listOf(first, second))
        val studentLocalCopies = listOf(second, first).map {
            it.copy(bookId = "student_local_book", pageNumber = 999)
        }

        assertEquals(baseline, teacherReviewMarkGroupsSha256(studentLocalCopies))
        assertEquals(
            baseline,
            teacherReviewMarkGroupsSha256(listOf(
                first.copy(
                    anchor = PagePoint(99f, 88f),
                    hiddenAtEpochMillis = 900L,
                    syncRevision = 99L,
                    lastModifiedByDeviceId = "another_writer",
                ),
                second,
            )),
        )
        assertNotEquals(
            baseline,
            teacherReviewMarkGroupsSha256(
                listOf(
                    first.copy(
                        marks = first.marks.map { it.copy(hiddenAtEpochMillis = 901L) },
                    ),
                    second,
                ),
            ),
        )
        assertNotEquals(baseline, teacherReviewMarkGroupsSha256(emptyList()))
    }

    @Test fun sharedMetadataNormalizesToLatestGlobalStateIndependentOfAttemptOrder() {
        val firstAttempt = markGroup("shared", "teacher_book", 13, MarkColor.BLUE)
        val laterAttempt = firstAttempt.copy(
            bookId = "student_book",
            pageNumber = 999,
            anchor = PagePoint(90f, 80f, 0.5f),
            marks = listOf(Mark(3, MarkColor.RED, gradedAtEpochMillis = 900L)),
            hiddenAtEpochMillis = 1_000L,
            syncRevision = 5L,
            lastModifiedByDeviceId = "teacher_device_b",
        )

        val expected = normalizeTeacherReviewMarkGroupMetadata(listOf(firstAttempt, laterAttempt))
        assertEquals(expected, normalizeTeacherReviewMarkGroupMetadata(listOf(laterAttempt, firstAttempt)))
        assertEquals(1, expected.size)
        assertEquals(laterAttempt.anchor, expected.single().anchor)
        assertEquals(laterAttempt.hiddenAtEpochMillis, expected.single().hiddenAtEpochMillis)
    }

    @Test fun laterAttemptMetadataDoesNotInvalidateEarlierAttemptWhenBothSidesNormalizePage() {
        val firstPublished = markGroup("shared", "teacher_book", 13, MarkColor.BLUE)
            .copy(marks = listOf(Mark(1, MarkColor.BLUE, gradedAtEpochMillis = 700L)))
        val secondPublished = firstPublished.copy(
            anchor = PagePoint(90f, 80f, 0.5f),
            marks = listOf(Mark(2, MarkColor.RED, gradedAtEpochMillis = 900L)),
            syncRevision = 5L,
            lastModifiedByDeviceId = "teacher_device_b",
        )
        val studentCurrent = secondPublished.copy(
            bookId = "student_book",
            pageNumber = 999,
            marks = firstPublished.marks + secondPublished.marks,
        )
        val teacherMetadata = teacherReviewMarkGroupMetadataSha256(
            normalizeTeacherReviewMarkGroupMetadata(listOf(firstPublished, secondPublished)),
        )
        val studentMetadata = teacherReviewMarkGroupMetadataSha256(
            normalizeTeacherReviewMarkGroupMetadata(listOf(studentCurrent)),
        )

        assertEquals(teacherMetadata, studentMetadata)
        assertEquals(
            teacherReviewGradeStateSha256(
                teacherReviewMarkGroupsSha256(listOf(firstPublished)),
                teacherMetadata,
            ),
            teacherReviewGradeStateSha256(
                teacherReviewMarkGroupsSha256(
                    listOf(studentCurrent.copy(marks = firstPublished.marks)),
                ),
                studentMetadata,
            ),
        )
    }

    @Test fun rolledBackAnchorOrVisibilityChangesCombinedGradeState() {
        val current = markGroup("shared", "teacher_book", 13, MarkColor.BLUE).copy(
            anchor = PagePoint(90f, 80f, 0.5f),
            hiddenAtEpochMillis = 1_000L,
            syncRevision = 5L,
            lastModifiedByDeviceId = "teacher_device_b",
        )
        val marksSha = teacherReviewMarkGroupsSha256(listOf(current))
        fun combined(group: MarkGroup): String = teacherReviewGradeStateSha256(
            marksSha,
            teacherReviewMarkGroupMetadataSha256(
                normalizeTeacherReviewMarkGroupMetadata(listOf(group)),
            ),
        )
        val baseline = combined(current)

        assertNotEquals(baseline, combined(current.copy(anchor = PagePoint(12f, 34f, 0.75f))))
        assertNotEquals(baseline, combined(current.copy(hiddenAtEpochMillis = null)))
    }

    @Test fun metadataTieBreakAndCanonicalHashAreDeterministic() {
        val fromA = markGroup("shared", "teacher_book", 13, MarkColor.BLUE).copy(
            syncRevision = 7L,
            lastModifiedByDeviceId = "device_a",
            anchor = PagePoint(10f, 20f),
        )
        val fromB = fromA.copy(
            lastModifiedByDeviceId = "device_b",
            anchor = PagePoint(30f, 40f),
        )
        val selected = normalizeTeacherReviewMarkGroupMetadata(listOf(fromA, fromB))
        assertEquals(fromB.anchor, selected.single().anchor)
        assertEquals(
            teacherReviewMarkGroupMetadataSha256(selected),
            teacherReviewMarkGroupMetadataSha256(
                normalizeTeacherReviewMarkGroupMetadata(listOf(fromB, fromA)),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            teacherReviewMarkGroupMetadataSha256(listOf(selected.single(), selected.single()))
        }
    }

    @Test fun duplicateRepairMetadataComparatorIgnoresMarksButDetectsNewerGlobalState() {
        val incoming = markGroup("shared", "teacher_book", 13, MarkColor.BLUE)
        val sameMetadataDifferentAttempt = incoming.copy(
            bookId = "student_book",
            pageNumber = 999,
            marks = listOf(Mark(8, MarkColor.RED, gradedAtEpochMillis = 9_000L)),
        )
        val newer = incoming.copy(
            anchor = PagePoint(90f, 80f),
            syncRevision = incoming.syncRevision + 1L,
            lastModifiedByDeviceId = "teacher_device_b",
        )

        assertEquals(
            0,
            compareTeacherReviewMarkGroupMetadataGlobalOrder(
                incoming,
                sameMetadataDifferentAttempt,
            ),
        )
        assertTrue(compareTeacherReviewMarkGroupMetadataGlobalOrder(newer, incoming) > 0)
        assertTrue(compareTeacherReviewMarkGroupMetadataGlobalOrder(incoming, newer) < 0)
        assertThrows(IllegalArgumentException::class.java) {
            compareTeacherReviewMarkGroupMetadataGlobalOrder(
                incoming,
                incoming.copy(id = "another"),
            )
        }
    }

    private fun entry(
        attemptNo: Int,
        publicationByte: String,
        layerByte: String,
        gradeByte: String,
    ) = TeacherReviewStateEvidence(
        attemptNo = attemptNo,
        publicationId = publicationByte.repeat(32),
        resultLayerSha256 = layerByte.repeat(32),
        markGroupsSha256 = gradeByte.repeat(32),
    )

    private fun markGroup(
        id: String,
        bookId: String,
        pageNumber: Int,
        color: MarkColor,
    ) = MarkGroup(
        id = id,
        bookId = bookId,
        pageNumber = pageNumber,
        anchor = PagePoint(12f, 34f, 0.75f),
        marks = listOf(Mark(attemptNo = 2, color = color, gradedAtEpochMillis = 700L)),
        createdAtEpochMillis = 600L,
        syncRevision = 4L,
        lastModifiedByDeviceId = "teacher_device",
    )
}
