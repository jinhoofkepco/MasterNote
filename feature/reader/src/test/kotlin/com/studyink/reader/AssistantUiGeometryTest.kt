package com.studyink.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantUiGeometryTest {
    @Test
    fun normalizedRect_reversesAndClampsBothPointers() {
        val limit = AssistantUiRect(10f, 20f, 110f, 220f)

        val result = normalizedAssistantRect(
            startX = 140f,
            startY = 240f,
            endX = -30f,
            endY = 5f,
            limit = limit,
        )

        assertEquals(limit, result)
    }

    @Test
    fun resizeCorner_preservesMinimumSizeAndLimit() {
        val limit = AssistantUiRect(0f, 0f, 200f, 200f)
        val original = AssistantUiRect(30f, 40f, 130f, 160f)

        val tooFar = resizeAssistantSelection(
            original = original,
            handle = AssistantSelectionHandle.TOP_LEFT,
            pointerX = 190f,
            pointerY = 190f,
            limit = limit,
            minimumSize = 24f,
        )
        val beyondLimit = resizeAssistantSelection(
            original = original,
            handle = AssistantSelectionHandle.BOTTOM_RIGHT,
            pointerX = 400f,
            pointerY = 500f,
            limit = limit,
            minimumSize = 24f,
        )

        assertEquals(24f, tooFar.width, 0.001f)
        assertEquals(24f, tooFar.height, 0.001f)
        assertEquals(200f, beyondLimit.right, 0.001f)
        assertEquals(200f, beyondLimit.bottom, 0.001f)
    }

    @Test
    fun chips_preferAnchorRight_thenLeft_andStayInViewport() {
        val viewport = AssistantUiRect(0f, 0f, 300f, 240f)
        val placements = placeAssistantChips(
            chips = listOf(
                AssistantAnchoredChip("right", AssistantUiRect(20f, 20f, 70f, 70f), 80f, 36f),
                AssistantAnchoredChip("left", AssistantUiRect(250f, 90f, 290f, 140f), 80f, 36f),
            ),
            viewport = viewport,
            gap = 8f,
        )

        assertEquals(78f, placements[0].bounds.left, 0.001f)
        assertEquals(162f, placements[1].bounds.left, 0.001f)
        placements.forEach { placement ->
            assertTrue(placement.bounds.left >= viewport.left)
            assertTrue(placement.bounds.top >= viewport.top)
            assertTrue(placement.bounds.right <= viewport.right)
            assertTrue(placement.bounds.bottom <= viewport.bottom)
        }
    }

    @Test
    fun collidingChips_moveWithoutOverlapping() {
        val viewport = AssistantUiRect(0f, 0f, 320f, 300f)
        val commonAnchor = AssistantUiRect(20f, 20f, 80f, 80f)

        val placements = placeAssistantChips(
            chips = listOf(
                AssistantAnchoredChip("one", commonAnchor, 100f, 40f),
                AssistantAnchoredChip("two", commonAnchor, 100f, 40f),
                AssistantAnchoredChip("three", commonAnchor, 100f, 40f),
            ),
            viewport = viewport,
            gap = 8f,
        )

        assertEquals(3, placements.size)
        assertFalse(placements[0].bounds.intersects(placements[1].bounds))
        assertFalse(placements[0].bounds.intersects(placements[2].bounds))
        assertFalse(placements[1].bounds.intersects(placements[2].bounds))
    }

    @Test
    fun panel_fallsBackAndClampsToViewport() {
        val viewport = AssistantUiRect(10f, 20f, 310f, 220f)
        val anchor = AssistantUiRect(270f, 190f, 300f, 210f)

        val panel = placeAssistantPanel(anchor, viewport, width = 180f, height = 150f, gap = 8f)

        assertEquals(82f, panel.left, 0.001f)
        assertEquals(70f, panel.top, 0.001f)
        assertEquals(262f, panel.right, 0.001f)
        assertEquals(220f, panel.bottom, 0.001f)
    }

    @Test
    fun hitPolicy_closeThenPanelThenChip_andOutsidePassesThrough() {
        val chips = listOf(
            AssistantChipPlacement("card", AssistantUiRect(10f, 10f, 100f, 50f)),
        )
        val panel = AssistantUiRect(70f, 20f, 250f, 200f)
        val close = AssistantUiRect(210f, 20f, 250f, 60f)

        assertEquals(
            AssistantOverlayHit(AssistantOverlayHitKind.CLOSE, "card"),
            assistantOverlayHitTest(225f, 35f, chips, "card", panel, close),
        )
        assertEquals(
            AssistantOverlayHit(AssistantOverlayHitKind.PANEL, "card"),
            assistantOverlayHitTest(80f, 30f, chips, "card", panel, close),
        )
        assertEquals(
            AssistantOverlayHit(AssistantOverlayHitKind.CHIP, "card"),
            assistantOverlayHitTest(20f, 20f, chips, "card", panel, close),
        )
        assertEquals(
            AssistantOverlayHit(AssistantOverlayHitKind.OUTSIDE),
            assistantOverlayHitTest(280f, 230f, chips, "card", panel, close),
        )
    }
}
