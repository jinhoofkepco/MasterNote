package com.studyink.library.data

import com.studyink.core.model.MarkGroup

/**
 * Process-local notification for durable, locally-authored mark-group changes.
 *
 * The repository emits only after its catalog write succeeds. Remote upserts deliberately do not
 * emit here, which prevents a received LAN snapshot from being echoed back indefinitely.
 */
object LibraryMarkGroupBus {
    interface Listener {
        fun onLocalMarkGroupChanged(group: MarkGroup) = Unit
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
    internal fun markGroupChanged(group: MarkGroup) {
        listeners.toList().forEach { it.onLocalMarkGroupChanged(group) }
    }
}
