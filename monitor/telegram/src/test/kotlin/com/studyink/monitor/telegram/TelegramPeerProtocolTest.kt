package com.studyink.monitor.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TelegramPeerProtocolTest {
    @Test fun teacherAndStudentMustUseDifferentBots() {
        val teacher = TelegramBotIdentity(101L, "teacher_bot", "Teacher")
        requireDistinctRemoteReviewBots(teacher, 202L)

        try {
            requireDistinctRemoteReviewBots(teacher, 101L)
            fail("The same bot must not be accepted on both devices")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("서로 다른 Telegram 봇"))
        }
    }

    @Test fun unboundPairingStopsBeingConfiguredAfterQrExpiry() {
        val key = TelegramPeerProtocol.encodeKey(TelegramPeerProtocol.newSharedKey())
        val waiting = TelegramCredentials(
            botToken = "123456:abcdefghijklmnopqrstuvwxyz",
            allowedPrivateChatId = 77L,
            chatLabel = "parent",
            localBotId = 101L,
            localBotUsername = "student_bot",
            remoteReviewRole = RemoteReviewRole.STUDENT,
            peerPairId = "pair_identifier_123",
            peerSharedKeyBase64 = key,
            peerPairingExpiresAtEpochMs = 20_000L,
        )

        assertTrue(resolveRemoteReviewPeerStatus(waiting, null, 19_999L) is RemoteReviewPeerStatus.WaitingForTeacher)
        assertTrue(resolveRemoteReviewPeerStatus(waiting, null, 20_001L) is RemoteReviewPeerStatus.Unconfigured)

        val connected = waiting.withPeer(TelegramPeerBinding(202L, "teacher_bot"))
        assertTrue(resolveRemoteReviewPeerStatus(connected, null, 30_000L) is RemoteReviewPeerStatus.Connected)
    }

    @Test fun studentQrCarriesIdentityAndKeyButNeverBotToken() {
        val now = 10_000L
        val key = TelegramPeerProtocol.newSharedKey()
        val payload = TelegramPeerProtocol.createStudentPayload(
            TelegramBotIdentity(101L, "student_bot", "Student"),
            "pair_identifier_123",
            TelegramPeerProtocol.encodeKey(key),
            now + 60_000L,
        )

        assertFalse(payload.encoded.contains("123456:secret-token"))
        val decoded = TelegramPeerProtocol.decodeStudentPayload(payload.encoded, now)
        assertEquals(101L, decoded.studentBotId)
        assertEquals("student_bot", decoded.studentBotUsername)
        assertEquals("pair_identifier_123", decoded.pairId)
        assertTrue(key.contentEquals(TelegramPeerProtocol.decodeKey(decoded.sharedKeyBase64)))
    }

    @Test fun handshakeAndAckAreAuthenticatedAndTamperingIsRejected() {
        val key = TelegramPeerProtocol.newSharedKey()
        val hello = TelegramPeerProtocol.hello(
            "pair_identifier_123",
            TelegramBotIdentity(202L, "teacher_bot", "Teacher"),
            "nonce_identifier_123",
            key,
        )
        assertNotNull(TelegramPeerProtocol.parseControl(hello, key))
        assertNull(TelegramPeerProtocol.parseControl(hello.replace("202", "203"), key))

        val ack = TelegramPeerProtocol.deliveryAck("pair_identifier_123", "transfer_123", key)
        val parsed = TelegramPeerProtocol.parseControl(ack, key)
        assertEquals("transfer_123", (parsed as TelegramPeerProtocol.PeerControl.Received).transferId)
    }

    @Test fun lightweightConnectionControlsRoundTripWithSignedCorrelationFields() {
        val key = TelegramPeerProtocol.newSharedKey()
        val sentAt = 1_000_000L
        val expiresAt = sentAt + TelegramPeerProtocol.DEFAULT_CONTROL_REQUEST_LIFETIME_MS

        assertEquals(
            TelegramPeerProtocol.PeerControl.ConnectRequest("request_123", sentAt, expiresAt),
            TelegramPeerProtocol.parseControl(
                TelegramPeerProtocol.connectRequest("request_123", sentAt, expiresAt, key),
                key,
            ),
        )
        assertEquals(
            TelegramPeerProtocol.PeerControl.ConnectAccept("request_123", sentAt + 1L),
            TelegramPeerProtocol.parseControl(
                TelegramPeerProtocol.connectAccept("request_123", sentAt + 1L, key),
                key,
            ),
        )
        assertEquals(
            TelegramPeerProtocol.PeerControl.Ping("session_123", "nonce_123", sentAt, expiresAt),
            TelegramPeerProtocol.parseControl(
                TelegramPeerProtocol.ping("session_123", "nonce_123", sentAt, expiresAt, key),
                key,
            ),
        )
        assertEquals(
            TelegramPeerProtocol.PeerControl.Pong("session_123", "nonce_123", sentAt + 2L),
            TelegramPeerProtocol.parseControl(
                TelegramPeerProtocol.pong("session_123", "nonce_123", sentAt + 2L, key),
                key,
            ),
        )
    }

    @Test fun lightweightControlsRejectTamperingWrongKeysAndMalformedWindows() {
        val key = TelegramPeerProtocol.newSharedKey()
        val otherKey = TelegramPeerProtocol.newSharedKey()
        val sentAt = 2_000_000L
        val expiresAt = sentAt + 30_000L
        val request = TelegramPeerProtocol.connectRequest("request_123", sentAt, expiresAt, key)
        val ping = TelegramPeerProtocol.ping("session_123", "nonce_123", sentAt, expiresAt, key)

        assertNull(TelegramPeerProtocol.parseControl(request.replace("request_123", "request_124"), key))
        assertNull(TelegramPeerProtocol.parseControl(ping.replace("nonce_123", "nonce_124"), key))
        assertNull(TelegramPeerProtocol.parseControl(request, otherKey))
        assertNull(TelegramPeerProtocol.parseControl(ping, otherKey))
        assertNull(TelegramPeerProtocol.parseControl("MNTP1 CONNECT_ACCEPT bad/id 100 signature", key))

        try {
            TelegramPeerProtocol.connectRequest(
                "request_123",
                sentAt,
                sentAt - 1L,
                key,
            )
            fail("A control message cannot expire before it was sent")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test fun parserPreservesSignedExpiryForGatewayFreshnessPolicy() {
        val key = TelegramPeerProtocol.newSharedKey()
        val alreadyOldSentAt = 10_000L
        val alreadyOldExpiry = 20_000L

        val request = TelegramPeerProtocol.parseControl(
            TelegramPeerProtocol.connectRequest(
                "request_old",
                alreadyOldSentAt,
                alreadyOldExpiry,
                key,
            ),
            key,
        ) as TelegramPeerProtocol.PeerControl.ConnectRequest
        val ping = TelegramPeerProtocol.parseControl(
            TelegramPeerProtocol.ping(
                "session_old",
                "nonce_old",
                alreadyOldSentAt,
                alreadyOldExpiry,
                key,
            ),
            key,
        ) as TelegramPeerProtocol.PeerControl.Ping

        assertEquals(alreadyOldExpiry, request.expiresAtEpochMs)
        assertEquals(alreadyOldExpiry, ping.expiresAtEpochMs)
    }
}
