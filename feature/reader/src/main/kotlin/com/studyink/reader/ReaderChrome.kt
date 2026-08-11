package com.studyink.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private val InkBlack = Color(0xFF17233C)
private val InkRed = Color(0xFFE23B3B)
private val InkBlue = Color(0xFF3568E8)
private val InkGreen = Color(0xFF2F9B67)
private val InkYellow = Color(0xFFF1B92B)
private const val CompactFanRadius = 108
private const val FanOriginX = 135
private const val FanOriginY = 190

private enum class RadialMenuPage { MAIN, COLORS, PEN }

@Composable
fun TopReaderBar(
    state: ReaderUiState,
    onOpenPdf: () -> Unit,
) {
    MaterialTheme {
        Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = state.documentLabel,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Text(
                    text = if (state.busy) "처리 중" else "저장됨",
                    color = if (state.busy) Color(0xFFE57700) else Color(0xFF16834A),
                    style = MaterialTheme.typography.labelSmall,
                )
                TextButton(onClick = onOpenPdf) { Text("PDF 열기") }
            }
        }
    }
}

@Composable
fun StylusToolMenu(
    expanded: Boolean,
    state: ReaderUiState,
    selectedTool: ReaderTool,
    selectedColorArgb: Int,
    selectedWidthDp: Float,
    selectedOpacity: Float,
    currentPage: Int,
    pageCount: Int,
    onSelectTool: (ReaderTool) -> Unit,
    onSelectColor: (Int) -> Unit,
    onSelectWidth: (Float) -> Unit,
    onSelectOpacity: (Float) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onResetZoom: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    var menuPage by remember { mutableStateOf(RadialMenuPage.MAIN) }
    LaunchedEffect(expanded) {
        if (expanded) menuPage = RadialMenuPage.MAIN
    }
    if (!expanded) return

    MaterialTheme {
        Popup(
            alignment = Alignment.BottomCenter,
            offset = IntOffset.Zero,
            onDismissRequest = onDismissRequest,
            properties = PopupProperties(
                focusable = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = true,
                clippingEnabled = true,
            ),
        ) {
            Box(modifier = Modifier.size(width = 270.dp, height = 240.dp)) {
                when (menuPage) {
                    RadialMenuPage.MAIN -> MainRadialMenu(
                        state = state,
                        selectedTool = selectedTool,
                        selectedColorArgb = selectedColorArgb,
                        currentPage = currentPage,
                        pageCount = pageCount,
                        onPenClick = {
                            if (selectedTool == ReaderTool.PEN) {
                                menuPage = RadialMenuPage.PEN
                            } else {
                                onSelectTool(ReaderTool.PEN)
                            }
                        },
                        onOpenColors = { menuPage = RadialMenuPage.COLORS },
                        onSelectTool = onSelectTool,
                        onPreviousPage = onPreviousPage,
                        onNextPage = onNextPage,
                        onResetZoom = onResetZoom,
                        onUndo = onUndo,
                        onRedo = onRedo,
                    )

                    RadialMenuPage.COLORS -> ColorRadialMenu(
                        selectedColorArgb = selectedColorArgb,
                        onSelectColor = onSelectColor,
                        onBack = { menuPage = RadialMenuPage.MAIN },
                    )

                    RadialMenuPage.PEN -> PenRadialMenu(
                        selectedColorArgb = selectedColorArgb,
                        selectedWidthDp = selectedWidthDp,
                        selectedOpacity = selectedOpacity,
                        onSelectWidth = onSelectWidth,
                        onSelectOpacity = onSelectOpacity,
                    )
                }
            }
        }
    }
}

@Composable
private fun MainRadialMenu(
    state: ReaderUiState,
    selectedTool: ReaderTool,
    selectedColorArgb: Int,
    currentPage: Int,
    pageCount: Int,
    onPenClick: () -> Unit,
    onOpenColors: () -> Unit,
    onSelectTool: (ReaderTool) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onResetZoom: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    RadialFan(
        itemCount = 7,
        originX = FanOriginX,
        originY = FanOriginY,
        radius = CompactFanRadius,
        startAngleDegrees = 180f,
        sweepAngleDegrees = 180f,
        animationKey = "main-tools",
    ) { index ->
        when (index) {
            0 -> RadialActionButton(
                icon = Icons.AutoMirrored.Rounded.Undo,
                label = "되돌리기",
                enabled = state.snapshot.undoStack.isNotEmpty() && !state.busy,
                size = 44,
            ) { onUndo() }
            1 -> RadialActionButton(
                icon = Icons.AutoMirrored.Rounded.Backspace,
                label = "지우개",
                selected = selectedTool == ReaderTool.PARTIAL_ERASER,
                size = 44,
            ) { onSelectTool(ReaderTool.PARTIAL_ERASER) }
            2 -> RadialActionButton(
                icon = Icons.Rounded.Edit,
                label = "펜",
                selected = selectedTool == ReaderTool.PEN,
                size = 44,
            ) { onPenClick() }
            3 -> RadialActionButton(
                icon = Icons.Rounded.Brush,
                label = "형광펜",
                selected = selectedTool == ReaderTool.HIGHLIGHTER,
                size = 44,
            ) { onSelectTool(ReaderTool.HIGHLIGHTER) }
            4 -> PaletteButton(
                selectedColorArgb = selectedColorArgb,
                onClick = onOpenColors,
            )
            5 -> RadialActionButton(
                icon = Icons.Rounded.FitScreen,
                label = "확대 초기화",
                size = 44,
            ) { onResetZoom() }
            else -> RadialActionButton(
                icon = Icons.AutoMirrored.Rounded.Redo,
                label = "다시 실행",
                enabled = state.snapshot.redoStack.isNotEmpty() && !state.busy,
                size = 44,
            ) { onRedo() }
        }
    }
    PageControl(
        currentPage = currentPage,
        pageCount = pageCount,
        onPreviousPage = onPreviousPage,
        onNextPage = onNextPage,
        modifier = Modifier.offset(x = 67.dp, y = 202.dp),
    )
}

@Composable
private fun ColorRadialMenu(
    selectedColorArgb: Int,
    onSelectColor: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val colors = listOf(
        Triple(InkBlack, "검정", InkBlack.toArgb()),
        Triple(InkBlue, "파랑", InkBlue.toArgb()),
        Triple(InkGreen, "초록", InkGreen.toArgb()),
        Triple(InkYellow, "노랑", InkYellow.toArgb()),
        Triple(InkRed, "빨강", InkRed.toArgb()),
    )
    RadialFan(
        itemCount = colors.size + 1,
        originX = FanOriginX,
        originY = FanOriginY,
        radius = CompactFanRadius,
        startAngleDegrees = 180f,
        sweepAngleDegrees = 180f,
        animationKey = "colors",
    ) { index ->
        if (index < colors.size) {
            val (color, label) = colors[index]
            ColorChoice(color, selectedColorArgb, label, onSelect = onSelectColor)
        } else {
            PaletteButton(selectedColorArgb = selectedColorArgb, onClick = onBack)
        }
    }
}

@Composable
private fun PenRadialMenu(
    selectedColorArgb: Int,
    selectedWidthDp: Float,
    selectedOpacity: Float,
    onSelectWidth: (Float) -> Unit,
    onSelectOpacity: (Float) -> Unit,
) {
    val widths = listOf(6.4f, 4.8f, 3.2f, 2.4f, 1.6f)
    CurvedOpacitySlider(
        color = Color(selectedColorArgb),
        widthDp = selectedWidthDp,
        opacity = selectedOpacity,
        centerX = FanOriginX,
        centerY = FanOriginY,
        radius = CompactFanRadius,
        startAngle = 294f,
        sweepAngle = 66f,
        onOpacityChange = onSelectOpacity,
        modifier = Modifier.fillMaxSize(),
    )
    RadialFan(
        itemCount = widths.size,
        originX = FanOriginX,
        originY = FanOriginY,
        radius = CompactFanRadius,
        startAngleDegrees = 180f,
        sweepAngleDegrees = 100f,
        itemSize = 44,
        animationKey = "pen-widths",
    ) { index ->
        StrokeWidthChoice(
            widthDp = widths[index],
            selectedWidthDp = selectedWidthDp,
            onSelect = onSelectWidth,
        )
    }
}

/**
 * Polar-coordinate fan placement and spring motion adapted from
 * skydoves/compose-animations AnimationExample14 (Apache License 2.0).
 * https://github.com/skydoves/compose-animations
 */
@Composable
private fun RadialFan(
    itemCount: Int,
    originX: Int,
    originY: Int,
    radius: Int,
    startAngleDegrees: Float,
    sweepAngleDegrees: Float,
    itemSize: Int = 50,
    animationKey: Any,
    content: @Composable (Int) -> Unit,
) {
    val progress = remember(animationKey, itemCount) { Animatable(0f) }
    LaunchedEffect(animationKey, itemCount) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        )
    }

    val density = LocalDensity.current
    repeat(itemCount) { index ->
        val angleDegrees = if (itemCount == 1) {
            startAngleDegrees
        } else {
            startAngleDegrees + (index / (itemCount - 1f)) * sweepAngleDegrees
        }
        val angleRadians = Math.toRadians(angleDegrees.toDouble())
        val deltaXDp = (radius * cos(angleRadians)).toFloat()
        val deltaYDp = (radius * sin(angleRadians)).toFloat()
        val deltaXPx = with(density) { deltaXDp.dp.toPx() }
        val deltaYPx = with(density) { deltaYDp.dp.toPx() }
        Box(
            modifier = Modifier
                .offset((originX - itemSize / 2).dp, (originY - itemSize / 2).dp)
                .size(itemSize.dp)
                .graphicsLayer {
                    translationX = deltaXPx * progress.value
                    translationY = deltaYPx * progress.value
                    val scale = 0.52f + 0.48f * progress.value
                    scaleX = scale
                    scaleY = scale
                    alpha = progress.value
                },
            contentAlignment = Alignment.Center,
        ) {
            content(index)
        }
    }
}

@Composable
private fun RadialActionButton(
    icon: ImageVector,
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: Int = 48,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.size(size.dp).alpha(if (enabled) 1f else 0.36f),
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.inverseSurface
        else MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        contentColor = if (selected) MaterialTheme.colorScheme.inverseOnSurface
        else MaterialTheme.colorScheme.onSurface,
        shadowElevation = if (selected) 8.dp else 5.dp,
        tonalElevation = 2.dp,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label },
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size((size * 0.48f).dp))
        }
    }
}

@Composable
private fun PaletteButton(
    selectedColorArgb: Int,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.size(46.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        shadowElevation = 7.dp,
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.semantics { contentDescription = "색상 팔레트" },
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        brush = Brush.sweepGradient(
                            listOf(InkRed, InkYellow, InkGreen, InkBlue, Color(0xFF7657D6), InkRed)
                        ),
                        shape = CircleShape,
                    )
                    .padding(4.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .padding(3.dp)
                    .background(Color(selectedColorArgb), CircleShape),
            )
        }
    }
}

@Composable
private fun ColorChoice(
    color: Color,
    selectedColorArgb: Int,
    label: String,
    onSelect: (Int) -> Unit,
) {
    val selected = color.toArgb() == selectedColorArgb
    IconButton(
        onClick = { onSelect(color.toArgb()) },
        modifier = Modifier.size(48.dp).semantics { contentDescription = label },
    ) {
        Box(
            modifier = Modifier
                .size(if (selected) 36.dp else 32.dp)
                .then(
                    if (selected) Modifier
                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                        .padding(3.dp)
                        .border(2.dp, color, CircleShape)
                        .padding(3.dp)
                    else Modifier
                )
                .background(color, CircleShape),
        )
    }
}

@Composable
private fun StrokeWidthChoice(
    widthDp: Float,
    selectedWidthDp: Float,
    onSelect: (Float) -> Unit,
) {
    val selected = kotlin.math.abs(widthDp - selectedWidthDp) < 0.15f
    val strokeColor = if (selected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.inverseSurface else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurface,
        shadowElevation = if (selected) 7.dp else 0.dp,
    ) {
        IconButton(
            onClick = { onSelect(widthDp) },
            modifier = Modifier.semantics { contentDescription = "선 굵기 $widthDp" },
        ) {
            Canvas(modifier = Modifier.size(29.dp)) {
                val path = Path().apply {
                    moveTo(size.width * 0.14f, size.height * 0.62f)
                    cubicTo(
                        size.width * 0.32f, size.height * 0.18f,
                        size.width * 0.48f, size.height * 0.89f,
                        size.width * 0.82f, size.height * 0.46f,
                    )
                }
                drawPath(
                    path = path,
                    color = strokeColor,
                    style = Stroke(width = widthDp.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
private fun CurvedOpacitySlider(
    color: Color,
    widthDp: Float,
    opacity: Float,
    centerX: Int,
    centerY: Int,
    radius: Int,
    startAngle: Float,
    sweepAngle: Float,
    onOpacityChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val center = Offset(
        x = with(density) { centerX.dp.toPx() },
        y = with(density) { centerY.dp.toPx() },
    )
    val radiusPx = with(density) { radius.dp.toPx() }
    val touchTolerance = with(density) { 26.dp.toPx() }
    fun updateOpacity(position: Offset) {
        var angle = (atan2(position.y - center.y, position.x - center.x) * 180f / PI.toFloat())
        if (angle < 0f) angle += 360f
        if (angle < 90f) angle += 360f
        val distance = hypot(position.x - center.x, position.y - center.y)
        if (kotlin.math.abs(distance - radiusPx) > touchTolerance) return
        if (angle < startAngle - 8f || angle > startAngle + sweepAngle + 8f) return
        val fraction = ((angle - startAngle) / sweepAngle).coerceIn(0f, 1f)
        onOpacityChange(0.15f + fraction * 0.85f)
    }
    val inputModifier = modifier
        .semantics { contentDescription = "펜 투명도 ${(opacity * 100).roundToInt()}%" }
        .pointerInput(onOpacityChange) {
            detectTapGestures { position ->
                updateOpacity(position)
            }
        }
        .pointerInput(onOpacityChange) {
            detectDragGestures(
                onDragStart = { position ->
                    updateOpacity(position)
                },
                onDrag = { change, _ ->
                    change.consume()
                    updateOpacity(change.position)
                },
            )
        }
    val previewSurface = MaterialTheme.colorScheme.surface
    Canvas(modifier = inputModifier) {
        val strokeWidth = (widthDp * 4.6f).dp.toPx().coerceIn(12.dp.toPx(), 30.dp.toPx())
        drawArc(
            color = color.copy(alpha = 0.15f),
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = Offset(center.x - radiusPx, center.y - radiusPx),
            size = androidx.compose.ui.geometry.Size(radiusPx * 2f, radiusPx * 2f),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
        val progress = ((opacity - 0.15f) / 0.85f).coerceIn(0f, 1f)
        drawArc(
            color = color.copy(alpha = opacity),
            startAngle = startAngle,
            sweepAngle = sweepAngle * progress,
            useCenter = false,
            topLeft = Offset(center.x - radiusPx, center.y - radiusPx),
            size = androidx.compose.ui.geometry.Size(radiusPx * 2f, radiusPx * 2f),
            style = Stroke(
                width = strokeWidth,
                cap = StrokeCap.Round,
            ),
        )
        val thumbAngle = Math.toRadians((startAngle + sweepAngle * progress).toDouble())
        val thumbCenter = Offset(
            x = center.x + radiusPx * cos(thumbAngle).toFloat(),
            y = center.y + radiusPx * sin(thumbAngle).toFloat(),
        )
        drawCircle(
            color = previewSurface,
            radius = 14.dp.toPx(),
            center = thumbCenter,
        )
        drawCircle(
            color = color.copy(alpha = opacity),
            radius = 10.dp.toPx(),
            center = thumbCenter,
        )
    }
}

@Composable
private fun PageControl(
    currentPage: Int,
    pageCount: Int,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(width = 136.dp, height = 36.dp),
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 4.dp,
        tonalElevation = 2.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(
                onClick = onPreviousPage,
                enabled = currentPage > 0,
                modifier = Modifier.size(36.dp).semantics { contentDescription = "이전 페이지" },
            ) {
                Icon(Icons.Rounded.ChevronLeft, contentDescription = null, modifier = Modifier.size(22.dp))
            }
            Text(
                text = "${currentPage + 1}/${pageCount.coerceAtLeast(1)}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
            IconButton(
                onClick = onNextPage,
                enabled = currentPage + 1 < pageCount,
                modifier = Modifier.size(36.dp).semantics { contentDescription = "다음 페이지" },
            ) {
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, modifier = Modifier.size(22.dp))
            }
        }
    }
}
