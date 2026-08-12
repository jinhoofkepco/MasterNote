package com.studyink.remote.sync

import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachingResourceTransferTest {
    private val root = java.nio.file.Files.createTempDirectory("resource-cache-").toFile()
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun incompleteTransferNeverBecomesVisible() {
        val source = File(root.parentFile, "source-${System.nanoTime()}").apply { writeBytes(ByteArray(80_000) { it.toByte() }) }
        val hash = source.sha()
        val chunks = TeachingResourceChunker.chunks(source, hash).toList()
        val cache = RemoteTeachingAssetCache(File(root, "cache"))
        assertTrue(cache.receive(chunks.first()) is ResourceChunkResult.Pending)
        assertFalse(cache.has(hash))
        source.delete()
    }

    @Test fun completeVerifiedTransferIsReusedByHash() {
        val source = File(root.parentFile, "source-${System.nanoTime()}").apply { writeBytes(ByteArray(100_000) { (it * 7).toByte() }) }
        val hash = source.sha(); val cache = RemoteTeachingAssetCache(File(root, "cache"))
        val result = TeachingResourceChunker.chunks(source, hash).toList().reversed().map(cache::receive).last()
        assertTrue(result is ResourceChunkResult.Ready)
        assertTrue(cache.has(hash)); assertArrayEquals(source.readBytes(), cache.open(hash)?.readBytes())
        source.delete()
    }

    private fun File.sha() = MessageDigest.getInstance("SHA-256").digest(readBytes()).joinToString("") { "%02x".format(it) }
}
