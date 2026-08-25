package com.studyink.library.data

import com.studyink.core.model.Attempt
import com.studyink.core.model.Mark
import com.studyink.core.model.MarkColor
import com.studyink.core.model.MarkGroup
import com.studyink.core.model.PagePoint
import com.studyink.core.model.TEACHER_PAGE_REVIEW_ATTEMPT_NO
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TeacherGradeDraftCommitTest {
    @Test
    fun insertsExactMarkerAndReplayIsCompleteNoOp() {
        val inserted = commit(emptyList())

        assertTrue(inserted.changed)
        assertEquals(1, inserted.markGroups.size)
        assertEquals(GROUP_ID, inserted.committedGroup.id)
        assertEquals(BOOK_ID, inserted.committedGroup.bookId)
        assertEquals(PAGE_NUMBER, inserted.committedGroup.pageNumber)
        assertEquals(ANCHOR, inserted.committedGroup.anchor)
        assertEquals(CREATED_AT, inserted.committedGroup.createdAtEpochMillis)
        assertEquals(1L, inserted.committedGroup.syncRevision)
        assertEquals(DEVICE_ID, inserted.committedGroup.lastModifiedByDeviceId)
        assertEquals(
            Mark(ATTEMPT_NO, MarkColor.RED, UPDATED_AT),
            inserted.committedGroup.marks.single(),
        )

        val replay = commit(inserted.markGroups)

        assertFalse(replay.changed)
        assertSame(inserted.markGroups, replay.markGroups)
        assertSame(inserted.committedGroup, replay.committedGroup)
        assertEquals(1L, replay.committedGroup.syncRevision)
    }

    @Test
    fun appendDoesNotDuplicateExactMarkerButDifferentTimestampIsNewHistory() {
        val original = group(
            marks = listOf(Mark(ATTEMPT_NO, MarkColor.RED, UPDATED_AT)),
            revision = 7L,
        )

        val moved = commit(listOf(original), anchor = PagePoint(300f, 400f))

        assertTrue(moved.changed)
        assertEquals(1, moved.committedGroup.marks.size)
        assertEquals(8L, moved.committedGroup.syncRevision)
        val movedReplay = commit(moved.markGroups, anchor = PagePoint(300f, 400f))
        assertFalse(movedReplay.changed)
        assertEquals(8L, movedReplay.committedGroup.syncRevision)

        val newerMarker = commit(
            moved.markGroups,
            anchor = PagePoint(300f, 400f),
            updatedAt = UPDATED_AT + 1L,
        )
        assertTrue(newerMarker.changed)
        assertEquals(2, newerMarker.committedGroup.marks.size)
        assertEquals(UPDATED_AT + 1L, newerMarker.committedGroup.marks.last().gradedAtEpochMillis)
        assertEquals(9L, newerMarker.committedGroup.syncRevision)
    }

    @Test
    fun editModeChangesOnlyLatestVisibleAttemptColorAndPreservesItsTimestamp() {
        val hiddenLatest = Mark(
            attemptNo = ATTEMPT_NO,
            color = MarkColor.BLUE,
            gradedAtEpochMillis = 40L,
            hiddenAtEpochMillis = 50L,
        )
        val originalMarks = listOf(
            Mark(ATTEMPT_NO, MarkColor.BLUE, 10L),
            Mark(2, MarkColor.GRAY, 15L),
            Mark(ATTEMPT_NO, MarkColor.RED, 20L),
            hiddenLatest,
        )
        val original = group(marks = originalMarks, revision = 4L)

        val edited = commit(
            listOf(original),
            color = MarkColor.GRAY,
            appendMark = false,
        )

        assertTrue(edited.changed)
        assertEquals(4, edited.committedGroup.marks.size)
        assertEquals(originalMarks[0], edited.committedGroup.marks[0])
        assertEquals(originalMarks[1], edited.committedGroup.marks[1])
        assertEquals(Mark(ATTEMPT_NO, MarkColor.GRAY, 20L), edited.committedGroup.marks[2])
        assertEquals(hiddenLatest, edited.committedGroup.marks[3])
        assertEquals(5L, edited.committedGroup.syncRevision)

        val replay = commit(
            edited.markGroups,
            color = MarkColor.GRAY,
            appendMark = false,
        )
        assertFalse(replay.changed)
        assertEquals(5L, replay.committedGroup.syncRevision)
    }

    @Test
    fun editModeAddsExactMarkerWhenAttemptHasNoVisibleMark() {
        val original = group(
            marks = listOf(Mark(2, MarkColor.BLUE, 10L)),
            revision = 2L,
        )

        val edited = commit(listOf(original), appendMark = false)

        assertTrue(edited.changed)
        assertEquals(2, edited.committedGroup.marks.size)
        assertEquals(Mark(ATTEMPT_NO, MarkColor.RED, UPDATED_AT), edited.committedGroup.marks.last())
        assertEquals(3L, edited.committedGroup.syncRevision)
    }

    @Test
    fun hiddenTombstoneAndUnhideEachAdvanceOnceAndReplayDoesNot() {
        val visible = group(
            marks = listOf(Mark(ATTEMPT_NO, MarkColor.RED, UPDATED_AT)),
            revision = 9L,
        )

        val hidden = commit(listOf(visible), hidden = true)

        assertTrue(hidden.changed)
        assertEquals(UPDATED_AT, hidden.committedGroup.hiddenAtEpochMillis)
        assertEquals(10L, hidden.committedGroup.syncRevision)
        assertFalse(commit(hidden.markGroups, hidden = true).changed)

        val unhidden = commit(hidden.markGroups, hidden = false, updatedAt = UPDATED_AT + 1L)
        assertTrue(unhidden.changed)
        assertEquals(null, unhidden.committedGroup.hiddenAtEpochMillis)
        assertEquals(11L, unhidden.committedGroup.syncRevision)
        assertFalse(
            commit(unhidden.markGroups, hidden = false, updatedAt = UPDATED_AT + 1L).changed,
        )
    }

    @Test
    fun validatesExactBookPageAndAttemptBeforeChangingAnything() {
        val wrongBook = group().copy(bookId = "another-book")
        val wrongPage = group().copy(pageNumber = PAGE_NUMBER + 1)
        val pageLevel = group(
            marks = listOf(Mark(TEACHER_PAGE_REVIEW_ATTEMPT_NO, MarkColor.BLUE, 1L)),
        )

        assertThrows(IllegalArgumentException::class.java) { commit(listOf(wrongBook)) }
        assertThrows(IllegalArgumentException::class.java) { commit(listOf(wrongPage)) }
        assertThrows(IllegalArgumentException::class.java) { commit(listOf(pageLevel)) }
        assertThrows(IllegalArgumentException::class.java) {
            commit(emptyList(), attempts = listOf(Attempt(BOOK_ID, PAGE_NUMBER + 1, ATTEMPT_NO)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            commit(emptyList(), pageNumber = PAGE_COUNT)
        }
    }

    @Test
    fun pageLevelReviewUsesReservedAttemptWithoutStudentAttemptRecord() {
        val result = commit(
            markGroups = emptyList(),
            attempts = emptyList(),
            attemptNo = TEACHER_PAGE_REVIEW_ATTEMPT_NO,
        )

        assertTrue(result.changed)
        assertEquals(TEACHER_PAGE_REVIEW_ATTEMPT_NO, result.committedGroup.marks.single().attemptNo)
    }

    @Test
    fun unchangedGroupAtMaximumRevisionCanReplayButRealMutationFails() {
        val exact = group(
            marks = listOf(Mark(ATTEMPT_NO, MarkColor.RED, UPDATED_AT)),
            revision = Long.MAX_VALUE,
        )

        assertFalse(commit(listOf(exact)).changed)
        assertThrows(IllegalStateException::class.java) {
            commit(listOf(exact), anchor = PagePoint(500f, 600f))
        }
    }

    @Test
    fun rejectsInvalidIdentityTimeAndGeometry() {
        assertThrows(IllegalArgumentException::class.java) {
            commit(emptyList(), groupId = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            commit(emptyList(), createdAt = UPDATED_AT + 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            commit(emptyList(), anchor = PagePoint(Float.NaN, 1f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            commit(emptyList(), anchor = PagePoint(1f, 1f, pressure = 1.1f))
        }
    }

    @Test
    fun changedResultInstallsPersistsAndEmitsExactlyOnce() {
        val result = commit(emptyList())
        var installs = 0
        var persists = 0
        var emits = 0
        var installed: List<MarkGroup>? = null

        val committed = applyTeacherGradeDraftCommit(
            result = result,
            install = { groups ->
                installs += 1
                installed = groups
            },
            rollback = { error("rollback must not run") },
            persist = { persists += 1 },
            emit = { group ->
                emits += 1
                assertSame(result.committedGroup, group)
            },
        )

        assertSame(result.committedGroup, committed)
        assertSame(result.markGroups, installed)
        assertEquals(1, installs)
        assertEquals(1, persists)
        assertEquals(1, emits)
    }

    @Test
    fun noOpResultSkipsInstallPersistenceAndEmission() {
        val inserted = commit(emptyList())
        val replay = commit(inserted.markGroups)
        var sideEffects = 0

        val committed = applyTeacherGradeDraftCommit(
            result = replay,
            install = { sideEffects += 1 },
            rollback = { sideEffects += 1 },
            persist = { sideEffects += 1 },
            emit = { sideEffects += 1 },
        )

        assertSame(replay.committedGroup, committed)
        assertEquals(0, sideEffects)
    }

    @Test
    fun persistenceFailureRollsBackAndNeverEmits() {
        val result = commit(emptyList())
        var installs = 0
        var rollbacks = 0
        var emits = 0

        assertThrows(IllegalStateException::class.java) {
            applyTeacherGradeDraftCommit(
                result = result,
                install = { installs += 1 },
                rollback = { rollbacks += 1 },
                persist = { error("disk full") },
                emit = { emits += 1 },
            )
        }

        assertEquals(1, installs)
        assertEquals(1, rollbacks)
        assertEquals(0, emits)
    }

    @Test
    fun catalogRoundTripPreservesAnchorPressureAndReadsLegacyAnchor() {
        val original = group().copy(anchor = PagePoint(123f, 456f, pressure = 0.37f))
        val encoded = original.toCatalogJson()

        assertEquals(original, encoded.toCatalogMarkGroup())

        encoded.put("anchor", JSONArray().put(123f).put(456f))
        assertEquals(PagePoint(123f, 456f, pressure = 1f), encoded.toCatalogMarkGroup().anchor)
    }

    private fun commit(
        markGroups: List<MarkGroup>,
        attempts: List<Attempt> = attempts(),
        pageNumber: Int = PAGE_NUMBER,
        attemptNo: Int = ATTEMPT_NO,
        groupId: String = GROUP_ID,
        anchor: PagePoint = ANCHOR,
        color: MarkColor = MarkColor.RED,
        hidden: Boolean = false,
        appendMark: Boolean = true,
        createdAt: Long = CREATED_AT,
        updatedAt: Long = UPDATED_AT,
    ): TeacherGradeDraftCommitResult = mergeTeacherGradeDraftCommit(
        markGroups = markGroups,
        attempts = attempts,
        bookId = BOOK_ID,
        pageNumber = pageNumber,
        pageCount = PAGE_COUNT,
        attemptNo = attemptNo,
        groupId = groupId,
        anchor = anchor,
        color = color,
        hidden = hidden,
        appendMark = appendMark,
        createdAtEpochMillis = createdAt,
        updatedAtEpochMillis = updatedAt,
        deviceId = DEVICE_ID,
    )

    private fun group(
        marks: List<Mark> = listOf(Mark(ATTEMPT_NO, MarkColor.BLUE, 10L)),
        revision: Long = 1L,
    ) = MarkGroup(
        id = GROUP_ID,
        bookId = BOOK_ID,
        pageNumber = PAGE_NUMBER,
        anchor = ANCHOR,
        marks = marks,
        createdAtEpochMillis = CREATED_AT,
        syncRevision = revision,
        lastModifiedByDeviceId = "previous-device",
    )

    private fun attempts() = listOf(
        Attempt(BOOK_ID, PAGE_NUMBER, ATTEMPT_NO),
        Attempt(BOOK_ID, PAGE_NUMBER, 2),
    )

    private companion object {
        const val BOOK_ID = "book-1"
        const val PAGE_NUMBER = 2
        const val PAGE_COUNT = 10
        const val ATTEMPT_NO = 1
        const val GROUP_ID = "grade-group-1"
        const val DEVICE_ID = "teacher-device"
        const val CREATED_AT = 100L
        const val UPDATED_AT = 200L
        val ANCHOR = PagePoint(100f, 200f, 0.5f)
    }
}
