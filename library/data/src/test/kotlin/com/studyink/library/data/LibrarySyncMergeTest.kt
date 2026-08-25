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

    @Test
    fun exactAttemptMergeReplacesOnlyTargetAndUsesNewerGlobalMetadata() {
        val attemptOne = Mark(1, MarkColor.BLUE, gradedAtEpochMillis = 10L)
        val oldAttemptTwo = Mark(2, MarkColor.GRAY, gradedAtEpochMillis = 20L)
        val attemptThree = Mark(3, MarkColor.RED, gradedAtEpochMillis = 30L)
        val anotherOldAttemptTwo = Mark(2, MarkColor.BLUE, gradedAtEpochMillis = 35L)
        val existing = studentGroup(
            anchor = PagePoint(100f, 200f),
            marks = listOf(attemptOne, oldAttemptTwo, attemptThree, anotherOldAttemptTwo),
            revision = 4L,
            deviceId = "device-a",
            createdAt = 40L,
        )
        val replacement = listOf(
            Mark(2, MarkColor.RED, gradedAtEpochMillis = 50L),
            Mark(2, MarkColor.BLUE, gradedAtEpochMillis = 60L, hiddenAtEpochMillis = 70L),
        )
        val incoming = existing.copy(
            anchor = PagePoint(700f, 800f, pressure = 0.5f),
            marks = replacement,
            createdAtEpochMillis = 45L,
            hiddenAtEpochMillis = 90L,
            syncRevision = 5L,
            lastModifiedByDeviceId = "device-b",
        )

        val merged = mergeRemoteMarkGroupAttempt(
            markGroups = listOf(existing),
            bookId = BOOK,
            pageNumber = 2,
            pageCount = PAGE_COUNT,
            attempts = studentAttempts(1, 2, 3),
            attemptNo = 2,
            incoming = incoming,
        )

        assertTrue(merged.changed)
        assertEquals(
            listOf(attemptOne) + replacement + attemptThree,
            merged.markGroups.single().marks,
        )
        assertEquals(incoming.anchor, merged.markGroups.single().anchor)
        assertEquals(incoming.createdAtEpochMillis, merged.markGroups.single().createdAtEpochMillis)
        assertEquals(incoming.hiddenAtEpochMillis, merged.markGroups.single().hiddenAtEpochMillis)
        assertEquals(incoming.lastModifiedByDeviceId, merged.markGroups.single().lastModifiedByDeviceId)
        assertEquals(5L, merged.markGroups.single().syncRevision)

        val replay = mergeRemoteMarkGroupAttempt(
            markGroups = merged.markGroups,
            bookId = BOOK,
            pageNumber = 2,
            pageCount = PAGE_COUNT,
            attempts = studentAttempts(1, 2, 3),
            attemptNo = 2,
            incoming = incoming,
        )
        assertFalse(replay.changed)
        assertEquals(merged.markGroups, replay.markGroups)
    }

    @Test
    fun exactAttemptMergeAppliesOlderSliceButPreservesNewerGlobalMetadata() {
        val preservedOtherAttempt = Mark(2, MarkColor.GRAY, gradedAtEpochMillis = 200L)
        val existing = studentGroup(
            anchor = PagePoint(300f, 400f, pressure = 0.75f),
            marks = listOf(
                Mark(1, MarkColor.BLUE, gradedAtEpochMillis = 100L),
                preservedOtherAttempt,
            ),
            revision = 10L,
            deviceId = "device-z",
            createdAt = 80L,
        )
        val replacement = Mark(1, MarkColor.RED, gradedAtEpochMillis = 300L)
        val staleGlobalFreshAttempt = existing.copy(
            anchor = PagePoint(900f, 1_000f),
            marks = listOf(replacement),
            createdAtEpochMillis = 1L,
            hiddenAtEpochMillis = 500L,
            syncRevision = 3L,
            lastModifiedByDeviceId = "device-a",
        )

        val merged = mergeRemoteMarkGroupAttempt(
            markGroups = listOf(existing),
            bookId = BOOK,
            pageNumber = 2,
            pageCount = PAGE_COUNT,
            attempts = studentAttempts(1, 2),
            attemptNo = 1,
            incoming = staleGlobalFreshAttempt,
        )

        assertTrue(merged.changed)
        val result = merged.markGroups.single()
        assertEquals(listOf(replacement, preservedOtherAttempt), result.marks)
        assertEquals(existing.anchor, result.anchor)
        assertEquals(existing.createdAtEpochMillis, result.createdAtEpochMillis)
        assertEquals(existing.hiddenAtEpochMillis, result.hiddenAtEpochMillis)
        assertEquals(existing.lastModifiedByDeviceId, result.lastModifiedByDeviceId)
        assertEquals(10L, result.syncRevision)

        val replay = mergeRemoteMarkGroupAttempt(
            markGroups = merged.markGroups,
            bookId = BOOK,
            pageNumber = 2,
            pageCount = PAGE_COUNT,
            attempts = studentAttempts(1, 2),
            attemptNo = 1,
            incoming = staleGlobalFreshAttempt,
        )
        assertFalse(replay.changed)
    }

    @Test
    fun exactAttemptMergeInsertsProjectedGroup() {
        val incoming = studentGroup(
            marks = listOf(Mark(2, MarkColor.RED, gradedAtEpochMillis = 20L)),
            revision = 7L,
            deviceId = "teacher",
        )

        val inserted = mergeRemoteMarkGroupAttempt(
            markGroups = emptyList(),
            bookId = BOOK,
            pageNumber = 2,
            pageCount = PAGE_COUNT,
            attempts = studentAttempts(2),
            attemptNo = 2,
            incoming = incoming,
        )

        assertTrue(inserted.changed)
        assertEquals(incoming, inserted.markGroups.single())
    }

    @Test
    fun exactAttemptMergeRejectsEmptyMixedReservedOrMissingAttempt() {
        val attempts = studentAttempts(1, 2)
        val exact = studentGroup(marks = listOf(Mark(1, MarkColor.RED)), revision = 1L)

        assertThrows(IllegalArgumentException::class.java) {
            mergeRemoteMarkGroupAttempt(
                emptyList(), BOOK, 2, PAGE_COUNT, attempts, 1, exact.copy(marks = emptyList()),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            mergeRemoteMarkGroupAttempt(
                emptyList(),
                BOOK,
                2,
                PAGE_COUNT,
                attempts,
                1,
                exact.copy(marks = listOf(Mark(1, MarkColor.RED), Mark(2, MarkColor.BLUE))),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            mergeRemoteMarkGroupAttempt(
                emptyList(), BOOK, 2, PAGE_COUNT, attempts, TEACHER_PAGE_REVIEW_ATTEMPT_NO,
                pageLevelGroup(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            mergeRemoteMarkGroupAttempt(
                emptyList(), BOOK, 2, PAGE_COUNT, attempts, 3,
                studentGroup(marks = listOf(Mark(3, MarkColor.RED)), revision = 1L),
            )
        }
    }

    @Test
    fun exactAttemptBatchPersistsOnceAndReplayHasNoSideEffects() {
        val incoming = listOf(
            studentGroup(
                marks = listOf(Mark(1, MarkColor.RED, gradedAtEpochMillis = 10L)),
                revision = 2L,
            ).copy(id = "group-a"),
            studentGroup(
                marks = listOf(Mark(1, MarkColor.BLUE, gradedAtEpochMillis = 20L)),
                revision = 3L,
            ).copy(id = "group-b"),
        )
        val merged = mergeRemoteMarkGroupAttempts(
            markGroups = emptyList(),
            bookId = BOOK,
            pageNumber = 2,
            pageCount = PAGE_COUNT,
            attempts = studentAttempts(1),
            attemptNo = 1,
            incoming = incoming,
        )
        var installed: List<MarkGroup> = emptyList()
        var installs = 0
        var persists = 0

        val applied = applyRemoteMarkGroupAttemptBatch(
            result = merged,
            install = { markGroups ->
                installs += 1
                installed = markGroups
            },
            rollback = { error("rollback must not run") },
            persist = { persists += 1 },
        )

        assertTrue(applied)
        assertEquals(incoming, installed)
        assertEquals(1, installs)
        assertEquals(1, persists)

        val replay = mergeRemoteMarkGroupAttempts(
            markGroups = installed,
            bookId = BOOK,
            pageNumber = 2,
            pageCount = PAGE_COUNT,
            attempts = studentAttempts(1),
            attemptNo = 1,
            incoming = incoming,
        )
        var replaySideEffects = 0
        assertFalse(
            applyRemoteMarkGroupAttemptBatch(
                result = replay,
                install = { replaySideEffects += 1 },
                rollback = { replaySideEffects += 1 },
                persist = { replaySideEffects += 1 },
            ),
        )
        assertEquals(0, replaySideEffects)
        assertEquals(installed, replay.markGroups)
    }

    @Test
    fun exactAttemptBatchLeavesCatalogUntouchedWhenLaterGroupIsInvalidOrCollides() {
        val otherPage = studentGroup(
            marks = listOf(Mark(1, MarkColor.GRAY, gradedAtEpochMillis = 1L)),
            revision = 1L,
        ).copy(id = "occupied-id", pageNumber = 3)
        val originalCatalog = listOf(otherPage)
        val validFirst = studentGroup(
            marks = listOf(Mark(1, MarkColor.RED, gradedAtEpochMillis = 10L)),
            revision = 2L,
        ).copy(id = "valid-id")
        val invalidLater = validFirst.copy(id = "invalid-id", marks = emptyList())
        val collidingLater = validFirst.copy(id = otherPage.id)

        listOf(invalidLater, collidingLater).forEach { badLater ->
            var installedCatalog = originalCatalog
            var persists = 0

            assertThrows(IllegalArgumentException::class.java) {
                val merged = mergeRemoteMarkGroupAttempts(
                    markGroups = originalCatalog,
                    bookId = BOOK,
                    pageNumber = 2,
                    pageCount = PAGE_COUNT,
                    attempts = studentAttempts(1),
                    attemptNo = 1,
                    incoming = listOf(validFirst, badLater),
                )
                applyRemoteMarkGroupAttemptBatch(
                    result = merged,
                    install = { installedCatalog = it },
                    rollback = { installedCatalog = originalCatalog },
                    persist = { persists += 1 },
                )
            }

            assertEquals(originalCatalog, installedCatalog)
            assertEquals(0, persists)
        }
    }

    @Test
    fun exactAttemptBatchRejectsDuplicateIncomingIdentityBeforeFold() {
        val duplicate = studentGroup(
            marks = listOf(Mark(1, MarkColor.RED)),
            revision = 1L,
        )

        assertThrows(IllegalArgumentException::class.java) {
            mergeRemoteMarkGroupAttempts(
                markGroups = emptyList(),
                bookId = BOOK,
                pageNumber = 2,
                pageCount = PAGE_COUNT,
                attempts = studentAttempts(1),
                attemptNo = 1,
                incoming = listOf(duplicate, duplicate.copy(marks = listOf(Mark(1, MarkColor.BLUE)))),
            )
        }
    }

    @Test
    fun exactAttemptBatchRollsBackInstalledCatalogWhenPersistenceFails() {
        val original = listOf(
            studentGroup(
                marks = listOf(Mark(2, MarkColor.GRAY)),
                revision = 1L,
            ).copy(id = "preserved"),
        )
        val incoming = studentGroup(
            marks = listOf(Mark(1, MarkColor.RED)),
            revision = 2L,
        ).copy(id = "new-group")
        val merged = mergeRemoteMarkGroupAttempts(
            markGroups = original,
            bookId = BOOK,
            pageNumber = 2,
            pageCount = PAGE_COUNT,
            attempts = studentAttempts(1, 2),
            attemptNo = 1,
            incoming = listOf(incoming),
        )
        var installed = original

        assertThrows(IllegalStateException::class.java) {
            applyRemoteMarkGroupAttemptBatch(
                result = merged,
                install = { installed = it },
                rollback = { installed = original },
                persist = { error("disk failure") },
            )
        }

        assertEquals(original, installed)
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

    private fun studentGroup(
        anchor: PagePoint = PagePoint(100f, 200f),
        marks: List<Mark>,
        revision: Long,
        deviceId: String = "device-a",
        createdAt: Long = 1L,
    ) = MarkGroup(
        id = "student-mark-1",
        bookId = BOOK,
        pageNumber = 2,
        anchor = anchor,
        marks = marks,
        createdAtEpochMillis = createdAt,
        syncRevision = revision,
        lastModifiedByDeviceId = deviceId,
    )

    private fun studentAttempts(vararg attemptNos: Int): List<Attempt> = attemptNos.map { attemptNo ->
        Attempt(BOOK, pageNumber = 2, attemptNo = attemptNo)
    }

    private companion object {
        const val BOOK = "book-1"
        const val PAGE_COUNT = 10
    }
}
