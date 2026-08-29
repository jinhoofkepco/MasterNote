package com.studyink.assistant.core

import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

/** A small, bounded wrapper around Android's crash-safe [AtomicFile]. */
internal class AtomicAssistantFile(
    private val file: File,
    private val maximumBytes: Int,
) {
    private val atomicFile = AtomicFile(file)

    fun readOrNull(): ByteArray? {
        if (!file.exists() && !backupFile.exists()) return null
        return try {
            atomicFile.openRead().use { input -> input.readBounded(maximumBytes) }
        } catch (_: FileNotFoundException) {
            null
        }
    }

    fun write(bytes: ByteArray) {
        require(bytes.size <= maximumBytes) { "Assistant feature file is too large" }
        val parent = requireNotNull(file.parentFile)
        check(parent.mkdirs() || parent.isDirectory) { "Unable to create assistant feature directory" }
        val output = atomicFile.startWrite()
        try {
            output.write(bytes)
            output.flush()
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            runCatching { atomicFile.failWrite(output) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
    }

    /** Moves malformed feature data aside without deleting any catalog or annotation data. */
    fun quarantineCorrupt() {
        val suffix = ".corrupt-${System.currentTimeMillis()}-${UUID.randomUUID()}"
        listOf(file, backupFile, File(file.path + ".new")).forEachIndexed { index, candidate ->
            if (candidate.exists()) {
                runCatching { candidate.renameTo(File(candidate.parentFile, file.name + suffix + "-$index")) }
            }
        }
    }

    internal fun baseFileForTest(): File = file

    private val backupFile: File
        get() = File(file.path + ".bak")
}

private fun InputStream.readBounded(maximumBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maximumBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= maximumBytes) { "Assistant feature file is too large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
