package com.studyink.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.studyink.monitor.core.RemoteMonitorMaintenanceBus
import com.studyink.monitor.telegram.RemoteMonitorGateway
import com.studyink.monitor.telegram.RemoteMonitorPreferences
import com.studyink.monitor.telegram.RemoteMonitorStatus
import com.studyink.monitor.telegram.RemoteMonitorStatusSubscription
import com.studyink.monitor.telegram.RemoteReviewPeerStatus
import com.studyink.monitor.telegram.RemoteReviewRole
import com.studyink.monitor.telegram.TelegramApiException
import com.studyink.monitor.telegram.TelegramEnqueueResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Parent-facing setup surface for the student device's Telegram monitor.
 *
 * Pairing deliberately runs on one private worker. Starting a setup session first stops the
 * gateway's normal inbox poller, so Bot API getUpdates can never have two owners in this process.
 */
class TelegramSetupActivity : Activity() {
    private lateinit var gateway: RemoteMonitorGateway
    private val setupWorker: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MasterNote-Telegram-setup").apply { isDaemon = true }
    }
    private val pairingGeneration = AtomicInteger(0)

    private lateinit var statusText: TextView
    private lateinit var setupPanel: LinearLayout
    private lateinit var tokenInput: EditText
    private lateinit var dedicatedBotCheck: CheckBox
    private lateinit var pairButton: Button
    private lateinit var cancelPairingButton: Button
    private lateinit var pairingGuide: TextView
    private lateinit var settingsPanel: LinearLayout
    private lateinit var monitoringSwitch: Switch
    private lateinit var ttsSwitch: Switch
    private lateinit var wakeVoiceSwitch: Switch
    private lateinit var remoteReviewButton: Button
    private lateinit var reconnectButton: Button
    private lateinit var clearButton: Button

    private var statusSubscription: RemoteMonitorStatusSubscription? = null
    private var preferencesSubscription: RemoteMonitorStatusSubscription? = null
    private var latestStatus: RemoteMonitorStatus = RemoteMonitorStatus.NotConfigured
    private var latestPreferences = RemoteMonitorPreferences()
    private var forceSetupVisible = false
    private var pairingActive = false
    private var syncingSwitches = false
    private var waitingForAudioPermission = false
    private val processPairingRefresh = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            renderState()
            if (processPairingInProgress.get()) window.decorView.postDelayed(this, 400L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gateway = RemoteMonitorGateway.get(applicationContext)
        setContentView(buildContent())
        bindActions()

        statusSubscription = gateway.subscribeStatus { status ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                latestStatus = status
                renderState()
            }
        }
        preferencesSubscription = gateway.subscribePreferences { preferences ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                latestPreferences = preferences
                renderState()
            }
        }
    }

    override fun onDestroy() {
        statusSubscription?.close()
        preferencesSubscription?.close()
        pairingGeneration.incrementAndGet()
        setupWorker.shutdown()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        window.decorView.removeCallbacks(processPairingRefresh)
        window.decorView.post(processPairingRefresh)
    }

    override fun onPause() {
        window.decorView.removeCallbacks(processPairingRefresh)
        super.onPause()
    }

    @Deprecated("Android permission callback retained for the app's minSdk-compatible Activity.")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO || !waitingForAudioPermission) return
        waitingForAudioPermission = false
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (granted) {
            updatePreferences(transform = { it.copy(wakeVoiceEnabled = true) })
        } else {
            syncSwitch(wakeVoiceSwitch, false)
            Toast.makeText(this, "말을 글로 보내려면 마이크 권한이 필요합니다.", Toast.LENGTH_LONG).show()
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
            addView(TextView(context).apply {
                text = "Telegram 부모 연결"
                textSize = 25f
                setTextColor(COLOR_TEXT)
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(context).apply {
                text = "닫기"
                isAllCaps = false
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)))
        })
        root.addView(bodyText("학생 문제집의 제출 화면과 학습 상태를 부모 Telegram으로 보냅니다.\n부모 메시지는 학생 화면에 5초간 표시됩니다."))

        statusText = TextView(this).apply {
            textSize = 16f
            setTextColor(COLOR_TEXT)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            backgroundTintList = ColorStateList.valueOf(COLOR_STATUS)
            background = roundedBackground(COLOR_STATUS, 14f)
        }
        root.addView(statusText, matchWidth(top = 18, bottom = 16))

        setupPanel = verticalPanel().also { panel ->
            panel.addView(sectionTitle("새 봇 연결"))
            panel.addView(bodyText("BotFather가 발급한 토큰을 붙여 넣으세요. 토큰은 앱의 암호화 저장소에만 보관됩니다."))
            tokenInput = EditText(this).apply {
                hint = "123456789:AA…"
                textSize = 16f
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setPadding(dp(14), dp(4), dp(14), dp(4))
            }
            panel.addView(tokenInput, matchWidth(height = 54, top = 10))
            dedicatedBotCheck = CheckBox(this).apply {
                text = "이 봇은 MasterNote 전용이며 다른 프로그램에서 사용하지 않습니다."
                textSize = 15f
                setTextColor(COLOR_TEXT)
                setPadding(0, dp(10), 0, dp(4))
            }
            panel.addView(dedicatedBotCheck, matchWidth())
            panel.addView(bodyText("연결을 시작하면 이 봇의 webhook을 해제하고 업데이트 수신을 MasterNote가 맡습니다. 공유 봇이면 다른 프로그램의 수신이 멈출 수 있습니다."))
            pairButton = primaryButton("연결 시작")
            panel.addView(pairButton, matchWidth(height = 52, top = 14))
            cancelPairingButton = secondaryButton("연결 기다리기 취소").apply {
                visibility = View.GONE
            }
            panel.addView(cancelPairingButton, matchWidth(height = 50, top = 8))
            pairingGuide = bodyText("").apply {
                visibility = View.GONE
                setTextColor(COLOR_ACCENT_DARK)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(14), 0, 0)
            }
            panel.addView(pairingGuide, matchWidth())
        }
        root.addView(setupPanel, matchWidth(bottom = 14))

        settingsPanel = verticalPanel().also { panel ->
            panel.addView(sectionTitle("연결 후 기능"))
            monitoringSwitch = settingSwitch(
                "부모 모니터링",
                "제출 화면과 /화면 요청을 전송합니다. 활동 알림은 기본 1시간 요약이며 Telegram에서 /실시간, /일반으로 바꿀 수 있습니다.",
            )
            ttsSwitch = settingSwitch(
                "부모 메시지 읽어주기",
                "상단에 표시되는 부모 메시지를 한국어 음성으로도 읽습니다.",
            )
            wakeVoiceSwitch = settingSwitch(
                "‘아빠’라고 부르고 글 보내기",
                "문제집 화면에서 ‘아빠’라고 말한 뒤 이어서 말하면 글로 바꿔 부모에게 보냅니다.",
            )
            panel.addView(monitoringSwitch, matchWidth())
            panel.addView(ttsSwitch, matchWidth())
            panel.addView(wakeVoiceSwitch, matchWidth())
            remoteReviewButton = primaryButton("선생·학생 원격 페이지 첨삭 연결")
            panel.addView(remoteReviewButton, matchWidth(height = 52, top = 14))
            reconnectButton = secondaryButton("다른 봇 또는 부모 계정으로 다시 연결")
            panel.addView(reconnectButton, matchWidth(height = 50, top = 8))
            clearButton = secondaryButton("연결 초기화").apply { setTextColor(COLOR_DANGER) }
            panel.addView(clearButton, matchWidth(height = 50, top = 8))
            panel.addView(bodyText("다시 연결하거나 초기화해도 아직 전송되지 않은 제출·메시지 대기열은 삭제하지 않습니다.").apply {
                setPadding(0, dp(10), 0, 0)
            })
        }
        root.addView(settingsPanel, matchWidth())
        return scroll
    }

    private fun bindActions() {
        pairButton.setOnClickListener { beginPairing() }
        cancelPairingButton.setOnClickListener {
            pairingGeneration.incrementAndGet()
            pairingGuide.text = "연결 대기를 취소하는 중입니다…"
            pairButton.isEnabled = false
            cancelPairingButton.isEnabled = false
        }
        reconnectButton.setOnClickListener {
            forceSetupVisible = true
            renderState()
            tokenInput.requestFocus()
        }
        clearButton.setOnClickListener { confirmClearConnection() }

        monitoringSwitch.setOnCheckedChangeListener { _, enabled ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            if (enabled && latestStatus == RemoteMonitorStatus.NotConfigured) {
                syncSwitch(monitoringSwitch, false)
                forceSetupVisible = true
                renderState()
                Toast.makeText(this, "먼저 Telegram 봇을 연결해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnCheckedChangeListener
            }
            if (enabled) requestNotificationPermissionIfNeeded()
            updatePreferences(
                transform = { it.copy(monitoringEnabled = enabled) },
            )
        }
        ttsSwitch.setOnCheckedChangeListener { _, enabled ->
            if (!syncingSwitches) updatePreferences(transform = { it.copy(ttsEnabled = enabled) })
        }
        wakeVoiceSwitch.setOnCheckedChangeListener { _, enabled ->
            if (syncingSwitches) return@setOnCheckedChangeListener
            if (!enabled) {
                updatePreferences(transform = { it.copy(wakeVoiceEnabled = false) })
            } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                updatePreferences(transform = { it.copy(wakeVoiceEnabled = true) })
            } else {
                waitingForAudioPermission = true
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            }
        }
        remoteReviewButton.setOnClickListener {
            startActivity(Intent(this, RemoteReviewSetupActivity::class.java))
        }
    }

    private fun beginPairing() {
        val token = tokenInput.text?.toString()?.trim().orEmpty()
        if (!dedicatedBotCheck.isChecked) {
            Toast.makeText(this, "전용 봇 확인에 먼저 체크해주세요.", Toast.LENGTH_LONG).show()
            return
        }
        if (token.isBlank()) {
            tokenInput.error = "BotFather 토큰을 입력해주세요."
            return
        }
        if (!processPairingInProgress.compareAndSet(false, true)) {
            forceSetupVisible = true
            pairingGuide.visibility = View.VISIBLE
            pairingGuide.text = "이전 연결 설정을 정리하는 중입니다. 잠시 뒤 다시 눌러주세요."
            renderState()
            window.decorView.post(processPairingRefresh)
            return
        }
        val generation = pairingGeneration.incrementAndGet()
        pairingActive = true
        pairButton.isEnabled = false
        cancelPairingButton.isEnabled = true
        cancelPairingButton.visibility = View.VISIBLE
        pairingGuide.visibility = View.VISIBLE
        pairingGuide.text = "봇 정보를 확인하는 중입니다…"
        renderState()
        try {
            setupWorker.execute {
                var connected = false
                var maintenancePauseRequested = false
                try {
                    maintenancePauseRequested = true
                    check(RemoteMonitorMaintenanceBus.pauseAndAwait(PAIRING_RENDER_PAUSE_TIMEOUT_MILLIS)) {
                        "기존 시험지 전송을 안전하게 마치지 못했습니다. 잠시 뒤 다시 시도해주세요."
                    }
                    val session = gateway.beginPairing(token)
                    if (!isPairingCurrent(generation)) return@execute
                    postForPairing(generation) {
                        val username = session.bot.username?.let { "@$it" } ?: session.bot.displayName
                        pairingGuide.text =
                            "Telegram에서 $username 과의 개인 채팅을 열고 /연결 을 보내세요.\n새 메시지만 연결에 사용하며, 연결될 때까지 안전하게 기다립니다."
                    }
                    while (isPairingCurrent(generation)) {
                        val request = gateway.pollForPairing(session, PAIR_POLL_SECONDS) ?: continue
                        if (!isPairingCurrent(generation)) break
                        // Pending inbound data belongs to the previous parent chat. Never replay
                        // an old parent's instruction or screen request after credentials change.
                        gateway.pendingParentMessage()?.let { pending ->
                            gateway.acknowledgeParentMessage(pending.updateId)
                        }
                        gateway.pendingScreenRequests().forEach { pending ->
                            gateway.acknowledgeScreenRequest(pending.updateId)
                        }
                        // A new parent connection owns a new privacy boundary. Reset before the
                        // gateway enables its new credentials so the preference callback cannot
                        // queue submissions from an earlier/offline period for the new parent.
                        MasterNoteRemoteMonitorCoordinator.resetSubmissionBaseline()
                        gateway.completePairing(session, request, sendTestMessage = false)
                        // This cannot depend on the Activity UI post: rotation may destroy this
                        // instance after pairing succeeded but before the result is rendered.
                        runCatching { RemoteMonitorService.startIfEnabled(applicationContext) }
                        connected = true
                        val testResult = gateway.enqueueText(
                            idempotencyKey =
                                "telegram-setup-test:${session.bot.id}:${request.chatId}:${request.updateId}",
                            text = "MasterNote 학생 기기 연결 완료 · /화면 명령으로 현재 시험지를 받을 수 있습니다.",
                            expectedChatId = request.chatId,
                        )
                        postForPairing(generation) {
                            pairingActive = false
                            forceSetupVisible = false
                            tokenInput.text?.clear()
                            pairingGuide.text = if (testResult.isAcceptedForDelivery()) {
                                "${request.displayName} 계정과 연결했습니다. Telegram으로 시험 메시지를 보냈습니다."
                            } else {
                                "${request.displayName} 계정과 연결했습니다. 시험 메시지는 전송 대기열 상태를 확인해주세요."
                            }
                            pairButton.isEnabled = true
                            cancelPairingButton.visibility = View.GONE
                            requestNotificationPermissionIfNeeded()
                            RemoteMonitorService.startIfEnabled(this)
                            renderState()
                        }
                        return@execute
                    }
                } catch (error: Throwable) {
                    if (isPairingCurrent(generation)) {
                        postForPairing(generation) {
                            pairingGuide.visibility = View.VISIBLE
                            pairingGuide.text = "연결하지 못했습니다. ${error.userFacingMessage()}"
                        }
                    }
                } finally {
                    if (!connected) {
                        // beginPairing stopped the regular poller. Restore the previous valid link
                        // only after setup getUpdates returned, avoiding concurrent pollers.
                        runCatching { gateway.startIfEnabled() }
                    }
                    if (maintenancePauseRequested) RemoteMonitorMaintenanceBus.resume()
                    processPairingInProgress.set(false)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (pairingGeneration.get() != generation) {
                            pairingActive = false
                            pairingGuide.visibility = View.VISIBLE
                            pairingGuide.text = "연결 대기를 취소했습니다."
                            pairButton.isEnabled = true
                            cancelPairingButton.visibility = View.GONE
                            cancelPairingButton.isEnabled = true
                            renderState()
                            return@runOnUiThread
                        }
                        pairingActive = false
                        pairButton.isEnabled = true
                        cancelPairingButton.visibility = View.GONE
                        cancelPairingButton.isEnabled = true
                        renderState()
                    }
                }
            }
        } catch (error: RuntimeException) {
            processPairingInProgress.set(false)
            pairingActive = false
            pairButton.isEnabled = true
            cancelPairingButton.visibility = View.GONE
            pairingGuide.text = "연결 작업을 시작하지 못했습니다. ${error.userFacingMessage()}"
            renderState()
        }
    }

    private fun confirmClearConnection() {
        AlertDialog.Builder(this)
            .setTitle("Telegram 연결을 초기화할까요?")
            .setMessage("봇 토큰과 부모 채팅 연결만 지웁니다. 아직 전송되지 않은 대기열은 보존합니다.")
            .setNegativeButton("취소", null)
            .setPositiveButton("초기화") { _, _ ->
                pairingGeneration.incrementAndGet()
                setupWorker.execute {
                    gateway.updatePreferences { it.copy(monitoringEnabled = false) }
                    RemoteMonitorService.stop(applicationContext)
                    gateway.clearConnection()
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        forceSetupVisible = true
                        dedicatedBotCheck.isChecked = false
                        pairingGuide.visibility = View.GONE
                        Toast.makeText(this, "Telegram 연결을 초기화했습니다.", Toast.LENGTH_SHORT).show()
                        renderState()
                    }
                }
            }
            .show()
    }

    private fun updatePreferences(
        transform: (RemoteMonitorPreferences) -> RemoteMonitorPreferences,
    ) {
        setupWorker.execute {
            runCatching {
                gateway.updatePreferences(transform).also { updated ->
                    if (updated.monitoringEnabled) {
                        RemoteMonitorService.startIfEnabled(applicationContext)
                    } else {
                        RemoteMonitorService.stop(applicationContext)
                    }
                }
            }
                .onSuccess { updated ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        latestPreferences = updated
                        renderState()
                    }
                }
                .onFailure { error ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        renderState()
                        Toast.makeText(
                            this,
                            "설정을 저장하지 못했습니다. ${error.userFacingMessage()}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
        }
    }

    private fun renderState() {
        statusText.text = latestStatus.displayText()
        val configured = latestStatus != RemoteMonitorStatus.NotConfigured
        val pairingBusy = pairingActive || processPairingInProgress.get()
        setupPanel.visibility = if (!configured || forceSetupVisible || pairingBusy) View.VISIBLE else View.GONE
        settingsPanel.visibility = if (configured && !pairingBusy) View.VISIBLE else View.GONE
        pairButton.isEnabled = !pairingBusy
        if (processPairingInProgress.get() && !pairingActive) {
            pairingGuide.visibility = View.VISIBLE
            pairingGuide.text = "이전 연결 설정을 정리하는 중입니다. 잠시만 기다려주세요."
        }
        syncingSwitches = true
        monitoringSwitch.isChecked = latestPreferences.monitoringEnabled
        val peerStatus = gateway.remoteReviewPeerStatus()
        val teacherReviewDevice = peerStatus is RemoteReviewPeerStatus.WaitingForStudentAck ||
            peerStatus is RemoteReviewPeerStatus.Connected && peerStatus.role == RemoteReviewRole.TEACHER
        monitoringSwitch.isEnabled = !teacherReviewDevice
        monitoringSwitch.alpha = if (teacherReviewDevice) 0.5f else 1f
        ttsSwitch.isChecked = latestPreferences.ttsEnabled
        wakeVoiceSwitch.isChecked = latestPreferences.wakeVoiceEnabled
        syncingSwitches = false
    }

    private fun syncSwitch(view: Switch, checked: Boolean) {
        syncingSwitches = true
        view.isChecked = checked
        syncingSwitches = false
    }

    private fun postForPairing(generation: Int, action: () -> Unit) {
        runOnUiThread {
            if (isPairingCurrent(generation) && !isFinishing && !isDestroyed) action()
        }
    }

    private fun isPairingCurrent(generation: Int): Boolean =
        pairingGeneration.get() == generation && !setupWorker.isShutdown

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun RemoteMonitorStatus.displayText(): String = when (this) {
        RemoteMonitorStatus.NotConfigured -> "연결되지 않음 · 새 전용 봇을 연결해주세요."
        RemoteMonitorStatus.Stopped -> "연결 저장됨 · 모니터링 꺼짐"
        is RemoteMonitorStatus.Starting -> "$chatLabel · 연결 준비 중"
        is RemoteMonitorStatus.Connected -> "$chatLabel · 연결됨"
        is RemoteMonitorStatus.Offline -> "$chatLabel · 인터넷 연결 대기 중"
        is RemoteMonitorStatus.Error -> "${chatLabel ?: "Telegram"} · $reason"
    }

    private fun TelegramEnqueueResult.isAcceptedForDelivery(): Boolean = when (this) {
        TelegramEnqueueResult.ENQUEUED,
        TelegramEnqueueResult.ALREADY_PENDING,
        TelegramEnqueueResult.ALREADY_DELIVERED,
        -> true
        else -> false
    }

    private fun Throwable.userFacingMessage(): String = when (this) {
        is TelegramApiException ->
            "Telegram $statusCode · ${message.orEmpty().trim().take(120)}"
        is IllegalArgumentException, is IllegalStateException ->
            message?.trim()?.takeIf(String::isNotEmpty)?.take(160) ?: "입력 내용을 확인해주세요."
        // IOException messages may contain the request URL, and a Bot API URL contains the token.
        // Do not surface arbitrary transport exception text in UI or screenshots.
        else -> "네트워크 연결을 확인해주세요."
    }

    private fun verticalPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = roundedBackground(COLOR_PANEL, 18f)
    }

    private fun horizontalRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun sectionTitle(textValue: String): TextView = TextView(this).apply {
        text = textValue
        textSize = 19f
        setTextColor(COLOR_TEXT)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(5))
    }

    private fun bodyText(textValue: String): TextView = TextView(this).apply {
        text = textValue
        textSize = 14f
        setTextColor(COLOR_MUTED)
        setLineSpacing(0f, 1.18f)
    }

    private fun primaryButton(textValue: String): Button = Button(this).apply {
        text = textValue
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.WHITE)
        backgroundTintList = ColorStateList.valueOf(COLOR_ACCENT)
    }

    private fun secondaryButton(textValue: String): Button = Button(this).apply {
        text = textValue
        isAllCaps = false
        textSize = 15f
        setTextColor(COLOR_TEXT)
        backgroundTintList = ColorStateList.valueOf(COLOR_BUTTON)
    }

    @Suppress("DEPRECATION")
    private fun settingSwitch(title: String, description: String): Switch = Switch(this).apply {
        text = "$title\n$description"
        textSize = 15f
        setTextColor(COLOR_TEXT)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(9), 0, dp(9))
        showText = false
    }

    private fun roundedBackground(color: Int, radiusDp: Float) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp.toInt()).toFloat()
        }

    private fun matchWidth(
        height: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
        top: Int = 0,
        bottom: Int = 0,
    ) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        if (height > 0) dp(height) else height,
    ).apply {
        topMargin = dp(top)
        bottomMargin = dp(bottom)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        val processPairingInProgress = AtomicBoolean(false)

        const val REQUEST_RECORD_AUDIO = 7_201
        const val REQUEST_NOTIFICATIONS = 7_202
        const val PAIR_POLL_SECONDS = 5
        const val PAIRING_RENDER_PAUSE_TIMEOUT_MILLIS = 30_000L

        const val COLOR_BACKGROUND = 0xFFF7F3EA.toInt()
        const val COLOR_PANEL = 0xFFFFFCF6.toInt()
        const val COLOR_STATUS = 0xFFE7F4F0.toInt()
        const val COLOR_BUTTON = 0xFFECE8DE.toInt()
        const val COLOR_TEXT = 0xFF34322E.toInt()
        const val COLOR_MUTED = 0xFF716D65.toInt()
        const val COLOR_ACCENT = 0xFF4F8B7B.toInt()
        const val COLOR_ACCENT_DARK = 0xFF356A5D.toInt()
        const val COLOR_DANGER = 0xFFA94949.toInt()
    }
}
