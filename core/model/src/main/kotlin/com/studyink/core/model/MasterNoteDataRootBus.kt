package com.studyink.core.model

import java.util.concurrent.CopyOnWriteArraySet

/** Process-local invalidation sent after the app data root is atomically replaced or rolled back. */
object MasterNoteDataRootBus {
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    fun dataRootReplaced() {
        listeners.forEach { listener -> runCatching(listener) }
    }
}
