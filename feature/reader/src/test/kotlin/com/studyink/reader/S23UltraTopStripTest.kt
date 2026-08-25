package com.studyink.reader

import android.content.res.Configuration
import androidx.compose.ui.input.pointer.PointerType
import com.studyink.sync.lan.LanConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class S23UltraTopStripTest {
    @Test
    fun studentPageShortcutShowsForAnotherPage() {
        val state = studentShortcutState(
            pageNumber = 3,
            attemptNo = 2,
            studentPageNumber = 4,
            studentAttemptNo = 2,
        )

        assertTrue(state.showsStudentPageShortcut())
    }

    @Test
    fun studentPageShortcutShowsForAnotherAttemptOnTheSamePage() {
        val state = studentShortcutState(
            pageNumber = 3,
            attemptNo = 2,
            studentPageNumber = 3,
            studentAttemptNo = 3,
        )

        assertTrue(state.showsStudentPageShortcut())
    }

    @Test
    fun studentPageShortcutShowsForAnotherWorkbookEvenAtTheSamePageAndAttempt() {
        val state = studentShortcutState(
            pageNumber = 3,
            attemptNo = 2,
            studentPageNumber = 3,
            studentAttemptNo = 2,
        ).copy(bookId = "teacher-book", studentBookId = "student-book", studentBookTitle = "수학")

        assertTrue(state.showsStudentPageShortcut())
        assertTrue(state.copy(isFollowingStudent = true).shouldFollowRemoteStudentPage("student-book", 3, 2))
    }

    @Test
    fun studentPageShortcutHidesForTheExactVisibleTargetOrMissingRemotePage() {
        val exactTarget = studentShortcutState(
            pageNumber = 3,
            attemptNo = 2,
            studentPageNumber = 3,
            studentAttemptNo = 2,
        )
        val missingRemotePage = exactTarget.copy(studentPageNumber = null)

        assertFalse(exactTarget.showsStudentPageShortcut())
        assertFalse(missingRemotePage.showsStudentPageShortcut())
    }

    @Test
    fun pageReadinessDoesNotChangeStudentPageShortcutVisibility() {
        val waitingForPage = studentShortcutState(
            pageNumber = 3,
            attemptNo = 2,
            studentPageNumber = 4,
            studentAttemptNo = 2,
            studentPageReady = false,
        )

        assertTrue(waitingForPage.showsStudentPageShortcut())
        assertTrue(waitingForPage.copy(studentPageReady = true).showsStudentPageShortcut())
    }

    @Test
    fun attemptCellDirectTapBelongsToFingerNotStylus() {
        assertTrue(s23AttemptCellHandlesDirectPointer(PointerType.Touch))
        assertFalse(s23AttemptCellHandlesDirectPointer(PointerType.Stylus))
        assertFalse(s23AttemptCellHandlesDirectPointer(PointerType.Mouse))
    }

    @Test
    fun stripStillReservesExactlyTenCellsIncludingFourAttemptCells() {
        assertEquals(10, S23_STRIP_CELL_COUNT)
        assertEquals(4, S23_STRIP_HISTORY_CELL_COUNT)
        assertEquals(6, S23_STRIP_CELL_COUNT - S23_STRIP_HISTORY_CELL_COUNT)
    }

    @Test
    fun onlyS23UltraTeacherPhonePortraitUsesTheDeviceStrip() {
        assertTrue(
            shouldUseS23UltraTopStrip(
                model = "SM-S918N",
                orientation = Configuration.ORIENTATION_PORTRAIT,
                role = ReaderRole.TEACHER_PHONE,
            ),
        )
        assertTrue(
            shouldUseS23UltraTopStrip(
                model = "sm-s918u1",
                orientation = Configuration.ORIENTATION_PORTRAIT,
                role = ReaderRole.TEACHER_PHONE,
            ),
        )

        assertFalse(
            shouldUseS23UltraTopStrip(
                model = "SM-S918N",
                orientation = Configuration.ORIENTATION_LANDSCAPE,
                role = ReaderRole.TEACHER_PHONE,
            ),
        )
        assertFalse(
            shouldUseS23UltraTopStrip(
                model = "SM-S918N",
                orientation = Configuration.ORIENTATION_PORTRAIT,
                role = ReaderRole.TEACHER_TABLET,
            ),
        )
        assertFalse(
            shouldUseS23UltraTopStrip(
                model = "SM-S928N",
                orientation = Configuration.ORIENTATION_PORTRAIT,
                role = ReaderRole.TEACHER_PHONE,
            ),
        )
    }

    @Test
    fun attemptLaneKeepsCurrentAndThreeEarlierFramesWhenAvailable() {
        val bundles = (1..8).map { ReaderAttemptMarkBundle(it, emptyList()) }

        assertEquals(listOf(1, 2, 3, 4), s23VisibleAttemptBundles(bundles, 1).map { it.attemptNo })
        assertEquals(listOf(1, 2, 3, 4), s23VisibleAttemptBundles(bundles, 4).map { it.attemptNo })
        assertEquals(listOf(5, 6, 7, 8), s23VisibleAttemptBundles(bundles, 8).map { it.attemptNo })
    }

    @Test
    fun shortAttemptHistoryDoesNotInventFrames() {
        val bundles = listOf(
            ReaderAttemptMarkBundle(1, emptyList()),
            ReaderAttemptMarkBundle(2, emptyList()),
        )

        assertEquals(listOf(1, 2), s23VisibleAttemptBundles(bundles, 2).map { it.attemptNo })
    }

    @Test
    fun usableLanAlwaysOwnsTheSingleTransportCell() {
        listOf(
            S23TransportLinkState.CONNECTED to S23TransportTone.CONNECTED,
            S23TransportLinkState.READY to S23TransportTone.CONNECTED,
            S23TransportLinkState.CONNECTING to S23TransportTone.TRANSITIONING,
        ).forEach { (lan, expectedTone) ->
            val model = S23TransportCellModel(
                lan = lan,
                telegram = S23TransportLinkState.READY,
                telegramUnreadCount = 3,
            )

            assertEquals(S23TransportMode.LIVE, model.activeMode)
            assertEquals("실", model.label)
            assertEquals(expectedTone, model.activeTone)
            assertEquals(3, model.telegramUnreadCount)
        }
    }

    @Test
    fun telegramOwnsTheSameCellOnlyWhenLanIsUnavailable() {
        listOf(
            S23TransportLinkState.CONNECTED to S23TransportTone.CONNECTED,
            S23TransportLinkState.READY to S23TransportTone.CONNECTED,
            S23TransportLinkState.CONNECTING to S23TransportTone.TRANSITIONING,
            S23TransportLinkState.QUEUED to S23TransportTone.TRANSITIONING,
        ).forEach { (telegram, expectedTone) ->
            val model = S23TransportCellModel(
                lan = S23TransportLinkState.UNAVAILABLE,
                telegram = telegram,
            )

            assertEquals(S23TransportMode.TELEGRAM, model.activeMode)
            assertEquals("텔", model.label)
            assertEquals(expectedTone, model.activeTone)
        }
    }

    @Test
    fun bothUnavailableFallsBackToGrayLiveLabel() {
        val model = S23TransportCellModel(
            lan = S23TransportLinkState.UNAVAILABLE,
            telegram = S23TransportLinkState.UNAVAILABLE,
        )

        assertEquals(S23TransportMode.LIVE, model.activeMode)
        assertEquals("실", model.label)
        assertEquals(S23TransportTone.UNAVAILABLE, model.activeTone)
    }

    @Test
    fun legacyLanMappingPreservesExistingMenuStateUntilCoordinatorIsWired() {
        assertEquals(
            S23TransportTone.CONNECTED,
            s23TransportCellModelForLan(LanConnectionState.CONNECTED).activeTone,
        )
        assertEquals(
            S23TransportTone.TRANSITIONING,
            s23TransportCellModelForLan(LanConnectionState.CONNECTING).activeTone,
        )
        assertEquals(
            S23TransportTone.UNAVAILABLE,
            s23TransportCellModelForLan(LanConnectionState.DISCONNECTED).activeTone,
        )
        assertEquals(
            S23TransportTone.UNAVAILABLE,
            s23TransportCellModelForLan(LanConnectionState.IDLE).activeTone,
        )
    }

    @Test
    fun unreadBadgeIsBoundedWithoutLosingTheUnderlyingCount() {
        assertEquals(null, s23UnreadBadgeLabel(-1))
        assertEquals(null, s23UnreadBadgeLabel(0))
        assertEquals("1", s23UnreadBadgeLabel(1))
        assertEquals("9", s23UnreadBadgeLabel(9))
        assertEquals("9+", s23UnreadBadgeLabel(10))
        assertEquals("9+", s23UnreadBadgeLabel(999))
    }

    private fun studentShortcutState(
        pageNumber: Int,
        attemptNo: Int,
        studentPageNumber: Int?,
        studentAttemptNo: Int?,
        studentPageReady: Boolean = true,
    ): ReaderUiState = ReaderUiState(
        pageNumber = pageNumber,
        attemptNo = attemptNo,
        role = ReaderRole.TEACHER_PHONE,
        workflow = ReaderWorkflow.LIVE_MONITOR,
        capabilities = ReaderCapabilities.forRole(ReaderRole.TEACHER_PHONE),
        studentPageNumber = studentPageNumber,
        studentAttemptNo = studentAttemptNo,
        studentPageReady = studentPageReady,
    )
}
