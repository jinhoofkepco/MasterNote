package com.studyink.sync.lan

import android.net.Uri
import com.studyink.assistant.core.StudentExplanationLayer
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum class LanPeerRole { STUDENT_SERVER, TEACHER_CLIENT }

data class PairingPayload(
    val host: String,
    val port: Int,
    val bookId: String,
    val token: String,
) {
    fun toUri(): Uri = Uri.Builder().scheme("masternote").authority("pair")
        .appendQueryParameter("v", LAN_AUTH_VERSION.toString())
        .appendQueryParameter("host", host)
        .appendQueryParameter("port", port.toString())
        .appendQueryParameter("book", bookId)
        .appendQueryParameter("token", token)
        .build()

    companion object {
        fun parse(uri: Uri): PairingPayload {
            require(uri.scheme == "masternote" && uri.host == "pair")
            require(uri.getQueryParameter("v")?.toIntOrNull() == LAN_AUTH_VERSION) {
                "Unsupported LAN pairing payload"
            }
            return PairingPayload(
                host = requireNotNull(uri.getQueryParameter("host")),
                port = requireNotNull(uri.getQueryParameter("port")).toInt(),
                bookId = requireNotNull(uri.getQueryParameter("book")),
                token = requireNotNull(uri.getQueryParameter("token")),
            )
        }
    }
}

internal object LanWire {
    const val PROTOCOL_VERSION = 2
    const val MAX_LINE_CHARS = 800_000

    fun message(type: String, configure: JSONObject.() -> Unit = {}): String = JSONObject()
        .put("protocolVersion", PROTOCOL_VERSION)
        .put("type", type)
        .apply(configure)
        .toString()

    fun decode(line: String): JSONObject {
        require(line.length <= MAX_LINE_CHARS) { "LAN message too large" }
        val root = JSONObject(line)
        require(root.getInt("protocolVersion") == PROTOCOL_VERSION) { "Unsupported LAN protocol" }
        return root
    }
}

/** Page-scoped peer cursors. Annotation clocks are allocated independently by each page editor. */
internal class PageOperationWatermarks {
    private data class Key(val pageNumber: Int, val deviceId: String)

    private val clocks = mutableMapOf<Key, Long>()

    @Synchronized
    fun replace(pageNumber: Int, deviceId: String, logicalClock: Long) {
        require(pageNumber >= 0) { "Watermark page cannot be negative" }
        require(deviceId.isNotBlank()) { "Watermark device cannot be blank" }
        require(logicalClock >= 0L) { "Watermark clock cannot be negative" }
        clocks[Key(pageNumber, deviceId)] = logicalClock
    }

    @Synchronized
    fun acknowledge(pageNumber: Int, deviceId: String, logicalClock: Long) {
        require(pageNumber >= 0) { "Watermark page cannot be negative" }
        require(deviceId.isNotBlank()) { "Watermark device cannot be blank" }
        require(logicalClock >= 0L) { "Watermark clock cannot be negative" }
        val key = Key(pageNumber, deviceId)
        clocks[key] = maxOf(clocks[key] ?: 0L, logicalClock)
    }

    @Synchronized
    fun clock(pageNumber: Int, deviceId: String): Long =
        clocks[Key(pageNumber, deviceId)] ?: 0L

    @Synchronized
    fun clear() = clocks.clear()
}

/** The reader page currently open on this process, retained per book for late service startup. */
data class PagePresence(
    val bookId: String,
    val pageNumber: Int,
    val attemptNo: Int? = null,
    val revision: Long,
    val followRemoteStudent: Boolean = false,
) {
    init {
        require(bookId.isNotBlank()) { "Presence book id cannot be blank" }
        require(pageNumber >= 0) { "Presence page cannot be negative" }
        require(attemptNo == null || attemptNo >= 0) { "Presence attempt cannot be negative" }
        require(revision >= 0L) { "Presence revision cannot be negative" }
    }
}

/** The latest student location received from a paired device, retained per local book. */
data class StudentLocation(
    val bookId: String,
    val pageNumber: Int,
    val attemptNo: Int? = null,
    val revision: Long = 0L,
) {
    init {
        require(bookId.isNotBlank()) { "Student location book id cannot be blank" }
        require(pageNumber >= 0) { "Student location page cannot be negative" }
        require(attemptNo == null || attemptNo >= 0) { "Student location attempt cannot be negative" }
        require(revision >= 0L) { "Student location revision cannot be negative" }
    }
}

/** What the local device can currently say about its paired peer. */
enum class LanConnectionState { IDLE, CONNECTING, CONNECTED, DISCONNECTED }

/**
 * Application-level readiness kept separate from the socket state.
 *
 * A connected socket is not yet safe to prefer over the Telegram fallback: the peer still has to
 * authenticate and the teacher must receive every durable operation for the subscribed page.
 */
enum class LanSessionPhase {
    IDLE,
    CONNECTING,
    SOCKET_CONNECTED,
    HANDSHAKE_COMPLETE,
    PAGE_CATCHING_UP,
    READY,
    DISCONNECTED,
}

internal const val LAN_AUTH_VERSION = 2
/** V2 adds durable publication IDs, authority epochs, and exact digest ACKs. */
internal const val LAN_CAPABILITY_GPT_EXPLANATION_V2 = "GPT_EXPLANATION_V2"
private val LAN_SHA256_HEX = Regex("[0-9a-f]{64}")

internal fun isValidLanSha256(value: String): Boolean = LAN_SHA256_HEX.matches(value)

internal fun isExactLanGptExplanationAck(
    expectedPublicationId: String,
    expectedPageNumber: Int,
    expectedAttemptNo: Int,
    expectedRevision: Long,
    expectedDigestSha256: String,
    expectedAuthorityEpoch: String,
    publicationId: String,
    pageNumber: Int,
    attemptNo: Int,
    revision: Long,
    digestSha256: String,
    authorityEpoch: String,
): Boolean = expectedPublicationId == publicationId && expectedPageNumber == pageNumber &&
    expectedAttemptNo == attemptNo && expectedRevision == revision &&
    expectedDigestSha256 == digestSha256 && expectedAuthorityEpoch == authorityEpoch

internal fun newLanSecretHex(): String = ByteArray(32).also(SecureRandom()::nextBytes).toHex()

/** Public greeting by construction: the shared secret is not an input and cannot leak on the wire. */
internal fun lanHelloMessage(
    deviceId: String,
    role: LanPeerRole,
    bookId: String,
    documentSha256: String,
    nonceHex: String,
): String = LanWire.message("HELLO") {
    lanHelloPublicFields(deviceId, role, bookId, documentSha256, nonceHex).forEach { (key, value) ->
        put(key, value)
    }
    put("capabilities", JSONArray().put(LAN_CAPABILITY_GPT_EXPLANATION_V2))
}

internal fun lanHelloPublicFields(
    deviceId: String,
    role: LanPeerRole,
    bookId: String,
    documentSha256: String,
    nonceHex: String,
): Map<String, Any> {
    require(deviceId.isNotBlank() && deviceId.length <= 512)
    require(bookId.isNotBlank() && bookId.length <= 512)
    require(isValidLanSha256(documentSha256) && isValidLanSha256(nonceHex))
    return linkedMapOf(
        "authVersion" to LAN_AUTH_VERSION,
        "deviceId" to deviceId,
        "role" to role.name,
        "bookId" to bookId,
        "documentHash" to documentSha256,
        "nonce" to nonceHex,
    )
}

/**
 * Mutual-authentication proof over both socket nonces and both endpoint identities.
 * Length-prefixing every field makes the transcript unambiguous; sender role separation prevents
 * reflecting one side's proof back as the other side's proof.
 */
internal fun lanAuthProofHex(
    secretHex: String,
    senderNonceHex: String,
    receiverNonceHex: String,
    senderDeviceId: String,
    receiverDeviceId: String,
    senderRole: LanPeerRole,
    receiverRole: LanPeerRole,
    senderBookId: String,
    receiverBookId: String,
    documentSha256: String,
): String {
    require(isValidLanSha256(secretHex)) { "LAN shared secret is invalid" }
    require(isValidLanSha256(senderNonceHex) && isValidLanSha256(receiverNonceHex)) {
        "LAN authentication nonce is invalid"
    }
    require(isValidLanSha256(documentSha256)) { "LAN document digest is invalid" }
    val fields = listOf(
        "masternote-lan-auth-v$LAN_AUTH_VERSION",
        "${senderRole.name.lowercase()}-proof",
        senderNonceHex,
        receiverNonceHex,
        senderDeviceId,
        receiverDeviceId,
        senderRole.name,
        receiverRole.name,
        senderBookId,
        receiverBookId,
        documentSha256,
    )
    require(fields.drop(2).dropLast(1).all { it.isNotBlank() && it.length <= 512 }) {
        "LAN authentication identity is invalid"
    }
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secretHex.hexToBytes(), "HmacSHA256"))
    fields.forEach { field ->
        val bytes = field.toByteArray(StandardCharsets.UTF_8)
        mac.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        mac.update(bytes)
    }
    return mac.doFinal().toHex()
}

internal fun lanAuthProofMatches(expectedHex: String, receivedHex: String): Boolean =
    isValidLanSha256(expectedHex) && isValidLanSha256(receivedHex) &&
        MessageDigest.isEqual(expectedHex.hexToBytes(), receivedHex.hexToBytes())

private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { index ->
    substring(index * 2, index * 2 + 2).toInt(16).toByte()
}

private fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

/** One lock-consistent view used by transport arbitration immediately before durable enqueue. */
data class LanSessionSnapshot(
    val connectionState: LanConnectionState,
    val phase: LanSessionPhase,
)

/**
 * The LAN service's one current session and its lock-consistent transport state.
 *
 * Per-book sticky state is intentionally retained for late UI subscribers, so callers deciding
 * whether LAN owns application traffic must use this view instead of guessing a book id from a
 * Reader/session that may never have been opened or may already have closed.
 */
data class LanActiveSessionSnapshot(
    val bookId: String,
    val session: LanSessionSnapshot,
)

data class LanTeacherReviewPublication(
    val bookId: String,
    val pageNumber: Int,
    val attemptNo: Int,
    val publicationId: String,
) {
    init {
        require(bookId.isNotBlank() && pageNumber >= 0 && attemptNo > 0)
        require(publicationId.matches(Regex("[0-9a-f]{64}")))
    }
}

object LanSyncBus {
    interface Listener {
        fun onConnectionStateChanged(bookId: String, state: LanConnectionState) {}
        fun onSessionPhaseChanged(bookId: String, phase: LanSessionPhase) {}
        fun onLocalOperation(bookId: String, pageNumber: Int) {}
        fun onPageChanged(bookId: String, pageNumber: Int, revision: Long) {}
        fun onPagePresenceChanged(presence: PagePresence) {
            onPageChanged(presence.bookId, presence.pageNumber, presence.revision)
        }
        fun onRemoteOperation(bookId: String, pageNumber: Int) {}
        fun onRemoteMarkGroup(bookId: String, pageNumber: Int) {}
        fun onRemoteAttempt(bookId: String, pageNumber: Int) {}
        fun onRemotePageChanged(bookId: String, pageNumber: Int) {}
        fun onRemoteStudentLocationChanged(location: StudentLocation) {
            onRemotePageChanged(location.bookId, location.pageNumber)
        }
        fun onLocalTeacherReviewPublished(publication: LanTeacherReviewPublication) {}
        fun onTeacherReviewAcknowledged(publication: LanTeacherReviewPublication) {}
        fun onLocalGptExplanationLayerPublished(layer: StudentExplanationLayer) {}
        fun onRemoteGptExplanationLayerApplied(layer: StudentExplanationLayer) {}
        /** The four-second catch-up lease expired; close only the still-current unready socket. */
        fun onCatchUpYieldRequested(bookId: String) {}
        fun onPairingReady(bookId: String, pairingUri: String) {}
        fun onSessionIssue(message: String) {}
    }

    private val listeners = linkedSetOf<Listener>()
    private val localPagePresences = mutableMapOf<String, PagePresence>()
    private val remoteStudentLocations = mutableMapOf<String, StudentLocation>()
    private val connectionStates = mutableMapOf<String, LanConnectionState>()
    private val sessionPhases = mutableMapOf<String, LanSessionPhase>()
    private val sessionRoles = mutableMapOf<String, LanPeerRole>()
    private val pairingUris = mutableMapOf<String, String>()
    private var activeSessionBookId: String? = null

    fun addListener(listener: Listener) {
        synchronized(this) { listeners += listener }
    }

    fun removeListener(listener: Listener) {
        synchronized(this) { listeners -= listener }
    }

    fun localPagePresence(bookId: String): PagePresence? = synchronized(this) {
        localPagePresences[bookId]
    }

    fun remoteStudentLocation(bookId: String): StudentLocation? = synchronized(this) {
        remoteStudentLocations[bookId]
    }

    /** Retained so a reader opened after the session started still shows the right state. */
    fun connectionState(bookId: String): LanConnectionState = synchronized(this) {
        connectionStates[bookId] ?: LanConnectionState.IDLE
    }

    /** Sticky application-level readiness for late coordinator/reader startup. */
    fun sessionPhase(bookId: String): LanSessionPhase = synchronized(this) {
        sessionPhases[bookId] ?: LanSessionPhase.IDLE
    }

    /** The role actually owned by the running service, independent of the Reader's UI role. */
    fun sessionRole(bookId: String): LanPeerRole? = synchronized(this) { sessionRoles[bookId] }

    fun sessionSnapshot(bookId: String): LanSessionSnapshot = synchronized(this) {
        sessionSnapshotLocked(bookId)
    }

    /** The service's active book and state, read under the same monitor as both state maps. */
    fun activeSessionSnapshot(): LanActiveSessionSnapshot? = synchronized(this) {
        activeSessionSnapshotLocked()
    }

    /**
     * Runs one application-data ownership decision while holding the same monitor used by LAN
     * connection and session-phase transitions. This is deliberately a narrow lease rather than
     * a cached boolean: if [block] observes no active LAN owner, a socket cannot become visible to
     * the application until the guarded Telegram mutation has finished.
     *
     * Keep [block] bounded and never wait for another thread from it. Listener callbacks are
     * dispatched after this monitor is released, so re-reading bus state on the current thread is
     * safe and re-entrant.
     */
    fun <T> withActiveSessionLease(block: (LanActiveSessionSnapshot?) -> T): T =
        synchronized(this) { block(activeSessionSnapshotLocked()) }

    private fun activeSessionSnapshotLocked(): LanActiveSessionSnapshot? {
        val bookId = activeSessionBookId ?: return null
        return LanActiveSessionSnapshot(
            bookId = bookId,
            session = sessionSnapshotLocked(bookId),
        )
    }

    /**
     * The pairing code of the session already running for this book, if any. Retained so the QR can
     * be shown on demand without restarting a session that a peer may already be using.
     */
    fun pairingUri(bookId: String): String? = synchronized(this) { pairingUris[bookId] }

    internal fun connectionStateChanged(bookId: String, state: LanConnectionState) {
        if (bookId.isBlank()) return
        val snapshot = synchronized(this) {
            if (connectionStates[bookId] == state) return
            connectionStates[bookId] = state
            refreshActiveSessionLocked(bookId)
            listeners.toList()
        }
        snapshot.forEach { it.onConnectionStateChanged(bookId, state) }
    }

    internal fun clearConnectionState(bookId: String) {
        if (bookId.isBlank()) return
        synchronized(this) {
            pairingUris.remove(bookId)
            sessionRoles.remove(bookId)
        }
        connectionStateChanged(bookId, LanConnectionState.IDLE)
        sessionPhaseChanged(bookId, LanSessionPhase.IDLE)
    }

    internal fun sessionRoleChanged(bookId: String, role: LanPeerRole) {
        if (bookId.isBlank()) return
        synchronized(this) { sessionRoles[bookId] = role }
    }

    internal fun sessionPhaseChanged(bookId: String, phase: LanSessionPhase) {
        if (bookId.isBlank()) return
        val snapshot = synchronized(this) {
            if (sessionPhases[bookId] == phase) return
            sessionPhases[bookId] = phase
            refreshActiveSessionLocked(bookId)
            listeners.toList()
        }
        snapshot.forEach { it.onSessionPhaseChanged(bookId, phase) }
    }

    private fun sessionSnapshotLocked(bookId: String) = LanSessionSnapshot(
        connectionState = connectionStates[bookId] ?: LanConnectionState.IDLE,
        phase = sessionPhases[bookId] ?: LanSessionPhase.IDLE,
    )

    private fun refreshActiveSessionLocked(bookId: String) {
        val session = sessionSnapshotLocked(bookId)
        if (session.connectionState != LanConnectionState.IDLE || session.phase != LanSessionPhase.IDLE) {
            activeSessionBookId = bookId
        } else if (activeSessionBookId == bookId) {
            // Do not select another sticky per-book record: it belongs to an older service session.
            activeSessionBookId = null
        }
    }

    internal fun clearRemoteStudentLocation(bookId: String) {
        synchronized(this) { remoteStudentLocations.remove(bookId) }
    }

    fun operationWritten(bookId: String, pageNumber: Int) = listenerSnapshot().forEach {
        it.onLocalOperation(bookId, pageNumber)
    }

    /**
     * Requests an ownership handoff without publishing a false disconnect. The socket reader owns
     * the DISCONNECTED transition after every already-read LAN frame has finished applying.
     */
    fun requestCatchUpYield(bookId: String) {
        if (bookId.isBlank()) return
        listenerSnapshot().forEach { it.onCatchUpYieldRequested(bookId) }
    }

    fun teacherReviewPublished(publication: LanTeacherReviewPublication) = listenerSnapshot().forEach {
        it.onLocalTeacherReviewPublished(publication)
    }

    internal fun teacherReviewAcknowledged(publication: LanTeacherReviewPublication) =
        listenerSnapshot().forEach { it.onTeacherReviewAcknowledged(publication) }

    fun gptExplanationLayerPublished(layer: StudentExplanationLayer) = listenerSnapshot().forEach {
        it.onLocalGptExplanationLayerPublished(layer)
    }

    internal fun remoteGptExplanationLayerApplied(layer: StudentExplanationLayer) =
        listenerSnapshot().forEach { it.onRemoteGptExplanationLayerApplied(layer) }

    fun pageChanged(
        bookId: String,
        pageNumber: Int,
        revision: Long,
        attemptNo: Int? = null,
        followRemoteStudent: Boolean = false,
    ) = pageChanged(
        PagePresence(
            bookId = bookId,
            pageNumber = pageNumber,
            attemptNo = attemptNo,
            revision = revision,
            followRemoteStudent = followRemoteStudent,
        )
    )

    fun pageChanged(presence: PagePresence) {
        val snapshot = synchronized(this) {
            localPagePresences[presence.bookId] = presence
            listeners.toList()
        }
        snapshot.forEach { it.onPagePresenceChanged(presence) }
    }

    internal fun remoteOperation(bookId: String, pageNumber: Int) = listenerSnapshot().forEach {
        it.onRemoteOperation(bookId, pageNumber)
    }

    internal fun remoteMarkGroup(bookId: String, pageNumber: Int) = listenerSnapshot().forEach {
        it.onRemoteMarkGroup(bookId, pageNumber)
    }

    internal fun remoteAttempt(bookId: String, pageNumber: Int) = listenerSnapshot().forEach {
        it.onRemoteAttempt(bookId, pageNumber)
    }

    internal fun remotePageChanged(
        bookId: String,
        pageNumber: Int,
        attemptNo: Int? = null,
        revision: Long = 0L,
    ) = remotePageChanged(StudentLocation(bookId, pageNumber, attemptNo, revision))

    internal fun remotePageChanged(location: StudentLocation) {
        val snapshot = synchronized(this) {
            remoteStudentLocations[location.bookId] = location
            listeners.toList()
        }
        snapshot.forEach { it.onRemoteStudentLocationChanged(location) }
    }

    internal fun pairingReady(bookId: String, pairingUri: String) {
        val snapshot = synchronized(this) {
            pairingUris[bookId] = pairingUri
            listeners.toList()
        }
        snapshot.forEach { it.onPairingReady(bookId, pairingUri) }
    }

    internal fun sessionIssue(message: String) = listenerSnapshot().forEach {
        it.onSessionIssue(message)
    }

    private fun listenerSnapshot(): List<Listener> = synchronized(this) { listeners.toList() }
}
