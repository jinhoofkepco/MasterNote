package com.studyink.monitor.telegram

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Durable bounded FIFO for `/화면` commands.
 *
 * [offer] commits the request before the Telegram update offset may advance. A full queue throws,
 * deliberately leaving that update uncommitted so the poller can retry after the coordinator has
 * acknowledged older work. The highest accepted update id survives acknowledgement and restart,
 * preventing an offset rollback from inserting the same request twice.
 */
internal class TelegramScreenRequestInbox(
    private val file: File,
    private val maxPendingRequests: Int = DEFAULT_MAX_PENDING_REQUESTS,
) {
    private data class State(
        val highestUpdateId: Long = -1L,
        val pending: List<PendingScreenRequest> = emptyList(),
    )

    private val lock = Any()
    private val listeners = linkedSetOf<(PendingScreenRequest) -> Unit>()
    private var state: State

    init {
        require(maxPendingRequests > 0)
        state = load()
    }

    /** Returns false only for an update already accepted before. */
    fun offer(request: PendingScreenRequest): Boolean {
        val snapshot = synchronized(lock) {
            if (request.updateId <= state.highestUpdateId) return false
            check(state.pending.size < maxPendingRequests) { "The screen request inbox is full." }
            val next = State(
                highestUpdateId = request.updateId,
                pending = state.pending + request,
            )
            persist(next)
            state = next
            listeners.toList()
        }
        snapshot.forEach { listener -> listener(request) }
        return true
    }

    fun pending(): List<PendingScreenRequest> = synchronized(lock) { state.pending.toList() }

    /** Removes only the exact completed request; later queued commands remain intact. */
    fun acknowledge(updateId: Long): Boolean = synchronized(lock) {
        val index = state.pending.indexOfFirst { it.updateId == updateId }
        if (index < 0) return false
        val nextPending = state.pending.toMutableList().apply { removeAt(index) }
        val next = state.copy(pending = nextPending)
        persist(next)
        state = next
        true
    }

    /** Clears queued commands and dedupe history before a different parent is connected. */
    fun clear() = synchronized(lock) {
        val next = State()
        persist(next)
        state = next
    }

    fun subscribe(
        emitPending: Boolean = true,
        listener: (PendingScreenRequest) -> Unit,
    ): RemoteMonitorStatusSubscription {
        synchronized(lock) {
            listeners += listener
            try {
                if (emitPending) state.pending.forEach(listener)
            } catch (error: Throwable) {
                listeners -= listener
                throw error
            }
        }
        return RemoteMonitorStatusSubscription { synchronized(lock) { listeners -= listener } }
    }

    private fun load(): State {
        val lines = runCatching { file.readLines(StandardCharsets.UTF_8) }.getOrNull()
            ?: return State()
        if (lines.isEmpty()) return State()
        return runCatching {
            val header = lines.first().split('\t')
            require(header.size == HEADER_FIELD_COUNT && header[0] == VERSION)
            val highest = header[1].toLong().also { require(it >= -1L) }
            val pending = lines.drop(1).map(::decodeRequest)
            require(pending.size <= maxPendingRequests)
            require(pending.map(PendingScreenRequest::updateId).distinct().size == pending.size)
            require(pending.zipWithNext().all { (left, right) -> left.updateId < right.updateId })
            require(pending.all { it.updateId <= highest })
            State(highest, pending)
        }.getOrDefault(State())
    }

    private fun persist(value: State) {
        val serialized = buildString {
            append(VERSION).append('\t').append(value.highestUpdateId).append('\n')
            value.pending.forEach { append(encodeRequest(it)).append('\n') }
        }
        AtomicDiskFile.writeText(file, serialized)
    }

    private fun encodeRequest(request: PendingScreenRequest): String = listOf(
        RECORD,
        request.updateId.toString(),
        encode(request.requestId),
        request.chatId.toString(),
        request.requestedAtElapsedMs.toString(),
        if (request.active) "1" else "0",
        encodeNullable(request.bookId),
        request.pageNumber?.toString() ?: NULL_VALUE,
        request.attemptNo?.toString() ?: NULL_VALUE,
    ).joinToString("\t")

    private fun decodeRequest(line: String): PendingScreenRequest {
        val fields = line.split('\t')
        require(fields.size == REQUEST_FIELD_COUNT && fields[0] == RECORD)
        return PendingScreenRequest(
            updateId = fields[1].toLong(),
            requestId = decode(fields[2]),
            chatId = fields[3].toLong(),
            requestedAtElapsedMs = fields[4].toLong(),
            active = when (fields[5]) {
                "1" -> true
                "0" -> false
                else -> error("Invalid active flag")
            },
            bookId = decodeNullable(fields[6]),
            pageNumber = fields[7].takeUnless { it == NULL_VALUE }?.toInt(),
            attemptNo = fields[8].takeUnless { it == NULL_VALUE }?.toInt(),
        )
    }

    private fun encodeNullable(value: String?): String = value?.let(::encode) ?: NULL_VALUE

    private fun decodeNullable(value: String): String? =
        value.takeUnless { it == NULL_VALUE }?.let(::decode)

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String = Base64.getUrlDecoder().decode(value)
        .toString(StandardCharsets.UTF_8)

    private companion object {
        const val VERSION = "V1"
        const val RECORD = "Q"
        const val NULL_VALUE = "~"
        const val HEADER_FIELD_COUNT = 2
        const val REQUEST_FIELD_COUNT = 9
        const val DEFAULT_MAX_PENDING_REQUESTS = 64
    }
}
