package com.studyink.monitor.core

/** Sticky process-local projection consumed by Reader chrome and transport-aware coordinators. */
data class HybridLinkStatus(
    val bookId: String,
    val decision: HybridLinkDecision,
    val updatedAtElapsedMs: Long,
) {
    init {
        require(bookId.isNotBlank())
        require(updatedAtElapsedMs >= 0L)
    }
}

object HybridLinkStatusBus {
    private val lock = Any()
    private val listeners = linkedSetOf<(HybridLinkStatus) -> Unit>()
    private var current: HybridLinkStatus? = null

    fun current(bookId: String? = null): HybridLinkStatus? = synchronized(lock) {
        current?.takeIf { bookId == null || it.bookId == bookId }
    }

    fun subscribe(
        emitCurrent: Boolean = true,
        listener: (HybridLinkStatus) -> Unit,
    ): MonitorSubscription {
        val initial = synchronized(lock) {
            listeners += listener
            if (emitCurrent) current else null
        }
        initial?.let(listener)
        return MonitorSubscription { synchronized(lock) { listeners -= listener } }
    }

    fun publish(status: HybridLinkStatus): Int {
        val snapshot = synchronized(lock) {
            current = status
            listeners.toList()
        }
        snapshot.forEach { listener -> runCatching { listener(status) } }
        return snapshot.size
    }

    /** Explicit session teardown/test helper; ordinary link loss publishes OFFLINE_QUEUEING. */
    fun clear() = synchronized(lock) { current = null }
}
