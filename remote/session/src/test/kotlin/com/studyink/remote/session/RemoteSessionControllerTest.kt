package com.studyink.remote.session

import com.studyink.remote.transport.FakeRemoteTransport
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteSessionControllerTest {
    @Test fun explicitMatchingPairingThenPingPongReachesLiveAndCleansUp() = runTest {
        val (studentTransport, teacherTransport) = FakeRemoteTransport.pair(this)
        val student = RemoteSessionController(RemoteSessionRole.STUDENT, "session", "student-device", "학생", studentTransport, this, elapsedRealtimeMs = { 7 })
        val teacher = RemoteSessionController(RemoteSessionRole.TEACHER, "session", "teacher-device", "선생", teacherTransport, this, elapsedRealtimeMs = { 8 })
        student.start(); teacher.start(); advanceUntilIdle()
        student.connect(teacherTransport.endpointId); advanceUntilIdle()
        assertEquals("4821", student.snapshot.value.pairing?.authenticationDigits)
        assertEquals("4821", teacher.snapshot.value.pairing?.authenticationDigits)
        student.acceptPairing(); teacher.acceptPairing(); advanceUntilIdle()
        assertEquals(RemoteSessionState.INITIAL_SYNC, student.snapshot.value.state)
        assertEquals(RemoteSessionState.INITIAL_SYNC, teacher.snapshot.value.state)
        student.initialSyncComplete(); teacher.initialSyncComplete()
        assertEquals(RemoteSessionState.LIVE, student.snapshot.value.state)
        assertEquals(RemoteSessionState.LIVE, teacher.snapshot.value.state)
        student.end(); teacher.end(); advanceUntilIdle()
        assertEquals(RemoteSessionState.ENDED, student.snapshot.value.state)
    }
}
