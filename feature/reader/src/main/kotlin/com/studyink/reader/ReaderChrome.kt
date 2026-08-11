package com.studyink.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val InkBlack = Color(0xFF17233C)
private val InkRed = Color(0xFFE23B3B)
private val InkBlue = Color(0xFF3568E8)
private val InkGreen = Color(0xFF2F9B67)
private val InkYellow = Color(0xFFF1B92B)

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
            alignment = Alignment.BottomEnd,
            offset = IntOffset(22, 22),
            onDismissRequest = onDismissRequest,
            properties = PopupProperties(
                focusable = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                clippingEnabled = true,
            ),
        ) {
            Box(modifier = Modifier.size(width = 300.dp, height = 250.dp)) {
                when (menuPage) {
                    RadialMenuPage.MAIN -> MainRadialMenu(
                        state = state,
                        selectedTool = selectedTool,
                        selectedColorArgb = selectedColorArgb,
                        currentPage = currentPage,
                        pageCount = pageCount,
                        onOpenPen = {
                            onSelectTool(ReaderTool.PEN)
                            menuPage = RadialMenuPage.PEN
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
                        onBack = { menuPage = RadialMenuPage.MAIN },
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
    onOpenPen: () -> Unit,
    onOpenColors: () -> Unit,
    onSelectTool: (ReaderTool) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onResetZoom: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    RadialFan(
        itemCount = 9,
        originX = 150,
        originY = 215,
        radius = 120,
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
                icon = Icons.Rounded.ChevronLeft,
                label = "이전 페이지",
                enabled = currentPage > 0,
                size = 44,
            ) { onPreviousPage() }
            2 -> RadialActionButton(
                icon = Icons.AutoMirrored.Rounded.Backspace,
                label = "지우개",
                selected = selectedTool == ReaderTool.PARTIAL_ERASER,
                size = 44,
            ) { onSelectTool(ReaderTool.PARTIAL_ERASER) }
            3 -> RadialActionButton(
                icon = Icons.Rounded.Edit,
                label = "펜",
                selected = selectedTool == ReaderTool.PEN,
                size = 44,
            ) { onOpenPen() }
            4 -> RadialActionButton(
                icon = Icons.Rounded.Brush,
                label = "형광펜",
                selected = selectedTool == ReaderTool.HIGHLIGHTER,
                size = 44,
            ) { onSelectTool(ReaderTool.HIGHLIGHTER) }
            5 -> PaletteButton(
                selectedColorArgb = selectedColorArgb,
                onClick = onOpenColors,
            )
            6 -> RadialActionButton(
                icon = Icons.Rounded.FitScreen,
                label = "확대 초기화",
                size = 44,
            ) { onResetZoom() }
            7 -> RadialActionButton(
                icon = Icons.Rounded.ChevronRight,
                label = "다음 페이지",
                enabled = currentPage + 1 < pageCount,
                size = 44,
            ) { onNextPage() }
            else -> RadialActionButton(
                icon = Icons.AutoMirrored.Rounded.Redo,
                label = "다시 실행",
                enabled = state.snapshot.redoStack.isNotEmpty() && !state.busy,
                size = 44,
            ) { onRedo() }
        }
    }
    PageBadge(currentPage = currentPage, pageCount = pageCount, x = 123, y = 200)
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
        originX = 150,
        originY = 215,
        radius = 120,
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
    onBack: () -> Unit,
) {
    val widths = listOf(6.4f, 4.8f, 3.2f, 2.4f, 1.6f)
    RadialFan(
        itemCount = widths.size,
        originX = 185,
        originY = 210,
        radius = 135,
        startAngleDegrees = 190f,
        sweepAngleDegrees = 80f,
        animationKey = "pen-widths",
    ) { index ->
        StrokeWidthChoice(
            widthDp = widths[index],
            selectedWidthDp = selectedWidthDp,
            onSelect = onSelectWidth,
        )
    }
    PenStrokePreview(
        color = Color(selectedColorArgb).copy(alpha = selectedOpacity),
        widthDp = selectedWidthDp,
        modifier = Modifier.offset(x = 207.dp, y = 82.dp).size(width = 82.dp, height = 90.dp),
    )
    OpacityControl(
        opacity = selectedOpacity,
        onOpacityChange = onSelectOpacity,
        modifier = Modifier.offset(x = 87.dp, y = 168.dp).size(width = 178.dp, height = 76.dp),
    )
    RadialActionButton(
        icon = Icons.Rounded.ExpandMore,
        label = "도구 메뉴로 돌아가기",
        size = 40,
        x = 14,
        y = 207,
    ) { onBack() }
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
    val itemSize = 50
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
    x: Int = 0,
    y: Int = 0,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: Int = 48,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.offset(x.dp, y.dp).size(size.dp).alpha(if (enabled) 1f else 0.36f),
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
    x: Int = 0,
    y: Int = 0,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.offset(x.dp, y.dp).size(46.dp),
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
private fun OpacityControl(
    opacity: Float,
    onOpacityChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.semantics { contentDescription = "펜 투명도" },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        shadowElevation = 6.dp,
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("투명도", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
                Text(
                    "${(opacity * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            }
            Slider(
                value = opacity,
                onValueChange = { onOpacityChange(it.coerceIn(0.15f, 1f)) },
                valueRange = 0.15f..1f,
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
        }
    }
}

@Composable
private fun PenStrokePreview(
    color: Color,
    widthDp: Float,
    modifier: Modifier = Modifier,
) {
    val previewSurface = MaterialTheme.colorScheme.surface
    Canvas(modifier = modifier.semantics { contentDescription = "현재 펜 미리보기" }) {
        val path = Path().apply {
            moveTo(0f, size.height * 0.18f)
            cubicTo(
                size.width * 0.45f, size.height * 0.02f,
                size.width * 0.82f, size.height * 0.18f,
                size.width * 0.86f, size.height * 0.80f,
            )
        }
        drawPath(
            path = path,
            brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.18f), color)),
            style = Stroke(
                width = (widthDp * 5.2f).dp.toPx().coerceIn(12.dp.toPx(), 34.dp.toPx()),
                cap = StrokeCap.Round,
            ),
        )
        drawCircle(
            color = previewSurface,
            radius = 16.dp.toPx(),
            center = Offset(size.width * 0.86f, size.height * 0.80f),
        )
        drawCircle(
            color = color,
            radius = 12.dp.toPx(),
            center = Offset(size.width * 0.86f, size.height * 0.80f),
        )
    }
}

@Composable
private fun PageBadge(currentPage: Int, pageCount: Int, x: Int, y: Int) {
    Surface(
        modifier = Modifier.offset(x.dp, y.dp).size(width = 54.dp, height = 30.dp),
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shadowElevation = 4.dp,
        tonalElevation = 2.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "${currentPage + 1}/${pageCount.coerceAtLeast(1)}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
