package com.studyink.reader

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.FrameLayout
import com.studyink.construction.core.ConstructionScene
import com.studyink.construction.core.ConstraintType
import com.studyink.construction.core.GeometryCircle
import com.studyink.construction.core.GeometryConstraint
import com.studyink.construction.core.GeometryLineStyle
import com.studyink.construction.core.GeometryPoint
import com.studyink.construction.core.GeometrySegment
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

/** Exercise actual native Android drawing and public geometry hit-testing, not Paint reflection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w800dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ConstructionLineStyleCanvasTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var view: ConstructionCanvasView
    private lateinit var viewport: SharedMemoViewport

    @Before fun setup() {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        viewport = SharedMemoViewport().apply { updateSize(SIZE, SIZE) }
        view = ConstructionCanvasView(activity).apply { sharedViewport = viewport }
        activity.setContentView(FrameLayout(activity).apply { addView(view) })
        view.layout(0, 0, SIZE, SIZE)
    }

    @After fun cleanup() { controller.pause().stop().destroy() }

    @Test fun `solid dashed and dotted give visibly different continuous dash and dot patterns on both shapes`() {
        for (circle in listOf(false, true)) {
            val profiles = GeometryLineStyle.entries.associateWith { style ->
                view.scene = shape(circle, style)
                render { bitmap -> profile(bitmap, circle).map { it.ink } }
            }
            val solid = profiles.getValue(GeometryLineStyle.SOLID)
            val dashed = profiles.getValue(GeometryLineStyle.DASHED)
            val dotted = profiles.getValue(GeometryLineStyle.DOTTED)
            val label = if (circle) "circle" else "segment"
            assertTrue("$label solid has a continuous colored centerline: ${coverage(solid)}", coverage(solid) > .95)
            assertTrue("$label dashed has both strokes and real gaps: ${coverage(dashed)}", coverage(dashed) in .35.. .82)
            assertTrue("$label dotted has both small dots and real gaps: ${coverage(dotted)}", coverage(dotted) in .1.. .7)
            assertTrue("$label dots repeat more frequently than long dashes", transitions(dotted) > transitions(dashed) * 1.4)
            assertNotEquals(dashed, dotted)
        }
    }

    @Test fun `selecting either shape preserves its chosen ink color and adds a separate pale halo`() {
        for (circle in listOf(false, true)) for (style in GeometryLineStyle.entries) {
            val original = shape(circle, style)
            view.scene = original
            val plain = render { bitmap -> bitmapPixels(bitmap) }
            // The blue halo changes the background beneath antialiased red edges. Counting
            // threshold-classified edge pixels incorrectly treats that compositing as recoloring,
            // especially for tiny dots. Compare the strongest foreground samples instead.
            val foreground = plain.indices.filter { isGeometryInk(plain[it]) }
                .sortedBy { colorDistance(plain[it], RED) }.take(20)
            assertEquals("$style must have enough visible foreground samples", 20, foreground.size)
            assertTrue("$style foreground samples must be recognizably red", foreground.all { colorDistance(plain[it], RED) < 160 })
            view.selectedIds = setOf(if (circle) "circle" else "AB")
            val selected = render { bitmap -> bitmapPixels(bitmap) }
            for (index in foreground) {
                assertTrue("$style selected geometry must retain its red foreground at pixel $index", isGeometryInk(selected[index]))
                assertTrue("$style selection must not replace the chosen stroke color",
                    kotlin.math.abs(Color.red(plain[index]) - Color.red(selected[index])) <= 20 &&
                        kotlin.math.abs(Color.green(plain[index]) - Color.green(selected[index])) <= 20 &&
                        kotlin.math.abs(Color.blue(plain[index]) - Color.blue(selected[index])) <= 20)
            }
            val haloPixels = plain.indices.count { index ->
                plain[index] != selected[index] && !isGeometryInk(plain[index]) && !isGeometryInk(selected[index])
            }
            assertTrue("$style selected geometry needs a distinct halo", haloPixels > 100)
            assertEquals("Selection and rendering never alter mathematical data", original, view.scene)
            view.clearSelection()
        }
    }

    @Test fun `all stroke styles leave dimension guide color text hit bounds and solid guide pattern unchanged`() {
        val guideRegion = Rect(100, 130, 700, 175)
        var originalPixels: IntArray? = null
        var originalBounds: android.graphics.RectF? = null
        for (style in GeometryLineStyle.entries) {
            view.scene = ConstructionScene(
                points = listOf(GeometryPoint("A", 2.0, 17.0), GeometryPoint("B", 22.0, 17.0), GeometryPoint("C", 12.0, 7.0)),
                segments = listOf(GeometrySegment("AB", "A", "B", colorArgb = RED, lineStyle = style)),
                circles = listOf(GeometryCircle("circle", "C", 2.0, colorArgb = RED, lineStyle = style)),
                constraints = listOf(GeometryConstraint("length", ConstraintType.LENGTH, listOf("AB"), value = 20.0)),
            )
            val pixels = render { bitmap -> regionPixels(bitmap, guideRegion) }
            val bounds = requireNotNull(view.constraintScreenBounds("length"))
            assertTrue("Length guide stays light blue for $style", pixels.count { closeTo(it, 0xFF96B4D8.toInt()) } > 100)
            assertTrue("Length text stays muted blue for $style", pixels.count { closeTo(it, 0xFF5F82AD.toInt()) } > 10)
            if (originalPixels == null) {
                originalPixels = pixels
                originalBounds = bounds
            } else {
                assertArrayEquals("Geometry dash effects must not leak into dimension guides", originalPixels, pixels)
                assertEquals(originalBounds, bounds)
            }
        }
    }

    @Test fun `a visible dash or dot gap remains selectable as the same complete line or circle`() {
        for (circle in listOf(false, true)) for (style in listOf(GeometryLineStyle.DASHED, GeometryLineStyle.DOTTED)) {
            val original = shape(circle, style)
            view.scene = original
            view.clearSelection()
            val samples = render { bitmap -> profile(bitmap, circle) }
            // Find the middle of a genuinely unpainted run, not a barely antialiased edge.
            val gapIndex = (2 until samples.size - 2).firstOrNull { index ->
                (index - 1..index + 1).all { !samples[it].ink }
            }
            assertNotNull("$style must expose a real gap to test", gapIndex)
            val gap = samples[gapIndex!!].point
            val downTime = SystemClock.uptimeMillis()
            for ((offset, action) in listOf(0L to MotionEvent.ACTION_DOWN, 20L to MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(downTime, downTime + offset, action, gap.x, gap.y, 0)
                try { assertTrue(view.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
            assertEquals("Unpainted $style gaps retain the full geometry hit target", setOf(if (circle) "circle" else "AB"), view.selectedIds)
            assertEquals(original, view.scene)
        }
    }

    private data class Sample(val point: PointF, val ink: Boolean)

    private fun profile(bitmap: Bitmap, circle: Boolean): List<Sample> {
        if (!circle) {
            val start = viewport.worldToView(2.0, 12.0)
            val end = viewport.worldToView(22.0, 12.0)
            return (ceil(start.x + 30).toInt()..(end.x - 30).toInt()).map { x ->
                val point = PointF(x.toFloat(), start.y)
                Sample(point, (-1..1).any { dy -> isGeometryInk(bitmap.getPixel(x, start.y.roundToInt() + dy)) })
            }
        }
        val center = viewport.worldToView(12.0, 12.0)
        val radius = 6f * viewport.pixelsPerCm
        val count = ceil(2 * PI * radius).toInt()
        return (0 until count).map { index ->
            val angle = index * 2 * PI / count
            val point = PointF(center.x + (cos(angle) * radius).toFloat(), center.y + (sin(angle) * radius).toFloat())
            // Radial samples tolerate circle rasterization without bridging tangential dash gaps.
            Sample(point, listOf(-.75, 0.0, .75).any { offset ->
                val x = (center.x + cos(angle) * (radius + offset)).roundToInt()
                val y = (center.y + sin(angle) * (radius + offset)).roundToInt()
                isGeometryInk(bitmap.getPixel(x, y))
            })
        }
    }

    private fun shape(circle: Boolean, style: GeometryLineStyle): ConstructionScene = if (circle) ConstructionScene(
        points = listOf(GeometryPoint("C", 12.0, 12.0)),
        circles = listOf(GeometryCircle("circle", "C", 6.0, colorArgb = RED, lineStyle = style)),
    ) else ConstructionScene(
        points = listOf(GeometryPoint("A", 2.0, 12.0), GeometryPoint("B", 22.0, 12.0)),
        segments = listOf(GeometrySegment("AB", "A", "B", colorArgb = RED, lineStyle = style)),
    )

    private fun coverage(profile: List<Boolean>) = profile.count { it }.toDouble() / profile.size
    private fun transitions(profile: List<Boolean>) = profile.zipWithNext().count { (a, b) -> a != b }
    private fun isGeometryInk(color: Int) = Color.red(color) > Color.green(color) + 50 && Color.red(color) > Color.blue(color) + 40
    private fun colorDistance(actual: Int, expected: Int) =
        kotlin.math.abs(Color.red(actual) - Color.red(expected)) + kotlin.math.abs(Color.green(actual) - Color.green(expected)) +
            kotlin.math.abs(Color.blue(actual) - Color.blue(expected))
    private fun closeTo(actual: Int, expected: Int) = colorDistance(actual, expected) < 30
    private fun bitmapPixels(bitmap: Bitmap) = regionPixels(bitmap, Rect(0, 0, bitmap.width, bitmap.height))
    private fun regionPixels(bitmap: Bitmap, rect: Rect) = IntArray(rect.width() * rect.height()).also {
        bitmap.getPixels(it, 0, rect.width(), rect.left, rect.top, rect.width(), rect.height())
    }
    private inline fun <T> render(check: (Bitmap) -> T): T {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        return try { view.draw(Canvas(bitmap)); check(bitmap) } finally { bitmap.recycle() }
    }

    companion object {
        private const val SIZE = 800
        private const val RED = 0xFFAD2643.toInt()
    }
}
