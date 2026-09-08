package com.studyink.memo.core

/** Storage admission is deliberately smaller than transport's envelope-ready allowance. */
object MemoStorageLimits {
    const val MAX_ENCODED_MEMO_BYTES = 8 * 1024 * 1024
    const val MAX_TARGET_FILE_BYTES = 64 * 1024 * 1024
    const val MAX_MEMOS_PER_TARGET = 128
    const val MAX_STROKES_PER_MEMO = 16_384
    const val MAX_POINTS_PER_STROKE = 100_000
    const val MAX_POINTS_PER_MEMO = 500_000
    const val MAX_POINTS_PER_TARGET = 2_000_000
}

enum class MemoLimitScope {
    STORAGE_BYTES, TRANSPORT_BYTES, TARGET_BYTES,
    MEMOS_PER_TARGET, STROKES_PER_MEMO, POINTS_PER_STROKE, POINTS_PER_MEMO, POINTS_PER_TARGET,
}

open class MemoCapacityExceededException(
    val scope: MemoLimitScope,
    val actualCount: Long,
    val maximumCount: Long,
) : IllegalArgumentException("Memo capacity $scope is $actualCount; maximum is $maximumCount")

class MemoPayloadTooLargeException(
    val actualBytes: Int,
    val maximumBytes: Int = MemoTransportLimits.MAX_ENCODED_MEMO_BYTES,
    scope: MemoLimitScope = MemoLimitScope.TRANSPORT_BYTES,
) : MemoCapacityExceededException(scope, actualBytes.toLong(), maximumBytes.toLong())

data class MemoUsage(
    val encodedBytes: Int,
    val pointCount: Int,
    val strokeCount: Int,
    val maximumBytes: Int = MemoStorageLimits.MAX_ENCODED_MEMO_BYTES,
)

data class MemoExport(val memo: StudentMemo, val bytes: ByteArray)

internal fun checkMemoCapacity(actual: Int, maximum: Int, scope: MemoLimitScope) {
    if (actual > maximum) throw MemoCapacityExceededException(scope, actual.toLong(), maximum.toLong())
}
