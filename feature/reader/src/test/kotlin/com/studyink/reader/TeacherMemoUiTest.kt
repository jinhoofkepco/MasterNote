package com.studyink.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TeacherMemoUiTest {
    @Test fun `teacher menus include memo creation without removing grade or redo`() {
        assertEquals(7, mainMenuItemCount(ReaderUiState()))
        for (role in listOf(ReaderRole.TEACHER_PHONE, ReaderRole.TEACHER_TABLET)) {
            val state = ReaderUiState(role = role, capabilities = ReaderCapabilities.forRole(role))
            assertTrue(state.capabilities.canGrade)
            assertEquals(COMMON_RADIAL_MENU_ITEM_COUNT, mainMenuItemCount(state))
        }
    }
}
