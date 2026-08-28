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
    /** The one page currently using the serial transfer slot, including an automatic page. */
    val activePage: RemotePageSyncPageUi? = null,
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
    val queueMode: RemotePageSyncQueueMode = RemotePageSyncQueueMode.MANUAL,
    /** Exact durable fragment progress. Null means the response size is not known yet. */
    val progress: RemotePageSyncProgressUi? = null,
)

enum class RemotePageSyncQueueMode { AUTOMATIC, MANUAL }

data class RemotePageSyncProgressUi(
    val receivedChunks: Int,
    val totalChunks: Int,
    val receivedBytes: Long,
    val totalBytes: Long,
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
    state.isTeacher && (
        state.activePage != null || state.pendingPages.isNotEmpty() || !state.inventoryComplete
    )

internal fun remotePageSyncProgressFraction(progress: RemotePageSyncProgressUi?): Float? {
    progress ?: return null
    return when {
        progress.totalBytes > 0L ->
            (progress.receivedBytes.toDouble() / progress.totalBytes.toDouble()).toFloat()
        progress.totalChunks > 0 -> progress.receivedChunks.toFloat() / progress.totalChunks.toFloat()
        else -> return null
    }.coerceIn(0f, 1f)
}

internal fun formatRemotePageSyncPageStatus(page: RemotePageSyncPageUi): String = when (page.status) {
    RemotePageSyncPageStatus.WAITING -> "대기"
    RemotePageSyncPageStatus.SYNCING -> page.progress?.let { progress ->
        val percent = ((remotePageSyncProgressFraction(progress) ?: 0f) * 100f).toInt()
        "동기화 중 ${progress.receivedChunks}/${progress.totalChunks} · ${percent}%"
    } ?: "동기화 중 · 응답 대기"
    RemotePageSyncPageStatus.READY -> "준비됨"
    RemotePageSyncPageStatus.FAILED -> "다시 시도"
    RemotePageSyncPageStatus.DEVICE_OFFLINE -> "기기 오프라인"
    RemotePageSyncPageStatus.MAPPING_REQUIRED -> "교재 선택 필요"
}

internal fun formatRemotePageSyncSummary(state: RemotePageSyncUiState): String =
    when {
        state.inventoryComplete ->
            "동기화 필요 ${state.pendingPages.size}페이지 · 약 " +
                formatRemotePageSyncBytes(state.remainingApproxBytes)

        state.inventoryPageCount != null ->
            "목록 수집 ${state.discoveredPageCount}/${state.inventoryPageCount} · " +
                "동기화 필요 ${state.pendingPages.size}페이지 · 확인된 약 " +
                formatRemotePageSyncBytes(state.remainingApproxBytes)

        else ->
            "목록 수집 중 · 확인 ${state.discoveredPageCount}페이지 · " +
                "동기화 필요 ${state.pendingPages.size}페이지 · 확인된 약 " +
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
