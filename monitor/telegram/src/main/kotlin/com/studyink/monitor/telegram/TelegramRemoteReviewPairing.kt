package com.studyink.monitor.telegram

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class RemoteReviewPairingPayload(
    val encoded: String,
    val pairId: String,
    val expiresAtEpochMs: Long,
)

sealed interface RemoteReviewPeerStatus {
    data object Unconfigured : RemoteReviewPeerStatus
    data class WaitingForTeacher(val pairId: String, val expiresAtEpochMs: Long) : RemoteReviewPeerStatus
    data class WaitingForStudentAck(
        val pairId: String,
        val expectedStudentBotId: Long,
        val expiresAtEpochMs: Long,
    ) : RemoteReviewPeerStatus
    data class Connected(
        val role: RemoteReviewRole,
        val pairId: String,
        val peer: TelegramPeerBinding,
    ) : RemoteReviewPeerStatus
}

internal fun resolveRemoteReviewPeerStatus(
    credentials: TelegramCredentials?,
    pending: TelegramPeerHandshakeState?,
    nowEpochMs: Long,
): RemoteReviewPeerStatus {
    require(nowEpochMs >= 0L)
    val active = credentials ?: return RemoteReviewPeerStatus.Unconfigured
    val role = active.remoteReviewRole ?: return RemoteReviewPeerStatus.Unconfigured
    val pairId = active.peerPairId ?: return RemoteReviewPeerStatus.Unconfigured
    active.peerBinding?.let { return RemoteReviewPeerStatus.Connected(role, pairId, it) }
    val expiresAt = active.peerPairingExpiresAtEpochMs ?: return RemoteReviewPeerStatus.Unconfigured
    if (nowEpochMs > expiresAt) return RemoteReviewPeerStatus.Unconfigured
    return if (role == RemoteReviewRole.TEACHER && pending?.expectedPeerBotId != null) {
        RemoteReviewPeerStatus.WaitingForStudentAck(pairId, pending.expectedPeerBotId, expiresAt)
    } else {
        RemoteReviewPeerStatus.WaitingForTeacher(pairId, expiresAt)
    }
}

internal data class DecodedStudentPairingPayload(
    val pairId: String,
    val studentBotId: Long,
    val studentBotUsername: String,
    val sharedKeyBase64: String,
    val expiresAtEpochMs: Long,
)

internal fun requireDistinctRemoteReviewBots(
    localBot: TelegramBotIdentity,
    studentBotId: Long,
) {
    require(localBot.id != studentBotId) {
        "두 기기에는 서로 다른 Telegram 봇을 연결해야 합니다."
    }
}

internal data class TelegramPeerHandshakeState(
    val role: RemoteReviewRole,
    val pairId: String,
    val expectedPeerBotId: Long?,
    val expectedPeerUsername: String?,
    val nonce: String?,
    val expiresAtEpochMs: Long,
)

internal class TelegramPeerHandshakeStateStore(private val file: java.io.File) {
    @Synchronized fun save(value: TelegramPeerHandshakeState) {
        val fields = listOf(
            VERSION,
            value.role.name,
            value.pairId,
            value.expectedPeerBotId?.toString().orEmpty(),
            value.expectedPeerUsername.orEmpty(),
            value.nonce.orEmpty(),
            value.expiresAtEpochMs.toString(),
        ).joinToString("\t") { encode(it) }
        AtomicDiskFile.writeText(file, fields + "\n")
    }

    @Synchronized fun load(): TelegramPeerHandshakeState? = runCatching {
        val fields = file.readText(StandardCharsets.UTF_8).trim().split('\t').map(::decode)
        if (fields.size != 7 || fields[0] != VERSION) return null
        TelegramPeerHandshakeState(
            role = RemoteReviewRole.valueOf(fields[1]),
            pairId = fields[2].also { require(PEER_IDENTIFIER.matches(it)) },
            expectedPeerBotId = fields[3].takeIf(String::isNotBlank)?.toLong(),
            expectedPeerUsername = fields[4].takeIf(String::isNotBlank)?.let(::normalizeTelegramUsername),
            nonce = fields[5].takeIf(String::isNotBlank),
            expiresAtEpochMs = fields[6].toLong().also { require(it > 0L) },
        )
    }.getOrNull()

    @Synchronized fun clear() { file.delete() }

    private fun encode(value: String) = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    private fun decode(value: String) = Base64.getUrlDecoder().decode(value)
        .toString(StandardCharsets.UTF_8)

    private companion object { const val VERSION = "MNPH1" }
}

internal object TelegramPeerProtocol {
    const val VERSION = "MNTP1"
    const val CIPHERTEXT_MIME = "application/vnd.masternote.peer+encrypted"

    fun createStudentPayload(
        identity: TelegramBotIdentity,
        pairId: String,
        keyBase64: String,
        expiresAtEpochMs: Long,
    ): RemoteReviewPairingPayload {
        val username = normalizeTelegramUsername(requireNotNull(identity.username))
        require(identity.id > 0L && PEER_IDENTIFIER.matches(pairId))
        require(decodeKey(keyBase64).size == 32 && expiresAtEpochMs > 0L)
        val json = JSONObject()
            .put("v", 1)
            .put("pair", pairId)
            .put("bot_id", identity.id)
            .put("bot_user", username)
            .put("key", keyBase64)
            .put("exp", expiresAtEpochMs)
            .toString()
        val body = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(StandardCharsets.UTF_8))
        return RemoteReviewPairingPayload("masternote-telegram-peer:v1:$body", pairId, expiresAtEpochMs)
    }

    fun decodeStudentPayload(encoded: String, nowEpochMs: Long): DecodedStudentPairingPayload {
        val body = encoded.trim().removePrefix(PAYLOAD_PREFIX)
        require(body != encoded.trim() && body.length <= 2_048) { "Invalid remote-review QR payload." }
        val json = JSONObject(Base64.getUrlDecoder().decode(body).toString(StandardCharsets.UTF_8))
        require(json.getInt("v") == 1)
        val expiresAt = json.getLong("exp")
        require(nowEpochMs <= expiresAt) { "Remote-review QR has expired." }
        require(expiresAt - nowEpochMs <= MAX_PAIRING_LIFETIME_MS) { "Remote-review QR expiry is invalid." }
        return DecodedStudentPairingPayload(
            pairId = json.getString("pair").also { require(PEER_IDENTIFIER.matches(it)) },
            studentBotId = json.getLong("bot_id").also { require(it > 0L) },
            studentBotUsername = normalizeTelegramUsername(json.getString("bot_user")),
            sharedKeyBase64 = json.getString("key").also { require(decodeKey(it).size == 32) },
            expiresAtEpochMs = expiresAt,
        )
    }

    fun hello(
        pairId: String,
        teacher: TelegramBotIdentity,
        nonce: String,
        key: ByteArray,
    ): String {
        val username = normalizeTelegramUsername(requireNotNull(teacher.username))
        val unsigned = "$VERSION HELLO $pairId ${teacher.id} $username $nonce"
        return "$unsigned ${mac(key, unsigned)}"
    }

    fun ack(
        pairId: String,
        student: TelegramBotIdentity,
        nonce: String,
        key: ByteArray,
    ): String {
        val username = normalizeTelegramUsername(requireNotNull(student.username))
        val unsigned = "$VERSION PAIR_ACK $pairId ${student.id} $username $nonce"
        return "$unsigned ${mac(key, unsigned)}"
    }

    fun documentCaption(pairId: String, transferId: String, payloadType: String): String {
        require(PEER_IDENTIFIER.matches(pairId))
        require(PEER_IDENTIFIER.matches(transferId))
        require(PAYLOAD_TYPE.matches(payloadType))
        return "$VERSION DOC $pairId $transferId $payloadType"
    }

    fun deliveryAck(pairId: String, transferId: String, key: ByteArray): String {
        require(PEER_IDENTIFIER.matches(pairId) && PEER_IDENTIFIER.matches(transferId))
        val unsigned = "$VERSION RECEIVED $pairId $transferId"
        return "$unsigned ${mac(key, unsigned)}"
    }

    fun connectRequest(
        requestId: String,
        sentAtEpochMs: Long,
        expiresAtEpochMs: Long,
        key: ByteArray,
    ): String {
        requireControlId(requestId, "requestId")
        requireExpiringControlWindow(sentAtEpochMs, expiresAtEpochMs)
        val unsigned = "$VERSION CONNECT_REQUEST $requestId $sentAtEpochMs $expiresAtEpochMs"
        return "$unsigned ${mac(key, unsigned)}"
    }

    fun connectAccept(
        requestId: String,
        sentAtEpochMs: Long,
        key: ByteArray,
    ): String {
        requireControlId(requestId, "requestId")
        require(sentAtEpochMs > 0L)
        val unsigned = "$VERSION CONNECT_ACCEPT $requestId $sentAtEpochMs"
        return "$unsigned ${mac(key, unsigned)}"
    }

    fun ping(
        sessionId: String,
        nonce: String,
        sentAtEpochMs: Long,
        expiresAtEpochMs: Long,
        key: ByteArray,
    ): String {
        requireControlId(sessionId, "sessionId")
        requireControlId(nonce, "nonce")
        requireExpiringControlWindow(sentAtEpochMs, expiresAtEpochMs)
        val unsigned = "$VERSION PING $sessionId $nonce $sentAtEpochMs $expiresAtEpochMs"
        return "$unsigned ${mac(key, unsigned)}"
    }

    fun pong(
        sessionId: String,
        nonce: String,
        sentAtEpochMs: Long,
        key: ByteArray,
    ): String {
        requireControlId(sessionId, "sessionId")
        requireControlId(nonce, "nonce")
        require(sentAtEpochMs > 0L)
        val unsigned = "$VERSION PONG $sessionId $nonce $sentAtEpochMs"
        return "$unsigned ${mac(key, unsigned)}"
    }

    fun parseDocumentCaption(value: String?): PeerDocumentHeader? {
        val fields = value?.trim()?.split(' ') ?: return null
        if (fields.size != 5 || fields[0] != VERSION || fields[1] != "DOC") return null
        if (!PEER_IDENTIFIER.matches(fields[2]) || !PEER_IDENTIFIER.matches(fields[3]) ||
            !PAYLOAD_TYPE.matches(fields[4])
        ) return null
        return PeerDocumentHeader(fields[2], fields[3], fields[4])
    }

    fun parseControl(value: String?, key: ByteArray): PeerControl? {
        val fields = value?.trim()?.split(' ') ?: return null
        if (fields.firstOrNull() != VERSION) return null
        return when {
            fields.size == 7 && fields[1] == "HELLO" -> {
                if (!PEER_IDENTIFIER.matches(fields[2])) return null
                val unsigned = fields.take(6).joinToString(" ")
                if (!validMac(key, unsigned, fields[6])) null else PeerControl.Hello(
                    pairId = fields[2],
                    botId = fields[3].toLongOrNull() ?: return null,
                    username = runCatching { normalizeTelegramUsername(fields[4]) }.getOrNull() ?: return null,
                    nonce = fields[5].takeIf(PEER_IDENTIFIER::matches) ?: return null,
                )
            }
            fields.size == 7 && fields[1] == "PAIR_ACK" -> {
                if (!PEER_IDENTIFIER.matches(fields[2])) return null
                val unsigned = fields.take(6).joinToString(" ")
                if (!validMac(key, unsigned, fields[6])) null else PeerControl.PairAck(
                    pairId = fields[2],
                    botId = fields[3].toLongOrNull() ?: return null,
                    username = runCatching { normalizeTelegramUsername(fields[4]) }.getOrNull() ?: return null,
                    nonce = fields[5].takeIf(PEER_IDENTIFIER::matches) ?: return null,
                )
            }
            fields.size == 5 && fields[1] == "RECEIVED" -> {
                if (!PEER_IDENTIFIER.matches(fields[2]) || !PEER_IDENTIFIER.matches(fields[3])) return null
                val unsigned = fields.take(4).joinToString(" ")
                if (!validMac(key, unsigned, fields[4])) null else PeerControl.Received(
                    pairId = fields[2],
                    transferId = fields[3],
                )
            }
            fields.size == 6 && fields[1] == "CONNECT_REQUEST" -> {
                val requestId = fields[2].takeIf(PEER_IDENTIFIER::matches) ?: return null
                val unsigned = fields.take(5).joinToString(" ")
                if (!validMac(key, unsigned, fields[5])) return null
                val sentAt = fields[3].toLongOrNull() ?: return null
                val expiresAt = fields[4].toLongOrNull() ?: return null
                if (!isValidExpiringControlWindow(sentAt, expiresAt)) return null
                PeerControl.ConnectRequest(requestId, sentAt, expiresAt)
            }
            fields.size == 5 && fields[1] == "CONNECT_ACCEPT" -> {
                val requestId = fields[2].takeIf(PEER_IDENTIFIER::matches) ?: return null
                val unsigned = fields.take(4).joinToString(" ")
                if (!validMac(key, unsigned, fields[4])) return null
                val sentAt = fields[3].toLongOrNull() ?: return null
                if (sentAt <= 0L) return null
                PeerControl.ConnectAccept(requestId, sentAt)
            }
            fields.size == 7 && fields[1] == "PING" -> {
                val sessionId = fields[2].takeIf(PEER_IDENTIFIER::matches) ?: return null
                val nonce = fields[3].takeIf(PEER_IDENTIFIER::matches) ?: return null
                val unsigned = fields.take(6).joinToString(" ")
                if (!validMac(key, unsigned, fields[6])) return null
                val sentAt = fields[4].toLongOrNull() ?: return null
                val expiresAt = fields[5].toLongOrNull() ?: return null
                if (!isValidExpiringControlWindow(sentAt, expiresAt)) return null
                PeerControl.Ping(sessionId, nonce, sentAt, expiresAt)
            }
            fields.size == 6 && fields[1] == "PONG" -> {
                val sessionId = fields[2].takeIf(PEER_IDENTIFIER::matches) ?: return null
                val nonce = fields[3].takeIf(PEER_IDENTIFIER::matches) ?: return null
                val unsigned = fields.take(5).joinToString(" ")
                if (!validMac(key, unsigned, fields[5])) return null
                val sentAt = fields[4].toLongOrNull() ?: return null
                if (sentAt <= 0L) return null
                PeerControl.Pong(sessionId, nonce, sentAt)
            }
            else -> null
        }
    }

    fun newPairId(): String = randomId(18)
    fun newNonce(): String = randomId(18)
    fun newRequestId(): String = randomId(18)
    fun newSessionId(): String = randomId(18)
    fun newSharedKey(): ByteArray = ByteArray(32).also(SECURE_RANDOM::nextBytes)
    fun encodeKey(key: ByteArray): String {
        require(key.size == 32)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key)
    }
    fun decodeKey(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

    private fun randomId(bytes: Int): String = ByteArray(bytes).also(SECURE_RANDOM::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    private fun mac(key: ByteArray, unsigned: String): String {
        require(key.size == 32)
        val bytes = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(unsigned.toByteArray(StandardCharsets.UTF_8))
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun validMac(key: ByteArray, unsigned: String, encoded: String): Boolean {
        val expected = runCatching { Base64.getUrlDecoder().decode(mac(key, unsigned)) }.getOrNull() ?: return false
        val actual = runCatching { Base64.getUrlDecoder().decode(encoded) }.getOrNull() ?: return false
        return MessageDigest.isEqual(expected, actual)
    }

    private fun requireControlId(value: String, field: String) {
        require(PEER_IDENTIFIER.matches(value)) { "$field is invalid" }
    }

    private fun requireExpiringControlWindow(sentAtEpochMs: Long, expiresAtEpochMs: Long) {
        require(sentAtEpochMs > 0L)
        require(expiresAtEpochMs >= sentAtEpochMs)
    }

    private fun isValidExpiringControlWindow(
        sentAtEpochMs: Long,
        expiresAtEpochMs: Long,
    ): Boolean = sentAtEpochMs > 0L &&
        expiresAtEpochMs >= sentAtEpochMs

    data class PeerDocumentHeader(val pairId: String, val transferId: String, val payloadType: String)
    sealed interface PeerControl {
        data class Hello(val pairId: String, val botId: Long, val username: String, val nonce: String) : PeerControl
        data class PairAck(val pairId: String, val botId: Long, val username: String, val nonce: String) : PeerControl
        data class Received(val pairId: String, val transferId: String) : PeerControl
        data class ConnectRequest(
            val requestId: String,
            val sentAtEpochMs: Long,
            val expiresAtEpochMs: Long,
        ) : PeerControl
        data class ConnectAccept(
            val requestId: String,
            val sentAtEpochMs: Long,
        ) : PeerControl
        data class Ping(
            val sessionId: String,
            val nonce: String,
            val sentAtEpochMs: Long,
            val expiresAtEpochMs: Long,
        ) : PeerControl
        data class Pong(
            val sessionId: String,
            val nonce: String,
            val sentAtEpochMs: Long,
        ) : PeerControl
    }

    const val DEFAULT_PAIRING_LIFETIME_MS = 15L * 60L * 1_000L
    const val DEFAULT_CONTROL_REQUEST_LIFETIME_MS = 2L * 60L * 1_000L
    const val MAX_CONTROL_REQUEST_LIFETIME_MS = 5L * 60L * 1_000L
    const val MAX_CONTROL_RESPONSE_AGE_MS = 5L * 60L * 1_000L
    const val MAX_CONTROL_CLOCK_SKEW_MS = 2L * 60L * 1_000L
    private const val MAX_PAIRING_LIFETIME_MS = 30L * 60L * 1_000L
    private const val PAYLOAD_PREFIX = "masternote-telegram-peer:v1:"
    private val PAYLOAD_TYPE = Regex("^[A-Z][A-Z0-9_]{0,39}$")
    private val SECURE_RANDOM = SecureRandom()
}
