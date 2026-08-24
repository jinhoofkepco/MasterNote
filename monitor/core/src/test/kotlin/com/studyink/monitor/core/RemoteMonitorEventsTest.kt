package com.studyink.monitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteMonitorEventsTest {
    @Test fun listenersCanRemoveThemselvesDuringDelivery() {
        val received = mutableListOf<Long>()
        lateinit var subscription: MonitorSubscription
        subscription = ParentMessageBus.subscribe {
            received += it.updateId
            subscription.close()
        }

        ParentMessageBus.publish(ParentMessage(1L, "one", 10L))
        ParentMessageBus.publish(ParentMessage(2L, "two", 20L))

        assertEquals(listOf(1L), received)
    }

    @Test fun studyPresenceIsSticky() {
        StudentStudyPresenceBus.clear()
        val expected = StudentStudyPresence("book", 17, 4, true, 100L)
        StudentStudyPresenceBus.publish(expected)
        var received: StudentStudyPresence? = null

        val subscription = StudentStudyPresenceBus.subscribe { received = it }

        assertEquals(expected, StudentStudyPresenceBus.current())
        assertEquals(expected, received)
        subscription.close()
        StudentStudyPresenceBus.clear()
        assertNull(StudentStudyPresenceBus.current())
    }
}
