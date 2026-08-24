package com.studyink.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentMessageSpeakerGateTest {
    @Test fun staleCompletionCannotFinishTheReplacementUtterance() {
        val gate = CurrentUtteranceGate()
        gate.begin("first")
        gate.begin("replacement")

        assertFalse(gate.finishIfCurrent("first"))
        assertTrue(gate.isCurrent("replacement"))
        assertTrue(gate.finishIfCurrent("replacement"))
        assertFalse(gate.isCurrent("replacement"))
    }

    @Test fun clearInvalidatesLateStartAndCompletionCallbacks() {
        val gate = CurrentUtteranceGate()
        gate.begin("current")

        gate.clear()

        assertFalse(gate.isCurrent("current"))
        assertFalse(gate.finishIfCurrent("current"))
        assertFalse(gate.finishIfCurrent(null))
    }
}
