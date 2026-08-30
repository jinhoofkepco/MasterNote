package com.studyink.reader

import android.content.res.Configuration
import android.os.Build
import android.view.MotionEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.studyink.core.model.Mark
import com.studyink.core.model.MarkColor
import com.studyink.core.model.MarkGroup
import com.studyink.core.model.PagePoint
import com.studyink.monitor.core.HybridLinkDecision
import com.studyink.monitor.core.HybridLinkHealth
import com.studyink.monitor.core.HybridLinkMode
import com.studyink.sync.lan.LanConnectionState
import kotlin.math.abs
import kotlin.math.roundToInt

internal const val S23_ULTRA_MODEL_PREFIX = "SM-S918"
internal const val S23_STRIP_CELL_COUNT = 11
internal const val S23_STRIP_HISTORY_CELL_COUNT = 3

// The visible paper is deliberately much thinner than the interaction lane. Keeping the latter
// at 48dp preserves reliable finger/S Pen hover and taps while the workbook remains visible behind
// the lower, transparent part of the lane.
private val S23_STRIP_TOUCH_HEIGHT = 48.dp
private val S23_STRIP_VISUAL_HEIGHT = 30.dp

/** S Pen taps belong exclusively to the parent drag/tap interop path. */
internal fun s23AttemptCellHandlesDirectPointer(pointerType: PointerType): Boolean =
    pointerType == PointerType.Touch

/** The one transport cell shows the route currently capable of carrying the teacher session. */
enum class S23TransportMode {
    LIVE,
    TELEGRAM,
}

/**
 * Transport detail deliberately stays independent of LAN and Telegram implementation classes.
 * The app coordinator can therefore map its richer health model without making the reader own
 * reconnection or fallback policy.
 */
enum class S23TransportLinkState {
    CONNECTED,
    READY,
    CONNECTING,
    QUEUED,
    ERROR,
    UNAVAILABLE,
}

enum class S23TransportTone {
    CONNECTED,
    TRANSITIONING,
    ERROR,
    UNAVAILABLE,
}

/**
 * Input and resolved state for the S23 Ultra's single compact transport cell.
 *
 * A usable LAN route always owns the cell. Telegram is shown only after LAN becomes unavailable;
 * this prevents a queued Telegram document from visually replacing a healthy live session.
 */
@Immutable
data class S23TransportCellModel(
    val lan: S23TransportLinkState,
    val telegram: S23TransportLinkState,
    val telegramUnreadCount: Int = 0,
    /** Lets the hybrid policy show a gray 텔 even before Telegram has been configured. */
    val preferredMode: S23TransportMode? = null,
) {
    val activeMode: S23TransportMode
        get() = when {
            preferredMode != null -> preferredMode
            lan != S23TransportLinkState.UNAVAILABLE -> S23TransportMode.LIVE
            telegram != S23TransportLinkState.UNAVAILABLE -> S23TransportMode.TELEGRAM
            else -> S23TransportMode.LIVE
        }

    val activeLinkState: S23TransportLinkState
        get() = when (activeMode) {
            S23TransportMode.LIVE -> lan
            S23TransportMode.TELEGRAM -> telegram
        }

    val activeTone: S23TransportTone
        get() = when (activeLinkState) {
            S23TransportLinkState.CONNECTED,
            S23TransportLinkState.READY,
            -> S23TransportTone.CONNECTED

            S23TransportLinkState.CONNECTING,
            S23TransportLinkState.QUEUED,
            -> S23TransportTone.TRANSITIONING

            S23TransportLinkState.ERROR -> S23TransportTone.ERROR

            S23TransportLinkState.UNAVAILABLE -> S23TransportTone.UNAVAILABLE
        }

    val label: String
        get() = when (activeMode) {
            S23TransportMode.LIVE -> "실"
            S23TransportMode.TELEGRAM -> "텔"
        }
}

internal fun s23TransportCellModelForLan(connection: LanConnectionState): S23TransportCellModel =
    S23TransportCellModel(
        lan = when (connection) {
            LanConnectionState.CONNECTED -> S23TransportLinkState.READY
            LanConnectionState.CONNECTING -> S23TransportLinkState.CONNECTING
            LanConnectionState.IDLE,
            LanConnectionState.DISCONNECTED,
            -> S23TransportLinkState.UNAVAILABLE
        },
        telegram = S23TransportLinkState.UNAVAILABLE,
    )

internal fun s23TransportCellModelForHybrid(
    decision: HybridLinkDecision?,
    legacyLanConnection: LanConnectionState,
    telegramUnreadCount: Int,
): S23TransportCellModel {
    if (decision == null) {
        return s23TransportCellModelForLan(legacyLanConnection).copy(
            telegramUnreadCount = telegramUnreadCount.coerceAtLeast(0),
        )
    }
    return when (decision.mode) {
        HybridLinkMode.LAN_LIVE -> S23TransportCellModel(
            lan = S23TransportLinkState.READY,
            telegram = S23TransportLinkState.UNAVAILABLE,
            telegramUnreadCount = telegramUnreadCount.coerceAtLeast(0),
            preferredMode = S23TransportMode.LIVE,
        )
        HybridLinkMode.LAN_GRACE -> S23TransportCellModel(
            lan = S23TransportLinkState.CONNECTING,
            telegram = S23TransportLinkState.UNAVAILABLE,
            telegramUnreadCount = telegramUnreadCount.coerceAtLeast(0),
            preferredMode = S23TransportMode.LIVE,
        )
        HybridLinkMode.TELEGRAM_FALLBACK -> S23TransportCellModel(
            lan = S23TransportLinkState.UNAVAILABLE,
            telegram = S23TransportLinkState.READY,
            telegramUnreadCount = telegramUnreadCount.coerceAtLeast(0),
            preferredMode = S23TransportMode.TELEGRAM,
        )
        HybridLinkMode.OFFLINE_QUEUEING -> S23TransportCellModel(
            lan = S23TransportLinkState.UNAVAILABLE,
            telegram = when (decision.health) {
                HybridLinkHealth.ERROR -> S23TransportLinkState.ERROR
                HybridLinkHealth.TRANSITIONING -> S23TransportLinkState.QUEUED
                HybridLinkHealth.READY -> S23TransportLinkState.READY
                HybridLinkHealth.INACTIVE -> S23TransportLinkState.UNAVAILABLE
            },
            telegramUnreadCount = telegramUnreadCount.coerceAtLeast(0),
            preferredMode = S23TransportMode.TELEGRAM,
        )
    }
}

internal fun s23UnreadBadgeLabel(unreadCount: Int): String? = when {
    unreadCount <= 0 -> null
    unreadCount > 9 -> "9+"
    else -> unreadCount.toString()
}

/**
 * The strip is deliberately a device exception, not another compact-width breakpoint. A tablet in
 * a narrow split-screen window and every other phone keep the established reader chrome.
 */
internal fun shouldUseS23UltraTopStrip(
    model: String,
    orientation: Int,
    role: ReaderRole,
): Boolean = role == ReaderRole.TEACHER_PHONE &&
    orientation == Configuration.ORIENTATION_PORTRAIT &&
    model.startsWith(S23_ULTRA_MODEL_PREFIX, ignoreCase = true)

@Composable
internal fun shouldUseS23UltraTopStrip(role: ReaderRole): Boolean =
    shouldUseS23UltraTopStrip(
        model = Build.MODEL.orEmpty(),
        orientation = LocalConfiguration.current.orientation,
        role = role,
    )

/** Same attempt-window policy as the shared chrome, with three physical cells reserved for it. */
internal fun s23VisibleAttemptBundles(
    bundles: List<ReaderAttemptMarkBundle>,
    selectedAttemptNo: Int,
): List<ReaderAttemptMarkBundle> {
    if (bundles.isEmpty()) return emptyList()
    val selectedIndex = bundles.indexOfFirst { it.attemptNo == selectedAttemptNo }
        .takeIf { it >= 0 }
        ?: bundles.lastIndex
    val end = maxOf(selectedIndex + 1, S23_STRIP_HISTORY_CELL_COUNT)
        .coerceAtMost(bundles.size)
    return bundles.subList(
        (end - S23_STRIP_HISTORY_CELL_COUNT).coerceAtLeast(0),
        end,
    )
}

/**
 * Galaxy S23 Ultra portrait-only reader chrome. The cells are structural, so every action
 * keeps the same rectangular hit target and the attempt lane can never be squeezed by its peers.
 */
@Composable
internal fun S23UltraTopStrip(
    state: ReaderUiState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onExitToLibrary: () -> Unit,
    onPreviousAttempt: () -> Unit,
    onNextAttempt: () -> Unit,
    onPublish: () -> Unit,
    onDismissDataError: () -> Unit,
    onSelectAttempt: (Int) -> Unit,
    onShowStudentActivity: () -> Unit,
    onResumeStudentFollow: () -> Unit,
    onOpenGptAssistant: () -> Unit = {},
    onOpenAnswerPdf: () -> Unit = {},
    transportCellModel: S23TransportCellModel =
        s23TransportCellModelForLan(state.liveConnection),
    onTransportClick: (S23TransportMode) -> Unit = { mode ->
        if (mode == S23TransportMode.LIVE) onResumeStudentFollow()
    },
    previewHoveredDescription: String? = null,
    markHistoryContent: (@Composable () -> Unit)? = null,
) {
    MaterialTheme {
        val tokens = readerChromeTokens(state.role)
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Display zoom can narrow the window, so scale every cell together rather than
            // clipping the last page button. GPT and answer remain separate direct actions.
            val cellWidth = minOf(40.dp, maxWidth / S23_STRIP_CELL_COUNT.toFloat())
            val stripWidth = cellWidth * S23_STRIP_CELL_COUNT
            val stripHeight = S23_STRIP_TOUCH_HEIGHT
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = 4.dp)
                    .width(stripWidth)
                    .height(stripHeight),
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .width(stripWidth)
                        .height(S23_STRIP_VISUAL_HEIGHT)
                        // Only the slim paper strip is visible. The transparent remainder is the
                        // generous input target and does not cover the workbook.
                        .background(tokens.paperSurface.copy(alpha = 0.15f), RectangleShape)
                        .border(
                            width = 0.5.dp,
                            color = tokens.paperStroke.copy(alpha = 0.25f),
                            shape = RectangleShape,
                        ),
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val step = size.width / S23_STRIP_CELL_COUNT
                        for (index in 1 until S23_STRIP_CELL_COUNT) {
                            drawLine(
                                color = tokens.paperStroke.copy(alpha = 0.22f),
                                start = Offset(step * index, 0f),
                                end = Offset(step * index, size.height),
                                strokeWidth = 0.5.dp.toPx(),
                            )
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxSize()) {
                    S23StripIconButton(
                        description = "이전 페이지",
                        iconRes = R.drawable.ic_page_prev,
                        onAction = onPrevious,
                        enabled = state.documentReady && state.pageNumber > 0,
                        role = state.role,
                        cellWidth = cellWidth,
                        forceHovered = previewHoveredDescription == "이전 페이지",
                    )
                    S23StripIconButton(
                        description = "교재 페이지로 돌아가기",
                        iconRes = R.drawable.ic_back_shelf,
                        onAction = onExitToLibrary,
                        role = state.role,
                        cellWidth = cellWidth,
                        forceHovered =
                            previewHoveredDescription == "교재 페이지로 돌아가기",
                    )
                    S23TransportCell(
                        model = transportCellModel,
                        onTransportClick = onTransportClick,
                        role = state.role,
                        cellWidth = cellWidth,
                        forceHovered = previewHoveredDescription == "학생 화면 다시 따라가기" ||
                            previewHoveredDescription == "전송 상태",
                    )
                    S23StudentPageOrActivityCell(
                        state = state,
                        onResumeStudentFollow = onResumeStudentFollow,
                        onShowStudentActivity = onShowStudentActivity,
                        role = state.role,
                        cellWidth = cellWidth,
                        forceHovered = previewHoveredDescription == "학생 필기량 보기" ||
                            previewHoveredDescription?.startsWith("학생 현재 페이지") == true,
                    )
                    S23StripButton(
                        description = "GPT 페이지 설명",
                        onAction = onOpenGptAssistant,
                        enabled = state.role != ReaderRole.STUDENT && state.documentReady,
                        role = state.role,
                        cellWidth = cellWidth,
                        forceHovered = previewHoveredDescription == "GPT 페이지 설명",
                    ) {
                        Text(
                            text = "GPT",
                            color = tokens.paletteBlue,
                            fontSize = 9.sp,
                            lineHeight = 10.sp,
                            fontWeight = FontWeight.Black,
                        )
                    }
                    S23StripButton(
                        description = "답안 PDF 열기",
                        onAction = onOpenAnswerPdf,
                        enabled = state.role != ReaderRole.STUDENT && state.documentReady,
                        role = state.role,
                        cellWidth = cellWidth,
                        forceHovered = previewHoveredDescription == "답안 PDF 열기",
                    ) {
                        Text(
                            text = "답",
                            color = tokens.paletteBlue,
                            fontSize = 10.sp,
                            lineHeight = 11.sp,
                            fontWeight = FontWeight.Black,
                        )
                    }
                    if (markHistoryContent != null) {
                        Box(
                            modifier = Modifier
                                .width(cellWidth * S23_STRIP_HISTORY_CELL_COUNT)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .width(cellWidth * S23_STRIP_HISTORY_CELL_COUNT)
                                    .height(S23_STRIP_VISUAL_HEIGHT),
                                contentAlignment = Alignment.Center,
                            ) {
                                markHistoryContent()
                            }
                        }
                    } else {
                        S23AttemptHistory(
                            state = state,
                            cellWidth = cellWidth,
                            onPreviousAttempt = onPreviousAttempt,
                            onNextAttempt = onNextAttempt,
                            onSelectAttempt = onSelectAttempt,
                        )
                    }
                    S23StripIconButton(
                        description = "첨삭 발행",
                        iconRes = R.drawable.ic_publish,
                        onAction = onPublish,
                        enabled = state.canPublishTeacherInkNow,
                        role = state.role,
                        cellWidth = cellWidth,
                        forceHovered = previewHoveredDescription == "첨삭 발행",
                    )
                    S23StripIconButton(
                        description = "다음 페이지",
                        iconRes = R.drawable.ic_page_next,
                        onAction = onNext,
                        enabled = state.documentReady && state.pageNumber + 1 < state.pageCount,
                        role = state.role,
                        cellWidth = cellWidth,
                        forceHovered = previewHoveredDescription == "다음 페이지",
                    )
                }
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

internal fun ReaderUiState.showsStudentPageShortcut(): Boolean {
    val remotePage = studentPageNumber ?: return false
    val remoteBook = studentBookId ?: bookId
    return capabilities.showsStudentLocation &&
        (bookId != remoteBook || pageNumber != remotePage ||
            studentAttemptNo != null && attemptNo != studentAttemptNo)
}

@Composable
private fun S23StudentPageOrActivityCell(
    state: ReaderUiState,
    onResumeStudentFollow: () -> Unit,
    onShowStudentActivity: () -> Unit,
    role: ReaderRole,
    cellWidth: Dp,
    forceHovered: Boolean,
) {
    val tokens = readerChromeTokens(role)
    val remotePage = state.studentPageNumber
    if (state.showsStudentPageShortcut() && remotePage != null) {
        val pageLabel = remotePage + 1
        val bookLabel = state.studentBookTitle?.takeIf { state.studentBookId != state.bookId }
        val attemptLabel = state.studentAttemptNo?.let { "${it}회" }
        S23StripButton(
            description = buildString {
                append("학생 현재 페이지 ").append(pageLabel).append("쪽")
                attemptLabel?.let { append(" ").append(it) }
                if (!state.studentPageReady) append(", 필기 동기화 대기 중")
            },
            onAction = onResumeStudentFollow,
            enabled = state.capabilities.showsStudentLocation,
            role = role,
            cellWidth = cellWidth,
            forceHovered = forceHovered,
        ) {
            Text(
                text = bookLabel?.let { "${it.take(3)}·$pageLabel" } ?: pageLabel.toString(),
                color = if (state.studentPageReady) tokens.paletteGreen else tokens.statusForeground,
                fontSize = 12.sp,
                lineHeight = 13.sp,
                fontWeight = FontWeight.Black,
            )
            attemptLabel?.let { label ->
                Text(
                    text = label,
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 1.dp, end = 2.dp),
                    color = tokens.statusForeground,
                    fontSize = 7.sp,
                    lineHeight = 8.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    } else {
        S23StripButton(
            description = "학생 필기량 보기",
            onAction = onShowStudentActivity,
            enabled = state.capabilities.showsStudentLocation,
            role = role,
            cellWidth = cellWidth,
            forceHovered = forceHovered,
        ) {
            S23ActivityBars(color = tokens.paletteBlue)
        }
    }
}

@Composable
private fun S23EmptyStripCell(cellWidth: Dp) {
    Box(modifier = Modifier.width(cellWidth).fillMaxHeight())
}

@Composable
private fun S23StripIconButton(
    description: String,
    iconRes: Int,
    onAction: () -> Unit,
    role: ReaderRole,
    cellWidth: Dp,
    enabled: Boolean = true,
    forceHovered: Boolean = false,
) {
    val tokens = readerChromeTokens(role)
    S23StripButton(
        description = description,
        onAction = onAction,
        enabled = enabled,
        role = role,
        cellWidth = cellWidth,
        forceHovered = forceHovered,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            // Disabled actions keep full information opacity; colour, not alpha, carries state.
            colorFilter = ColorFilter.tint(
                if (enabled) tokens.buttonForeground else tokens.statusForeground,
            ),
        )
    }
}

@Composable
private fun S23StripButton(
    description: String,
    onAction: () -> Unit,
    role: ReaderRole,
    cellWidth: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    forceHovered: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val tokens = readerChromeTokens(role)
    PenInteractionTarget(
        description = description,
        onAction = onAction,
        enabled = enabled,
        modifier = modifier.width(cellWidth).fillMaxHeight(),
        // Intentionally rectangular: reusing IconPenButton would reintroduce circular dead zones.
        circularHitTest = false,
        forceHoveredForPreview = forceHovered,
    ) { hovered, pressed ->
        val overlayAlpha = when {
            pressed -> 0.35f
            hovered -> 0.25f
            else -> 0f
        }
        val overlay by animateColorAsState(
            targetValue = tokens.paperHoverSurface.copy(alpha = overlayAlpha),
            animationSpec = tween(tokens.fadeDurationMillis),
            label = "s23-strip-cell-overlay",
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(S23_STRIP_VISUAL_HEIGHT)
                .background(overlay, RectangleShape),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

@Composable
private fun S23TransportCell(
    model: S23TransportCellModel,
    onTransportClick: (S23TransportMode) -> Unit,
    role: ReaderRole,
    cellWidth: Dp,
    forceHovered: Boolean,
) {
    val tokens = readerChromeTokens(role)
    val description = when (model.activeMode) {
        S23TransportMode.LIVE -> when (model.activeLinkState) {
            S23TransportLinkState.CONNECTED,
            S23TransportLinkState.READY,
            -> "실시간 연결됨"

            S23TransportLinkState.CONNECTING,
            S23TransportLinkState.QUEUED,
            -> "실시간 연결 중"

            S23TransportLinkState.ERROR -> "실시간 연결 오류"
            S23TransportLinkState.UNAVAILABLE -> "실시간 연결 없음"
        }
        S23TransportMode.TELEGRAM -> when (model.activeLinkState) {
            S23TransportLinkState.CONNECTED,
            S23TransportLinkState.READY,
            -> "Telegram 연결됨"

            S23TransportLinkState.CONNECTING -> "Telegram 연결 중"
            S23TransportLinkState.QUEUED -> "Telegram 전송 대기 중"
            S23TransportLinkState.ERROR -> "Telegram 연결 오류"
            S23TransportLinkState.UNAVAILABLE -> "Telegram 연결 없음"
        }
    }
    S23StripButton(
        description = description,
        onAction = { onTransportClick(model.activeMode) },
        role = role,
        cellWidth = cellWidth,
        forceHovered = forceHovered,
    ) {
        val foreground = when (model.activeTone) {
            S23TransportTone.CONNECTED -> tokens.paletteGreen
            S23TransportTone.TRANSITIONING -> tokens.paletteOrange
            S23TransportTone.ERROR -> tokens.palettePink
            S23TransportTone.UNAVAILABLE -> tokens.statusForeground
        }
        Text(
            text = model.label,
            color = foreground,
            fontSize = 12.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Black,
        )
        // Old Telegram unread state must not make the live-owned cell look like a second active
        // transport. It reappears on this same cell as soon as fallback selects 텔.
        s23UnreadBadgeLabel(model.telegramUnreadCount)
            ?.takeIf { model.activeMode == S23TransportMode.TELEGRAM }
            ?.let { badge ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-2).dp, y = 1.dp)
                    .defaultMinSize(minWidth = 13.dp, minHeight = 13.dp)
                    .background(tokens.palettePink, RoundedCornerShape(50))
                    .padding(horizontal = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = badge,
                    color = tokens.paletteCream,
                    fontSize = 7.sp,
                    lineHeight = 7.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun S23ActivityBars(color: Color) {
    Canvas(modifier = Modifier.size(17.dp)) {
        val gap = size.width * 0.16f
        val barWidth = (size.width - gap * 2f) / 3f
        listOf(0.45f, 1f, 0.7f).forEachIndexed { index, scale ->
            val height = size.height * scale
            drawRect(
                color = color,
                topLeft = Offset(index * (barWidth + gap), size.height - height),
                size = Size(barWidth, height),
            )
        }
    }
}

@Composable
private fun S23AttemptHistory(
    state: ReaderUiState,
    cellWidth: Dp,
    onPreviousAttempt: () -> Unit,
    onNextAttempt: () -> Unit,
    onSelectAttempt: (Int) -> Unit,
) {
    val bundles = remember(state.marks, state.pageNumber, state.attemptNo, state.pageAttemptNos) {
        readerAttemptMarkBundles(
            groups = state.marks,
            pageNumber = state.pageNumber,
            selectedAttemptNo = state.attemptNo,
            attemptNos = state.pageAttemptNos,
        )
    }
    val visibleBundles = s23VisibleAttemptBundles(bundles, state.attemptNo)
    val visibleAttemptNos = visibleBundles.map { it.attemptNo }
    val bundleBounds = remember(state.pageNumber, visibleAttemptNos) {
        mutableStateMapOf<Int, ClosedFloatingPointRange<Float>>()
    }
    val dragThresholdPx = with(LocalDensity.current) { 16.dp.toPx() }
    var stylusDragStartX by remember(state.pageNumber, state.attemptNo) {
        mutableStateOf<Float?>(null)
    }
    var stylusDragOffset by remember(state.pageNumber, state.attemptNo) {
        mutableFloatStateOf(0f)
    }
    val tokens = readerChromeTokens(state.role)

    Box(
        modifier = Modifier
            .width(cellWidth * S23_STRIP_HISTORY_CELL_COUNT)
            .fillMaxHeight()
            .clip(RectangleShape)
            .semantics {
                contentDescription = "최근 회차 문제별 정오답. S펜으로 좌우로 밀어 회차 이동"
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
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxSize()
                .offset {
                    IntOffset(stylusDragOffset.coerceIn(-20f, 20f).roundToInt(), 0)
                },
        ) {
            repeat(S23_STRIP_HISTORY_CELL_COUNT - visibleBundles.size) {
                S23EmptyStripCell(cellWidth)
            }
            visibleBundles.forEach { bundle ->
                val selected = bundle.attemptNo == state.attemptNo
                Box(
                    modifier = Modifier
                        .width(cellWidth)
                        .fillMaxHeight()
                        .onGloballyPositioned { coordinates ->
                            val bounds = coordinates.boundsInWindow()
                            bundleBounds[bundle.attemptNo] = bounds.left..bounds.right
                        }
                        .pointerInput(state.capabilities.canBrowseAttempts, bundle.attemptNo) {
                            if (!state.capabilities.canBrowseAttempts) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (!s23AttemptCellHandlesDirectPointer(down.type)) {
                                    return@awaitEachGesture
                                }
                                down.consume()
                                waitForUpOrCancellation()?.let { up ->
                                    up.consume()
                                    onSelectAttempt(bundle.attemptNo)
                                }
                            }
                        }
                        .semantics(mergeDescendants = true) {
                            role = Role.Button
                            contentDescription = "${bundle.attemptNo}회차 정오답"
                            this.selected = selected
                            if (state.capabilities.canBrowseAttempts) {
                                onClick(label = "${bundle.attemptNo}회차 열기") {
                                    onSelectAttempt(bundle.attemptNo)
                                    true
                                }
                            } else {
                                disabled()
                            }
                        },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(S23_STRIP_VISUAL_HEIGHT),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) {
                                        tokens.markPendingHighlight
                                    } else {
                                        tokens.markPendingBorder
                                    },
                                    shape = RoundedCornerShape(3.dp),
                                )
                                .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                                .padding(horizontal = 2.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            S23AttemptMarkMicroGrid(
                                colors = bundle.colors,
                                alpha = if (selected) 1f else tokens.markBundleDimAlpha,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun S23AttemptMarkMicroGrid(colors: List<MarkColor>, alpha: Float) {
    if (colors.isEmpty()) return
    val grid = remember(colors.size) { readerAttemptSummaryGrid(colors.size) }
    val naturalWidth = grid.columns * 3f + (grid.columns - 1).coerceAtLeast(0)
    val naturalHeight = grid.rows * 5f + (grid.rows - 1).coerceAtLeast(0)
    val scale = minOf(1f, 28f / naturalWidth, 24f / naturalHeight)
    val cellWidth = 3.dp * scale
    val cellHeight = 5.dp * scale
    val gap = 1.dp * scale
    val width = cellWidth * grid.columns + gap * (grid.columns - 1).coerceAtLeast(0)
    val height = cellHeight * grid.rows + gap * (grid.rows - 1).coerceAtLeast(0)
    val canvasTokens = readerCanvasTokens()
    Canvas(modifier = Modifier.size(width, height).alpha(alpha)) {
        val cellWidthPx = cellWidth.toPx()
        val cellHeightPx = cellHeight.toPx()
        val gapPx = gap.toPx()
        val radius = minOf(cellWidthPx, cellHeightPx) * 0.42f
        colors.forEachIndexed { index, color ->
            val cell = grid.cells[index]
            drawRoundRect(
                color = Color(
                    when (color) {
                        MarkColor.BLUE -> canvasTokens.markBlueArgb
                        MarkColor.RED -> canvasTokens.markRedArgb
                        MarkColor.GRAY -> canvasTokens.markGrayArgb
                    },
                ),
                topLeft = Offset(
                    x = cell.column * (cellWidthPx + gapPx),
                    y = cell.row * (cellHeightPx + gapPx),
                ),
                size = Size(cellWidthPx, cellHeightPx),
                cornerRadius = CornerRadius(radius, radius),
            )
        }
    }
}

@Preview(
    name = "S23 Ultra · 반투명 10칸 띠",
    widthDp = 412,
    heightDp = 76,
    showBackground = true,
)
@Composable
private fun S23UltraTopStripPreview() {
    val page = 2
    val marks = List(8) { index ->
        MarkGroup(
            id = "s23-preview-$index",
            bookId = "preview",
            pageNumber = page,
            anchor = PagePoint((index % 2) * 100f, (index / 2) * 100f),
            marks = (1..4).map { attempt ->
                Mark(
                    attemptNo = attempt,
                    color = if ((index + attempt) % 4 == 0) MarkColor.RED else MarkColor.BLUE,
                )
            },
        )
    }
    S23UltraTopStrip(
        state = ReaderUiState(
            bookId = "preview",
            bookTitle = "학생 풀이 검토",
            pageCount = 24,
            documentReady = true,
            pageNumber = page,
            attemptNo = 4,
            role = ReaderRole.TEACHER_PHONE,
            workflow = ReaderWorkflow.LIVE_MONITOR,
            capabilities = ReaderCapabilities.forSession(
                ReaderRole.TEACHER_PHONE,
                ReaderWorkflow.LIVE_MONITOR,
                4,
            ),
            marks = marks,
            pageAttemptNos = listOf(1, 2, 3, 4),
            studentPageNumber = page,
            isFollowingStudent = true,
            liveConnection = LanConnectionState.CONNECTED,
        ),
        onPrevious = {},
        onNext = {},
        onExitToLibrary = {},
        onPreviousAttempt = {},
        onNextAttempt = {},
        onPublish = {},
        onDismissDataError = {},
        onSelectAttempt = {},
        onShowStudentActivity = {},
        onResumeStudentFollow = {},
    )
}
