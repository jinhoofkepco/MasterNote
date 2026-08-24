package com.studyink.monitor.core

import java.util.concurrent.CopyOnWriteArraySet

/** Emitted after a remote teacher layer has reached the student's annotation store. */
data class RemoteTeacherFeedbackApplied(
    val bookId: String,
    /** Zero-based local page number. */
    val pageNumber: Int,
    val attemptNo: Int?,
    val transferId: String,
    val basedOnStudentRevision: Long,
    val note: String? = null,
) {
    init {
        require(bookId.isNotBlank())
        require(pageNumber >= 0)
        require(attemptNo == null || attemptNo > 0)
        require(transferId.isNotBlank())
        require(basedOnStudentRevision >= 0L)
        require(note == null || note.length <= 2_000)
    }
}

/**
 * Process-local wake-up only. The annotation log remains the durable source of truth, so missing
 * this event during process death merely means the Reader reloads the layer the next time it opens.
 */
object RemoteReviewFeedbackBus {
    private val listeners = CopyOnWriteArraySet<(RemoteTeacherFeedbackApplied) -> Unit>()

    fun subscribe(listener: (RemoteTeacherFeedbackApplied) -> Unit): MonitorSubscription {
        listeners += listener
        return MonitorSubscription { listeners -= listener }
    }

    fun publish(event: RemoteTeacherFeedbackApplied): Int {
        listeners.forEach { listener -> runCatching { listener(event) } }
        return listeners.size
    }
}
