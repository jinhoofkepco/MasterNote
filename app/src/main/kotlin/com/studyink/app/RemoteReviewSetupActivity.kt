package com.studyink.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.integration.android.IntentIntegrator
import com.studyink.monitor.telegram.RemoteMonitorGateway
import com.studyink.monitor.telegram.RemoteReviewPairingPayload
import com.studyink.monitor.telegram.RemoteReviewPeerStatus
import com.studyink.monitor.telegram.RemoteReviewRole
import com.studyink.monitor.telegram.TelegramApiException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Adds a second, bot-to-bot pairing on top of the existing human parent Telegram connection.
 * Neither the local bot token nor workbook metadata is placed in the QR code.
 */
class RemoteReviewSetupActivity : Activity() {
    private lateinit var gateway: RemoteMonitorGateway
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MasterNote-remote-review-setup").apply { isDaemon = true }
    }

    private lateinit var statusText: TextView
    private lateinit var qrImage: ImageView
    private lateinit var qrGuide: TextView
    private lateinit var studentButton: Button
    private lateinit var teacherButton: Button
    private lateinit var retryButton: Button
    private lateinit var inboxButton: Button
    private lateinit var clearButton: Button
    private var displayedPayload: RemoteReviewPairingPayload? = null
    private var qrBitmap: Bitmap? = null
    private var busy = false

    private val refreshStatus = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            renderState()
            window.decorView.postDelayed(this, STATUS_REFRESH_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gateway = RemoteMonitorGateway.get(applicationContext)
        setContentView(buildContent())
        bindActions()
    }

    override fun onResume() {
        super.onResume()
        window.decorView.removeCallbacks(refreshStatus)
        window.decorView.post(refreshStatus)
    }

    override fun onPause() {
        window.decorView.removeCallbacks(refreshStatus)
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        qrImage.setImageDrawable(null)
        qrBitmap?.recycle()
        qrBitmap = null
        super.onDestroy()
    }

    @Deprecated("JourneyApps scanner callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result == null) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val encoded = result.contents ?: return
        runSetup("학생 기기를 확인하고 있어요…") {
            gateway.acceptStudentPairingPayload(encoded)
            // This device is now the review receiver, not a student activity reporter. Keep the
            // same bot credentials but suppress idle/hourly reports from the teacher phone.
            gateway.updatePreferences {
                it.copy(monitoringEnabled = false, realtimeActivityEnabled = false)
            }
            RemoteMonitorService.startForRemoteReview(applicationContext)
            "학생 기기로 연결 요청을 보냈습니다. 두 기기가 확인되면 자동으로 완료됩니다."
        }
    }

    private fun buildContent(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(COLOR_BACKGROUND)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(32))
        }
        scroll.addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(horizontalRow().apply {
            addView(title("원격 페이지 첨삭"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(secondaryButton("닫기").apply { setOnClickListener { finish() } })
        })
        root.addView(body(
            "학생 기기는 약 1분 간격과 제출·페이지 이동 때 필기 포함 페이지를 보냅니다. " +
                "선생 기기에서 그 위에 첨삭해 돌려보낼 수 있습니다. 채점·회차·학생 필기는 바뀌지 않습니다.",
        ).apply { setPadding(0, dp(10), 0, dp(14)) })

        statusText = TextView(this).apply {
            textSize = 16f
            setTextColor(COLOR_TEXT)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedBackground(COLOR_STATUS, 14f)
        }
        root.addView(statusText, matchWidth(bottom = 14))

        val panel = verticalPanel()
        panel.addView(sectionTitle("1. Telegram 준비"))
        panel.addView(body(
            "두 기기마다 서로 다른 전용 봇을 연결하고, BotFather의 각 봇 설정에서 " +
                "Bot-to-Bot Communication Mode를 모두 켜세요. 기존 부모 메시지 기능은 그대로 유지됩니다.",
        ))
        panel.addView(secondaryButton("기존 Telegram 봇 설정 열기").apply {
            setOnClickListener { startActivity(Intent(this@RemoteReviewSetupActivity, TelegramSetupActivity::class.java)) }
        }, matchWidth(height = 50, top = 12))
        root.addView(panel, matchWidth(bottom = 14))

        val pairingPanel = verticalPanel()
        pairingPanel.addView(sectionTitle("2. 두 기기 연결"))
        pairingPanel.addView(body("학생 기기에서 QR을 만든 뒤 선생 기기로 스캔하세요. QR은 15분 뒤 만료되며 봇 토큰은 포함하지 않습니다."))
        studentButton = primaryButton("이 기기는 학생 · QR 만들기")
        teacherButton = primaryButton("이 기기는 선생 · 학생 QR 스캔")
        retryButton = secondaryButton("연결 요청 다시 보내기").apply { visibility = View.GONE }
        pairingPanel.addView(studentButton, matchWidth(height = 52, top = 12))
        pairingPanel.addView(teacherButton, matchWidth(height = 52, top = 8))
        pairingPanel.addView(retryButton, matchWidth(height = 50, top = 8))
        qrImage = ImageView(this).apply {
            adjustViewBounds = true
            contentDescription = "원격 첨삭 학생 기기 연결 QR"
            visibility = View.GONE
            setBackgroundColor(Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        pairingPanel.addView(qrImage, LinearLayout.LayoutParams(dp(290), dp(290)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(14)
        })
        qrGuide = body("").apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            setTextColor(COLOR_ACCENT_DARK)
            setTypeface(typeface, Typeface.BOLD)
        }
        pairingPanel.addView(qrGuide, matchWidth(top = 8))
        root.addView(pairingPanel, matchWidth(bottom = 14))

        val actions = verticalPanel()
        actions.addView(sectionTitle("3. 첨삭"))
        actions.addView(body("선생 기기에는 받은 페이지가 내구 저장됩니다. 전송 실패 시 재시도되며 중복 첨삭은 한 번만 적용됩니다."))
        inboxButton = primaryButton("받은 페이지 열기")
        clearButton = secondaryButton("원격 첨삭 연결만 해제").apply { setTextColor(COLOR_DANGER) }
        actions.addView(inboxButton, matchWidth(height = 52, top = 12))
        actions.addView(clearButton, matchWidth(height = 50, top = 8))
        root.addView(actions, matchWidth())
        return scroll
    }

    private fun bindActions() {
        studentButton.setOnClickListener { confirmReplacingPeer { createStudentQr() } }
        teacherButton.setOnClickListener { confirmReplacingPeer { launchScanner() } }
        retryButton.setOnClickListener {
            runSetup("연결 요청을 다시 보내고 있어요…") {
                gateway.retryRemoteReviewHandshake()
                RemoteMonitorService.startForRemoteReview(applicationContext)
                "연결 요청을 다시 보냈습니다."
            }
        }
        inboxButton.setOnClickListener { startActivity(Intent(this, RemoteReviewActivity::class.java)) }
        clearButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("원격 첨삭 연결을 해제할까요?")
                .setMessage("부모 텍스트 연결과 이미 저장된 학생 필기·받은 페이지는 유지됩니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("해제") { _, _ ->
                    runSetup("원격 첨삭 연결을 해제하고 있어요…") {
                        gateway.clearRemoteReviewPeer()
                        displayedPayload = null
                        if (gateway.preferences().monitoringEnabled) {
                            RemoteMonitorService.startIfEnabled(applicationContext)
                        } else {
                            RemoteMonitorService.stop(applicationContext)
                        }
                        "원격 첨삭 연결만 해제했습니다."
                    }
                }
                .show()
        }
    }

    private fun confirmReplacingPeer(action: () -> Unit) {
        if (gateway.remoteReviewPeerStatus() == RemoteReviewPeerStatus.Unconfigured) {
            action()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("새 원격 첨삭 연결을 만들까요?")
            .setMessage("현재 원격 첨삭 상대와의 전송 대기열은 폐기됩니다. 부모 텍스트 연결과 학생 필기는 유지됩니다.")
            .setNegativeButton("취소", null)
            .setPositiveButton("새로 연결") { _, _ -> action() }
            .show()
    }

    private fun createStudentQr() {
        runSetup("안전한 연결 QR을 만들고 있어요…") {
            val payload = gateway.createStudentPairingPayload()
            val bitmap = createQr(payload.encoded, QR_SIZE)
            displayedPayload = payload
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    bitmap.recycle()
                    return@runOnUiThread
                }
                qrImage.setImageDrawable(null)
                qrBitmap?.recycle()
                qrBitmap = bitmap
                qrImage.setImageBitmap(bitmap)
                qrImage.visibility = View.VISIBLE
                qrGuide.visibility = View.VISIBLE
            }
            RemoteMonitorService.startForRemoteReview(applicationContext)
            "학생 QR을 만들었습니다. 선생 기기로 스캔하세요."
        }
    }

    private fun launchScanner() {
        if (gateway.configuredChatId() == null) {
            Toast.makeText(this, "먼저 이 기기의 Telegram 전용 봇을 연결해주세요.", Toast.LENGTH_LONG).show()
            return
        }
        IntentIntegrator(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt("학생 기기의 원격 첨삭 QR을 비춰 주세요")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .initiateScan()
    }

    private fun runSetup(progress: String, action: () -> String) {
        if (busy || worker.isShutdown) return
        busy = true
        statusText.text = progress
        renderButtons()
        worker.execute {
            runCatching(action).fold(
                onSuccess = { message ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        busy = false
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                        renderState()
                    }
                },
                onFailure = { error ->
                    val message = error.safeMessage()
                    Log.w(LOG_TAG, "Remote-review setup failed: $message")
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        busy = false
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                        renderState()
                    }
                },
            )
        }
    }

    private fun renderState() {
        val localConfigured = gateway.configuredChatId() != null
        val state = gateway.remoteReviewPeerStatus()
        statusText.text = when {
            !localConfigured -> "먼저 이 기기의 Telegram 전용 봇을 연결하세요."
            state is RemoteReviewPeerStatus.Unconfigured -> "원격 첨삭 상대가 연결되지 않았습니다."
            state is RemoteReviewPeerStatus.WaitingForTeacher ->
                "학생 기기 · 선생 기기 스캔 대기 · ${formatExpiry(state.expiresAtEpochMs)} 만료"
            state is RemoteReviewPeerStatus.WaitingForStudentAck ->
                "선생 기기 · 학생 기기 확인 대기 · ${formatExpiry(state.expiresAtEpochMs)} 만료"
            state is RemoteReviewPeerStatus.Connected -> {
                val role = if (state.role == RemoteReviewRole.STUDENT) "학생" else "선생"
                "$role 기기 · @${state.peer.username} 연결됨"
            }
            else -> "원격 첨삭 연결 상태를 확인할 수 없습니다."
        }
        if (state !is RemoteReviewPeerStatus.WaitingForTeacher) {
            displayedPayload = null
            qrImage.setImageDrawable(null)
            qrBitmap?.recycle()
            qrBitmap = null
            qrImage.visibility = View.GONE
            qrGuide.visibility = View.GONE
        } else if (displayedPayload?.pairId == state.pairId) {
            qrGuide.text = "이 QR을 선생 기기로 스캔하세요 · ${formatExpiry(state.expiresAtEpochMs)}까지"
        }
        retryButton.visibility = if (state is RemoteReviewPeerStatus.WaitingForStudentAck) View.VISIBLE else View.GONE
        inboxButton.visibility = if (state is RemoteReviewPeerStatus.Connected && state.role == RemoteReviewRole.TEACHER) {
            View.VISIBLE
        } else {
            View.GONE
        }
        clearButton.isEnabled = state !is RemoteReviewPeerStatus.Unconfigured && !busy
        renderButtons()
    }

    private fun renderButtons() {
        val enabled = !busy
        studentButton.isEnabled = enabled
        teacherButton.isEnabled = enabled
        retryButton.isEnabled = enabled
        inboxButton.isEnabled = enabled
        studentButton.alpha = if (enabled) 1f else 0.55f
        teacherButton.alpha = studentButton.alpha
    }

    private fun formatExpiry(epochMs: Long): String =
        SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(epochMs))

    private fun Throwable.safeMessage(): String = when (this) {
        is TelegramApiException ->
            message?.take(180) ?: "Telegram 서버가 연결 요청을 거부했습니다."
        is IllegalArgumentException, is IllegalStateException ->
            message?.take(180) ?: "입력 내용을 확인해주세요."
        else -> "연결하지 못했습니다. 인터넷과 Bot-to-Bot 설정을 확인해주세요."
    }

    private fun createQr(value: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) pixels[y * size + x] =
                if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    private fun title(value: String) = TextView(this).apply {
        text = value
        textSize = 25f
        setTextColor(COLOR_TEXT)
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 19f
        setTextColor(COLOR_TEXT)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(5))
    }

    private fun body(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(COLOR_MUTED)
        setLineSpacing(0f, 1.18f)
    }

    private fun primaryButton(value: String) = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(COLOR_ACCENT)
    }

    private fun secondaryButton(value: String) = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 15f
        setTextColor(COLOR_TEXT)
        backgroundTintList = ColorStateList.valueOf(COLOR_BUTTON)
    }

    private fun verticalPanel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = roundedBackground(COLOR_PANEL, 18f)
    }

    private fun horizontalRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun roundedBackground(color: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun matchWidth(height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (height >= 0) dp(height) else height,
        ).apply {
            topMargin = dp(top)
            bottomMargin = dp(bottom)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val LOG_TAG = "MasterNoteRemoteReview"
        const val STATUS_REFRESH_MILLIS = 1_000L
        const val QR_SIZE = 720
        const val COLOR_BACKGROUND = 0xFFF4F6F8.toInt()
        const val COLOR_PANEL = 0xFFFFFFFF.toInt()
        const val COLOR_STATUS = 0xFFE8EEF5.toInt()
        const val COLOR_BUTTON = 0xFFE9EDF2.toInt()
        const val COLOR_ACCENT = 0xFF315C96.toInt()
        const val COLOR_ACCENT_DARK = 0xFF244873.toInt()
        const val COLOR_TEXT = 0xFF20252B.toInt()
        const val COLOR_MUTED = 0xFF626B76.toInt()
        const val COLOR_DANGER = 0xFF9D2E2E.toInt()
    }
}
