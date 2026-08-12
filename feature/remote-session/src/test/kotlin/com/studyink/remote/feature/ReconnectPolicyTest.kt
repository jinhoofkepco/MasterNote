package com.studyink.remote.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {
    @Test fun backoffStartsFastCapsAtEightSecondsAndNeverExceedsOneMinute() {
        val schedule = ReconnectPolicy().schedule()
        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L), schedule.take(5))
        assertTrue(schedule.drop(5).all { it == 8_000L })
        assertTrue(schedule.sum() <= 60_000L)
        assertTrue(schedule.sum() + 8_000L > 60_000L)
    }
}
