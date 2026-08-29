package com.studyink.reader

import android.content.Context
import android.util.Log
import android.view.MotionEvent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import com.studyink.core.model.MarkColor
import com.studyink.sync.lan.LanConnectionState
import com.studyink.core.model.Mark
import com.studyink.core.model.MarkGroup
import com.studyink.core.model.PagePoint
import com.studyink.core.model.ResultBundleGrid
import com.studyink.core.model.TEACHER_PAGE_REVIEW_ATTEMPT_NO
import com.studyink.core.model.resultBundleGrid
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

enum class PenButtonSurfaceStyle { DEFAULT, FILLED, GHOST }

/**
 * Resting opacity override for pen buttons. The top chrome deliberately sits faint over the page
 * until the pen hovers it; a menu the user popped open has no reason to hide, so it provides
 * [ReaderChromeTokens.menuRestingAlpha] here instead. `null` means "use the chrome default".
 */
val LocalPenRestingAlpha = compositionLocalOf<Float?> { null }

private data class ReaderPaperSpeck(
    val center: Offset,
    val radius: Float,
    val color: Color,
)

private data class ReaderPaperFiber(
    val start: Offset,
    val end: Offset,
    val strokeWidth: Float,
    val color: Color,
)

/** Stable pseudo-random value so the paper grain never shimmers while Compose redraws it. */
private fun readerPaperNoise(index: Int, channel: Int): Float {
    val raw = sin((index + 1) * 12.9898 + (channel + 1) * 78.233) * 43_758.5453
    return (raw - floor(raw)).toFloat()
}

private fun Modifier.readerPaperTexture(
    tokens: ReaderChromeTokens,
    seed: Int,
): Modifier = drawWithCache {
    val texture = tokens.paperTexture
    val oneDp = 1.dp.toPx()
    val areaDp = (size.width / oneDp) * (size.height / oneDp)
    val speckCount = (areaDp / texture.areaPerSpeck)
        .roundToInt()
        .coerceIn(texture.minSpecks, texture.maxSpecks)
    val fiberCount = (areaDp / texture.areaPerFiber)
        .roundToInt()
        .coerceIn(texture.minFibers, texture.maxFibers)
    val channel = (seed and 0x3ff) * 181
    val wash = Brush.linearGradient(
        colors = listOf(
            tokens.paperHighlight.copy(alpha = texture.washLightAlpha),
            Color.Transparent,
            tokens.paperStroke.copy(alpha = texture.washShadeAlpha),
        ),
        start = Offset.Zero,
        end = Offset(size.width, size.height),
    )
    val minSpeck = texture.speckMinRadius.toPx()
    val speckRange = texture.speckMaxRadius.toPx() - minSpeck
    val specks = List(speckCount) { index ->
        val light = readerPaperNoise(index, 113 + channel) > 0.68f
        ReaderPaperSpeck(
            center = Offset(
                x = readerPaperNoise(index, 127 + channel) * size.width,
                y = readerPaperNoise(index, 131 + channel) * size.height,
            ),
            radius = minSpeck + readerPaperNoise(index, 137 + channel) * speckRange,
            color = if (light) {
                tokens.paperHighlight.copy(alpha = texture.lightGrainAlpha)
            } else {
                tokens.paperStroke.copy(alpha = texture.darkGrainAlpha)
            },
        )
    }
    val minFiberLength = texture.fiberMinLength.toPx()
    val fiberLengthRange = texture.fiberMaxLength.toPx() - minFiberLength
    val minFiberWidth = texture.fiberMinWidth.toPx()
    val fiberWidthRange = texture.fiberMaxWidth.toPx() - minFiberWidth
    val fibers = List(fiberCount) { index ->
        val start = Offset(
            x = readerPaperNoise(index, 151 + channel) * size.width,
            y = readerPaperNoise(index, 157 + channel) * size.height,
        )
        val length = minFiberLength + readerPaperNoise(index, 163 + channel) * fiberLengthRange
        val slope = (readerPaperNoise(index, 167 + channel) - 0.5f) * texture.fiberSlope
        ReaderPaperFiber(
            start = start,
            end = Offset(
                x = (start.x + length).coerceAtMost(size.width),
                y = (start.y + length * slope).coerceIn(0f, size.height),
            ),
            strokeWidth = minFiberWidth +
                readerPaperNoise(index, 173 + channel) * fiberWidthRange,
            color = tokens.paperShade.copy(alpha = texture.fiberAlpha),
        )
    }

    onDrawBehind {
        drawRect(brush = wash)
        fibers.forEach { fiber ->
            drawLine(fiber.color, fiber.start, fiber.end, fiber.strokeWidth)
        }
        specks.forEach { speck ->
            drawCircle(speck.color, speck.radius, speck.center)
        }
    }
}

@Composable
internal fun PenInteractionTarget(
    description: String,
    onAction: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier,
    circularHitTest: Boolean = false,
    forceHoveredForPreview: Boolean = false,
    content: @Composable BoxScope.(hovered: Boolean, pressed: Boolean) -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    var gestureStartedInside by remember { mutableStateOf(false) }
    var componentSize by remember { mutableStateOf(IntSize.Zero) }
    fun isInside(x: Float, y: Float): Boolean {
        if (x < 0f || y < 0f || x >= componentSize.width || y >= componentSize.height) return false
        if (!circularHitTest) return true
        val dx = x - componentSize.width / 2f
        val dy = y - componentSize.height / 2f
        val radius = minOf(componentSize.width, componentSize.height) / 2f
        return dx * dx + dy * dy <= radius * radius
    }
    fun isInside(event: MotionEvent): Boolean = isInside(event.x, event.y)
    val effectiveHovered = enabled && (hovered || forceHoveredForPreview)
    Box(
        modifier = modifier
            .semantics { contentDescription = description }
            .onSizeChanged { componentSize = it }
            // Hover is handled by Compose's pointer event path. Android's interop filter below is
            // deliberately touch-only because it is not guaranteed to receive ACTION_HOVER_*.
            .pointerInput(enabled) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val stylus = event.changes.firstOrNull { it.type == PointerType.Stylus }
                        if (stylus != null) {
                            val nextHovered = enabled && event.type != PointerEventType.Exit &&
                                isInside(stylus.position.x, stylus.position.y)
                            if (nextHovered != hovered) {
                                Log.d(PEN_INPUT_LOG_TAG, "hover target=$description active=$nextHovered")
                            }
                            hovered = nextHovered
                        }
                    }
                }
            }
            .pointerInteropFilter { event ->
                // Hover belongs to the Compose pointer path above. Consuming it here prevents
                // PointerEventType.Enter/Move/Exit from reaching that path on Samsung devices.
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
                        gestureStartedInside = enabled && isInside(event)
                        armed = gestureStartedInside
                        Log.d(
                            PEN_INPUT_LOG_TAG,
                            "down target=$description inside=$gestureStartedInside x=${event.x} y=${event.y} size=$componentSize",
                        )
                    }
                    // Keep the initial-down contract, but re-arm when the pen re-enters.
                    MotionEvent.ACTION_MOVE -> armed = gestureStartedInside && enabled && isInside(event)
                    MotionEvent.ACTION_UP -> {
                        val activate = gestureStartedInside && enabled && isInside(event)
                        Log.d(
                            PEN_INPUT_LOG_TAG,
                            "up target=$description activate=$activate x=${event.x} y=${event.y}",
                        )
                        if (activate) onAction()
                        armed = false
                        gestureStartedInside = false
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        armed = false
                        gestureStartedInside = false
                    }
                }
                true
            }
            // The interop handler consumes S Pen taps so they retain the precise down/up hit
            // contract above. Finger taps pass through to this Compose click target, which also
            // supplies keyboard and TalkBack button semantics.
            .clickable(enabled = enabled, role = Role.Button, onClick = onAction),
        contentAlignment = Alignment.Center,
    ) {
        content(effectiveHovered, armed)
    }
}

private const val PEN_INPUT_LOG_TAG = "MasterNotePenInput"

@Composable
private fun AnimatedPenSurface(
    hovered: Boolean,
    pressed: Boolean,
    enabled: Boolean,
    selected: Boolean,
    role: ReaderRole,
    /** `null` sizes the surface to its own content, floored at [minVisualWidth]. */
    visualWidth: Dp?,
    visualHeight: Dp,
    shape: Shape,
    style: PenButtonSurfaceStyle,
    modifier: Modifier = Modifier,
    minVisualWidth: Dp = 0.dp,
    backgroundOverride: Color? = null,
    textureSeed: Int = 0,
    content: @Composable BoxScope.() -> Unit,
) {
    val tokens = readerChromeTokens(role)
    val targetAlpha = if (!enabled) tokens.disabledAlpha else if (hovered || selected) {
        tokens.hoveredAlpha
    } else {
        LocalPenRestingAlpha.current ?: tokens.defaultAlpha
    }
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(tokens.fadeDurationMillis),
        label = "pen-button-alpha",
    )
    val hoverScale by animateFloatAsState(
        targetValue = if (hovered && enabled) tokens.hoverScale else 1f,
        animationSpec = spring(tokens.springDampingRatio, tokens.springStiffness),
        label = "pen-button-hover-scale",
    )
    val pressedScaleY by animateFloatAsState(
        targetValue = if (pressed && enabled) tokens.pressedScaleY else 1f,
        animationSpec = spring(tokens.springDampingRatio, tokens.springStiffness),
        label = "pen-button-pressed-scale",
    )
    val elevation by animateDpAsState(
        targetValue = when {
            style == PenButtonSurfaceStyle.GHOST -> 0.dp
            hovered && enabled -> tokens.hoveredElevation
            else -> tokens.restingElevation
        },
        animationSpec = spring(tokens.springDampingRatio, tokens.springStiffness),
        label = "pen-button-elevation",
    )
    val paperSurface = style != PenButtonSurfaceStyle.GHOST
    val background by animateColorAsState(
        targetValue = when {
            !enabled -> tokens.disabledBackground
            style == PenButtonSurfaceStyle.GHOST -> Color.Transparent
            backgroundOverride != null -> backgroundOverride
            hovered -> tokens.paperHoverSurface
            else -> tokens.paperSurface
        },
        animationSpec = tween(tokens.fadeDurationMillis),
        label = "pen-button-paper-color",
    )
    val rimColor by animateColorAsState(
        targetValue = when {
            !paperSurface -> Color.Transparent
            hovered && enabled -> tokens.paperHoverRim
            selected -> tokens.paperSelectedRim
            else -> tokens.paperStroke
        },
        animationSpec = tween(tokens.fadeDurationMillis),
        label = "pen-button-paper-rim",
    )
    val foreground = tokens.buttonForeground
    val sizing = if (visualWidth != null) {
        Modifier.size(width = visualWidth, height = visualHeight)
    } else {
        Modifier.height(visualHeight).widthIn(min = minVisualWidth)
    }
    Surface(
        modifier = modifier
            .then(sizing)
            .alpha(alpha)
            .graphicsLayer {
                scaleX = hoverScale
                scaleY = hoverScale * pressedScaleY
            }
            .then(
                if (paperSurface) {
                    Modifier.shadow(
                        elevation = elevation,
                        shape = shape,
                        clip = false,
                        ambientColor = tokens.paperShadow,
                        spotColor = tokens.paperShadow,
                    )
                } else {
                    Modifier
                }
            ),
        shape = shape,
        color = background,
        contentColor = foreground,
        border = if (paperSurface) BorderStroke(tokens.paperStrokeWidth, rimColor) else null,
        shadowElevation = 0.dp,
    ) {
        // A content-sized surface must not let its child fill the row's leftover width.
        val inner = if (visualWidth != null) Modifier.fillMaxSize() else Modifier.fillMaxHeight()
        Box(
            modifier = inner
                .then(if (paperSurface) Modifier.clip(shape) else Modifier)
                .then(
                    if (paperSurface) {
                        Modifier.background(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    tokens.paperHighlight,
                                    Color.Transparent,
                                    tokens.paperShade,
                                ),
                            ),
                            shape = shape,
                        )
                    } else {
                        Modifier
                    }
                )
                .then(
                    if (paperSurface) {
                        Modifier.readerPaperTexture(tokens = tokens, seed = textureSeed)
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

@Composable
fun IconPenButton(
    description: String,
    iconRes: Int?,
    onAction: () -> Unit,
    role: ReaderRole,
    visualSize: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    style: PenButtonSurfaceStyle = PenButtonSurfaceStyle.DEFAULT,
    backgroundOverride: Color? = null,
    forceHoveredForPreview: Boolean = false,
    content: (@Composable BoxScope.() -> Unit)? = null,
) {
    val tokens = readerChromeTokens(role)
    PenInteractionTarget(
        description = description,
        onAction = onAction,
        enabled = enabled,
        // A circle wider than its own layout box would spill over its neighbours and leave
        // its rim untappable, so the box follows the larger of the two.
        modifier = modifier.size(visualSize.coerceAtLeast(tokens.minimumTouchSize)),
        circularHitTest = true,
        forceHoveredForPreview = forceHoveredForPreview,
    ) { hovered, pressed ->
        AnimatedPenSurface(
            hovered = hovered,
            pressed = pressed,
            enabled = enabled,
            selected = selected,
            role = role,
            visualWidth = visualSize,
            visualHeight = visualSize,
            shape = CircleShape,
            style = style,
            backgroundOverride = backgroundOverride,
            textureSeed = description.hashCode(),
        ) {
            if (content != null) {
                content()
            } else if (iconRes != null) {
                Image(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(visualSize * tokens.iconScale),
                    colorFilter = ColorFilter.tint(tokens.buttonForeground),
                )
            }
        }
    }
}

@Composable
fun PrimaryPenButton(
    text: String,
    description: String,
    iconRes: Int,
    onAction: () -> Unit,
    role: ReaderRole,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    forceHoveredForPreview: Boolean = false,
) {
    val tokens = readerChromeTokens(role)
    PenInteractionTarget(
        description = description,
        onAction = onAction,
        enabled = enabled,
        modifier = modifier
            .widthIn(min = tokens.primaryMinWidth)
            .height(tokens.minimumTouchSize),
        forceHoveredForPreview = forceHoveredForPreview,
    ) { hovered, pressed ->
        AnimatedPenSurface(
            hovered = hovered,
            pressed = pressed,
            enabled = enabled,
            selected = false,
            role = role,
            visualWidth = null,
            visualHeight = tokens.generalButtonSize,
            shape = RoundedCornerShape(tokens.cornerRadius),
            style = PenButtonSurfaceStyle.FILLED,
            minVisualWidth = tokens.primaryMinWidth,
            textureSeed = description.hashCode(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = tokens.primaryHorizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(tokens.primaryContentGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(tokens.generalButtonSize * tokens.iconScale),
                    colorFilter = ColorFilter.tint(tokens.buttonForeground),
                )
                Text(text = text, maxLines = 1, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun StatusChip(
    text: String,
    role: ReaderRole,
    modifier: Modifier = Modifier,
    description: String = "현재 풀이 상태",
    onAction: (() -> Unit)? = null,
) {
    val tokens = readerChromeTokens(role)
    val content: @Composable () -> Unit = {
        Surface(
            modifier = Modifier.height(tokens.generalButtonSize),
            shape = RoundedCornerShape(tokens.cornerRadius),
            color = tokens.statusBackground,
            contentColor = tokens.statusForeground,
            shadowElevation = 0.dp,
        ) {
            Box(
                modifier = Modifier.padding(horizontal = tokens.statusHorizontalPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
    if (onAction == null) {
        Box(modifier = modifier.semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
            content()
        }
    } else {
        PenInteractionTarget(
            description = description,
            onAction = onAction,
            modifier = modifier.height(tokens.minimumTouchSize),
        ) { _, _ -> content() }
    }
}

@Composable
fun ToolPenButton(
    description: String,
    toolItemRes: Int,
    onAction: () -> Unit,
    role: ReaderRole,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    forceHoveredForPreview: Boolean = false,
    radialAngleDegrees: Float = 270f,
    radialRadius: Dp? = null,
) {
    val tokens = readerChromeTokens(role)
    val isEraser = description == "지우개"
    val toolImageWidth = tokens.toolImageWidth *
        if (isEraser) tokens.eraserImageWidthScale else 1f
    val toolImageHeight = tokens.toolImageHeight *
        if (isEraser) tokens.eraserImageHeightScale else 1f
    val artworkBottomPaddingFraction = if (isEraser) {
        tokens.eraserArtworkBottomPaddingFraction
    } else {
        tokens.toolArtworkBottomPaddingFraction
    }
    val revealGeometry = radialToolRevealGeometry(
        fanRadius = radialRadius ?: tokens.radialMinRadius,
        toolButtonSize = tokens.toolButtonSize,
        protrusionDistance = tokens.toolProtrusionDistance,
    )
    PenInteractionTarget(
        description = description,
        onAction = onAction,
        enabled = enabled,
        modifier = modifier.size(tokens.toolButtonSize.coerceAtLeast(tokens.minimumTouchSize)),
        circularHitTest = true,
        forceHoveredForPreview = forceHoveredForPreview,
    ) { hovered, pressed ->
        val targetAlpha = when {
            !enabled -> tokens.disabledAlpha
            hovered || selected -> tokens.hoveredAlpha
            else -> LocalPenRestingAlpha.current ?: tokens.defaultAlpha
        }
        val artworkAlpha by animateFloatAsState(
            targetValue = targetAlpha,
            animationSpec = tween(tokens.fadeDurationMillis),
            label = "tool-alpha",
        )
        val hoverScale by animateFloatAsState(
            targetValue = if (hovered && enabled) tokens.hoverScale else 1f,
            animationSpec = spring(tokens.springDampingRatio, tokens.springStiffness),
            label = "tool-hover-scale",
        )
        val pressedScaleY by animateFloatAsState(
            targetValue = if (pressed && enabled) tokens.pressedScaleY else 1f,
            animationSpec = spring(tokens.springDampingRatio, tokens.springStiffness),
            label = "tool-pressed-scale",
        )
        val revealDepth by animateDpAsState(
            targetValue = if (hovered || selected) {
                revealGeometry.extractedTipDepth
            } else {
                revealGeometry.restingTipDepth
            },
            animationSpec = spring(tokens.springDampingRatio, tokens.springStiffness),
            label = "tool-reveal",
        )
        val toolTipFromImageTop = toolImageHeight * (1f - artworkBottomPaddingFraction)
        val translationYPx = with(LocalDensity.current) { revealDepth.toPx() }

        // In this local coordinate system +Y points away from the fan centre. The viewport's top
        // is radius a (the old slot's inner edge), rest puts the tip at b, and hover/selection puts
        // it at c. The viewport itself ends at c, so spring overshoot cannot revive the old
        // percentage-based over-extraction.
        // Rotate the local coordinate system around the slot centre first. The reveal-window
        // direction must then follow local +Y; doing this outside the rotated parent would move
        // diagonal tools vertically instead of radially.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = radialAngleDegrees - 90f },
        ) {
            Layout(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .requiredSize(width = toolImageWidth, height = revealGeometry.viewportLength)
                    .graphicsLayer {
                        // Anchor scaling at c so hover enlargement does not change the tip radius.
                        transformOrigin = TransformOrigin(0.5f, 1f)
                        scaleX = hoverScale
                        scaleY = hoverScale * pressedScaleY
                        alpha = artworkAlpha
                    }
                    .clip(RectangleShape),
                content = {
                    Image(
                        painter = painterResource(toolItemRes),
                        contentDescription = null,
                        modifier = Modifier.graphicsLayer { translationY = translationYPx },
                    )
                },
            ) { measurables, constraints ->
                val imageWidthPx = toolImageWidth.roundToPx()
                val imageHeightPx = toolImageHeight.roundToPx()
                val tipFromTopPx = toolTipFromImageTop.roundToPx()
                val image = measurables.single().measure(
                    Constraints.fixed(imageWidthPx, imageHeightPx),
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    image.placeRelative(
                        x = (constraints.maxWidth - imageWidthPx) / 2,
                        y = -tipFromTopPx,
                    )
                }
            }
        }
    }
}

@Composable
fun ReaderTopChrome(
    state: ReaderUiState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onExitToLibrary: () -> Unit,
    onSubmit: () -> Unit,
    onPreviousAttempt: () -> Unit,
    onNextAttempt: () -> Unit,
    onPublish: () -> Unit,
    onDismissDataError: () -> Unit,
    onSelectAttempt: (Int) -> Unit = {},
    onShowStudentActivity: () -> Unit = {},
    onResumeStudentFollow: () -> Unit = {},
    onOpenRemoteMonitor: () -> Unit = {},
    onOpenGptAssistant: () -> Unit = {},
    onOpenAnswerPdf: () -> Unit = {},
    previewHoveredDescription: String? = null,
    /**
     * Optional compact, one-line page/attempt history. The caller owns its data and gestures so
     * this chrome never needs to open a second row or popup. When absent, the production summary
     * is derived directly from [ReaderUiState.marks].
     */
    markHistoryContent: (@Composable () -> Unit)? = null,
) {
    if (shouldUseS23UltraTopStrip(state.role)) {
        S23UltraTopStrip(
            state = state,
            onPrevious = onPrevious,
            onNext = onNext,
            onExitToLibrary = onExitToLibrary,
            onPreviousAttempt = onPreviousAttempt,
            onNextAttempt = onNextAttempt,
            onPublish = onPublish,
            onDismissDataError = onDismissDataError,
            onSelectAttempt = onSelectAttempt,
            onShowStudentActivity = onShowStudentActivity,
            onResumeStudentFollow = onResumeStudentFollow,
            onOpenGptAssistant = onOpenGptAssistant,
            onOpenAnswerPdf = onOpenAnswerPdf,
            transportCellModel = s23TransportCellModelForHybrid(
                decision = state.hybridLink,
                legacyLanConnection = state.liveConnection,
                telegramUnreadCount = state.telegramUnreadCount,
            ),
            onTransportClick = { mode ->
                when (mode) {
                    S23TransportMode.LIVE -> onResumeStudentFollow()
                    S23TransportMode.TELEGRAM -> onOpenRemoteMonitor()
                }
            },
            previewHoveredDescription = previewHoveredDescription,
            markHistoryContent = markHistoryContent,
        )
        return
    }
    MaterialTheme {
        val tokens = readerChromeTokens(state.role)
        Box(
            Modifier.fillMaxSize().padding(
                horizontal = tokens.chromeHorizontalPadding,
                vertical = tokens.chromeVerticalPadding,
            )
        ) {
            IconPenButton(
                description = "이전 페이지",
                iconRes = R.drawable.ic_page_prev,
                onAction = onPrevious,
                enabled = state.documentReady && state.pageNumber > 0,
                modifier = Modifier.align(Alignment.TopStart),
                visualSize = tokens.navigationButtonSize,
                role = state.role,
                forceHoveredForPreview = previewHoveredDescription == "이전 페이지",
            )
            IconPenButton(
                description = "다음 페이지",
                iconRes = R.drawable.ic_page_next,
                onAction = onNext,
                enabled = state.documentReady && state.pageNumber + 1 < state.pageCount,
                modifier = Modifier.align(Alignment.TopEnd),
                visualSize = tokens.navigationButtonSize,
                role = state.role,
                forceHoveredForPreview = previewHoveredDescription == "다음 페이지",
            )

            if (!expanded) {
                IconPenButton(
                    description = "상단 메뉴 열기",
                    iconRes = R.drawable.ic_menu_open,
                    onAction = onToggleExpanded,
                    modifier = Modifier.align(Alignment.TopCenter),
                    visualSize = tokens.menuOpenButtonSize,
                    role = state.role,
                    forceHoveredForPreview = previewHoveredDescription == "상단 메뉴 열기",
                )
            } else {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
                ) {
                    val compact = maxWidth < tokens.compactWidthBreakpoint
                    val chromeWidth = (maxWidth - tokens.navigationExclusion * 2).coerceAtLeast(0.dp)
                    Row(
                        modifier = Modifier
                            .width(chromeWidth)
                            .offset(x = tokens.navigationExclusion),
                        horizontalArrangement = Arrangement.spacedBy(
                            tokens.itemGap,
                            Alignment.CenterHorizontally,
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The title is the only route back to this book's page overview. Keeping
                        // it in the same lane on phone and tablet removes the old book-icon-only
                        // compact variant and guarantees that expanded chrome is always one row.
                        val liveMonitoring = state.capabilities.showsStudentLocation
                        if (liveMonitoring) {
                            if (state.isFollowingStudent) {
                                LiveMonitorBadge(
                                    role = state.role,
                                    compact = compact,
                                    connection = state.liveConnection,
                                )
                            } else {
                                ResumeStudentFollowChip(
                                    role = state.role,
                                    compact = compact,
                                    studentPageNumber = state.studentPageNumber,
                                    onAction = onResumeStudentFollow,
                                )
                            }
                        }
                        if (liveMonitoring && compact) {
                            // A phone lane cannot carry the title and the submission stack at once,
                            // and an ellipsized title says nothing. Give the room to the stack.
                            IconPenButton(
                                description = "교재 페이지로 돌아가기",
                                iconRes = R.drawable.ic_back_shelf,
                                onAction = onExitToLibrary,
                                role = state.role,
                                visualSize = tokens.generalButtonSize,
                                forceHoveredForPreview =
                                    previewHoveredDescription == "교재 페이지로 돌아가기",
                            )
                        } else {
                            ReaderTitleButton(
                                title = state.bookTitle.ifBlank { "교재 페이지" },
                                compact = compact,
                                role = state.role,
                                forceHovered = previewHoveredDescription == "교재 페이지로 돌아가기",
                                onAction = onExitToLibrary,
                                modifier = Modifier
                                    .weight(1f)
                                    .widthIn(max = tokens.expandedContextMaxWidth),
                            )
                        }
                        Box(
                            modifier = if (liveMonitoring && compact) {
                                // The stack is the reason this screen exists while monitoring, so
                                // it takes the lane the title gave up.
                                Modifier.weight(1f).height(tokens.minimumTouchSize)
                            } else {
                                Modifier
                                    .widthIn(
                                        max = if (compact) {
                                            tokens.compactStatusMaxWidth
                                        } else {
                                            tokens.expandedStatusMaxWidth
                                        },
                                    )
                                    .height(tokens.minimumTouchSize)
                            },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (markHistoryContent != null) {
                                markHistoryContent()
                            } else {
                                ReaderAttemptMarkHistory(
                                    state = state,
                                    maxVisibleBundles = 5,
                                    onPreviousAttempt = onPreviousAttempt,
                                    onNextAttempt = onNextAttempt,
                                    onSelectAttempt = onSelectAttempt,
                                )
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(tokens.itemGap),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (state.capabilities.canSubmit) {
                                PrimaryPenButton(
                                    text = "제출",
                                    description = "현재 페이지 제출",
                                    iconRes = R.drawable.ic_submit,
                                    onAction = onSubmit,
                                    role = state.role,
                                    enabled = state.canSubmitNow,
                                )
                            }
                            if (state.role == ReaderRole.STUDENT) {
                                IconPenButton(
                                    description = "Telegram 연결 및 설정",
                                    iconRes = R.drawable.ic_publish,
                                    onAction = onOpenRemoteMonitor,
                                    role = state.role,
                                    visualSize = tokens.generalButtonSize,
                                    forceHoveredForPreview =
                                        previewHoveredDescription == "Telegram 연결 및 설정",
                                )
                            }
                            if (state.role != ReaderRole.STUDENT) {
                                IconPenButton(
                                    description = "GPT 페이지 설명",
                                    iconRes = null,
                                    onAction = onOpenGptAssistant,
                                    role = state.role,
                                    enabled = state.documentReady,
                                    visualSize = tokens.generalButtonSize,
                                ) {
                                    Text(
                                        text = "GPT",
                                        color = tokens.paletteBlue,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Black,
                                    )
                                }
                                IconPenButton(
                                    description = "답안 PDF 열기",
                                    iconRes = null,
                                    onAction = onOpenAnswerPdf,
                                    role = state.role,
                                    enabled = state.documentReady,
                                    visualSize = tokens.generalButtonSize,
                                ) {
                                    Text(
                                        text = "답",
                                        color = tokens.paletteBlue,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Black,
                                    )
                                }
                            }
                            if (state.capabilities.showsStudentLocation) {
                                IconPenButton(
                                    description = "학생 필기량 보기",
                                    iconRes = null,
                                    onAction = onShowStudentActivity,
                                    role = state.role,
                                    visualSize = tokens.generalButtonSize,
                                ) {
                                    // No bar-chart asset exists in the icon set, and slicing one
                                    // would have to match the rest of the sheet. Three bars drawn
                                    // here stay crisp at any size and follow the chrome colours.
                                    Canvas(modifier = Modifier.size(tokens.generalButtonSize * 0.5f)) {
                                        val gap = size.width * 0.16f
                                        val barWidth = (size.width - gap * 2f) / 3f
                                        listOf(0.45f, 1f, 0.7f).forEachIndexed { index, scale ->
                                            val height = size.height * scale
                                            drawRect(
                                                color = tokens.paletteBlue,
                                                topLeft = androidx.compose.ui.geometry.Offset(
                                                    index * (barWidth + gap),
                                                    size.height - height,
                                                ),
                                                size = androidx.compose.ui.geometry.Size(barWidth, height),
                                            )
                                        }
                                    }
                                }
                            }
                            if (state.capabilities.canPublishTeacherInk) {
                                PrimaryPenButton(
                                    text = "발행",
                                    description = "첨삭 발행",
                                    iconRes = R.drawable.ic_publish,
                                    onAction = onPublish,
                                    role = state.role,
                                    enabled = state.canPublishTeacherInkNow,
                                )
                            }
                            IconPenButton(
                                description = "상단 메뉴 닫기",
                                iconRes = R.drawable.ic_menu_close,
                                onAction = onToggleExpanded,
                                visualSize = tokens.menuOpenButtonSize,
                                role = state.role,
                                style = PenButtonSurfaceStyle.GHOST,
                            )
                        }
                    }
                }
            }
            if (state.currentAttemptSubmitted) {
                SubmittedAttemptBanner(
                    role = state.role,
                    modifier = Modifier.align(Alignment.BottomStart),
                )
            }
        }
        state.dataError?.let { message ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text("필기 데이터 확인 필요") },
                text = { Text(message) },
                confirmButton = {
                    PrimaryPenButton(
                        text = "확인",
                        description = "오류 확인",
                        iconRes = R.drawable.ic_submit,
                        onAction = onDismissDataError,
                        role = state.role,
                    )
                },
            )
        }
    }
}

/**
 * Tells the student the page they are looking at is already handed in. The page itself is blurred
 * behind this, and the next stroke opens the following attempt, which clears both.
 */
@Composable
private fun SubmittedAttemptBanner(role: ReaderRole, modifier: Modifier = Modifier) {
    val tokens = readerChromeTokens(role)
    Row(
        modifier = modifier
            .height(tokens.generalButtonSize)
            .clip(RoundedCornerShape(tokens.cornerRadius))
            .background(tokens.buttonBackground.copy(alpha = 0.92f))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "제출됨 : 아무데나 필기를 시작하면 새로운 회차 시작",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
            color = tokens.buttonForeground,
        )
    }
}

/**
 * Marks the teacher chrome while a student's page is being followed in real time. A phone only has
 * room for the eye itself; the wider chrome spells it out.
 */
@Composable
private fun LiveMonitorBadge(
    role: ReaderRole,
    compact: Boolean,
    connection: LanConnectionState,
) {
    val tokens = readerChromeTokens(role)
    val linked = connection == LanConnectionState.CONNECTED
    val label = when (connection) {
        LanConnectionState.CONNECTED -> "실시간 감독 중"
        LanConnectionState.CONNECTING -> "학생 기기 찾는 중"
        else -> "학생 기기 연결 끊김"
    }
    Row(
        modifier = Modifier
            .height(tokens.generalButtonSize)
            .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(modifier = Modifier.size(if (compact) 16.dp else 18.dp)) {
            // Open red eye only while the peer is actually reachable. A closed, struck-through grey
            // eye is what tells the teacher the link dropped instead of silently showing nothing.
            val eye = if (linked) tokens.liveBadge else tokens.statusForeground
            val width = size.width
            val height = size.height
            val outline = Path().apply {
                moveTo(width * 0.04f, height * 0.5f)
                quadraticTo(width * 0.5f, height * 0.06f, width * 0.96f, height * 0.5f)
                quadraticTo(width * 0.5f, height * 0.94f, width * 0.04f, height * 0.5f)
                close()
            }
            drawPath(outline, eye, style = Stroke(width = width * 0.1f))
            if (linked) {
                drawCircle(eye, radius = width * 0.19f, center = Offset(width * 0.5f, height * 0.5f))
            } else {
                drawLine(
                    color = eye,
                    start = Offset(width * 0.12f, height * 0.86f),
                    end = Offset(width * 0.88f, height * 0.14f),
                    strokeWidth = width * 0.12f,
                )
            }
        }
        if (!compact) {
            Text(
                text = label,
                maxLines = 1,
                style = MaterialTheme.typography.labelMedium,
                color = tokens.statusForeground,
            )
        }
    }
}

/**
 * Manual page navigation only pauses the cursor-following part of LIVE_MONITOR. Keep the last
 * received student page visible here so returning to the live cursor is one deliberate S Pen tap.
 */
@Composable
private fun ResumeStudentFollowChip(
    role: ReaderRole,
    compact: Boolean,
    studentPageNumber: Int?,
    onAction: () -> Unit,
) {
    val tokens = readerChromeTokens(role)
    val pageLabel = studentPageNumber?.let { "학생 ${it + 1}쪽 다시 따라가기" }
        ?: "학생 화면 다시 따라가기"
    PenInteractionTarget(
        description = pageLabel,
        onAction = onAction,
        modifier = Modifier.height(tokens.minimumTouchSize),
    ) { hovered, pressed ->
        AnimatedPenSurface(
            hovered = hovered,
            pressed = pressed,
            enabled = true,
            selected = false,
            role = role,
            visualWidth = null,
            visualHeight = tokens.generalButtonSize,
            shape = RoundedCornerShape(tokens.cornerRadius),
            style = PenButtonSurfaceStyle.FILLED,
            minVisualWidth = if (compact) tokens.generalButtonSize else tokens.primaryMinWidth,
            textureSeed = pageLabel.hashCode(),
        ) {
            Text(
                text = if (compact) {
                    studentPageNumber?.let { "${it + 1}쪽" } ?: "따라가기"
                } else {
                    pageLabel
                },
                modifier = Modifier.padding(horizontal = tokens.primaryHorizontalPadding),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
                color = tokens.buttonForeground,
            )
        }
    }
}

@Composable
private fun ReaderTitleButton(
    title: String,
    compact: Boolean,
    role: ReaderRole,
    forceHovered: Boolean,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = readerChromeTokens(role)
    PenInteractionTarget(
        description = "교재 페이지로 돌아가기",
        onAction = onAction,
        modifier = modifier.height(tokens.minimumTouchSize),
        forceHoveredForPreview = forceHovered,
    ) { hovered, pressed ->
        AnimatedPenSurface(
            hovered = hovered,
            pressed = pressed,
            enabled = true,
            selected = false,
            role = role,
            visualWidth = null,
            visualHeight = tokens.generalButtonSize,
            shape = RoundedCornerShape(tokens.cornerRadius),
            style = PenButtonSurfaceStyle.DEFAULT,
            modifier = Modifier.fillMaxWidth(),
            textureSeed = title.hashCode(),
        ) {
            Text(
                text = title,
                modifier = Modifier.padding(horizontal = tokens.primaryHorizontalPadding),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = if (compact) {
                    MaterialTheme.typography.labelMedium
                } else {
                    MaterialTheme.typography.titleSmall
                },
                color = tokens.buttonForeground,
            )
        }
    }
}

internal data class ReaderAttemptMarkBundle(
    val attemptNo: Int,
    val colors: List<MarkColor>,
)

/**
 * Packs problem results into one compact, slightly-wide bundle. Eight results intentionally form
 * the approved 4 x 2 shape. Larger pages add both columns and rows instead of becoming a long
 * strip; unusually large bundles are scaled into the fixed top-chrome lane by the renderer.
 */
internal fun readerAttemptSummaryGrid(problemCount: Int): ResultBundleGrid =
    resultBundleGrid(problemCount)

/**
 * Projects one page into one color bundle per attempt. Problem order follows paper position so the
 * same cell always refers to the same visible mark group: top-to-bottom, then left-to-right.
 * Teacher page-level marks (attempt 0) never share a bundle with student attempt history.
 */
internal fun readerAttemptMarkBundles(
    groups: List<MarkGroup>,
    pageNumber: Int,
    selectedAttemptNo: Int,
    /** Attempts that exist on the page. A submission with no marks yet still needs a slot. */
    attemptNos: List<Int> = emptyList(),
): List<ReaderAttemptMarkBundle> {
    val pageLevel = selectedAttemptNo == TEACHER_PAGE_REVIEW_ATTEMPT_NO
    val sortedGroups = groups.asSequence()
        .filter { it.pageNumber == pageNumber && it.hiddenAtEpochMillis == null }
        .filter { group ->
            group.marks.any { mark ->
                mark.hiddenAtEpochMillis == null &&
                    (mark.attemptNo == TEACHER_PAGE_REVIEW_ATTEMPT_NO) == pageLevel
            }
        }
        .sortedWith(compareBy<MarkGroup>({ it.anchor.y }, { it.anchor.x }, { it.id }))
        .toList()
    // A submission that nobody has started grading has no mark groups yet. It still needs its own
    // empty frame, otherwise the stack silently drops every ungraded attempt on the page.
    val hasSubmissions = !pageLevel && attemptNos.any { it > TEACHER_PAGE_REVIEW_ATTEMPT_NO }
    if (sortedGroups.isEmpty() && !hasSubmissions) return emptyList()

    val attemptNumbers = if (pageLevel) {
        listOf(TEACHER_PAGE_REVIEW_ATTEMPT_NO)
    } else {
        buildSet {
            sortedGroups.forEach { group ->
                group.marks.asSequence()
                    .filter { mark ->
                        mark.hiddenAtEpochMillis == null &&
                            mark.attemptNo > TEACHER_PAGE_REVIEW_ATTEMPT_NO
                    }
                    .mapTo(this) { it.attemptNo }
            }
            attemptNos.filterTo(this) { it > TEACHER_PAGE_REVIEW_ATTEMPT_NO }
            if (selectedAttemptNo > TEACHER_PAGE_REVIEW_ATTEMPT_NO) add(selectedAttemptNo)
        }.sorted()
    }

    return attemptNumbers.map { attemptNo ->
        ReaderAttemptMarkBundle(
            attemptNo = attemptNo,
            colors = sortedGroups.map { group ->
                group.marks.asSequence()
                    .filter { mark ->
                        mark.attemptNo == attemptNo && mark.hiddenAtEpochMillis == null
                    }
                    .maxByOrNull { it.gradedAtEpochMillis }
                    ?.color
                    ?: MarkColor.GRAY
            },
        )
    }
}

@Composable
private fun ReaderAttemptMarkHistory(
    state: ReaderUiState,
    maxVisibleBundles: Int,
    onPreviousAttempt: () -> Unit,
    onNextAttempt: () -> Unit,
    onSelectAttempt: (Int) -> Unit = {},
) {
    val bundles = remember(state.marks, state.pageNumber, state.attemptNo, state.pageAttemptNos) {
        readerAttemptMarkBundles(
            groups = state.marks,
            pageNumber = state.pageNumber,
            selectedAttemptNo = state.attemptNo,
            attemptNos = state.pageAttemptNos,
        )
    }
    if (bundles.isEmpty()) return

    // Every submission keeps a frame, including the ones after the attempt on screen. Trimming to
    // the selected attempt is what left a teacher with only the newest submission to look at.
    val selectedIndex = bundles.indexOfFirst { it.attemptNo == state.attemptNo }
        .takeIf { it >= 0 }
        ?: bundles.lastIndex
    val window = maxVisibleBundles.coerceAtLeast(1)
    val end = (maxOf(selectedIndex + 1, window)).coerceAtMost(bundles.size)
    val visibleBundles = bundles.subList((end - window).coerceAtLeast(0), end)
    val bundleBounds = remember(state.pageNumber) {
        mutableStateMapOf<Int, ClosedFloatingPointRange<Float>>()
    }
    val tokens = readerChromeTokens(state.role)
    val dragThresholdPx = with(LocalDensity.current) { 16.dp.toPx() }
    var stylusDragStartX by remember(state.pageNumber, state.attemptNo) { mutableStateOf<Float?>(null) }
    var stylusDragOffset by remember(state.pageNumber, state.attemptNo) { mutableStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(tokens.minimumTouchSize)
            .clip(RoundedCornerShape(tokens.cornerRadius))
            .semantics {
                contentDescription = "현재 회차 문제별 정오답. S펜으로 좌우로 밀어 회차 이동"
            }
            .pointerInteropFilter { event ->
                if (event.pointerCount == 0 || event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) {
                    return@pointerInteropFilter false
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        stylusDragStartX = event.x
                        stylusDragOffset = 0f
                    }
                    MotionEvent.ACTION_MOVE -> {
                        stylusDragOffset = event.x - (stylusDragStartX ?: event.x)
                    }
                    MotionEvent.ACTION_UP -> {
                        val distance = event.x - (stylusDragStartX ?: event.x)
                        val releasedAtX = event.rawX
                        stylusDragStartX = null
                        stylusDragOffset = 0f
                        if (state.capabilities.canBrowseAttempts) {
                            if (abs(distance) >= dragThresholdPx) {
                                if (distance > 0f) onPreviousAttempt() else onNextAttempt()
                            } else {
                                // A tap opens the frame under the pen. Stepping one attempt at a
                                // time is still available by dragging.
                                bundleBounds.entries
                                    .firstOrNull { releasedAtX in it.value }
                                    ?.let { onSelectAttempt(it.key) }
                            }
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        stylusDragStartX = null
                        stylusDragOffset = 0f
                    }
                }
                true
            },
        contentAlignment = Alignment.CenterEnd,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 3.dp)
                .graphicsLayer { translationX = stylusDragOffset.coerceIn(-20f, 20f) },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            visibleBundles.forEach { bundle ->
                val selected = bundle.attemptNo == state.attemptNo
                // Each attempt keeps its own frame so an ungraded submission still reads as
                // "waiting to be graded", and the selected one is the only fully opaque frame.
                Box(
                    modifier = Modifier
                        .onGloballyPositioned { coordinates ->
                            val bounds = coordinates.boundsInWindow()
                            bundleBounds[bundle.attemptNo] = bounds.left..bounds.right
                        }
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) {
                                tokens.markPendingHighlight
                            } else {
                                tokens.markPendingBorder
                            },
                            shape = RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 3.dp, vertical = 2.dp)
                        // An ungraded submission draws an empty frame, so keep it big enough to
                        // read as a slot that is still waiting for marks.
                        .defaultMinSize(minWidth = 10.dp, minHeight = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    AttemptMarkMicroGrid(
                        colors = bundle.colors,
                        alpha = if (selected) 1f else tokens.markBundleDimAlpha,
                    )
                }
            }
        }
    }
}

@Composable
private fun AttemptMarkMicroGrid(
    colors: List<MarkColor>,
    alpha: Float,
) {
    if (colors.isEmpty()) return
    val grid = remember(colors.size) { readerAttemptSummaryGrid(colors.size) }
    val naturalWidth = grid.columns * 3f + (grid.columns - 1).coerceAtLeast(0)
    val naturalHeight = grid.rows * 5f + (grid.rows - 1).coerceAtLeast(0)
    val scale = minOf(1f, 40f / naturalWidth, 30f / naturalHeight)
    val cellWidth = 3.dp * scale
    val cellHeight = 5.dp * scale
    val gap = 1.dp * scale
    val width = cellWidth * grid.columns + gap * (grid.columns - 1).coerceAtLeast(0)
    val height = cellHeight * grid.rows + gap * (grid.rows - 1).coerceAtLeast(0)
    val canvasTokens = readerCanvasTokens()
    Canvas(
        modifier = Modifier
            .size(width = width, height = height)
            .alpha(alpha),
    ) {
        val cellWidthPx = cellWidth.toPx()
        val cellHeightPx = cellHeight.toPx()
        val gapPx = gap.toPx()
        val radius = minOf(cellWidthPx, cellHeightPx) * 0.42f
        colors.forEachIndexed { index, color ->
            val gridCell = grid.cells[index]
            drawRoundRect(
                color = Color(
                    when (color) {
                        MarkColor.BLUE -> canvasTokens.markBlueArgb
                        MarkColor.RED -> canvasTokens.markRedArgb
                        MarkColor.GRAY -> canvasTokens.markGrayArgb
                    },
                ),
                topLeft = Offset(
                    x = gridCell.column * (cellWidthPx + gapPx),
                    y = gridCell.row * (cellHeightPx + gapPx),
                ),
                size = Size(cellWidthPx, cellHeightPx),
                cornerRadius = CornerRadius(radius, radius),
            )
        }
    }
}

private object ReaderTopChromePreviewFixtures {
    private fun marks(pageNumber: Int): List<MarkGroup> = List(8) { index ->
        MarkGroup(
            id = "preview-problem-$index",
            bookId = "preview-book",
            pageNumber = pageNumber,
            anchor = PagePoint(
                x = (index % 2) * 100f,
                y = (index / 2) * 100f,
            ),
            marks = listOf(
                Mark(
                    attemptNo = 1,
                    color = if (index % 3 == 0) MarkColor.RED else MarkColor.BLUE,
                ),
                Mark(
                    attemptNo = 2,
                    color = when (index % 4) {
                        0 -> MarkColor.RED
                        3 -> MarkColor.GRAY
                        else -> MarkColor.BLUE
                    },
                ),
            ),
        )
    }

    fun state(role: ReaderRole, title: String, studentPageNumber: Int? = null) = ReaderUiState(
        bookId = "preview-book",
        bookTitle = title,
        pageCount = 24,
        documentReady = true,
        pageNumber = 2,
        attemptNo = 2,
        role = role,
        workflow = ReaderWorkflow.defaultFor(role),
        capabilities = ReaderCapabilities.forSession(role, ReaderWorkflow.defaultFor(role), 2),
        marks = marks(pageNumber = 2),
        studentPageNumber = studentPageNumber,
        isFollowingStudent = role == ReaderRole.TEACHER_PHONE,
    )
}

@Composable
private fun ReaderTopChromePreviewContent(
    state: ReaderUiState,
    expanded: Boolean,
    hoveredDescription: String? = null,
) {
    ReaderTopChrome(
        state = state,
        expanded = expanded,
        onToggleExpanded = {},
        onPrevious = {},
        onNext = {},
        onExitToLibrary = {},
        onSubmit = {},
        onPreviousAttempt = {},
        onNextAttempt = {},
        onPublish = {},
        onDismissDataError = {},
        previewHoveredDescription = hoveredDescription,
    )
}

@Preview(
    name = "학생 · 폰 412 · 펼침",
    group = "상단바 검증",
    widthDp = 412,
    heightDp = 76,
    showBackground = true,
)
@Composable
private fun StudentPhoneChromePreview() = ReaderTopChromePreviewContent(
    state = ReaderTopChromePreviewFixtures.state(
        ReaderRole.STUDENT,
        "영어 문제집 — 문장 구조와 어휘 연습",
    ),
    expanded = true,
)

@Preview(
    name = "선생태블릿 역할 · 폰 412 · 펼침",
    group = "상단바 검증",
    widthDp = 412,
    heightDp = 76,
    showBackground = true,
)
@Composable
private fun TeacherTabletAtPhoneWidthChromePreview() = ReaderTopChromePreviewContent(
    state = ReaderTopChromePreviewFixtures.state(
        ReaderRole.TEACHER_TABLET,
        "학생 풀이 검토",
    ),
    expanded = true,
)

@Preview(
    name = "선생폰 · 412 · 학생위치와 발행",
    group = "상단바 검증",
    widthDp = 412,
    heightDp = 76,
    showBackground = true,
)
@Composable
private fun TeacherPhoneChromePreview() = ReaderTopChromePreviewContent(
    state = ReaderTopChromePreviewFixtures.state(
        ReaderRole.TEACHER_PHONE,
        "학생 풀이 검토 — 문장 구조와 어휘 연습",
        studentPageNumber = 2,
    ),
    expanded = true,
)

@Preview(
    name = "선생태블릿 · Tab S11 800 · 펼침",
    group = "상단바 검증",
    widthDp = 800,
    heightDp = 76,
    showBackground = true,
)
@Composable
private fun TeacherTabletExpandedChromePreview() = ReaderTopChromePreviewContent(
    state = ReaderTopChromePreviewFixtures.state(
        ReaderRole.TEACHER_TABLET,
        "English Workbook — Reading and Vocabulary Unit 12",
    ),
    expanded = true,
)

@Preview(name = "접힘 · 폰 412", group = "상단바 검증", widthDp = 412, heightDp = 76, showBackground = true)
@Composable
private fun CollapsedPhoneChromePreview() = ReaderTopChromePreviewContent(
    state = ReaderTopChromePreviewFixtures.state(ReaderRole.STUDENT, "Unit 1"),
    expanded = false,
)

@Preview(name = "아주 긴 단원명 · 폰 412", group = "상단바 검증", widthDp = 412, heightDp = 76, showBackground = true)
@Composable
private fun LongTitlePhoneChromePreview() = ReaderTopChromePreviewContent(
    state = ReaderTopChromePreviewFixtures.state(
        ReaderRole.STUDENT,
        "아주 긴 영어 문제집 단원명 — 문장 구조와 어휘 연습 및 심화 문제 풀이",
    ),
    expanded = true,
)

class TeacherAccessController(context: Context) {
    private val preferences = context.getSharedPreferences("teacher-access", Context.MODE_PRIVATE)
    private val debugBypassEnabled = ReaderDebugSessionStore.isEnabled(context)

    val hasPin: Boolean get() = preferences.contains(PIN_HASH)
    fun setPin(pin: String): Boolean {
        if (pin.length < 4 || pin.any { !it.isDigit() }) return false
        preferences.edit().putString(PIN_HASH, pin.hash()).apply()
        return true
    }
    fun verify(pin: String, rememberUntilProcessExit: Boolean): Boolean {
        val valid = preferences.getString(PIN_HASH, null) == pin.hash()
        if (valid && rememberUntilProcessExit) sessionAuthenticated = true
        return valid
    }
    fun isSessionAuthenticated(): Boolean = debugBypassEnabled || sessionAuthenticated

    companion object {
        private const val PIN_HASH = "pinHash"
        @Volatile private var sessionAuthenticated = false
        fun invalidateSession() { sessionAuthenticated = false }
    }
}

@Composable
fun TeacherPinDialog(
    setup: Boolean,
    onCancel: () -> Unit,
    onConfirm: (pin: String, remember: Boolean) -> Boolean,
) {
    var pin by remember { mutableStateOf("") }
    var rememberSession by remember { mutableStateOf(true) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (setup) "선생 PIN 설정" else "선생 PIN") },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(8); invalid = false },
                    label = { Text("숫자 4자리 이상") },
                    visualTransformation = PasswordVisualTransformation(),
                    isError = invalid,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val tokens = readerChromeTokens(ReaderRole.TEACHER_TABLET)
                    IconPenButton(
                        description = "앱 종료까지 PIN 재입력 안 함",
                        iconRes = R.drawable.ic_student_switch,
                        onAction = { rememberSession = !rememberSession },
                        role = ReaderRole.TEACHER_TABLET,
                        visualSize = tokens.generalButtonSize,
                        selected = rememberSession,
                    )
                    Text(
                        if (rememberSession) "✓ 앱 종료까지 다시 묻지 않음" else "앱 종료까지 다시 묻지 않음",
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        },
        confirmButton = {
            PrimaryPenButton(
                text = if (setup) "설정" else "확인",
                description = "PIN 확인",
                iconRes = R.drawable.ic_submit,
                onAction = { if (!onConfirm(pin, rememberSession)) invalid = true },
                role = ReaderRole.TEACHER_TABLET,
            )
        },
        dismissButton = {
            val tokens = readerChromeTokens(ReaderRole.TEACHER_TABLET)
            IconPenButton(
                description = "PIN 취소",
                iconRes = R.drawable.ic_menu_close,
                onAction = onCancel,
                role = ReaderRole.TEACHER_TABLET,
                visualSize = tokens.generalButtonSize,
            )
        },
    )
}

@Composable
fun MarkEditDialog(
    onBlue: () -> Unit,
    onRed: () -> Unit,
    onMove: () -> Unit,
    onHide: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("채점 표시 수정") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryPenButton("파랑", "파랑으로 변경", R.drawable.ic_tool_grade_item, onBlue, ReaderRole.TEACHER_TABLET)
                PrimaryPenButton("빨강", "빨강으로 변경", R.drawable.ic_tool_grade_item, onRed, ReaderRole.TEACHER_TABLET)
                PrimaryPenButton("이동", "채점 표시 위치 이동", R.drawable.ic_student_switch, onMove, ReaderRole.TEACHER_TABLET)
                PrimaryPenButton("감춤", "채점 표시 감추기", R.drawable.ic_menu_close, onHide, ReaderRole.TEACHER_TABLET)
            }
        },
        confirmButton = {
            val tokens = readerChromeTokens(ReaderRole.TEACHER_TABLET)
            IconPenButton(
                description = "채점 수정 닫기",
                iconRes = R.drawable.ic_menu_close,
                onAction = onCancel,
                role = ReaderRole.TEACHER_TABLET,
                visualSize = tokens.generalButtonSize,
            )
        },
    )
}

private fun String.hash(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
