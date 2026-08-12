package com.studyink.remote.transport

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow

enum class RemoteTransportState { IDLE, ADVERTISING, DISCOVERING, PAIRING, CONNECTED, DISCONNECTED, FAILED }

sealed interface RemoteTransportEvent {
    data class EndpointFound(val endpointId: String, val displayName: String) : RemoteTransportEvent
    data class EndpointLost(val endpointId: String) : RemoteTransportEvent
    data class PairingRequested(
        val endpointId: String,
        val displayName: String,
        val authenticationDigits: String,
    ) : RemoteTransportEvent
    data class Connected(val endpointId: String) : RemoteTransportEvent
    data class BytesReceived(val endpointId: String, val bytes: ByteArray) : RemoteTransportEvent
    data class Disconnected(val endpointId: String) : RemoteTransportEvent
    data class TransportError(val operation: String, val message: String, val recoverable: Boolean) : RemoteTransportEvent
}

interface RemoteTransport {
    val state: StateFlow<RemoteTransportState>
    val events: Flow<RemoteTransportEvent>
    suspend fun advertise(localName: String)
    suspend fun discover()
    suspend fun requestConnection(endpointId: String, localName: String)
    suspend fun acceptConnection(endpointId: String)
    suspend fun rejectConnection(endpointId: String)
    suspend fun send(endpointId: String, bytes: ByteArray)
    suspend fun disconnect(endpointId: String)
    suspend fun stop()
}

data class FakeTransportFaults(
    val delayMs: Long = 0,
    val dropEvery: Int = 0,
    val duplicateEvery: Int = 0,
    val reorderPairs: Boolean = false,
    val bandwidthBytesPerSecond: Int = Int.MAX_VALUE,
)
