package com.studyink.remote.sync

import com.studyink.remote.protocol.CHECKPOINT_CHUNK_BYTES
import com.studyink.remote.protocol.RemoteResourceChunk
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID

class RemoteTeachingAssetCache(private val root: File) {
    private val staging = File(root, ".staging")
    init { root.mkdirs(); staging.mkdirs() }

    fun has(hash: String): Boolean = finalFile(hash).isFile
    fun open(hash: String): File? = finalFile(hash).takeIf(File::isFile)

    @Synchronized fun receive(chunk: RemoteResourceChunk): ResourceChunkResult {
        require(chunk.assetHash.matches(Regex("[0-9a-f]{64}")))
        val transfer = File(staging, chunk.transferId).apply { mkdirs() }
        val meta = File(transfer, "meta")
        if (meta.exists() && meta.readText() != "${chunk.assetHash}:${chunk.chunkCount}") return ResourceChunkResult.Rejected("transfer metadata mismatch")
        meta.writeText("${chunk.assetHash}:${chunk.chunkCount}")
        File(transfer, "${chunk.chunkIndex}.chunk").writeBytes(chunk.data)
        val chunks = (0 until chunk.chunkCount).map { File(transfer, "$it.chunk") }
        if (chunks.any { !it.isFile }) return ResourceChunkResult.Pending(chunks.count(File::isFile), chunk.chunkCount)
        val temp = File(staging, "${UUID.randomUUID()}.complete")
        FileOutputStream(temp).use { output -> chunks.forEach { part -> FileInputStream(part).use { it.copyTo(output, CHECKPOINT_CHUNK_BYTES) } }; output.fd.sync() }
        val actual = sha256(temp)
        if (actual != chunk.assetHash) { temp.delete(); transfer.deleteRecursively(); return ResourceChunkResult.Rejected("asset hash mismatch") }
        val final = finalFile(actual)
        if (!final.exists()) check(temp.renameTo(final)) else temp.delete()
        transfer.deleteRecursively()
        return ResourceChunkResult.Ready(final)
    }

    fun clear() { root.listFiles()?.filter { it != staging }?.forEach(File::delete); staging.deleteRecursively(); staging.mkdirs() }
    private fun finalFile(hash: String) = File(root, "$hash.asset")
}

sealed interface ResourceChunkResult {
    data class Pending(val received: Int, val total: Int) : ResourceChunkResult
    data class Ready(val file: File) : ResourceChunkResult
    data class Rejected(val reason: String) : ResourceChunkResult
}

object TeachingResourceChunker {
    fun chunks(file: File, assetHash: String, transferId: String = UUID.randomUUID().toString()): Sequence<RemoteResourceChunk> {
        require(assetHash == sha256(file))
        val count = ((file.length() + CHECKPOINT_CHUNK_BYTES - 1) / CHECKPOINT_CHUNK_BYTES).toInt().coerceAtLeast(1)
        return sequence {
            FileInputStream(file).use { input ->
                repeat(count) { index ->
                    val buffer = ByteArray(CHECKPOINT_CHUNK_BYTES)
                    var offset = 0
                    while (offset < buffer.size) { val read = input.read(buffer, offset, buffer.size - offset); if (read < 0) break; offset += read }
                    yield(RemoteResourceChunk(transferId, assetHash, index, count, buffer.copyOf(offset)))
                }
            }
        }
    }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
