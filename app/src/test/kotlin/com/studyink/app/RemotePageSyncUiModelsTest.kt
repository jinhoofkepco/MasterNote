package com.studyink.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePageSyncUiModelsTest {
    @Test fun summaryShowsPendingPageCountAndApproximateBytes() {
        val state = RemotePageSyncUiState(
            pendingPages = listOf(
                page(pageNumber = 2, changedAt = 10L),
                page(pageNumber = 7, changedAt = 20L),
            ),
            remainingApproxBytes = 2L * 1024L * 1024L,
        )

        assertEquals("동기화 필요 2페이지 · 약 2.0MB", formatRemotePageSyncSummary(state))
    }

    @Test fun pagesAreOrderedByLatestChangeThenHigherPageNumber() {
        val ordered = remotePageSyncPagesLatestFirst(
            listOf(
                page(pageNumber = 1, changedAt = 20L),
                page(pageNumber = 9, changedAt = 10L),
                page(pageNumber = 3, changedAt = 20L),
            ),
        )

        assertEquals(listOf(3, 1, 9), ordered.map(RemotePageSyncPageUi::pageNumber))
    }

    @Test fun intervalAcceptsThirtySecondsAndDefaultsEverythingElseToOneMinute() {
        assertEquals(30, normalizeRemotePageSyncInterval(30))
        assertEquals(60, normalizeRemotePageSyncInterval(60))
        assertEquals(60, normalizeRemotePageSyncInterval(0))
        assertEquals(60, normalizeRemotePageSyncInterval(45))
    }

    @Test fun panelIsVisibleOnlyForATeacherWithPendingPages() {
        val pending = listOf(page(pageNumber = 4, changedAt = 1L))

        assertTrue(shouldShowRemotePageSyncPanel(RemotePageSyncUiState(isTeacher = true, pendingPages = pending)))
        assertFalse(shouldShowRemotePageSyncPanel(RemotePageSyncUiState(isTeacher = false, pendingPages = pending)))
        assertFalse(shouldShowRemotePageSyncPanel(RemotePageSyncUiState(isTeacher = true)))
    }

    @Test fun byteFormatterIsBoundedAndReadable() {
        assertEquals("0B", formatRemotePageSyncBytes(-1L))
        assertEquals("512B", formatRemotePageSyncBytes(512L))
        assertEquals("1.5KB", formatRemotePageSyncBytes(1_536L))
        assertEquals("12KB", formatRemotePageSyncBytes(12L * 1024L))
    }

    private fun page(pageNumber: Int, changedAt: Long) = RemotePageSyncPageUi(
        pageNumber = pageNumber,
        lastChangedEpochMs = changedAt,
    )
}
