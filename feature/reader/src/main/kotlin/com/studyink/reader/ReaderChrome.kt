package com.studyink.reader

import android.view.MotionEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class RadialMenuPage { MAIN, COLORS, PEN }

internal const val COMMON_RADIAL_MENU_ITEM_COUNT = 8
private const val FanStartDegrees = 180f
private const val FanSweepDegrees = 100f
private const val PenWidthSweepDegrees = 58f
private const val PenOpacityStartDegrees = 248f
private const val PenOpacitySweepDegrees = 20f
private const val PenOpacityHitToleranceDegrees = 3f
private const val PenBackDegrees = 280f

internal data class PenMenuAngleSpec(
    val widthAnglesDegrees: List<Float>,
    val opacityStartDegrees: Float,
    val opacityEndDegrees: Float,
    val opacityHitToleranceDegrees: Float,
    val backDegrees: Float,
)

internal fun penMenuAngleSpec(): PenMenuAngleSpec = PenMenuAngleSpec(
    widthAnglesDegrees = List(5) { index ->
        radialItemAngleDegrees(index, 5, PenWidthSweepDegrees)
    },
    opacityStartDegrees = PenOpacityStartDegrees,
    opacityEndDegrees = PenOpacityStartDegrees + PenOpacitySweepDegrees,
    opacityHitToleranceDegrees = PenOpacityHitToleranceDegrees,
    backDegrees = PenBackDegrees,
)

internal fun commonRadialMenuGeometry(tokens: ReaderChromeTokens): RadialFanGeometry =
    commonRadialMenuGeometry(
        toolButtonSize = tokens.toolButtonSize,
        radialItemGap = tokens.radialItemGap,
        radialMinRadius = tokens.radialMinRadius,
        radialEdgeMargin = tokens.radialEdgeMargin,
        radialArtworkHorizontalPadding = tokens.radialArtworkHorizontalPadding,
        radialTopMargin = tokens.radialTopMargin,
    )

internal fun commonRadialMenuGeometry(
    toolButtonSize: Dp,
    radialItemGap: Dp,
    radialMinRadius: Dp,
    radialEdgeMargin: Dp,
    radialArtworkHorizontalPadding: Dp,
    radialTopMargin: Dp,
): RadialFanGeometry {
    val stepRadians = Math.toRadians(
        (FanSweepDegrees / (COMMON_RADIAL_MENU_ITEM_COUNT - 1)).toDouble(),
    )
    val neededRadius = (toolButtonSize + radialItemGap) /
        (2f * sin(stepRadians / 2.0).toFloat())
    val radius = maxOf(radialMinRadius, neededRadius)
    val horizontalPadding = toolButtonSize / 2 + radialEdgeMargin +
        radialArtworkHorizontalPadding
    val rightExtentFactor = cos(
        Math.toRadians((FanStartDegrees + FanSweepDegrees).toDouble()),
    ).toFloat().coerceAtLeast(0f)
    val originY = radialTopMargin + radius + toolButtonSize / 2
    return RadialFanGeometry(
        radius = radius,
        originX = radius + horizontalPadding,
        originY = originY,
        menuWidth = horizontalPadding * 2 + radius * (1f + rightExtentFactor),
        menuHeight = originY + toolButtonSize / 2 + radialEdgeMargin,
    )
}

internal data class RadialItemCenter(val x: Dp, val y: Dp)

internal fun radialItemCenter(
    originX: Dp,
    originY: Dp,
    radius: Dp,
    angleDegrees: Float,
): RadialItemCenter {
    val angleRadians = Math.toRadians(angleDegrees.toDouble())
    return RadialItemCenter(
        x = originX + radius * cos(angleRadians).toFloat(),
        y = originY + radius * sin(angleRadians).toFloat(),
    )
}

internal fun radialMenuInputHalfThickness(tokens: ReaderChromeTokens): Dp =
    radialMenuInputHalfThickness(
        opacityTouchTolerance = tokens.opacityTouchTolerance,
        toolButtonSize = tokens.toolButtonSize,
    )

internal fun radialMenuInputHalfThickness(
    opacityTouchTolerance: Dp,
    toolButtonSize: Dp,
): Dp = minOf(opacityTouchTolerance, toolButtonSize / 2)

private data class MainToolSlot(
    val index: Int,
    val tool: ReaderTool,
)

private fun mainToolSlots(canGrade: Boolean): List<MainToolSlot> = buildList {
    add(MainToolSlot(1, ReaderTool.PARTIAL_ERASER))
    add(MainToolSlot(2, ReaderTool.PEN))
    add(MainToolSlot(3, ReaderTool.HIGHLIGHTER))
    if (canGrade) add(MainToolSlot(5, ReaderTool.GRADE))
}

internal fun radialItemAngleDegrees(index: Int, itemCount: Int, sweepAngleDegrees: Float): Float =
    if (itemCount <= 1) FanStartDegrees
    else FanStartDegrees + (index / (itemCount - 1f)) * sweepAngleDegrees

internal fun isToolExtensionGestureArmed(
    startedTool: ReaderTool?,
    currentTool: ReaderTool?,
): Boolean = startedTool != null && startedTool == currentTool

/** Student memo creation replaces the teacher-only grade slot, so the main ring stays stable. */
private fun mainMenuItemCount(state: ReaderUiState) = 7

/**
 * Whether an S Pen side button is held down, read straight from the platform event.
 *
 * Compose cannot answer this: on Android it folds BUTTON_STYLUS_PRIMARY into isPrimaryPressed, the
 * flag the pen tip already raises, so a Compose-level check either misses the button or fires on
 * every stroke. Both windows that watch for the button share this one reading.
 */
internal fun MotionEvent.stylusSideButtonDown(): Boolean {
    val mask = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY
    if (buttonState and mask != 0) return true
    return actionMasked == MotionEvent.ACTION_BUTTON_PRESS && actionButton and mask != 0
}

@Composable
fun StylusToolMenu(
    expanded: Boolean,
    anchorInHost: Offset,
    state: ReaderUiState,
    selectedTool: ReaderTool,
    selectedColorArgb: Int,
    selectedWidthDp: Float,
    selectedOpacity: Float,
    onSelectTool: (ReaderTool) -> Unit,
    onSelectColor: (Int) -> Unit,
    onSelectWidth: (Float) -> Unit,
    onSelectOpacity: (Float) -> Unit,
    onCreateMemo: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onInputRegionChanged: (StylusMenuInputRegion?) -> Unit,
) {
    val tokens = readerChromeTokens(state.role)
    var menuPage by remember(expanded) { mutableStateOf(RadialMenuPage.MAIN) }
    var forcedHoveredTool by remember(expanded) { mutableStateOf<ReaderTool?>(null) }
    val reportInputRegion by rememberUpdatedState(onInputRegionChanged)
    LaunchedEffect(menuPage) { forcedHoveredTool = null }
    DisposableEffect(expanded) {
        if (!expanded) reportInputRegion(null)
        onDispose { reportInputRegion(null) }
    }
    if (!expanded) return

    MaterialTheme {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val density = LocalDensity.current
            // Every page uses one polar frame. Eight slots are the busiest layout (seven colours
            // plus Back), so deriving the frame from that page keeps R, a, b, c and the viewport
            // invariant while MAIN/COLORS/PEN are exchanged.
            val geometry = commonRadialMenuGeometry(tokens)
            val penAngles = penMenuAngleSpec()
            val reveal = radialToolRevealGeometry(
                fanRadius = geometry.radius,
                toolButtonSize = tokens.toolButtonSize,
                protrusionDistance = tokens.toolProtrusionDistance,
            )
            val viewportOriginPx = Offset(
                x = with(density) { geometry.originX.toPx() },
                y = with(density) { geometry.originY.toPx() },
            )
            val viewportSizePx = Offset(
                x = with(density) { geometry.menuWidth.toPx() },
                y = with(density) { geometry.menuHeight.toPx() },
            )
            val hostSizePx = Offset(
                x = with(density) { maxWidth.toPx() },
                y = with(density) { maxHeight.toPx() },
            )
            val safeAnchor = anchorInHost.takeIf { it.x.isFinite() && it.y.isFinite() }
                ?: Offset(hostSizePx.x / 2f, hostSizePx.y / 2f)
            val preferredOrigin = stylusAnchoredFanOrigin(
                stylusPoint = safeAnchor,
                innerRadiusPx = with(density) { reveal.a.toPx() },
                middleAngleDegrees = FanStartDegrees + FanSweepDegrees / 2f,
                anchorRadiusFraction = tokens.stylusAnchorRadiusFraction,
            )
            val menuTopLeft = clampRadialMenuTopLeft(
                preferred = preferredOrigin - viewportOriginPx,
                viewportWidthPx = viewportSizePx.x,
                viewportHeightPx = viewportSizePx.y,
                hostWidthPx = hostSizePx.x,
                hostHeightPx = hostSizePx.y,
            )
            val actualOrigin = menuTopLeft + viewportOriginPx
            val mainSlots = mainToolSlots(state.capabilities.canGrade)
            val mainItemCount = mainMenuItemCount(state)
            val pageItemAngles = when (menuPage) {
                RadialMenuPage.MAIN -> List(mainItemCount) { index ->
                    radialItemAngleDegrees(index, mainItemCount, FanSweepDegrees)
                }
                RadialMenuPage.COLORS -> List(COMMON_RADIAL_MENU_ITEM_COUNT) { index ->
                    radialItemAngleDegrees(index, COMMON_RADIAL_MENU_ITEM_COUNT, FanSweepDegrees)
                }
                RadialMenuPage.PEN -> penAngles.widthAnglesDegrees + penAngles.backDegrees
            }
            val toolAngles = if (menuPage == RadialMenuPage.MAIN) {
                mainSlots.map { slot ->
                    radialItemAngleDegrees(slot.index, mainItemCount, FanSweepDegrees)
                }
            } else {
                emptyList()
            }
            val originInViewportPx = viewportOriginPx
            val aPx = with(density) { reveal.a.toPx() }
            val bPx = with(density) { reveal.b.toPx() }
            val cPx = with(density) { reveal.c.toPx() }
            val toolHalfWidthPx = with(density) { (tokens.toolButtonSize / 2).toPx() }

            fun extensionToolAt(position: Offset): ReaderTool? {
                if (menuPage != RadialMenuPage.MAIN) return null
                val dx = position.x - originInViewportPx.x
                val dy = position.y - originInViewportPx.y
                return mainSlots.firstOrNull { slot ->
                    val angle = Math.toRadians(
                        radialItemAngleDegrees(slot.index, mainItemCount, FanSweepDegrees).toDouble(),
                    )
                    val directionX = cos(angle).toFloat()
                    val directionY = sin(angle).toFloat()
                    val radial = dx * directionX + dy * directionY
                    val tangential = abs(-dx * directionY + dy * directionX)
                    radial > bPx && radial <= cPx && tangential <= toolHalfWidthPx
                }?.tool
            }

            fun activateExtension(tool: ReaderTool) {
                forcedHoveredTool = null
                if (tool == ReaderTool.PEN && selectedTool == ReaderTool.PEN) {
                    menuPage = RadialMenuPage.PEN
                } else {
                    onSelectTool(tool)
                }
            }

            val inputRegion = StylusMenuInputRegion(
                originX = actualOrigin.x,
                originY = actualOrigin.y,
                a = aPx,
                b = bPx,
                c = cPx,
                startAngleDegrees = FanStartDegrees,
                endAngleDegrees = FanStartDegrees + FanSweepDegrees,
                itemAnglesDegrees = pageItemAngles,
                itemCenterRadius = with(density) { geometry.radius.toPx() },
                itemHitRadius = toolHalfWidthPx,
                toolCorridorAnglesDegrees = toolAngles,
                toolCorridorHalfWidth = toolHalfWidthPx,
            )
            SideEffect {
                // Geometry is computed from the same immutable frame used to place every item, so
                // the host can own the A/B/C region as soon as this page is composed. Waiting for a
                // global-position callback left the visible fan without any Android input region
                // on Samsung after some menu opens/page swaps.
                reportInputRegion(inputRegion)
            }

            CompositionLocalProvider(LocalPenRestingAlpha provides tokens.menuRestingAlpha) {
                Box(
                    modifier = Modifier
                        .absoluteOffset(
                            x = with(density) { menuTopLeft.x.toDp() },
                            y = with(density) { menuTopLeft.y.toDp() },
                        )
                        .size(
                            width = geometry.menuWidth,
                            height = geometry.menuHeight,
                        )
                        .pointerInput(menuPage, actualOrigin, aPx, bPx, cPx, toolAngles) {
                            var activePointerId: PointerId? = null
                            var startedTool: ReaderTool? = null
                            var armed = false
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Final)
                                    val change = event.changes.firstOrNull { it.type == PointerType.Stylus }
                                    if (change == null) {
                                        forcedHoveredTool = null
                                        activePointerId = null
                                        startedTool = null
                                        armed = false
                                        continue
                                    }
                                    val extensionTool = extensionToolAt(change.position)
                                    if (activePointerId == null) {
                                        if (!change.previousPressed && change.pressed && extensionTool != null) {
                                            activePointerId = change.id
                                            startedTool = extensionTool
                                            armed = true
                                            forcedHoveredTool = extensionTool
                                        } else if (!change.pressed) {
                                            forcedHoveredTool = extensionTool
                                        }
                                    } else if (change.id == activePointerId) {
                                        if (!change.pressed) {
                                            val toolToActivate = startedTool?.takeIf {
                                                armed && isToolExtensionGestureArmed(it, extensionTool)
                                            }
                                            activePointerId = null
                                            startedTool = null
                                            armed = false
                                            forcedHoveredTool = extensionTool
                                            if (change.previousPressed && toolToActivate != null) {
                                                activateExtension(toolToActivate)
                                            }
                                        } else {
                                            armed = isToolExtensionGestureArmed(startedTool, extensionTool)
                                            forcedHoveredTool = extensionTool
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
                            selectedTool = selectedTool,
                            selectedColorArgb = selectedColorArgb,
                            onPenClick = {
                                if (selectedTool == ReaderTool.PEN) {
                                    menuPage = RadialMenuPage.PEN
                                } else {
                                    onSelectTool(ReaderTool.PEN)
                                }
                            },
                            onOpenColors = { menuPage = RadialMenuPage.COLORS },
                            onCreateMemo = onCreateMemo,
                            onSelectTool = onSelectTool,
                            onUndo = onUndo,
                            onRedo = onRedo,
                            forceHoveredToolForPreview = forcedHoveredTool,
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
                            onBack = { menuPage = RadialMenuPage.MAIN },
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
    onCreateMemo: () -> Unit,
    onSelectTool: (ReaderTool) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    previewStatic: Boolean = false,
    forceHoveredToolForPreview: ReaderTool? = null,
) {
    val tokens = readerChromeTokens(state.role)
    val canGrade = state.capabilities.canGrade
    val paletteIndex = if (canGrade) 4 else 5
    val redoIndex = 6
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
                radialRadius = geometry.radius,
            )
            index == 2 -> ToolPenButton(
                description = "펜",
                toolItemRes = R.drawable.ic_tool_pen_item,
                onAction = onPenClick,
                role = state.role,
                selected = selectedTool == ReaderTool.PEN,
                forceHoveredForPreview = forceHoveredToolForPreview == ReaderTool.PEN,
                radialAngleDegrees = angleDegrees,
                radialRadius = geometry.radius,
            )
            index == 3 -> ToolPenButton(
                description = "형광펜",
                toolItemRes = R.drawable.ic_tool_highlighter_item,
                onAction = { onSelectTool(ReaderTool.HIGHLIGHTER) },
                role = state.role,
                selected = selectedTool == ReaderTool.HIGHLIGHTER,
                forceHoveredForPreview = forceHoveredToolForPreview == ReaderTool.HIGHLIGHTER,
                radialAngleDegrees = angleDegrees,
                radialRadius = geometry.radius,
            )
            !canGrade && index == 4 -> RadialActionButton(
                iconRes = R.drawable.ic_memo,
                label = "메모 만들기",
                enabled = state.documentReady && state.storageAvailable &&
                    !state.submissionInProgress,
                role = state.role,
                onClick = onCreateMemo,
            )
            index == paletteIndex -> PaletteButton(
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
                radialRadius = geometry.radius,
            )
            index == redoIndex -> RadialActionButton(
                iconRes = R.drawable.ic_redo,
                label = "다시 실행",
                enabled = state.canRedo,
                role = state.role,
            ) { onRedo() }
            else -> Unit
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
        Triple(tokens.paletteInk, "먹색", tokens.paletteInk.toArgb()),
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
            RadialActionButton(
                iconRes = R.drawable.ic_page_prev,
                label = "도구 메뉴로 돌아가기",
                role = role,
                onClick = onBack,
            )
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
    onBack: () -> Unit,
    previewStatic: Boolean = false,
) {
    val tokens = readerChromeTokens(role)
    val widths = PEN_WIDTH_CHOICES_DP
    val angles = penMenuAngleSpec()
    CurvedOpacitySlider(
        color = Color(selectedColorArgb),
        widthDp = selectedWidthDp,
        opacity = selectedOpacity,
        centerX = geometry.originX,
        centerY = geometry.originY,
        radius = geometry.radius,
        startAngle = angles.opacityStartDegrees,
        sweepAngle = angles.opacityEndDegrees - angles.opacityStartDegrees,
        angleHitTolerance = angles.opacityHitToleranceDegrees,
        tokens = tokens,
        onOpacityChange = onSelectOpacity,
        modifier = Modifier.fillMaxSize(),
    )
    RadialFanAtAngles(
        anglesDegrees = angles.widthAnglesDegrees + angles.backDegrees,
        geometry = geometry,
        itemSize = tokens.toolButtonSize,
        animationKey = "pen-settings",
        entranceStartScale = tokens.radialEntranceStartScale,
        dampingRatio = tokens.springDampingRatio,
        stiffness = tokens.springStiffness,
        staticProgress = if (previewStatic) 1f else null,
    ) { index, _ ->
        if (index < widths.size) {
            StrokeWidthChoice(
                widthDp = widths[index],
                selectedWidthDp = selectedWidthDp,
                role = role,
                onSelect = onSelectWidth,
            )
        } else {
            RadialActionButton(
                iconRes = R.drawable.ic_page_prev,
                label = "도구 메뉴로 돌아가기",
                role = role,
                onClick = onBack,
            )
        }
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
    val angles = List(itemCount) { index ->
        radialItemAngleDegrees(index, itemCount, sweepAngleDegrees) +
            (startAngleDegrees - FanStartDegrees)
    }
    RadialFanAtAngles(
        anglesDegrees = angles,
        geometry = geometry,
        itemSize = itemSize,
        animationKey = animationKey,
        entranceStartScale = entranceStartScale,
        dampingRatio = dampingRatio,
        stiffness = stiffness,
        staticProgress = staticProgress,
        content = content,
    )
}

@Composable
private fun RadialFanAtAngles(
    anglesDegrees: List<Float>,
    geometry: RadialFanGeometry,
    itemSize: Dp,
    animationKey: Any,
    entranceStartScale: Float,
    dampingRatio: Float,
    stiffness: Float,
    staticProgress: Float? = null,
    content: @Composable (index: Int, angleDegrees: Float) -> Unit,
) {
    val progress = remember(animationKey, anglesDegrees) { Animatable(0f) }
    if (staticProgress == null) {
        LaunchedEffect(animationKey, anglesDegrees) {
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
    val visibleProgress = renderedProgress.coerceIn(0f, 1f)

    anglesDegrees.forEachIndexed { index, angleDegrees ->
        val center = radialItemCenter(
            originX = geometry.originX,
            originY = geometry.originY,
            radius = geometry.radius,
            angleDegrees = angleDegrees,
        )
        val itemLeft = center.x - itemSize / 2
        val itemTop = center.y - itemSize / 2
        Box(
            modifier = Modifier
                // The interactive slot is laid out at its final polar coordinate from the first
                // frame. Entrance motion changes only scale/alpha, so rendering and hit-testing
                // can never disagree about the fan centre.
                .absoluteOffset(x = itemLeft, y = itemTop)
                .size(itemSize)
                .graphicsLayer {
                    // Alpha normally promotes the 48/60dp slot to an offscreen layer while the
                    // fan enters, which can crop artwork protruding beyond that slot. Each slot
                    // has a single child, so direct alpha modulation preserves the overflow.
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    val scale = entranceStartScale + (1f - entranceStartScale) * renderedProgress
                    scaleX = scale
                    scaleY = scale
                    alpha = visibleProgress
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
    angleHitTolerance: Float,
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
    val touchTolerance = with(density) { radialMenuInputHalfThickness(tokens).toPx() }
    fun opacityAt(position: Offset): Float? {
        var angle = (atan2(position.y - center.y, position.x - center.x) * 180f / PI.toFloat())
        if (angle < 0f) angle += 360f
        if (angle < 90f) angle += 360f
        val distance = hypot(position.x - center.x, position.y - center.y)
        if (kotlin.math.abs(distance - radiusPx) > touchTolerance) return null
        if (angle < startAngle - angleHitTolerance ||
            angle > startAngle + sweepAngle + angleHitTolerance
        ) return null
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
    val geometry = commonRadialMenuGeometry(tokens)
    ReaderDevicePreviewFrame(state) {
        CompositionLocalProvider(LocalPenRestingAlpha provides tokens.menuRestingAlpha) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .size(width = geometry.menuWidth, height = geometry.menuHeight),
            ) {
                when (page) {
                    StylusMenuPreviewPage.TOOLS -> MainRadialMenu(
                        state = state,
                        geometry = geometry,
                        selectedTool = ReaderTool.PEN,
                        selectedColorArgb = tokens.paletteBlue.toArgb(),
                        onPenClick = {},
                        onOpenColors = {},
                        onCreateMemo = {},
                        onSelectTool = {},
                        onUndo = {},
                        onRedo = {},
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
                        selectedWidthDp = DEFAULT_PEN_WIDTH_DP,
                        selectedOpacity = 0.72f,
                        onSelectWidth = {},
                        onSelectOpacity = {},
                        onBack = {},
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
