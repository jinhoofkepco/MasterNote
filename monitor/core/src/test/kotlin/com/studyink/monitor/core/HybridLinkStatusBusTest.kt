package com.studyink.monitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HybridLinkStatusBusTest {
    @Test
    fun retainsLatestStatusAndFiltersByBook() {
        HybridLinkStatusBus.clear()
        val received = mutableListOf<HybridLinkStatus>()
        val subscription = HybridLinkStatusBus.subscribe { received += it }
        try {
            val value = HybridLinkStatus(
                bookId = "book-a",
                decision = HybridLinkStateMachine().update(
                    HybridLinkSignals(
                        lanSocketConnected = false,
                        lanHandshakeComplete = false,
                        lanPageCatchUpComplete = false,
                        telegramConfigured = true,
                        telegramApiHealthy = true,
                        telegramPeerRecent = true,
                        nowElapsedMs = 12L,
                    ),
                ),
                updatedAtElapsedMs = 12L,
            )

            HybridLinkStatusBus.publish(value)

            assertEquals(value, HybridLinkStatusBus.current("book-a"))
            assertNull(HybridLinkStatusBus.current("book-b"))
            assertEquals(listOf(value), received)
        } finally {
            subscription.close()
            HybridLinkStatusBus.clear()
        }
    }
}
