package com.studyink.reader

import com.studyink.memo.core.MemoCapacityExceededException
import com.studyink.memo.core.MemoLimitScope
import com.studyink.memo.core.MemoPayloadTooLargeException
import com.studyink.memo.core.MemoStorageLimits
import com.studyink.memo.core.MemoUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoCapacityUiTest {
    @Test fun `usage accounts for the first exhausted bound instead of only compressed bytes`() {
        assertEquals(0, memoUsagePercent(MemoUsage(0, 0, 0)))
        assertEquals(50, memoUsagePercent(MemoUsage(MemoStorageLimits.MAX_ENCODED_MEMO_BYTES / 2, 1, 1)))
        assertEquals(80, memoUsagePercent(MemoUsage(1000, 400_000, 1)))
        assertEquals(100, memoUsagePercent(MemoUsage(1000, 1, 16_384)))
    }

    @Test fun `capacity failures are distinguished from ordinary save errors`() {
        val bytes = MemoPayloadTooLargeException(9 * 1024 * 1024, MemoStorageLimits.MAX_ENCODED_MEMO_BYTES)
        val points = MemoCapacityExceededException(MemoLimitScope.POINTS_PER_MEMO, 500001, 500000)
        for (error in listOf(bytes, points)) {
            assertTrue(memoSaveFailureMessage(error).contains("저장 한도"))
            assertTrue(memoSaveFailureMessage(error).contains("마지막 저장 내용은 유지"))
        }
        assertTrue(memoSaveFailureMessage(IllegalStateException()).contains("저장하지 못했습니다"))
        assertTrue(memoUsageDescription(MemoUsage(1024 * 1024, 100, 3)).contains("1.00 / 8 MiB"))
    }
}
