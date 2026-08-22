package com.studyink.library.data

import com.studyink.core.model.Attempt

/** Process-local notification for durable, locally-authored attempt changes. */
object LibraryAttemptBus {
    interface Listener {
        fun onLocalAttemptChanged(attempt: Attempt) = Unit
    }

    private val listeners = linkedSetOf<Listener>()

    @Synchronized
    fun addListener(listener: Listener) {
        listeners += listener
    }

    @Synchronized
    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    @Synchronized
    internal fun attemptChanged(attempt: Attempt) {
        listeners.toList().forEach { it.onLocalAttemptChanged(attempt) }
    }
}
