package com.studyink.remote.feature

import com.studyink.remote.session.RemoteSessionRole

data class RemoteSessionDiagnostics(
    val sessionId: String,
    val localDeviceId: String,
    val role: RemoteSessionRole,
    val startedAtEpochMillis: Long,
    var endedAtEpochMillis: Long? = null,
    var reconnectCount: Int = 0,
    var bytesSentDurable: Long = 0,
    var bytesSentEphemeral: Long = 0,
    var bytesReceived: Long = 0,
    var previewsDropped: Long = 0,
    var durableSent: Long = 0,
    var durableResent: Long = 0,
    var duplicateReceived: Long = 0,
    var sequenceGapCount: Long = 0,
    var checkpointCount: Long = 0,
    var checkpointMismatchCount: Long = 0,
    var maxOutboxSize: Int = 0,
    var maxUnackedCount: Int = 0,
    var lastError: String? = null,
) {
    private val latencies = ArrayDeque<Long>()
    fun recordLatency(valueMillis: Long) {
        if (latencies.size == 2_048) latencies.removeFirst()
        latencies.addLast(valueMillis.coerceAtLeast(0))
    }
    fun p50Latency(): Long? = percentile(.50)
    fun p95Latency(): Long? = percentile(.95)
    private fun percentile(fraction: Double): Long? {
        if (latencies.isEmpty()) return null
        val sorted = latencies.sorted()
        return sorted[((sorted.lastIndex) * fraction).toInt()]
    }
}
