package com.studyink.reader

import com.studyink.memo.core.MemoCapacityExceededException
import com.studyink.memo.core.MemoPayloadTooLargeException
import com.studyink.memo.core.MemoStorageLimits
import com.studyink.memo.core.MemoUsage
import java.util.Locale
import kotlin.math.ceil

/** The first exhausted bound wins: byte usage alone understates a very compressible note. */
internal fun memoUsagePercent(usage: MemoUsage): Int = ceil(100.0 * maxOf(
    usage.encodedBytes.toDouble() / MemoStorageLimits.MAX_ENCODED_MEMO_BYTES,
    usage.pointCount.toDouble() / MemoStorageLimits.MAX_POINTS_PER_MEMO,
    usage.strokeCount.toDouble() / MemoStorageLimits.MAX_STROKES_PER_MEMO,
)).toInt().coerceIn(0, 100)

internal fun memoUsageDescription(usage: MemoUsage): String =
    "메모 사용 ${memoUsagePercent(usage)}% · 압축 저장 " +
        String.format(Locale.KOREA, "%.2f / 8 MiB", usage.encodedBytes / (1024.0 * 1024.0)) +
        "\n필기점 ${usage.pointCount} / ${MemoStorageLimits.MAX_POINTS_PER_MEMO}" +
        " · 획 ${usage.strokeCount} / ${MemoStorageLimits.MAX_STROKES_PER_MEMO}"

internal fun memoSaveFailureMessage(error: Throwable): String = when (error) {
    is MemoPayloadTooLargeException, is MemoCapacityExceededException ->
        "메모 저장 한도에 도달했습니다. 마지막 저장 내용은 유지됩니다. 새 메모에 이어 써주세요."
    else -> "메모를 저장하지 못했습니다. 마지막 저장 내용은 유지됩니다."
}
