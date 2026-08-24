package com.studyink.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class StylusMenuGeometryTest {

    @Test
    fun toolExtensionRearmsOnlyWhenThePenReturnsToItsStartingTool() {
        val started = ReaderTool.PEN
        assertTrue(isToolExtensionGestureArmed(started, ReaderTool.PEN))
        assertFalse(isToolExtensionGestureArmed(started, null))
        assertFalse(isToolExtensionGestureArmed(started, ReaderTool.HIGHLIGHTER))
        assertTrue(isToolExtensionGestureArmed(started, ReaderTool.PEN))
    }

    @Test
    fun stylusPointSitsAtSixtySixPercentOfAAlongTheFanMidline() {
        val stylusPoint = Offset(724f, 388f)
        val a = 120f
        val angleDegrees = 230f
        val anchorFraction = 0.66f

        val origin = stylusAnchoredFanOrigin(
            stylusPoint = stylusPoint,
            innerRadiusPx = a,
            middleAngleDegrees = angleDegrees,
            anchorRadiusFraction = anchorFraction,
        )

        val angleRadians = Math.toRadians(angleDegrees.toDouble())
        val reconstructedStylusPoint = Offset(
            x = origin.x + cos(angleRadians).toFloat() * a * anchorFraction,
            y = origin.y + sin(angleRadians).toFloat() * a * anchorFraction,
        )
        assertEquals(stylusPoint.x, reconstructedStylusPoint.x, 0.001f)
        assertEquals(stylusPoint.y, reconstructedStylusPoint.y, 0.001f)
    }

    @Test
    fun menuViewportIsClampedToTheHostWithoutMovingAnAlreadySafePosition() {
        assertEquals(
            Offset(40f, 60f),
            clampRadialMenuTopLeft(
                preferred = Offset(40f, 60f),
                viewportWidthPx = 240f,
                viewportHeightPx = 180f,
                hostWidthPx = 800f,
                hostHeightPx = 600f,
            ),
        )
        assertEquals(
            Offset.Zero,
            clampRadialMenuTopLeft(
                preferred = Offset(-30f, -20f),
                viewportWidthPx = 240f,
                viewportHeightPx = 180f,
                hostWidthPx = 800f,
                hostHeightPx = 600f,
            ),
        )
        assertEquals(
            Offset(560f, 420f),
            clampRadialMenuTopLeft(
                preferred = Offset(900f, 700f),
                viewportWidthPx = 240f,
                viewportHeightPx = 180f,
                hostWidthPx = 800f,
                hostHeightPx = 600f,
            ),
        )
    }

    @Test
    fun menuLargerThanHostPinsToTheHostOrigin() {
        assertEquals(
            Offset.Zero,
            clampRadialMenuTopLeft(
                preferred = Offset(20f, 30f),
                viewportWidthPx = 900f,
                viewportHeightPx = 700f,
                hostWidthPx = 800f,
                hostHeightPx = 600f,
            ),
        )
    }

    @Test
    fun annularSectorOwnsOnlyRadiiAThroughBAndAngles180Through280() {
        val region = testRegion(toolCorridorAnglesDegrees = emptyList())

        assertTrue(region.contains(pointAt(angleDegrees = 180f, radius = A)))
        assertTrue(region.contains(pointAt(angleDegrees = 230f, radius = A)))
        assertTrue(region.contains(pointAt(angleDegrees = 230f, radius = B)))
        assertTrue(region.contains(pointAt(angleDegrees = 280f, radius = B)))

        assertFalse(region.contains(pointAt(angleDegrees = 230f, radius = A - 0.1f)))
        assertFalse(region.contains(pointAt(angleDegrees = 230f, radius = B + 0.1f)))
        assertFalse(region.contains(pointAt(angleDegrees = 179f, radius = (A + B) / 2f)))
        assertFalse(region.contains(pointAt(angleDegrees = 281f, radius = (A + B) / 2f)))
    }

    @Test
    fun toolCorridorExtendsFromAThroughCButNotBeyondItsWidthOrEnd() {
        val corridorAngle = 230f
        val region = testRegion(toolCorridorAnglesDegrees = listOf(corridorAngle))

        assertTrue(region.contains(pointAt(angleDegrees = corridorAngle, radius = A)))
        assertTrue(region.contains(pointAt(angleDegrees = corridorAngle, radius = B)))
        assertTrue(region.contains(pointAt(angleDegrees = corridorAngle, radius = C - 0.01f)))
        assertFalse(region.contains(pointAt(angleDegrees = corridorAngle, radius = C + 0.1f)))

        assertTrue(region.contains(corridorPoint(corridorAngle, radial = C, tangential = HALF_WIDTH - 0.01f)))
        assertFalse(region.contains(corridorPoint(corridorAngle, radial = C, tangential = HALF_WIDTH + 0.1f)))
    }

    @Test
    fun centreHoleAndEverythingOutsideTheRingAndToolCorridorsPassThrough() {
        val region = testRegion(toolCorridorAnglesDegrees = listOf(230f))

        assertFalse(region.contains(ORIGIN_X, ORIGIN_Y))
        assertFalse(region.contains(pointAt(angleDegrees = 230f, radius = A - 1f)))
        assertFalse(region.contains(pointAt(angleDegrees = 200f, radius = B + 1f)))
        assertFalse(region.contains(pointAt(angleDegrees = 300f, radius = (A + B) / 2f)))
        assertFalse(region.contains(Float.NaN, ORIGIN_Y))
    }

    @Test
    fun endpointButtonsRemainInteractiveOutsideTheBareSectorAngle() {
        val region = testRegion(toolCorridorAnglesDegrees = emptyList()).copy(
            itemAnglesDegrees = listOf(180f, 280f),
            itemCenterRadius = (A + B) / 2f,
            itemHitRadius = (B - A) / 2f,
        )

        assertTrue(region.contains(corridorPoint(180f, radial = (A + B) / 2f, tangential = -10f)))
        assertTrue(region.contains(corridorPoint(280f, radial = (A + B) / 2f, tangential = 10f)))
        assertFalse(region.contains(corridorPoint(180f, radial = (A + B) / 2f, tangential = -30f)))
    }

    @Test
    fun commonEightSlotFrameOwnsEveryFinalCenterOnEveryMenuPage() {
        val geometry = compactCommonGeometry()
        val halfStepRadians = Math.toRadians(
            (FAN_SWEEP / (COMMON_RADIAL_MENU_ITEM_COUNT - 1) / 2f).toDouble(),
        )
        val expectedRadius = maxOf(
            RADIAL_MIN_RADIUS,
            (TOOL_BUTTON_SIZE + RADIAL_ITEM_GAP) /
                (2f * sin(halfStepRadians).toFloat()),
        )
        assertEquals(8, COMMON_RADIAL_MENU_ITEM_COUNT)
        assertEquals(expectedRadius, geometry.radius.value, 0.001f)

        val reveal = radialToolRevealGeometry(
            fanRadius = geometry.radius,
            toolButtonSize = TOOL_BUTTON_SIZE.dp,
            protrusionDistance = TOOL_PROTRUSION.dp,
        )
        val penAngles = penMenuAngleSpec()
        val pageAngles = linkedMapOf(
            "main-6" to radialAngles(itemCount = 6),
            "main-7" to radialAngles(itemCount = 7),
            "colors-8" to radialAngles(itemCount = COMMON_RADIAL_MENU_ITEM_COUNT),
            "pen-widths-and-back" to penAngles.widthAnglesDegrees + penAngles.backDegrees,
        )

        pageAngles.forEach { (page, angles) ->
            val region = commonRegion(geometry, reveal, angles)
            assertEquals("$page a", reveal.a.value, region.a, 0.001f)
            assertEquals("$page b", reveal.b.value, region.b, 0.001f)
            assertEquals("$page c", reveal.c.value, region.c, 0.001f)

            angles.forEach { angle ->
                val center = radialItemCenter(
                    originX = geometry.originX,
                    originY = geometry.originY,
                    radius = geometry.radius,
                    angleDegrees = angle,
                )
                val centerPx = center.x.value to center.y.value
                assertTrue(
                    "$page $angle° center must belong to its input region",
                    region.contains(centerPx),
                )

                // Entrance animation may scale/fade the item, but its laid-out centre is final from
                // frame zero: O + R(cos theta, sin theta). There is no progress-dependent translation.
                val radians = Math.toRadians(angle.toDouble())
                val dx = center.x.value - geometry.originX.value
                val dy = center.y.value - geometry.originY.value
                assertEquals(geometry.radius.value * cos(radians).toFloat(), dx, 0.001f)
                assertEquals(geometry.radius.value * sin(radians).toFloat(), dy, 0.001f)
                assertEquals(geometry.radius.value, hypot(dx, dy), 0.001f)
            }
        }
    }

    @Test
    fun opacityArcAndItsThreeDegreeEndToleranceStayInsideTheCommonRegion() {
        val geometry = compactCommonGeometry()
        val reveal = radialToolRevealGeometry(
            fanRadius = geometry.radius,
            toolButtonSize = TOOL_BUTTON_SIZE.dp,
            protrusionDistance = TOOL_PROTRUSION.dp,
        )
        val angles = penMenuAngleSpec()
        assertEquals(248f, angles.opacityStartDegrees, 0f)
        assertEquals(268f, angles.opacityEndDegrees, 0f)
        assertEquals(3f, angles.opacityHitToleranceDegrees, 0f)

        val halfThickness = radialMenuInputHalfThickness(
            opacityTouchTolerance = OPACITY_TOUCH_TOLERANCE.dp,
            toolButtonSize = TOOL_BUTTON_SIZE.dp,
        ).value
        assertEquals(TOOL_BUTTON_SIZE / 2f, halfThickness, 0f)
        val region = commonRegion(
            geometry = geometry,
            reveal = reveal,
            itemAnglesDegrees = angles.widthAnglesDegrees + angles.backDegrees,
        )
        val firstAllowedTenth =
            ((angles.opacityStartDegrees - angles.opacityHitToleranceDegrees) * 10).toInt()
        val lastAllowedTenth =
            ((angles.opacityEndDegrees + angles.opacityHitToleranceDegrees) * 10).toInt()

        for (angleTenth in firstAllowedTenth..lastAllowedTenth) {
            val angle = angleTenth / 10f
            listOf(-halfThickness, 0f, halfThickness).forEach { radialOffset ->
                val point = pointAt(
                    originX = geometry.originX.value,
                    originY = geometry.originY.value,
                    angleDegrees = angle,
                    radius = geometry.radius.value + radialOffset,
                )
                assertTrue(
                    "opacity hit band must own angle=$angle, radialOffset=$radialOffset",
                    region.contains(point),
                )
            }
        }
    }

    private fun testRegion(toolCorridorAnglesDegrees: List<Float>) = StylusMenuInputRegion(
        originX = ORIGIN_X,
        originY = ORIGIN_Y,
        a = A,
        b = B,
        c = C,
        startAngleDegrees = 180f,
        endAngleDegrees = 280f,
        toolCorridorAnglesDegrees = toolCorridorAnglesDegrees,
        toolCorridorHalfWidth = HALF_WIDTH,
    )

    private fun compactCommonGeometry() = commonRadialMenuGeometry(
        toolButtonSize = TOOL_BUTTON_SIZE.dp,
        radialItemGap = RADIAL_ITEM_GAP.dp,
        radialMinRadius = RADIAL_MIN_RADIUS.dp,
        radialEdgeMargin = RADIAL_EDGE_MARGIN.dp,
        radialArtworkHorizontalPadding = RADIAL_ARTWORK_HORIZONTAL_PADDING.dp,
        radialTopMargin = RADIAL_TOP_MARGIN.dp,
    )

    private fun commonRegion(
        geometry: RadialFanGeometry,
        reveal: RadialToolRevealGeometry,
        itemAnglesDegrees: List<Float>,
    ) = StylusMenuInputRegion(
        originX = geometry.originX.value,
        originY = geometry.originY.value,
        a = reveal.a.value,
        b = reveal.b.value,
        c = reveal.c.value,
        startAngleDegrees = FAN_START,
        endAngleDegrees = FAN_START + FAN_SWEEP,
        itemAnglesDegrees = itemAnglesDegrees,
        itemCenterRadius = geometry.radius.value,
        itemHitRadius = TOOL_BUTTON_SIZE / 2f,
    )

    private fun radialAngles(itemCount: Int): List<Float> = List(itemCount) { index ->
        radialItemAngleDegrees(index, itemCount, FAN_SWEEP)
    }

    private fun pointAt(angleDegrees: Float, radius: Float): Pair<Float, Float> {
        return pointAt(ORIGIN_X, ORIGIN_Y, angleDegrees, radius)
    }

    private fun pointAt(
        originX: Float,
        originY: Float,
        angleDegrees: Float,
        radius: Float,
    ): Pair<Float, Float> {
        val angleRadians = angleDegrees * PI.toFloat() / 180f
        return Pair(
            originX + cos(angleRadians) * radius,
            originY + sin(angleRadians) * radius,
        )
    }

    private fun corridorPoint(angleDegrees: Float, radial: Float, tangential: Float): Pair<Float, Float> {
        val angleRadians = angleDegrees * PI.toFloat() / 180f
        val directionX = cos(angleRadians)
        val directionY = sin(angleRadians)
        return Pair(
            ORIGIN_X + directionX * radial - directionY * tangential,
            ORIGIN_Y + directionY * radial + directionX * tangential,
        )
    }

    private fun StylusMenuInputRegion.contains(point: Pair<Float, Float>): Boolean =
        contains(point.first, point.second)

    private companion object {
        const val ORIGIN_X = 400f
        const val ORIGIN_Y = 300f
        const val A = 100f
        const val B = 150f
        const val C = 190f
        const val HALF_WIDTH = 12f
        const val FAN_START = 180f
        const val FAN_SWEEP = 100f
        const val TOOL_BUTTON_SIZE = 48f
        const val TOOL_PROTRUSION = 20f
        const val OPACITY_TOUCH_TOLERANCE = 26f
        const val RADIAL_ITEM_GAP = 0f
        const val RADIAL_EDGE_MARGIN = 12f
        const val RADIAL_ARTWORK_HORIZONTAL_PADDING = 16f
        const val RADIAL_TOP_MARGIN = 58f
        const val RADIAL_MIN_RADIUS = 96f
    }
}
