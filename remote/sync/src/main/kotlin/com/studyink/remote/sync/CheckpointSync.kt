package com.studyink.remote.sync

import com.studyink.remote.protocol.CHECKPOINT_CHUNK_BYTES
import com.studyink.remote.protocol.RemoteCheckpointChunk
import com.studyink.remote.protocol.RemotePageDigest
import com.studyink.remote.protocol.RemoteStrokeAsset
import com.studyink.remote.protocol.RemoteStrokeAssetListCodec
import com.studyink.remote.storage.RemoteReplicaPage
import com.studyink.remote.storage.RemoteReplicaStore
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

const val MAX_CHECKPOINT_CHUNKS = 256

fun pageDigest(pageId: String, revision: Long, strokes: List<RemoteStrokeAsset>): RemotePageDigest {
    val ids = strokes.map(RemoteStrokeAsset::strokeId).sorted()
    return RemotePageDigest(pageId, revision, ids.size, sha256(ids.joinToString("\u0000").encodeToByteArray()))
}

fun RemotePageDigest.matches(other: RemotePageDigest): Boolean =
    pageId == other.pageId && layerRevision == other.layerRevision &&
        activeStrokeCount == other.activeStrokeCount && sortedStrokeIdHash.contentEquals(other.sortedStrokeIdHash)

object CheckpointProducer {
    fun create(
        snapshotId: String,
        pageId: String,
        layerRevision: Long,
        strokes: List<RemoteStrokeAsset>,
    ): List<RemoteCheckpointChunk> {
        val body = RemoteStrokeAssetListCodec.encode(strokes)
        val hash = sha256(body)
        val count = maxOf(1, (body.size + CHECKPOINT_CHUNK_BYTES - 1) / CHECKPOINT_CHUNK_BYTES)
        require(count <= MAX_CHECKPOINT_CHUNKS) { "Checkpoint exceeds bounded assembly size" }
        return List(count) { index ->
            val start = index * CHECKPOINT_CHUNK_BYTES
            val end = minOf(body.size, start + CHECKPOINT_CHUNK_BYTES)
            RemoteCheckpointChunk(snapshotId, pageId, layerRevision, index, count, body.copyOfRange(start, end), hash)
        }
    }
}

sealed interface CheckpointReceiveResult {
    data class Pending(val receivedChunks: Int, val totalChunks: Int) : CheckpointReceiveResult
    data class Applied(val pageId: String, val layerRevision: Long, val strokeCount: Int) : CheckpointReceiveResult
    data class Rejected(val reason: String) : CheckpointReceiveResult
}

class CheckpointAssembler(
    private val sessionId: String,
    private val replicaStore: RemoteReplicaStore,
    private val pageNumber: (String) -> Int,
    private val nowEpochMillis: () -> Long,
) {
    private data class Assembly(
        val pageId: String,
        val revision: Long,
        val count: Int,
        val hash: ByteArray,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
    )

    private val assemblies = mutableMapOf<String, Assembly>()

    suspend fun receive(chunk: RemoteCheckpointChunk): CheckpointReceiveResult {
        if (chunk.chunkCount !in 1..MAX_CHECKPOINT_CHUNKS || chunk.chunkIndex !in 0 until chunk.chunkCount) {
            return CheckpointReceiveResult.Rejected("Invalid chunk bounds")
        }
        val assembly = assemblies.getOrPut(chunk.snapshotId) {
            Assembly(chunk.pageId, chunk.layerRevision, chunk.chunkCount, chunk.payloadHash.copyOf())
        }
        if (assembly.pageId != chunk.pageId || assembly.revision != chunk.layerRevision ||
            assembly.count != chunk.chunkCount || !assembly.hash.contentEquals(chunk.payloadHash)
        ) {
            assemblies.remove(chunk.snapshotId)
            return CheckpointReceiveResult.Rejected("Inconsistent checkpoint metadata")
        }
        assembly.chunks.putIfAbsent(chunk.chunkIndex, chunk.strokeAssets.copyOf())
        if (assembly.chunks.size < assembly.count) {
            return CheckpointReceiveResult.Pending(assembly.chunks.size, assembly.count)
        }
        assemblies.remove(chunk.snapshotId)
        val body = ByteArrayOutputStream().use { output ->
            repeat(assembly.count) { index -> output.write(requireNotNull(assembly.chunks[index])) }
            output.toByteArray()
        }
        if (!sha256(body).contentEquals(assembly.hash)) return CheckpointReceiveResult.Rejected("Checkpoint hash mismatch")
        val strokes = runCatching { RemoteStrokeAssetListCodec.decode(body) }
            .getOrElse { return CheckpointReceiveResult.Rejected("Checkpoint body is corrupt") }
        replicaStore.replacePageAtomically(RemoteReplicaPage(
            sessionId, assembly.pageId, pageNumber(assembly.pageId), assembly.revision,
            lastAppliedSequence = 0L, lastUpdatedAtEpochMillis = nowEpochMillis(), strokes = strokes,
        ))
        return CheckpointReceiveResult.Applied(assembly.pageId, assembly.revision, strokes.size)
    }

    fun discard(snapshotId: String) { assemblies.remove(snapshotId) }
    fun clear() { assemblies.clear() }
    fun pendingAssemblyCount(): Int = assemblies.size
}

private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
