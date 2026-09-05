package com.studyink.construction.storage

import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

/** Android's durable atomic-replace primitive; never falls back to a blank scene on a read error. */
internal class AtomicConstructionFile(private val file: File, private val maximumBytes: Int) {
    private val atomicFile = AtomicFile(file)

    fun readOrNull(): ByteArray? {
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        return atomicFile.openRead().use { it.readBounded(maximumBytes) }
    }

    fun write(bytes: ByteArray) {
        require(bytes.size <= maximumBytes) { "Construction document is too large" }
        val parent = requireNotNull(file.parentFile)
        check(parent.mkdirs() || parent.isDirectory) { "Unable to create construction directory" }
        val output = atomicFile.startWrite()
        try {
            output.write(bytes)
            output.flush()
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            runCatching { atomicFile.failWrite(output) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        // AtomicFile.finishWrite logs some rename failures instead of throwing. Do not publish a
        // durable commit unless reopening the committed base returns the exact replacement bytes.
        check(atomicFile.openRead().use { it.readBounded(maximumBytes) }.contentEquals(bytes)) {
            "Unable to verify the committed construction document"
        }
    }
}

private fun InputStream.readBounded(maximumBytes: Int): ByteArray {
    val result = ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) return result.toByteArray()
        total += count
        require(total <= maximumBytes) { "Construction document is too large" }
        result.write(buffer, 0, count)
    }
}
