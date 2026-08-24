package com.studyink.sync.lan

import android.app.NotificationChannel
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Build
import android.util.Base64
import android.util.Log
import com.studyink.annotation.storage.PageOperationLogStore
import com.studyink.core.model.Attempt
import com.studyink.core.model.MarkGroup
import com.studyink.library.data.LibraryAttemptBus
import com.studyink.library.data.LibraryMarkGroupBus
import com.studyink.library.data.LibraryRepository
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-student/one-teacher LAN transport. Stroke operations follow the subscribed page, while
 * student attempts and teacher mark groups use idempotent full-state upserts. Local persistence
 * never waits for this service.
 */
class LanSyncService : Service(),
    LanSyncBus.Listener,
    LibraryAttemptBus.Listener,
    LibraryMarkGroupBus.Listener {
    private val io = Executors.newCachedThreadPool()
    private val metadataIo = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val store by lazy { PageOperationLogStore.get(this) }
    private val library by lazy { LibraryRepository.get(this) }
    private val pairingPreferences by lazy { getSharedPreferences("masternote-lan-pairs", MODE_PRIVATE) }
    private val nsd by lazy { getSystemService(NsdManager::class.java) }
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    @Volatile private var wifiNetwork: Network? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    // Session state is written by the socket reader, the flush timer on the main looper, the
    // reader's mutation dispatcher and the metadata executor. Without these barriers a subscription
    // set on the socket thread could stay invisible to onLocalOperation, which is what stopped live
    // ink from ever being flushed while page-change repair - running on the socket thread - worked.
    @Volatile private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var registration: NsdManager.RegistrationListener? = null
    @Volatile private var discovery: NsdManager.DiscoveryListener? = null
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var role: LanPeerRole? = null
    @Volatile private var peerRole: LanPeerRole? = null
    @Volatile private var bookId: String = ""
    @Volatile private var peerBookId: String = ""
    @Volatile private var documentHash: String = ""
    @Volatile private var peerDeviceId: String = ""
    @Volatile private var peerHost: String = ""
    @Volatile private var peerPort: Int = 0
    @Volatile private var pairingToken: String = ""
    @Volatile private var subscribedPage = -1
    private val peerReceivedClocks = PageOperationWatermarks()
    @Volatile private var pendingPage = -1
    @Volatile private var pendingSince = 0L
    @Volatile private var lastFlushAt = 0L
    @Volatile private var currentStudentPage = -1
    @Volatile private var currentStudentAttemptNo: Int? = null
    @Volatile private var currentStudentRevision = 0L
    @Volatile private var currentTeacherAttemptNo: Int? = null
    @Volatile private var followRemoteStudent = false
    @Volatile private var cachedPageCountBookId: String? = null
    @Volatile private var cachedPageCount = 0
    @Volatile private var connectionGeneration = 0L
    @Volatile private var lastSubscriptionGeneration = -1L
    @Volatile private var lastSubscriptionPage = -1
    @Volatile private var lastTeacherRepairGeneration = -1L
    private val stopping = AtomicBoolean(false)

    // The debounce timer lives on the main looper, but the flush it triggers writes to a socket.
    // Doing that inline threw NetworkOnMainThreadException on every live stroke, which send()
    // swallowed as a generic write failure - so live ink was never transmitted while the paths that
    // already ran off the main thread (peer SUBSCRIBE, page presence) worked and masked it.
    private val flushRunnable = Runnable { io.execute { flushPendingAtStrokeBoundary() } }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        trackWifiNetwork()
        LanSyncBus.addListener(this)
        LibraryAttemptBus.addListener(this)
        LibraryMarkGroupBus.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every caller uses startForegroundService, which promises a startForeground within a few
        // seconds or the platform kills the process. Reading the book and parsing the pairing URI
        // can both throw, and they used to run before that promise was kept, so a missing book or
        // a malformed QR took the whole app down. Claim the foreground slot first, then start.
        runCatching { startForeground(NOTIFICATION_ID, notification("원격 수업 준비 중")) }
            .onFailure { Log.e(TAG, "startForeground failed", it) }
        val action = intent?.action
        if (action == null || action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching {
            when (action) {
                ACTION_STUDENT_SERVER -> startStudent(requireNotNull(intent.getStringExtra(EXTRA_BOOK_ID)))
                ACTION_TEACHER_DISCOVER -> startTeacher(requireNotNull(intent.getStringExtra(EXTRA_BOOK_ID)))
                ACTION_TEACHER_PAIR_URI -> startTeacherPairing(
                    targetBookId = requireNotNull(intent.getStringExtra(EXTRA_BOOK_ID)),
                    payload = PairingPayload.parse(
                        UriCompat.parse(requireNotNull(intent.getStringExtra(EXTRA_PAIR_URI)))
                    ),
                )
                else -> Unit
            }
        }.onFailure { error ->
            Log.e(TAG, "session start failed action=$action", error)
            LanSyncBus.sessionIssue("원격 수업을 시작하지 못했습니다. 교재와 연결 정보를 확인해 주세요.")
            closeSession()
            stopSelf()
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
        bootstrapLocalPresence()
        logSessionStart()
        startForeground(NOTIFICATION_ID, notification("선생 기기 연결 대기 중"))
        LanSyncBus.connectionStateChanged(bookId, LanConnectionState.CONNECTING)
        LanSyncBus.sessionPhaseChanged(bookId, LanSessionPhase.CONNECTING)
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
                val accepted = runCatching { server.accept() }.getOrNull() ?: break
                // attachSocket writes the greeting, so it can throw for the same reason readLoop
                // can. Losing one peer must not end the accept loop or the process.
                runCatching { attachSocket(accepted) }.onFailure {
                    Log.w(TAG, "LAN attach failed book=$bookId", it)
                    runCatching { accepted.close() }
                }
            }
        }
    }

    private fun startTeacher(targetBookId: String) {
        closeSession()
        LanSyncBus.clearRemoteStudentLocation(targetBookId)
        stopping.set(false)
        role = LanPeerRole.TEACHER_CLIENT
        bookId = targetBookId
        documentHash = library.book(targetBookId).contentSha256
        bootstrapLocalPresence()
        logSessionStart()
        startForeground(NOTIFICATION_ID, notification("학생 기기 찾는 중"))
        LanSyncBus.connectionStateChanged(bookId, LanConnectionState.CONNECTING)
        LanSyncBus.sessionPhaseChanged(bookId, LanSessionPhase.CONNECTING)
        acquireMulticast()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != SERVICE_TYPE || socket?.isConnected == true) return
                resolveService(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                // Silently stopping here is what let the chrome claim live monitoring while no
                // discovery was running at all.
                Log.w(TAG, "discovery start failed book=$bookId error=$errorCode")
                stopDiscovery()
                LanSyncBus.connectionStateChanged(bookId, LanConnectionState.DISCONNECTED)
                LanSyncBus.sessionIssue("학생 기기를 찾지 못했습니다. 다시 연결해 주세요.")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discovery = listener
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun startTeacherPairing(targetBookId: String, payload: PairingPayload) {
        closeSession()
        LanSyncBus.clearRemoteStudentLocation(targetBookId)
        stopping.set(false)
        role = LanPeerRole.TEACHER_CLIENT
        bookId = targetBookId
        documentHash = library.book(targetBookId).contentSha256
        bootstrapLocalPresence()
        logSessionStart()
        startForeground(NOTIFICATION_ID, notification("학생 기기에 연결 중"))
        LanSyncBus.connectionStateChanged(bookId, LanConnectionState.CONNECTING)
        LanSyncBus.sessionPhaseChanged(bookId, LanSessionPhase.CONNECTING)
        startTeacherSocket(payload.host, payload.port, payload.bookId, payload.token)
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
            runCatching { attachSocket(openPeerSocket(host, port)) }
                .onFailure {
                    Log.w(TAG, "LAN connect failed host=$host port=$port wifi=${wifiNetwork != null}", it)
                    if (wifiNetwork == null) {
                        LanSyncBus.sessionIssue("같은 Wi-Fi에 연결되어 있는지 확인해 주세요.")
                    }
                    scheduleReconnect()
                }
        }
    }

    private fun attachSocket(connected: Socket) {
        socket?.close()
        socket = connected.apply { tcpNoDelay = true; keepAlive = true }
        writer = BufferedWriter(OutputStreamWriter(connected.getOutputStream(), Charsets.UTF_8))
        connectionGeneration += 1L
        lastSubscriptionGeneration = -1L
        lastSubscriptionPage = -1
        lastTeacherRepairGeneration = -1L
        send(LanWire.message("HELLO") {
            put("deviceId", library.deviceId)
            put("role", role?.name)
            put("bookId", bookId)
            put("documentHash", documentHash)
            put("token", pairingToken)
        })
        updateNotification("연결됨")
        Log.i(TAG, "LAN attached role=$role book=$bookId generation=$connectionGeneration")
        LanSyncBus.connectionStateChanged(bookId, LanConnectionState.CONNECTED)
        LanSyncBus.sessionPhaseChanged(bookId, LanSessionPhase.SOCKET_CONNECTED)
        io.execute { readLoop(connected) }
    }

    private fun readLoop(connected: Socket) {
        try {
            BufferedReader(InputStreamReader(connected.getInputStream(), Charsets.UTF_8)).use { reader ->
                while (!stopping.get()) {
                    val line = reader.readLine() ?: break
                    val type = runCatching { JSONObject(line).optString("type") }.getOrDefault("")
                    val failure = runCatching { handle(LanWire.decode(line)) }.exceptionOrNull()
                        ?: continue
                    Log.w(TAG, "LAN message failed role=$role type=$type", failure)
                    // Only the handshake is worth dropping the link for. Tearing the session down
                    // on any single bad payload is what turned one unusable operation into a dead
                    // connection that never came back.
                    if (type == "HELLO" || type == "HELLO_OK" || type.isEmpty()) {
                        LanSyncBus.sessionIssue("교재 또는 연결 정보를 확인해 주세요.")
                        break
                    }
                }
            }
        } catch (error: Throwable) {
            // A peer that disappears mid-read makes readLine throw rather than return null. This
            // body runs on an executor thread, where an uncaught throwable takes the whole process
            // down - which is how losing the link killed the app instead of showing "연결 끊김".
            Log.w(TAG, "LAN read loop ended role=$role book=$bookId", error)
        } finally {
            if (socket === connected) {
                socket = null
                writer = null
                lastSubscriptionGeneration = -1L
                lastSubscriptionPage = -1
                lastTeacherRepairGeneration = -1L
                updateNotification("연결 끊김")
                Log.i(TAG, "LAN detached role=$role book=$bookId generation=$connectionGeneration")
                LanSyncBus.connectionStateChanged(bookId, LanConnectionState.DISCONNECTED)
                LanSyncBus.sessionPhaseChanged(bookId, LanSessionPhase.DISCONNECTED)
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        if (stopping.get() || role != LanPeerRole.TEACHER_CLIENT || peerHost.isBlank() || peerPort <= 0) return
        handler.postDelayed({
            if (!stopping.get() && socket?.isConnected != true) {
                Log.i(TAG, "LAN reconnect attempt book=$bookId host=$peerHost port=$peerPort")
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
                peerRole = LanPeerRole.valueOf(message.getString("role")).also { announcedRole ->
                    require(
                        role == LanPeerRole.STUDENT_SERVER && announcedRole == LanPeerRole.TEACHER_CLIENT ||
                            role == LanPeerRole.TEACHER_CLIENT && announcedRole == LanPeerRole.STUDENT_SERVER
                    ) { "Peer role does not match this session" }
                }
                val pairingKey = "${role?.name}:$bookId"
                val pairedDeviceId = pairingPreferences.getString(pairingKey, null)
                require(pairedDeviceId == null || pairedDeviceId == peerDeviceId) { "Another device is already paired" }
                if (pairedDeviceId == null) pairingPreferences.edit().putString(pairingKey, peerDeviceId).apply()
                LanSyncBus.sessionPhaseChanged(bookId, LanSessionPhase.HANDSHAKE_COMPLETE)
                send(LanWire.message("HELLO_OK"))
                sendMetadataSnapshot()
                if (role == LanPeerRole.STUDENT_SERVER) sendStudentPageState("hello")
                repairTeacherConnection()
            }
            "HELLO_OK" -> {
                LanSyncBus.sessionPhaseChanged(bookId, LanSessionPhase.HANDSHAKE_COMPLETE)
                repairTeacherConnection()
            }
            "SUBSCRIBE" -> {
                require(
                    role == LanPeerRole.STUDENT_SERVER && peerRole == LanPeerRole.TEACHER_CLIENT
                ) { "Only a teacher peer may subscribe to student ink" }
                val requestedPage = message.getInt("page")
                require(isPageInBook(requestedPage)) { "Subscription page is outside the book" }
                updateDesiredSubscription(requestedPage, "peer-request")
                peerReceivedClocks.replace(
                    pageNumber = requestedPage,
                    deviceId = library.deviceId,
                    logicalClock = message.getLong("receivedClock"),
                )
                LanSyncBus.sessionPhaseChanged(bookId, LanSessionPhase.PAGE_CATCHING_UP)
                // A teacher may enter Live Monitor after the original page event. Repeating the
                // state is safe because the teacher's subscription sender is connection-idempotent.
                sendStudentPageState("subscription")
                if (flushPage(subscribedPage)) {
                    if (pendingPage == subscribedPage) pendingPage = -1
                    if (sendPageSynced(subscribedPage)) {
                        LanSyncBus.sessionPhaseChanged(bookId, LanSessionPhase.READY)
                    }
                }
            }
            "OPERATION" -> {
                val page = message.getInt("page")
                require(isPageInBook(page)) { "Operation page is outside the book" }
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
                    peerReceivedClocks.acknowledge(
                        pageNumber = message.getInt("page"),
                        deviceId = library.deviceId,
                        logicalClock = message.getLong("logicalClock"),
                    )
                }
            }
            "PAGE_STATE" -> {
                require(
                    role == LanPeerRole.TEACHER_CLIENT && peerRole == LanPeerRole.STUDENT_SERVER
                ) { "Only a student peer may publish page state" }
                val page = message.getInt("page")
                require(isPageInBook(page)) { "Student page is outside the book" }
                val attemptNo = message.optionalNonNegativeInt("attemptNo")
                val revision = message.optLong("revision", 0L).also {
                    require(it >= 0L) { "Student page revision cannot be negative" }
                }
                val location = StudentLocation(bookId, page, attemptNo, revision)
                Log.i(
                    TAG,
                    "PAGE_STATE receive book=$bookId page=$page attempt=${attemptNo ?: "-"} revision=$revision follow=$followRemoteStudent",
                )
                if (followRemoteStudent) {
                    updateDesiredSubscription(page, "live-follow")
                }
                // Assert the subscription on every student page report, not only while following.
                // A teacher that connected before its reader published a page would otherwise stay
                // silently unsubscribed and receive no live ink.
                ensureSubscription()
                LanSyncBus.remotePageChanged(location)
            }
            "PAGE_SYNCED" -> {
                require(
                    role == LanPeerRole.TEACHER_CLIENT && peerRole == LanPeerRole.STUDENT_SERVER
                ) { "Only a student peer may complete page catch-up" }
                val page = message.getInt("page")
                require(isPageInBook(page)) { "Synchronized page is outside the book" }
                if (page == subscribedPage) {
                    LanSyncBus.sessionPhaseChanged(bookId, LanSessionPhase.READY)
                }
            }
            "ATTEMPT_UPSERT" -> {
                require(
                    role == LanPeerRole.TEACHER_CLIENT && peerRole == LanPeerRole.STUDENT_SERVER
                ) { "Only a student peer may send attempts" }
                require(message.getString("bookId") == peerBookId) { "Attempt book does not match peer" }
                val page = message.getInt("page")
                val attempt = AttemptWireCodec.decode(message.getJSONObject("payload"), bookId, page)
                if (library.upsertAttemptFromSync(bookId, page, attempt)) {
                    LanSyncBus.remoteAttempt(bookId, page)
                }
            }
            "MARK_GROUP_UPSERT" -> {
                require(role != null) { "Mark received outside a LAN session" }
                require(message.getString("bookId") == peerBookId) { "Mark book does not match peer" }
                val page = message.getInt("page")
                val group = MarkGroupWireCodec.decode(message.getJSONObject("payload"), bookId, page)
                if (library.upsertMarkGroupFromSync(bookId, page, group)) {
                    LanSyncBus.remoteMarkGroup(bookId, page)
                }
            }
        }
    }

    override fun onLocalAttemptChanged(attempt: Attempt) {
        if (role != LanPeerRole.STUDENT_SERVER || attempt.bookId != bookId) return
        enqueueAttempt(attempt)
    }

    override fun onLocalMarkGroupChanged(group: MarkGroup) {
        if (role == null || group.bookId != bookId) return
        enqueueMarkGroup(group)
    }

    override fun onLocalOperation(bookId: String, pageNumber: Int) {
        // Arrives on the reader's mutation dispatcher. Hop onto the thread that owns the flush
        // timer so the pending-page bookkeeping is never interleaved with the socket reader.
        handler.post { scheduleLocalFlush(bookId, pageNumber) }
    }

    private fun scheduleLocalFlush(bookId: String, pageNumber: Int) {
        if (this.bookId != bookId || pageNumber != subscribedPage) return
        if (role == LanPeerRole.TEACHER_CLIENT) {
            pendingPage = pageNumber
            // Published corrections go out immediately, but this runs on the looper that owns the
            // debounce timer, so the socket write itself has to leave the main thread.
            io.execute { flushPendingAtStrokeBoundary() }
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

    override fun onPagePresenceChanged(presence: PagePresence) {
        if (bookId != presence.bookId || !isPageInBook(presence.pageNumber)) return
        if (role == LanPeerRole.STUDENT_SERVER) {
            flushPendingAtStrokeBoundary()
            currentStudentPage = presence.pageNumber
            currentStudentAttemptNo = presence.attemptNo
            currentStudentRevision = presence.revision
            // Local presence is also published after ordinary strokes and attempt updates. Those
            // mutations are already delivered by the operation/attempt queues and must not make a
            // healthy LAN session look unavailable to the LAN-first transport selector. Only an
            // actual teacher subscription changes the page catch-up phase (SUBSCRIBE -> READY).
            sendStudentPageState("local-presence")
        } else if (role == LanPeerRole.TEACHER_CLIENT) {
            currentTeacherAttemptNo = presence.attemptNo
            followRemoteStudent = presence.followRemoteStudent
            val desiredPage = if (followRemoteStudent) {
                LanSyncBus.remoteStudentLocation(bookId)
                    ?.takeIf { isPageInBook(it.pageNumber) }
                    ?.pageNumber
                    ?: presence.pageNumber
            } else {
                presence.pageNumber
            }
            updateDesiredSubscription(desiredPage, "local-presence")
            if (ensureSubscription()) {
                if (flushPage(desiredPage) && pendingPage == desiredPage) pendingPage = -1
            }
        }
    }

    @Synchronized
    private fun ensureSubscription(force: Boolean = false): Boolean {
        val page = subscribedPage
        if (writer == null || !isPageInBook(page)) return false
        if (
            !force &&
            lastSubscriptionGeneration == connectionGeneration &&
            lastSubscriptionPage == page
        ) return true
        val receivedClock = if (peerDeviceId.isBlank()) 0L else runCatching {
            store.maxOperationClock(bookId, page, peerDeviceId)
        }.getOrDefault(0L)
        val sent = send(LanWire.message("SUBSCRIBE") {
            put("page", page)
            put("receivedClock", receivedClock)
        })
        if (sent) {
            lastSubscriptionGeneration = connectionGeneration
            lastSubscriptionPage = page
            Log.i(TAG, "SUBSCRIBE send book=$bookId page=$page generation=$connectionGeneration")
        }
        return sent
    }

    private fun repairTeacherConnection() {
        if (role != LanPeerRole.TEACHER_CLIENT || writer == null) return
        if (!isPageInBook(subscribedPage)) {
            // The reader may have published its page before this session existed, or after the
            // bootstrap read. Without this the teacher connects and never subscribes at all, and
            // student ink only appears when a page change happens to trigger a subscription.
            LanSyncBus.localPagePresence(bookId)
                ?.takeIf { isPageInBook(it.pageNumber) }
                ?.let { presence ->
                    followRemoteStudent = presence.followRemoteStudent
                    updateDesiredSubscription(presence.pageNumber, "connect-bootstrap")
                }
        }
        if (!isPageInBook(subscribedPage)) return
        if (lastTeacherRepairGeneration == connectionGeneration) return
        LanSyncBus.sessionPhaseChanged(bookId, LanSessionPhase.PAGE_CATCHING_UP)
        if (!ensureSubscription()) return
        lastTeacherRepairGeneration = connectionGeneration
        // A reconnect must repair published teacher operations as well as request student ink.
        if (flushPage(subscribedPage) && pendingPage == subscribedPage) pendingPage = -1
    }

    private fun updateDesiredSubscription(pageNumber: Int, reason: String) {
        if (!isPageInBook(pageNumber)) return
        if (subscribedPage == pageNumber) return
        subscribedPage = pageNumber
        if (writer != null) {
            LanSyncBus.sessionPhaseChanged(bookId, LanSessionPhase.PAGE_CATCHING_UP)
        }
        Log.i(TAG, "subscription target book=$bookId page=$pageNumber reason=$reason")
    }

    private fun sendStudentPageState(reason: String): Boolean {
        if (role != LanPeerRole.STUDENT_SERVER || writer == null || !isPageInBook(currentStudentPage)) return false
        val page = currentStudentPage
        val attemptNo = currentStudentAttemptNo
        val revision = currentStudentRevision
        val sent = send(LanWire.message("PAGE_STATE") {
            put("page", page)
            put("revision", revision)
            attemptNo?.let { put("attemptNo", it) }
        })
        if (sent) {
            Log.i(
                TAG,
                "PAGE_STATE send book=$bookId page=$page attempt=${attemptNo ?: "-"} revision=$revision reason=$reason",
            )
        }
        return sent
    }

    /** Ordered after every operation emitted by [flushPage] on the same synchronized writer. */
    private fun sendPageSynced(page: Int): Boolean {
        if (role != LanPeerRole.STUDENT_SERVER || page != subscribedPage || writer == null) return false
        return send(LanWire.message("PAGE_SYNCED") {
            put("page", page)
            put("revision", currentStudentRevision.coerceAtLeast(0L))
        }).also { sent ->
            if (sent) Log.i(TAG, "PAGE_SYNCED send book=$bookId page=$page generation=$connectionGeneration")
        }
    }

    private fun bootstrapLocalPresence() {
        val presence = LanSyncBus.localPagePresence(bookId)
            ?.takeIf { isPageInBook(it.pageNumber) }
            ?: return
        when (role) {
            LanPeerRole.STUDENT_SERVER -> {
                currentStudentPage = presence.pageNumber
                currentStudentAttemptNo = presence.attemptNo
                currentStudentRevision = presence.revision
            }

            LanPeerRole.TEACHER_CLIENT -> {
                currentTeacherAttemptNo = presence.attemptNo
                followRemoteStudent = presence.followRemoteStudent
                subscribedPage = presence.pageNumber
            }

            null -> Unit
        }
    }

    private fun logSessionStart() {
        val localPage = when (role) {
            LanPeerRole.STUDENT_SERVER -> currentStudentPage
            LanPeerRole.TEACHER_CLIENT -> subscribedPage
            null -> -1
        }
        val localAttempt = when (role) {
            LanPeerRole.STUDENT_SERVER -> currentStudentAttemptNo
            LanPeerRole.TEACHER_CLIENT -> currentTeacherAttemptNo
            null -> null
        }
        Log.i(
            TAG,
            "session start role=$role book=$bookId page=$localPage attempt=${localAttempt ?: "-"} follow=$followRemoteStudent",
        )
    }

    /**
     * Page count never changes for a book, but reading it takes the library lock, which a catalog
     * write holds while it fsyncs. This runs for every inbound message, so it caches instead.
     */
    private fun bookPageCount(): Int {
        val current = bookId
        if (current.isBlank()) return 0
        cachedPageCountBookId.takeIf { it == current }?.let { return cachedPageCount }
        val count = runCatching { library.book(current).pageCount }.getOrDefault(0)
        if (count > 0) {
            cachedPageCount = count
            cachedPageCountBookId = current
        }
        return count
    }

    private fun isPageInBook(pageNumber: Int): Boolean =
        pageNumber >= 0 && pageNumber < bookPageCount()

    /**
     * Repairs events that occurred before connection or during a disconnect. Attempts come from
     * the student role; versioned mark groups converge in both directions.
     */
    private fun sendMetadataSnapshot() {
        val expectedBookId = bookId
        val expectedRole = role ?: return
        metadataIo.execute {
            if (role != expectedRole || bookId != expectedBookId || writer == null) return@execute
            if (expectedRole == LanPeerRole.STUDENT_SERVER) {
                library.attemptsForSync(expectedBookId).forEach(::sendAttemptNow)
            }
            // Either physical device may enter teacher perspective, so marks converge both ways.
            library.markGroupsForSync(expectedBookId).forEach(::sendMarkGroupNow)
        }
    }

    private fun enqueueAttempt(attempt: Attempt) {
        metadataIo.execute {
            if (role == LanPeerRole.STUDENT_SERVER && bookId == attempt.bookId && writer != null) {
                sendAttemptNow(attempt)
            }
        }
    }

    private fun enqueueMarkGroup(group: MarkGroup) {
        metadataIo.execute {
            if (role != null && bookId == group.bookId && writer != null) {
                sendMarkGroupNow(group)
            }
        }
    }

    private fun sendAttemptNow(attempt: Attempt) {
        send(LanWire.message("ATTEMPT_UPSERT") {
            put("bookId", attempt.bookId)
            put("page", attempt.pageNumber)
            put("payload", AttemptWireCodec.encode(attempt))
        })
    }

    private fun sendMarkGroupNow(group: MarkGroup) {
        send(LanWire.message("MARK_GROUP_UPSERT") {
            put("bookId", group.bookId)
            put("page", group.pageNumber)
            put("payload", MarkGroupWireCodec.encode(group))
        })
    }

    private fun flushPendingAtStrokeBoundary() {
        handler.removeCallbacks(flushRunnable)
        val page = pendingPage
        if (page < 0) return
        if (writer == null) {
            // Keep the trigger. The reconnect repair path will retry durable operations.
            return
        }
        if (page != subscribedPage) {
            pendingPage = -1
            return
        }
        if (flushPage(page)) pendingPage = -1
    }

    private fun flushPage(page: Int): Boolean {
        if (page != subscribedPage || writer == null || !isPageInBook(page)) return false
        return runCatching {
            val acknowledgedClock = peerReceivedClocks.clock(page, library.deviceId)
            val records = store.encodedOperationsAfter(
                bookId = bookId,
                pageNumber = page,
                originDeviceId = library.deviceId,
                logicalClock = acknowledgedClock,
                includeTeacherDrafts = role != LanPeerRole.TEACHER_CLIENT,
            )
            val allSent = records.all { record ->
                send(LanWire.message("OPERATION") {
                    put("page", page)
                    put("payload", Base64.encodeToString(record, Base64.NO_WRAP))
                })
            }
            if (!allSent) return@runCatching false
            lastFlushAt = System.currentTimeMillis()
            Log.i(TAG, "operation flush role=$role book=$bookId page=$page count=${records.size}")
            true
        }.onFailure {
            updateNotification("필기 로그 확인 필요")
        }.getOrDefault(false)
    }

    @Synchronized
    private fun send(line: String): Boolean {
        if (line.length > LanWire.MAX_LINE_CHARS) return false
        // Socket writes on the looper throw NetworkOnMainThreadException, which this method used to
        // swallow as an ordinary write failure - twice, once per direction. Refuse loudly instead of
        // failing quietly, and never by throwing: a caller that gets this wrong must not take the
        // process down with it.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.e(TAG, "LAN send attempted on the main thread role=$role", IllegalStateException(line.take(40)))
            return false
        }
        val target = writer ?: return false
        return runCatching {
            target.write(line)
            target.newLine()
            target.flush()
            true
        }.onFailure {
            Log.w(TAG, "LAN write failed role=$role book=$bookId", it)
        }.getOrDefault(false)
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
        LanSyncBus.clearConnectionState(bookId)
        stopping.set(true)
        handler.removeCallbacksAndMessages(null)
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        stopDiscovery()
        runCatching { socket?.close() }
        runCatching { serverSocket?.close() }
        socket = null; serverSocket = null; writer = null
        role = null
        bookId = ""
        documentHash = ""
        pairingToken = ""
        peerBookId = ""
        peerRole = null
        peerDeviceId = ""
        peerHost = ""
        peerPort = 0
        subscribedPage = -1
        currentStudentPage = -1
        currentStudentAttemptNo = null
        currentStudentRevision = 0L
        currentTeacherAttemptNo = null
        followRemoteStudent = false
        pendingPage = -1
        pendingSince = 0L
        lastFlushAt = 0L
        connectionGeneration = 0L
        lastSubscriptionGeneration = -1L
        lastSubscriptionPage = -1
        lastTeacherRepairGeneration = -1L
        peerReceivedClocks.clear()
    }

    override fun onDestroy() {
        wifiCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        wifiCallback = null
        LanSyncBus.removeListener(this)
        LibraryAttemptBus.removeListener(this)
        LibraryMarkGroupBus.removeListener(this)
        closeSession()
        io.shutdownNow()
        metadataIo.shutdownNow()
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

    /**
     * Keeps a handle on the Wi-Fi network. A phone with mobile data up routes app sockets through
     * cellular by default, and a classroom LAN address is simply unreachable there - the connect
     * times out with no hint as to why. Note the request deliberately does not ask for INTERNET:
     * the Wi-Fi that matters here may have no working uplink at all.
     */
    private fun trackWifiNetwork() {
        if (wifiCallback != null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifiNetwork = network
                Log.i(TAG, "wifi network available")
            }

            override fun onLost(network: Network) {
                if (wifiNetwork == network) wifiNetwork = null
                Log.i(TAG, "wifi network lost")
            }
        }
        wifiCallback = callback
        runCatching { connectivity.registerNetworkCallback(request, callback) }
            .onFailure {
                wifiCallback = null
                Log.w(TAG, "wifi network callback failed", it)
            }
    }

    private fun openPeerSocket(host: String, port: Int): Socket {
        val network = wifiNetwork
        val socket = network?.socketFactory?.createSocket() ?: Socket()
        return socket.apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS) }
    }

    /**
     * Address to advertise in the pairing code. Prefers the Wi-Fi link so a phone that also holds a
     * cellular address never hands a peer something it cannot reach.
     */
    private fun localIpv4Address(): String? = wifiLinkIpv4Address() ?: anyLocalIpv4Address()

    private fun wifiLinkIpv4Address(): String? {
        val network = wifiNetwork ?: return null
        return runCatching {
            connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()
                .map(LinkAddress::getAddress)
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }

    private fun anyLocalIpv4Address(): String? = NetworkInterface.getNetworkInterfaces().toList()
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
        private const val DEBOUNCE_MILLIS = 180L
        private const val MAX_DELAY_MILLIS = 600L
        private const val RECONNECT_DELAY_MILLIS = 2_000L
        // A LAN peer answers in milliseconds. Waiting out the platform default just stalls retries.
        private const val CONNECT_TIMEOUT_MILLIS = 5_000
        private const val TAG = "MasterNoteLan"

        fun startStudent(context: Context, bookId: String) = context.startForegroundService(
            Intent(context, LanSyncService::class.java).setAction(ACTION_STUDENT_SERVER).putExtra(EXTRA_BOOK_ID, bookId)
        )
        fun startTeacher(context: Context, bookId: String) {
            // Clear synchronously before the caller launches ReaderActivity. Service start is
            // asynchronous, so clearing only in onStartCommand can expose a previous session.
            LanSyncBus.clearRemoteStudentLocation(bookId)
            context.startForegroundService(
                Intent(context, LanSyncService::class.java)
                    .setAction(ACTION_TEACHER_DISCOVER)
                    .putExtra(EXTRA_BOOK_ID, bookId)
            )
        }

        fun startTeacherPairing(context: Context, bookId: String, pairingUri: String) {
            LanSyncBus.clearRemoteStudentLocation(bookId)
            context.startForegroundService(
                Intent(context, LanSyncService::class.java)
                    .setAction(ACTION_TEACHER_PAIR_URI)
                    .putExtra(EXTRA_BOOK_ID, bookId)
                    .putExtra(EXTRA_PAIR_URI, pairingUri)
            )
        }
        fun stop(context: Context) = context.startService(Intent(context, LanSyncService::class.java).setAction(ACTION_STOP))
    }
}

/** Keeps android.net.Uri parsing isolated for local JVM protocol tests. */
private object UriCompat { fun parse(value: String) = android.net.Uri.parse(value) }

private fun JSONObject.optionalNonNegativeInt(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return getInt(name).also { require(it >= 0) { "$name cannot be negative" } }
}
