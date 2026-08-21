package com.studyink.reader

import android.view.MotionEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
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

private enum class RadialMenuPage { MAIN, COLORS, PEN }

private const val FanStartDegrees = 180f
private const val FanSweepDegrees = 120f
private const val PenWidthSweepDegrees = 67f
private const val PenOpacityStartDegrees = 256f
private const val PenOpacitySweepDegrees = 44f

/** The ring is sized for the busiest page so it never resizes as the user pages through it. */
private fun mainMenuItemCount(state: ReaderUiState) = if (state.capabilities.canGrade) 9 else 8

@Composable
fun StylusToolMenu(
    expanded: Boolean,
    state: ReaderUiState,
    selectedColorArgb: Int,
    selectedWidthDp: Float,
    selectedOpacity: Float,
    onSelectTool: (ReaderTool) -> Unit,
    onSelectColor: (Int) -> Unit,
    onSelectWidth: (Float) -> Unit,
    onSelectOpacity: (Float) -> Unit,
    onResetZoom: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggleRole: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val tokens = readerChromeTokens(state.role)
    var menuPage by remember { mutableStateOf(RadialMenuPage.MAIN) }
    var raisedTool by remember { mutableStateOf<ReaderTool?>(null) }
    LaunchedEffect(expanded) {
        if (expanded) {
            menuPage = RadialMenuPage.MAIN
            // A persisted drawing tool must not look pre-selected when a fresh menu opens. Only a
            // tool explicitly pressed during this menu session remains extracted.
            raisedTool = null
        }
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
                dismissOnClickOutside = false,
                clippingEnabled = true,
            ),
        ) {
            val rootGeometry = radialFanGeometry(
                tokens,
                mainMenuItemCount(state),
                FanSweepDegrees,
            )
            val geometry = when (menuPage) {
                RadialMenuPage.MAIN -> rootGeometry
                RadialMenuPage.COLORS -> radialFanGeometry(
                    tokens = tokens,
                    itemCount = 7,
                    sweepAngleDegrees = FanSweepDegrees,
                )
                RadialMenuPage.PEN -> radialFanGeometry(
                    tokens = tokens,
                    itemCount = 5,
                    sweepAngleDegrees = PenWidthSweepDegrees,
                    boundsSweepAngleDegrees = FanSweepDegrees,
                )
            }.copy(
                // Every page shares the exact left-start baseline. Only its radius changes so
                // pages with fewer choices remain just as compact as the main tools page.
                originY = rootGeometry.originY,
            )
            val density = LocalDensity.current
            val dismissOriginXPx = with(density) { geometry.originX.toPx() }
            val dismissOriginYPx = with(density) { geometry.originY.toPx() }
            val dismissClearance = maxOf(
                // Radial items use square 48/60dp targets. Clear their inward-facing diagonal
                // corners as well as their side, then leave a small no-man's-land between them and
                // the blank-sector dismiss target.
                maxOf(tokens.toolButtonSize, tokens.minimumTouchSize) * 0.7071068f + 2.dp,
                tokens.opacityTouchTolerance,
            )
            val dismissRadiusPx = with(density) { (geometry.radius - dismissClearance).toPx() }
            fun isInsideDismissSector(position: Offset): Boolean {
                val dx = position.x - dismissOriginXPx
                val dy = position.y - dismissOriginYPx
                val distanceSquared = dx * dx + dy * dy
                if (distanceSquared > dismissRadiusPx * dismissRadiusPx) return false
                if (distanceSquared <= 1f) return true
                var angle = atan2(dy, dx) * 180f / PI.toFloat()
                if (angle < 0f) angle += 360f
                val angleFromStart = (angle - FanStartDegrees + 360f) % 360f
                return angleFromStart <= FanSweepDegrees
            }
            CompositionLocalProvider(LocalPenRestingAlpha provides tokens.menuRestingAlpha) {
                Box(
                    modifier = Modifier
                        .size(width = rootGeometry.menuWidth, height = rootGeometry.menuHeight)
                        // Observe the stylus at Final pass without consuming it. A parent
                        // pointerInteropFilter interrupts the child interop streams used by every
                        // S Pen button, which is why the buttons previously stopped responding.
                        .pointerInput(
                            dismissOriginXPx,
                            dismissOriginYPx,
                            dismissRadiusPx,
                            onDismissRequest,
                        ) {
                            var activePointerId: PointerId? = null
                            var armed = false
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Final)
                                    if (activePointerId == null) {
                                        val down = event.changes.firstOrNull { change ->
                                            change.type == PointerType.Stylus &&
                                                !change.previousPressed && change.pressed &&
                                                !change.isConsumed
                                        }
                                        if (down != null && isInsideDismissSector(down.position)) {
                                            activePointerId = down.id
                                            armed = true
                                        }
                                    } else {
                                        val change = event.changes.firstOrNull {
                                            it.id == activePointerId
                                        }
                                        if (change == null) {
                                            activePointerId = null
                                            armed = false
                                        } else if (!change.pressed) {
                                            val shouldDismiss = armed &&
                                                !change.isConsumed &&
                                                isInsideDismissSector(change.position)
                                            activePointerId = null
                                            armed = false
                                            if (shouldDismiss) onDismissRequest()
                                        } else {
                                            armed = isInsideDismissSector(change.position)
                                        }
                                    }
                                }
                            }
                        },
                ) {
                    when (menuPage) {
                        RadialMenuPage.MAIN -> MainRadialMenu(
                            state = state,
                            geometry = geometry,
                            selectedTool = raisedTool,
                            selectedColorArgb = selectedColorArgb,
                            onPenClick = {
                                if (raisedTool == ReaderTool.PEN) {
                                    menuPage = RadialMenuPage.PEN
                                } else {
                                    raisedTool = ReaderTool.PEN
                                    onSelectTool(ReaderTool.PEN)
                                }
                            },
                            onOpenColors = { menuPage = RadialMenuPage.COLORS },
                            onSelectTool = { tool ->
                                raisedTool = tool
                                onSelectTool(tool)
                            },
                            onResetZoom = onResetZoom,
                            onUndo = onUndo,
                            onRedo = onRedo,
                            onToggleRole = onToggleRole,
                        )

                        RadialMenuPage.COLORS -> ColorRadialMenu(
                            role = state.role,
                            geometry = geometry,
                            selectedColorArgb = selectedColorArgb,
                            onSelectColor = onSelectColor,
                            onBack = { menuPage = RadialMenuPage.MAIN },
                        )

                        RadialMenuPage.PEN -> PenRadialMenu(
                            role = state.role,
                            geometry = geometry,
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
}

@Composable
private fun MainRadialMenu(
    state: ReaderUiState,
    geometry: RadialFanGeometry,
    selectedTool: ReaderTool?,
    selectedColorArgb: Int,
    onPenClick: () -> Unit,
    onOpenColors: () -> Unit,
    onSelectTool: (ReaderTool) -> Unit,
    onResetZoom: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onToggleRole: () -> Unit,
    previewStatic: Boolean = false,
    forceHoveredToolForPreview: ReaderTool? = null,
) {
    val tokens = readerChromeTokens(state.role)
    val canGrade = state.capabilities.canGrade
    val resetIndex = if (canGrade) 6 else 5
    val modeIndex = resetIndex + 1
    RadialFan(
        itemCount = mainMenuItemCount(state),
        geometry = geometry,
        itemSize = tokens.toolButtonSize,
        startAngleDegrees = FanStartDegrees,
        sweepAngleDegrees = FanSweepDegrees,
        animationKey = "main-tools",
        entranceStartScale = tokens.radialEntranceStartScale,
        dampingRatio = tokens.springDampingRatio,
        stiffness = tokens.springStiffness,
        staticProgress = if (previewStatic) 1f else null,
    ) { index, angleDegrees ->
        when {
            index == 0 -> RadialActionButton(
                iconRes = R.drawable.ic_undo,
                label = "되돌리기",
                enabled = state.canUndo,
                role = state.role,
            ) { onUndo() }
            index == 1 -> ToolPenButton(
                description = "지우개",
                toolItemRes = R.drawable.ic_tool_eraser_item,
                onAction = { onSelectTool(ReaderTool.PARTIAL_ERASER) },
                role = state.role,
                selected = selectedTool == ReaderTool.PARTIAL_ERASER,
                forceHoveredForPreview = forceHoveredToolForPreview == ReaderTool.PARTIAL_ERASER,
                radialAngleDegrees = angleDegrees,
            )
            index == 2 -> ToolPenButton(
                description = "펜",
                toolItemRes = R.drawable.ic_tool_pen_item,
                onAction = onPenClick,
                role = state.role,
                selected = selectedTool == ReaderTool.PEN,
                forceHoveredForPreview = forceHoveredToolForPreview == ReaderTool.PEN,
                radialAngleDegrees = angleDegrees,
            )
            index == 3 -> ToolPenButton(
                description = "형광펜",
                toolItemRes = R.drawable.ic_tool_highlighter_item,
                onAction = { onSelectTool(ReaderTool.HIGHLIGHTER) },
                role = state.role,
                selected = selectedTool == ReaderTool.HIGHLIGHTER,
                forceHoveredForPreview = forceHoveredToolForPreview == ReaderTool.HIGHLIGHTER,
                radialAngleDegrees = angleDegrees,
            )
            index == 4 -> PaletteButton(
                selectedColorArgb = selectedColorArgb,
                role = state.role,
                onClick = onOpenColors,
            )
            canGrade && index == 5 -> ToolPenButton(
                description = "채점",
                toolItemRes = R.drawable.ic_tool_grade_item,
                onAction = { onSelectTool(ReaderTool.GRADE) },
                role = state.role,
                selected = selectedTool == ReaderTool.GRADE,
                forceHoveredForPreview = forceHoveredToolForPreview == ReaderTool.GRADE,
                radialAngleDegrees = angleDegrees,
            )
            index == resetIndex -> RadialActionButton(
                iconRes = R.drawable.ic_zoom_reset,
                label = "확대 초기화",
                role = state.role,
            ) { onResetZoom() }
            index == modeIndex -> RadialActionButton(
                iconRes = if (state.role == ReaderRole.STUDENT) {
                    R.drawable.ic_teacher_mode
                } else {
                    R.drawable.ic_student_switch
                },
                label = if (state.role == ReaderRole.STUDENT) "선생 모드" else "학생 모드",
                role = state.role,
            ) { onToggleRole() }
            else -> RadialActionButton(
                iconRes = R.drawable.ic_redo,
                label = "다시 실행",
                enabled = state.canRedo,
                role = state.role,
            ) { onRedo() }
        }
    }
}

@Composable
private fun ColorRadialMenu(
    role: ReaderRole,
    geometry: RadialFanGeometry,
    selectedColorArgb: Int,
    onSelectColor: (Int) -> Unit,
    onBack: () -> Unit,
    previewStatic: Boolean = false,
) {
    val tokens = readerChromeTokens(role)
    val colors = listOf(
        Triple(tokens.paletteBlue, "파랑", tokens.paletteBlue.toArgb()),
        Triple(tokens.paletteGreen, "초록", tokens.paletteGreen.toArgb()),
        Triple(tokens.paletteYellow, "노랑", tokens.paletteYellow.toArgb()),
        Triple(tokens.paletteOrange, "주황", tokens.paletteOrange.toArgb()),
        Triple(tokens.palettePink, "분홍", tokens.palettePink.toArgb()),
        Triple(tokens.paletteCream, "크림", tokens.paletteCream.toArgb()),
    )
    RadialFan(
        itemCount = colors.size + 1,
        geometry = geometry,
        itemSize = tokens.toolButtonSize,
        startAngleDegrees = FanStartDegrees,
        sweepAngleDegrees = FanSweepDegrees,
        animationKey = "colors",
        entranceStartScale = tokens.radialEntranceStartScale,
        dampingRatio = tokens.springDampingRatio,
        stiffness = tokens.springStiffness,
        staticProgress = if (previewStatic) 1f else null,
    ) { index, _ ->
        if (index < colors.size) {
            val (color, label) = colors[index]
            ColorChoice(color, selectedColorArgb, label, role, onSelect = onSelectColor)
        } else {
            PaletteButton(selectedColorArgb = selectedColorArgb, role = role, onClick = onBack)
        }
    }
}

@Composable
private fun PenRadialMenu(
    role: ReaderRole,
    geometry: RadialFanGeometry,
    selectedColorArgb: Int,
    selectedWidthDp: Float,
    selectedOpacity: Float,
    onSelectWidth: (Float) -> Unit,
    onSelectOpacity: (Float) -> Unit,
    previewStatic: Boolean = false,
) {
    val tokens = readerChromeTokens(role)
    val widths = listOf(6.4f, 4.8f, 3.2f, 2.4f, 1.6f)
    CurvedOpacitySlider(
        color = Color(selectedColorArgb),
        widthDp = selectedWidthDp,
        opacity = selectedOpacity,
        centerX = geometry.originX,
        centerY = geometry.originY,
        radius = geometry.radius,
        startAngle = PenOpacityStartDegrees,
        sweepAngle = PenOpacitySweepDegrees,
        tokens = tokens,
        onOpacityChange = onSelectOpacity,
        modifier = Modifier.fillMaxSize(),
    )
    RadialFan(
        itemCount = widths.size,
        geometry = geometry,
        startAngleDegrees = FanStartDegrees,
        sweepAngleDegrees = PenWidthSweepDegrees,
        itemSize = tokens.toolButtonSize,
        animationKey = "pen-widths",
        entranceStartScale = tokens.radialEntranceStartScale,
        dampingRatio = tokens.springDampingRatio,
        stiffness = tokens.springStiffness,
        staticProgress = if (previewStatic) 1f else null,
    ) { index, _ ->
        StrokeWidthChoice(
            widthDp = widths[index],
            selectedWidthDp = selectedWidthDp,
            role = role,
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
    geometry: RadialFanGeometry,
    startAngleDegrees: Float,
    sweepAngleDegrees: Float,
    itemSize: Dp,
    animationKey: Any,
    entranceStartScale: Float,
    dampingRatio: Float,
    stiffness: Float,
    staticProgress: Float? = null,
    content: @Composable (index: Int, angleDegrees: Float) -> Unit,
) {
    val progress = remember(animationKey, itemCount) { Animatable(0f) }
    if (staticProgress == null) {
        LaunchedEffect(animationKey, itemCount) {
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = dampingRatio,
                    stiffness = stiffness,
                ),
            )
        }
    }
    val renderedProgress = staticProgress ?: progress.value
    val positionProgress = renderedProgress.coerceIn(0f, 1f)

    val density = LocalDensity.current
    repeat(itemCount) { index ->
        val angleDegrees = if (itemCount == 1) {
            startAngleDegrees
        } else {
            startAngleDegrees + (index / (itemCount - 1f)) * sweepAngleDegrees
        }
        val angleRadians = Math.toRadians(angleDegrees.toDouble())
        val deltaXPx = with(density) { (geometry.radius * cos(angleRadians).toFloat()).toPx() }
        val deltaYPx = with(density) { (geometry.radius * sin(angleRadians).toFloat()).toPx() }
        Box(
            modifier = Modifier
                .offset(geometry.originX - itemSize / 2, geometry.originY - itemSize / 2)
                .size(itemSize)
                .graphicsLayer {
                    // Alpha normally promotes the 48/60dp slot to an offscreen layer while the
                    // fan enters, which can crop artwork protruding beyond that slot. Each slot
                    // has a single child, so direct alpha modulation preserves the overflow.
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    // The spring may overshoot 1.0. Keep that playfulness in scale, but never move
                    // a slot beyond its final radius where the protruding artwork could be clipped.
                    translationX = deltaXPx * positionProgress
                    translationY = deltaYPx * positionProgress
                    val scale = entranceStartScale + (1f - entranceStartScale) * renderedProgress
                    scaleX = scale
                    scaleY = scale
                    alpha = positionProgress
                },
            contentAlignment = Alignment.Center,
        ) {
            content(index, angleDegrees)
        }
    }
}

@Composable
private fun RadialActionButton(
    iconRes: Int,
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    role: ReaderRole = ReaderRole.STUDENT,
    onClick: () -> Unit,
) {
    val tokens = readerChromeTokens(role)
    IconPenButton(
        description = label,
        iconRes = iconRes,
        onAction = onClick,
        enabled = enabled,
        selected = selected,
        visualSize = tokens.actionButtonSize,
        role = role,
    )
}

@Composable
private fun PaletteButton(
    selectedColorArgb: Int,
    role: ReaderRole = ReaderRole.STUDENT,
    onClick: () -> Unit,
) {
    val tokens = readerChromeTokens(role)
    val palette = listOf(
        tokens.palettePink,
        tokens.paletteYellow,
        tokens.paletteGreen,
        tokens.paletteBlue,
        tokens.paletteOrange,
        tokens.palettePink,
    )
    IconPenButton(
        description = "색상 팔레트",
        iconRes = null,
        onAction = onClick,
        visualSize = tokens.generalButtonSize,
        role = role,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(tokens.generalButtonSize * 0.70f)
                    .background(
                        brush = Brush.sweepGradient(palette),
                        shape = CircleShape,
                    )
                    .padding(tokens.generalButtonSize * 0.10f)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .padding(tokens.generalButtonSize * 0.08f)
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
    role: ReaderRole,
    onSelect: (Int) -> Unit,
) {
    val tokens = readerChromeTokens(role)
    val selected = color.toArgb() == selectedColorArgb
    IconPenButton(
        description = label,
        iconRes = null,
        onAction = { onSelect(color.toArgb()) },
        selected = selected,
        visualSize = tokens.generalButtonSize,
        role = role,
    ) {
        Box(
            modifier = Modifier
                .size(tokens.generalButtonSize * if (selected) 0.48f else 0.38f)
                .background(
                    color.copy(alpha = if (selected) 1f else tokens.colorChoiceRestingAlpha),
                    CircleShape,
                ),
        )
    }
}

@Composable
private fun StrokeWidthChoice(
    widthDp: Float,
    selectedWidthDp: Float,
    role: ReaderRole,
    onSelect: (Float) -> Unit,
) {
    val tokens = readerChromeTokens(role)
    val selected = kotlin.math.abs(widthDp - selectedWidthDp) < 0.15f
    val strokeColor = tokens.buttonForeground
    IconPenButton(
        description = "선 굵기 $widthDp",
        iconRes = null,
        onAction = { onSelect(widthDp) },
        selected = selected,
        visualSize = tokens.generalButtonSize,
        role = role,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(tokens.strokePreviewSize)) {
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
    centerX: Dp,
    centerY: Dp,
    radius: Dp,
    startAngle: Float,
    sweepAngle: Float,
    tokens: ReaderChromeTokens,
    onOpacityChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val center = Offset(
        x = with(density) { centerX.toPx() },
        y = with(density) { centerY.toPx() },
    )
    val radiusPx = with(density) { radius.toPx() }
    val touchTolerance = with(density) { tokens.opacityTouchTolerance.toPx() }
    fun opacityAt(position: Offset): Float? {
        var angle = (atan2(position.y - center.y, position.x - center.x) * 180f / PI.toFloat())
        if (angle < 0f) angle += 360f
        if (angle < 90f) angle += 360f
        val distance = hypot(position.x - center.x, position.y - center.y)
        if (kotlin.math.abs(distance - radiusPx) > touchTolerance) return null
        if (angle < startAngle - 8f || angle > startAngle + sweepAngle + 8f) return null
        val fraction = ((angle - startAngle) / sweepAngle).coerceIn(0f, 1f)
        return tokens.strokeOpacityMin + fraction * (1f - tokens.strokeOpacityMin)
    }
    var adjustingOpacity by remember { mutableStateOf(false) }
    val inputModifier = modifier
        .semantics { contentDescription = "펜 투명도 ${(opacity * 100).roundToInt()}%" }
        .pointerInteropFilter { event ->
            if (
                event.actionMasked == MotionEvent.ACTION_HOVER_ENTER ||
                event.actionMasked == MotionEvent.ACTION_HOVER_MOVE ||
                event.actionMasked == MotionEvent.ACTION_HOVER_EXIT
            ) {
                return@pointerInteropFilter false
            }
            val index = event.actionIndex.coerceIn(0, (event.pointerCount - 1).coerceAtLeast(0))
            val stylus = event.pointerCount > 0 &&
                event.getToolType(index) == MotionEvent.TOOL_TYPE_STYLUS
            if (!stylus) return@pointerInteropFilter false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val nextOpacity = opacityAt(Offset(event.x, event.y))
                    adjustingOpacity = nextOpacity != null
                    if (nextOpacity != null) onOpacityChange(nextOpacity)
                    adjustingOpacity
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!adjustingOpacity) return@pointerInteropFilter false
                    opacityAt(Offset(event.x, event.y))?.let(onOpacityChange)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val handled = adjustingOpacity
                    if (handled) opacityAt(Offset(event.x, event.y))?.let(onOpacityChange)
                    adjustingOpacity = false
                    handled
                }
                MotionEvent.ACTION_CANCEL -> {
                    val handled = adjustingOpacity
                    adjustingOpacity = false
                    handled
                }
                else -> adjustingOpacity
            }
        }
    val previewSurface = MaterialTheme.colorScheme.surface
    Canvas(modifier = inputModifier) {
        val strokeWidth = (widthDp * tokens.opacityStrokeScale).dp.toPx().coerceIn(
            tokens.opacityStrokeMin.toPx(),
            tokens.opacityStrokeMax.toPx(),
        )
        drawArc(
            color = color.copy(alpha = tokens.strokeOpacityMin),
            startAngle = startAngle,
            sweepAngle = sweepAngle,
            useCenter = false,
            topLeft = Offset(center.x - radiusPx, center.y - radiusPx),
            size = androidx.compose.ui.geometry.Size(radiusPx * 2f, radiusPx * 2f),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
        val progress = ((opacity - tokens.strokeOpacityMin) / (1f - tokens.strokeOpacityMin)).coerceIn(0f, 1f)
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
            radius = tokens.opacityThumbOuterRadius.toPx(),
            center = thumbCenter,
        )
        drawCircle(
            color = color.copy(alpha = opacity),
            radius = tokens.opacityThumbInnerRadius.toPx(),
            center = thumbCenter,
        )
    }
}

private enum class StylusMenuPreviewPage { TOOLS, COLORS, PEN_SETTINGS }

@Preview(
    name = "도구 4개 · hovered=true",
    group = "도구 버튼 검증",
    widthDp = 320,
    heightDp = 110,
    showBackground = true,
)
@Composable
private fun HoveredToolButtonsPreview() {
    MaterialTheme {
        Row(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolPenButton("펜", R.drawable.ic_tool_pen_item, {}, ReaderRole.TEACHER_TABLET, forceHoveredForPreview = true)
            ToolPenButton("형광펜", R.drawable.ic_tool_highlighter_item, {}, ReaderRole.TEACHER_TABLET, forceHoveredForPreview = true)
            ToolPenButton("지우개", R.drawable.ic_tool_eraser_item, {}, ReaderRole.TEACHER_TABLET, forceHoveredForPreview = true)
            ToolPenButton("채점", R.drawable.ic_tool_grade_item, {}, ReaderRole.TEACHER_TABLET, forceHoveredForPreview = true)
        }
    }
}

@Composable
private fun StylusMenuDevicePreview(
    state: ReaderUiState,
    page: StylusMenuPreviewPage,
    forceHoveredToolForPreview: ReaderTool? = null,
) {
    val tokens = readerChromeTokens(state.role)
    val rootGeometry = radialFanGeometry(
        tokens,
        mainMenuItemCount(state),
        FanSweepDegrees,
    )
    val geometry = when (page) {
        StylusMenuPreviewPage.TOOLS -> rootGeometry
        StylusMenuPreviewPage.COLORS -> radialFanGeometry(
            tokens = tokens,
            itemCount = 7,
            sweepAngleDegrees = FanSweepDegrees,
        )
        StylusMenuPreviewPage.PEN_SETTINGS -> radialFanGeometry(
            tokens = tokens,
            itemCount = 5,
            sweepAngleDegrees = PenWidthSweepDegrees,
            boundsSweepAngleDegrees = FanSweepDegrees,
        )
    }.copy(originY = rootGeometry.originY)
    ReaderDevicePreviewFrame(state) {
        CompositionLocalProvider(LocalPenRestingAlpha provides tokens.menuRestingAlpha) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .size(width = rootGeometry.menuWidth, height = rootGeometry.menuHeight),
            ) {
                when (page) {
                    StylusMenuPreviewPage.TOOLS -> MainRadialMenu(
                        state = state,
                        geometry = geometry,
                        selectedTool = ReaderTool.PEN,
                        selectedColorArgb = tokens.paletteBlue.toArgb(),
                        onPenClick = {},
                        onOpenColors = {},
                        onSelectTool = {},
                        onResetZoom = {},
                        onUndo = {},
                        onRedo = {},
                        onToggleRole = {},
                        previewStatic = true,
                        forceHoveredToolForPreview = forceHoveredToolForPreview,
                    )

                    StylusMenuPreviewPage.COLORS -> ColorRadialMenu(
                        role = state.role,
                        geometry = geometry,
                        selectedColorArgb = tokens.paletteBlue.toArgb(),
                        onSelectColor = {},
                        onBack = {},
                        previewStatic = true,
                    )

                    StylusMenuPreviewPage.PEN_SETTINGS -> PenRadialMenu(
                        role = state.role,
                        geometry = geometry,
                        selectedColorArgb = tokens.paletteBlue.toArgb(),
                        selectedWidthDp = 3.2f,
                        selectedOpacity = 0.72f,
                        onSelectWidth = {},
                        onSelectOpacity = {},
                        previewStatic = true,
                    )
                }
            }
        }
    }
}

@Preview(
    name = "펜 메뉴 · 도구 · 형광펜 호버",
    group = "교사폰 S23 Ultra · 세로",
    widthDp = 412,
    heightDp = 892,
    showBackground = true,
)
@Composable
private fun TeacherPhoneToolMenuPreview() {
    StylusMenuDevicePreview(
        state = ReaderDevicePreviewFixtures.teacherPhone(),
        page = StylusMenuPreviewPage.TOOLS,
        forceHoveredToolForPreview = ReaderTool.HIGHLIGHTER,
    )
}

@Preview(
    name = "펜 메뉴 · 색상",
    group = "교사폰 S23 Ultra · 세로",
    widthDp = 412,
    heightDp = 892,
    showBackground = true,
)
@Composable
private fun TeacherPhoneColorMenuPreview() {
    StylusMenuDevicePreview(
        state = ReaderDevicePreviewFixtures.teacherPhone(),
        page = StylusMenuPreviewPage.COLORS,
    )
}

@Preview(
    name = "펜 메뉴 · 굵기와 투명도",
    group = "교사폰 S23 Ultra · 세로",
    widthDp = 412,
    heightDp = 892,
    showBackground = true,
)
@Composable
private fun TeacherPhonePenSettingsPreview() {
    StylusMenuDevicePreview(
        state = ReaderDevicePreviewFixtures.teacherPhone(),
        page = StylusMenuPreviewPage.PEN_SETTINGS,
    )
}

@Preview(
    name = "펜 메뉴 · 도구 · 지우개 호버",
    group = "학생 Tab S11 · 세로",
    widthDp = 800,
    heightDp = 1280,
    showBackground = true,
)
@Composable
private fun StudentTabletToolMenuPreview() {
    StylusMenuDevicePreview(
        state = ReaderDevicePreviewFixtures.studentTablet(),
        page = StylusMenuPreviewPage.TOOLS,
        forceHoveredToolForPreview = ReaderTool.PARTIAL_ERASER,
    )
}

@Preview(
    name = "펜 메뉴 · 색상",
    group = "학생 Tab S11 · 세로",
    widthDp = 800,
    heightDp = 1280,
    showBackground = true,
)
@Composable
private fun StudentTabletColorMenuPreview() {
    StylusMenuDevicePreview(
        state = ReaderDevicePreviewFixtures.studentTablet(),
        page = StylusMenuPreviewPage.COLORS,
    )
}

@Preview(
    name = "펜 메뉴 · 굵기와 투명도",
    group = "학생 Tab S11 · 세로",
    widthDp = 800,
    heightDp = 1280,
    showBackground = true,
)
@Composable
private fun StudentTabletPenSettingsPreview() {
    StylusMenuDevicePreview(
        state = ReaderDevicePreviewFixtures.studentTablet(),
        page = StylusMenuPreviewPage.PEN_SETTINGS,
    )
}
