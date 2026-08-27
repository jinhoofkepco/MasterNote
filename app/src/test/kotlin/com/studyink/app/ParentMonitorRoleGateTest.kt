package com.studyink.app

import com.studyink.monitor.telegram.RemoteReviewPeerStatus
import com.studyink.monitor.telegram.RemoteReviewRole
import com.studyink.monitor.telegram.TelegramPeerBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentMonitorRoleGateTest {
    @Test
    fun connectedTeacherBlocksRenderingAndConsumesLegacyScreenCommands() {
        val gate = parentMonitorRoleGate(
            RemoteReviewPeerStatus.Connected(
                role = RemoteReviewRole.TEACHER,
                pairId = "teacher-pair",
                peer = TelegramPeerBinding(botId = 20L, username = "student_bot"),
            ),
        )

        assertSame(ParentMonitorRoleGate.BLOCK_REMOTE_TEACHER, gate)
        assertFalse(gate.allowsRendering)
        assertTrue(gate.consumePendingScreenRequests)
    }

    @Test
    fun teacherHandshakeIsBlockedBeforeConnectionCompletes() {
        val gate = parentMonitorRoleGate(
            RemoteReviewPeerStatus.WaitingForStudentAck(
                pairId = "teacher-pair",
                expectedStudentBotId = 20L,
                expiresAtEpochMs = 30_000L,
            ),
        )

        assertSame(ParentMonitorRoleGate.BLOCK_REMOTE_TEACHER, gate)
    }

    @Test
    fun studentAndLegacyParentModesKeepParentRenderingEnabled() {
        val connectedStudent = RemoteReviewPeerStatus.Connected(
            role = RemoteReviewRole.STUDENT,
            pairId = "student-pair",
            peer = TelegramPeerBinding(botId = 10L, username = "teacher_bot"),
        )

        listOf(
            RemoteReviewPeerStatus.Unconfigured,
            RemoteReviewPeerStatus.WaitingForTeacher(
                pairId = "student-pair",
                expiresAtEpochMs = 30_000L,
            ),
            connectedStudent,
        ).forEach { status ->
            val gate = parentMonitorRoleGate(status)
            assertSame(ParentMonitorRoleGate.ALLOW, gate)
            assertTrue(gate.allowsRendering)
            assertFalse(gate.consumePendingScreenRequests)
        }
    }

    @Test
    fun activityReportingUsesTheSameTeacherGateAsPageRendering() {
        val teacher = RemoteReviewPeerStatus.Connected(
            role = RemoteReviewRole.TEACHER,
            pairId = "teacher-pair",
            peer = TelegramPeerBinding(botId = 20L, username = "student_bot"),
        )
        val student = RemoteReviewPeerStatus.Connected(
            role = RemoteReviewRole.STUDENT,
            pairId = "student-pair",
            peer = TelegramPeerBinding(botId = 10L, username = "teacher_bot"),
        )

        assertFalse(parentActivityReportingAllowed(monitoringEnabled = true, peerStatus = teacher))
        assertFalse(
            parentActivityReportingAllowed(
                monitoringEnabled = true,
                peerStatus = RemoteReviewPeerStatus.WaitingForStudentAck(
                    pairId = "teacher-pair",
                    expectedStudentBotId = 20L,
                    expiresAtEpochMs = 30_000L,
                ),
            ),
        )
        assertFalse(parentActivityReportingAllowed(monitoringEnabled = false, peerStatus = student))
        assertTrue(parentActivityReportingAllowed(monitoringEnabled = true, peerStatus = student))
    }

    @Test
    fun workerRestartDelayDoublesAndCapsWithoutOverflow() {
        assertEquals(30_000L, remoteMonitorWorkerRetryDelayMillis(1))
        assertEquals(60_000L, remoteMonitorWorkerRetryDelayMillis(2))
        assertEquals(120_000L, remoteMonitorWorkerRetryDelayMillis(3))
        assertEquals(15L * 60L * 1_000L, remoteMonitorWorkerRetryDelayMillis(6))
        assertEquals(15L * 60L * 1_000L, remoteMonitorWorkerRetryDelayMillis(Int.MAX_VALUE))
    }
}
