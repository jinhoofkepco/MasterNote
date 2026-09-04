package com.studyink.reader

import kotlin.math.hypot

/**
 * Timing and pointer-lifecycle policy for "draw, then hold to snap".
 *
 * This class deliberately knows nothing about Android, ink storage, or shape recognition. The
 * caller owns the raw/canonical point lists and tells the session whether the current path has a
 * viable recognition candidate. When a scheduled hold fires, the caller performs one final
 * recognition pass and supplies that immutable candidate to [onHoldTimer].
 *
 * No method other than [onUp] can emit [QuickShapeEffect.Commit]. In particular, showing a snapped
 * preview is never a document mutation, which keeps cancellation, page changes, and process-level
 * persistence boundaries unambiguous.
 */
internal class QuickShapeSession<Candidate : Any>(
    private val holdSlopPx: Float,
    private val rawResumeSlopPx: Float,
    private val holdDurationMs: Long = HOLD_DURATION_MS,
) {
    init {
        require(holdSlopPx.isFinite() && holdSlopPx > 0f) { "Hold slop must be finite and positive" }
        require(rawResumeSlopPx.isFinite() && rawResumeSlopPx > holdSlopPx) {
            "Raw-resume slop must be finite and greater than hold slop"
        }
        require(holdDurationMs > 0L) { "Hold duration must be positive" }
    }

    private var phase = QuickShapePhase.IDLE
    private var generation = 0L
    private var current = QuickShapeViewPoint(0f, 0f)
    private var lastMeaningful = current
    private var lastMeaningfulAtMs = 0L
    private var holdDueAtMs: Long? = null
    private var snappedDueAtMs: Long? = null
    private var snapAnchor = current
    private var snappedCandidate: Candidate? = null

    val snapshot: QuickShapeSessionSnapshot
        get() = QuickShapeSessionSnapshot(
            phase = phase,
            timerGeneration = generation,
            holdDueAtMs = holdDueAtMs,
        )

    /** Starts a new physical contact. Any abandoned old contact is cleaned up without committing. */
    fun onDown(x: Float, y: Float, eventTimeMs: Long): List<QuickShapeEffect<Candidate>> {
        requirePoint(x, y)
        require(eventTimeMs >= 0L) { "Event time cannot be negative" }
        val effects = if (phase == QuickShapePhase.IDLE) {
            emptyList()
        } else {
            buildList {
                if (phase == QuickShapePhase.HOLD_ARMED) add(QuickShapeEffect.CancelHoldTimer)
                add(QuickShapeEffect.CleanupPreview)
            }
        }
        invalidateGeneration()
        phase = QuickShapePhase.RAW
        current = QuickShapeViewPoint(x, y)
        lastMeaningful = current
        lastMeaningfulAtMs = eventTimeMs
        holdDueAtMs = null
        snappedDueAtMs = null
        snapAnchor = current
        snappedCandidate = null
        return effects
    }

    /**
     * Advances the contact in view pixels.
     *
     * [candidateAvailable] is intentionally only a cheap viability signal. The definitive candidate
     * is requested at timer delivery, so a recognizer can evolve independently of this state machine.
     */
    fun onMove(
        x: Float,
        y: Float,
        eventTimeMs: Long,
        candidateAvailable: Boolean,
    ): List<QuickShapeEffect<Candidate>> {
        requirePoint(x, y)
        require(eventTimeMs >= 0L) { "Event time cannot be negative" }
        if (phase == QuickShapePhase.IDLE) return emptyList()
        current = QuickShapeViewPoint(x, y)

        if (phase == QuickShapePhase.SNAPPED) {
            val delayedPreDeadlineMove = snappedDueAtMs?.let { dueAt ->
                eventTimeMs < dueAt && current.distanceTo(lastMeaningful) >= holdSlopPx
            } == true
            val normalPostSnapEscape = current.distanceTo(snapAnchor) >= rawResumeSlopPx
            if (!delayedPreDeadlineMove && !normalPostSnapEscape) return emptyList()

            // Once the user deliberately moves away from the snapped endpoint, the caller restores
            // its raw preview. A MOVE physically sampled before the hold deadline can also arrive
            // after the timer callback; use the tighter hold slop for that ordering race so a queued
            // pre-deadline movement can never leave a false snap in place. The same physical contact
            // may then become a new hold candidate.
            invalidateGeneration()
            phase = QuickShapePhase.RAW
            snappedCandidate = null
            snappedDueAtMs = null
            lastMeaningful = current
            lastMeaningfulAtMs = eventTimeMs.coerceAtLeast(lastMeaningfulAtMs)
            holdDueAtMs = null
            return buildList {
                add(QuickShapeEffect.ResumeRawPreview)
                if (candidateAvailable) add(armHold(lastMeaningfulAtMs))
            }
        }

        val meaningfullyMoved = current.distanceTo(lastMeaningful) >= holdSlopPx
        if (meaningfullyMoved) {
            val effects = mutableListOf<QuickShapeEffect<Candidate>>()
            if (phase == QuickShapePhase.HOLD_ARMED) effects += QuickShapeEffect.CancelHoldTimer
            invalidateGeneration()
            phase = QuickShapePhase.RAW
            holdDueAtMs = null
            lastMeaningful = current
            lastMeaningfulAtMs = eventTimeMs.coerceAtLeast(lastMeaningfulAtMs)
            if (candidateAvailable) effects += armHold(lastMeaningfulAtMs)
            return effects
        }

        if (!candidateAvailable) {
            if (phase != QuickShapePhase.HOLD_ARMED) return emptyList()
            invalidateGeneration()
            phase = QuickShapePhase.RAW
            holdDueAtMs = null
            return listOf(QuickShapeEffect.CancelHoldTimer)
        }

        return if (phase == QuickShapePhase.RAW) {
            listOf(armHold(lastMeaningfulAtMs))
        } else {
            emptyList()
        }
    }

    /**
     * Delivers one caller-scheduled timer. A stale generation is a strict no-op.
     *
     * If delivery is early, the same generation is scheduled for the original exact deadline.
     * [candidate] should be the recognizer's result for all raw points collected so far.
     */
    fun onHoldTimer(
        timerGeneration: Long,
        nowMs: Long,
        candidate: Candidate?,
    ): List<QuickShapeEffect<Candidate>> {
        require(nowMs >= 0L) { "Timer time cannot be negative" }
        if (phase != QuickShapePhase.HOLD_ARMED || timerGeneration != generation) return emptyList()
        val dueAt = holdDueAtMs ?: return emptyList()
        if (nowMs < dueAt) {
            return listOf(QuickShapeEffect.ScheduleHoldTimer(timerGeneration, dueAt))
        }
        if (candidate == null) {
            invalidateGeneration()
            phase = QuickShapePhase.RAW
            holdDueAtMs = null
            // A tiny jitter after rejection may make the same cheap viability gate true again.
            // Start any retry window here instead of immediately re-firing an already-past dueAt.
            lastMeaningfulAtMs = nowMs.coerceAtLeast(lastMeaningfulAtMs)
            return emptyList()
        }

        invalidateGeneration()
        phase = QuickShapePhase.SNAPPED
        holdDueAtMs = null
        snappedDueAtMs = dueAt
        snapAnchor = current
        snappedCandidate = candidate
        return listOf(QuickShapeEffect.ShowSnappedPreview(candidate))
    }

    /** The only commit boundary: one UP produces either the raw or snapped form of this stroke. */
    fun onUp(eventTimeMs: Long): List<QuickShapeEffect<Candidate>> {
        require(eventTimeMs >= 0L) { "Event time cannot be negative" }
        if (phase == QuickShapePhase.IDLE) return emptyList()
        val endingPhase = phase
        val candidate = snappedCandidate
        val physicalHoldCompleted = snappedDueAtMs?.let { eventTimeMs >= it } ?: false
        val effects = buildList {
            if (endingPhase == QuickShapePhase.HOLD_ARMED) add(QuickShapeEffect.CancelHoldTimer)
            add(
                QuickShapeEffect.Commit(
                    if (
                        endingPhase == QuickShapePhase.SNAPPED && candidate != null &&
                        physicalHoldCompleted
                    ) {
                        QuickShapeCommit.Snapped(candidate)
                    } else {
                        QuickShapeCommit.Raw
                    }
                )
            )
            add(QuickShapeEffect.CleanupPreview)
        }
        resetToIdle()
        return effects
    }

    /** Abandons all timer/preview state and never emits a document commit. */
    fun onCancel(): List<QuickShapeEffect<Candidate>> {
        if (phase == QuickShapePhase.IDLE) return emptyList()
        val cancelTimer = phase == QuickShapePhase.HOLD_ARMED
        val effects = buildList {
            if (cancelTimer) add(QuickShapeEffect.CancelHoldTimer)
            add(QuickShapeEffect.CleanupPreview)
        }
        resetToIdle()
        return effects
    }

    private fun armHold(stillSinceMs: Long): QuickShapeEffect.ScheduleHoldTimer {
        invalidateGeneration()
        phase = QuickShapePhase.HOLD_ARMED
        snappedDueAtMs = null
        val dueAt = safeAdd(stillSinceMs, holdDurationMs)
        holdDueAtMs = dueAt
        return QuickShapeEffect.ScheduleHoldTimer(generation, dueAt)
    }

    private fun resetToIdle() {
        invalidateGeneration()
        phase = QuickShapePhase.IDLE
        holdDueAtMs = null
        snappedDueAtMs = null
        snappedCandidate = null
    }

    private fun invalidateGeneration() {
        generation = if (generation == Long.MAX_VALUE) 1L else generation + 1L
    }

    private fun requirePoint(x: Float, y: Float) {
        require(x.isFinite() && y.isFinite()) { "Pointer coordinates must be finite" }
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun QuickShapeViewPoint.distanceTo(other: QuickShapeViewPoint): Float =
        hypot(x - other.x, y - other.y)

    internal companion object {
        const val HOLD_DURATION_MS = 2_000L
    }
}

internal enum class QuickShapePhase {
    IDLE,
    RAW,
    HOLD_ARMED,
    SNAPPED,
}

internal data class QuickShapeSessionSnapshot(
    val phase: QuickShapePhase,
    val timerGeneration: Long,
    val holdDueAtMs: Long?,
)

internal sealed interface QuickShapeEffect<out Candidate : Any> {
    /** Schedule against the same monotonic time base used for MotionEvent.eventTime. */
    data class ScheduleHoldTimer(
        val generation: Long,
        val dueAtMs: Long,
    ) : QuickShapeEffect<Nothing>

    data object CancelHoldTimer : QuickShapeEffect<Nothing>

    data class ShowSnappedPreview<Candidate : Any>(
        val candidate: Candidate,
    ) : QuickShapeEffect<Candidate>

    /** The snap was intentionally escaped; redraw all retained raw points without committing. */
    data object ResumeRawPreview : QuickShapeEffect<Nothing>

    data class Commit<Candidate : Any>(
        val stroke: QuickShapeCommit<Candidate>,
    ) : QuickShapeEffect<Candidate>

    /** Clears any custom raw/snapped overlay owned by the caller. */
    data object CleanupPreview : QuickShapeEffect<Nothing>
}

internal sealed interface QuickShapeCommit<out Candidate : Any> {
    data object Raw : QuickShapeCommit<Nothing>

    data class Snapped<Candidate : Any>(
        val candidate: Candidate,
    ) : QuickShapeCommit<Candidate>
}

private data class QuickShapeViewPoint(val x: Float, val y: Float)
