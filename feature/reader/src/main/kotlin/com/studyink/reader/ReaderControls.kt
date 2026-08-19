package com.studyink.reader

import android.content.Context
import android.view.MotionEvent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import java.security.MessageDigest

enum class PenButtonStyle { DEFAULT, FILLED_ACTION, OUTLINED_ACTION, GHOST }

@Composable
fun PenTapButton(
    description: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    shape: Shape? = null,
    style: PenButtonStyle = PenButtonStyle.DEFAULT,
    role: ReaderRole = ReaderRole.STUDENT,
    forceHoveredForPreview: Boolean = false,
    content: @Composable () -> Unit,
) {
    val tokens = readerChromeTokens(role)
    var hovered by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    var gestureStartedInside by remember { mutableStateOf(false) }
    var componentSize by remember { mutableStateOf(IntSize.Zero) }
    fun isInside(event: MotionEvent): Boolean =
        event.x >= 0f && event.y >= 0f && event.x < componentSize.width && event.y < componentSize.height
    val effectiveHovered = hovered || forceHoveredForPreview
    val targetAlpha = if (!enabled) tokens.disabledAlpha else if (effectiveHovered || selected) {
        tokens.hoveredAlpha
    } else {
        tokens.defaultAlpha
    }
    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(tokens.fadeDurationMillis),
        label = "pen-button-alpha",
    )
    val hoverScale by animateFloatAsState(
        targetValue = if (effectiveHovered && enabled) tokens.hoverScale else 1f,
        animationSpec = spring(tokens.springDampingRatio, tokens.springStiffness),
        label = "pen-button-hover-scale",
    )
    val pressedScaleY by animateFloatAsState(
        targetValue = if (armed && enabled) tokens.pressedScaleY else 1f,
        animationSpec = spring(tokens.springDampingRatio, tokens.springStiffness),
        label = "pen-button-pressed-scale",
    )
    val elevation by animateDpAsState(
        targetValue = when {
            style == PenButtonStyle.GHOST -> 0.dp
            effectiveHovered && enabled -> tokens.hoveredElevation
            else -> tokens.restingElevation
        },
        animationSpec = spring(tokens.springDampingRatio, tokens.springStiffness),
        label = "pen-button-elevation",
    )
    val background = when {
        !enabled -> tokens.disabledBackground
        selected -> tokens.buttonSelectedBackground
        style == PenButtonStyle.FILLED_ACTION -> tokens.actionBackground
        style == PenButtonStyle.GHOST -> Color.Transparent
        else -> tokens.buttonBackground
    }
    val foreground = when {
        selected -> tokens.buttonSelectedForeground
        style == PenButtonStyle.FILLED_ACTION -> tokens.actionForeground
        else -> tokens.buttonForeground
    }
    Surface(
        modifier = modifier
            .sizeIn(minWidth = tokens.minimumTouchSize, minHeight = tokens.minimumTouchSize)
            .alpha(alpha)
            .graphicsLayer {
                scaleX = hoverScale
                scaleY = hoverScale * pressedScaleY
            }
            .semantics {
                contentDescription = description
                onClick {
                    if (enabled) onAction()
                    enabled
                }
            }
            .onSizeChanged { componentSize = it }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.type == PointerType.Stylus }) {
                            hovered = event.type != PointerEventType.Exit
                        }
                    }
                }
            }
            .pointerInteropFilter { event ->
                val index = event.actionIndex.coerceIn(0, (event.pointerCount - 1).coerceAtLeast(0))
                val stylus = event.pointerCount > 0 && when (event.getToolType(index)) {
                    MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> true
                    else -> false
                }
                if (!stylus) return@pointerInteropFilter false
                when (event.actionMasked) {
                    MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> hovered = true
                    MotionEvent.ACTION_HOVER_EXIT -> { hovered = false; armed = false; gestureStartedInside = false }
                    MotionEvent.ACTION_DOWN -> {
                        gestureStartedInside = enabled && isInside(event)
                        armed = gestureStartedInside
                    }
                    MotionEvent.ACTION_MOVE -> armed = gestureStartedInside && isInside(event)
                    MotionEvent.ACTION_UP -> {
                        if (enabled && armed && isInside(event)) onAction()
                        armed = false
                        gestureStartedInside = false
                    }
                    MotionEvent.ACTION_CANCEL -> { armed = false; gestureStartedInside = false }
                }
                true
            },
        shape = shape ?: RoundedCornerShape(tokens.cornerRadius),
        color = background,
        contentColor = foreground,
        border = if (style == PenButtonStyle.OUTLINED_ACTION) {
            BorderStroke(tokens.buttonBorderWidth, tokens.outline)
        } else {
            null
        },
        shadowElevation = elevation,
        content = content,
    )
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
    previewHoveredDescription: String? = null,
) {
    MaterialTheme {
        val tokens = readerChromeTokens(state.role)
        val attemptPopupOffset = with(LocalDensity.current) { tokens.popupVerticalOffset.roundToPx() }
        var attemptPickerExpanded by remember(state.pageNumber, state.role) { mutableStateOf(false) }
        Box(
            Modifier.fillMaxSize().padding(
                horizontal = tokens.chromeHorizontalPadding,
                vertical = tokens.chromeVerticalPadding,
            )
        ) {
            PenTapButton(
                description = "이전 페이지",
                onAction = onPrevious,
                enabled = state.documentReady && state.pageNumber > 0,
                modifier = Modifier.align(Alignment.TopStart).size(tokens.navigationButtonSize),
                shape = CircleShape,
                role = state.role,
                forceHoveredForPreview = previewHoveredDescription == "이전 페이지",
            ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ChevronLeft, null)
            } }
            PenTapButton(
                description = "다음 페이지",
                onAction = onNext,
                enabled = state.documentReady && state.pageNumber + 1 < state.pageCount,
                modifier = Modifier.align(Alignment.TopEnd).size(tokens.navigationButtonSize),
                shape = CircleShape,
                role = state.role,
                forceHoveredForPreview = previewHoveredDescription == "다음 페이지",
            ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ChevronRight, null)
            } }

            if (!expanded) {
                PenTapButton(
                    description = "상단 메뉴 열기",
                    onAction = onToggleExpanded,
                    modifier = Modifier.align(Alignment.TopCenter).size(tokens.compactMenuButtonSize),
                    shape = CircleShape,
                    role = state.role,
                    forceHoveredForPreview = previewHoveredDescription == "상단 메뉴 열기",
                ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MoreHoriz, null)
                } }
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
                        horizontalArrangement = Arrangement.spacedBy(tokens.itemGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Give the expanded chrome an exact lane between the two navigation hit
                        // targets. Fixed offsets avoid Row's compact-width child compression from
                        // borrowing the space reserved for either page arrow.
                            ReaderContextButton(
                                title = state.bookTitle.ifBlank { "책장" },
                                compact = compact,
                                role = state.role,
                                forceHovered = previewHoveredDescription == "책장으로 나가기",
                                onAction = onExitToLibrary,
                                modifier = Modifier
                                    .weight(1f)
                                    .widthIn(
                                        max = if (compact) {
                                            tokens.compactContextMaxWidth
                                        } else {
                                            tokens.expandedContextMaxWidth
                                        },
                                    ),
                            )
                            Box(
                                modifier = Modifier.widthIn(
                                    max = if (compact) {
                                        tokens.compactStatusMaxWidth
                                    } else {
                                        tokens.expandedStatusMaxWidth
                                    },
                                ),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                ReaderStatus(
                                    state = state,
                                    compact = compact,
                                    role = state.role,
                                    onAction = {
                                        if (state.capabilities.canBrowseAttempts) attemptPickerExpanded = true
                                    },
                                )
                                if (attemptPickerExpanded) {
                                    Popup(
                                        alignment = Alignment.TopCenter,
                                        offset = IntOffset(0, attemptPopupOffset),
                                        onDismissRequest = { attemptPickerExpanded = false },
                                        properties = PopupProperties(focusable = true),
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(tokens.cornerRadius),
                                            color = tokens.buttonBackground,
                                            shadowElevation = tokens.hoveredElevation,
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(tokens.popupContentPadding),
                                                horizontalArrangement = Arrangement.spacedBy(tokens.popupItemGap),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                PenTextButton("‹", "이전 풀이", onPreviousAttempt, role = state.role)
                                                Text("${state.attemptNo}회", color = tokens.buttonForeground)
                                                PenTextButton("›", "다음 풀이", onNextAttempt, role = state.role)
                                            }
                                        }
                                    }
                                }
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(tokens.itemGap),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (state.capabilities.canSubmit) {
                                    PenTextButton(
                                        "제출",
                                        "현재 페이지 제출",
                                        onSubmit,
                                        role = state.role,
                                        style = PenButtonStyle.FILLED_ACTION,
                                    )
                                }
                                if (state.capabilities.canPublishTeacherInk) {
                                    PenTextButton(
                                        "발행",
                                        "첨삭 발행",
                                        onPublish,
                                        role = state.role,
                                        style = PenButtonStyle.OUTLINED_ACTION,
                                    )
                                }
                                PenTapButton(
                                    description = "상단 메뉴 닫기",
                                    onAction = onToggleExpanded,
                                    modifier = Modifier.size(tokens.compactMenuButtonSize),
                                    role = state.role,
                                    style = PenButtonStyle.GHOST,
                                ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.ExpandLess, null)
                                } }
                            }
                    }
                }
            }
        }
        state.dataError?.let { message ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text("필기 데이터 확인 필요") },
                text = { Text(message) },
                confirmButton = { PenTextButton("확인", "오류 확인", onDismissDataError) },
            )
        }
    }
}

@Composable
private fun ReaderContextButton(
    title: String,
    compact: Boolean,
    role: ReaderRole,
    forceHovered: Boolean,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = readerChromeTokens(role)
    PenTapButton(
        description = "책장으로 나가기",
        onAction = onAction,
        modifier = modifier.height(tokens.actionButtonHeight),
        role = role,
        forceHoveredForPreview = forceHovered,
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = tokens.contentHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(tokens.contextContentGap),
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, modifier = Modifier.size(tokens.contextIconSize))
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
            )
        }
    }
}

@Composable
private fun ReaderStatus(state: ReaderUiState, compact: Boolean, role: ReaderRole, onAction: () -> Unit) {
    val tokens = readerChromeTokens(role)
    val location = if (state.capabilities.showsStudentLocation) {
        state.studentPageNumber?.let { "학생 ${it + 1}쪽" } ?: "학생 대기"
    } else {
        null
    }
    val text = listOfNotNull("${state.attemptNo}회", location).joinToString(" · ")
    if (state.capabilities.canBrowseAttempts) {
        PenTapButton(
            description = "풀이 회차 선택",
            onAction = onAction,
            role = role,
            style = PenButtonStyle.GHOST,
            modifier = Modifier.height(tokens.actionButtonHeight),
        ) {
            Box(
                Modifier.padding(
                    horizontal = if (compact) tokens.compactStatusPadding else tokens.expandedStatusPadding,
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    } else {
        Text(
            text,
            modifier = Modifier
                .semantics { contentDescription = "현재 풀이 상태" }
                .padding(
                    horizontal = if (compact) tokens.compactStatusPadding else tokens.expandedStatusPadding,
                ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelLarge,
            color = tokens.buttonForeground,
        )
    }
}

@Composable
private fun PenTextButton(
    text: String,
    description: String,
    onAction: () -> Unit,
    role: ReaderRole = ReaderRole.STUDENT,
    style: PenButtonStyle = PenButtonStyle.DEFAULT,
) {
    val tokens = readerChromeTokens(role)
    PenTapButton(
        description = description,
        onAction = onAction,
        modifier = Modifier.widthIn(min = tokens.minimumTouchSize).height(tokens.actionButtonHeight),
        role = role,
        style = style,
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .padding(horizontal = tokens.contentHorizontalPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, maxLines = 1)
        }
    }
}

private object ReaderTopChromePreviewFixtures {
    fun state(role: ReaderRole, title: String, studentPageNumber: Int? = null) = ReaderUiState(
        bookId = "preview-book",
        bookTitle = title,
        pageCount = 24,
        documentReady = true,
        pageNumber = 2,
        attemptNo = 2,
        role = role,
        capabilities = ReaderCapabilities.forRole(role),
        studentPageNumber = studentPageNumber,
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

@Preview(name = "학생 · 폰 · 긴 단원명 · 호버", widthDp = 412, heightDp = 76, showBackground = true)
@Composable
private fun StudentPhoneChromePreview() = ReaderTopChromePreviewContent(
    state = ReaderTopChromePreviewFixtures.state(
        ReaderRole.STUDENT,
        "아주 긴 영어 문제집 단원명 — 문장 구조와 어휘 연습",
    ),
    expanded = true,
    hoveredDescription = "책장으로 나가기",
)

@Preview(name = "선생 · 폰 폭", widthDp = 412, heightDp = 76, showBackground = true)
@Composable
private fun TeacherTabletPhoneChromePreview() = ReaderTopChromePreviewContent(
    state = ReaderTopChromePreviewFixtures.state(ReaderRole.TEACHER_TABLET, "Unit 2"),
    expanded = true,
)

@Preview(name = "선생폰 · 폰 폭", widthDp = 412, heightDp = 76, showBackground = true)
@Composable
private fun TeacherPhoneChromePreview() = ReaderTopChromePreviewContent(
    state = ReaderTopChromePreviewFixtures.state(
        ReaderRole.TEACHER_PHONE,
        "긴 단원명을 가진 학생 풀이 검토 화면",
        studentPageNumber = 2,
    ),
    expanded = true,
)

@Preview(name = "선생 · 태블릿", widthDp = 1600, heightDp = 76, showBackground = true)
@Composable
private fun TeacherTabletChromePreview() = ReaderTopChromePreviewContent(
    state = ReaderTopChromePreviewFixtures.state(
        ReaderRole.TEACHER_TABLET,
        "English Workbook — Reading and Vocabulary Unit 12",
    ),
    expanded = true,
)

@Preview(name = "접힘 상태", widthDp = 412, heightDp = 76, showBackground = true)
@Composable
private fun CollapsedChromePreview() = ReaderTopChromePreviewContent(
    state = ReaderTopChromePreviewFixtures.state(ReaderRole.STUDENT, "Unit 1"),
    expanded = false,
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
                PenTapButton(
                    description = "앱 종료까지 PIN 재입력 안 함",
                    onAction = { rememberSession = !rememberSession },
                    selected = rememberSession,
                ) {
                    Text(
                        if (rememberSession) "✓ 앱 종료까지 다시 묻지 않음" else "앱 종료까지 다시 묻지 않음",
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        },
        confirmButton = {
            PenTextButton(if (setup) "설정" else "확인", "PIN 확인", onAction = {
                if (!onConfirm(pin, rememberSession)) invalid = true
            })
        },
        dismissButton = { PenTextButton("취소", "PIN 취소", onCancel) },
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
                PenTextButton("파랑", "파랑으로 변경", onBlue)
                PenTextButton("빨강", "빨강으로 변경", onRed)
                PenTextButton("이동", "채점 표시 위치 이동", onMove)
                PenTextButton("감추기", "채점 표시 감추기", onHide)
            }
        },
        confirmButton = { PenTextButton("닫기", "채점 수정 닫기", onCancel) },
    )
}

private fun String.hash(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
