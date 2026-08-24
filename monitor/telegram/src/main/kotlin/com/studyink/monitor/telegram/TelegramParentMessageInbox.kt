package com.studyink.monitor.telegram

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * One durable, latest-wins parent message.
 *
 * Telegram's update offset is committed only after [offer] returns, so a process death can never
 * turn a received instruction into an acknowledged-but-lost in-memory event. [highestUpdateId]
 * remains after acknowledgement and rejects an update replayed after an offset-file rollback.
 */
internal class TelegramParentMessageInbox(private val file: File) {
    data class Pending(val updateId: Long, val text: String)

    private data class State(
        val highestUpdateId: Long = -1L,
        val pending: Pending? = null,
    )

    private val lock = Any()
    private val listeners = linkedSetOf<(Pending) -> Unit>()
    private var state = load()

    /** Returns true only when this is a new update and its durable write completed. */
    fun offer(updateId: Long, text: String): Boolean {
        require(updateId >= 0L)
        val normalized = text.trim().take(MAX_TEXT_CHARS)
        require(normalized.isNotEmpty())
        val (accepted, snapshot) = synchronized(lock) {
            if (updateId <= state.highestUpdateId) return false
            val pending = Pending(updateId, normalized)
            val next = State(highestUpdateId = updateId, pending = pending)
            persist(next)
            state = next
            true to listeners.toList()
        }
        if (accepted) snapshot.forEach { it(Pending(updateId, normalized)) }
        return accepted
    }

    fun pending(): Pending? = synchronized(lock) { state.pending }

    /**
     * Clears only the exact message which was displayed. An acknowledgement racing a newer update
     * cannot erase that newer instruction.
     */
    fun acknowledge(updateId: Long): Boolean = synchronized(lock) {
        if (state.pending?.updateId != updateId) return false
        val next = state.copy(pending = null)
        persist(next)
        state = next
        true
    }

    /** Clears both pending content and update-id history before a different parent is connected. */
    fun clear() = synchronized(lock) {
        val next = State()
        persist(next)
        state = next
    }

    fun subscribe(
        emitPending: Boolean = true,
        listener: (Pending) -> Unit,
    ): RemoteMonitorStatusSubscription {
        synchronized(lock) {
            listeners += listener
            try {
                if (emitPending) state.pending?.let(listener)
            } catch (error: Throwable) {
                listeners -= listener
                throw error
            }
        }
        return RemoteMonitorStatusSubscription { synchronized(lock) { listeners -= listener } }
    }

    private fun load(): State {
        val fields = runCatching {
            file.readText(StandardCharsets.UTF_8).trim().split('\t')
        }.getOrNull() ?: return State()
        if (fields.size != FIELD_COUNT || fields[0] != VERSION) return State()
        return runCatching {
            val highest = fields[1].toLong()
            val pendingId = fields[2].toLong()
            val text = decode(fields[3])
            require(highest >= -1L)
            val pending = if (pendingId >= 0L) {
                require(pendingId == highest && text.isNotBlank())
                Pending(pendingId, text.take(MAX_TEXT_CHARS))
            } else {
                require(text.isEmpty())
                null
            }
            State(highest, pending)
        }.getOrDefault(State())
    }

    private fun persist(value: State) {
        val pending = value.pending
        AtomicDiskFile.writeText(
            file,
            listOf(
                VERSION,
                value.highestUpdateId.toString(),
                (pending?.updateId ?: -1L).toString(),
                encode(pending?.text.orEmpty()),
            ).joinToString("\t"),
        )
    }

    private fun encode(value: String): String = if (value.isEmpty()) {
        EMPTY_VALUE
    } else {
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun decode(value: String): String = if (value == EMPTY_VALUE) {
        ""
    } else {
        Base64.getUrlDecoder().decode(value).toString(StandardCharsets.UTF_8)
    }

    private companion object {
        const val VERSION = "V1"
        const val FIELD_COUNT = 4
        const val MAX_TEXT_CHARS = 4_096
        const val EMPTY_VALUE = "~"
    }
}
