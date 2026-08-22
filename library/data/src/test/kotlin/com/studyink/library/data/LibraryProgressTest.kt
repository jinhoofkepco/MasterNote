package com.studyink.library.data

import com.studyink.core.model.Attempt
import com.studyink.core.model.Mark
import com.studyink.core.model.MarkColor
import com.studyink.core.model.MarkGroup
import com.studyink.core.model.PagePoint
import com.studyink.core.model.TEACHER_PAGE_REVIEW_ATTEMPT_NO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryProgressTest {
    @Test
    fun projectsEveryBookPageAndKeepsReaderPageIndices() {
        val progress = projectBookPageProgress(pageCount = 3, attempts = emptyList(), markGroups = emptyList())

        assertEquals(listOf(0, 1, 2), progress.map(PageProgressSummary::pageNumber))
        assertTrue(progress.all { it.status == PageProgressStatus.NOT_STARTED })
        assertTrue(progress.all { it.attempts.isEmpty() })
        assertNull(progress.first().latestActivityAtEpochMillis)
    }

    @Test
    fun latestAttemptDeterminesThePageWorkflowState() {
        val attempts = listOf(
            attempt(no = 1, locked = true, startedAt = 100, lockedAt = 200),
            attempt(no = 2, locked = false, startedAt = 300),
        )
        val marks = listOf(group(attemptNo = 1, gradedAt = 250))

        val summary = projectBookPageProgress(1, attempts, marks).single()

        assertEquals(PageProgressStatus.IN_PROGRESS, summary.status)
        assertEquals(2, summary.attemptCount)
        assertEquals(1, summary.submittedAttemptCount)
        assertEquals(1, summary.markCount)
        assertTrue(summary.hasReviewActivity)
        assertEquals(2, summary.latestAttemptNo)
        assertEquals(300L, summary.latestActivityAtEpochMillis)
        assertEquals(1, summary.latestSubmittedAttemptNo)
        assertEquals(PageProgressStatus.REVIEW_IN_PROGRESS, summary.teacherStatus)
    }

    @Test
    fun newerDraftDoesNotHideEarlierSubmissionFromTeacher() {
        val summary = projectBookPageProgress(
            pageCount = 1,
            attempts = listOf(
                attempt(no = 1, locked = true, startedAt = 100, lockedAt = 200),
                attempt(no = 2, locked = false, startedAt = 300),
            ),
            markGroups = emptyList(),
        ).single()

        assertEquals(PageProgressStatus.IN_PROGRESS, summary.statusFor(LibraryPerspective.STUDENT))
        assertEquals(PageProgressStatus.SUBMITTED, summary.statusFor(LibraryPerspective.TEACHER))
        assertEquals(1, summary.latestSubmittedAttemptNo)
    }

    @Test
    fun lockedAttemptWithoutMarksIsOnlySubmitted() {
        val summary = projectBookPageProgress(
            pageCount = 1,
            attempts = listOf(attempt(no = 1, locked = true, startedAt = 100, lockedAt = 200)),
            markGroups = emptyList(),
        ).single()

        assertEquals(PageProgressStatus.SUBMITTED, summary.status)
        assertEquals(AttemptProgressStatus.SUBMITTED, summary.attempts.single().status)
        assertFalse(summary.hasReviewActivity)
    }

    @Test
    fun marksOnAnOlderAttemptDoNotCompleteTheLatestSubmission() {
        val summary = projectBookPageProgress(
            pageCount = 1,
            attempts = listOf(
                attempt(no = 1, locked = true, startedAt = 100, lockedAt = 200),
                attempt(no = 2, locked = true, startedAt = 300, lockedAt = 400),
            ),
            markGroups = listOf(group(attemptNo = 1, gradedAt = 250)),
        ).single()

        assertEquals(PageProgressStatus.SUBMITTED, summary.status)
        assertEquals(AttemptProgressStatus.REVIEW_IN_PROGRESS, summary.attempts[0].status)
        assertEquals(AttemptProgressStatus.SUBMITTED, summary.attempts[1].status)
    }

    @Test
    fun visibleMarkMeansReviewActivityButNeverPublishedFeedback() {
        val summary = projectBookPageProgress(
            pageCount = 1,
            attempts = listOf(attempt(no = 1, locked = true, startedAt = 100, lockedAt = 200)),
            markGroups = listOf(
                group(attemptNo = 1, gradedAt = 350),
                group(attemptNo = 1, gradedAt = 400, markHiddenAt = 410),
                group(attemptNo = 1, gradedAt = 450, groupHiddenAt = 460),
            ),
        ).single()

        assertEquals(PageProgressStatus.REVIEW_IN_PROGRESS, summary.status)
        assertEquals(AttemptProgressStatus.REVIEW_IN_PROGRESS, summary.attempts.single().status)
        assertEquals(1, summary.markCount)
        assertEquals(350L, summary.latestActivityAtEpochMillis)
    }

    @Test
    fun pageLevelTeacherMarkDoesNotStartAStudentAttempt() {
        val summary = projectBookPageProgress(
            pageCount = 1,
            attempts = emptyList(),
            markGroups = listOf(group(attemptNo = TEACHER_PAGE_REVIEW_ATTEMPT_NO, gradedAt = 350)),
        ).single()

        assertEquals(PageProgressStatus.NOT_STARTED, summary.statusFor(LibraryPerspective.STUDENT))
        assertEquals(PageProgressStatus.TEACHER_MARKED, summary.statusFor(LibraryPerspective.TEACHER))
        assertEquals(0, summary.attemptCount)
        assertEquals(0, summary.submittedAttemptCount)
        assertEquals(0, summary.markCount)
        assertEquals(1, summary.pageLevelTeacherMarkCount)
        assertTrue(summary.hasReviewActivity)
        assertEquals(350L, summary.latestActivityAtEpochMillis)
    }

    @Test
    fun firstStudentAttemptStaysSeparateFromPageLevelTeacherMarks() {
        val summary = projectBookPageProgress(
            pageCount = 1,
            attempts = listOf(attempt(no = 1, locked = false, startedAt = 400)),
            markGroups = listOf(group(attemptNo = TEACHER_PAGE_REVIEW_ATTEMPT_NO, gradedAt = 350)),
        ).single()

        assertEquals(PageProgressStatus.IN_PROGRESS, summary.statusFor(LibraryPerspective.STUDENT))
        assertEquals(PageProgressStatus.TEACHER_MARKED, summary.statusFor(LibraryPerspective.TEACHER))
        assertEquals(1, summary.latestAttemptNo)
        assertNull(summary.latestSubmittedAttemptNo)
        assertEquals(1, summary.attemptCount)
        assertEquals(0, summary.markCount)
        assertEquals(1, summary.pageLevelTeacherMarkCount)
    }

    @Test
    fun realSubmissionTakesPriorityOverPageLevelTeacherMarks() {
        val summary = projectBookPageProgress(
            pageCount = 1,
            attempts = listOf(attempt(no = 1, locked = true, startedAt = 400, lockedAt = 500)),
            markGroups = listOf(group(attemptNo = TEACHER_PAGE_REVIEW_ATTEMPT_NO, gradedAt = 350)),
        ).single()

        assertEquals(PageProgressStatus.SUBMITTED, summary.statusFor(LibraryPerspective.TEACHER))
        assertEquals(1, summary.latestSubmittedAttemptNo)
        assertEquals(1, summary.pageLevelTeacherMarkCount)
    }

    @Test
    fun projectsProblemColorsAndUsesNeutralCellsForMissingCurrentAttemptGrades() {
        val summary = projectBookPageProgress(
            pageCount = 1,
            attempts = listOf(
                attempt(no = 1, locked = true, startedAt = 100, lockedAt = 200),
                attempt(no = 2, locked = false, startedAt = 300),
            ),
            markGroups = listOf(
                group(
                    id = "second-on-paper",
                    attemptNo = 1,
                    color = MarkColor.RED,
                    gradedAt = 220,
                    anchor = PagePoint(100f, 200f),
                ),
                MarkGroup(
                    id = "first-on-paper",
                    bookId = BOOK_ID,
                    pageNumber = 0,
                    anchor = PagePoint(100f, 100f),
                    marks = listOf(
                        Mark(1, MarkColor.RED, gradedAtEpochMillis = 210),
                        Mark(2, MarkColor.BLUE, gradedAtEpochMillis = 310),
                    ),
                ),
            ),
        ).single()

        assertEquals(listOf("first-on-paper", "second-on-paper"), summary.problemGrades.map { it.groupId })
        val snapshot = summary.gradeSnapshotFor(LibraryPerspective.STUDENT)!!
        assertEquals(2, snapshot.attemptNo)
        assertEquals(listOf(MarkColor.BLUE, MarkColor.GRAY), snapshot.cells.map { it.color })
        assertEquals(listOf(MarkColor.RED), snapshot.cells[0].previousColors)
        assertEquals(listOf(MarkColor.RED), snapshot.cells[1].previousColors)
        assertEquals(1, snapshot.correctCount)
        assertEquals(0, snapshot.wrongCount)
        assertEquals(1, snapshot.unansweredCount)
    }

    @Test
    fun teacherSnapshotUsesLatestSubmissionAndKeepsPageLevelReviewSeparate() {
        val summary = projectBookPageProgress(
            pageCount = 1,
            attempts = listOf(
                attempt(no = 1, locked = true, startedAt = 100, lockedAt = 200),
                attempt(no = 2, locked = false, startedAt = 300),
            ),
            markGroups = listOf(
                group(id = "student", attemptNo = 1, color = MarkColor.RED, gradedAt = 210),
                group(
                    id = "page-note",
                    attemptNo = TEACHER_PAGE_REVIEW_ATTEMPT_NO,
                    color = MarkColor.BLUE,
                    gradedAt = 90,
                ),
            ),
        ).single()

        val snapshot = summary.gradeSnapshotFor(LibraryPerspective.TEACHER)!!
        assertEquals(1, snapshot.attemptNo)
        assertFalse(snapshot.pageLevel)
        assertEquals(listOf("student"), snapshot.cells.map { it.groupId })
        assertEquals(0, snapshot.pageLevelCount)
        assertEquals(MarkColor.RED, snapshot.cells.single().color)
        assertFalse(snapshot.cells.single().pageLevel)
        assertEquals(1, summary.pageLevelTeacherMarkCount)
    }

    @Test
    fun markAttemptTargetRejectsOrphanPositiveAttemptNumbers() {
        val attempts = listOf(attempt(no = 1, locked = false, startedAt = 100))

        assertTrue(
            isValidMarkAttemptTarget(
                BOOK_ID,
                0,
                TEACHER_PAGE_REVIEW_ATTEMPT_NO,
                attempts,
            )
        )
        assertTrue(isValidMarkAttemptTarget(BOOK_ID, 0, 1, attempts))
        assertFalse(isValidMarkAttemptTarget(BOOK_ID, 0, 2, attempts))
        assertFalse(isValidMarkAttemptTarget("other-book", 0, 1, attempts))
        assertFalse(isValidMarkAttemptTarget(BOOK_ID, 1, 1, attempts))
    }

    @Test
    fun markGroupNeverMixesPageLevelAndStudentAttemptTargets() {
        val pageLevel = group(
            attemptNo = TEACHER_PAGE_REVIEW_ATTEMPT_NO,
            gradedAt = 100,
        )
        val studentAttempt = group(attemptNo = 1, gradedAt = 100)

        assertTrue(isCompatibleMarkGroupTarget(pageLevel, TEACHER_PAGE_REVIEW_ATTEMPT_NO))
        assertFalse(isCompatibleMarkGroupTarget(pageLevel, 1))
        assertTrue(isCompatibleMarkGroupTarget(studentAttempt, 2))
        assertFalse(isCompatibleMarkGroupTarget(studentAttempt, TEACHER_PAGE_REVIEW_ATTEMPT_NO))
    }

    private fun attempt(
        no: Int,
        locked: Boolean,
        startedAt: Long,
        lockedAt: Long? = null,
    ) = Attempt(
        bookId = BOOK_ID,
        pageNumber = 0,
        attemptNo = no,
        locked = locked,
        startedAtEpochMillis = startedAt,
        lockedAtEpochMillis = lockedAt,
    )

    private fun group(
        attemptNo: Int,
        gradedAt: Long,
        id: String = "group-$attemptNo-$gradedAt",
        color: MarkColor = MarkColor.BLUE,
        anchor: PagePoint = PagePoint(100f, 100f),
        markHiddenAt: Long? = null,
        groupHiddenAt: Long? = null,
    ) = MarkGroup(
        id = id,
        bookId = BOOK_ID,
        pageNumber = 0,
        anchor = anchor,
        marks = listOf(
            Mark(
                attemptNo = attemptNo,
                color = color,
                gradedAtEpochMillis = gradedAt,
                hiddenAtEpochMillis = markHiddenAt,
            )
        ),
        hiddenAtEpochMillis = groupHiddenAt,
    )

    private companion object {
        const val BOOK_ID = "book"
    }
}
