package com.studyink.reader

import java.util.concurrent.Executor

/**
 * Persists one value at a time and coalesces values offered meanwhile to the newest one.
 *
 * The drain loop itself runs on [executor], so [detachObserver] only disconnects UI delivery; it
 * never cancels an already accepted latest value.
 */
internal class LatestSnapshotSerialQueue<T : Any, B : Any>(
    private val executor: Executor,
    initialBase: B,
    private val persist: (value: T, base: B) -> B,
    observer: Observer<T, B>,
) {
    interface Observer<T : Any, B : Any> {
        fun onPersisted(value: T, committedBase: B)
        fun onFailure(error: Throwable)
    }

    private val lock = Any()
    private var base = initialBase
    private var active = false
    private var failed = false
    private var pending: T? = null
    private var latestCommitted: T? = null
    private var observer: Observer<T, B>? = observer

    val isBusy: Boolean get() = synchronized(lock) { active }

    /** Returns false only when the worker cannot accept the value. */
    fun offer(value: T): Boolean {
        synchronized(lock) {
            if (failed) return false
            if (active) {
                pending = value
                return true
            }
            active = true
        }
        return try {
            executor.execute { drain(value) }
            true
        } catch (_: RuntimeException) {
            synchronized(lock) {
                active = false
                failed = true
                pending = null
            }
            false
        }
    }

    /** Stops UI callbacks while allowing accepted persistence to finish. */
    fun detachObserver() {
        synchronized(lock) { observer = null }
    }

    fun isLatestCommitted(value: T): Boolean = synchronized(lock) { latestCommitted === value }

    private fun drain(first: T) {
        var value = first
        while (true) {
            val currentBase = synchronized(lock) { base }
            val result = runCatching { persist(value, currentBase) }
            if (result.isFailure) {
                val listener = synchronized(lock) {
                    pending = null
                    active = false
                    failed = true
                    observer
                }
                runCatching { listener?.onFailure(requireNotNull(result.exceptionOrNull())) }
                return
            }

            val committed = result.getOrThrow()
            val next: T?
            val listener: Observer<T, B>?
            synchronized(lock) {
                base = committed
                latestCommitted = value
                next = pending
                pending = null
                active = next != null
                listener = observer
            }
            runCatching { listener?.onPersisted(value, committed) }
            if (next == null) return
            value = next
        }
    }
}
