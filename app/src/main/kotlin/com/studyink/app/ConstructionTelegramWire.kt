package com.studyink.app

import com.studyink.construction.storage.ConstructionSyncCodec
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/** Small independently encrypted documents; existing Telegram plaintext limits are unchanged. */
internal object ConstructionTelegramWire {
    const val CHUNK_BYTES = 512 * 1024
    const val MAX_FRAME_BYTES = 710_000
    data class Chunk(val address: ConstructionTelegramAddress, val transmissionId: String, val digest: String,
        val totalBytes: Int, val index: Int, val count: Int, val bytes: ByteArray) {
        val transferId: String get() = transferId(transmissionId, index)
    }
    fun frames(address: ConstructionTelegramAddress, payload: ByteArray, deliveryAttempt: String = UUID.randomUUID().toString()): List<Pair<String, ByteArray>> {
        require(payload.size in 1..ConstructionSyncCodec.MAX_PACKET_BYTES)
        val hash = digest(payload)
        require(UUID.fromString(deliveryAttempt).toString() == deliveryAttempt)
        val transmission = stableId(address.toString() + hash + deliveryAttempt)
        val count = (payload.size + CHUNK_BYTES - 1) / CHUNK_BYTES
        return (0 until count).map { index ->
            val part = payload.copyOfRange(index * CHUNK_BYTES, minOf((index + 1) * CHUNK_BYTES, payload.size))
            transferId(transmission, index) to JSONObject().put("format", 1)
                .put("pairId", address.pairId).put("syncGeneration", address.syncGeneration)
                .put("pageToken", address.pageToken).put("workbookToken", address.workbookToken)
                .put("contentSha256", address.contentSha256).put("pageNumber", address.pageNumber)
                .put("attemptNo", address.attemptNo).put("memoId", address.memoId)
                .put("transmissionId", transmission).put("deliveryAttempt", deliveryAttempt).put("digest", hash).put("totalBytes", payload.size)
                .put("index", index).put("count", count).put("payload", Base64.getEncoder().encodeToString(part))
                .toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_FRAME_BYTES) }
        }
    }
    fun decode(bytes: ByteArray): Chunk {
        require(bytes.size in 1..MAX_FRAME_BYTES)
        val j = JSONObject(bytes.toString(Charsets.UTF_8))
        require(j.number("format") == 1L)
        val a = ConstructionTelegramAddress(j.text("pairId", 128), j.number("syncGeneration"),
            j.text("pageToken", 256), j.text("workbookToken", 256), j.text("contentSha256", 64),
            j.number("pageNumber").boundedInt(0, 1_000_000), j.number("attemptNo").boundedInt(1, 1_000_000), j.uuid("memoId"))
        require(a.syncGeneration > 0 && Regex("[0-9a-f]{64}").matches(a.contentSha256))
        val id = j.uuid("transmissionId")
        val digest = j.text("digest", 64).also { require(Regex("[0-9a-f]{64}").matches(it)) }
        val size = j.number("totalBytes").boundedInt(1, ConstructionSyncCodec.MAX_PACKET_BYTES)
        val count = j.number("count").boundedInt(1, 8)
        require(count == (size + CHUNK_BYTES - 1) / CHUNK_BYTES)
        val index = j.number("index").boundedInt(0, count - 1)
        val encoded = j.text("payload", ((CHUNK_BYTES + 2) / 3) * 4)
        val part = Base64.getDecoder().decode(encoded)
        require(part.size == minOf(CHUNK_BYTES, size - index * CHUNK_BYTES))
        require(Base64.getEncoder().encodeToString(part) == encoded)
        require(id == stableId(a.toString() + digest + j.uuid("deliveryAttempt")))
        return Chunk(a, id, digest, size, index, count, part)
    }
    fun assemble(chunks: List<Chunk>): ByteArray? {
        val first = chunks.firstOrNull() ?: return null
        val parts = arrayOfNulls<ByteArray>(first.count)
        chunks.forEach {
            require(it.address == first.address && it.transmissionId == first.transmissionId && it.digest == first.digest &&
                it.totalBytes == first.totalBytes && it.count == first.count)
            require(parts[it.index] == null || parts[it.index]!!.contentEquals(it.bytes))
            parts[it.index] = it.bytes
        }
        if (parts.any { it == null }) return null
        val bytes = ByteArray(first.totalBytes)
        parts.forEachIndexed { i, part -> part!!.copyInto(bytes, i * CHUNK_BYTES) }
        require(digest(bytes) == first.digest)
        return bytes
    }
    fun stableId(text: String): String = UUID.nameUUIDFromBytes(text.toByteArray(Charsets.UTF_8)).toString()
    private fun transferId(id: String, index: Int) = stableId("construction:$id:$index")
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    private fun JSONObject.text(key: String, max: Int) = (get(key) as? String)?.also { require(it.isNotBlank() && it.length <= max) } ?: error("Invalid $key")
    private fun JSONObject.number(key: String): Long { val v = get(key); require(v is Number); return requireNotNull(v.toString().toLongOrNull()) }
    private fun JSONObject.uuid(key: String) = text(key, 36).also { require(UUID.fromString(it).toString() == it) }
    private fun Long.boundedInt(min: Int, max: Int) = also { require(it in min.toLong()..max.toLong()) }.toInt()
}
