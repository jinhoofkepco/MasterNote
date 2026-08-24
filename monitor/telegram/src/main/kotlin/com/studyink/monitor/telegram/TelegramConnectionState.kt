package com.studyink.monitor.telegram

import java.io.File
import java.io.FileNotFoundException
import java.nio.file.NoSuchFileException

/** Adapted from FocusMonitor2 TelegramConnectionState at commit e5809ebc. */
sealed interface TelegramConnectionState {
    data object Unknown : TelegramConnectionState
    data object Connected : TelegramConnectionState
    data class Outage(val startedAtEpochMs: Long) : TelegramConnectionState
}

sealed interface TelegramConnectionEvent {
    data class Success(val atEpochMs: Long) : TelegramConnectionEvent
    data class NetworkFailure(val atEpochMs: Long) : TelegramConnectionEvent
}

object TelegramConnectionStateMachine {
    fun reduce(state: TelegramConnectionState, event: TelegramConnectionEvent): TelegramConnectionState =
        when (event) {
            is TelegramConnectionEvent.Success -> TelegramConnectionState.Connected
            is TelegramConnectionEvent.NetworkFailure -> when (state) {
                TelegramConnectionState.Unknown -> state
                TelegramConnectionState.Connected -> TelegramConnectionState.Outage(event.atEpochMs)
                is TelegramConnectionState.Outage -> state
            }
        }
}

object TelegramConnectionFailureClassifier {
    fun isNetworkOutage(error: Throwable): Boolean = when (error) {
        is TelegramApiException -> error.statusCode in 500..599 || error.statusCode == 429 ||
            error.indicatesConnectionFailure
        is FileNotFoundException, is NoSuchFileException, is SecurityException,
        is IllegalArgumentException -> false
        is java.io.IOException -> true
        else -> false
    }
}

class TelegramConnectionStateStore(private val file: File) {
    private var current = load()

    init {
        // Each new process must prove reachability again instead of displaying stale Connected.
        if (current == TelegramConnectionState.Connected) {
            current = TelegramConnectionState.Unknown
            runCatching { persist(current) }
        }
    }

    @Synchronized fun state(): TelegramConnectionState = current

    @Synchronized
    fun transition(event: TelegramConnectionEvent): TelegramConnectionState {
        val updated = TelegramConnectionStateMachine.reduce(current, event)
        if (updated != current) {
            persist(updated)
            current = updated
        }
        return current
    }

    @Synchronized fun reset() {
        current = TelegramConnectionState.Unknown
        file.delete()
    }

    private fun load(): TelegramConnectionState {
        val fields = runCatching { file.readText().trim().split('\t') }.getOrNull()
            ?: return TelegramConnectionState.Unknown
        if (fields.size != 3 || fields[0] != VERSION) return TelegramConnectionState.Unknown
        return when (fields[1]) {
            "CONNECTED" -> TelegramConnectionState.Connected
            "OUTAGE" -> fields[2].toLongOrNull()?.takeIf { it > 0L }
                ?.let(TelegramConnectionState::Outage) ?: TelegramConnectionState.Unknown
            else -> TelegramConnectionState.Unknown
        }
    }

    private fun persist(state: TelegramConnectionState) {
        val encoded = when (state) {
            TelegramConnectionState.Unknown -> "UNKNOWN\t0"
            TelegramConnectionState.Connected -> "CONNECTED\t0"
            is TelegramConnectionState.Outage -> "OUTAGE\t${state.startedAtEpochMs}"
        }
        AtomicDiskFile.writeText(file, "$VERSION\t$encoded")
    }

    private companion object { const val VERSION = "V1" }
}

internal class TelegramConnectionTracker(
    private val store: TelegramConnectionStateStore,
    private val onState: (TelegramConnectionState) -> Unit,
) {
    private val lock = Any()

    fun success(nowEpochMs: Long) = transition(TelegramConnectionEvent.Success(nowEpochMs))

    fun failure(error: Throwable, nowEpochMs: Long) {
        if (TelegramConnectionFailureClassifier.isNetworkOutage(error)) {
            transition(TelegramConnectionEvent.NetworkFailure(nowEpochMs))
        }
    }

    private fun transition(event: TelegramConnectionEvent) {
        val before: TelegramConnectionState
        val after: TelegramConnectionState
        synchronized(lock) {
            before = store.state()
            after = store.transition(event)
        }
        if (before != after) onState(after)
    }
}
