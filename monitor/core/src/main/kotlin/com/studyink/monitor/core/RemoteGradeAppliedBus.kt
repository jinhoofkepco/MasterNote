package com.studyink.monitor.core

/** Process-local notice emitted only after a remotely received grade is durably merged. */
data class RemoteGradeApplied(
    val bookId: String,
    /** Zero-based local page number. */
    val pageNumber: Int,
    val attemptNo: Int,
    val gradeGroupId: String,
    val correct: Boolean,
) {
    init {
        require(bookId.isNotBlank())
        require(pageNumber >= 0)
        require(attemptNo > 0)
        require(gradeGroupId.isNotBlank())
    }
}

object RemoteGradeAppliedBus {
    private class Registration(val callback: (RemoteGradeApplied) -> Unit)

    private val lock = Any()
    private val listeners = linkedSetOf<Registration>()

    fun subscribe(listener: (RemoteGradeApplied) -> Unit): MonitorSubscription {
        val registration = Registration(listener)
        synchronized(lock) { listeners += registration }
        return MonitorSubscription { synchronized(lock) { listeners.remove(registration) } }
    }

    fun publish(event: RemoteGradeApplied): Int {
        val snapshot = synchronized(lock) { listeners.toList() }
        snapshot.forEach { registration -> runCatching { registration.callback(event) } }
        return snapshot.size
    }
}
