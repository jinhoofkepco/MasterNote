package com.studyink.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Reader chrome values intentionally live in a composable function body. Android Studio Live Edit
 * can replace the body while the Reader is running, unlike values initialized on a singleton.
 */
@Immutable
data class ReaderChromeTokens(
    val buttonBackground: Color,
    val buttonForeground: Color,
    val buttonSelectedBackground: Color,
    val buttonSelectedForeground: Color,
    val actionBackground: Color,
    val actionForeground: Color,
    val outline: Color,
    val disabledBackground: Color,
    val cornerRadius: Dp,
    val navigationButtonSize: Dp,
    val compactMenuButtonSize: Dp,
    val actionButtonHeight: Dp,
    val minimumTouchSize: Dp,
    val chromeHorizontalPadding: Dp,
    val chromeVerticalPadding: Dp,
    val navigationExclusion: Dp,
    val itemGap: Dp,
    val contentHorizontalPadding: Dp,
    val buttonBorderWidth: Dp,
    val contextIconSize: Dp,
    val contextContentGap: Dp,
    val popupVerticalOffset: Dp,
    val popupContentPadding: Dp,
    val popupItemGap: Dp,
    val compactStatusPadding: Dp,
    val expandedStatusPadding: Dp,
    val compactContextMaxWidth: Dp,
    val expandedContextMaxWidth: Dp,
    val compactStatusMaxWidth: Dp,
    val expandedStatusMaxWidth: Dp,
    val defaultAlpha: Float,
    val hoveredAlpha: Float,
    val disabledAlpha: Float,
    val restingElevation: Dp,
    val hoveredElevation: Dp,
    val hoverScale: Float,
    val pressedScaleY: Float,
    val springDampingRatio: Float,
    val springStiffness: Float,
    val fadeDurationMillis: Int,
    val compactWidthBreakpoint: Dp,
)

@Composable
fun readerChromeTokens(role: ReaderRole): ReaderChromeTokens {
    val teacher = role != ReaderRole.STUDENT
    return ReaderChromeTokens(
        buttonBackground = if (teacher) Color(0xFFF7F4FF) else Color(0xFFFFF9F0),
        buttonForeground = if (teacher) Color(0xFF30264A) else Color(0xFF3E352A),
        buttonSelectedBackground = if (teacher) Color(0xFF6C52B8) else Color(0xFFEF8F62),
        buttonSelectedForeground = Color.White,
        actionBackground = if (teacher) Color(0xFF6C52B8) else Color(0xFFEC774E),
        actionForeground = Color.White,
        outline = if (teacher) Color(0xFF7967AA) else Color(0xFFB87759),
        disabledBackground = Color(0xFFE2DFE7),
        cornerRadius = 24.dp,
        navigationButtonSize = 48.dp,
        compactMenuButtonSize = 44.dp,
        actionButtonHeight = 46.dp,
        minimumTouchSize = 44.dp,
        chromeHorizontalPadding = 10.dp,
        chromeVerticalPadding = 8.dp,
        navigationExclusion = 58.dp,
        itemGap = 8.dp,
        contentHorizontalPadding = 14.dp,
        buttonBorderWidth = 1.5.dp,
        contextIconSize = 20.dp,
        contextContentGap = 6.dp,
        popupVerticalOffset = 54.dp,
        popupContentPadding = 6.dp,
        popupItemGap = 4.dp,
        compactStatusPadding = 4.dp,
        expandedStatusPadding = 10.dp,
        compactContextMaxWidth = 82.dp,
        expandedContextMaxWidth = 360.dp,
        compactStatusMaxWidth = 88.dp,
        expandedStatusMaxWidth = 180.dp,
        defaultAlpha = 0.58f,
        hoveredAlpha = 1f,
        disabledAlpha = 0.28f,
        restingElevation = 3.dp,
        hoveredElevation = 9.dp,
        hoverScale = 1.065f,
        pressedScaleY = 0.88f,
        springDampingRatio = 0.64f,
        springStiffness = 520f,
        fadeDurationMillis = 120,
        compactWidthBreakpoint = 700.dp,
    )
}

/** Canvas values are centralized with the Compose tokens, but require an APK reinstall to change. */
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
