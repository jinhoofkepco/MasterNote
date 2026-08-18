package com.studyink.reader

import android.content.Context
import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import java.security.MessageDigest

@Composable
fun PenTapButton(
    description: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    shape: Shape = RoundedCornerShape(22.dp),
    content: @Composable () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    var gestureStartedInside by remember { mutableStateOf(false) }
    var componentSize by remember { mutableStateOf(IntSize.Zero) }
    fun isInside(event: MotionEvent): Boolean =
        event.x >= 0f && event.y >= 0f && event.x < componentSize.width && event.y < componentSize.height
    val alpha = if (!enabled) 0.28f else if (hovered || selected) 1f else 0.48f
    Surface(
        modifier = modifier
            .alpha(alpha)
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
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurface,
        shadowElevation = if (hovered) 8.dp else 3.dp,
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
    onTeacherMode: () -> Unit,
    onPreviousAttempt: () -> Unit,
    onNextAttempt: () -> Unit,
    onPublish: () -> Unit,
    onDismissDataError: () -> Unit,
) {
    MaterialTheme {
        Box(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
            PenTapButton(
                description = "이전 페이지",
                onAction = onPrevious,
                enabled = state.documentReady && state.pageNumber > 0,
                modifier = Modifier.align(Alignment.TopStart).size(48.dp),
                shape = CircleShape,
            ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ChevronLeft, null)
            } }
            PenTapButton(
                description = "다음 페이지",
                onAction = onNext,
                enabled = state.documentReady && state.pageNumber + 1 < state.pageCount,
                modifier = Modifier.align(Alignment.TopEnd).size(48.dp),
                shape = CircleShape,
            ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ChevronRight, null)
            } }

            if (!expanded) {
                PenTapButton(
                    description = "상단 메뉴 열기",
                    onAction = onToggleExpanded,
                    modifier = Modifier.align(Alignment.TopCenter).size(36.dp),
                    shape = CircleShape,
                ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MoreHoriz, null)
                } }
            } else {
                Row(
                    modifier = Modifier.align(Alignment.TopCenter),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PenTextButton(state.bookTitle.ifBlank { "책장" }, "책장으로 나가기", onExitToLibrary)
                    if (state.capabilities.canSubmit) PenTextButton("제출", "현재 페이지 제출", onSubmit)
                    if (state.capabilities.canBrowseAttempts) {
                        PenTextButton("‹", "이전 풀이", onPreviousAttempt)
                        Text("${state.attemptNo}회")
                        PenTextButton("›", "다음 풀이", onNextAttempt)
                    }
                    if (state.capabilities.showsStudentLocation) {
                        Text(state.studentPageNumber?.let { "학생 ${it + 1}쪽" } ?: "학생 위치 대기 중")
                    }
                    if (state.capabilities.canPublishTeacherInk) PenTextButton("발행", "첨삭 발행", onPublish)
                    PenTextButton(
                        if (state.role == ReaderRole.STUDENT) "선생" else "학생",
                        "모드 전환",
                        onTeacherMode,
                    )
                    PenTapButton(
                        description = "상단 메뉴 닫기",
                        onAction = onToggleExpanded,
                        modifier = Modifier.size(40.dp),
                    ) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.ExpandLess, null)
                    } }
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
private fun PenTextButton(text: String, description: String, onAction: () -> Unit) {
    PenTapButton(
        description = description,
        onAction = onAction,
        modifier = Modifier.widthIn(min = 44.dp),
    ) { Text(text, modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp), maxLines = 1) }
}

class TeacherAccessController(context: Context) {
    private val preferences = context.getSharedPreferences("teacher-access", Context.MODE_PRIVATE)

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
    fun isSessionAuthenticated(): Boolean = sessionAuthenticated

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
            PenTextButton(if (setup) "설정" else "확인", "PIN 확인") {
                if (!onConfirm(pin, rememberSession)) invalid = true
            }
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
