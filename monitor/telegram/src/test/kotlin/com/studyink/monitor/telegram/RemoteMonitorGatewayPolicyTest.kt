package com.studyink.monitor.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMonitorGatewayPolicyTest {
    @Test fun queueFullKeepsCallerOwnedMediaForRetry() {
        assertFalse(shouldDeleteRejectedOwnedMedia(TelegramEnqueueResult.QUEUE_FULL))
        assertFalse(shouldDeleteRejectedOwnedMedia(TelegramEnqueueResult.ENQUEUED))
    }

    @Test fun idempotentTerminalRejectionsMayDeleteAnUnreferencedDuplicateFile() {
        assertTrue(shouldDeleteRejectedOwnedMedia(TelegramEnqueueResult.ALREADY_PENDING))
        assertTrue(shouldDeleteRejectedOwnedMedia(TelegramEnqueueResult.ALREADY_DELIVERED))
        assertTrue(shouldDeleteRejectedOwnedMedia(TelegramEnqueueResult.PREVIOUSLY_DEAD))
        assertTrue(shouldDeleteRejectedOwnedMedia(TelegramEnqueueResult.PREVIOUSLY_SUPERSEDED))
    }

    @Test fun enqueueRejectsEveryDestinationWhileMonitoringIsDisabled() {
        assertEquals(
            TelegramEnqueueResult.NOT_CONFIGURED,
            enqueuePrecondition(
                monitoringEnabled = false,
                activeCredentials = credentials(chatId = 7L),
                expectedChatId = 7L,
            ),
        )
    }

    @Test fun enqueueRejectsAChangedDestinationWithinTheEnabledSession() {
        assertEquals(
            TelegramEnqueueResult.CHAT_CHANGED,
            enqueuePrecondition(
                monitoringEnabled = true,
                activeCredentials = credentials(chatId = 8L),
                expectedChatId = 7L,
            ),
        )
    }

    @Test fun enqueueAcceptsOnlyTheEnabledMatchingDestination() {
        assertNull(
            enqueuePrecondition(
                monitoringEnabled = true,
                activeCredentials = credentials(chatId = 7L),
                expectedChatId = 7L,
            ),
        )
        assertNull(
            enqueuePrecondition(
                monitoringEnabled = true,
                activeCredentials = credentials(chatId = 7L),
                expectedChatId = null,
            ),
        )
    }

    @Test fun peerDocumentsHaveASeparateCountAndDiskQuota() {
        assertTrue(withinPeerDocumentDiskQuota(47, 94L * 1_024L * 1_024L, 2L * 1_024L * 1_024L))
        assertFalse(withinPeerDocumentDiskQuota(48, 0L, 1L))
        assertFalse(withinPeerDocumentDiskQuota(1, 95L * 1_024L * 1_024L, 2L * 1_024L * 1_024L))
    }

    @Test fun repeatedDeliveryGetsANewAckWithoutDuplicatingTheSameUpdate() {
        val first = peerDeliveryAckInstanceId("pair_12345678", "snapshot_12345678", 41L)
        assertEquals(first, peerDeliveryAckInstanceId("pair_12345678", "snapshot_12345678", 41L))
        assertTrue(first.startsWith("ack_"))
        assertEquals(68, first.length)
        assertFalse(first == peerDeliveryAckInstanceId("pair_12345678", "snapshot_12345678", 42L))
    }

    private fun credentials(chatId: Long) = TelegramCredentials(
        botToken = "123456:test-token",
        allowedPrivateChatId = chatId,
        chatLabel = "parent",
    )
}
