package com.studyink.remote.feature

import com.studyink.remote.session.RemoteSessionController
import com.studyink.remote.session.RemoteSessionSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.studyink.remote.protocol.RemoteLiveStrokePreview
import com.studyink.remote.sync.RemoteFollowController
import com.studyink.remote.sync.RemotePreviewReplica
import com.studyink.remote.protocol.RemotePresentResource
import com.studyink.remote.protocol.RemoteResourceOffer
import com.studyink.remote.protocol.RemoteDismissResource
import java.io.File
import java.security.MessageDigest

data class RemotePresentedResource(val assetHash: String, val title: String, val text: String, val mimeType: String, val imageFile: File?)
internal data class OfferedTeachingResource(val offer: RemoteResourceOffer, val text: String, val file: File?)

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
    private val _presentedResource = MutableStateFlow<RemotePresentedResource?>(null)
    val presentedResource: StateFlow<RemotePresentedResource?> = _presentedResource.asStateFlow()
    private val offers = java.util.concurrent.ConcurrentHashMap<String, OfferedTeachingResource>()

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
    suspend fun offerTeachingResource(title: String, text: String, mimeType: String, file: File?) {
        val hash = file?.sha256() ?: MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        val offer = RemoteResourceOffer(hash, mimeType, title, file?.length() ?: 0)
        offers[hash] = OfferedTeachingResource(offer, text, file)
        controller?.sendApplication(offer)
    }
    suspend fun dismissTeachingResource(hash: String) { controller?.sendApplication(RemoteDismissResource(hash)) }
    internal fun offered(hash: String) = offers[hash]
    internal fun present(value: RemotePresentResource, file: File?) { _presentedResource.value = RemotePresentedResource(value.assetHash, value.title, value.textContent, value.mimeType, file) }
    internal fun dismiss(hash: String) { if (_presentedResource.value?.assetHash == hash) _presentedResource.value = null }
    internal fun clear() {
        controller = null
        diagnostics = null
        previewReplica.visible().map { it.previewId }.forEach(previewReplica::onFinalStroke)
        _previews.value = emptyList()
        _presentedResource.value = null
        offers.clear()
        follow.setFollowing(true)
        _snapshot.value = RemoteSessionSnapshot()
    }
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) } }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
