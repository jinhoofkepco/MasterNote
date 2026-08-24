package com.studyink.reader

import com.studyink.core.model.Mark
import com.studyink.core.model.MarkColor
import com.studyink.core.model.MarkGroup
import com.studyink.core.model.PagePoint
import com.studyink.core.model.TEACHER_PAGE_REVIEW_ATTEMPT_NO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderAttemptMarkHistoryTest {
    @Test
    fun eightProblemsUseApprovedFourByTwoBundle() {
        val grid = readerAttemptSummaryGrid(problemCount = 8)

        assertEquals(4, grid.columns)
        assertEquals(2, grid.rows)
    }

    @Test
    fun topSummaryUsesTheSameShapeAsProblemBundlesForEveryCount() {
        (1..200).forEach { problemCount ->
            assertEquals(
                com.studyink.core.model.resultBundleGrid(problemCount),
                readerAttemptSummaryGrid(problemCount),
            )
        }
    }

    @Test
    fun sixAndNineProblemsKeepTheSharedCompactShapes() {
        assertEquals(3 to 2, readerAttemptSummaryGrid(6).let { it.columns to it.rows })
        assertEquals(4 to 3, readerAttemptSummaryGrid(9).let { it.columns to it.rows })
    }

    @Test
    fun arbitraryProblemCountsRemainCompactAndHaveEnoughCells() {
        (1..200).forEach { problemCount ->
            val grid = readerAttemptSummaryGrid(problemCount)

            assertTrue(grid.columns * grid.rows >= problemCount)
            assertTrue(grid.columns >= grid.rows)
        }
    }

    @Test
    fun bundlesSortProblemsByAnchorYThenXAndUseGrayWhenAttemptHasNoMark() {
        val groups = listOf(
            group("bottom", x = 0f, y = 20f, Mark(2, MarkColor.BLUE)),
            group("top-right", x = 20f, y = 0f, Mark(1, MarkColor.RED)),
            group(
                "top-left",
                x = 0f,
                y = 0f,
                Mark(1, MarkColor.BLUE),
                Mark(2, MarkColor.RED),
            ),
        )

        val bundles = readerAttemptMarkBundles(
            groups = groups,
            pageNumber = 4,
            selectedAttemptNo = 2,
        )

        assertEquals(listOf(1, 2), bundles.map { it.attemptNo })
        assertEquals(
            listOf(MarkColor.BLUE, MarkColor.RED, MarkColor.GRAY),
            bundles[0].colors,
        )
        assertEquals(
            listOf(MarkColor.RED, MarkColor.GRAY, MarkColor.BLUE),
            bundles[1].colors,
        )
    }

    @Test
    fun teacherPageTargetNeverMixesWithStudentAttempts() {
        val mixed = group(
            "mixed",
            x = 0f,
            y = 0f,
            Mark(TEACHER_PAGE_REVIEW_ATTEMPT_NO, MarkColor.RED),
            Mark(1, MarkColor.BLUE),
        )
        val studentOnly = group("student", x = 10f, y = 0f, Mark(1, MarkColor.RED))

        val pageLevel = readerAttemptMarkBundles(
            groups = listOf(studentOnly, mixed),
            pageNumber = 4,
            selectedAttemptNo = TEACHER_PAGE_REVIEW_ATTEMPT_NO,
        )
        val studentAttempt = readerAttemptMarkBundles(
            groups = listOf(studentOnly, mixed),
            pageNumber = 4,
            selectedAttemptNo = 1,
        )

        assertEquals(listOf(TEACHER_PAGE_REVIEW_ATTEMPT_NO), pageLevel.map { it.attemptNo })
        assertEquals(listOf(MarkColor.RED), pageLevel.single().colors)
        assertEquals(listOf(1), studentAttempt.map { it.attemptNo })
        assertEquals(listOf(MarkColor.BLUE, MarkColor.RED), studentAttempt.single().colors)
    }

    private fun group(
        id: String,
        x: Float,
        y: Float,
        vararg marks: Mark,
    ) = MarkGroup(
        id = id,
        bookId = "book",
        pageNumber = 4,
        anchor = PagePoint(x, y),
        marks = marks.toList(),
    )

    @Test
    fun everySubmissionKeepsAFrameEvenBeforeItIsGraded() {
        val groups = listOf(
            group("only", x = 0f, y = 0f, Mark(1, MarkColor.BLUE)),
        )

        // Attempts 2 and 3 are submitted but not graded yet. Deriving the stack from marks alone
        // dropped them, which left a teacher able to reach only the newest submission.
        val bundles = readerAttemptMarkBundles(
            groups = groups,
            pageNumber = 4,
            selectedAttemptNo = 1,
            attemptNos = listOf(1, 2, 3),
        )

        assertEquals(listOf(1, 2, 3), bundles.map { it.attemptNo })
        assertEquals(listOf(MarkColor.BLUE), bundles[0].colors)
        assertEquals(listOf(MarkColor.GRAY), bundles[1].colors)
        assertEquals(listOf(MarkColor.GRAY), bundles[2].colors)
    }

    @Test
    fun pageLevelTeacherTargetIgnoresStudentSubmissionNumbers() {
        val groups = listOf(
            group("page", x = 0f, y = 0f, Mark(TEACHER_PAGE_REVIEW_ATTEMPT_NO, MarkColor.RED)),
        )

        val bundles = readerAttemptMarkBundles(
            groups = groups,
            pageNumber = 4,
            selectedAttemptNo = TEACHER_PAGE_REVIEW_ATTEMPT_NO,
            attemptNos = listOf(1, 2, 3),
        )

        assertEquals(listOf(TEACHER_PAGE_REVIEW_ATTEMPT_NO), bundles.map { it.attemptNo })
    }

    @Test
    fun submissionsWithNoGradingYetStillGetTheirOwnFrame() {
        // Page 3 of the test book: four submissions, nothing graded. Bailing out on an empty mark
        // list left the teacher with no stack at all on exactly the pages that need grading most.
        val bundles = readerAttemptMarkBundles(
            groups = emptyList(),
            pageNumber = 3,
            selectedAttemptNo = 4,
            attemptNos = listOf(1, 2, 3, 4),
        )

        assertEquals(listOf(1, 2, 3, 4), bundles.map { it.attemptNo })
        assertTrue(bundles.all { it.colors.isEmpty() })
    }

    @Test
    fun aPageWithNeitherMarksNorSubmissionsStaysEmpty() {
        assertTrue(
            readerAttemptMarkBundles(
                groups = emptyList(),
                pageNumber = 3,
                selectedAttemptNo = 1,
                attemptNos = emptyList(),
            ).isEmpty()
        )
    }
}
