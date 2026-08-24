package com.studyink.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import com.studyink.library.data.LibraryRepository
import com.studyink.library.ui.LibraryActivity
import com.studyink.monitor.core.HourlyActivityReport
import com.studyink.monitor.core.HourlyActivityReportStateMachine
import com.studyink.monitor.core.IdleAlert
import com.studyink.monitor.core.IdleAlertStateMachine
import com.studyink.monitor.core.StudentStudyPresence
import com.studyink.monitor.core.StudentStudyPresenceBus
import com.studyink.monitor.core.StudentWorkHeartbeatBus
import com.studyink.monitor.telegram.RemoteMonitorGateway
import com.studyink.monitor.telegram.RemoteReviewPeerStatus
import com.studyink.monitor.telegram.RemoteReviewRole
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Keeps the student's one Telegram poller and inactivity clock alive during monitoring. */
class RemoteMonitorService : Service() {
    private val idleLock = Any()
    private val idle = IdleAlertStateMachine()
    private val hourly = HourlyActivityReportStateMachine()
    private val sessionId = UUID.randomUUID().toString()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "MasterNote-activity-report-clock").apply { isDaemon = true }
    }
    private lateinit var gateway: RemoteMonitorGateway
    private var currentPresence: StudentStudyPresence? = null
    private var latestActivityElapsedMs: Long? = null
    private var realtimeActivityEnabled = false
    private var presenceSubscription: AutoCloseable? = null
    private var heartbeatSubscription: AutoCloseable? = null
    private var preferencesSubscription: AutoCloseable? = null
    private var reportClock: ScheduledFuture<*>? = null
    private var remoteReviewWatchdog: ScheduledFuture<*>? = null
    @Volatile private var activityReportingStarted = false

    override fun onCreate() {
        super.onCreate()
        gateway = RemoteMonitorGateway.get(this)
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("연결 준비 중"))
        // A process/service restart must not send an offline-era idle warning before the sticky
        // Reader presence gets a chance to prove that the student has resumed work.
        gateway.cancelCoalesced(IDLE_COALESCE_KEY)
        gateway.cancelCoalesced(HOURLY_REPORT_COALESCE_KEY)
        val canRun = gateway.preferences().monitoringEnabled || remoteReviewConfigured()
        if (!canRun || !gateway.start()) {
            stopSelf()
            return
        }
        remoteReviewWatchdog = scheduler.scheduleWithFixedDelay(
            ::stopIfNothingConfigured,
            REMOTE_REVIEW_WATCHDOG_SECONDS,
            REMOTE_REVIEW_WATCHDOG_SECONDS,
            TimeUnit.SECONDS,
        )
        startActivityReportingIfNeeded()
        updateNotification()
    }

    private fun startActivityReportingIfNeeded() {
        if (activityReportingStarted) return
        if (!gateway.preferences().monitoringEnabled || teacherRemoteReview()) return
        activityReportingStarted = true
        synchronized(idleLock) {
            realtimeActivityEnabled = gateway.preferences().realtimeActivityEnabled
            if (!realtimeActivityEnabled) hourly.start(SystemClock.elapsedRealtime())
        }
        preferencesSubscription = gateway.subscribePreferences(emitCurrent = false) { preferences ->
            switchActivityReportingMode(preferences.realtimeActivityEnabled)
        }
        // Close the narrow read-to-subscribe race if a Telegram command arrived during startup.
        switchActivityReportingMode(gateway.preferences().realtimeActivityEnabled)
        presenceSubscription = StudentStudyPresenceBus.subscribe { next ->
            val resetIdleAlert = synchronized(idleLock) {
                val previous = currentPresence
                currentPresence = next
                when {
                    !next.active -> {
                        idle.stop()
                        true
                    }
                    previous == null || !previous.active ||
                        previous.bookId != next.bookId ||
                        previous.pageNumber != next.pageNumber ||
                        previous.attemptNo != next.attemptNo -> {
                            // A sticky presence can be much older than this service instance and
                            // does not include the latest non-sticky pen heartbeat. Give a fresh
                            // grace period on bootstrap; real page/attempt changes keep event time.
                            val activityAt = if (previous == null) SystemClock.elapsedRealtime()
                            else next.updatedAtElapsedMs
                            latestActivityElapsedMs = maxOf(latestActivityElapsedMs ?: 0L, activityAt)
                            if (realtimeActivityEnabled) idle.start(activityAt) else idle.stop()
                            if (!realtimeActivityEnabled) hourly.heartbeat(activityAt)
                            true
                        }
                    else -> false
                }
            }
            if (resetIdleAlert) gateway.cancelCoalesced(IDLE_COALESCE_KEY)
            updateNotification()
        }
        heartbeatSubscription = StudentWorkHeartbeatBus.subscribe { heartbeat ->
            val resumedWork = synchronized(idleLock) {
                if (currentPresence?.active == true) {
                    latestActivityElapsedMs = maxOf(
                        latestActivityElapsedMs ?: 0L,
                        heartbeat.atElapsedMs,
                    )
                    if (realtimeActivityEnabled) idle.heartbeat(heartbeat.atElapsedMs)
                    else hourly.heartbeat(heartbeat.atElapsedMs)
                    true
                } else {
                    false
                }
            }
            if (resumedWork) gateway.cancelCoalesced(IDLE_COALESCE_KEY)
        }
        reportClock = scheduler.scheduleWithFixedDelay(::pollActivityReporting, 1L, 1L, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                gateway.cancelCoalesced(IDLE_COALESCE_KEY)
                gateway.cancelCoalesced(HOURLY_REPORT_COALESCE_KEY)
                gateway.updatePreferences { it.copy(monitoringEnabled = false) }
                if (!remoteReviewConfigured()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                stopActivityReporting()
                gateway.start()
                updateNotification()
            }
            ACTION_REFRESH -> {
                if (gateway.preferences().monitoringEnabled || remoteReviewConfigured()) gateway.start()
                startActivityReportingIfNeeded()
                updateNotification()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        remoteReviewWatchdog?.cancel(false)
        remoteReviewWatchdog = null
        stopActivityReporting()
        scheduler.shutdownNow()
        synchronized(idleLock) {
            idle.stop()
            hourly.stop()
        }
        gateway.cancelCoalesced(IDLE_COALESCE_KEY)
        gateway.cancelCoalesced(HOURLY_REPORT_COALESCE_KEY)
        gateway.stop()
        super.onDestroy()
    }

    private fun stopActivityReporting() {
        presenceSubscription?.close()
        heartbeatSubscription?.close()
        preferencesSubscription?.close()
        presenceSubscription = null
        heartbeatSubscription = null
        preferencesSubscription = null
        reportClock?.cancel(false)
        reportClock = null
        activityReportingStarted = false
        synchronized(idleLock) {
            idle.stop()
            hourly.stop()
        }
        gateway.cancelCoalesced(IDLE_COALESCE_KEY)
        gateway.cancelCoalesced(HOURLY_REPORT_COALESCE_KEY)
    }

    private fun remoteReviewConfigured(): Boolean =
        gateway.remoteReviewPeerStatus() !is RemoteReviewPeerStatus.Unconfigured

    private fun stopIfNothingConfigured() {
        if (!gateway.preferences().monitoringEnabled && !remoteReviewConfigured()) stopSelf()
    }

    private fun teacherRemoteReview(): Boolean = when (val status = gateway.remoteReviewPeerStatus()) {
        is RemoteReviewPeerStatus.WaitingForStudentAck -> true
        is RemoteReviewPeerStatus.Connected -> status.role == RemoteReviewRole.TEACHER
        else -> false
    }

    private fun switchActivityReportingMode(realtime: Boolean) {
        val changed = synchronized(idleLock) {
            if (realtimeActivityEnabled == realtime) {
                false
            } else {
                realtimeActivityEnabled = realtime
                val now = SystemClock.elapsedRealtime()
                if (realtime) {
                    hourly.stop()
                    if (currentPresence?.active == true) idle.start(now) else idle.stop()
                } else {
                    idle.stop()
                    hourly.start(now, latestActivityElapsedMs)
                }
                true
            }
        }
        if (!changed) return
        // Do not retain or retry a queued message from the mode the parent just left.
        gateway.cancelCoalesced(IDLE_COALESCE_KEY)
        gateway.cancelCoalesced(HOURLY_REPORT_COALESCE_KEY)
        updateNotification()
    }

    private fun pollActivityReporting() {
        val decision = synchronized(idleLock) {
            val now = SystemClock.elapsedRealtime()
            if (realtimeActivityEnabled) {
                val presence = currentPresence?.takeIf(StudentStudyPresence::active)
                    ?: return@synchronized null
                idle.poll(now)?.let { ActivityReportDecision.Realtime(presence, it) }
            } else {
                hourly.poll(now)?.let { ActivityReportDecision.Hourly(currentPresence, it) }
            }
        } ?: return
        when (decision) {
            is ActivityReportDecision.Realtime -> sendRealtimeIdle(decision.presence, decision.alert)
            is ActivityReportDecision.Hourly -> sendHourlyReport(decision.presence, decision.report)
        }
    }

    private fun sendRealtimeIdle(presence: StudentStudyPresence, alert: IdleAlert) {
        val repository = runCatching { LibraryRepository.get(applicationContext) }.getOrNull()
        val book = presence.bookId?.let { id -> runCatching { repository?.book(id) }.getOrNull() }
        val student = book?.studentId?.let { id ->
            repository?.state?.students?.firstOrNull { it.id == id }?.displayName
        } ?: "학생"
        val page = presence.pageNumber ?: return
        val attempt = presence.attemptNo?.let { " · ${it}회" }.orEmpty()
        gateway.enqueueLatestText(
            coalesceKey = IDLE_COALESCE_KEY,
            idempotencyKey = "idle:$sessionId:${alert.episode}:${alert.thresholdSeconds}",
            text = "$student · ${book?.title ?: "문제집"} · ${page}쪽$attempt — " +
                "${alert.actualIdleSeconds}초 동안 움직임 없음",
        )
    }

    private fun sendHourlyReport(
        presence: StudentStudyPresence?,
        report: HourlyActivityReport,
    ) {
        val activePresence = presence?.takeIf(StudentStudyPresence::active)
        val repository = runCatching { LibraryRepository.get(applicationContext) }.getOrNull()
        val book = activePresence?.bookId?.let { id ->
            runCatching { repository?.book(id) }.getOrNull()
        }
        val student = book?.studentId?.let { id ->
            repository?.state?.students?.firstOrNull { it.id == id }?.displayName
        } ?: "학생"
        val location = activePresence?.pageNumber?.let { page ->
            val attempt = activePresence.attemptNo?.let { " · ${it}회" }.orEmpty()
            "$student · ${book?.title ?: "문제집"} · ${page}쪽$attempt"
        } ?: "$student · 현재 문제집 화면 없음"
        val activity = if (report.hadActivityInLastHour) {
            "지난 1시간 활동 있음"
        } else {
            "지난 1시간 활동 없음"
        }
        val lastActivity = report.secondsSinceLastActivity?.let {
            "마지막 활동 ${formatElapsedSeconds(it)} 전"
        } ?: "마지막 활동 기록 없음"
        gateway.enqueueLatestText(
            coalesceKey = HOURLY_REPORT_COALESCE_KEY,
            idempotencyKey = "activity-hour:$sessionId:${report.sequence}",
            text = "[1시간 활동 리포트]\n$location\n$activity · $lastActivity",
        )
    }

    private fun updateNotification() {
        if (teacherRemoteReview()) {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification("원격 첨삭 페이지 수신 대기"),
            )
            return
        }
        if (!gateway.preferences().monitoringEnabled && remoteReviewConfigured()) {
            getSystemService(NotificationManager::class.java).notify(
                NOTIFICATION_ID,
                notification("원격 첨삭 페이지 전송 대기"),
            )
            return
        }
        val text = currentPresence?.takeIf { it.active }?.let { presence ->
            val mode = if (realtimeActivityEnabled) "실시간" else "1시간 요약"
            "${presence.pageNumber}쪽 학습 상태 · $mode"
        } ?: if (realtimeActivityEnabled) {
            "Telegram 연결됨 · 실시간 활동 알림"
        } else {
            "Telegram 연결됨 · 1시간 활동 요약"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, LibraryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RemoteMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("MasterNote 부모 알림")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (gateway.preferences().monitoringEnabled) {
            builder.addAction(Notification.Action.Builder(null, "부모 알림 끄기", stopIntent).build())
        }
        return builder.build()
    }

    private fun ensureNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "부모 Telegram 알림",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "문제집 제출, 현재 화면과 학습 활동 요약을 부모에게 전송합니다."
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "master-note-remote-monitor"
        private const val NOTIFICATION_ID = 4_207
        private const val IDLE_COALESCE_KEY = "idle-current"
        private const val HOURLY_REPORT_COALESCE_KEY = "activity-hourly-current"
        private const val ACTION_STOP = "com.studyink.app.remote.STOP"
        private const val ACTION_REFRESH = "com.studyink.app.remote.REFRESH"
        private const val REMOTE_REVIEW_WATCHDOG_SECONDS = 30L

        fun startIfEnabled(context: Context) {
            val gateway = RemoteMonitorGateway.get(context)
            if (!gateway.preferences().monitoringEnabled &&
                gateway.remoteReviewPeerStatus() is RemoteReviewPeerStatus.Unconfigured
            ) return
            context.startForegroundService(
                Intent(context, RemoteMonitorService::class.java).setAction(ACTION_REFRESH),
            )
        }

        fun startForRemoteReview(context: Context) {
            context.startForegroundService(
                Intent(context, RemoteMonitorService::class.java).setAction(ACTION_REFRESH),
            )
        }

        fun stop(context: Context) {
            val gateway = RemoteMonitorGateway.get(context)
            if (gateway.remoteReviewPeerStatus() is RemoteReviewPeerStatus.Unconfigured) {
                context.stopService(Intent(context, RemoteMonitorService::class.java))
            } else {
                context.startForegroundService(
                    Intent(context, RemoteMonitorService::class.java).setAction(ACTION_STOP),
                )
            }
        }
    }
}

private sealed interface ActivityReportDecision {
    data class Realtime(
        val presence: StudentStudyPresence,
        val alert: IdleAlert,
    ) : ActivityReportDecision

    data class Hourly(
        val presence: StudentStudyPresence?,
        val report: HourlyActivityReport,
    ) : ActivityReportDecision
}

internal fun formatElapsedSeconds(seconds: Long): String {
    val safeSeconds = seconds.coerceAtLeast(0L)
    if (safeSeconds < 60L) return "${safeSeconds}초"
    if (safeSeconds < 3_600L) return "${safeSeconds / 60L}분"
    val hours = safeSeconds / 3_600L
    val minutes = (safeSeconds % 3_600L) / 60L
    return if (minutes == 0L) "${hours}시간" else "${hours}시간 ${minutes}분"
}
