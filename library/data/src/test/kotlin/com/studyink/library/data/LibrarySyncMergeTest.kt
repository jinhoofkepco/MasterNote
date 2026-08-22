package com.studyink.library.data

import com.studyink.core.model.Attempt
import com.studyink.core.model.Mark
import com.studyink.core.model.MarkColor
import com.studyink.core.model.MarkGroup
import com.studyink.core.model.PagePoint
import com.studyink.core.model.TEACHER_PAGE_REVIEW_ATTEMPT_NO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySyncMergeTest {
    @Test
    fun attemptUpsertIsIdempotentAndLockCannotRegress() {
        val open = Attempt(BOOK, pageNumber = 2, attemptNo = 1, startedAtEpochMillis = 10L)
        val inserted = mergeRemoteAttempt(emptyList(), BOOK, 2, PAGE_COUNT, open)
        assertTrue(inserted.changed)

        val replay = mergeRemoteAttempt(inserted.attempts, BOOK, 2, PAGE_COUNT, open)
        assertFalse(replay.changed)

        val locked = open.copy(locked = true, lockedAtEpochMillis = 20L)
        val upgraded = mergeRemoteAttempt(replay.attempts, BOOK, 2, PAGE_COUNT, locked)
        assertTrue(upgraded.changed)
        assertTrue(upgraded.attempts.single().locked)

        val staleReplay = mergeRemoteAttempt(upgraded.attempts, BOOK, 2, PAGE_COUNT, open)
        assertFalse(staleReplay.changed)
        assertTrue(staleReplay.attempts.single().locked)
    }

    @Test
    fun attemptUpsertRejectsReservedOrWrongScope() {
        val pageTarget = Attempt(BOOK, 2, TEACHER_PAGE_REVIEW_ATTEMPT_NO)
        assertThrows(IllegalArgumentException::class.java) {
            mergeRemoteAttempt(emptyList(), BOOK, 2, PAGE_COUNT, pageTarget)
        }
        assertThrows(IllegalArgumentException::class.java) {
            mergeRemoteAttempt(
                emptyList(),
                BOOK,
                2,
                PAGE_COUNT,
                Attempt("another-book", 2, 1),
            )
        }
    }

    @Test
    fun pageLevelMarkUpsertReplaysAndReplacesByIdentity() {
        val group = pageLevelGroup(anchor = PagePoint(120f, 240f))
        val inserted = mergeRemoteMarkGroup(
            emptyList(), BOOK, 2, PAGE_COUNT, emptyList(), group,
        )
        assertTrue(inserted.changed)

        val replay = mergeRemoteMarkGroup(
            inserted.markGroups, BOOK, 2, PAGE_COUNT, emptyList(), group,
        )
        assertFalse(replay.changed)

        val moved = group.copy(anchor = PagePoint(180f, 260f), syncRevision = 2L)
        val replaced = mergeRemoteMarkGroup(
            replay.markGroups, BOOK, 2, PAGE_COUNT, emptyList(), moved,
        )
        assertTrue(replaced.changed)
        assertEquals(moved, replaced.markGroups.single())
    }

    @Test
    fun concurrentMarkUpsertsConvergeWithDeviceTieBreak() {
        val fromA = pageLevelGroup().copy(
            anchor = PagePoint(200f, 300f),
            syncRevision = 4L,
            lastModifiedByDeviceId = "device-a",
        )
        val fromB = fromA.copy(
            anchor = PagePoint(400f, 500f),
            lastModifiedByDeviceId = "device-b",
        )

        val aReceivesB = mergeRemoteMarkGroup(
            listOf(fromA), BOOK, 2, PAGE_COUNT, emptyList(), fromB,
        )
        val bReceivesA = mergeRemoteMarkGroup(
            listOf(fromB), BOOK, 2, PAGE_COUNT, emptyList(), fromA,
        )

        assertEquals(fromB, aReceivesB.markGroups.single())
        assertEquals(fromB, bReceivesA.markGroups.single())
    }

    @Test
    fun markUpsertRejectsWrongScopeAndMixedPageTarget() {
        val attempt = Attempt(BOOK, 2, 1)
        val mixed = pageLevelGroup().copy(
            marks = listOf(
                Mark(TEACHER_PAGE_REVIEW_ATTEMPT_NO, MarkColor.BLUE),
                Mark(1, MarkColor.RED),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            mergeRemoteMarkGroup(emptyList(), BOOK, 2, PAGE_COUNT, listOf(attempt), mixed)
        }
        assertThrows(IllegalArgumentException::class.java) {
            mergeRemoteMarkGroup(
                emptyList(),
                BOOK,
                2,
                PAGE_COUNT,
                listOf(attempt),
                pageLevelGroup().copy(bookId = "another-book"),
            )
        }
    }

    private fun pageLevelGroup(anchor: PagePoint = PagePoint(100f, 200f)) = MarkGroup(
        id = "mark-1",
        bookId = BOOK,
        pageNumber = 2,
        anchor = anchor,
        marks = listOf(Mark(TEACHER_PAGE_REVIEW_ATTEMPT_NO, MarkColor.BLUE)),
        createdAtEpochMillis = 1L,
        syncRevision = 1L,
        lastModifiedByDeviceId = "device-a",
    )

    private companion object {
        const val BOOK = "book-1"
        const val PAGE_COUNT = 10
    }
}
