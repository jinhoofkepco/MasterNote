package com.studyink.remote.session

import com.studyink.remote.protocol.ProtobufRemoteMessageCodec
import com.studyink.remote.protocol.RemoteEnvelope
import com.studyink.remote.protocol.RemoteLane
import com.studyink.remote.protocol.RemoteMessageCodec
import com.studyink.remote.protocol.RemotePing
import com.studyink.remote.protocol.RemotePong
import com.studyink.remote.protocol.RemoteHello
import com.studyink.remote.protocol.RemoteHelloAccepted
import com.studyink.remote.protocol.RemoteSessionRejected
import com.studyink.remote.protocol.RemoteDeviceRole
import com.studyink.remote.protocol.RemotePageGeometry
import com.studyink.remote.protocol.CURRENT_PROTOCOL_VERSION
import com.studyink.remote.transport.RemoteTransport
import com.studyink.remote.transport.RemoteTransportEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class RemoteSessionState {
    IDLE, ADVERTISING, DISCOVERING, PAIRING, HANDSHAKING, INITIAL_SYNC, LIVE,
    RECONNECTING, MANUAL_RECONNECT_REQUIRED, FAILED, ENDED,
}
enum class RemoteSessionRole { STUDENT, TEACHER }
data class RemotePairingRequest(val endpointId: String, val displayName: String, val authenticationDigits: String)
data class RemoteEndpoint(val endpointId: String, val displayName: String)
data class RemoteHandshakeConfig(
    val appVersion: String = "unknown",
    val bookRevisionId: String = "",
    val documentContentHash: String = "",
    val pageCount: Int = 0,
    val attemptId: String = "",
    val currentPageId: String = "",
    val pages: List<RemotePageGeometry> = emptyList(),
)
data class RemoteSessionSnapshot(
    val state: RemoteSessionState = RemoteSessionState.IDLE,
    val endpointId: String? = null,
    val pairing: RemotePairingRequest? = null,
    val error: String? = null,
    val availableEndpoints: List<RemoteEndpoint> = emptyList(),
)

class RemoteSessionController(
    private val role: RemoteSessionRole,
    private val sessionId: String,
    private val deviceId: String,
    private val localName: String,
    private val transport: RemoteTransport,
    private val scope: CoroutineScope,
    private val codec: RemoteMessageCodec = ProtobufRemoteMessageCodec(),
    private val elapsedRealtimeMs: () -> Long,
    private val handshake: RemoteHandshakeConfig = RemoteHandshakeConfig(),
) {
    private val mutableSnapshot = MutableStateFlow(RemoteSessionSnapshot())
    val snapshot: StateFlow<RemoteSessionSnapshot> = mutableSnapshot
    val receivedApplicationBytes = MutableSharedFlow<ByteArray>(extraBufferCapacity = 32)
    private var collector: Job? = null
    private val availableEndpoints = linkedMapOf<String, RemoteEndpoint>()

    fun start() {
        check(collector == null)
        collector = scope.launch { transport.events.collect(::onTransportEvent) }
        scope.launch {
            if (role == RemoteSessionRole.TEACHER) {
                transition(RemoteSessionState.ADVERTISING)
                transport.advertise(localName)
            } else {
                transition(RemoteSessionState.DISCOVERING)
                transport.discover()
            }
        }
    }

    fun connect(endpointId: String) = scope.launch {
        check(role == RemoteSessionRole.STUDENT && snapshot.value.state == RemoteSessionState.DISCOVERING)
        transport.requestConnection(endpointId, localName)
    }

    fun acceptPairing() = scope.launch {
        val pairing = requireNotNull(snapshot.value.pairing)
        transport.acceptConnection(pairing.endpointId)
    }

    fun rejectPairing() = scope.launch {
        val pairing = requireNotNull(snapshot.value.pairing)
        transport.rejectConnection(pairing.endpointId)
        transition(if (role == RemoteSessionRole.TEACHER) RemoteSessionState.ADVERTISING else RemoteSessionState.DISCOVERING)
    }

    fun end() = scope.launch { stopNow() }

    suspend fun stopNow() {
        transport.stop()
        collector?.cancel()
        collector = null
        transition(RemoteSessionState.ENDED)
    }

    suspend fun reconnect() {
        check(snapshot.value.state == RemoteSessionState.RECONNECTING)
        transport.stop()
        if (role == RemoteSessionRole.TEACHER) {
            transition(RemoteSessionState.ADVERTISING)
            transport.advertise(localName)
        } else {
            transition(RemoteSessionState.DISCOVERING)
            transport.discover()
        }
    }

    fun requireManualReconnect() {
        if (snapshot.value.state !in setOf(RemoteSessionState.LIVE, RemoteSessionState.ENDED)) {
            transition(RemoteSessionState.MANUAL_RECONNECT_REQUIRED)
        }
    }

    private suspend fun onTransportEvent(event: RemoteTransportEvent) {
        when (event) {
            is RemoteTransportEvent.PairingRequested -> {
                transition(RemoteSessionState.PAIRING, event.endpointId, RemotePairingRequest(event.endpointId, event.displayName, event.authenticationDigits))
            }
            is RemoteTransportEvent.Connected -> {
                transition(RemoteSessionState.HANDSHAKING, event.endpointId)
                send(event.endpointId, RemoteHello(
                    CURRENT_PROTOCOL_VERSION, CURRENT_PROTOCOL_VERSION, handshake.appVersion,
                    if (role == RemoteSessionRole.STUDENT) RemoteDeviceRole.STUDENT else RemoteDeviceRole.TEACHER,
                    deviceId, handshake.bookRevisionId, handshake.documentContentHash, handshake.pageCount,
                    handshake.attemptId, handshake.currentPageId, 0, handshake.pages,
                ))
            }
            is RemoteTransportEvent.BytesReceived -> handleBytes(event.endpointId, event.bytes)
            is RemoteTransportEvent.Disconnected -> transition(RemoteSessionState.RECONNECTING, event.endpointId)
            is RemoteTransportEvent.TransportError -> transition(if (event.recoverable) RemoteSessionState.RECONNECTING else RemoteSessionState.FAILED, error = event.message)
            is RemoteTransportEvent.EndpointFound -> {
                availableEndpoints[event.endpointId] = RemoteEndpoint(event.endpointId, event.displayName)
                transition(snapshot.value.state)
            }
            is RemoteTransportEvent.EndpointLost -> {
                availableEndpoints.remove(event.endpointId)
                transition(snapshot.value.state)
            }
        }
    }

    private suspend fun handleBytes(endpointId: String, bytes: ByteArray) {
        val envelope = runCatching { codec.decode(bytes) }.getOrElse {
            transition(RemoteSessionState.FAILED, endpointId, error = it.message)
            return
        }
        if (envelope.sessionId != sessionId) {
            send(endpointId, RemoteSessionRejected("Session ID mismatch"))
            transition(RemoteSessionState.FAILED, endpointId, error = "Session ID mismatch")
            return
        }
        when (val payload = envelope.payload) {
            is RemoteHello -> {
                val mismatch = when {
                    payload.minProtocolVersion > CURRENT_PROTOCOL_VERSION || payload.maxProtocolVersion < CURRENT_PROTOCOL_VERSION -> "Protocol mismatch"
                    handshake.bookRevisionId.isNotBlank() && payload.bookRevisionId != handshake.bookRevisionId -> "Book revision mismatch"
                    handshake.documentContentHash.isNotBlank() && payload.documentContentHash != handshake.documentContentHash -> "Document hash mismatch"
                    handshake.pageCount > 0 && payload.pageCount != handshake.pageCount -> "Page count mismatch"
                    handshake.attemptId.isNotBlank() && payload.attemptId != handshake.attemptId -> "Attempt mismatch"
                    handshake.pages.isNotEmpty() && payload.pages != handshake.pages -> "Page geometry mismatch"
                    else -> null
                }
                if (mismatch != null) {
                    send(endpointId, RemoteSessionRejected(mismatch))
                    transition(RemoteSessionState.FAILED, endpointId, error = mismatch)
                } else {
                    send(endpointId, RemoteHelloAccepted(CURRENT_PROTOCOL_VERSION, 1))
                }
            }
            is RemoteHelloAccepted -> transition(RemoteSessionState.INITIAL_SYNC, endpointId)
            is RemoteSessionRejected -> transition(RemoteSessionState.FAILED, endpointId, error = payload.reason)
            is RemotePing -> {
                send(endpointId, RemotePong(payload.nonce))
                transition(RemoteSessionState.LIVE, endpointId)
            }
            is RemotePong -> transition(RemoteSessionState.LIVE, endpointId)
            else -> receivedApplicationBytes.emit(bytes)
        }
    }

    fun initialSyncComplete() {
        if (snapshot.value.state == RemoteSessionState.INITIAL_SYNC) transition(RemoteSessionState.LIVE)
    }

    private suspend fun send(endpointId: String, payload: com.studyink.remote.protocol.RemotePayload) {
        transport.send(endpointId, codec.encode(RemoteEnvelope(
            sessionId = sessionId, senderDeviceId = deviceId, messageId = UUID.randomUUID().toString(),
            lane = RemoteLane.EPHEMERAL, sentElapsedRealtimeMs = elapsedRealtimeMs(), payload = payload,
        )))
    }

    private fun transition(
        state: RemoteSessionState,
        endpointId: String? = snapshot.value.endpointId,
        pairing: RemotePairingRequest? = null,
        error: String? = null,
    ) { mutableSnapshot.value = RemoteSessionSnapshot(state, endpointId, pairing, error, availableEndpoints.values.toList()) }
}
