package com.studyink.memo.core

import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/** Bounded crash-safe file; corruption is quarantined without touching any other feature data. */
internal class AtomicMemoFile(
    private val file: File,
    private val maximumBytes: Int,
) {
    private val atomicFile = AtomicFile(file)

    fun readOrNull(): ByteArray? {
        if (!file.exists() && !backupFile.exists()) return null
        return try {
            atomicFile.openRead().use { it.readBounded(maximumBytes) }
        } catch (_: FileNotFoundException) {
            null
        }
    }

    fun write(bytes: ByteArray) {
        require(bytes.size <= maximumBytes) { "Memo file is too large" }
        val parent = requireNotNull(file.parentFile)
        check(parent.mkdirs() || parent.isDirectory) { "Unable to create memo directory" }
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
    }

    internal fun baseFileForTest(): File = file

    private val backupFile: File get() = File(file.path + ".bak")
}

private fun InputStream.readBounded(maximumBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= maximumBytes) { "Memo file is too large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
