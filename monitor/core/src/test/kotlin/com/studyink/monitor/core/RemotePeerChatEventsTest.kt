package com.studyink.monitor.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemotePeerChatEventsTest {
    @After fun clearBus() = RemotePeerChatStateBus.clear()

    @Test fun scopedSubscribersReceiveOnlyNewerMatchingStateAndListenerFailureIsIsolated() {
        val firstScope = scope("pair_scope_0001")
        val secondScope = scope("pair_scope_0002")
        var firstDeliveries = 0
        var globalDeliveries = 0
        val failing = RemotePeerChatStateBus.subscribe(firstScope, emitCurrent = false) {
            error("listener failure")
        }
        val scoped = RemotePeerChatStateBus.subscribe(firstScope, emitCurrent = false) {
            firstDeliveries++
        }
        val global = RemotePeerChatStateBus.subscribe(emitCurrent = false) { globalDeliveries++ }

        try {
            assertEquals(3, RemotePeerChatStateBus.publish(state(firstScope, revision = 1L)))
            assertEquals(0, RemotePeerChatStateBus.publish(state(firstScope, revision = 1L)))
            assertEquals(1, RemotePeerChatStateBus.publish(state(secondScope, revision = 1L)))
            assertEquals(1, firstDeliveries)
            assertEquals(2, globalDeliveries)
            assertEquals(1L, RemotePeerChatStateBus.current(firstScope)?.stateRevision)
        } finally {
            failing.close()
            scoped.close()
            global.close()
        }
    }

    @Test fun scopedSubscriptionCanReplayCurrentAndClearExactPairOnly() {
        val first = scope("pair_scope_0001")
        val second = scope("pair_scope_0002")
        RemotePeerChatStateBus.publish(state(first, revision = 2L))
        RemotePeerChatStateBus.publish(state(second, revision = 3L))
        var replayed: RemotePeerChatState? = null

        RemotePeerChatStateBus.subscribe(first) { replayed = it }.close()
        RemotePeerChatStateBus.clear(first)

        assertEquals(2L, replayed?.stateRevision)
        assertNull(RemotePeerChatStateBus.current(first))
        assertEquals(3L, RemotePeerChatStateBus.current(second)?.stateRevision)
    }

    private fun state(scope: RemotePeerChatScope, revision: Long) = RemotePeerChatState(
        scope = scope,
        recentMessages = emptyList(),
        retainedMessageCount = 0,
        unreadCount = 0,
        stateRevision = revision,
        lastReadAtEpochMs = null,
    )

    private fun scope(pairId: String) = RemotePeerChatScope(
        pairId = pairId,
        localDeviceId = "local_device_0001",
        peerDeviceId = "peer_device_00001",
    )
}
