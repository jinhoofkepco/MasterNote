package com.studyink.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteReviewGeometryTest {
    @Test
    fun portraitPageFitsCenterWithoutChangingAspectRatio() {
        val page = RemoteReviewGeometry.fitCenter(
            containerWidth = 1_000f,
            containerHeight = 1_000f,
            pageWidth = 600f,
            pageHeight = 800f,
        )

        assertEquals(125f, page.left, 0.001f)
        assertEquals(0f, page.top, 0.001f)
        assertEquals(875f, page.right, 0.001f)
        assertEquals(1_000f, page.bottom, 0.001f)
    }

    @Test
    fun viewAndNormalizedCoordinatesRoundTripAndRejectMargins() {
        val page = RemoteReviewRect(100f, 50f, 700f, 850f)
        val normalized = RemoteReviewGeometry.viewToNormalized(250f, 650f, page)

        assertNotNull(normalized)
        normalized!!
        assertEquals(0.25f, normalized.x, 0.0001f)
        assertEquals(0.75f, normalized.y, 0.0001f)
        val restored = RemoteReviewGeometry.normalizedToView(normalized, page)
        assertEquals(250f, restored.x, 0.001f)
        assertEquals(650f, restored.y, 0.001f)
        assertNull(RemoteReviewGeometry.viewToNormalized(99f, 400f, page))
    }

    @Test
    fun crossingEraserPathHitsEvenWhenAllEndpointsAreFarAway() {
        val stroke = stroke(
            listOf(
                RemoteNormalizedPoint(0.1f, 0.5f),
                RemoteNormalizedPoint(0.9f, 0.5f),
            )
        )
        val eraser = listOf(
            RemoteNormalizedPoint(0.5f, 0.1f),
            RemoteNormalizedPoint(0.5f, 0.9f),
        )

        assertTrue(RemoteReviewGeometry.intersectsEraser(stroke, eraser, 0.01f, 600, 900))
    }

    @Test
    fun portraitPageUsesCircularPixelMetricForDistantStroke() {
        val stroke = stroke(
            listOf(
                RemoteNormalizedPoint(0.1f, 0.1f),
                RemoteNormalizedPoint(0.2f, 0.1f),
            )
        )
        val eraser = listOf(
            RemoteNormalizedPoint(0.1f, 0.8f),
            RemoteNormalizedPoint(0.2f, 0.8f),
        )

        assertFalse(RemoteReviewGeometry.intersectsEraser(stroke, eraser, 0.05f, 600, 1_200))
    }

    private fun stroke(points: List<RemoteNormalizedPoint>) = RemoteFeedbackStroke(
        id = "teacher-stroke",
        tool = RemoteFeedbackStrokeTool.PEN,
        colorArgb = 0xFFD94747.toInt(),
        widthFraction = 0.004f,
        points = points,
    )
}

