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

    @Test fun summaryShowsInventoryCollectionWhenTotalIsNotKnownYet() {
        val state = RemotePageSyncUiState(
            pendingPages = List(9) { page(pageNumber = it + 1, changedAt = it.toLong()) },
            remainingApproxBytes = 666L * 1024L,
            inventoryPageCount = null,
            discoveredPageCount = 30,
            inventoryComplete = false,
        )

        assertEquals(
            "목록 수집 중 · 확인 30페이지 · 동기화 필요 9페이지 · 확인된 약 666KB",
            formatRemotePageSyncSummary(state),
        )
    }

    @Test fun summaryRetainsKnownInventoryTotalWhileCollectionIsIncomplete() {
        val state = RemotePageSyncUiState(
            pendingPages = listOf(page(pageNumber = 4, changedAt = 1L)),
            remainingApproxBytes = 2L * 1024L,
            inventoryPageCount = 37,
            discoveredPageCount = 30,
            inventoryComplete = false,
        )

        assertEquals(
            "목록 수집 30/37 · 동기화 필요 1페이지 · 확인된 약 2.0KB",
            formatRemotePageSyncSummary(state),
        )
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

    @Test fun panelIsVisibleForTeacherWithPendingPagesOrIncompleteInventory() {
        val pending = listOf(page(pageNumber = 4, changedAt = 1L))

        assertTrue(shouldShowRemotePageSyncPanel(RemotePageSyncUiState(isTeacher = true, pendingPages = pending)))
        assertFalse(shouldShowRemotePageSyncPanel(RemotePageSyncUiState(isTeacher = false, pendingPages = pending)))
        assertFalse(shouldShowRemotePageSyncPanel(RemotePageSyncUiState(isTeacher = true)))
        assertTrue(
            shouldShowRemotePageSyncPanel(
                RemotePageSyncUiState(isTeacher = true, inventoryComplete = false),
            ),
        )
        assertFalse(
            shouldShowRemotePageSyncPanel(
                RemotePageSyncUiState(isTeacher = false, inventoryComplete = false),
            ),
        )
        assertTrue(
            shouldShowRemotePageSyncPanel(
                RemotePageSyncUiState(
                    isTeacher = true,
                    activePage = page(pageNumber = 94, changedAt = 3L),
                ),
            ),
        )
    }

    @Test fun chunkProgressUsesExactBytesAndProducesAReadableStatus() {
        val progress = RemotePageSyncProgressUi(
            receivedChunks = 2,
            totalChunks = 5,
            receivedBytes = 400L,
            totalBytes = 1_000L,
        )
        val syncing = page(pageNumber = 72, changedAt = 1L).copy(
            status = RemotePageSyncPageStatus.SYNCING,
            progress = progress,
        )

        assertEquals(0.4f, remotePageSyncProgressFraction(progress))
        assertEquals("동기화 중 2/5 · 40%", formatRemotePageSyncPageStatus(syncing))
        assertEquals(
            "동기화 중 · 응답 대기",
            formatRemotePageSyncPageStatus(syncing.copy(progress = null)),
        )
    }

    @Test fun progressFractionIsClampedForDefensiveRendering() {
        assertEquals(
            1f,
            remotePageSyncProgressFraction(RemotePageSyncProgressUi(4, 3, 1_200L, 1_000L)),
        )
        assertEquals(
            0f,
            remotePageSyncProgressFraction(RemotePageSyncProgressUi(0, 3, -10L, 1_000L)),
        )
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
