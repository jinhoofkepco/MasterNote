package com.studyink.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MasterNoteDataCommitBusTest {
    @Test
    fun durableCommitsAdvanceGenerationAndNotifyListeners() {
        val baseline = MasterNoteDataCommitBus.currentGeneration()
        val observed = mutableListOf<Long>()
        val subscription = MasterNoteDataCommitBus.addListener(observed::add)
        try {
            val first = MasterNoteDataCommitBus.recordDurableCommit()
            val second = MasterNoteDataCommitBus.recordDurableCommit()

            assertEquals(baseline + 1L, first)
            assertEquals(baseline + 2L, second)
            assertEquals(listOf(first, second), observed)
            assertEquals(second, MasterNoteDataCommitBus.currentGeneration())
        } finally {
            subscription.close()
        }
    }

    @Test
    fun closingSubscriptionStopsNotifications() {
        var notifications = 0
        val subscription = MasterNoteDataCommitBus.addListener { notifications += 1 }
        subscription.close()

        MasterNoteDataCommitBus.recordDurableCommit()

        assertEquals(0, notifications)
    }

    @Test
    fun brokenListenerCannotFailACommitOrStarveOtherListeners() {
        var healthyListenerCalled = false
        val broken = MasterNoteDataCommitBus.addListener { error("listener failure") }
        val healthy = MasterNoteDataCommitBus.addListener { healthyListenerCalled = true }
        try {
            val before = MasterNoteDataCommitBus.currentGeneration()

            val committed = MasterNoteDataCommitBus.recordDurableCommit()

            assertEquals(before + 1L, committed)
            assertTrue(healthyListenerCalled)
        } finally {
            broken.close()
            healthy.close()
        }
    }
}
