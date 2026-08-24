package com.studyink.monitor.telegram

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TelegramConnectionStateTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun transitionsOnlyAfterAProvenConnection() {
        var state: TelegramConnectionState = TelegramConnectionState.Unknown
        state = TelegramConnectionStateMachine.reduce(state, TelegramConnectionEvent.NetworkFailure(10L))
        assertEquals(TelegramConnectionState.Unknown, state)
        state = TelegramConnectionStateMachine.reduce(state, TelegramConnectionEvent.Success(20L))
        assertEquals(TelegramConnectionState.Connected, state)
        state = TelegramConnectionStateMachine.reduce(state, TelegramConnectionEvent.NetworkFailure(30L))
        assertEquals(TelegramConnectionState.Outage(30L), state)
        state = TelegramConnectionStateMachine.reduce(state, TelegramConnectionEvent.Success(40L))
        assertEquals(TelegramConnectionState.Connected, state)
    }

    @Test fun persistedConnectedStateIsRearmedOnNewOwner() {
        val file = temporary.newFile("connection")
        val first = TelegramConnectionStateStore(file)
        first.transition(TelegramConnectionEvent.Success(1L))

        assertEquals(TelegramConnectionState.Unknown, TelegramConnectionStateStore(file).state())
    }
}
