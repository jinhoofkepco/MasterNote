package com.studyink.monitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridLinkStateMachineTest {
    @Test
    fun readyLanWinsAndMakesTelegramInactive() {
        val decision = HybridLinkStateMachine().update(
            signals(now = 0L, lanReady = true, telegramReady = true),
        )

        assertEquals(HybridLinkMode.LAN_LIVE, decision.mode)
        assertEquals(HybridLinkLabel.LAN, decision.label)
        assertEquals("실", decision.label.text)
        assertEquals(HybridLinkHealth.READY, decision.health)
        assertEquals(HybridLinkTransport.LAN, decision.activeTransport)
        assertFalse(decision.telegramActive)
        assertFalse(decision.enteredTelegramFallback)
    }

    @Test
    fun losingReadyLanGetsExactlyFourSecondsOfGrace() {
        val machine = HybridLinkStateMachine()
        machine.update(signals(now = 10L, lanReady = true, telegramReady = true))

        val loss = machine.update(signals(now = 100L, lanReady = false, telegramReady = true))
        val beforeBoundary = machine.update(signals(now = 4_099L, lanReady = false, telegramReady = true))
        val atBoundary = machine.update(signals(now = 4_100L, lanReady = false, telegramReady = true))
        val afterBoundary = machine.update(signals(now = 4_101L, lanReady = false, telegramReady = true))

        assertEquals(HybridLinkMode.LAN_GRACE, loss.mode)
        assertEquals(HybridLinkLabel.LAN, loss.label)
        assertEquals(HybridLinkHealth.TRANSITIONING, loss.health)
        assertNull(loss.activeTransport)
        assertEquals(HybridLinkMode.LAN_GRACE, beforeBoundary.mode)
        assertEquals(HybridLinkMode.TELEGRAM_FALLBACK, atBoundary.mode)
        assertTrue(atBoundary.enteredTelegramFallback)
        assertTrue(atBoundary.telegramActive)
        assertFalse(afterBoundary.enteredTelegramFallback)
    }

    @Test
    fun definitiveLanDisconnectFallsBackImmediatelyWithoutGrace() {
        val machine = HybridLinkStateMachine()
        machine.update(signals(now = 10L, lanReady = true, telegramReady = true))

        val disconnected = machine.update(
            signals(
                now = 11L,
                lanReady = false,
                telegramReady = true,
                lanDefinitelyDisconnected = true,
            ),
        )

        assertEquals(HybridLinkMode.TELEGRAM_FALLBACK, disconnected.mode)
        assertTrue(disconnected.telegramActive)
        assertTrue(disconnected.enteredTelegramFallback)
    }

    @Test
    fun definitiveDisconnectDuringGraceEndsGraceImmediately() {
        val machine = HybridLinkStateMachine()
        machine.update(signals(now = 0L, lanReady = true, telegramReady = true))
        assertEquals(
            HybridLinkMode.LAN_GRACE,
            machine.update(signals(now = 100L, lanReady = false, telegramReady = true)).mode,
        )

        val disconnected = machine.update(
            signals(
                now = 101L,
                lanReady = false,
                telegramReady = true,
                lanDefinitelyDisconnected = true,
            ),
        )

        assertEquals(HybridLinkMode.TELEGRAM_FALLBACK, disconnected.mode)
        assertTrue(disconnected.enteredTelegramFallback)
    }

    @Test
    fun recoveringDuringGraceCancelsFallbackAndNextLossGetsANewGracePeriod() {
        val machine = HybridLinkStateMachine()
        machine.update(signals(now = 0L, lanReady = true, telegramReady = true))
        machine.update(signals(now = 1_000L, lanReady = false, telegramReady = true))

        val recovered = machine.update(signals(now = 4_999L, lanReady = true, telegramReady = true))
        val lostAgain = machine.update(signals(now = 5_000L, lanReady = false, telegramReady = true))
        val stillGrace = machine.update(signals(now = 8_999L, lanReady = false, telegramReady = true))
        val fallback = machine.update(signals(now = 9_000L, lanReady = false, telegramReady = true))

        assertEquals(HybridLinkMode.LAN_LIVE, recovered.mode)
        assertEquals(HybridLinkMode.LAN_GRACE, lostAgain.mode)
        assertEquals(HybridLinkMode.LAN_GRACE, stillGrace.mode)
        assertEquals(HybridLinkMode.TELEGRAM_FALLBACK, fallback.mode)
        assertTrue(fallback.enteredTelegramFallback)
    }

    @Test
    fun fallbackDoesNotReturnToLanBeforeHandshakeAndPageCatchUp() {
        val machine = HybridLinkStateMachine()
        assertEquals(
            HybridLinkMode.TELEGRAM_FALLBACK,
            machine.update(signals(now = 0L, lanReady = false, telegramReady = true)).mode,
        )

        val socketOnly = machine.update(
            signals(now = 1L, telegramReady = true, socket = true),
        )
        val handshaken = machine.update(
            signals(now = 2L, telegramReady = true, socket = true, handshake = true),
        )
        val caughtUp = machine.update(
            signals(
                now = 3L,
                telegramReady = true,
                socket = true,
                handshake = true,
                catchUp = true,
            ),
        )

        assertEquals(HybridLinkMode.TELEGRAM_FALLBACK, socketOnly.mode)
        assertTrue(socketOnly.telegramActive)
        assertEquals(HybridLinkMode.TELEGRAM_FALLBACK, handshaken.mode)
        assertEquals(HybridLinkMode.LAN_LIVE, caughtUp.mode)
        assertFalse(caughtUp.telegramActive)
    }

    @Test
    fun initiallyPartialLanDoesNotDelayAnAvailableTelegramFallback() {
        val decision = HybridLinkStateMachine().update(
            signals(now = 0L, telegramReady = true, socket = true, handshake = true, catchUp = false),
        )

        assertEquals(HybridLinkMode.TELEGRAM_FALLBACK, decision.mode)
        assertTrue(decision.enteredTelegramFallback)
    }

    @Test
    fun fallbackEntrySignalFiresOncePerFallbackEpisode() {
        val machine = HybridLinkStateMachine()
        val firstEntry = machine.update(signals(now = 0L, telegramReady = true))
        val steady = machine.update(signals(now = 1L, telegramReady = true))
        val offline = machine.update(signals(now = 2L, telegramConfigured = true, telegramApiHealthy = true))
        val secondEntry = machine.update(signals(now = 3L, telegramReady = true))

        assertTrue(firstEntry.enteredTelegramFallback)
        assertFalse(steady.enteredTelegramFallback)
        assertEquals(HybridLinkMode.OFFLINE_QUEUEING, offline.mode)
        assertTrue(secondEntry.enteredTelegramFallback)
    }

    @Test
    fun offlineQueueingHealthDistinguishesConfigurationApiAndStalePeer() {
        val notConfigured = HybridLinkStateMachine().update(signals(now = 0L))
        val apiError = HybridLinkStateMachine().update(
            signals(now = 0L, telegramConfigured = true),
        )
        val stalePeer = HybridLinkStateMachine().update(
            signals(now = 0L, telegramConfigured = true, telegramApiHealthy = true),
        )

        assertEquals(HybridLinkMode.OFFLINE_QUEUEING, notConfigured.mode)
        assertEquals(HybridLinkLabel.TELEGRAM, notConfigured.label)
        assertEquals("텔", notConfigured.label.text)
        assertEquals(HybridLinkHealth.INACTIVE, notConfigured.health)
        assertEquals(HybridLinkHealth.ERROR, apiError.health)
        assertEquals(HybridLinkHealth.TRANSITIONING, stalePeer.health)
    }

    @Test
    fun unavailableTelegramAfterGraceQueuesOfflineAtTheExactBoundary() {
        val machine = HybridLinkStateMachine()
        machine.update(signals(now = 0L, lanReady = true))
        machine.update(signals(now = 10L))

        assertEquals(HybridLinkMode.LAN_GRACE, machine.update(signals(now = 4_009L)).mode)
        val offline = machine.update(signals(now = 4_010L))
        assertEquals(HybridLinkMode.OFFLINE_QUEUEING, offline.mode)
        assertFalse(offline.enteredTelegramFallback)
        assertNull(offline.activeTransport)
    }

    @Test
    fun healthyLanIgnoresTelegramFailureForSelectionAndDisplayHealth() {
        val decision = HybridLinkStateMachine().update(
            signals(now = 0L, lanReady = true, telegramConfigured = true),
        )

        assertEquals(HybridLinkMode.LAN_LIVE, decision.mode)
        assertEquals(HybridLinkHealth.READY, decision.health)
        assertEquals(HybridLinkTransport.LAN, decision.activeTransport)
    }

    @Test
    fun rejectsRegressingElapsedTime() {
        val machine = HybridLinkStateMachine()
        machine.update(signals(now = 10L))

        assertThrows(IllegalArgumentException::class.java) {
            machine.update(signals(now = 9L))
        }
    }

    private fun signals(
        now: Long,
        lanReady: Boolean = false,
        telegramReady: Boolean = false,
        socket: Boolean = lanReady,
        handshake: Boolean = lanReady,
        catchUp: Boolean = lanReady,
        telegramConfigured: Boolean = telegramReady,
        telegramApiHealthy: Boolean = telegramReady,
        telegramPeerRecent: Boolean = telegramReady,
        lanDefinitelyDisconnected: Boolean = false,
    ) = HybridLinkSignals(
        lanSocketConnected = socket,
        lanHandshakeComplete = handshake,
        lanPageCatchUpComplete = catchUp,
        telegramConfigured = telegramConfigured,
        telegramApiHealthy = telegramApiHealthy,
        telegramPeerRecent = telegramPeerRecent,
        nowElapsedMs = now,
        lanDefinitelyDisconnected = lanDefinitelyDisconnected,
    )
}
