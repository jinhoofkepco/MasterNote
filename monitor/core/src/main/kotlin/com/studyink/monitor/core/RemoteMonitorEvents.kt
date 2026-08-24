package com.studyink.monitor.core

/** A removable process-local listener registration. */
fun interface MonitorSubscription : AutoCloseable {
    override fun close()
}

data class ParentMessage(
    val updateId: Long,
    val text: String,
    val receivedAtElapsedMs: Long,
)

/** Events are copied under the lock and invoked after it, so listeners may safely unsubscribe. */
object ParentMessageBus {
    private val bus = ProcessEventBus<ParentMessage>()

    fun subscribe(listener: (ParentMessage) -> Unit): MonitorSubscription = bus.subscribe(listener)

    /** Returns the number of listeners which received the message. */
    fun publish(message: ParentMessage): Int = bus.publish(message)
}

sealed interface RemoteMonitorCommand {
    data class CurrentPageSnapshot(
        val requestId: String,
        val updateId: Long,
        val chatId: Long,
        val requestedAtElapsedMs: Long,
    ) : RemoteMonitorCommand
}

object RemoteMonitorCommandBus {
    private val bus = ProcessEventBus<RemoteMonitorCommand>()

    fun subscribe(listener: (RemoteMonitorCommand) -> Unit): MonitorSubscription =
        bus.subscribe(listener)

    /** Returns the number of listeners which accepted the in-process request. */
    fun publish(command: RemoteMonitorCommand): Int = bus.publish(command)
}

data class StudentStudyPresence(
    val bookId: String?,
    val pageNumber: Int?,
    val attemptNo: Int?,
    val active: Boolean,
    val updatedAtElapsedMs: Long,
) {
    init {
        require(pageNumber == null || pageNumber > 0)
        require(attemptNo == null || attemptNo > 0)
        require(updatedAtElapsedMs >= 0L)
        if (active) {
            require(!bookId.isNullOrBlank()) { "An active study presence needs a bookId." }
            requireNotNull(pageNumber) { "An active study presence needs a pageNumber." }
        }
    }
}

/** Sticky process-local state describing the student's current workbook location. */
object StudentStudyPresenceBus {
    private val bus = StickyProcessEventBus<StudentStudyPresence>()

    fun current(): StudentStudyPresence? = bus.current()

    fun subscribe(
        emitCurrent: Boolean = true,
        listener: (StudentStudyPresence) -> Unit,
    ): MonitorSubscription = bus.subscribe(emitCurrent, listener)

    fun publish(presence: StudentStudyPresence): Int = bus.publish(presence)

    /** Intended for explicit session teardown and tests, not ordinary page changes. */
    fun clear() = bus.clear()
}

enum class StudentWorkKind {
    PEN_CONTACT,
    ERASE_COMMIT,
    UNDO,
    REDO,
    PAGE_CHANGE,
    SUBMIT,
}

data class StudentWorkHeartbeat(
    val atElapsedMs: Long,
    val kind: StudentWorkKind,
    val bookId: String? = null,
    val pageNumber: Int? = null,
) {
    init {
        require(atElapsedMs >= 0L)
        require(pageNumber == null || pageNumber > 0)
    }
}

/**
 * Lightweight process bus for work activity. Producers should throttle PEN_CONTACT heartbeats;
 * this bus deliberately does not retain a history.
 */
object StudentWorkHeartbeatBus {
    private val bus = ProcessEventBus<StudentWorkHeartbeat>()

    fun subscribe(listener: (StudentWorkHeartbeat) -> Unit): MonitorSubscription =
        bus.subscribe(listener)

    fun publish(heartbeat: StudentWorkHeartbeat): Int = bus.publish(heartbeat)
}

private class ProcessEventBus<T> {
    private val lock = Any()
    private val listeners = linkedSetOf<(T) -> Unit>()

    fun subscribe(listener: (T) -> Unit): MonitorSubscription {
        synchronized(lock) { listeners += listener }
        return MonitorSubscription { synchronized(lock) { listeners -= listener } }
    }

    fun publish(value: T): Int {
        val snapshot = synchronized(lock) { listeners.toList() }
        snapshot.forEach { it(value) }
        return snapshot.size
    }
}

private class StickyProcessEventBus<T> {
    private val lock = Any()
    private val listeners = linkedSetOf<(T) -> Unit>()
    private var value: T? = null

    fun current(): T? = synchronized(lock) { value }

    fun subscribe(emitCurrent: Boolean, listener: (T) -> Unit): MonitorSubscription {
        val initial = synchronized(lock) {
            listeners += listener
            if (emitCurrent) value else null
        }
        initial?.let(listener)
        return MonitorSubscription { synchronized(lock) { listeners -= listener } }
    }

    fun publish(next: T): Int {
        val snapshot = synchronized(lock) {
            value = next
            listeners.toList()
        }
        snapshot.forEach { it(next) }
        return snapshot.size
    }

    fun clear() = synchronized(lock) { value = null }
}
