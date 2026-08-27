package com.studyink.monitor.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TelegramPeerLinkStateTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun stateRequiresRecentAuthenticatedObservationNotPairingAlone() {
        val connected = connectedStatus()

        assertEquals(
            TelegramPeerLinkHealth.STALE,
            resolveTelegramPeerLinkState(connected, null, NOW, 90_000L).health,
        )
        assertEquals(
            TelegramPeerLinkHealth.CHECKING,
            resolveTelegramPeerLinkState(
                connected,
                record().copy(
                    pendingPingNonce = "nonce_identifier_123",
                    pendingPingSentAtEpochMs = NOW - 1_000L,
                    pendingPingExpiresAtEpochMs = NOW + 10_000L,
                ),
                NOW,
                90_000L,
            ).health,
        )
        assertTrue(
            resolveTelegramPeerLinkState(
                connected,
                record().copy(lastPeerResponseEpochMs = NOW - 89_999L),
                NOW,
                90_000L,
            ).peerRecent,
        )
        assertFalse(
            resolveTelegramPeerLinkState(
                connected,
                record().copy(lastPeerResponseEpochMs = NOW - 90_001L),
                NOW,
                90_000L,
            ).peerRecent,
        )
    }

    @Test fun expiredAndFutureControlsCannotManufactureAConnection() {
        assertFalse(isUsablePeerControlWindow(NOW - 10_000L, NOW - 1L, NOW))
        assertFalse(
            isUsablePeerControlWindow(
                NOW + TelegramPeerProtocol.MAX_CONTROL_CLOCK_SKEW_MS + 1L,
                NOW + TelegramPeerProtocol.MAX_CONTROL_CLOCK_SKEW_MS + 2L,
                NOW,
            ),
        )
        assertFalse(
            isFreshPeerControlResponse(
                NOW - TelegramPeerProtocol.MAX_CONTROL_RESPONSE_AGE_MS - 1L,
                NOW,
            ),
        )
        assertTrue(isUsablePeerControlWindow(NOW - 1_000L, NOW + 1_000L, NOW))
        assertTrue(isFreshPeerControlResponse(NOW - 1_000L, NOW))
    }

    @Test fun pairScopedCorrelationSurvivesRestartAndCorruptStateIsIgnored() {
        val file = temporary.newFile("peer-link.v1")
        val first = TelegramPeerLinkStateStore(file)
        val expected = record().copy(
            lastPeerResponseEpochMs = NOW - 1_000L,
            pendingRequestId = "request_identifier_123",
            pendingRequestExpiresAtEpochMs = NOW + 60_000L,
        )
        first.save(expected)
        assertEquals(expected, TelegramPeerLinkStateStore(file).load())

        file.writeText("V1\twrong\n")
        assertNull(TelegramPeerLinkStateStore(file).load())
    }

    @Test fun aRecordFromAnOldPairNeverMakesTheNewPairGreen() {
        val old = record().copy(
            pairId = "old_pair_identifier_123",
            lastPeerResponseEpochMs = NOW,
        )
        assertEquals(
            TelegramPeerLinkHealth.STALE,
            resolveTelegramPeerLinkState(connectedStatus(), old, NOW, 90_000L).health,
        )
    }

    @Test fun automaticPingBootstrapsOnceThenStopsWhenPeerIsStale() {
        val neverProbed = record()
        assertTrue(shouldScheduleAutomaticPeerPing(neverProbed, NOW, 30_000L, 90_000L))

        val staleAfterProbe = neverProbed.copy(lastPingScheduledAtEpochMs = NOW - 60_000L)
        assertFalse(shouldScheduleAutomaticPeerPing(staleAfterProbe, NOW, 30_000L, 90_000L))
    }

    @Test fun automaticPingContinuesOnlyWhileAuthenticatedResponseIsRecent() {
        val healthy = record().copy(
            lastPeerResponseEpochMs = NOW - 20_000L,
            lastPingScheduledAtEpochMs = NOW - 30_000L,
        )
        assertTrue(shouldScheduleAutomaticPeerPing(healthy, NOW, 30_000L, 90_000L))
        assertFalse(
            shouldScheduleAutomaticPeerPing(
                healthy.copy(lastPeerResponseEpochMs = NOW - 90_001L),
                NOW,
                30_000L,
                90_000L,
            ),
        )
        assertFalse(
            shouldScheduleAutomaticPeerPing(
                healthy.copy(
                    pendingRequestId = "request_identifier_123",
                    pendingRequestExpiresAtEpochMs = NOW + 1_000L,
                ),
                NOW,
                30_000L,
                90_000L,
            ),
        )
    }

    @Test fun implausiblyFuturePersistedRequestIsNotPendingAfterClockRollback() {
        val plausible = record().copy(
            pendingRequestId = "request_identifier_123",
            pendingRequestExpiresAtEpochMs = NOW +
                TelegramPeerProtocol.MAX_CONTROL_REQUEST_LIFETIME_MS,
        )
        assertTrue(hasUsablePendingConnectionRequest(plausible, NOW))

        val farFuture = plausible.copy(
            pendingRequestExpiresAtEpochMs = NOW +
                TelegramPeerProtocol.MAX_CONTROL_REQUEST_LIFETIME_MS +
                TelegramPeerProtocol.MAX_CONTROL_CLOCK_SKEW_MS + 1L,
        )
        assertFalse(hasUsablePendingConnectionRequest(farFuture, NOW))
        assertEquals(
            TelegramPeerLinkHealth.STALE,
            resolveTelegramPeerLinkState(connectedStatus(), farFuture, NOW, 90_000L).health,
        )
    }

    @Test fun authenticatedResponseRepairsImplausiblyFutureObservationAfterClockRollback() {
        assertEquals(
            NOW,
            mergeAuthenticatedPeerObservation(
                previousEpochMs = NOW + TelegramPeerProtocol.MAX_CONTROL_CLOCK_SKEW_MS + 1L,
                observedAtEpochMs = NOW,
            ),
        )
        assertEquals(
            NOW + 1L,
            mergeAuthenticatedPeerObservation(NOW, NOW + 1L),
        )
    }

    @Test fun telegramServerDateCountsRecentTrafficWithoutRefreshingOldBacklog() {
        assertEquals(NOW, freshTelegramPeerUpdateEpochMs(NOW / 1_000L, NOW, 90_000L))
        assertEquals(
            NOW - 80_000L,
            freshTelegramPeerUpdateEpochMs((NOW - 80_000L) / 1_000L, NOW, 90_000L),
        )
        assertNull(freshTelegramPeerUpdateEpochMs((NOW - 91_000L) / 1_000L, NOW, 90_000L))
        assertNull(
            freshTelegramPeerUpdateEpochMs(
                (NOW + TelegramPeerProtocol.MAX_CONTROL_CLOCK_SKEW_MS + 1_000L) / 1_000L,
                NOW,
                90_000L,
            ),
        )
        assertNull(freshTelegramPeerUpdateEpochMs(null, NOW, 90_000L))
        assertNull(freshTelegramPeerUpdateEpochMs(Long.MAX_VALUE, NOW, 90_000L))

        val serverObservedAt = requireNotNull(
            freshTelegramPeerUpdateEpochMs((NOW - 80_000L) / 1_000L, NOW, 90_000L),
        )
        val observedRecord = record().copy(lastPeerResponseEpochMs = serverObservedAt)
        assertEquals(
            TelegramPeerLinkHealth.CONNECTED,
            resolveTelegramPeerLinkState(connectedStatus(), observedRecord, NOW, 90_000L).health,
        )
        assertEquals(
            TelegramPeerLinkHealth.STALE,
            resolveTelegramPeerLinkState(
                connectedStatus(),
                observedRecord,
                NOW + 10_001L,
                90_000L,
            ).health,
        )
    }

    @Test fun retiredFullPageAndImmediateFeedbackPayloadsAreDiscardedBeforeDownload() {
        assertTrue(shouldDiscardLegacyPeerPayload("PAGE_SNAPSHOT"))
        assertTrue(shouldDiscardLegacyPeerPayload("TEACHER_FEEDBACK"))
        assertTrue(shouldDiscardLegacyPeerPayload("REMOTE_GRADE"))
        assertFalse(shouldDiscardLegacyPeerPayload("PAGE_ANNOTATION"))
        assertFalse(shouldDiscardLegacyPeerPayload("PAGE_SYNC_MANIFEST"))
    }

    private fun connectedStatus() = RemoteReviewPeerStatus.Connected(
        role = RemoteReviewRole.TEACHER,
        pairId = PAIR_ID,
        peer = TelegramPeerBinding(22L, "student_bot"),
    )

    private fun record() = TelegramPeerLinkRecord(
        pairId = PAIR_ID,
        sessionId = "session_identifier_123",
    )

    private companion object {
        const val PAIR_ID = "pair_identifier_123"
        const val NOW = 2_000_000_000_000L
    }
}
