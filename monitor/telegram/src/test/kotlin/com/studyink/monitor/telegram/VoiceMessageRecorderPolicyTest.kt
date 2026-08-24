package com.studyink.monitor.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceMessageRecorderPolicyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun pathsStayInsideOutputDirectoryAndUsePartThenM4a() {
        val paths = VoiceMessageFilePolicy.resolve(temporary.root, "msg_01-ABC").getOrThrow()
        assertEquals("msg_01-ABC.part", paths.stagingFile.name)
        assertEquals("msg_01-ABC.m4a", paths.committedFile.name)
        assertTrue(VoiceMessageFilePolicy.resolve(temporary.root, "../escape").isFailure)
    }

    @Test fun stateMachineRejectsOverlappingRecordingsAndClosedReuse() {
        val machine = VoiceMessageRecorderStateMachine()
        assertTrue(machine.begin().isSuccess)
        assertTrue(machine.begin().isFailure)
        machine.idle()
        machine.close()
        assertTrue(machine.begin().isFailure)
    }
}
