package com.studyink.remote.sync

import com.studyink.remote.protocol.RemoteLiveStrokePreview
import com.studyink.remote.protocol.RemotePageState
import com.studyink.remote.protocol.RemoteStrokePoint
import com.studyink.remote.protocol.RemoteViewportState
import com.studyink.remote.protocol.ProtobufRemoteMessageCodec
import com.studyink.remote.protocol.RemoteEnvelope
import com.studyink.remote.protocol.RemoteLane
import com.studyink.remote.protocol.RemoteMessageCodec
import com.studyink.remote.protocol.RemotePayload
import com.studyink.remote.transport.RemoteTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val PREVIEW_INTERVAL_MILLIS = 100L
const val MAX_PREVIEW_POINTS = 24
const val PREVIEW_TIMEOUT_MILLIS = 2_000L

/** Conflated ephemeral lane: each state flow retains exactly one latest value. */
class RemoteLivePublisher {
    private val _preview = MutableStateFlow<RemoteLiveStrokePreview?>(null)
    private val _pageState = MutableStateFlow<RemotePageState?>(null)
    private val _viewportState = MutableStateFlow<RemoteViewportState?>(null)
    val preview: StateFlow<RemoteLiveStrokePreview?> = _preview.asStateFlow()
    val pageState: StateFlow<RemotePageState?> = _pageState.asStateFlow()
    val viewportState: StateFlow<RemoteViewportState?> = _viewportState.asStateFlow()
    private var lastPreviewSentAt = Long.MIN_VALUE
    private var activePreviewId: String? = null

    fun offerStroke(
        previewId: String,
        pageId: String,
        points: List<RemoteStrokePoint>,
        nowElapsedMillis: Long,
    ): Boolean {
        if (points.isEmpty()) return false
        val idChanged = activePreviewId != previewId
        if (!idChanged && lastPreviewSentAt != Long.MIN_VALUE &&
            nowElapsedMillis - lastPreviewSentAt < PREVIEW_INTERVAL_MILLIS
        ) return false
        activePreviewId = previewId
        lastPreviewSentAt = nowElapsedMillis
        _preview.value = RemoteLiveStrokePreview(previewId, pageId, sample(points, MAX_PREVIEW_POINTS))
        return true
    }

    fun finishStroke(previewId: String) {
        if (activePreviewId == previewId) {
            activePreviewId = null
            _preview.value = null
        }
    }

    fun expirePreview(nowElapsedMillis: Long): Boolean {
        if (_preview.value != null && nowElapsedMillis - lastPreviewSentAt >= PREVIEW_TIMEOUT_MILLIS) {
            activePreviewId = null
            _preview.value = null
            return true
        }
        return false
    }

    fun updatePage(value: RemotePageState) { _pageState.value = value }
    fun updateViewport(value: RemoteViewportState) { _viewportState.value = value }

    private fun sample(points: List<RemoteStrokePoint>, max: Int): List<RemoteStrokePoint> {
        if (points.size <= max) return points
        return List(max) { index -> points[index * (points.lastIndex) / (max - 1)] }
    }
}

class RemotePreviewReplica {
    data class Entry(val preview: RemoteLiveStrokePreview, val receivedAtElapsedMillis: Long)
    private val entries = linkedMapOf<String, Entry>()

    fun receive(preview: RemoteLiveStrokePreview, nowElapsedMillis: Long) {
        entries[preview.previewId] = Entry(preview, nowElapsedMillis)
    }

    /** Final stroke IDs equal preview IDs, so durable completion removes the temporary path. */
    fun onFinalStroke(strokeId: String) { entries.remove(strokeId) }

    fun expire(nowElapsedMillis: Long): Int {
        val expired = entries.filterValues { nowElapsedMillis - it.receivedAtElapsedMillis >= PREVIEW_TIMEOUT_MILLIS }.keys
        expired.forEach(entries::remove)
        return expired.size
    }

    fun visible(): List<RemoteLiveStrokePreview> = entries.values.map(Entry::preview)
    fun size(): Int = entries.size
}

class RemoteEphemeralSender(
    private val sessionId: String,
    private val senderDeviceId: String,
    private val endpointId: () -> String?,
    private val transport: RemoteTransport,
    private val elapsedRealtime: () -> Long,
    private val codec: RemoteMessageCodec = ProtobufRemoteMessageCodec(),
) {
    private var nextMessage = 0L

    suspend fun sendLatest(payload: RemotePayload?): Boolean {
        val endpoint = endpointId() ?: return false
        payload ?: return false
        val envelope = RemoteEnvelope(
            sessionId = sessionId,
            senderDeviceId = senderDeviceId,
            messageId = "ephemeral-${++nextMessage}",
            lane = RemoteLane.EPHEMERAL,
            sentElapsedRealtimeMs = elapsedRealtime(),
            payload = payload,
        )
        transport.send(endpoint, codec.encode(envelope))
        return true
    }
}

data class RemoteFollowState(
    val following: Boolean = true,
    val studentPage: RemotePageState? = null,
    val appliedPage: RemotePageState? = null,
    val appliedViewport: RemoteViewportState? = null,
)

class RemoteFollowController {
    private val _state = MutableStateFlow(RemoteFollowState())
    val state: StateFlow<RemoteFollowState> = _state.asStateFlow()

    fun setFollowing(enabled: Boolean) {
        val current = _state.value
        _state.value = current.copy(
            following = enabled,
            appliedPage = if (enabled) current.studentPage else current.appliedPage,
        )
    }

    fun onPage(value: RemotePageState) {
        val current = _state.value
        _state.value = current.copy(studentPage = value, appliedPage = if (current.following) value else current.appliedPage)
    }

    fun onViewport(value: RemoteViewportState) {
        val current = _state.value
        if (current.following) _state.value = current.copy(appliedViewport = value)
    }
}
