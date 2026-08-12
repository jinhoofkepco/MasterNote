package com.studyink.remote.transport.nearby

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionType
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.studyink.remote.transport.RemoteTransport
import com.studyink.remote.transport.RemoteTransportEvent
import com.studyink.remote.transport.RemoteTransportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class NearbyRemoteTransport(
    context: Context,
    private val scope: CoroutineScope,
    private val serviceId: String = context.packageName + ".remote.v1",
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext),
) : RemoteTransport {
    override val state = MutableStateFlow(RemoteTransportState.IDLE)
    override val events = MutableSharedFlow<RemoteTransportEvent>(extraBufferCapacity = 64)
    private val pendingEndpoints = linkedSetOf<String>()
    private val connectedEndpoints = linkedSetOf<String>()

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return emitError("receive", "Only BYTES payloads are accepted", false)
            scope.launch { events.emit(RemoteTransportEvent.BytesReceived(endpointId, bytes)) }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val lifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            pendingEndpoints += endpointId
            state.value = RemoteTransportState.PAIRING
            scope.launch {
                events.emit(RemoteTransportEvent.PairingRequested(
                    endpointId, info.endpointName, info.authenticationDigits,
                ))
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            pendingEndpoints -= endpointId
            if (result.status.isSuccess) {
                connectedEndpoints += endpointId
                client.stopAdvertising()
                client.stopDiscovery()
                state.value = RemoteTransportState.CONNECTED
                scope.launch { events.emit(RemoteTransportEvent.Connected(endpointId)) }
            } else {
                state.value = RemoteTransportState.FAILED
                emitError("connect", "Nearby status ${result.status.statusCode}", true)
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints -= endpointId
            state.value = RemoteTransportState.DISCONNECTED
            scope.launch { events.emit(RemoteTransportEvent.Disconnected(endpointId)) }
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            scope.launch { events.emit(RemoteTransportEvent.EndpointFound(endpointId, info.endpointName)) }
        }
        override fun onEndpointLost(endpointId: String) {
            scope.launch { events.emit(RemoteTransportEvent.EndpointLost(endpointId)) }
        }
    }

    override suspend fun advertise(localName: String) {
        check(state.value == RemoteTransportState.IDLE || state.value == RemoteTransportState.DISCONNECTED)
        state.value = RemoteTransportState.ADVERTISING
        runCatching {
            client.startAdvertising(
                localName, serviceId, lifecycleCallback,
                AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT)
                    .setConnectionType(ConnectionType.NON_DISRUPTIVE).build(),
            ).await()
        }.onFailure { fail("advertise", it, true) }.getOrThrow()
    }

    override suspend fun discover() {
        check(state.value == RemoteTransportState.IDLE || state.value == RemoteTransportState.DISCONNECTED)
        state.value = RemoteTransportState.DISCOVERING
        runCatching {
            client.startDiscovery(
                serviceId, discoveryCallback,
                DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT)
                    .build(),
            ).await()
        }.onFailure { fail("discover", it, true) }.getOrThrow()
    }

    override suspend fun requestConnection(endpointId: String, localName: String) {
        client.requestConnection(localName, endpointId, lifecycleCallback).await()
    }

    override suspend fun acceptConnection(endpointId: String) {
        check(endpointId in pendingEndpoints)
        client.acceptConnection(endpointId, payloadCallback).await()
    }

    override suspend fun rejectConnection(endpointId: String) {
        pendingEndpoints -= endpointId
        client.rejectConnection(endpointId).await()
        state.value = RemoteTransportState.IDLE
    }

    override suspend fun send(endpointId: String, bytes: ByteArray) {
        require(endpointId in connectedEndpoints)
        client.sendPayload(endpointId, Payload.fromBytes(bytes)).await()
    }

    override suspend fun disconnect(endpointId: String) {
        client.disconnectFromEndpoint(endpointId)
        connectedEndpoints -= endpointId
        state.value = RemoteTransportState.DISCONNECTED
    }

    override suspend fun stop() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        pendingEndpoints.clear()
        connectedEndpoints.clear()
        state.value = RemoteTransportState.IDLE
    }

    private fun fail(operation: String, error: Throwable, recoverable: Boolean) {
        state.value = RemoteTransportState.FAILED
        emitError(operation, error.message ?: error.javaClass.simpleName, recoverable)
    }
    private fun emitError(operation: String, message: String, recoverable: Boolean) {
        scope.launch { events.emit(RemoteTransportEvent.TransportError(operation, message, recoverable)) }
    }
}
