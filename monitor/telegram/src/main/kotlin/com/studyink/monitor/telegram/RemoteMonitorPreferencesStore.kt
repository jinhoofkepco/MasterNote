package com.studyink.monitor.telegram

import java.nio.charset.StandardCharsets

class RemoteMonitorPreferencesStore(private val paths: TelegramStoragePaths) {
    private val lock = Any()
    private val listeners = linkedSetOf<(RemoteMonitorPreferences) -> Unit>()
    private var current = loadFromDisk()

    fun get(): RemoteMonitorPreferences = synchronized(lock) { current }

    fun update(transform: (RemoteMonitorPreferences) -> RemoteMonitorPreferences): RemoteMonitorPreferences {
        val (updated, snapshot) = synchronized(lock) {
            val next = transform(current)
            if (next == current) return current
            persist(next)
            current = next
            next to listeners.toList()
        }
        snapshot.forEach { it(updated) }
        return updated
    }

    fun subscribe(
        emitCurrent: Boolean = true,
        listener: (RemoteMonitorPreferences) -> Unit,
    ): RemoteMonitorStatusSubscription {
        val initial = synchronized(lock) {
            listeners += listener
            if (emitCurrent) current else null
        }
        initial?.let(listener)
        return RemoteMonitorStatusSubscription { synchronized(lock) { listeners -= listener } }
    }

    private fun loadFromDisk(): RemoteMonitorPreferences {
        val values = runCatching {
            paths.preferencesFile.readText(StandardCharsets.UTF_8).trim().split('\t')
        }.getOrNull() ?: return RemoteMonitorPreferences()
        return when {
            values.size == 5 && values[0] == VERSION -> RemoteMonitorPreferences(
                ttsEnabled = values[1] == "1",
                wakeVoiceEnabled = values[2] == "1",
                monitoringEnabled = values[3] == "1",
                realtimeActivityEnabled = values[4] == "1",
            )
            // Existing installations migrate to the deliberately quiet hourly default.
            values.size == 4 && values[0] == LEGACY_VERSION -> RemoteMonitorPreferences(
                ttsEnabled = values[1] == "1",
                wakeVoiceEnabled = values[2] == "1",
                monitoringEnabled = values[3] == "1",
                realtimeActivityEnabled = false,
            )
            else -> RemoteMonitorPreferences()
        }
    }

    private fun persist(value: RemoteMonitorPreferences) {
        AtomicDiskFile.writeText(
            paths.preferencesFile,
            listOf(
                VERSION,
                if (value.ttsEnabled) "1" else "0",
                if (value.wakeVoiceEnabled) "1" else "0",
                if (value.monitoringEnabled) "1" else "0",
                if (value.realtimeActivityEnabled) "1" else "0",
            ).joinToString("\t"),
        )
    }

    private companion object {
        const val VERSION = "V2"
        const val LEGACY_VERSION = "V1"
    }
}
