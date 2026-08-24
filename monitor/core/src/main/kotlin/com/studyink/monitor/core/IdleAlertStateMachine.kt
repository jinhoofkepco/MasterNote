package com.studyink.monitor.core

/** One inactivity notification decision. [thresholdSeconds] is the deduplication boundary. */
data class IdleAlert(
    val episode: Long,
    val thresholdSeconds: Long,
    val actualIdleSeconds: Long,
)

/**
 * Monotonic, allocation-light inactivity policy for an active student study session.
 *
 * A delayed tick emits only the newest boundary it has crossed. It never replays a burst of stale
 * 30/40/50 second messages after the process or network has been paused.
 */
class IdleAlertStateMachine {
    private var armed = false
    private var episode = 0L
    private var lastActivityElapsedMs = 0L
    private var lastEmittedThresholdSeconds = 0L

    fun start(nowElapsedMs: Long) {
        require(nowElapsedMs >= 0L)
        armed = true
        episode += 1L
        lastActivityElapsedMs = nowElapsedMs
        lastEmittedThresholdSeconds = 0L
    }

    fun stop() {
        armed = false
        lastEmittedThresholdSeconds = 0L
    }

    fun heartbeat(nowElapsedMs: Long) {
        require(nowElapsedMs >= 0L)
        if (!armed) return
        if (nowElapsedMs < lastActivityElapsedMs) return
        episode += 1L
        lastActivityElapsedMs = nowElapsedMs
        lastEmittedThresholdSeconds = 0L
    }

    fun poll(nowElapsedMs: Long): IdleAlert? {
        require(nowElapsedMs >= 0L)
        if (!armed || nowElapsedMs < lastActivityElapsedMs) return null
        val actualSeconds = (nowElapsedMs - lastActivityElapsedMs) / 1_000L
        val threshold = latestThresholdAtOrBefore(actualSeconds) ?: return null
        if (threshold <= lastEmittedThresholdSeconds) return null
        lastEmittedThresholdSeconds = threshold
        return IdleAlert(
            episode = episode,
            thresholdSeconds = threshold,
            actualIdleSeconds = actualSeconds,
        )
    }
}

internal fun latestThresholdAtOrBefore(seconds: Long): Long? = when {
    seconds < 30L -> null
    seconds < 40L -> 30L
    seconds < 50L -> 40L
    seconds < 60L -> 50L
    seconds < 65L -> 60L
    else -> 65L + ((seconds - 65L) / 5L) * 5L
}
