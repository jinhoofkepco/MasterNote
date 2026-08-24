package com.studyink.monitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdleAlertStateMachineTest {
    @Test
    fun emitsRequestedBoundariesOnce() {
        val machine = IdleAlertStateMachine().also { it.start(1_000L) }

        assertNull(machine.poll(30_999L))
        assertEquals(30L, machine.poll(31_000L)?.thresholdSeconds)
        assertNull(machine.poll(39_999L))
        assertEquals(40L, machine.poll(41_000L)?.thresholdSeconds)
        assertEquals(50L, machine.poll(51_000L)?.thresholdSeconds)
        assertEquals(60L, machine.poll(61_000L)?.thresholdSeconds)
        assertEquals(65L, machine.poll(66_000L)?.thresholdSeconds)
        assertEquals(70L, machine.poll(71_000L)?.thresholdSeconds)
    }

    @Test
    fun heartbeatAtSixtyFourSecondsStartsAFreshAlertTimeline() {
        val machine = IdleAlertStateMachine().also { it.start(0L) }
        machine.poll(60_000L)
        machine.heartbeat(64_000L)

        assertEquals(60L, machine.poll(129_000L - 1L)?.thresholdSeconds)
        assertEquals(65L, machine.poll(129_000L)?.thresholdSeconds)
    }

    @Test
    fun delayedPollCoalescesMissedBoundaries() {
        val machine = IdleAlertStateMachine().also { it.start(0L) }

        val alert = machine.poll(72_900L)
        assertEquals(70L, alert?.thresholdSeconds)
        assertEquals(72L, alert?.actualIdleSeconds)
        assertNull(machine.poll(74_999L))
        assertEquals(75L, machine.poll(75_000L)?.thresholdSeconds)
    }

    @Test
    fun stoppedSessionNeverEmits() {
        val machine = IdleAlertStateMachine().also { it.start(0L) }
        machine.stop()
        assertNull(machine.poll(300_000L))
    }
}
