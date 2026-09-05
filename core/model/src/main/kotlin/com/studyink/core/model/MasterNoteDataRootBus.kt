package com.studyink.core.model

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/** Process-local invalidation sent after the app data root is atomically replaced or rolled back. */
object MasterNoteDataRootBus {
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val generation = AtomicLong(0L)

    /** Process-local restore count, including replacements before a feature registered a listener. */
    fun currentGeneration(): Long = generation.get()

    fun addListener(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    fun dataRootReplaced() {
        generation.updateAndGet { current ->
            check(current < Long.MAX_VALUE) { "Data root generation exhausted" }
            current + 1L
        }
        listeners.forEach { listener -> runCatching(listener) }
    }
}
