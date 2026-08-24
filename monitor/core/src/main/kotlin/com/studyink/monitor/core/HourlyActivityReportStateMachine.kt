package com.studyink.monitor.core

/** One coalescible summary of the most recent reporting interval. */
data class HourlyActivityReport(
    val sequence: Long,
    val hadActivityInLastHour: Boolean,
    val secondsSinceLastActivity: Long?,
)

/**
 * Monotonic hourly reporting clock.
 *
 * A delayed poll emits at most one summary and starts a fresh interval at the poll time. This is
 * intentional: waking after process suspension must never replay a burst of stale hourly reports.
 */
class HourlyActivityReportStateMachine(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    private var armed = false
    private var nextReportElapsedMs = 0L
    private var lastActivityElapsedMs: Long? = null
    private var sequence = 0L

    init {
        require(intervalMs > 0L)
    }

    fun start(nowElapsedMs: Long, latestActivityElapsedMs: Long? = null) {
        require(nowElapsedMs >= 0L)
        require(latestActivityElapsedMs == null || latestActivityElapsedMs >= 0L)
        armed = true
        nextReportElapsedMs = safeAdd(nowElapsedMs, intervalMs)
        lastActivityElapsedMs = latestActivityElapsedMs?.takeIf { it <= nowElapsedMs }
    }

    fun stop() {
        armed = false
        nextReportElapsedMs = 0L
    }

    fun heartbeat(nowElapsedMs: Long) {
        require(nowElapsedMs >= 0L)
        if (!armed) return
        val previous = lastActivityElapsedMs
        if (previous == null || nowElapsedMs >= previous) lastActivityElapsedMs = nowElapsedMs
    }

    fun poll(nowElapsedMs: Long): HourlyActivityReport? {
        require(nowElapsedMs >= 0L)
        if (!armed || nowElapsedMs < nextReportElapsedMs) return null

        val lastActivity = lastActivityElapsedMs
        val elapsedSinceActivity = lastActivity
            ?.takeIf { it <= nowElapsedMs }
            ?.let { (nowElapsedMs - it) / 1_000L }
        sequence += 1L
        nextReportElapsedMs = safeAdd(nowElapsedMs, intervalMs)
        return HourlyActivityReport(
            sequence = sequence,
            hadActivityInLastHour = elapsedSinceActivity != null &&
                elapsedSinceActivity < intervalMs / 1_000L,
            secondsSinceLastActivity = elapsedSinceActivity,
        )
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private companion object {
        const val DEFAULT_INTERVAL_MS = 60L * 60L * 1_000L
    }
}
