package com.studyink.sync.lan

import android.app.NotificationChannel
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Build
import android.util.Base64
import com.studyink.annotation.storage.PageOperationLogStore
import com.studyink.library.data.LibraryRepository
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-student/one-teacher LAN transport. It transfers only append-log records for the page the
 * teacher subscribed to. Local persistence never waits for this service.
 */
class LanSyncService : Service(), LanSyncBus.Listener {
    private val io = Executors.newCachedThreadPool()
    private val handler = Handler(Looper.getMainLooper())
    private val store by lazy { PageOperationLogStore(this) }
    private val library by lazy { LibraryRepository.get(this) }
    private val pairingPreferences by lazy { getSharedPreferences("masternote-lan-pairs", MODE_PRIVATE) }
    private val nsd by lazy { getSystemService(NsdManager::class.java) }
    private var multicastLock: WifiManager.MulticastLock? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null
    private var role: LanPeerRole? = null
    private var bookId: String = ""
    private var peerBookId: String = ""
    private var documentHash: String = ""
    private var peerDeviceId: String = ""
    private var peerHost: String = ""
    private var peerPort: Int = 0
    private var pairingToken: String = ""
    private var subscribedPage = -1
    private val peerReceivedClocks = mutableMapOf<String, Long>()
    private var pendingPage = -1
    private var pendingSince = 0L
    private var lastFlushAt = 0L
    private var currentStudentPage = -1
    private val stopping = AtomicBoolean(false)

    private val flushRunnable = Runnable { flushPendingAtStrokeBoundary() }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        LanSyncBus.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_STUDENT_SERVER -> startStudent(requireNotNull(intent.getStringExtra(EXTRA_BOOK_ID)))
            ACTION_TEACHER_DISCOVER -> startTeacher(requireNotNull(intent.getStringExtra(EXTRA_BOOK_ID)))
            ACTION_TEACHER_PAIR_URI -> {
                val payload = PairingPayload.parse(UriCompat.parse(requireNotNull(intent.getStringExtra(EXTRA_PAIR_URI))))
                bookId = requireNotNull(intent.getStringExtra(EXTRA_BOOK_ID))
                documentHash = library.book(bookId).contentSha256
                startTeacherSocket(payload.host, payload.port, payload.bookId, payload.token)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startStudent(targetBookId: String) {
        closeSession()
        stopping.set(false)
        role = LanPeerRole.STUDENT_SERVER
        bookId = targetBookId
        documentHash = library.book(targetBookId).contentSha256
        pairingToken = UUID.randomUUID().toString().substring(0, 8)
        startForeground(NOTIFICATION_ID, notification("선생 기기 연결 대기 중"))
        io.execute {
            val server = ServerSocket(0).also { serverSocket = it }
            registerService(server.localPort)
            localIpv4Address()?.let { host ->
                LanSyncBus.pairingReady(
                    bookId,
                    PairingPayload(host, server.localPort, bookId, pairingToken).toUri().toString(),
                )
            } ?: LanSyncBus.sessionIssue("현재 Wi-Fi 주소를 찾지 못했습니다.")
            while (!stopping.get()) {
                runCatching { server.accept() }.getOrNull()?.let(::attachSocket) ?: break
            }
        }
    }

    private fun startTeacher(targetBookId: String) {
        closeSession()
        stopping.set(false)
        role = LanPeerRole.TEACHER_CLIENT
        bookId = targetBookId
        documentHash = library.book(targetBookId).contentSha256
        startForeground(NOTIFICATION_ID, notification("학생 기기 찾는 중"))
        acquireMulticast()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != SERVICE_TYPE || socket?.isConnected == true) return
                resolveService(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { stopDiscovery() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discovery = listener
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= 34) {
            lateinit var callback: NsdManager.ServiceInfoCallback
            callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                override fun onServiceInfoCallbackUnregistered() = Unit
                override fun onServiceLost() = Unit
                override fun onServiceUpdated(info: NsdServiceInfo) {
                    connectResolvedService(info, info.hostAddresses.firstOrNull()?.hostAddress.orEmpty())
                    runCatching { nsd.unregisterServiceInfoCallback(callback) }
                }
            }
            nsd.registerServiceInfoCallback(serviceInfo, io, callback)
        } else {
            resolveLegacy(serviceInfo)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveLegacy(serviceInfo: NsdServiceInfo) {
        nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceResolved(info: NsdServiceInfo) {
                connectResolvedService(info, info.host?.hostAddress.orEmpty())
            }
        })
    }

    private fun connectResolvedService(info: NsdServiceInfo, host: String) {
        val token = info.attributes[ATTRIBUTE_TOKEN]?.toString(Charsets.UTF_8).orEmpty()
        val remoteBook = info.attributes[ATTRIBUTE_BOOK]?.toString(Charsets.UTF_8).orEmpty()
        val remoteHash = info.attributes[ATTRIBUTE_HASH]?.toString(Charsets.UTF_8).orEmpty()
        if (host.isNotBlank() && documentHash.isNotBlank() && remoteHash == documentHash) {
            startTeacherSocket(host, info.port, remoteBook, token)
        }
    }

    private fun startTeacherSocket(host: String, port: Int, targetBookId: String, token: String) {
        if (socket?.isConnected == true) return
        peerBookId = targetBookId
        peerHost = host
        peerPort = port
        pairingToken = token
        io.execute {
            runCatching { attachSocket(Socket(host, port)) }
                .onFailure { scheduleReconnect() }
        }
    }

    private fun attachSocket(connected: Socket) {
        socket?.close()
        socket = connected.apply { tcpNoDelay = true; keepAlive = true }
        writer = BufferedWriter(OutputStreamWriter(connected.getOutputStream(), Charsets.UTF_8))
        send(LanWire.message("HELLO") {
            put("deviceId", library.deviceId)
            put("role", role?.name)
            put("bookId", bookId)
            put("documentHash", documentHash)
            put("token", pairingToken)
        })
        updateNotification("연결됨")
        io.execute { readLoop(connected) }
    }

    private fun readLoop(connected: Socket) {
        try {
            BufferedReader(InputStreamReader(connected.getInputStream(), Charsets.UTF_8)).use { reader ->
                while (!stopping.get()) {
                    val line = reader.readLine() ?: break
                    runCatching { handle(LanWire.decode(line)) }
                        .onFailure { LanSyncBus.sessionIssue("교재 또는 연결 정보를 확인해 주세요.") }
                        .getOrElse { break }
                }
            }
        } finally {
            if (socket === connected) {
                socket = null
                writer = null
                updateNotification("연결 끊김")
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (stopping.get() || role != LanPeerRole.TEACHER_CLIENT || peerHost.isBlank() || peerPort <= 0) return
        handler.postDelayed({
            if (!stopping.get() && socket?.isConnected != true) {
                startTeacherSocket(peerHost, peerPort, peerBookId, pairingToken)
            }
        }, RECONNECT_DELAY_MILLIS)
    }

    private fun handle(message: JSONObject) {
        when (message.getString("type")) {
            "HELLO" -> {
                require(message.getString("documentHash") == documentHash && message.getString("token") == pairingToken)
                peerBookId = message.getString("bookId")
                peerDeviceId = message.getString("deviceId")
                val pairingKey = "${role?.name}:$bookId"
                val pairedDeviceId = pairingPreferences.getString(pairingKey, null)
                require(pairedDeviceId == null || pairedDeviceId == peerDeviceId) { "Another device is already paired" }
                if (pairedDeviceId == null) pairingPreferences.edit().putString(pairingKey, peerDeviceId).apply()
                send(LanWire.message("HELLO_OK"))
                if (role == LanPeerRole.STUDENT_SERVER && currentStudentPage >= 0) {
                    send(LanWire.message("PAGE_STATE") { put("page", currentStudentPage); put("revision", 0L) })
                }
                if (role == LanPeerRole.TEACHER_CLIENT && subscribedPage >= 0) sendSubscription()
            }
            "HELLO_OK" -> if (role == LanPeerRole.TEACHER_CLIENT && subscribedPage >= 0) sendSubscription()
            "SUBSCRIBE" -> {
                subscribedPage = message.getInt("page")
                peerReceivedClocks[library.deviceId] = message.getLong("receivedClock")
                flushPage(subscribedPage)
            }
            "OPERATION" -> {
                val page = message.getInt("page")
                val bytes = Base64.decode(message.getString("payload"), Base64.NO_WRAP)
                val cursor = store.operationCursor(bytes)
                store.appendEncodedOperation(bookId, page, bytes)
                LanSyncBus.remoteOperation(bookId, page)
                send(LanWire.message("ACK") {
                    put("page", page); put("deviceId", cursor.deviceId); put("logicalClock", cursor.logicalClock)
                })
            }
            "ACK" -> if (message.getInt("page") == subscribedPage) {
                if (message.getString("deviceId") == library.deviceId) {
                    peerReceivedClocks[library.deviceId] = maxOf(
                        peerReceivedClocks[library.deviceId] ?: 0L,
                        message.getLong("logicalClock"),
                    )
                }
            }
            "PAGE_STATE" -> LanSyncBus.remotePageChanged(bookId, message.getInt("page"))
        }
    }

    override fun onLocalOperation(bookId: String, pageNumber: Int) {
        if (this.bookId != bookId || pageNumber != subscribedPage) return
        if (role == LanPeerRole.TEACHER_CLIENT) {
            pendingPage = pageNumber
            flushPendingAtStrokeBoundary()
            return
        }
        if (role != LanPeerRole.STUDENT_SERVER) return
        val now = System.currentTimeMillis()
        if (lastFlushAt == 0L) lastFlushAt = now
        if (pendingPage != pageNumber) pendingSince = now
        pendingPage = pageNumber
        handler.removeCallbacks(flushRunnable)
        val untilMaximum = (lastFlushAt + MAX_DELAY_MILLIS - now).coerceAtLeast(0L)
        val delay = minOf(DEBOUNCE_MILLIS, untilMaximum)
        handler.postDelayed(flushRunnable, delay)
    }

    override fun onPageChanged(bookId: String, pageNumber: Int, revision: Long) {
        if (this.bookId != bookId) return
        if (role == LanPeerRole.STUDENT_SERVER) {
            currentStudentPage = pageNumber
            flushPendingAtStrokeBoundary()
            send(LanWire.message("PAGE_STATE") { put("page", pageNumber); put("revision", revision) })
        } else if (role == LanPeerRole.TEACHER_CLIENT) {
            subscribedPage = pageNumber
            sendSubscription()
        }
    }

    private fun sendSubscription() {
        if (writer == null || subscribedPage < 0) return
        val receivedClock = if (peerDeviceId.isBlank()) 0L else runCatching {
            store.maxOperationClock(bookId, subscribedPage, peerDeviceId)
        }.getOrDefault(0L)
        send(LanWire.message("SUBSCRIBE") { put("page", subscribedPage); put("receivedClock", receivedClock) })
    }

    private fun flushPendingAtStrokeBoundary() {
        handler.removeCallbacks(flushRunnable)
        val page = pendingPage
        pendingPage = -1
        if (page >= 0) flushPage(page)
    }

    private fun flushPage(page: Int) {
        if (page != subscribedPage || writer == null) return
        runCatching {
            val acknowledgedClock = peerReceivedClocks[library.deviceId] ?: 0L
            store.encodedOperationsAfter(
                bookId = bookId,
                pageNumber = page,
                originDeviceId = library.deviceId,
                logicalClock = acknowledgedClock,
                includeTeacherDrafts = role != LanPeerRole.TEACHER_CLIENT,
            ).forEach { record ->
                send(LanWire.message("OPERATION") {
                    put("page", page)
                    put("payload", Base64.encodeToString(record, Base64.NO_WRAP))
                })
            }
            lastFlushAt = System.currentTimeMillis()
        }.onFailure {
            updateNotification("필기 로그 확인 필요")
        }
    }

    @Synchronized
    private fun send(line: String) {
        if (line.length > LanWire.MAX_LINE_CHARS) return
        runCatching { writer?.apply { write(line); newLine(); flush() } }
    }

    private fun registerService(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "MasterNote-${library.deviceId.take(6)}"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute(ATTRIBUTE_TOKEN, pairingToken)
            setAttribute(ATTRIBUTE_BOOK, bookId)
            setAttribute(ATTRIBUTE_HASH, documentHash)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun acquireMulticast() {
        multicastLock = getSystemService(WifiManager::class.java).createMulticastLock("masternote-nsd").apply {
            setReferenceCounted(false); acquire()
        }
    }

    private fun stopDiscovery() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    private fun closeSession() {
        stopping.set(true)
        handler.removeCallbacksAndMessages(null)
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        stopDiscovery()
        runCatching { socket?.close() }
        runCatching { serverSocket?.close() }
        socket = null; serverSocket = null; writer = null
        peerBookId = ""
        peerDeviceId = ""
        peerHost = ""
        peerPort = 0
        peerReceivedClocks.clear()
    }

    override fun onDestroy() {
        LanSyncBus.removeListener(this)
        closeSession()
        io.shutdownNow()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "원격 수업", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(text: String) = Notification.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.presence_online)
        .setContentTitle("MasterNote 원격 수업")
        .setContentText(text)
        .setOngoing(true)
        .addAction(
            Notification.Action.Builder(
                null,
                "종료",
                PendingIntent.getService(
                this, 1, Intent(this, LanSyncService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            ).build(),
        )
        .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun localIpv4Address(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .asSequence()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList().asSequence() }
        .filterIsInstance<Inet4Address>()
        .firstOrNull { it.isSiteLocalAddress }
        ?.hostAddress

    companion object {
        const val ACTION_STUDENT_SERVER = "com.studyink.sync.START_STUDENT"
        const val ACTION_TEACHER_DISCOVER = "com.studyink.sync.START_TEACHER"
        const val ACTION_TEACHER_PAIR_URI = "com.studyink.sync.PAIR_URI"
        const val ACTION_STOP = "com.studyink.sync.STOP"
        const val EXTRA_BOOK_ID = "bookId"
        const val EXTRA_PAIR_URI = "pairUri"
        private const val SERVICE_TYPE = "_masternote._tcp."
        private const val ATTRIBUTE_TOKEN = "token"
        private const val ATTRIBUTE_BOOK = "book"
        private const val ATTRIBUTE_HASH = "hash"
        private const val CHANNEL_ID = "remote-class"
        private const val NOTIFICATION_ID = 4201
        private const val DEBOUNCE_MILLIS = 2_000L
        private const val MAX_DELAY_MILLIS = 5_000L
        private const val RECONNECT_DELAY_MILLIS = 2_000L

        fun startStudent(context: Context, bookId: String) = context.startForegroundService(
            Intent(context, LanSyncService::class.java).setAction(ACTION_STUDENT_SERVER).putExtra(EXTRA_BOOK_ID, bookId)
        )
        fun startTeacher(context: Context, bookId: String) = context.startForegroundService(
            Intent(context, LanSyncService::class.java).setAction(ACTION_TEACHER_DISCOVER).putExtra(EXTRA_BOOK_ID, bookId)
        )
        fun startTeacherPairing(context: Context, bookId: String, pairingUri: String) = context.startForegroundService(
            Intent(context, LanSyncService::class.java)
                .setAction(ACTION_TEACHER_PAIR_URI)
                .putExtra(EXTRA_BOOK_ID, bookId)
                .putExtra(EXTRA_PAIR_URI, pairingUri)
        )
        fun stop(context: Context) = context.startService(Intent(context, LanSyncService::class.java).setAction(ACTION_STOP))
    }
}

/** Keeps android.net.Uri parsing isolated for local JVM protocol tests. */
private object UriCompat { fun parse(value: String) = android.net.Uri.parse(value) }
