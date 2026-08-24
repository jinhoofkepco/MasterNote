package com.studyink.monitor.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteReviewEventsTest {
    @Test
    fun subscriptionIsRemovableAndFailuresAreIsolated() {
        var delivered = 0
        val failing = RemoteReviewFeedbackBus.subscribe { error("listener failure") }
        val healthy = RemoteReviewFeedbackBus.subscribe { delivered++ }
        val event = RemoteTeacherFeedbackApplied("book", 2, 1, "transfer", 4L)

        assertEquals(2, RemoteReviewFeedbackBus.publish(event))
        assertEquals(1, delivered)
        failing.close()
        healthy.close()
        assertEquals(0, RemoteReviewFeedbackBus.publish(event))
    }
}
