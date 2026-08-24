package com.studyink.monitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteMonitorMaintenanceBusTest {
    @Test
    fun delegatesPauseAndResumeAndDetachesCleanly() {
        var pauseTimeout = 0L
        var resumes = 0
        var replacements = 0
        val registration = RemoteMonitorMaintenanceBus.install(object : RemoteMonitorMaintenanceBus.Handler {
            override fun pauseAndAwait(timeoutMillis: Long): Boolean {
                pauseTimeout = timeoutMillis
                return true
            }

            override fun resume() {
                resumes++
            }

            override fun onDataRootReplaced() {
                replacements++
            }
        })

        assertTrue(RemoteMonitorMaintenanceBus.pauseAndAwait(321L))
        RemoteMonitorMaintenanceBus.onDataRootReplaced()
        RemoteMonitorMaintenanceBus.resume()
        assertEquals(321L, pauseTimeout)
        assertEquals(1, replacements)
        assertEquals(1, resumes)

        registration.close()
        assertTrue(RemoteMonitorMaintenanceBus.pauseAndAwait(1L))
        RemoteMonitorMaintenanceBus.resume()
        assertEquals(1, resumes)
        assertEquals(1, replacements)
    }
}
