package com.studyink.remote.transport

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FakeRemoteTransportTest {
    @Test fun pairingRequiresBothAcceptAndBytesCanBeDelayedDuplicatedOrDropped() = runTest {
        val (student, teacher) = FakeRemoteTransport.pair(
            this, FakeTransportFaults(delayMs = 100, dropEvery = 3, duplicateEvery = 2),
        )
        student.advertise("student")
        teacher.discover()
        val studentPairing = async(start = CoroutineStart.UNDISPATCHED) { student.events.filterIsInstance<RemoteTransportEvent.PairingRequested>().first() }
        val teacherPairing = async(start = CoroutineStart.UNDISPATCHED) { teacher.events.filterIsInstance<RemoteTransportEvent.PairingRequested>().first() }
        teacher.requestConnection(student.endpointId, "teacher")
        assertEquals("4821", studentPairing.await().authenticationDigits)
        assertEquals("4821", teacherPairing.await().authenticationDigits)
        student.acceptConnection(teacher.endpointId)
        assertEquals(RemoteTransportState.PAIRING, student.state.value)
        teacher.acceptConnection(student.endpointId)
        assertEquals(RemoteTransportState.CONNECTED, student.state.value)

        val received = mutableListOf<ByteArray>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            teacher.events.filterIsInstance<RemoteTransportEvent.BytesReceived>().collect { received += it.bytes }
        }
        student.send(teacher.endpointId, byteArrayOf(1))
        student.send(teacher.endpointId, byteArrayOf(2))
        student.send(teacher.endpointId, byteArrayOf(3))
        advanceUntilIdle()
        assertEquals(3, received.size)
        assertArrayEquals(byteArrayOf(1), received[0])
        assertArrayEquals(byteArrayOf(2), received[1])
        assertArrayEquals(byteArrayOf(2), received[2])
        collector.cancelAndJoin()
    }
}
