package com.studyink.monitor.core

/** Which transport supplied the student's durable page cursor. */
enum class RemoteStudentCursorTransport {
    LAN,
    TELEGRAM,
}

/**
 * Sticky, transport-neutral student location consumed by the teacher Reader.
 *
 * [pageReady] stays false until the annotation payload for [sourceRevision] has reached durable
 * local storage. This prevents a location-only Telegram manifest from opening an empty page.
 */
data class RemoteStudentCursor(
    val bookId: String,
    /** Zero-based local page number. */
    val pageNumber: Int,
    val attemptNo: Int?,
    val sourceRevision: Long,
    val pageReady: Boolean,
    val transport: RemoteStudentCursorTransport,
    val updatedAtElapsedMs: Long,
) {
    init {
        require(bookId.isNotBlank())
        require(pageNumber >= 0)
        require(attemptNo == null || attemptNo > 0)
        require(sourceRevision >= 0L)
        require(updatedAtElapsedMs >= 0L)
    }
}

/** Sticky cursor plus page-apply wake-ups shared by the app coordinator and Reader. */
object RemoteStudentCursorBus {
    private val lock = Any()
    private val listeners = linkedSetOf<(RemoteStudentCursor) -> Unit>()
    private var current: RemoteStudentCursor? = null

    fun current(bookId: String? = null): RemoteStudentCursor? = synchronized(lock) {
        current?.takeIf { bookId == null || it.bookId == bookId }
    }

    fun subscribe(
        emitCurrent: Boolean = true,
        listener: (RemoteStudentCursor) -> Unit,
    ): MonitorSubscription {
        val initial = synchronized(lock) {
            listeners += listener
            if (emitCurrent) current else null
        }
        initial?.let(listener)
        return MonitorSubscription { synchronized(lock) { listeners -= listener } }
    }

    fun publish(cursor: RemoteStudentCursor): Int {
        val snapshot = synchronized(lock) {
            val previous = current
            // A delayed Telegram manifest must not replace an equally-scoped newer cursor.
            if (previous != null &&
                previous.transport == cursor.transport &&
                previous.bookId == cursor.bookId &&
                cursor.updatedAtElapsedMs < previous.updatedAtElapsedMs
            ) return 0
            current = cursor
            listeners.toList()
        }
        snapshot.forEach { listener -> runCatching { listener(cursor) } }
        return snapshot.size
    }

    fun clear(transport: RemoteStudentCursorTransport? = null) = synchronized(lock) {
        if (transport == null || current?.transport == transport) current = null
    }
}

/** Emitted once after one remote page payload is durably applied. */
data class RemoteStudentPageApplied(
    val bookId: String,
    /** Zero-based local page number. */
    val pageNumber: Int,
    val sourceRevision: Long,
    val transferId: String,
) {
    init {
        require(bookId.isNotBlank())
        require(pageNumber >= 0)
        require(sourceRevision >= 0L)
        require(transferId.isNotBlank())
    }
}

object RemoteStudentPageAppliedBus {
    private val bus = ProcessSafePageAppliedBus()

    fun subscribe(listener: (RemoteStudentPageApplied) -> Unit): MonitorSubscription =
        bus.subscribe(listener)

    fun publish(event: RemoteStudentPageApplied): Int = bus.publish(event)

    private class ProcessSafePageAppliedBus {
        private val lock = Any()
        private val listeners = linkedSetOf<(RemoteStudentPageApplied) -> Unit>()

        fun subscribe(listener: (RemoteStudentPageApplied) -> Unit): MonitorSubscription {
            synchronized(lock) { listeners += listener }
            return MonitorSubscription { synchronized(lock) { listeners -= listener } }
        }

        fun publish(value: RemoteStudentPageApplied): Int {
            val snapshot = synchronized(lock) { listeners.toList() }
            snapshot.forEach { listener -> runCatching { listener(value) } }
            return snapshot.size
        }
    }
}

/** Exact review target emitted only after the teacher presses publish and local commits succeed. */
data class TeacherReviewPublished(
    val bookId: String,
    val pageNumber: Int,
    val attemptNo: Int,
    val publicationId: String,
) {
    init {
        require(bookId.isNotBlank())
        require(pageNumber >= 0)
        require(attemptNo > 0)
        require(publicationId.matches(Regex("[0-9a-f]{64}")))
    }
}

object TeacherReviewPublishedBus {
    private val lock = Any()
    private val listeners = linkedSetOf<(TeacherReviewPublished) -> Unit>()

    fun subscribe(listener: (TeacherReviewPublished) -> Unit): MonitorSubscription {
        synchronized(lock) { listeners += listener }
        return MonitorSubscription { synchronized(lock) { listeners -= listener } }
    }

    fun publish(event: TeacherReviewPublished): Int {
        val snapshot = synchronized(lock) { listeners.toList() }
        snapshot.forEach { listener -> runCatching { listener(event) } }
        return snapshot.size
    }
}

/** Immutable Telegram ownership captured before a teacher publication becomes durable. */
data class TeacherReviewPublicationProvenance(
    val pairId: String,
    val workbookToken: String?,
    val manifestGeneration: Long,
    val manifestSequence: Long,
) {
    init {
        validateOpaqueToken(pairId, "pairId")
        workbookToken?.let { validateOpaqueToken(it, "workbookToken") }
        require(manifestGeneration >= 0L)
        require(manifestSequence >= 0L)
    }
}

data class TeacherReviewPublicationTarget(
    val bookId: String,
    val pageNumber: Int,
    val attemptNo: Int,
) {
    init {
        require(bookId.isNotBlank())
        require(pageNumber >= 0)
        require(attemptNo > 0)
    }
}

/**
 * The app owns the pair-scoped mapping while Reader owns the publish transaction. This provider
 * bridges that boundary before the preparation sidecar is fsynced, closing the crash-before-event
 * gap without making Reader depend on the application module.
 */
object TeacherReviewPublicationProvenanceBus {
    private val lock = Any()
    private var provider:
        ((TeacherReviewPublicationTarget) -> TeacherReviewPublicationProvenance?)? = null

    fun install(
        next: (TeacherReviewPublicationTarget) -> TeacherReviewPublicationProvenance?,
    ): MonitorSubscription {
        synchronized(lock) { provider = next }
        return MonitorSubscription {
            synchronized(lock) {
                if (provider === next) provider = null
            }
        }
    }

    fun resolve(
        bookId: String,
        pageNumber: Int,
        attemptNo: Int,
    ): TeacherReviewPublicationProvenance? {
        val target = TeacherReviewPublicationTarget(bookId, pageNumber, attemptNo)
        val current = synchronized(lock) { provider } ?: return null
        return runCatching { current(target) }.getOrNull()
    }
}
