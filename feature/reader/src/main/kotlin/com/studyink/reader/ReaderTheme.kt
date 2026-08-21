package com.studyink.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * All reader chrome tuning values live in this composable path so Android Studio Live Edit can
 * replace them while the Reader is running. The width split is intentionally based on the active
 * window, not a device model, so split screen and future Galaxy devices follow the same hierarchy.
 */
@Immutable
data class ReaderChromeTokens(
    val compact: Boolean,
    val paletteBlue: Color,
    val paletteGreen: Color,
    val paletteYellow: Color,
    val paletteOrange: Color,
    val palettePink: Color,
    val paletteCream: Color,
    val buttonBackground: Color,
    val buttonForeground: Color,
    val buttonSelectedBackground: Color,
    val actionBackground: Color,
    val actionForeground: Color,
    val statusForeground: Color,
    val statusBackground: Color,
    val outline: Color,
    val disabledBackground: Color,
    val paperSurface: Color,
    val paperHoverSurface: Color,
    val paperStroke: Color,
    val paperHighlight: Color,
    val paperShade: Color,
    val paperShadow: Color,
    val paperHoverRim: Color,
    val paperSelectedRim: Color,
    val paperStrokeWidth: Dp,
    val toolButtonSize: Dp,
    val navigationButtonSize: Dp,
    val generalButtonSize: Dp,
    val actionButtonSize: Dp,
    val menuOpenButtonSize: Dp,
    val minimumTouchSize: Dp,
    val iconScale: Float,
    val primaryMinWidth: Dp,
    val primaryHorizontalPadding: Dp,
    val primaryContentGap: Dp,
    val statusHorizontalPadding: Dp,
    val cornerRadius: Dp,
    val chromeHorizontalPadding: Dp,
    val chromeVerticalPadding: Dp,
    val navigationExclusion: Dp,
    val itemGap: Dp,
    val compactContextMaxWidth: Dp,
    val expandedContextMaxWidth: Dp,
    val compactStatusMaxWidth: Dp,
    val expandedStatusMaxWidth: Dp,
    val popupVerticalOffset: Dp,
    val popupContentPadding: Dp,
    val popupItemGap: Dp,
    val defaultAlpha: Float,
    val menuRestingAlpha: Float,
    val hoveredAlpha: Float,
    val disabledAlpha: Float,
    val restingElevation: Dp,
    val hoveredElevation: Dp,
    val hoverScale: Float,
    val pressedScaleY: Float,
    val springDampingRatio: Float,
    val springStiffness: Float,
    val fadeDurationMillis: Int,
    val toolImageWidth: Dp,
    val toolImageHeight: Dp,
    val toolRevealTrackLength: Dp,
    val toolRestingRevealFraction: Float,
    val toolHoveredRevealFraction: Float,
    val toolSelectedRevealFraction: Float,
    val toolRevealOvershootFraction: Float,
    val toolArtworkContentFraction: Float,
    val toolArtworkBottomPaddingFraction: Float,
    val eraserImageScale: Float,
    val eraserArtworkContentFraction: Float,
    val eraserArtworkBottomPaddingFraction: Float,
    val radialItemGap: Dp,
    val radialEdgeMargin: Dp,
    val radialArtworkHorizontalPadding: Dp,
    val radialTopMargin: Dp,
    val radialMinRadius: Dp,
    val radialEntranceStartScale: Float,
    val strokePreviewSize: Dp,
    val strokeOpacityMin: Float,
    val colorChoiceRestingAlpha: Float,
    val opacityTouchTolerance: Dp,
    val opacityStrokeScale: Float,
    val opacityStrokeMin: Dp,
    val opacityStrokeMax: Dp,
    val opacityThumbOuterRadius: Dp,
    val opacityThumbInnerRadius: Dp,
    val compactWidthBreakpoint: Dp,
)

@Composable
fun readerChromeTokens(role: ReaderRole): ReaderChromeTokens {
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val compact = windowWidth < 600.dp
    val teacher = role != ReaderRole.STUDENT

    val blue = Color(0xFF2C6CE8)
    val green = Color(0xFF13AA85)
    val yellow = Color(0xFFFFC928)
    val orange = Color(0xFFFF8A24)
    val pink = Color(0xFFE65398)
    val cream = Color(0xFFFFFBF4)
    // Keep the Reader controls on the same approved paper palette as the library screen.
    val warmIvory = Color(0xFFFFFCF5)
    val graphite = Color(0xFF403D36)

    return ReaderChromeTokens(
        compact = compact,
        paletteBlue = blue,
        paletteGreen = green,
        paletteYellow = yellow,
        paletteOrange = orange,
        palettePink = pink,
        paletteCream = cream,
        buttonBackground = warmIvory,
        buttonForeground = graphite,
        buttonSelectedBackground = warmIvory,
        actionBackground = warmIvory,
        actionForeground = graphite,
        statusForeground = Color(0xFF536078),
        statusBackground = cream.copy(alpha = 0.34f),
        outline = if (teacher) Color(0xFF5E83BC) else Color(0xFFD9865B),
        disabledBackground = Color(0xFFEDE5D8),
        paperSurface = warmIvory,
        paperHoverSurface = Color(0xFFFFFFFB),
        paperStroke = Color(0xFFD9D1C3),
        paperHighlight = Color.White.copy(alpha = 0.72f),
        paperShade = Color(0xFFD9D1C3).copy(alpha = 0.18f),
        paperShadow = Color(0x283E3528),
        paperHoverRim = Color(0xFFF2C94C),
        paperSelectedRim = Color(0xFFF2C94C),
        paperStrokeWidth = 1.dp,
        toolButtonSize = if (compact) 48.dp else 60.dp,
        navigationButtonSize = if (compact) 44.dp else 52.dp,
        generalButtonSize = if (compact) 38.dp else 44.dp,
        actionButtonSize = if (compact) 34.dp else 40.dp,
        menuOpenButtonSize = if (compact) 30.dp else 34.dp,
        minimumTouchSize = 48.dp,
        iconScale = 0.55f,
        primaryMinWidth = if (compact) 66.dp else 78.dp,
        primaryHorizontalPadding = if (compact) 9.dp else 12.dp,
        primaryContentGap = if (compact) 4.dp else 6.dp,
        statusHorizontalPadding = if (compact) 4.dp else 10.dp,
        cornerRadius = if (compact) 19.dp else 22.dp,
        chromeHorizontalPadding = if (compact) 8.dp else 10.dp,
        chromeVerticalPadding = if (compact) 4.dp else 8.dp,
        navigationExclusion = if (compact) 52.dp else 62.dp,
        itemGap = if (compact) 6.dp else 8.dp,
        compactContextMaxWidth = 92.dp,
        expandedContextMaxWidth = 420.dp,
        compactStatusMaxWidth = 88.dp,
        expandedStatusMaxWidth = 190.dp,
        popupVerticalOffset = if (compact) 50.dp else 58.dp,
        popupContentPadding = if (compact) 4.dp else 7.dp,
        popupItemGap = if (compact) 3.dp else 5.dp,
        defaultAlpha = 0.55f,
        menuRestingAlpha = 1f,
        hoveredAlpha = 1f,
        disabledAlpha = 0.28f,
        restingElevation = 2.dp,
        hoveredElevation = if (compact) 7.dp else 10.dp,
        hoverScale = 1.065f,
        pressedScaleY = 0.88f,
        springDampingRatio = 0.58f,
        springStiffness = 480f,
        fadeDurationMillis = 120,
        // The source PNGs are 2:3 canvases with generous transparent margins.  The canvas must
        // therefore be substantially longer than the old circular button for 15% of the actual
        // artwork to read as a tool tip instead of a few indistinct pixels.
        toolImageWidth = if (compact) 100.dp else 120.dp,
        toolImageHeight = if (compact) 150.dp else 180.dp,
        // The reveal seam is the former circle's inner edge. 25% of this track is exactly one old
        // circle diameter, so the resting tip lands on its outer edge; hover travels beyond it.
        toolRevealTrackLength = if (compact) 192.dp else 240.dp,
        toolRestingRevealFraction = 0.25f,
        toolHoveredRevealFraction = 0.35f,
        // A tool pressed during the current menu session stays extracted as its selection mark.
        toolSelectedRevealFraction = 0.35f,
        toolRevealOvershootFraction = 0.03f,
        // The supplied PNGs share a 2:3 canvas with transparent padding around the artwork.
        // These values describe that canvas so the visible percentage applies to the tool itself.
        toolArtworkContentFraction = 0.5963542f,
        toolArtworkBottomPaddingFraction = 0.2018229f,
        eraserImageScale = 0.82f,
        eraserArtworkContentFraction = 0.4908854f,
        eraserArtworkBottomPaddingFraction = 0.2552083f,
        radialItemGap = if (compact) 2.dp else 4.dp,
        radialEdgeMargin = if (compact) 12.dp else 16.dp,
        radialArtworkHorizontalPadding = if (compact) 16.dp else 20.dp,
        radialTopMargin = if (compact) 58.dp else 70.dp,
        radialMinRadius = if (compact) 96.dp else 124.dp,
        radialEntranceStartScale = 0.52f,
        strokePreviewSize = if (compact) 29.dp else 36.dp,
        strokeOpacityMin = 0.15f,
        colorChoiceRestingAlpha = 0.82f,
        opacityTouchTolerance = if (compact) 26.dp else 32.dp,
        opacityStrokeScale = 4.6f,
        opacityStrokeMin = if (compact) 12.dp else 15.dp,
        opacityStrokeMax = if (compact) 30.dp else 36.dp,
        opacityThumbOuterRadius = if (compact) 14.dp else 17.dp,
        opacityThumbInnerRadius = if (compact) 10.dp else 12.dp,
        compactWidthBreakpoint = 600.dp,
    )
}

/**
 * Where every fan item sits, and how big the popup that holds them has to be.
 *
 * The radius is derived rather than tuned: neighbouring items are [ReaderChromeTokens.radialItemGap]
 * apart along the arc. [boundsSweepAngleDegrees] can describe a larger page sector when only part
 * of that sector contains discrete items (the pen-width choices plus their opacity slider).
 */
@Immutable
data class RadialFanGeometry(
    val radius: Dp,
    val originX: Dp,
    val originY: Dp,
    val menuWidth: Dp,
    val menuHeight: Dp,
)

fun radialFanGeometry(
    tokens: ReaderChromeTokens,
    itemCount: Int,
    sweepAngleDegrees: Float,
    boundsSweepAngleDegrees: Float = sweepAngleDegrees,
): RadialFanGeometry {
    val item = tokens.toolButtonSize
    val radius = if (itemCount < 2) {
        tokens.radialMinRadius
    } else {
        val stepRadians = Math.toRadians((sweepAngleDegrees / (itemCount - 1)).toDouble())
        // chord between neighbours = 2 * r * sin(step / 2), and it must clear one item plus the gap
        val needed = (item + tokens.radialItemGap) / (2f * sin(stepRadians / 2.0).toFloat())
        maxOf(tokens.radialMinRadius, needed)
    }
    val horizontalPadding = item / 2 + tokens.radialEdgeMargin +
        tokens.radialArtworkHorizontalPadding
    // Every reader fan starts at 180 degrees. From there through 360 degrees cosine increases
    // monotonically, so the end angle gives the sector's rightmost radial extent.
    val rightExtentFactor = cos(
        Math.toRadians((180f + boundsSweepAngleDegrees).toDouble()),
    ).toFloat().coerceAtLeast(0f)
    val originY = tokens.radialTopMargin + radius + item / 2
    return RadialFanGeometry(
        radius = radius,
        originX = radius + horizontalPadding,
        originY = originY,
        menuWidth = horizontalPadding * 2 + radius * (1f + rightExtentFactor),
        menuHeight = originY + item / 2 + tokens.radialEdgeMargin,
    )
}

/** Canvas values are centralized here but still require an APK reinstall to change. */
@Immutable
data class ReaderCanvasTokens(
    val markCellDp: Float,
    val markGapDp: Float,
    val markCornerDp: Float,
    val markHitPaddingDp: Float,
    val markAlpha: Int,
    val markBlueArgb: Int,
    val markRedArgb: Int,
    val markGrayArgb: Int,
    val markFocusArgb: Int,
    val markBarTopDp: Float,
    val markBarRightInsetDp: Float,
    val markBarWidthDp: Float,
)

fun readerCanvasTokens(): ReaderCanvasTokens = ReaderCanvasTokens(
    markCellDp = 22f,
    markGapDp = 4f,
    markCornerDp = 4f,
    markHitPaddingDp = 4f,
    markAlpha = 176,
    markBlueArgb = 0xFF2C6CE8.toInt(),
    markRedArgb = 0xFFE23B3B.toInt(),
    markGrayArgb = 0xFFAAAEB8.toInt(),
    markFocusArgb = 0xFF1F2A44.toInt(),
    markBarTopDp = 84f,
    markBarRightInsetDp = 5f,
    markBarWidthDp = 12f,
)
