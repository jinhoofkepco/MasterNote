package com.studyink.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickShapeSessionTest {
    @Test
    fun `snap fires exactly seven hundred milliseconds after the last meaningful movement`() {
        val session = session()
        assertHasNoCommit(session.onDown(0f, 0f, 100L))
        val schedule = session.onMove(20f, 0f, 250L, candidateAvailable = true).singleSchedule()

        assertEquals(950L, schedule.dueAtMs)
        assertEquals(
            schedule,
            session.onHoldTimer(schedule.generation, 949L, "line").singleSchedule(),
        )
        assertEquals(
            listOf(QuickShapeEffect.ShowSnappedPreview("line")),
            session.onHoldTimer(schedule.generation, 950L, "line"),
        )
        assertEquals(QuickShapePhase.SNAPPED, session.snapshot.phase)
    }

    @Test
    fun `movement inside hold slop keeps deadline and movement at boundary rearms`() {
        val session = session()
        session.onDown(0f, 0f, 0L)
        val first = session.onMove(20f, 0f, 100L, candidateAvailable = true).singleSchedule()

        assertTrue(session.onMove(27.99f, 0f, 500L, candidateAvailable = true).isEmpty())
        assertEquals(first.dueAtMs, session.snapshot.holdDueAtMs)

        val boundaryEffects = session.onMove(28f, 0f, 600L, candidateAvailable = true)
        assertEquals(QuickShapeEffect.CancelHoldTimer, boundaryEffects.first())
        val replacement = boundaryEffects.singleSchedule()
        assertEquals(1_300L, replacement.dueAtMs)
        assertTrue(replacement.generation != first.generation)
    }

    @Test
    fun `stale timer generation cannot snap after rearm`() {
        val session = session()
        session.onDown(0f, 0f, 0L)
        val old = session.onMove(20f, 0f, 100L, candidateAvailable = true).singleSchedule()
        val current = session.onMove(40f, 0f, 500L, candidateAvailable = true).singleSchedule()

        assertTrue(session.onHoldTimer(old.generation, 3_000L, "stale").isEmpty())
        assertEquals(QuickShapePhase.HOLD_ARMED, session.snapshot.phase)
        assertEquals(
            listOf(QuickShapeEffect.ShowSnappedPreview("current")),
            session.onHoldTimer(current.generation, current.dueAtMs, "current"),
        )
    }

    @Test
    fun `twelve pixel movement after snap resumes raw and rearms in same stroke`() {
        val session = session()
        session.onDown(0f, 0f, 0L)
        val first = session.onMove(20f, 0f, 100L, candidateAvailable = true).singleSchedule()
        session.onHoldTimer(first.generation, first.dueAtMs, "line-one")

        assertTrue(session.onMove(31.99f, 0f, 2_200L, candidateAvailable = true).isEmpty())
        val resumeEffects = session.onMove(32f, 0f, 2_300L, candidateAvailable = true)
        assertEquals(QuickShapeEffect.ResumeRawPreview, resumeEffects.first())
        val second = resumeEffects.singleSchedule()
        assertEquals(3_000L, second.dueAtMs)
        assertEquals(QuickShapePhase.HOLD_ARMED, session.snapshot.phase)

        assertEquals(
            listOf(QuickShapeEffect.ShowSnappedPreview("line-two")),
            session.onHoldTimer(second.generation, second.dueAtMs, "line-two"),
        )
        val commit = session.onUp(4_500L).singleCommit()
        assertEquals(QuickShapeCommit.Snapped("line-two"), commit.stroke)
    }

    @Test
    fun `raw resume can wait for a later viable candidate before rearming`() {
        val session = session()
        session.onDown(0f, 0f, 0L)
        val first = session.onMove(20f, 0f, 100L, candidateAvailable = true).singleSchedule()
        session.onHoldTimer(first.generation, first.dueAtMs, "line")

        assertEquals(
            listOf(QuickShapeEffect.ResumeRawPreview),
            session.onMove(32f, 0f, 2_200L, candidateAvailable = false),
        )
        assertEquals(QuickShapePhase.RAW, session.snapshot.phase)

        val later = session.onMove(40f, 0f, 2_500L, candidateAvailable = true).singleSchedule()
        assertEquals(3_200L, later.dueAtMs)
    }

    @Test
    fun `candidate loss disarms current hold`() {
        val session = session()
        session.onDown(0f, 0f, 0L)
        val schedule = session.onMove(20f, 0f, 100L, candidateAvailable = true).singleSchedule()

        assertEquals(
            listOf(QuickShapeEffect.CancelHoldTimer),
            session.onMove(21f, 0f, 200L, candidateAvailable = false),
        )
        assertEquals(QuickShapePhase.RAW, session.snapshot.phase)
        assertNull(session.snapshot.holdDueAtMs)
        assertTrue(session.onHoldTimer(schedule.generation, schedule.dueAtMs, "stale").isEmpty())
    }

    @Test
    fun `timer rejection returns to raw and may arm again`() {
        val session = session()
        session.onDown(0f, 0f, 0L)
        val rejected = session.onMove(20f, 0f, 100L, candidateAvailable = true).singleSchedule()
        assertTrue(session.onHoldTimer(rejected.generation, rejected.dueAtMs, null).isEmpty())
        assertEquals(QuickShapePhase.RAW, session.snapshot.phase)

        val retry = session.onMove(21f, 0f, 900L, candidateAvailable = true).singleSchedule()
        // The retry window starts at the rejected recognition deadline (800 ms), so the
        // first subsequent jitter event only schedules the remainder of a fresh 700 ms hold.
        assertEquals(1_500L, retry.dueAtMs)
        assertEquals(
            listOf(QuickShapeEffect.ShowSnappedPreview("circle")),
            session.onHoldTimer(retry.generation, 1_500L, "circle"),
        )
    }

    @Test
    fun `up is the only raw commit boundary`() {
        val session = session()
        assertHasNoCommit(session.onDown(0f, 0f, 0L))
        val effects = session.onMove(20f, 0f, 100L, candidateAvailable = true)
        assertHasNoCommit(effects)
        val schedule = effects.singleSchedule()
        assertHasNoCommit(
            session.onHoldTimer(schedule.generation, schedule.dueAtMs - 1L, "line"),
        )

        val commitEffects = session.onUp(schedule.dueAtMs - 1L)
        assertEquals(QuickShapeCommit.Raw, commitEffects.singleCommit().stroke)
        assertEquals(QuickShapeEffect.CleanupPreview, commitEffects.last())
        assertEquals(QuickShapePhase.IDLE, session.snapshot.phase)
    }

    @Test
    fun `physical up before deadline stays raw even if delayed timer ran first`() {
        val session = session()
        session.onDown(0f, 0f, 0L)
        val schedule = session.onMove(20f, 0f, 100L, candidateAvailable = true).singleSchedule()

        session.onHoldTimer(schedule.generation, schedule.dueAtMs, "line")

        assertEquals(
            QuickShapeCommit.Raw,
            session.onUp(schedule.dueAtMs - 1L).singleCommit().stroke,
        )
    }

    @Test
    fun `physical up exactly at deadline commits the snapped candidate`() {
        val session = session()
        session.onDown(0f, 0f, 0L)
        val schedule = session.onMove(20f, 0f, 100L, candidateAvailable = true).singleSchedule()

        assertHasNoCommit(session.onHoldTimer(schedule.generation, schedule.dueAtMs, "line"))

        assertEquals(
            QuickShapeCommit.Snapped("line"),
            session.onUp(schedule.dueAtMs).singleCommit().stroke,
        )
    }

    @Test
    fun `timer from first snap cannot win after raw resume rearms`() {
        val session = session()
        session.onDown(0f, 0f, 0L)
        val first = session.onMove(20f, 0f, 100L, candidateAvailable = true).singleSchedule()
        session.onHoldTimer(first.generation, first.dueAtMs, "first")
        val second = session.onMove(32f, 0f, 2_200L, candidateAvailable = true).singleSchedule()

        assertTrue(session.onHoldTimer(first.generation, second.dueAtMs, "stale").isEmpty())
        assertEquals(QuickShapePhase.HOLD_ARMED, session.snapshot.phase)
        assertEquals(second.generation, session.snapshot.timerGeneration)
        assertEquals(
            listOf(QuickShapeEffect.ShowSnappedPreview("second")),
            session.onHoldTimer(second.generation, second.dueAtMs, "second"),
        )
    }

    @Test
    fun `delayed meaningful move sampled before deadline revokes snap and rearms`() {
        val session = session()
        session.onDown(0f, 0f, 0L)
        val first = session.onMove(20f, 0f, 100L, candidateAvailable = true).singleSchedule()
        session.onHoldTimer(first.generation, first.dueAtMs, "premature")

        // Eight pixels is below the normal 12 px post-snap escape threshold. Its event time proves
        // it physically happened before the hold deadline, however, so the timer won a queue race.
        val recovery = session.onMove(28f, 0f, 700L, candidateAvailable = true)

        assertEquals(QuickShapeEffect.ResumeRawPreview, recovery.first())
        val replacement = recovery.singleSchedule()
        assertEquals(1_400L, replacement.dueAtMs)
        assertEquals(QuickShapePhase.HOLD_ARMED, session.snapshot.phase)
        assertTrue(session.onHoldTimer(first.generation, replacement.dueAtMs, "stale").isEmpty())
        assertEquals(
            listOf(QuickShapeEffect.ShowSnappedPreview("recovered")),
            session.onHoldTimer(replacement.generation, replacement.dueAtMs, "recovered"),
        )
    }

    @Test
    fun `delayed movement inside hold slop does not revoke snap`() {
        val session = session()
        session.onDown(0f, 0f, 0L)
        val schedule = session.onMove(20f, 0f, 100L, candidateAvailable = true).singleSchedule()
        session.onHoldTimer(schedule.generation, schedule.dueAtMs, "line")

        assertTrue(
            session.onMove(27.99f, 0f, schedule.dueAtMs - 1L, candidateAvailable = true).isEmpty(),
        )
        assertEquals(QuickShapePhase.SNAPPED, session.snapshot.phase)
        assertEquals(
            QuickShapeCommit.Snapped("line"),
            session.onUp(schedule.dueAtMs).singleCommit().stroke,
        )
    }

    @Test
    fun `recognition rejection invalidates its timer before fresh retry`() {
        val session = session()
        session.onDown(0f, 0f, 0L)
        val rejected = session.onMove(20f, 0f, 100L, candidateAvailable = true).singleSchedule()
        session.onHoldTimer(rejected.generation, rejected.dueAtMs, null)
        val retry = session.onMove(21f, 0f, 900L, candidateAvailable = true).singleSchedule()

        assertTrue(session.onHoldTimer(rejected.generation, retry.dueAtMs, "stale").isEmpty())
        assertEquals(QuickShapePhase.HOLD_ARMED, session.snapshot.phase)
        assertEquals(retry.generation, session.snapshot.timerGeneration)
    }

    @Test
    fun `cancel cleans timer and preview without commit and invalidates callback`() {
        val session = session()
        session.onDown(0f, 0f, 0L)
        val schedule = session.onMove(20f, 0f, 100L, candidateAvailable = true).singleSchedule()

        val effects = session.onCancel()
        assertEquals(
            listOf(QuickShapeEffect.CancelHoldTimer, QuickShapeEffect.CleanupPreview),
            effects,
        )
        assertHasNoCommit(effects)
        assertEquals(QuickShapePhase.IDLE, session.snapshot.phase)
        assertTrue(session.onHoldTimer(schedule.generation, schedule.dueAtMs, "stale").isEmpty())
        assertTrue(session.onUp(1_000L).isEmpty())
    }

    @Test
    fun `new down abandons old contact without committing`() {
        val session = session()
        session.onDown(0f, 0f, 0L)
        session.onMove(20f, 0f, 100L, candidateAvailable = true)

        val effects = session.onDown(5f, 6f, 200L)
        assertEquals(
            listOf(QuickShapeEffect.CancelHoldTimer, QuickShapeEffect.CleanupPreview),
            effects,
        )
        assertHasNoCommit(effects)
        assertEquals(QuickShapePhase.RAW, session.snapshot.phase)
    }

    @Test
    fun `cancel after snap removes preview without committing`() {
        val session = session()
        session.onDown(0f, 0f, 0L)
        val schedule = session.onMove(20f, 0f, 100L, candidateAvailable = true).singleSchedule()
        session.onHoldTimer(schedule.generation, schedule.dueAtMs, "line")

        val effects = session.onCancel()

        assertEquals(listOf(QuickShapeEffect.CleanupPreview), effects)
        assertHasNoCommit(effects)
        assertEquals(QuickShapePhase.IDLE, session.snapshot.phase)
        assertTrue(session.onHoldTimer(schedule.generation, Long.MAX_VALUE, "stale").isEmpty())
    }

    @Test
    fun `new down after snap cleans preview and starts an independent raw contact`() {
        val session = session()
        session.onDown(0f, 0f, 0L)
        val old = session.onMove(20f, 0f, 100L, candidateAvailable = true).singleSchedule()
        session.onHoldTimer(old.generation, old.dueAtMs, "old")

        val effects = session.onDown(5f, 6f, 3_000L)

        assertEquals(listOf(QuickShapeEffect.CleanupPreview), effects)
        assertHasNoCommit(effects)
        assertEquals(QuickShapePhase.RAW, session.snapshot.phase)
        assertTrue(session.onHoldTimer(old.generation, Long.MAX_VALUE, "stale").isEmpty())
        val fresh = session.onMove(25f, 6f, 3_100L, candidateAvailable = true).singleSchedule()
        assertEquals(3_800L, fresh.dueAtMs)
    }

    @Test
    fun `hold deadline saturates safely at long max`() {
        val session = session()
        session.onDown(0f, 0f, Long.MAX_VALUE - 3_000L)
        val schedule = session.onMove(
            20f,
            0f,
            Long.MAX_VALUE - 500L,
            candidateAvailable = true,
        ).singleSchedule()

        assertEquals(Long.MAX_VALUE, schedule.dueAtMs)
        assertEquals(
            schedule,
            session.onHoldTimer(schedule.generation, Long.MAX_VALUE - 1L, "line").singleSchedule(),
        )
        assertEquals(
            listOf(QuickShapeEffect.ShowSnappedPreview("line")),
            session.onHoldTimer(schedule.generation, Long.MAX_VALUE, "line"),
        )
        assertEquals(
            QuickShapeCommit.Snapped("line"),
            session.onUp(Long.MAX_VALUE).singleCommit().stroke,
        )
    }

    @Test
    fun `configuration rejects invalid pixel policies`() {
        assertThrows(IllegalArgumentException::class.java) {
            QuickShapeSession<String>(0f, 12f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuickShapeSession<String>(8f, 8f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            QuickShapeSession<String>(8f, Float.NaN)
        }
    }

    private fun session() = QuickShapeSession<String>(
        holdSlopPx = 8f,
        rawResumeSlopPx = 12f,
    )

    private fun List<QuickShapeEffect<String>>.singleSchedule(): QuickShapeEffect.ScheduleHoldTimer =
        filterIsInstance<QuickShapeEffect.ScheduleHoldTimer>().single()

    private fun List<QuickShapeEffect<String>>.singleCommit(): QuickShapeEffect.Commit<String> =
        filterIsInstance<QuickShapeEffect.Commit<String>>().single()

    private fun assertHasNoCommit(effects: List<QuickShapeEffect<String>>) {
        assertFalse(effects.any { it is QuickShapeEffect.Commit<*> })
    }
}
