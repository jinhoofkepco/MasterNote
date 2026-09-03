package com.studyink.monitor.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
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

    @Test fun parentOutboxIsRejectedForTeacherRoleButPreservedForStudentRole() {
        val teacher = credentials(chatId = 7L, role = RemoteReviewRole.TEACHER)
        val student = credentials(chatId = 7L, role = RemoteReviewRole.STUDENT)
        assertEquals(
            TelegramEnqueueResult.NOT_CONFIGURED,
            enqueuePrecondition(true, teacher, expectedChatId = 7L),
        )
        assertNull(enqueuePrecondition(true, student, expectedChatId = 7L))
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

    @Test fun teacherRoleConsumesParentCommandsAtGatewayBoundaryButStudentKeepsThem() {
        assertFalse(shouldAcceptParentInbound(false, null))
        assertFalse(shouldAcceptParentInbound(true, RemoteReviewRole.TEACHER))
        assertTrue(shouldAcceptParentInbound(true, RemoteReviewRole.STUDENT))
        assertTrue(shouldAcceptParentInbound(true, null))
    }

    @Test fun inboundPeerResponseConsumesTerminalTombstonesButRetriesRecoverableFailures() {
        listOf(
            TelegramEnqueueResult.ENQUEUED,
            TelegramEnqueueResult.ALREADY_PENDING,
            TelegramEnqueueResult.ALREADY_DELIVERED,
            TelegramEnqueueResult.PREVIOUSLY_DEAD,
            TelegramEnqueueResult.PREVIOUSLY_SUPERSEDED,
        ).forEach(::ensurePeerResponseDurablySettled)
        listOf(
            TelegramEnqueueResult.QUEUE_FULL,
            TelegramEnqueueResult.NOT_CONFIGURED,
            TelegramEnqueueResult.CHAT_CHANGED,
        ).forEach { result ->
            val error = assertThrows(TelegramPeerResponseRetryException::class.java) {
                ensurePeerResponseDurablySettled(result)
            }
            assertEquals(result, error.result)
            assertEquals(
                if (result == TelegramEnqueueResult.QUEUE_FULL) {
                    TelegramPeerTransportFailure.RESPONSE_QUEUE_FULL
                } else {
                    TelegramPeerTransportFailure.RESPONSE_UNAVAILABLE
                },
                telegramPeerTransportFailure(error),
            )
        }
    }

    private fun credentials(chatId: Long, role: RemoteReviewRole? = null) = TelegramCredentials(
        botToken = "123456:test-token",
        allowedPrivateChatId = chatId,
        chatLabel = "parent",
        remoteReviewRole = role,
        peerPairId = role?.let { "pair_identifier_123" },
        peerSharedKeyBase64 = role?.let { TelegramPeerProtocol.encodeKey(ByteArray(32) { 1 }) },
        peerPairingExpiresAtEpochMs = role?.let { 1_000_000L },
    )
}
