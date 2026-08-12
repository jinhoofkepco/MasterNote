package com.studyink.remote.feature

import com.studyink.remote.session.RemoteSessionController
import com.studyink.remote.session.RemoteSessionSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.studyink.remote.protocol.RemoteLiveStrokePreview
import com.studyink.remote.sync.RemoteFollowController
import com.studyink.remote.sync.RemotePreviewReplica

object RemoteSessionRuntime {
    private val _snapshot = MutableStateFlow(RemoteSessionSnapshot())
    val snapshot: StateFlow<RemoteSessionSnapshot> = _snapshot.asStateFlow()
    @Volatile internal var controller: RemoteSessionController? = null
    @Volatile var diagnostics: RemoteSessionDiagnostics? = null
        internal set
    private val previewReplica = RemotePreviewReplica()
    val follow = RemoteFollowController()
    private val _previews = MutableStateFlow<List<RemoteLiveStrokePreview>>(emptyList())
    val previews: StateFlow<List<RemoteLiveStrokePreview>> = _previews.asStateFlow()

    internal fun update(value: RemoteSessionSnapshot) { _snapshot.value = value }
    internal fun receivePreview(value: RemoteLiveStrokePreview, nowElapsedMillis: Long) {
        previewReplica.receive(value, nowElapsedMillis)
        _previews.value = previewReplica.visible()
    }
    internal fun finishPreviews(strokeIds: Collection<String>) {
        strokeIds.forEach(previewReplica::onFinalStroke)
        _previews.value = previewReplica.visible()
    }
    internal fun expirePreviews(nowElapsedMillis: Long) {
        if (previewReplica.expire(nowElapsedMillis) > 0) _previews.value = previewReplica.visible()
    }
    fun acceptPairing() { controller?.acceptPairing() }
    fun rejectPairing() { controller?.rejectPairing() }
    fun connect(endpointId: String) { controller?.connect(endpointId) }
    internal fun clear() {
        controller = null
        diagnostics = null
        previewReplica.visible().map { it.previewId }.forEach(previewReplica::onFinalStroke)
        _previews.value = emptyList()
        follow.setFollowing(true)
        _snapshot.value = RemoteSessionSnapshot()
    }
}
