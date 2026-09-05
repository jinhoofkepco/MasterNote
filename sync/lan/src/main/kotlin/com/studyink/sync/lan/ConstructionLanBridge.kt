package com.studyink.sync.lan

import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

data class ConstructionLanPeer(val localBookId: String, val documentSha256: String,
    val peerDeviceId: String, val peerIsStudent: Boolean, val sessionId: Long)

/** Socket receipt is not a durable application acknowledgement. */
object ConstructionLanBridge {
    const val MAX_PACKET_BYTES = 4 * 1024 * 1024
    interface Transport {
        fun peer(bookId: String): ConstructionLanPeer?
        fun send(bookId: String, payload: ByteArray, expectedPeer: ConstructionLanPeer): Boolean
    }
    private var transport: Transport? = null
    private val sessions = AtomicLong()
    private val receivers = CopyOnWriteArrayList<(ConstructionLanPeer, ByteArray) -> Unit>()
    fun registerTransport(value: Transport): AutoCloseable {
        synchronized(this) { transport = value }
        return AutoCloseable { synchronized(this) { if (transport === value) transport = null } }
    }
    fun peer(bookId: String): ConstructionLanPeer? {
        val current = synchronized(this) { transport } ?: return null
        return runCatching { current.peer(bookId) }.getOrNull()
    }
    fun send(bookId: String, payload: ByteArray, expectedPeer: ConstructionLanPeer): Boolean {
        if (bookId.isBlank() || bookId != expectedPeer.localBookId || payload.size !in 1..MAX_PACKET_BYTES) return false
        val current = synchronized(this) { transport } ?: return false
        return runCatching { current.send(bookId, payload.copyOf(), expectedPeer) }.getOrDefault(false)
    }
    fun addReceiver(receiver: (ConstructionLanPeer, ByteArray) -> Unit): AutoCloseable {
        receivers += receiver
        return AutoCloseable { receivers -= receiver }
    }
    internal fun nextSessionId(): Long = sessions.incrementAndGet().also { check(it > 0L) }
    internal fun deliver(context: ConstructionLanPeer, payload: ByteArray) {
        if (payload.size !in 1..MAX_PACKET_BYTES || peer(context.localBookId) != context) return
        receivers.forEach { if (peer(context.localBookId) == context) runCatching { it(context, payload.copyOf()) } }
    }
}

internal object ConstructionLanWire {
    const val TYPE = "CONSTRUCTION_CHUNK"
    const val CHUNK_BYTES = 128 * 1024
    const val MAX_CHUNKS = 32
    const val TTL_MILLIS = 60_000L
    fun frames(bookId: String, payload: ByteArray): Sequence<String> {
        require(bookId.isNotBlank() && bookId.length <= 512)
        require(payload.size in 1..ConstructionLanBridge.MAX_PACKET_BYTES)
        val id = UUID.randomUUID().toString()
        val digest = digest(payload)
        val count = (payload.size + CHUNK_BYTES - 1) / CHUNK_BYTES
        return (0 until count).asSequence().map { index ->
            val start = index * CHUNK_BYTES
            val part = payload.copyOfRange(start, minOf(start + CHUNK_BYTES, payload.size))
            LanWire.message(TYPE) {
                put("sourceBookId", bookId); put("transferId", id); put("digestSha256", digest)
                put("payloadSize", payload.size); put("chunkIndex", index); put("chunkCount", count)
                put("payload", Base64.getEncoder().encodeToString(part))
            }
        }
    }
    internal data class Chunk(val id: String, val digest: String, val size: Int, val index: Int, val count: Int, val bytes: ByteArray)
    internal fun decode(message: JSONObject, expectedSourceBook: String): Chunk {
        require(message.text("type", 64) == TYPE)
        require(message.text("sourceBookId", 512) == expectedSourceBook)
        val id = message.text("transferId", 36)
        require(UUID.fromString(id).toString() == id)
        val digest = message.text("digestSha256", 64)
        require(Regex("[0-9a-f]{64}").matches(digest))
        val size = message.exactInt("payloadSize")
        val count = message.exactInt("chunkCount")
        val index = message.exactInt("chunkIndex")
        require(size in 1..ConstructionLanBridge.MAX_PACKET_BYTES)
        require(count in 1..MAX_CHUNKS && count == (size + CHUNK_BYTES - 1) / CHUNK_BYTES)
        require(index in 0 until count)
        val encoded = message.text("payload", ((CHUNK_BYTES + 2) / 3) * 4)
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size == minOf(CHUNK_BYTES, size - index * CHUNK_BYTES))
        require(Base64.getEncoder().encodeToString(bytes) == encoded)
        return Chunk(id, digest, size, index, count, bytes)
    }
    internal fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private fun JSONObject.text(name: String, limit: Int): String =
        (get(name) as? String)?.also { require(it.length <= limit) } ?: error("Invalid $name")
    private fun JSONObject.exactInt(name: String): Int {
        val value = get(name)
        require(value is Number)
        return requireNotNull(value.toString().toIntOrNull()) { "Invalid $name" }
    }
}

/** Bounds memory and rejects transfers crossing an authenticated connection generation. */
internal class ConstructionLanAssembly {
    private data class Pending(val digest: String, val size: Int, val bornAt: Long, val parts: Array<ByteArray?>)
    private val pending = linkedMapOf<Pair<Long, String>, Pending>()
    @Synchronized fun clear() = pending.clear()
    @Synchronized fun accept(message: JSONObject, expectedSourceBook: String, session: Long, nowMillis: Long): ByteArray? {
        require(session > 0 && nowMillis >= 0)
        pending.entries.removeAll { nowMillis < it.value.bornAt || nowMillis - it.value.bornAt >= ConstructionLanWire.TTL_MILLIS }
        val chunk = ConstructionLanWire.decode(message, expectedSourceBook)
        val key = session to chunk.id
        val prior = pending[key]
        require(prior != null || chunk.index == 0) { "Construction chunks must begin at zero" }
        val assembly = prior ?: run {
            if (pending.size >= 4) pending.remove(pending.keys.first())
            Pending(chunk.digest, chunk.size, nowMillis, arrayOfNulls(chunk.count)).also { pending[key] = it }
        }
        require(assembly.digest == chunk.digest && assembly.size == chunk.size && assembly.parts.size == chunk.count)
        val previous = assembly.parts[chunk.index]
        require(previous == null || previous.contentEquals(chunk.bytes))
        assembly.parts[chunk.index] = chunk.bytes
        if (assembly.parts.any { it == null }) return null
        pending.remove(key)
        val bytes = ByteArray(assembly.size)
        assembly.parts.forEachIndexed { index, part -> requireNotNull(part).copyInto(bytes, index * ConstructionLanWire.CHUNK_BYTES) }
        require(ConstructionLanWire.digest(bytes) == assembly.digest)
        return bytes
    }
}
