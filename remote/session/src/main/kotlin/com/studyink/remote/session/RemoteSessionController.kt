package com.studyink.remote.session

import com.studyink.remote.protocol.ProtobufRemoteMessageCodec
import com.studyink.remote.protocol.RemoteEnvelope
import com.studyink.remote.protocol.RemoteLane
import com.studyink.remote.protocol.RemoteMessageCodec
import com.studyink.remote.protocol.RemotePing
import com.studyink.remote.protocol.RemotePong
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
data class RemoteSessionSnapshot(
    val state: RemoteSessionState = RemoteSessionState.IDLE,
    val endpointId: String? = null,
    val pairing: RemotePairingRequest? = null,
    val error: String? = null,
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
) {
    private val mutableSnapshot = MutableStateFlow(RemoteSessionSnapshot())
    val snapshot: StateFlow<RemoteSessionSnapshot> = mutableSnapshot
    val receivedApplicationBytes = MutableSharedFlow<ByteArray>(extraBufferCapacity = 32)
    private var collector: Job? = null
    private var pingNonce = 0L

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

    fun end() = scope.launch {
        transport.stop()
        collector?.cancel()
        collector = null
        transition(RemoteSessionState.ENDED)
    }

    private suspend fun onTransportEvent(event: RemoteTransportEvent) {
        when (event) {
            is RemoteTransportEvent.PairingRequested -> {
                transition(RemoteSessionState.PAIRING, event.endpointId, RemotePairingRequest(event.endpointId, event.displayName, event.authenticationDigits))
            }
            is RemoteTransportEvent.Connected -> {
                transition(RemoteSessionState.HANDSHAKING, event.endpointId)
                if (role == RemoteSessionRole.STUDENT) {
                    pingNonce = elapsedRealtimeMs()
                    send(event.endpointId, RemotePing(pingNonce))
                }
            }
            is RemoteTransportEvent.BytesReceived -> handleBytes(event.endpointId, event.bytes)
            is RemoteTransportEvent.Disconnected -> transition(RemoteSessionState.RECONNECTING, event.endpointId)
            is RemoteTransportEvent.TransportError -> transition(if (event.recoverable) RemoteSessionState.RECONNECTING else RemoteSessionState.FAILED, error = event.message)
            else -> Unit
        }
    }

    private suspend fun handleBytes(endpointId: String, bytes: ByteArray) {
        when (val payload = runCatching { codec.decode(bytes).payload }.getOrElse {
            transition(RemoteSessionState.FAILED, endpointId, error = it.message)
            return
        }) {
            is RemotePing -> {
                send(endpointId, RemotePong(payload.nonce))
                transition(RemoteSessionState.LIVE, endpointId)
            }
            is RemotePong -> if (payload.nonce == pingNonce) transition(RemoteSessionState.LIVE, endpointId)
            else -> receivedApplicationBytes.emit(bytes)
        }
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
    ) { mutableSnapshot.value = RemoteSessionSnapshot(state, endpointId, pairing, error) }
}
