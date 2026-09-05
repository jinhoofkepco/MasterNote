package com.studyink.construction.storage

import com.studyink.construction.core.ConstructionScene
import java.io.IOException
import java.util.UUID

/** The geometry belongs to exactly one local memo, independently of the memo's stroke format. */
data class ConstructionTarget(
    val bookId: String,
    val pageNumber: Int,
    val attemptNo: Int,
    val memoId: String,
    val ownerScope: String = "local",
) {
    init {
        require(bookId.isNotBlank() && bookId.toByteArray(Charsets.UTF_8).size <= 1_024)
        require(pageNumber in 0..1_000_000)
        // Reader teacher/general-page mode uses attempt 0; it must never alias student attempt 1.
        require(attemptNo in 0..1_000_000)
        require(memoId.length == 36 && UUID.fromString(memoId).toString() == memoId.lowercase())
        require(ownerScope.isNotBlank() && ownerScope.toByteArray(Charsets.UTF_8).size <= 1_024)
    }
}

/**
 * An immutable edit base. Retain this exact object for save(), then replace it with the returned
 * snapshot. The opaque tokens distinguish two different revisions restored to the same number.
 */
class ConstructionSceneSnapshot internal constructor(
    val target: ConstructionTarget,
    val revision: Long,
    val scene: ConstructionScene,
    internal val commitId: String?,
    internal val rootEpoch: Long,
    internal val rootIdentity: String,
)

/** Existing bytes are deliberately left in place; the caller must report the read failure. */
class ConstructionDataException(message: String, cause: Throwable? = null) : IOException(message, cause)
