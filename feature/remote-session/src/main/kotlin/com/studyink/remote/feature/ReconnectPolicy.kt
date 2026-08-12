package com.studyink.remote.feature

class ReconnectPolicy(
    private val maximumElapsedMillis: Long = 60_000L,
) {
    private val delays = longArrayOf(500L, 1_000L, 2_000L, 4_000L, 8_000L)

    fun schedule(): List<Long> {
        val result = mutableListOf<Long>()
        var elapsed = 0L
        var index = 0
        while (true) {
            val delay = delays[minOf(index, delays.lastIndex)]
            if (elapsed + delay > maximumElapsedMillis) break
            result += delay
            elapsed += delay
            index++
        }
        return result
    }
}
