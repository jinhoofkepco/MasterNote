package com.studyink.construction.storage

import com.studyink.core.model.MasterNoteDataRootBus
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * This tiny install-local counter deliberately lives beside, not inside, the backed-up data root.
 * Restoring a student backup must not make old teacher CAS requests valid again. App upgrades retain
 * it; a new installation is a new authenticated peer identity and must be paired by the app layer.
 * The root bus's process-local count also covers restores before this listener was initialized.
 * Ordinary process restarts do not change the counter or discard durable publication receipts.
 */
internal object ConstructionReplicaEpoch {
    private val entries = ConcurrentHashMap<String, Entry>()
    @Suppress("unused")
    private val subscription = MasterNoteDataRootBus.addListener {
        entries.values.forEach { entry ->
            if (entry.parent.exists()) advance(entry)
        }
    }

    fun current(root: File): Long {
        val absolute = root.toPath().toAbsolutePath().normalize().toFile()
        val entry = entries.computeIfAbsent(absolute.path) {
            val parent = requireNotNull(absolute.parentFile)
            val file = AtomicConstructionFile(File(parent, ".${absolute.name}-construction-sync-generation"), 128)
            val bytes = file.readOrNull()
            val value = if (bytes == null) 1L.also { file.write(it.toString().toByteArray(Charsets.UTF_8)) }
            else requireNotNull(bytes.toString(Charsets.UTF_8).toLongOrNull()).also { require(it > 0) }
            Entry(parent, file, value)
        }
        advance(entry)
        return synchronized(entry) {
            entry.failure?.let { throw ConstructionDataException("Unable to advance construction restore generation", it) }
            entry.value
        }
    }

    private fun advance(entry: Entry) = synchronized(entry) {
        if (entry.failure != null) return@synchronized
        val observed = MasterNoteDataRootBus.currentGeneration()
        val missed = observed - entry.observedRestores
        if (missed <= 0L) return@synchronized
        try {
            check(entry.value <= Long.MAX_VALUE - missed) { "Construction restore generation exhausted" }
            val next = entry.value + missed
            entry.file.write(next.toString().toByteArray(Charsets.UTF_8))
            entry.value = next
            entry.observedRestores = observed
        } catch (error: Throwable) {
            // Root bus isolates listener errors. Keep ours so future writes fail closed.
            entry.failure = error
        }
    }

    private class Entry(val parent: File, val file: AtomicConstructionFile, var value: Long,
        var observedRestores: Long = 0L, var failure: Throwable? = null)
}
