package com.studyink.monitor.core

/**
 * Cross-module maintenance boundary used before the live MasterNote data root is replaced.
 *
 * The app owns the renderer executor while the restore UI lives in feature:library. Keeping this
 * tiny process-local bridge in monitor:core lets restore wait for already queued renders without
 * making either module depend on the application module.
 */
object RemoteMonitorMaintenanceBus {
    interface Handler {
        fun pauseAndAwait(timeoutMillis: Long): Boolean
        /** Called while paused, after a validated restore has replaced the live data root. */
        fun onDataRootReplaced() = Unit
        fun resume()
    }

    private val lock = Any()
    private var handler: Handler? = null

    fun install(next: Handler): MonitorSubscription {
        synchronized(lock) { handler = next }
        return MonitorSubscription {
            synchronized(lock) {
                if (handler === next) handler = null
            }
        }
    }

    fun pauseAndAwait(timeoutMillis: Long): Boolean {
        require(timeoutMillis > 0L)
        return synchronized(lock) { handler }?.pauseAndAwait(timeoutMillis) ?: true
    }

    fun resume() {
        synchronized(lock) { handler }?.resume()
    }

    fun onDataRootReplaced() {
        synchronized(lock) { handler }?.onDataRootReplaced()
    }
}
