package com.studyink.app

import com.studyink.construction.storage.ConstructionTarget
import com.studyink.monitor.telegram.RemoteReviewRole

internal const val CONSTRUCTION_TELEGRAM_PAYLOAD = "MEMO_CONSTRUCTION"

/** Page numbers are zero-based, including on the wire. */
internal data class ConstructionTelegramAddress(
    val pairId: String, val syncGeneration: Long, val pageToken: String,
    val workbookToken: String, val contentSha256: String,
    val pageNumber: Int, val attemptNo: Int, val memoId: String,
)

internal data class ConstructionTelegramRoute(
    val target: ConstructionTarget, val localRole: RemoteReviewRole,
    val peerBotId: Long, val address: ConstructionTelegramAddress,
) {
    val peerIsStudent: Boolean get() = localRole == RemoteReviewRole.TEACHER
}
