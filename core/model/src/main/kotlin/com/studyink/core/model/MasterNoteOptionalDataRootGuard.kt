package com.studyink.core.model

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide guard for optional files stored below the ordinary MasterNote data root.
 *
 * Optional features can use this without making backup storage depend on the feature module. A
 * backup/restore holds the same root lock while copying or swapping the tree, so an AtomicFile
 * rename cannot race the directory walk. This is deliberately process-local; all current writers
 * and backup jobs run in the application process.
 */
object MasterNoteOptionalDataRootGuard {
    private val locks = ConcurrentHashMap<String, Any>()

    fun <T> withStableDataRoot(rootDirectory: File, block: () -> T): T {
        val key = rootDirectory.toPath().toAbsolutePath().normalize().toString()
        return synchronized(locks.computeIfAbsent(key) { Any() }, block)
    }
}
