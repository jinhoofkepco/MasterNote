package com.studyink.monitor.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteGradeAppliedBusTest {
    @Test
    fun publishesOnlyToCurrentSubscribers() {
        val events = mutableListOf<RemoteGradeApplied>()
        val subscription = RemoteGradeAppliedBus.subscribe(events::add)
        val event = RemoteGradeApplied("book", 2, 3, "grade_group", correct = true)

        assertEquals(1, RemoteGradeAppliedBus.publish(event))
        subscription.close()
        assertEquals(0, RemoteGradeAppliedBus.publish(event))
        assertEquals(listOf(event), events)
    }
}
