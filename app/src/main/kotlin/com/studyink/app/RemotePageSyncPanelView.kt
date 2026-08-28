package com.studyink.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Compact, non-modal controls for synchronizing pending student pages from the peer chat. */
class RemotePageSyncPanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val summaryText: TextView
    private val pageList: LinearLayout
    private val pageScroller: ScrollView
    private val thirtySecondButton: Button
    private val oneMinuteButton: Button
    private val actionButton: Button
    private var renderedState: RemotePageSyncUiState? = null

    var onStartRequested: (intervalSeconds: Int) -> Unit = {}
    var onPauseRequested: () -> Unit = {}
    var onWorkbookMappingRequested: (pageToken: String) -> Unit = {}

    var commandInProgress: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            renderedState?.let(::refreshControls)
        }

    var selectedIntervalSeconds: Int = DEFAULT_REMOTE_PAGE_SYNC_INTERVAL_SECONDS
        set(value) {
            val normalized = normalizeRemotePageSyncInterval(value)
            if (field == normalized) return
            field = normalized
            refreshIntervalButtons(renderedState?.running == true)
        }

    init {
        orientation = VERTICAL
        visibility = View.GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setPadding(dp(10), dp(8), dp(10), dp(9))
        background = rounded(COLOR_PANEL, 13f, COLOR_PANEL_BORDER)

        summaryText = TextView(context).apply {
            textSize = 14f
            setTextColor(COLOR_TEXT)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        addView(
            summaryText,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        pageList = LinearLayout(context).apply {
            orientation = VERTICAL
        }
        pageScroller = ScrollView(context).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(
                pageList,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        addView(
            pageScroller,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
            },
        )

        val controls = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        thirtySecondButton = intervalButton("30초", FAST_REMOTE_PAGE_SYNC_INTERVAL_SECONDS)
        oneMinuteButton = intervalButton("1분", DEFAULT_REMOTE_PAGE_SYNC_INTERVAL_SECONDS)
        actionButton = panelButton("최신순 동기화").apply {
            contentDescription = "대기 페이지를 최신순으로 동기화"
            setOnClickListener {
                val state = renderedState ?: return@setOnClickListener
                if (state.running) {
                    onPauseRequested()
                } else if (state.connected &&
                    (state.pendingPages.isNotEmpty() || !state.inventoryComplete)
                ) {
                    onStartRequested(selectedIntervalSeconds)
                }
            }
        }
        controls.addView(thirtySecondButton, LayoutParams(dp(62), dp(42)))
        controls.addView(oneMinuteButton, LayoutParams(dp(62), dp(42)).apply {
            marginStart = dp(5)
        })
        controls.addView(actionButton, LayoutParams(0, dp(42), 1f).apply {
            marginStart = dp(8)
        })
        addView(
            controls,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(7)
            },
        )
        refreshIntervalButtons(running = false)
    }

    /** Rendering never announces itself; the panel is status, not a new-page notification. */
    fun render(state: RemotePageSyncUiState) {
        renderedState = state
        val shouldShow = shouldShowRemotePageSyncPanel(state)
        visibility = if (shouldShow) View.VISIBLE else View.GONE
        if (!shouldShow) {
            pageList.removeAllViews()
            return
        }

        if (state.running) {
            selectedIntervalSeconds = normalizeRemotePageSyncInterval(state.intervalSeconds)
        }
        summaryText.text = formatRemotePageSyncSummary(state)
        summaryText.contentDescription = buildString {
            append(formatRemotePageSyncSummary(state))
            if (!state.connected) append(". 학생 기기 연결 대기 중")
        }

        pageList.removeAllViews()
        val activePage = state.activePage
        activePage?.let { page -> pageList.addView(pageRow(page, active = true)) }
        val pages = remotePageSyncPagesLatestFirst(state.pendingPages).filterNot { page ->
            activePage != null && page.pageToken == activePage.pageToken
        }
        pages.forEach { page -> pageList.addView(pageRow(page, active = false)) }
        val visibleRowCount = pages.size + if (activePage == null) 0 else 1
        pageScroller.layoutParams = (pageScroller.layoutParams as LayoutParams).apply {
            height = if (visibleRowCount > MAX_VISIBLE_PAGE_ROWS) {
                dp(PAGE_LIST_MAX_HEIGHT_DP)
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }
        }

        refreshControls(state)
    }

    private fun refreshControls(state: RemotePageSyncUiState) {
        refreshIntervalButtons(state.running || commandInProgress)
        actionButton.text = if (state.running) "일시정지" else "최신순 동기화"
        actionButton.contentDescription = if (state.running) {
            "페이지 동기화 일시정지"
        } else {
            "대기 페이지를 최신순으로 동기화"
        }
        actionButton.isEnabled = !commandInProgress && (state.running || state.connected)
        actionButton.alpha = if (actionButton.isEnabled) 1f else 0.45f
    }

    private fun intervalButton(label: String, seconds: Int): Button = panelButton(label).apply {
        contentDescription = "페이지 동기화 간격 $label"
        setOnClickListener { selectedIntervalSeconds = seconds }
    }

    private fun refreshIntervalButtons(running: Boolean) {
        listOf(
            thirtySecondButton to FAST_REMOTE_PAGE_SYNC_INTERVAL_SECONDS,
            oneMinuteButton to DEFAULT_REMOTE_PAGE_SYNC_INTERVAL_SECONDS,
        ).forEach { (button, seconds) ->
            val selected = selectedIntervalSeconds == seconds
            button.isSelected = selected
            button.isEnabled = !running
            button.alpha = if (running && !selected) 0.45f else 1f
            button.setTextColor(if (selected) Color.WHITE else COLOR_TEXT)
            button.backgroundTintList = ColorStateList.valueOf(
                if (selected) COLOR_SELECTED else COLOR_BUTTON,
            )
            button.contentDescription = buildString {
                append("페이지 동기화 간격 ").append(button.text)
                if (selected) append(", 선택됨")
                if (running) append(", 동기화 중에는 변경할 수 없음")
            }
        }
    }

    private fun pageRow(page: RemotePageSyncPageUi, active: Boolean): View {
        val status = formatRemotePageSyncPageStatus(page)
        val queuePrefix = if (page.queueMode == RemotePageSyncQueueMode.AUTOMATIC) "자동 · " else ""
        val attempts = page.attemptNos.asSequence()
            .filter { it > 0 }
            .distinct()
            .sorted()
            .joinToString(", ") { "${it}회" }
            .ifBlank { "회차 확인 중" }
        val changed = if (page.lastChangedEpochMs > 0L) {
            SimpleDateFormat("MM/dd HH:mm", Locale.KOREA).format(Date(page.lastChangedEpochMs))
        } else {
            "변경 시각 확인 중"
        }

        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), dp(6), dp(9), dp(6))
            background = rounded(
                color = if (active) COLOR_ACTIVE_PAGE else COLOR_PAGE_ROW,
                radiusDp = 9f,
            )
            contentDescription = buildString {
                if (queuePrefix.isNotEmpty()) append("자동 동기화, ")
                append(page.workbookLabel).append(", ").append(page.pageNumber).append("쪽, ").append(attempts)
                append(", ").append(status)
                append(", 약 ").append(formatRemotePageSyncBytes(page.approxBytes))
                append(", ").append(changed)
            }

            val identity = TextView(context).apply {
                text = "$queuePrefix${page.workbookLabel} · ${page.pageNumber}쪽 · $attempts\n" +
                    "약 ${formatRemotePageSyncBytes(page.approxBytes)} · $changed"
                textSize = 13f
                setTextColor(COLOR_TEXT)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                if (active) setTypeface(typeface, Typeface.BOLD)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val statusText = pageStatusView(page, status)
            addView(identity, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(statusText, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            })
        }.also { row ->
            if (page.status == RemotePageSyncPageStatus.MAPPING_REQUIRED && page.pageToken.isNotBlank()) {
                row.isClickable = true
                row.isFocusable = true
                row.setOnClickListener { onWorkbookMappingRequested(page.pageToken) }
                row.contentDescription = "${row.contentDescription}. 눌러서 교재 선택"
            }
            row.layoutParams = LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(4) }
        }
    }

    private fun pageStatusView(page: RemotePageSyncPageUi, status: String): View =
        FrameLayout(context).apply {
            background = statusBackground(page)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(
                TextView(context).apply {
                    text = status
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTextColor(page.status.textColor())
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }

    private fun statusBackground(page: RemotePageSyncPageUi) =
        remotePageSyncProgressFraction(page.progress)
            ?.takeIf { page.status == RemotePageSyncPageStatus.SYNCING }
            ?.let { fraction ->
                val fill = ClipDrawable(
                    rounded(COLOR_SYNCING_PROGRESS, 8f),
                    Gravity.START,
                    ClipDrawable.HORIZONTAL,
                ).apply { level = (fraction * CLIP_DRAWABLE_MAX_LEVEL).toInt() }
                LayerDrawable(
                    arrayOf(
                        rounded(page.status.backgroundColor(), 8f),
                        fill,
                    ),
                )
            }
            ?: rounded(page.status.backgroundColor(), 8f)

    private fun RemotePageSyncPageStatus.textColor(): Int = when (this) {
        RemotePageSyncPageStatus.READY -> COLOR_READY_TEXT
        RemotePageSyncPageStatus.SYNCING -> COLOR_SYNCING_TEXT
        RemotePageSyncPageStatus.FAILED -> COLOR_FAILED_TEXT
        RemotePageSyncPageStatus.WAITING,
        RemotePageSyncPageStatus.DEVICE_OFFLINE,
        RemotePageSyncPageStatus.MAPPING_REQUIRED,
        -> COLOR_MUTED
    }

    private fun RemotePageSyncPageStatus.backgroundColor(): Int = when (this) {
        RemotePageSyncPageStatus.READY -> COLOR_READY_BACKGROUND
        RemotePageSyncPageStatus.SYNCING -> COLOR_SYNCING_BACKGROUND
        RemotePageSyncPageStatus.FAILED -> COLOR_FAILED_BACKGROUND
        RemotePageSyncPageStatus.WAITING,
        RemotePageSyncPageStatus.DEVICE_OFFLINE,
        RemotePageSyncPageStatus.MAPPING_REQUIRED,
        -> COLOR_STATUS_BACKGROUND
    }

    private fun panelButton(label: String) = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        minWidth = 0
        minHeight = dp(42)
        setPadding(dp(7), 0, dp(7), 0)
        setTextColor(COLOR_TEXT)
        backgroundTintList = ColorStateList.valueOf(COLOR_BUTTON)
    }

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            strokeColor?.let { setStroke(dp(1), it) }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX_VISIBLE_PAGE_ROWS = 3
        const val PAGE_LIST_MAX_HEIGHT_DP = 154
        const val CLIP_DRAWABLE_MAX_LEVEL = 10_000
        const val COLOR_PANEL = 0xFFF8FAFD.toInt()
        const val COLOR_PANEL_BORDER = 0xFFD8DEE8.toInt()
        const val COLOR_PAGE_ROW = 0xFFFFFFFF.toInt()
        const val COLOR_ACTIVE_PAGE = 0xFFEAF2FF.toInt()
        const val COLOR_BUTTON = 0xFFE4E8ED.toInt()
        const val COLOR_SELECTED = 0xFF315C96.toInt()
        const val COLOR_TEXT = 0xFF24272C.toInt()
        const val COLOR_MUTED = 0xFF6C727B.toInt()
        const val COLOR_STATUS_BACKGROUND = 0xFFE9ECF0.toInt()
        const val COLOR_READY_BACKGROUND = 0xFFDDF4E5.toInt()
        const val COLOR_READY_TEXT = 0xFF23653A.toInt()
        const val COLOR_SYNCING_BACKGROUND = 0xFFFFE9C8.toInt()
        const val COLOR_SYNCING_PROGRESS = 0xFF9AD5AA.toInt()
        const val COLOR_SYNCING_TEXT = 0xFF805200.toInt()
        const val COLOR_FAILED_BACKGROUND = 0xFFFFE0E0.toInt()
        const val COLOR_FAILED_TEXT = 0xFF9A2B2B.toInt()
    }
}
