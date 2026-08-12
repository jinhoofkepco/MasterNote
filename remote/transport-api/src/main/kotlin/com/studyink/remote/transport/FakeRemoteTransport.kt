package com.studyink.remote.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger

class FakeRemoteTransport private constructor(
    val endpointId: String,
    private val scope: CoroutineScope,
    var faults: FakeTransportFaults,
) : RemoteTransport {
    override val state = MutableStateFlow(RemoteTransportState.IDLE)
    override val events = MutableSharedFlow<RemoteTransportEvent>(extraBufferCapacity = 64)
    private var peer: FakeRemoteTransport? = null
    private var accepted = false
    private val sent = AtomicInteger()
    private val reorderBuffer = ArrayDeque<ByteArray>()

    fun link(other: FakeRemoteTransport) { peer = other }

    override suspend fun advertise(localName: String) { state.value = RemoteTransportState.ADVERTISING }
    override suspend fun discover() { state.value = RemoteTransportState.DISCOVERING }
    override suspend fun requestConnection(endpointId: String, localName: String) {
        val target = requireNotNull(peer).also { require(it.endpointId == endpointId) }
        state.value = RemoteTransportState.PAIRING
        target.state.value = RemoteTransportState.PAIRING
        val digits = "4821"
        events.emit(RemoteTransportEvent.PairingRequested(target.endpointId, "peer", digits))
        target.events.emit(RemoteTransportEvent.PairingRequested(this.endpointId, localName, digits))
    }

    override suspend fun acceptConnection(endpointId: String) {
        require(peer?.endpointId == endpointId)
        accepted = true
        connectIfBothAccepted()
    }

    private suspend fun connectIfBothAccepted() {
        val target = requireNotNull(peer)
        if (!accepted || !target.accepted) return
        state.value = RemoteTransportState.CONNECTED
        target.state.value = RemoteTransportState.CONNECTED
        events.emit(RemoteTransportEvent.Connected(target.endpointId))
        target.events.emit(RemoteTransportEvent.Connected(endpointId))
    }

    override suspend fun rejectConnection(endpointId: String) {
        require(peer?.endpointId == endpointId)
        accepted = false
        state.value = RemoteTransportState.IDLE
        peer?.state?.value = RemoteTransportState.IDLE
    }

    override suspend fun send(endpointId: String, bytes: ByteArray) {
        require(state.value == RemoteTransportState.CONNECTED)
        val target = requireNotNull(peer).also { require(it.endpointId == endpointId) }
        val ordinal = sent.incrementAndGet()
        if (faults.dropEvery > 0 && ordinal % faults.dropEvery == 0) return
        val copies = if (faults.duplicateEvery > 0 && ordinal % faults.duplicateEvery == 0) 2 else 1
        repeat(copies) { enqueueOrDeliver(target, bytes.copyOf()) }
    }

    private fun enqueueOrDeliver(target: FakeRemoteTransport, bytes: ByteArray) {
        if (faults.reorderPairs) {
            reorderBuffer.addLast(bytes)
            if (reorderBuffer.size < 2) return
            val second = reorderBuffer.removeLast()
            val first = reorderBuffer.removeFirst()
            deliver(target, second)
            deliver(target, first)
        } else deliver(target, bytes)
    }

    private fun deliver(target: FakeRemoteTransport, bytes: ByteArray) = scope.launch {
        val transferDelay = if (faults.bandwidthBytesPerSecond == Int.MAX_VALUE) 0L
        else (bytes.size * 1_000L / faults.bandwidthBytesPerSecond).coerceAtLeast(1L)
        delay(faults.delayMs + transferDelay)
        target.events.emit(RemoteTransportEvent.BytesReceived(endpointId, bytes))
    }

    override suspend fun disconnect(endpointId: String) {
        require(peer?.endpointId == endpointId)
        val target = peer
        state.value = RemoteTransportState.DISCONNECTED
        target?.state?.value = RemoteTransportState.DISCONNECTED
        events.emit(RemoteTransportEvent.Disconnected(endpointId))
        target?.events?.emit(RemoteTransportEvent.Disconnected(this.endpointId))
    }

    override suspend fun stop() {
        accepted = false
        reorderBuffer.clear()
        state.value = RemoteTransportState.IDLE
    }

    companion object {
        fun pair(
            scope: CoroutineScope,
            firstFaults: FakeTransportFaults = FakeTransportFaults(),
            secondFaults: FakeTransportFaults = FakeTransportFaults(),
        ): Pair<FakeRemoteTransport, FakeRemoteTransport> {
            val first = FakeRemoteTransport("fake-student", scope, firstFaults)
            val second = FakeRemoteTransport("fake-teacher", scope, secondFaults)
            first.link(second)
            second.link(first)
            return first to second
        }
    }
}
