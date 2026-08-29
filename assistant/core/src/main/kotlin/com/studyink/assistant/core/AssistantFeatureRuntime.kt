package com.studyink.assistant.core

import android.content.Context
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/** One process-wide view of the optional sidecar store. It never opens legacy feature files. */
object AssistantRepositoryProvider {
    @Volatile private var instance: AssistantRepository? = null

    fun get(context: Context): AssistantRepository = instance ?: synchronized(this) {
        instance ?: AssistantRepository(
            File(context.applicationContext.filesDir, "masternote"),
        ).also { instance = it }
    }

    internal fun current(): AssistantRepository? = instance
}

/**
 * Small process bus between the reader and the two optional transports.
 *
 * Callers publish only after [AssistantRepository] has durably committed the full exact-attempt
 * layer. A transport failure therefore cannot lose or partially mutate the local explanation.
 */
object StudentExplanationLayerBus {
    interface Listener {
        fun onLocalLayerPublished(layer: StudentExplanationLayer) = Unit
        fun onRemoteLayerApplied(layer: StudentExplanationLayer) = Unit
    }

    private val listeners = CopyOnWriteArraySet<Listener>()

    fun addListener(listener: Listener) {
        listeners += listener
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun localLayerPublished(layer: StudentExplanationLayer) {
        // No listener may observe the event until the exact intent is fsynced in the sidecar.
        AssistantRepositoryProvider.current()?.ensurePendingStudentExplanationPublication(layer)
        listeners.forEach { listener -> runCatching { listener.onLocalLayerPublished(layer) } }
    }

    fun remoteLayerApplied(layer: StudentExplanationLayer) {
        listeners.forEach { listener -> runCatching { listener.onRemoteLayerApplied(layer) } }
    }
}
