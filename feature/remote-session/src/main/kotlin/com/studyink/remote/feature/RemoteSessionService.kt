package com.studyink.remote.feature

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.studyink.reader.ReaderRemoteBridge
import com.studyink.annotation.storage.RoomRemoteStore
import com.studyink.annotation.storage.RoomRemoteReplicaStore
import com.studyink.remote.protocol.ProtobufRemoteMessageCodec
import com.studyink.remote.protocol.RemoteAck
import com.studyink.remote.protocol.RemoteDurableOperationBatch
import com.studyink.remote.protocol.RemoteEnvelope
import com.studyink.remote.protocol.RemoteLane
import com.studyink.remote.protocol.RemoteNack
import com.studyink.remote.protocol.RemoteLiveStrokePreview
import com.studyink.remote.protocol.RemotePageState
import com.studyink.remote.protocol.RemoteViewportState
import com.studyink.remote.sync.DurableOutboxSender
import com.studyink.remote.sync.DurableReceiveResult
import com.studyink.remote.sync.DurableReceiver
import com.studyink.remote.sync.RemoteEphemeralSender
import com.studyink.remote.sync.RemoteLivePublisher
import com.studyink.remote.session.RemoteSessionController
import com.studyink.remote.session.RemoteSessionRole
import com.studyink.remote.session.RemoteSessionState
import com.studyink.remote.transport.nearby.NearbyRemoteTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class RemoteSessionService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var controller: RemoteSessionController? = null
    private var reconnectJob: Job? = null
    private var remoteStore: RoomRemoteStore? = null
    private var replicaStore: RoomRemoteReplicaStore? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSession()
            return START_NOT_STICKY
        }
        if (controller != null) return START_NOT_STICKY
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID) ?: return stopInvalid()
        val deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return stopInvalid()
        val localName = intent.getStringExtra(EXTRA_LOCAL_NAME) ?: return stopInvalid()
        val role = runCatching { RemoteSessionRole.valueOf(intent.getStringExtra(EXTRA_ROLE).orEmpty()) }.getOrNull()
            ?: return stopInvalid()
        startForegroundNow(sessionId)
        val transport = NearbyRemoteTransport(applicationContext, serviceScope)
        val persisted = RoomRemoteStore.open(applicationContext)
        val replica = RoomRemoteReplicaStore.open(applicationContext)
        remoteStore = persisted
        replicaStore = replica
        val livePublisher = RemoteLivePublisher()
        if (role == RemoteSessionRole.STUDENT) {
            ReaderRemoteBridge.sink = ServiceReaderSink(sessionId, deviceId, livePublisher)
        }
        val created = RemoteSessionController(
            role, sessionId, deviceId, localName, transport, serviceScope,
            elapsedRealtimeMs = SystemClock::elapsedRealtime,
        )
        controller = created
        RemoteSessionRuntime.controller = created
        RemoteSessionRuntime.diagnostics = RemoteSessionDiagnostics(
            sessionId, deviceId, role, System.currentTimeMillis(),
        )
        val codec = ProtobufRemoteMessageCodec()
        val endpoint = { created.snapshot.value.endpointId }
        val durableSender = DurableOutboxSender(sessionId, endpoint, persisted, transport, System::currentTimeMillis)
        val ephemeralSender = RemoteEphemeralSender(sessionId, deviceId, endpoint, transport, SystemClock::elapsedRealtime, codec)
        val durableReceiver = DurableReceiver(
            sessionId, persisted,
            applyOperation = { operation ->
                replica.applyOperationAtomically(
                    sessionId, created.snapshot.value.let { persisted.highestContiguousSequence(sessionId) + 1 },
                    "received-${operation.operationId}", operation, System.currentTimeMillis(),
                )
            },
            nowEpochMillis = System::currentTimeMillis,
            receiptOwnedByApplyOperation = true,
        )
        serviceScope.launch {
            created.snapshot.collectLatest { snapshot ->
                RemoteSessionRuntime.update(snapshot)
                if (snapshot.state == RemoteSessionState.RECONNECTING) startReconnect(created)
                if (snapshot.state == RemoteSessionState.LIVE) reconnectJob?.cancel()
                if (snapshot.state == RemoteSessionState.INITIAL_SYNC) created.initialSyncComplete()
            }
        }
        serviceScope.launch {
            created.receivedApplicationBytes.collect { bytes ->
                val envelope = runCatching { codec.decode(bytes) }.getOrElse {
                    RemoteSessionRuntime.diagnostics?.lastError = it.message
                    return@collect
                }
                RemoteSessionRuntime.diagnostics?.bytesReceived =
                    (RemoteSessionRuntime.diagnostics?.bytesReceived ?: 0) + bytes.size
                when (val payload = envelope.payload) {
                    is RemoteAck -> durableSender.acknowledge(payload.highestContiguousSequence)
                    is RemoteNack -> durableSender.sendWindow()
                    is RemoteDurableOperationBatch -> {
                        val result = durableReceiver.receive(bytes)
                        when (result) {
                            is DurableReceiveResult.Acknowledged -> ephemeralSender.sendLatest(RemoteAck(result.highestContiguousSequence))
                            is DurableReceiveResult.Missing -> ephemeralSender.sendLatest(RemoteNack(result.expectedSequence))
                            is DurableReceiveResult.CheckpointRequired -> Unit
                        }
                    }
                    is RemoteLiveStrokePreview -> Unit
                    is RemotePageState -> Unit
                    is RemoteViewportState -> Unit
                    else -> Unit
                }
            }
        }
        serviceScope.launch {
            while (true) {
                if (created.snapshot.value.state == RemoteSessionState.LIVE) durableSender.sendWindow()
                delay(200)
            }
        }
        serviceScope.launch { livePublisher.preview.collectLatest { if (created.snapshot.value.state == RemoteSessionState.LIVE) ephemeralSender.sendLatest(it) } }
        serviceScope.launch { livePublisher.pageState.collectLatest { if (created.snapshot.value.state == RemoteSessionState.LIVE) ephemeralSender.sendLatest(it) } }
        serviceScope.launch { livePublisher.viewportState.collectLatest { if (created.snapshot.value.state == RemoteSessionState.LIVE) ephemeralSender.sendLatest(it) } }
        created.start()
        return START_NOT_STICKY
    }

    private fun startReconnect(session: RemoteSessionController) {
        if (reconnectJob?.isActive == true) return
        reconnectJob = serviceScope.launch {
            RemoteSessionRuntime.diagnostics?.reconnectCount =
                (RemoteSessionRuntime.diagnostics?.reconnectCount ?: 0) + 1
            val schedule = ReconnectPolicy().schedule()
            schedule.forEachIndexed { index, wait ->
                delay(wait)
                if (session.snapshot.value.state == RemoteSessionState.LIVE) return@launch
                if (index == 0 && session.snapshot.value.state == RemoteSessionState.RECONNECTING) {
                    runCatching { session.reconnect() }.onFailure {
                        RemoteSessionRuntime.diagnostics?.lastError = it.message
                    }
                }
            }
            if (session.snapshot.value.state != RemoteSessionState.LIVE) session.requireManualReconnect()
        }
    }

    private fun startForegroundNow(sessionId: String) {
        val stopIntent = Intent(this, RemoteSessionService::class.java).setAction(ACTION_STOP)
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("원격 수업 연결 중")
            .setContentText("세션 ${sessionId.take(8)} · 탭에서 연결 상태 확인")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "세션 종료", pendingStop)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "원격 수업", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun stopInvalid(): Int { stopSelf(); return START_NOT_STICKY }

    private fun stopSession() {
        reconnectJob?.cancel()
        runBlocking { controller?.stopNow() }
        controller = null
        ReaderRemoteBridge.sink = null
        RemoteSessionRuntime.diagnostics?.endedAtEpochMillis = System.currentTimeMillis()
        RemoteSessionRuntime.clear()
        remoteStore?.close(); remoteStore = null
        replicaStore?.close(); replicaStore = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        reconnectJob?.cancel()
        runBlocking { controller?.stopNow() }
        ReaderRemoteBridge.sink = null
        serviceScope.cancel()
        RemoteSessionRuntime.clear()
        remoteStore?.close(); remoteStore = null
        replicaStore?.close(); replicaStore = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "remote-session"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_START = "com.studyink.remote.START"
        private const val ACTION_STOP = "com.studyink.remote.STOP"
        private const val EXTRA_SESSION_ID = "sessionId"
        private const val EXTRA_DEVICE_ID = "deviceId"
        private const val EXTRA_LOCAL_NAME = "localName"
        private const val EXTRA_ROLE = "role"

        /** Call only from a visible user action after Nearby runtime permissions are granted. */
        fun start(context: Context, sessionId: String, deviceId: String, localName: String, role: RemoteSessionRole) {
            val intent = Intent(context, RemoteSessionService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_SESSION_ID, sessionId).putExtra(EXTRA_DEVICE_ID, deviceId)
                .putExtra(EXTRA_LOCAL_NAME, localName).putExtra(EXTRA_ROLE, role.name)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RemoteSessionService::class.java).setAction(ACTION_STOP))
        }
    }
}
