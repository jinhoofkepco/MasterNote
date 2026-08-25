package com.studyink.reader

import com.studyink.core.model.Mark
import com.studyink.core.model.MarkColor
import com.studyink.core.model.MarkGroup
import com.studyink.core.model.PagePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TeacherGradeDraftMergeTest {
    @Test
    fun newDraftAddsACompleteOverlayGroup() {
        val draft = draft(
            groupId = "new-group",
            anchor = point(20f),
            color = MarkColor.RED,
            createdAt = 100L,
            updatedAt = 120L,
        )

        val result = mergeTeacherGradeDraftMarks(emptyList(), listOf(draft))

        assertEquals(listOf(draft.toMarkGroup()), result)
    }

    @Test
    fun appendDraftOverlaysCommittedGroupWithoutLosingHistory() {
        val committed = group(
            id = "shared-group",
            anchor = point(10f),
            marks = listOf(mark(attemptNo = 1, color = MarkColor.BLUE, gradedAt = 80L)),
            syncRevision = 7L,
            lastModifiedByDeviceId = "student-device",
        )
        val draft = draft(
            groupId = committed.id,
            attemptNo = 2,
            anchor = point(30f),
            color = MarkColor.RED,
            appendOnCommit = true,
            updatedAt = 200L,
        )

        val result = mergeTeacherGradeDraftMarks(listOf(committed), listOf(draft)).single()

        assertEquals(point(30f), result.anchor)
        assertEquals(
            listOf(
                mark(attemptNo = 1, color = MarkColor.BLUE, gradedAt = 80L),
                mark(attemptNo = 2, color = MarkColor.RED, gradedAt = 200L),
            ),
            result.marks,
        )
        assertEquals(committed.createdAtEpochMillis, result.createdAtEpochMillis)
        assertEquals(7L, result.syncRevision)
        assertEquals("student-device", result.lastModifiedByDeviceId)
        assertNull(result.hiddenAtEpochMillis)
    }

    @Test
    fun editDraftReplacesOnlyLatestVisibleMarkForItsAttempt() {
        val earlierForTarget = mark(attemptNo = 2, color = MarkColor.BLUE, gradedAt = 100L)
        val otherAttempt = mark(attemptNo = 1, color = MarkColor.GRAY, gradedAt = 150L)
        val latestForTarget = mark(attemptNo = 2, color = MarkColor.GRAY, gradedAt = 180L)
        val hiddenLaterMark = mark(
            attemptNo = 2,
            color = MarkColor.BLUE,
            gradedAt = 190L,
            hiddenAt = 195L,
        )
        val committed = group(
            id = "edited-group",
            anchor = point(10f),
            marks = listOf(earlierForTarget, otherAttempt, latestForTarget, hiddenLaterMark),
        )
        val draft = draft(
            groupId = committed.id,
            attemptNo = 2,
            anchor = point(40f),
            color = MarkColor.RED,
            appendOnCommit = false,
            updatedAt = 220L,
        )

        val result = mergeTeacherGradeDraftMarks(listOf(committed), listOf(draft)).single()

        assertEquals(point(40f), result.anchor)
        assertEquals(
            listOf(
                earlierForTarget,
                otherAttempt,
                mark(attemptNo = 2, color = MarkColor.RED, gradedAt = 220L),
                hiddenLaterMark,
            ),
            result.marks,
        )
    }

    @Test
    fun hiddenDraftTombstoneMasksCommittedGroup() {
        val committed = group(
            id = "hidden-group",
            anchor = point(10f),
            marks = listOf(mark(attemptNo = 2, color = MarkColor.BLUE, gradedAt = 80L)),
        )
        val tombstone = draft(
            groupId = committed.id,
            attemptNo = 2,
            anchor = point(50f),
            color = MarkColor.BLUE,
            hidden = true,
            appendOnCommit = false,
            updatedAt = 300L,
        )

        val result = mergeTeacherGradeDraftMarks(listOf(committed), listOf(tombstone)).single()

        assertEquals(point(50f), result.anchor)
        assertEquals(300L, result.hiddenAtEpochMillis)
        assertEquals(committed.marks, result.marks)
    }

    @Test
    fun mergeUsesOnlyTheDraftsSuppliedForTheSelectedTarget() {
        val selectedTarget = target(bookId = "book-a", pageNumber = 5, attemptNo = 2)
        val otherTarget = target(bookId = "book-a", pageNumber = 6, attemptNo = 1)
        val selectedDraft = draft(
            groupId = "selected-group",
            target = selectedTarget,
            anchor = point(20f),
        )
        val unrelatedDraft = draft(
            groupId = "other-group",
            target = otherTarget,
            anchor = point(30f),
        )
        val untouchedCommitted = group(
            id = "committed-other-page",
            bookId = otherTarget.bookId,
            pageNumber = otherTarget.pageNumber,
            anchor = point(10f),
            marks = listOf(mark(attemptNo = 1, color = MarkColor.GRAY, gradedAt = 90L)),
        )
        val draftsForInvocation = listOf(selectedDraft, unrelatedDraft)
            .filter { it.target == selectedTarget }

        val result = mergeTeacherGradeDraftMarks(
            committed = listOf(untouchedCommitted),
            drafts = draftsForInvocation,
        )

        assertEquals(setOf(untouchedCommitted.id, selectedDraft.groupId), result.map { it.id }.toSet())
        assertSame(untouchedCommitted, result.single { it.id == untouchedCommitted.id })
        assertTrue(result.none { it.id == unrelatedDraft.groupId })
    }

    private fun target(
        bookId: String = "math-book",
        pageNumber: Int = 5,
        attemptNo: Int = 2,
    ) = TeacherGradeDraftTarget(bookId, pageNumber, attemptNo)

    private fun draft(
        groupId: String,
        target: TeacherGradeDraftTarget = target(),
        attemptNo: Int = target.attemptNo,
        anchor: PagePoint,
        color: MarkColor = MarkColor.BLUE,
        hidden: Boolean = false,
        appendOnCommit: Boolean = true,
        createdAt: Long = 100L,
        updatedAt: Long = 120L,
    ) = TeacherGradeDraft(
        draftId = "draft-$groupId-$updatedAt",
        groupId = groupId,
        target = target.copy(attemptNo = attemptNo),
        anchor = anchor,
        color = color,
        hidden = hidden,
        appendOnCommit = appendOnCommit,
        createdAtEpochMillis = createdAt,
        updatedAtEpochMillis = updatedAt,
    )

    private fun group(
        id: String,
        bookId: String = "math-book",
        pageNumber: Int = 5,
        anchor: PagePoint,
        marks: List<Mark>,
        syncRevision: Long = 0L,
        lastModifiedByDeviceId: String = "device",
    ) = MarkGroup(
        id = id,
        bookId = bookId,
        pageNumber = pageNumber,
        anchor = anchor,
        marks = marks,
        createdAtEpochMillis = 70L,
        syncRevision = syncRevision,
        lastModifiedByDeviceId = lastModifiedByDeviceId,
    )

    private fun mark(
        attemptNo: Int,
        color: MarkColor,
        gradedAt: Long,
        hiddenAt: Long? = null,
    ) = Mark(
        attemptNo = attemptNo,
        color = color,
        gradedAtEpochMillis = gradedAt,
        hiddenAtEpochMillis = hiddenAt,
    )

    private fun point(x: Float) = PagePoint(x = x, y = x * 10f, pressure = 0.5f)
}
