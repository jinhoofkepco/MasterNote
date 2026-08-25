package com.studyink.app

import java.util.Locale

/** UI-only state for the teacher's pending Telegram page synchronization controls. */
data class RemotePageSyncUiState(
    val connected: Boolean = false,
    val isTeacher: Boolean = false,
    val pendingPages: List<RemotePageSyncPageUi> = emptyList(),
    val remainingApproxBytes: Long = 0L,
    val intervalSeconds: Int = DEFAULT_REMOTE_PAGE_SYNC_INTERVAL_SECONDS,
    val running: Boolean = false,
    /** Human-facing, one-based page number currently being synchronized. */
    val activePageNumber: Int? = null,
    val inventoryPageCount: Int? = null,
    val discoveredPageCount: Int = 0,
    val inventoryComplete: Boolean = true,
)

/** One workbook page summarized for the compact synchronization panel. */
data class RemotePageSyncPageUi(
    /** Stable pair/generation-scoped identity used only for explicit local workbook binding. */
    val pageToken: String = "",
    val workbookToken: String = "",
    val workbookLabel: String = "교재",
    /** Human-facing, one-based page number. */
    val pageNumber: Int,
    val attemptNos: List<Int> = emptyList(),
    val approxBytes: Long = 0L,
    val lastChangedEpochMs: Long = 0L,
    val status: RemotePageSyncPageStatus = RemotePageSyncPageStatus.WAITING,
)

/** A local workbook the teacher may explicitly bind to one remote workbook fingerprint. */
data class RemoteWorkbookMappingCandidate(
    val localBookId: String,
    val title: String,
    val pageCount: Int,
)

enum class RemotePageSyncPageStatus {
    WAITING,
    SYNCING,
    READY,
    FAILED,
    DEVICE_OFFLINE,
    MAPPING_REQUIRED,
}

internal const val DEFAULT_REMOTE_PAGE_SYNC_INTERVAL_SECONDS = 60
internal const val FAST_REMOTE_PAGE_SYNC_INTERVAL_SECONDS = 30

internal fun normalizeRemotePageSyncInterval(intervalSeconds: Int): Int =
    if (intervalSeconds == FAST_REMOTE_PAGE_SYNC_INTERVAL_SECONDS) {
        FAST_REMOTE_PAGE_SYNC_INTERVAL_SECONDS
    } else {
        DEFAULT_REMOTE_PAGE_SYNC_INTERVAL_SECONDS
    }

internal fun remotePageSyncPagesLatestFirst(
    pages: List<RemotePageSyncPageUi>,
): List<RemotePageSyncPageUi> = pages.sortedWith(
    compareByDescending<RemotePageSyncPageUi> { it.lastChangedEpochMs }
        .thenByDescending { it.pageNumber },
)

internal fun shouldShowRemotePageSyncPanel(state: RemotePageSyncUiState): Boolean =
    state.isTeacher && (state.pendingPages.isNotEmpty() ||
        state.inventoryPageCount != null && !state.inventoryComplete)

internal fun formatRemotePageSyncSummary(state: RemotePageSyncUiState): String =
    if (state.inventoryPageCount != null && !state.inventoryComplete) {
        "목록 수집 ${state.discoveredPageCount}/${state.inventoryPageCount} · " +
            "동기화 필요 ${state.pendingPages.size}페이지 · 확인된 약 " +
            formatRemotePageSyncBytes(state.remainingApproxBytes)
    } else {
        "동기화 필요 ${state.pendingPages.size}페이지 · 약 " +
            formatRemotePageSyncBytes(state.remainingApproxBytes)
    }

internal fun formatRemotePageSyncBytes(byteCount: Long): String {
    val bytes = byteCount.coerceAtLeast(0L)
    val kibibytes = bytes / 1024.0
    val mebibytes = kibibytes / 1024.0
    return when {
        bytes < 1024L -> "${bytes}B"
        bytes < 1024L * 1024L -> formatRemotePageSyncUnit(kibibytes, "KB")
        else -> formatRemotePageSyncUnit(mebibytes, "MB")
    }
}

private fun formatRemotePageSyncUnit(value: Double, unit: String): String =
    if (value >= 10.0) {
        String.format(Locale.KOREA, "%.0f%s", value, unit)
    } else {
        String.format(Locale.KOREA, "%.1f%s", value, unit)
    }
