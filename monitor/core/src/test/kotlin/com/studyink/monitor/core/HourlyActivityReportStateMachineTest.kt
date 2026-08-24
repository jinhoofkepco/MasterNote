package com.studyink.monitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HourlyActivityReportStateMachineTest {
    @Test fun firstReportIsDueAfterOneHourAndDescribesRecentActivity() {
        val machine = HourlyActivityReportStateMachine().also { it.start(1_000L) }
        machine.heartbeat(2_401_000L)

        assertNull(machine.poll(3_600_999L))
        val report = machine.poll(3_601_000L)
        assertEquals(1L, report?.sequence)
        assertTrue(report?.hadActivityInLastHour == true)
        assertEquals(1_200L, report?.secondsSinceLastActivity)
    }

    @Test fun reportDistinguishesNoRecentActivity() {
        val machine = HourlyActivityReportStateMachine().also {
            it.start(nowElapsedMs = 3_600_000L, latestActivityElapsedMs = 0L)
        }

        val report = machine.poll(7_200_000L)
        assertFalse(report?.hadActivityInLastHour == true)
        assertEquals(7_200L, report?.secondsSinceLastActivity)
    }

    @Test fun activityExactlyOneHourAgoIsNoLongerRecent() {
        val machine = HourlyActivityReportStateMachine().also {
            it.start(nowElapsedMs = 0L, latestActivityElapsedMs = 0L)
        }

        assertFalse(machine.poll(3_600_000L)?.hadActivityInLastHour == true)
    }

    @Test fun delayedPollEmitsOnceAndDoesNotReplayMissedHours() {
        val machine = HourlyActivityReportStateMachine(intervalMs = 1_000L).also { it.start(0L) }

        assertEquals(1L, machine.poll(5_000L)?.sequence)
        assertNull(machine.poll(5_000L))
        assertNull(machine.poll(5_999L))
        assertEquals(2L, machine.poll(6_000L)?.sequence)
    }

    @Test fun stopPreventsReportsAndIgnoresHeartbeats() {
        val machine = HourlyActivityReportStateMachine(intervalMs = 1_000L).also { it.start(0L) }
        machine.stop()
        machine.heartbeat(900L)

        assertNull(machine.poll(5_000L))
    }
}
