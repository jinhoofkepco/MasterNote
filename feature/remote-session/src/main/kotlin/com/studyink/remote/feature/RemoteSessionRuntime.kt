package com.studyink.remote.feature

import com.studyink.remote.session.RemoteSessionController
import com.studyink.remote.session.RemoteSessionSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object RemoteSessionRuntime {
    private val _snapshot = MutableStateFlow(RemoteSessionSnapshot())
    val snapshot: StateFlow<RemoteSessionSnapshot> = _snapshot.asStateFlow()
    @Volatile internal var controller: RemoteSessionController? = null
    @Volatile var diagnostics: RemoteSessionDiagnostics? = null
        internal set

    internal fun update(value: RemoteSessionSnapshot) { _snapshot.value = value }
    fun acceptPairing() { controller?.acceptPairing() }
    fun rejectPairing() { controller?.rejectPairing() }
    fun connect(endpointId: String) { controller?.connect(endpointId) }
    internal fun clear() {
        controller = null
        diagnostics = null
        _snapshot.value = RemoteSessionSnapshot()
    }
}
