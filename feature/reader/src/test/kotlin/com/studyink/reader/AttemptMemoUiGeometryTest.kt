package com.studyink.reader

import com.studyink.core.model.PagePoint
import com.studyink.memo.core.MemoAnchor
import com.studyink.memo.core.MemoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttemptMemoUiGeometryTest {
    @Test
    fun normalizedAnchorFollowsProblemPageBounds() {
        val bounds = MemoUiBounds(left = 100f, top = 40f, right = 500f, bottom = 840f)
        val center = memoAnchorCenter(MemoAnchor(0.25f, 0.75f), bounds)

        assertEquals(200f, center.x, 0.001f)
        assertEquals(640f, center.y, 0.001f)
        assertEquals(MemoAnchor(0.25f, 0.75f), memoAnchorAt(center.x, center.y, bounds))
    }

    @Test
    fun movedAnchorIsClampedToTheProblemPage() {
        val bounds = MemoUiBounds(left = 100f, top = 40f, right = 500f, bottom = 840f)
        assertEquals(MemoAnchor(0f, 1f), memoAnchorAt(-20f, 1_000f, bounds))
    }

    @Test
    fun iconHitUsesOneCircularTarget() {
        val center = MemoUiPoint(50f, 60f)
        assertTrue(memoIconHit(53f, 64f, center, 5f))
        assertFalse(memoIconHit(56f, 60f, center, 5f))
    }

    @Test
    fun overlappingMemoIconsReceiveSeparateStableCenters() {
        val bounds = MemoUiBounds(0f, 0f, 500f, 800f)
        val firstTwo = listOf(
            MemoUiAnchor("first", MemoAnchor(0.5f, 0.5f)),
            MemoUiAnchor("second", MemoAnchor(0.5f, 0.5f)),
        )
        val twoCenters = spreadMemoIconCenters(firstTwo, bounds, 40f, 20f)
        val withThird = spreadMemoIconCenters(
            firstTwo + MemoUiAnchor("third", MemoAnchor(0.5f, 0.5f)),
            bounds,
            40f,
            20f,
        )

        assertEquals(twoCenters["first"], withThird["first"])
        assertEquals(twoCenters["second"], withThird["second"])
        assertFalse(twoCenters["first"] == twoCenters["second"])
    }

    @Test
    fun moveModeCanRejectTouchesOutsideTheProblemPage() {
        val bounds = MemoUiBounds(10f, 20f, 100f, 200f)
        assertTrue(bounds.contains(10f, 20f))
        assertFalse(bounds.contains(50f, 10f))
    }

    @Test
    fun memoPointsRoundTripThroughFixedCanonicalCanvas() {
        val source = MemoPoint(0.25f, 0.8f, 0.7f)
        assertEquals(source, source.toCanonicalMemoPoint().toMemoPoint())

        val clamped = PagePoint(-20f, MEMO_CANONICAL_HEIGHT + 20f, 1f).toMemoPoint()
        assertEquals(0f, clamped.normalizedX, 0f)
        assertEquals(1f, clamped.normalizedY, 0f)
    }
}
