package com.studyink.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeerChatOverlayDeliveryGateTest {
    @Test
    fun stickyMessageBeforeResumeIsDeliveredExactlyOnce() {
        val gate = PeerChatOverlayDeliveryGate()

        assertNull(gate.offer("pair-a", "message-1", "확인해줘", canDisplayNow = false))
        assertEquals("message-1", gate.resume("pair-a")?.messageId)
        assertNull(gate.resume("pair-a"))
        assertNull(gate.offer("pair-a", "message-1", "확인해줘", canDisplayNow = true))
    }

    @Test
    fun newestPendingMessageWinsAndWrongPairIsDiscarded() {
        val gate = PeerChatOverlayDeliveryGate()

        gate.offer("pair-a", "message-1", "첫 메시지", canDisplayNow = false)
        gate.offer("pair-a", "message-2", "둘째 메시지", canDisplayNow = false)
        assertEquals("message-2", gate.resume("pair-a")?.messageId)

        gate.offer("pair-old", "message-3", "오래된 연결", canDisplayNow = false)
        assertNull(gate.resume("pair-new"))
        assertNull(gate.resume("pair-old"))
    }
}
