package com.studyink.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

@Immutable
data class ReaderPaperTextureTokens(
    val washLightAlpha: Float,
    val washShadeAlpha: Float,
    val lightGrainAlpha: Float,
    val darkGrainAlpha: Float,
    val fiberAlpha: Float,
    val areaPerSpeck: Float,
    val areaPerFiber: Float,
    val minSpecks: Int,
    val maxSpecks: Int,
    val minFibers: Int,
    val maxFibers: Int,
    val speckMinRadius: Dp,
    val speckMaxRadius: Dp,
    val fiberMinLength: Dp,
    val fiberMaxLength: Dp,
    val fiberMinWidth: Dp,
    val fiberMaxWidth: Dp,
    val fiberSlope: Float,
)

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
    /** The default writing colour. Without it the palette is a one-way trip away from dark ink. */
    val paletteInk: Color,
    val buttonBackground: Color,
    val buttonForeground: Color,
    val buttonSelectedBackground: Color,
    val actionBackground: Color,
    val actionForeground: Color,
    val statusForeground: Color,
    val statusBackground: Color,
    /** Frame drawn around one attempt's result cluster while it still awaits grading. */
    val markPendingBorder: Color,
    /** Fluorescent frame marking the attempt the reader is currently showing. */
    val markPendingHighlight: Color,
    /** Opacity of the attempts that are not selected, so the current one reads first. */
    val markBundleDimAlpha: Float,
    /** Live monitoring indicator in the teacher's chrome. */
    val liveBadge: Color,
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
    val paperTexture: ReaderPaperTextureTokens,
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
    /**
     * Distance of the real S Pen tip from the fan centre, expressed as a fraction of radius a.
     * The menu centre is moved away from the tip so the arc opens immediately in front of it.
     */
    val stylusAnchorRadiusFraction: Float,
    /** Radial distance from the old button's outer edge (b) to the extracted tip (c). */
    val toolProtrusionDistance: Dp,
    val toolArtworkBottomPaddingFraction: Float,
    val eraserImageWidthScale: Float,
    val eraserImageHeightScale: Float,
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
    val ink = Color(0xFF17233C)
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
        paletteInk = ink,
        buttonBackground = warmIvory,
        buttonForeground = graphite,
        buttonSelectedBackground = warmIvory,
        actionBackground = warmIvory,
        actionForeground = graphite,
        statusForeground = Color(0xFF536078),
        statusBackground = cream.copy(alpha = 0.34f),
        markPendingBorder = Color(0xFF9AA6BF),
        markPendingHighlight = Color(0xFFFFE94A),
        markBundleDimAlpha = 0.5f,
        liveBadge = Color(0xFFE23B3B),
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
        paperTexture = ReaderPaperTextureTokens(
            washLightAlpha = 0.065f,
            washShadeAlpha = 0.034f,
            lightGrainAlpha = 0.075f,
            darkGrainAlpha = 0.036f,
            fiberAlpha = 0.040f,
            areaPerSpeck = 1_050f,
            areaPerFiber = 4_000f,
            minSpecks = 6,
            maxSpecks = 32,
            minFibers = 2,
            maxFibers = 9,
            speckMinRadius = 0.18.dp,
            speckMaxRadius = 0.62.dp,
            fiberMinLength = 3.dp,
            fiberMaxLength = 11.dp,
            fiberMinWidth = 0.24.dp,
            fiberMaxWidth = 0.54.dp,
            fiberSlope = 0.62f,
        ),
        // The derived fan radius is driven by this: radius = (toolButtonSize + gap) / 2sin(step/2).
        // Shrinking the button is the only lever that pulls the whole ring in.
        // This is both the radial a..b thickness and the parent interaction slot. Keep it at least
        // 48dp so the compact parent cannot squeeze the child's minimum S Pen target back to 44dp.
        toolButtonSize = if (compact) 48.dp else 54.dp,
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
        // Wide enough for a short stack of submission frames on a phone-width chrome.
        compactStatusMaxWidth = 112.dp,
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
        stylusAnchorRadiusFraction = 0.66f,
        // c = b + this value. Rest ends exactly at b; hover and persistent selection end at c.
        toolProtrusionDistance = if (compact) 20.dp else 24.dp,
        // The supplied PNGs share a 2:3 canvas with transparent padding below the artwork tip.
        toolArtworkBottomPaddingFraction = 0.2018229f,
        // Keep the eraser visually narrower without shortening its body below the a..c viewport.
        eraserImageWidthScale = 0.82f,
        eraserImageHeightScale = 1f,
        eraserArtworkBottomPaddingFraction = 0.2552083f,
        radialItemGap = if (compact) 0.dp else 2.dp,
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

/**
 * The three radii used to align a radial tool with the slot it replaces.
 *
 * [a] is the slot's inner edge, [b] its outer edge, and [c] the extracted tool-tip endpoint.
 * The reveal viewport begins at [a]. An unselected tool ends at [b], while a hovered or selected
 * tool ends at [c]. Keeping these as radii prevents image padding or percentage-based reveal
 * values from gradually moving the artwork away from the existing 120-degree fan.
 */
@Immutable
data class RadialToolRevealGeometry(
    val a: Dp,
    val b: Dp,
    val c: Dp,
) {
    val restingTipDepth: Dp get() = b - a
    val extractedTipDepth: Dp get() = c - a
    val viewportLength: Dp get() = c - a
}

fun radialToolRevealGeometry(
    fanRadius: Dp,
    toolButtonSize: Dp,
    protrusionDistance: Dp,
): RadialToolRevealGeometry {
    val a = fanRadius - toolButtonSize / 2
    val b = fanRadius + toolButtonSize / 2
    return RadialToolRevealGeometry(
        a = a,
        b = b,
        c = b + protrusionDistance,
    )
}

/**
 * Places the fan centre away from the physical S Pen point so the arc opens immediately in front
 * of the hovering pen instead of treating that point as the centre of an otherwise empty circle.
 */
internal fun stylusAnchoredFanOrigin(
    stylusPoint: Offset,
    innerRadiusPx: Float,
    middleAngleDegrees: Float,
    anchorRadiusFraction: Float,
): Offset {
    val angle = Math.toRadians(middleAngleDegrees.toDouble())
    val distance = innerRadiusPx * anchorRadiusFraction
    return Offset(
        x = stylusPoint.x - cos(angle).toFloat() * distance,
        y = stylusPoint.y - sin(angle).toFloat() * distance,
    )
}

/** Keeps the complete menu viewport on screen; only this edge fallback may move the 0.66a anchor. */
internal fun clampRadialMenuTopLeft(
    preferred: Offset,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    hostWidthPx: Float,
    hostHeightPx: Float,
): Offset = Offset(
    x = preferred.x.coerceIn(0f, (hostWidthPx - viewportWidthPx).coerceAtLeast(0f)),
    y = preferred.y.coerceIn(0f, (hostHeightPx - viewportHeightPx).coerceAtLeast(0f)),
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
    val markHistoryCellWidthDp: Float,
    val markHistoryCellHeightDp: Float,
    val markCurrentCellWidthDp: Float,
    val markCurrentCellHeightDp: Float,
    val markHorizontalGapDp: Float,
    val markVerticalGapDp: Float,
    val markCornerDp: Float,
    val markHistoryCornerDp: Float,
    val markHitPaddingDp: Float,
    val markAlpha: Int,
    val markHistoryAlpha: Int,
    val markPlaceholderAlpha: Int,
    val markHorizontalSnapDp: Float,
    val markVerticalSnapDp: Float,
    val markPageEdgePaddingDp: Float,
    val markBlueArgb: Int,
    val markRedArgb: Int,
    val markGrayArgb: Int,
    val markFocusArgb: Int,
)

/** How strongly a published teacher correction glows behind its own trace. */
const val PUBLISHED_INK_GLOW_WIDTH_SCALE = 2.6f
const val PUBLISHED_INK_GLOW_ALPHA = 52

fun readerCanvasTokens(): ReaderCanvasTokens = ReaderCanvasTokens(
    // Eight outcomes become the approved 4x2 mosaic. Historical cells are thin and quiet; the
    // active attempt fills its slot so its result is readable without breaking the bundle grid.
    markHistoryCellWidthDp = 4f,
    markHistoryCellHeightDp = 8f,
    markCurrentCellWidthDp = 8f,
    markCurrentCellHeightDp = 12f,
    markHorizontalGapDp = 2f,
    markVerticalGapDp = 2f,
    markCornerDp = 3f,
    markHistoryCornerDp = 1.5f,
    markHitPaddingDp = 5f,
    markAlpha = 210,
    markHistoryAlpha = 72,
    markPlaceholderAlpha = 104,
    markHorizontalSnapDp = 4f,
    markVerticalSnapDp = 8f,
    markPageEdgePaddingDp = 3f,
    markBlueArgb = 0xFF2C6CE8.toInt(),
    markRedArgb = 0xFFE23B3B.toInt(),
    markGrayArgb = 0xFFAAAEB8.toInt(),
    markFocusArgb = 0xFF1F2A44.toInt(),
)
