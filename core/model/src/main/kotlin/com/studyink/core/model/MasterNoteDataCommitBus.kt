package com.studyink.core.model

import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-local signal emitted only after user-owned MasterNote data has reached durable storage.
 *
 * The monotonically increasing generation lets a backup scheduler coalesce several commits into
 * one archive without having to understand which repository changed. Generations deliberately do
 * not survive process death: the backup manager persists the last generation it handled and also
 * performs an initial-data check when a new process starts.
 *
 * Listeners run synchronously and must only mark/schedule work. A listener failure is isolated so
 * that it can never turn an already durable repository write into an apparent write failure.
 */
object MasterNoteDataCommitBus {
    private val generation = AtomicLong(0L)
    private val listeners = CopyOnWriteArraySet<(Long) -> Unit>()

    fun currentGeneration(): Long = generation.get()

    fun addListener(listener: (Long) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /** Called by persistence modules after their durable commit and in-memory index both succeed. */
    fun recordDurableCommit(): Long {
        val committedGeneration = generation.incrementAndGet()
        listeners.forEach { listener -> runCatching { listener(committedGeneration) } }
        return committedGeneration
    }
}
