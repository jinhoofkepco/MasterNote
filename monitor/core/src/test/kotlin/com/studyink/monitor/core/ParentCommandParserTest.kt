package com.studyink.monitor.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParentCommandParserTest {
    @Test fun parsesKoreanAndBotAddressedScreenCommand() {
        assertEquals(ParentInboundAction.CurrentPageSnapshot, ParentCommandParser.parse("/화면"))
        assertEquals(
            ParentInboundAction.CurrentPageSnapshot,
            ParentCommandParser.parse("/화면@MasterNoteParentBot 지금"),
        )
        assertEquals(ParentInboundAction.CurrentPageSnapshot, ParentCommandParser.parse("/screen"))
    }

    @Test fun ordinaryTextIsTrimmedAndForwarded() {
        assertEquals(ParentInboundAction.Text("집중해 보자"), ParentCommandParser.parse("  집중해 보자  "))
    }

    @Test fun parsesActivityReportingModeCommands() {
        assertEquals(ParentInboundAction.EnableRealtimeActivity, ParentCommandParser.parse("/실시간"))
        assertEquals(
            ParentInboundAction.EnableRealtimeActivity,
            ParentCommandParser.parse("/realtime@MasterNoteParentBot"),
        )
        assertEquals(ParentInboundAction.UseHourlyActivity, ParentCommandParser.parse("/일반"))
        assertEquals(ParentInboundAction.UseHourlyActivity, ParentCommandParser.parse("/hourly"))
    }

    @Test fun unknownCommandsAndPairingCommandAreNotShownAsParentText() {
        assertNull(ParentCommandParser.parse("/unknown"))
        assertNull(ParentCommandParser.parse("/연결"))
        assertTrue(ParentCommandParser.isPairingRequest("/연결@my_bot"))
    }
}
