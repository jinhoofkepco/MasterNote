package com.studyink.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkBundleLayoutTest {
    @Test
    fun oneResultUsesOneCell() {
        val layout = layoutFor(1)

        assertEquals(1, layout.columns)
        assertEquals(1, layout.rows)
        assertEquals(1, layout.cells.size)
    }

    @Test
    fun eightResultsMakeTheApprovedFourByTwoBundle() {
        val layout = layoutFor(8)

        assertEquals(4, layout.columns)
        assertEquals(2, layout.rows)
        assertEquals(8, layout.cells.size)
    }

    @Test
    fun arbitraryHistoriesRemainCompactInsteadOfBecomingOneLongStrip() {
        (1..80).forEach { historyCount ->
            val layout = layoutFor(historyCount)

            assertEquals(historyCount, layout.cells.size)
            assertTrue(layout.columns >= layout.rows)
            if (historyCount >= 4) assertTrue(layout.rows > 1)
        }
    }

    @Test
    fun incompleteLastRowIsCentered() {
        val layout = layoutFor(5)
        val lastRow = layout.cells.filter { it.row == layout.rows - 1 }

        assertTrue(lastRow.isNotEmpty())
        assertTrue(lastRow.first().column > 0f)
    }

    @Test
    fun originSnapsToPaperGridAndStaysInsidePage() {
        val aligned = alignedMarkBundleOrigin(
            anchorX = 43f,
            anchorY = 58f,
            pageLeft = 10f,
            pageTop = 10f,
            pageRight = 200f,
            pageBottom = 240f,
            bundleWidth = 30f,
            bundleHeight = 30f,
            horizontalSnap = 4f,
            verticalSnap = 8f,
            edgePadding = 3f,
        )

        assertEquals(42f, aligned.x, 0.001f)
        assertEquals(58f, aligned.y, 0.001f)

        val clamped = alignedMarkBundleOrigin(
            anchorX = 199f,
            anchorY = 239f,
            pageLeft = 10f,
            pageTop = 10f,
            pageRight = 200f,
            pageBottom = 240f,
            bundleWidth = 30f,
            bundleHeight = 30f,
            horizontalSnap = 4f,
            verticalSnap = 8f,
            edgePadding = 3f,
        )
        assertEquals(167f, clamped.x, 0.001f)
        assertEquals(207f, clamped.y, 0.001f)
    }

    @Test
    fun overlappingNearbyBundleMovesToNextSafeRow() {
        val result = nonOverlappingMarkBundleOrigin(
            candidate = MarkBundleOrigin(40f, 40f),
            bundleWidth = 30f,
            bundleHeight = 22f,
            minY = 10f,
            maxY = 180f,
            verticalStep = 8f,
            separation = 2f,
            occupied = listOf(MarkBundleBox(38f, 38f, 72f, 64f)),
        )

        assertEquals(72f, result.y, 0.001f)
    }

    @Test
    fun horizontalSeparationDoesNotMoveAnOtherwiseAlignedBundle() {
        val result = nonOverlappingMarkBundleOrigin(
            candidate = MarkBundleOrigin(80f, 40f),
            bundleWidth = 20f,
            bundleHeight = 20f,
            minY = 10f,
            maxY = 180f,
            verticalStep = 8f,
            separation = 2f,
            occupied = listOf(MarkBundleBox(20f, 38f, 50f, 62f)),
        )

        assertEquals(40f, result.y, 0.001f)
    }

    private fun layoutFor(count: Int) = com.studyink.core.model.resultBundleGrid(count)
}
