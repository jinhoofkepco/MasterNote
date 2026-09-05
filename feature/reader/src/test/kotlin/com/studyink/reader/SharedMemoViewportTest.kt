package com.studyink.reader

import android.graphics.PointF
import android.graphics.RectF
import com.studyink.core.model.PagePoint
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedMemoViewportTest {
    @Test fun `canonical paper and mathematical world share one uniform transform`() {
        val viewport = SharedMemoViewport().apply { updateSize(900, 1200) }
        assertEquals(30f, viewport.pixelsPerCm, .001f)
        same(viewport.worldToView(-3.0, 24.0), PointF(0f, 0f))
        same(viewport.worldToView(27.0, -42.0), PointF(900f, 1980f))
        assertAligned(viewport)
        val world = viewport.viewToWorld(180f, 240f)
        same(world, PointF(3f, 16f))
        assertEquals(viewport.canonicalWidthToView(0, 200f),
            viewport.worldToView(6.0, 0.0).x - viewport.worldToView(0.0, 0.0).x, .001f)
        assertEquals(200f, viewport.viewWidthToCanonical(0, viewport.canonicalWidthToView(0, 200f)), .001f)
    }

    @Test fun `ink drawn on a circle remains attached through pinch pan and resize`() {
        val viewport = SharedMemoViewport().apply { updateSize(900, 1200) }
        val circleTop = viewport.worldToView(12.0, 14.0)
        val handwrittenPoint = viewport.viewToCanonical(circleTop.x, circleTop.y)!!.point
        viewport.zoom(2f, 450f, 600f)
        viewport.pan(-80f, -100f)
        same(viewport.worldToView(12.0, 14.0), viewport.canonicalToView(0, handwrittenPoint)!!)
        val previousCenter = viewport.viewToWorld(450f, 600f)
        viewport.updateSize(1200, 900)
        same(previousCenter, viewport.viewToWorld(600f, 450f))
        same(viewport.worldToView(12.0, 14.0), viewport.canonicalToView(0, handwrittenPoint)!!)
        assertAligned(viewport)
    }

    @Test fun `reopening on another aspect or device never remaps saved points`() {
        val original = SharedMemoViewport().apply { updateSize(900, 1200); zoom(3f, 450f, 600f) }
        val canonical = PagePoint(380f, 630f)
        val savedWorld = PointF(-3f + 380f * .03f, 24f - 630f * .03f)
        val reopened = SharedMemoViewport().apply { updateSize(420, 800) }
        same(original.worldToView(savedWorld.x.toDouble(), savedWorld.y.toDouble()), original.canonicalToView(0, canonical)!!)
        same(reopened.worldToView(savedWorld.x.toDouble(), savedWorld.y.toDouble()), reopened.canonicalToView(0, canonical)!!)
        assertEquals(0f, reopened.paperBounds.top, .001f)
        assertEquals(420f, reopened.paperBounds.width(), .001f)
    }

    @Test fun `zoom out reveals full memo while pan cannot lose the paper`() {
        val viewport = SharedMemoViewport().apply { updateSize(900, 1200); zoom(.001f, 450f, 600f) }
        assertEquals(1200f, viewport.paperBounds.height(), .001f)
        assertEquals((900f - viewport.paperBounds.width()) / 2f, viewport.paperBounds.left, .001f)
        viewport.pan(10000f, -10000f)
        assertEquals(0f, viewport.paperBounds.top, .001f)
        viewport.zoom(10000f, 450f, 600f)
        assertEquals(240f, viewport.pixelsPerCm, .001f)
        viewport.pan(10000f, 10000f)
        assertEquals(0f, viewport.paperBounds.left, .001f)
        assertEquals(0f, viewport.paperBounds.top, .001f)
        viewport.reset()
        assertEquals(900f, viewport.paperBounds.width(), .001f)
        assertEquals(0f, viewport.paperBounds.top, .001f)
    }

    @Test fun `margins reject new ink and invalid numeric camera input is ignored`() {
        val viewport = SharedMemoViewport()
        assertNull(viewport.activePageBounds())
        assertNull(viewport.viewToCanonical(0f, 0f))
        viewport.updateSize(900, 1200)
        assertNull(viewport.canonicalToView(1, PagePoint(0f, 0f)))
        viewport.zoom(.001f, 450f, 600f)
        assertNull(viewport.viewToCanonical(0f, 500f))
        assertNull(viewport.viewToCanonical(Float.NaN, 500f))
        val before = viewport.paperBounds
        viewport.zoom(Float.NaN, 450f, 600f)
        viewport.pan(Float.POSITIVE_INFINITY, 0f)
        assertEquals(before, viewport.paperBounds)
        val bottom = viewport.viewToCanonical(viewport.paperBounds.right, viewport.paperBounds.bottom)!!
        assertEquals(1000f, bottom.point.x, .001f)
        assertEquals(2200f, bottom.point.y, .001f)
    }

    @Test fun `legacy geometry outside the finite memo remains reachable without remapping`() {
        val viewport = SharedMemoViewport().apply { updateSize(900, 600) }
        val original = viewport.paperBounds
        viewport.geometryWorldBounds = RectF(39f, 4f, 41f, 6f)
        assertEquals("Content updates never move the camera", original, viewport.paperBounds)
        viewport.pan(-10000f, 0f)
        val legacyPoint = viewport.worldToView(40.0, 5.0)
        assertTrue(legacyPoint.x in 0f..900f)
        viewport.fitContent()
        val fitted = viewport.worldToView(40.0, 5.0)
        assertTrue(fitted.x in 24f..876f)
        assertTrue(fitted.y in 24f..576f)
        assertNull("Ink cannot be created outside its existing normalized paper", viewport.viewToCanonical(fitted.x, fitted.y))
        assertAligned(viewport)
    }

    @Test fun `fit includes both actual ink and geometry with one camera and defensive bounds copies`() {
        val viewport = SharedMemoViewport().apply { updateSize(900, 600) }
        val geometry = RectF(0f, 0f, 10f, 10f)
        viewport.geometryWorldBounds = geometry
        viewport.inkWorldBounds = RectF(15f, -8f, 20f, 2f)
        geometry.offset(100f, 100f)
        viewport.geometryWorldBounds!!.offset(100f, 100f)
        viewport.fitContent()
        listOf(PointF(0f, 0f), PointF(10f, 10f), PointF(15f, -8f), PointF(20f, 2f)).forEach {
            val mapped = viewport.worldToView(it.x.toDouble(), it.y.toDouble())
            assertTrue("x=${mapped.x}", mapped.x in 23.99f..876.01f)
            assertTrue("y=${mapped.y}", mapped.y in 23.99f..576.01f)
        }
        assertAligned(viewport)
        viewport.reset()
        assertEquals(900f, viewport.paperBounds.width(), .001f)
        assertEquals(0f, viewport.paperBounds.top, .001f)
    }

    private fun assertAligned(viewport: SharedMemoViewport) {
        listOf(PagePoint(0f, 0f), PagePoint(1000f, 2200f), PagePoint(460f, 620f)).forEach {
            same(viewport.worldToView(-3.0 + it.x * .03, 24.0 - it.y * .03),
                viewport.canonicalToView(0, it)!!)
        }
    }

    private fun same(a: PointF, b: PointF) {
        assertEquals(a.x, b.x, .002f)
        assertEquals(a.y, b.y, .002f)
    }
}
