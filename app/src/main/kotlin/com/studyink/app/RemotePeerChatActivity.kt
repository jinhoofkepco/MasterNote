package com.studyink.app

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.ViewGroup
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.studyink.monitor.core.RemotePeerChatDirection
import com.studyink.monitor.core.RemotePeerChatState
import com.studyink.monitor.core.RemotePeerChatStateBus
import com.studyink.monitor.telegram.RemoteReviewPeerStatus
import com.studyink.monitor.telegram.RemoteMonitorGateway
import com.studyink.monitor.telegram.RemoteReviewRole
import com.studyink.monitor.telegram.TelegramEnqueueResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Small typed conversation carried inside the encrypted bot-to-bot peer channel. */
class RemotePeerChatActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MasterNote-peer-chat-ui").apply { isDaemon = true }
    }
    private lateinit var gateway: RemoteMonitorGateway
    private lateinit var statusText: TextView
    private lateinit var messageList: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var pageButton: Button
    private var stateSubscription: AutoCloseable? = null
    private var renderedRevision = Long.MIN_VALUE
    private var sendBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        gateway = RemoteMonitorGateway.get(this)
        setContentView(buildContent())
    }

    override fun onStart() {
        super.onStart()
        stateSubscription?.close()
        stateSubscription = RemotePeerChatStateBus.subscribe { state ->
            runOnUiThread { renderIfCurrent(state) }
        }
        refreshCurrentState()
    }

    override fun onResume() {
        super.onResume()
        // Pairing can change in the setup activity without producing a chat-state event.
        refreshCurrentState()
    }

    override fun onStop() {
        stateSubscription?.close()
        stateSubscription = null
        super.onStop()
    }

    override fun onDestroy() {
        stateSubscription?.close()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun buildContent(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(9), dp(8), dp(9))
            setBackgroundColor(COLOR_HEADER)
        }
        statusText = TextView(this).apply {
            text = "텔 대화"
            textSize = 17f
            setTextColor(COLOR_TEXT)
            setTypeface(typeface, Typeface.BOLD)
        }
        header.addView(statusText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        pageButton = smallButton("페이지").apply {
            contentDescription = "받은 페이지 열기"
            visibility = View.GONE
            setOnClickListener {
                startActivity(Intent(this@RemotePeerChatActivity, RemoteReviewActivity::class.java))
            }
        }
        header.addView(pageButton)
        header.addView(smallButton("연결").apply {
            setOnClickListener {
                startActivity(Intent(this@RemotePeerChatActivity, RemoteReviewSetupActivity::class.java))
            }
        })
        header.addView(smallButton("닫기").apply { setOnClickListener { finish() } })
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        messageList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(12), dp(10), dp(12))
        }
        scrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(messageList, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(8), dp(7), dp(8), dp(9))
            setBackgroundColor(COLOR_HEADER)
        }
        input = EditText(this).apply {
            hint = "조용히 타자로 메시지 보내기"
            textSize = 16f
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            minLines = 1
            maxLines = 4
            filters = arrayOf(InputFilter.LengthFilter(MAX_INPUT_CHARS))
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(false)
            background = rounded(COLOR_INPUT, 12f)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendCurrentText()
                    true
                } else {
                    false
                }
            }
        }
        sendButton = smallButton("보내기").apply {
            setOnClickListener { sendCurrentText() }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        composer.addView(sendButton, LinearLayout.LayoutParams(dp(74), dp(52)).apply {
            marginStart = dp(7)
        })
        root.addView(composer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        applyWindowInsets(root, header, composer)
        return root
    }

    private fun applyWindowInsets(root: View, header: View, composer: View) {
        val headerLeft = dp(12)
        val headerTop = dp(9)
        val headerRight = dp(8)
        val headerBottom = dp(9)
        val composerHorizontal = dp(8)
        val composerTop = dp(7)
        val composerBottom = dp(9)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            header.setPadding(
                headerLeft + bars.left,
                headerTop + bars.top,
                headerRight + bars.right,
                headerBottom,
            )
            composer.setPadding(
                composerHorizontal + bars.left,
                composerTop,
                composerHorizontal + bars.right,
                composerBottom + maxOf(bars.bottom, ime.bottom),
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun refreshCurrentState() {
        val state = MasterNoteRemoteReviewCoordinator.remotePeerChatState()
        if (state == null) {
            showDisconnected()
            return
        }
        renderIfCurrent(state, force = true)
    }

    private fun renderIfCurrent(state: RemotePeerChatState, force: Boolean = false) {
        val peer = gateway.remoteReviewPeerStatus() as? RemoteReviewPeerStatus.Connected
            ?: run {
                showDisconnected()
                return
            }
        if (state.scope.pairId != peer.pairId) return
        statusText.text = "텔 대화 · @${peer.peer.username}"
        pageButton.visibility = if (peer.role == RemoteReviewRole.TEACHER) View.VISIBLE else View.GONE
        input.isEnabled = !sendBusy
        sendButton.isEnabled = !sendBusy
        if (!force && state.stateRevision == renderedRevision) return
        renderedRevision = state.stateRevision
        messageList.removeAllViews()
        if (state.recentMessages.isEmpty()) {
            showEmpty("아직 메시지가 없습니다.")
        } else {
            state.recentMessages.forEach { message ->
                val row = FrameLayout(this)
                val incoming = message.direction == RemotePeerChatDirection.INCOMING
                val time = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)
                    .format(Date(message.sentAtEpochMs))
                val bubble = TextView(this).apply {
                    text = buildString {
                        append(if (incoming) "상대" else "나")
                        append(" · ").append(time).append('\n').append(message.text)
                    }
                    textSize = 15f
                    setTextColor(COLOR_TEXT)
                    setPadding(dp(13), dp(9), dp(13), dp(10))
                    background = rounded(if (incoming) COLOR_INCOMING else COLOR_OUTGOING, 13f)
                    maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
                }
                row.addView(
                    bubble,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        if (incoming) Gravity.START else Gravity.END,
                    ),
                )
                messageList.addView(
                    row,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(7)
                    },
                )
            }
        }
        scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        if (state.unreadCount > 0) {
            worker.execute { runCatching { MasterNoteRemoteReviewCoordinator.markRemotePeerChatRead() } }
        }
    }

    private fun showDisconnected() {
        renderedRevision = Long.MIN_VALUE
        statusText.text = "텔 대화 · 연결 필요"
        pageButton.visibility = View.GONE
        input.isEnabled = false
        sendButton.isEnabled = false
        messageList.removeAllViews()
        showEmpty("Telegram bot-to-bot 연결을 먼저 완료해주세요.")
    }

    private fun showEmpty(text: String) {
        messageList.addView(TextView(this).apply {
            this.text = text
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(COLOR_MUTED)
            setPadding(dp(20), dp(48), dp(20), dp(20))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun sendCurrentText() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() || sendBusy) return
        sendBusy = true
        input.isEnabled = false
        sendButton.isEnabled = false
        worker.execute {
            val result = runCatching { MasterNoteRemoteReviewCoordinator.sendRemotePeerChat(text) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                sendBusy = false
                val enqueue = result.getOrNull()
                if (enqueue?.accepted() == true) {
                    input.text?.clear()
                } else {
                    val message = when (enqueue) {
                        TelegramEnqueueResult.PREVIOUSLY_SUPERSEDED ->
                            "실시간 연결 중이거나 메시지가 너무 깁니다. 텔로 전환된 뒤 다시 보내주세요."
                        TelegramEnqueueResult.QUEUE_FULL -> "전송 대기열이 가득 찼습니다. 잠시 뒤 다시 보내주세요."
                        TelegramEnqueueResult.CHAT_CHANGED,
                        TelegramEnqueueResult.NOT_CONFIGURED,
                        -> "Telegram bot-to-bot 연결을 확인해주세요."
                        else -> "메시지를 저장하지 못했습니다. 다시 시도해주세요."
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
                refreshCurrentState()
            }
        }
    }

    private fun TelegramEnqueueResult.accepted(): Boolean =
        this == TelegramEnqueueResult.ENQUEUED ||
            this == TelegramEnqueueResult.ALREADY_PENDING ||
            this == TelegramEnqueueResult.ALREADY_DELIVERED

    private fun smallButton(label: String) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        minWidth = dp(58)
        minHeight = dp(42)
        setTextColor(COLOR_TEXT)
        backgroundTintList = ColorStateList.valueOf(COLOR_BUTTON)
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX_INPUT_CHARS = 1_000
        const val COLOR_BACKGROUND = 0xFFF2F0EA.toInt()
        const val COLOR_HEADER = 0xFFFDFBF6.toInt()
        const val COLOR_INPUT = 0xFFFFFFFF.toInt()
        const val COLOR_BUTTON = 0xFFE4E8ED.toInt()
        const val COLOR_INCOMING = 0xFFFFFFFF.toInt()
        const val COLOR_OUTGOING = 0xFFDCEBFF.toInt()
        const val COLOR_TEXT = 0xFF24272C.toInt()
        const val COLOR_MUTED = 0xFF6C727B.toInt()
    }
}
