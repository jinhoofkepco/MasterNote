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
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.studyink.assistant.core.AssistantPageKey
import com.studyink.assistant.core.AssistantPublicationLimits
import com.studyink.assistant.core.AssistantRepositoryProvider
import com.studyink.assistant.core.StudentExplanationLayer
import com.studyink.assistant.core.StudentExplanationLayerBus
import com.studyink.assistant.core.StudentExplanationTarget
import com.studyink.assistant.core.StudentLayerApplyStatus
import com.studyink.assistant.core.remapTo
import com.studyink.annotation.storage.PageOperationLogStore
import com.studyink.core.model.Attempt
import com.studyink.core.model.MarkGroup
import com.studyink.library.data.LibraryAttemptBus
import com.studyink.library.data.LibraryMarkGroupBus
import com.studyink.library.data.LibraryRepository
import org.json.JSONArray
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
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    private val teacherReviewIo = Executors.newSingleThreadScheduledExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val store by lazy { PageOperationLogStore.get(this) }
    private val library by lazy { LibraryRepository.get(this) }
    private val assistantRepository by lazy { AssistantRepositoryProvider.get(this) }
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
    /** The generation is authenticated only after this exact socket's HELLO fully validates. */
    @Volatile private var authenticatedConnectionGeneration = 0L
    @Volatile private var bookId: String = ""
    @Volatile private var peerBookId: String = ""
    /** Discovery/QR reconnect target; unlike [peerBookId], this is not authenticated peer state. */
    @Volatile private var reconnectPeerBookId: String = ""
    @Volatile private var documentHash: String = ""
    @Volatile private var peerDeviceId: String = ""
    @Volatile private var peerHost: String = ""
    @Volatile private var peerPort: Int = 0
    @Volatile private var pairingToken: String = ""
    @Volatile private var localAuthNonce: String = ""
    @Volatile private var pendingPeerHelloGeneration = 0L
    @Volatile private var pendingPeerNonce: String = ""
    @Volatile private var pendingPeerBookId: String = ""
    @Volatile private var pendingPeerDeviceId: String = ""
    @Volatile private var pendingPeerRole: LanPeerRole? = null
    @Volatile private var peerSupportsGptExplanation = false
    /** True only for a session that is visibly offering/consuming a QR pairing payload. */
    @Volatile private var explicitPairingWindow = false
    @Volatile private var subscribedPage = -1
    private val peerReceivedClocks = PageOperationWatermarks()
    private val pendingTeacherReviewAcks = linkedMapOf<String, PendingLanTeacherReviewAck>()
    private val pendingGptExplanationAcks = linkedMapOf<String, PendingLanGptExplanationAck>()
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
    private val connectionEpoch = MonotonicLanConnectionEpoch()
    private val connectionGeneration: Long get() = connectionEpoch.current
    @Volatile private var catchUpYieldRequestedGeneration = -1L
    @Volatile private var lastPeerReceiveAtElapsedMs = 0L
    /** Non-zero only while an authenticated socket still owes a PAGE_SYNCED transition. */
    @Volatile private var readyDeadlineAtElapsedMs = 0L
    @Volatile private var lastSubscriptionGeneration = -1L
    @Volatile private var lastSubscriptionPage = -1
    @Volatile private var lastTeacherRepairGeneration = -1L
    @Volatile private var lastTeacherPublicationRepairGeneration = -1L
    private val incomingTeacherReviewChunks = linkedMapOf<String, IncomingTeacherReviewChunks>()
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
        LanSyncBus.sessionRoleChanged(bookId, requireNotNull(role))
        documentHash = requireLanDocumentHash(targetBookId)
        pairingToken = loadOrCreateStudentPairingSecret(targetBookId, documentHash)
        explicitPairingWindow = true
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
        LanSyncBus.sessionRoleChanged(bookId, requireNotNull(role))
        documentHash = requireLanDocumentHash(targetBookId)
        pairingToken = storedPairingSecret(LanPeerRole.TEACHER_CLIENT, targetBookId, documentHash).orEmpty()
        explicitPairingWindow = false
        bootstrapLocalPresence()
        logSessionStart()
        startForeground(NOTIFICATION_ID, notification("학생 기기 찾는 중"))
        LanSyncBus.connectionStateChanged(bookId, LanConnectionState.CONNECTING)
        LanSyncBus.sessionPhaseChanged(bookId, LanSessionPhase.CONNECTING)
        if (!isValidLanSha256(pairingToken)) {
            updateNotification("최초 연결은 QR 스캔 필요")
            LanSyncBus.connectionStateChanged(bookId, LanConnectionState.DISCONNECTED)
            LanSyncBus.sessionPhaseChanged(bookId, LanSessionPhase.DISCONNECTED)
            LanSyncBus.sessionIssue("안전한 최초 연결을 위해 학생 기기의 QR을 한 번 스캔해 주세요.")
            return
        }
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
        LanSyncBus.sessionRoleChanged(bookId, requireNotNull(role))
        documentHash = requireLanDocumentHash(targetBookId)
        require(isValidLanSha256(payload.token)) { "LAN pairing secret is invalid" }
        pairingToken = payload.token
        explicitPairingWindow = true
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
        val authVersion = info.attributes[ATTRIBUTE_AUTH_VERSION]?.toString(Charsets.UTF_8)?.toIntOrNull()
        val remoteBook = info.attributes[ATTRIBUTE_BOOK]?.toString(Charsets.UTF_8).orEmpty()
        val remoteHash = info.attributes[ATTRIBUTE_HASH]?.toString(Charsets.UTF_8).orEmpty()
        val remoteDevice = info.attributes[ATTRIBUTE_DEVICE]?.toString(Charsets.UTF_8).orEmpty()
        val pairingKey = pairingPreferenceKey(LanPeerRole.TEACHER_CLIENT, bookId)
        val expectedDevice = pairingPreferences.getString(pairingKey, null)
        val expectedBook = pairingPreferences.getString("$pairingKey:peerBook", null)
        if (host.isNotBlank() && authVersion == LAN_AUTH_VERSION && remoteHash == documentHash &&
            remoteDevice.isNotBlank() && remoteDevice == expectedDevice &&
            remoteBook.isNotBlank() && remoteBook == expectedBook && isValidLanSha256(pairingToken)
        ) {
            startTeacherSocket(host, info.port, remoteBook, pairingToken)
        }
    }

    private fun startTeacherSocket(host: String, port: Int, targetBookId: String, token: String) {
        if (socket?.isConnected == true) return
        reconnectPeerBookId = targetBookId
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

    @Synchronized
    private fun attachSocket(connected: Socket) {
        // Never let a late discovery/accept candidate evict the established classroom peer. A
        // candidate that has not finished HELLO also occupies only this slot; Telegram remains the
        // data owner because CONNECTED is not published until authentication succeeds.
        if (socket != null) {
            runCatching { connected.close() }
            return
        }
        clearPeerConnectionIdentity()
        socket = connected.apply { tcpNoDelay = true; keepAlive = true }
        writer = BufferedWriter(OutputStreamWriter(connected.getOutputStream(), Charsets.UTF_8))
        connectionEpoch.advance()
        localAuthNonce = newLanSecretHex()
        lastSubscriptionGeneration = -1L
        lastSubscriptionPage = -1
        lastTeacherRepairGeneration = -1L
        lastTeacherPublicationRepairGeneration = -1L
        LanSyncBus.connectionStateChanged(bookId, LanConnectionState.CONNECTING)
        LanSyncBus.sessionPhaseChanged(bookId, LanSessionPhase.CONNECTING)
        val helloSent = send(
            lanHelloMessage(
                deviceId = library.deviceId,
                role = requireNotNull(role),
                bookId = bookId,
                documentSha256 = documentHash,
                nonceHex = localAuthNonce,
            ),
            allowBeforeAuthentication = true,
        )
        if (!helloSent) {
            socket = null
            writer = null
            clearPeerConnectionIdentity()
            runCatching { connected.close() }
            error("LAN HELLO could not be sent")
        }
        updateNotification("연결 확인 중")
        Log.i(TAG, "LAN candidate attached role=$role book=$bookId generation=$connectionGeneration")
        val attachedGeneration = connectionGeneration
        lastPeerReceiveAtElapsedMs = SystemClock.elapsedRealtime()
        scheduleLanHeartbeat(connected, attachedGeneration)
        io.execute { readLoop(connected, attachedGeneration) }
    }

    private fun readLoop(connected: Socket, attachedGeneration: Long) {
        try {
            BufferedReader(InputStreamReader(connected.getInputStream(), Charsets.UTF_8)).use { reader ->
                while (!stopping.get()) {
                    if (socket !== connected || connectionGeneration != attachedGeneration) break
                    val line = readBoundedLanLine(reader, LanWire.MAX_LINE_CHARS) ?: break
                    // A replaced socket can still yield data that was already buffered in its
                    // reader. Never let that stale generation apply a review after the new session
                    // has taken ownership.
                    if (socket !== connected || connectionGeneration != attachedGeneration) break
                    val type = runCatching { JSONObject(line).optString("type") }.getOrDefault("")
                    val failure = runCatching {
                        handle(LanWire.decode(line), attachedGeneration)
                    }.exceptionOrNull()
                    if (failure == null) {
                        // Invalid/pre-authentication traffic cannot extend the candidate lifetime.
                        lastPeerReceiveAtElapsedMs = SystemClock.elapsedRealtime()
                        continue
                    }
                    Log.w(TAG, "LAN message failed role=$role type=$type", failure)
                    // Authentication and page-catch-up frames are stateful: after one is rejected,
                    // the same socket must not claim READY. Isolated noncritical frames may recover.
                    if (mustCloseLanConnectionAfterFailure(
                            type,
                            authenticatedConnectionGeneration == attachedGeneration,
                        )
                    ) {
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
                clearPeerConnectionIdentity()
                readyDeadlineAtElapsedMs = 0L
                lastSubscriptionGeneration = -1L
                lastSubscriptionPage = -1
                lastTeacherRepairGeneration = -1L
                lastTeacherPublicationRepairGeneration = -1L
                synchronized(incomingTeacherReviewChunks) { incomingTeacherReviewChunks.clear() }
                updateNotification("연결 끊김")
                Log.i(TAG, "LAN detached role=$role book=$bookId generation=$connectionGeneration")
                LanSyncBus.connectionStateChanged(bookId, LanConnectionState.DISCONNECTED)
                LanSyncBus.sessionPhaseChanged(bookId, LanSessionPhase.DISCONNECTED)
                scheduleReconnect()
            }
        }
    }

    private fun scheduleLanHeartbeat(connected: Socket, generation: Long) {
        handler.postDelayed({
            io.execute { runLanHeartbeat(connected, generation) }
        }, LAN_HEARTBEAT_INTERVAL_MS)
    }

    private fun runLanHeartbeat(connected: Socket, generation: Long) {
        if (stopping.get() || socket !== connected || connectionGeneration != generation) return
        val now = SystemClock.elapsedRealtime()
        val lastReceived = lastPeerReceiveAtElapsedMs
        if (authenticatedConnectionGeneration != generation) {
            if (lastReceived <= 0L || now < lastReceived ||
                now - lastReceived >= LAN_HANDSHAKE_TIMEOUT_MS
            ) {
                Log.w(TAG, "LAN handshake timed out book=$bookId generation=$generation")
                runCatching { connected.close() }
            } else {
                scheduleLanHeartbeat(connected, generation)
            }
            return
        }
        val readyDeadline = readyDeadlineAtElapsedMs
        if (isLanPageCatchUpExpired(readyDeadline, now)) {
            Log.w(TAG, "LAN page catch-up timed out book=$bookId generation=$generation")
            runCatching { connected.close() }
            return
        }
        if (lastReceived > 0L && now >= lastReceived &&
            now - lastReceived >= LAN_HEARTBEAT_TIMEOUT_MS
        ) {
            Log.w(TAG, "LAN heartbeat timed out book=$bookId generation=$generation")
            runCatching { connected.close() }
            return
        }
        if (send(LanWire.message("PING") { put("nonce", now) })) {
            scheduleLanHeartbeat(connected, generation)
        }
    }

    private fun scheduleReconnect() {
        if (stopping.get() || role != LanPeerRole.TEACHER_CLIENT || peerHost.isBlank() || peerPort <= 0) return
        handler.postDelayed({
            if (!stopping.get() && socket?.isConnected != true) {
                Log.i(TAG, "LAN reconnect attempt book=$bookId host=$peerHost port=$peerPort")
                startTeacherSocket(peerHost, peerPort, reconnectPeerBookId, pairingToken)
            }
        }, RECONNECT_DELAY_MILLIS)
    }

    private fun handle(message: JSONObject, attachedGeneration: Long) {
        require(connectionGeneration == attachedGeneration && socket != null) {
            "Message belongs to a stale LAN socket"
        }
        val type = message.getString("type")
        if (type != "HELLO" && type != "AUTH_PROOF") {
            require(authenticatedConnectionGeneration == attachedGeneration) {
                "LAN peer has not authenticated this connection"
            }
        }
        when (type) {
            "PING" -> send(LanWire.message("PONG") { put("nonce", message.optLong("nonce")) })
            "PONG" -> Unit
            "HELLO" -> {
                require(authenticatedConnectionGeneration != attachedGeneration &&
                    pendingPeerHelloGeneration != attachedGeneration
                ) {
                    "Duplicate LAN HELLO"
                }
                require(message.getInt("authVersion") == LAN_AUTH_VERSION) { "Unsupported LAN authentication" }
                val announcedHash = message.getString("documentHash")
                require(isValidLanSha256(documentHash) && announcedHash == documentHash) {
                    "Peer document does not match"
                }
                require(isValidLanSha256(pairingToken)) { "LAN shared secret is unavailable" }
                val announcedBookId = message.getString("bookId")
                val announcedDeviceId = message.getString("deviceId")
                val announcedNonce = message.getString("nonce")
                require(announcedBookId.isNotBlank() && announcedBookId.length <= MAX_AUTH_ID_CHARS &&
                    announcedDeviceId.isNotBlank() && announcedDeviceId.length <= MAX_AUTH_ID_CHARS &&
                    isValidLanSha256(announcedNonce)
                ) {
                    "Peer identity is missing"
                }
                val announcedRole = LanPeerRole.valueOf(message.getString("role"))
                val announcedCapabilities = message.optJSONArray("capabilities")
                peerSupportsGptExplanation = announcedCapabilities != null &&
                    (0 until announcedCapabilities.length()).any { index ->
                        announcedCapabilities.optString(index) == LAN_CAPABILITY_GPT_EXPLANATION_V2
                    }
                require(
                    role == LanPeerRole.STUDENT_SERVER && announcedRole == LanPeerRole.TEACHER_CLIENT ||
                    role == LanPeerRole.TEACHER_CLIENT && announcedRole == LanPeerRole.STUDENT_SERVER
                ) { "Peer role does not match this session" }
                val localRole = requireNotNull(role)
                val pairingKey = pairingPreferenceKey(localRole, bookId)
                val pairedDeviceId = pairingPreferences.getString(pairingKey, null)
                val pairedBookId = pairingPreferences.getString("$pairingKey:peerBook", null)
                val pairedHash = pairingPreferences.getString("$pairingKey:hash", null)
                val pairedSecret = pairingPreferences.getString("$pairingKey:secret", null)
                val isStoredV2Pair = pairingPreferences.getInt("$pairingKey:authVersion", 0) == LAN_AUTH_VERSION &&
                    pairedDeviceId == announcedDeviceId && pairedBookId == announcedBookId &&
                    pairedHash == documentHash && pairedSecret == pairingToken
                require(explicitPairingWindow || isStoredV2Pair) {
                    "Another device is already paired"
                }
                pendingPeerHelloGeneration = attachedGeneration
                pendingPeerNonce = announcedNonce
                pendingPeerBookId = announcedBookId
                pendingPeerDeviceId = announcedDeviceId
                pendingPeerRole = announcedRole
                val proof = lanAuthProofHex(
                    pairingToken,
                    localAuthNonce,
                    announcedNonce,
                    library.deviceId,
                    announcedDeviceId,
                    localRole,
                    announcedRole,
                    bookId,
                    announcedBookId,
                    documentHash,
                )
                require(send(LanWire.message("AUTH_PROOF") {
                    put("deviceId", library.deviceId)
                    put("proof", proof)
                }, allowBeforeAuthentication = true)) { "LAN authentication proof could not be sent" }
            }
            "AUTH_PROOF" -> {
                require(authenticatedConnectionGeneration != attachedGeneration &&
                    pendingPeerHelloGeneration == attachedGeneration
                ) { "LAN authentication proof has no matching HELLO" }
                val localRole = requireNotNull(role)
                val announcedRole = requireNotNull(pendingPeerRole)
                require(message.getString("deviceId") == pendingPeerDeviceId) {
                    "LAN authentication identity changed"
                }
                val expected = lanAuthProofHex(
                    pairingToken,
                    pendingPeerNonce,
                    localAuthNonce,
                    pendingPeerDeviceId,
                    library.deviceId,
                    announcedRole,
                    localRole,
                    pendingPeerBookId,
                    bookId,
                    documentHash,
                )
                require(lanAuthProofMatches(expected, message.getString("proof"))) {
                    "LAN authentication proof is invalid"
                }
                val pairingKey = pairingPreferenceKey(localRole, bookId)
                require(pairingPreferences.edit()
                    .putString(pairingKey, pendingPeerDeviceId)
                    .putString("$pairingKey:peerBook", pendingPeerBookId)
                    .putString("$pairingKey:hash", documentHash)
                    .putString("$pairingKey:secret", pairingToken)
                    .putInt("$pairingKey:authVersion", LAN_AUTH_VERSION)
                    .commit()
                ) { "LAN pairing record could not be committed" }

                // Only a verified proof and a durable pair record may take transport ownership.
                peerBookId = pendingPeerBookId
                peerDeviceId = pendingPeerDeviceId
                peerRole = announcedRole
                authenticatedConnectionGeneration = attachedGeneration
                explicitPairingWindow = false
                markPageCatchUpProgress()
                updateNotification("연결됨")
                Log.i(TAG, "LAN authenticated role=$role book=$bookId generation=$attachedGeneration")
                LanSyncBus.connectionStateChanged(bookId, LanConnectionState.CONNECTED)
                LanSyncBus.sessionPhaseChanged(bookId, LanSessionPhase.SOCKET_CONNECTED)
                LanSyncBus.sessionPhaseChanged(bookId, LanSessionPhase.HANDSHAKE_COMPLETE)
                send(LanWire.message("HELLO_OK"))
                sendMetadataSnapshot()
                if (role == LanPeerRole.STUDENT_SERVER) sendStudentPageState("hello")
                repairTeacherConnection()
                repairGptExplanationConnection()
            }
            "HELLO_OK" -> {
                LanSyncBus.sessionPhaseChanged(bookId, LanSessionPhase.HANDSHAKE_COMPLETE)
                repairTeacherConnection()
                repairGptExplanationConnection()
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
                markPageCatchUpProgress()
                // A teacher may enter Live Monitor after the original page event. Repeating the
                // state is safe because the teacher's subscription sender is connection-idempotent.
                sendStudentPageState("subscription")
                if (flushPage(subscribedPage)) {
                    if (pendingPage == subscribedPage) pendingPage = -1
                    sendPageSyncedAndPublishReady(subscribedPage, attachedGeneration)
                }
            }
            "OPERATION" -> {
                require(
                    role == LanPeerRole.TEACHER_CLIENT && peerRole == LanPeerRole.STUDENT_SERVER
                ) { "Only a student peer may publish live ink" }
                val page = message.getInt("page")
                require(isPageInBook(page)) { "Operation page is outside the book" }
                val bytes = Base64.decode(message.getString("payload"), Base64.NO_WRAP)
                val cursor = store.operationCursor(bytes)
                // Authentication proves the peer device, not asset ownership. Live operations are
                // student->teacher only, so validate that they cannot add/remove/reactivate any
                // teacher layer before writing the durable log.
                store.appendEncodedStudentOperation(bookId, page, bytes)
                markPageCatchUpProgress()
                LanSyncBus.remoteOperation(bookId, page)
                send(LanWire.message("ACK") {
                    put("page", page); put("deviceId", cursor.deviceId); put("logicalClock", cursor.logicalClock)
                })
            }
            "TEACHER_REVIEW_CHUNK" -> receiveTeacherReviewChunk(message)
            "GPT_EXPLANATION_LAYER" -> receiveGptExplanationLayer(message)
            "GPT_EXPLANATION_ACK" -> receiveGptExplanationAck(message)
            "TEACHER_REVIEW_ACK" -> {
                require(
                    role == LanPeerRole.TEACHER_CLIENT && peerRole == LanPeerRole.STUDENT_SERVER
                ) { "Only a student peer may acknowledge a teacher review" }
                val publication = LanTeacherReviewPublication(
                    bookId = bookId,
                    pageNumber = message.getInt("page"),
                    attemptNo = message.getInt("attemptNo"),
                    publicationId = message.getString("publicationId"),
                )
                require(isPageInBook(publication.pageNumber)) { "Teacher review ACK page is outside the book" }
                synchronized(pendingTeacherReviewAcks) {
                    pendingTeacherReviewAcks[publication.publicationId]
                        ?.takeIf { it.publication == publication }
                        ?.let { pendingTeacherReviewAcks.remove(publication.publicationId) }
                }
                LanSyncBus.teacherReviewAcknowledged(publication)
            }
            "ACK" -> {
                require(
                    role == LanPeerRole.STUDENT_SERVER && peerRole == LanPeerRole.TEACHER_CLIENT
                ) { "Only a teacher peer may acknowledge student ink" }
                if (message.getInt("page") == subscribedPage) {
                    if (message.getString("deviceId") == library.deviceId) {
                        peerReceivedClocks.acknowledge(
                            pageNumber = message.getInt("page"),
                            deviceId = library.deviceId,
                            logicalClock = message.getLong("logicalClock"),
                        )
                        markPageCatchUpProgress()
                    }
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
                    publishReadyIfCurrent(attachedGeneration)
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
                require(isLegacyLanMarkGroup(group)) {
                    "Student-attempt grades require an exact published teacher review"
                }
                if (library.upsertMarkGroupFromSync(bookId, page, group)) {
                    LanSyncBus.remoteMarkGroup(bookId, page)
                }
            }
            else -> throw IllegalArgumentException("Unknown LAN message type: $type")
        }
    }

    override fun onLocalAttemptChanged(attempt: Attempt) {
        if (role != LanPeerRole.STUDENT_SERVER || attempt.bookId != bookId) return
        enqueueAttempt(attempt)
    }

    override fun onLocalMarkGroupChanged(group: MarkGroup) {
        if (role == null || group.bookId != bookId || !isLegacyLanMarkGroup(group)) return
        enqueueMarkGroup(group)
    }

    override fun onLocalOperation(bookId: String, pageNumber: Int) {
        // Arrives on the reader's mutation dispatcher. Hop onto the thread that owns the flush
        // timer so the pending-page bookkeeping is never interleaved with the socket reader.
        handler.post { scheduleLocalFlush(bookId, pageNumber) }
    }

    override fun onCatchUpYieldRequested(bookId: String) {
        val request = synchronized(this) {
            if (this.bookId != bookId ||
                LanSyncBus.connectionState(bookId) != LanConnectionState.CONNECTED ||
                LanSyncBus.sessionPhase(bookId) == LanSessionPhase.READY
            ) return
            val target = socket ?: return
            val generation = connectionGeneration
            if (catchUpYieldRequestedGeneration == generation) return
            // This flag and both READY publications share this service monitor. Whichever commits
            // first wins: READY rejects the yield, while a committed yield forbids phantom READY.
            catchUpYieldRequestedGeneration = generation
            target to generation
        }
        val (target, generation) = request
        io.execute {
            val stillUnready = synchronized(this) {
                this.bookId == bookId && socket === target && connectionGeneration == generation &&
                    catchUpYieldRequestedGeneration == generation &&
                    LanSyncBus.connectionState(bookId) == LanConnectionState.CONNECTED &&
                    LanSyncBus.sessionPhase(bookId) != LanSessionPhase.READY
            }
            if (stillUnready) {
                // readLoop.finally publishes DISCONNECTED only after any frame already being
                // handled has completed. That publication, not this request, transfers ownership.
                runCatching { target.close() }
            }
        }
    }

    override fun onLocalTeacherReviewPublished(publication: LanTeacherReviewPublication) {
        if (role != LanPeerRole.TEACHER_CLIENT || bookId != publication.bookId) return
        teacherReviewIo.execute { queueTeacherReviewPublication(publication) }
    }

    override fun onLocalGptExplanationLayerPublished(layer: StudentExplanationLayer) {
        if (role != LanPeerRole.TEACHER_CLIENT || bookId != layer.target.page.bookId) return
        teacherReviewIo.execute { queueGptExplanationLayer(layer) }
    }

    private fun scheduleLocalFlush(bookId: String, pageNumber: Int) {
        if (this.bookId != bookId || pageNumber != subscribedPage) return
        if (role == LanPeerRole.TEACHER_CLIENT) return
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
        if (lastTeacherPublicationRepairGeneration != connectionGeneration) {
            lastTeacherPublicationRepairGeneration = connectionGeneration
            store.teacherReviewPublishIntents()
                .filter { it.bookId == bookId && it.publicationId.isNotEmpty() }
                .forEach { intent ->
                    teacherReviewIo.execute {
                        queueTeacherReviewPublication(
                            LanTeacherReviewPublication(
                                intent.bookId,
                                intent.pageNumber,
                                intent.attemptNo,
                                intent.publicationId,
                            ),
                        )
                    }
                }
        }
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
        markPageCatchUpProgress()
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
            markPageCatchUpProgress()
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

    /** Atomically chooses READY over a concurrent catch-up yield, including the peer notification. */
    @Synchronized
    private fun sendPageSyncedAndPublishReady(page: Int, attachedGeneration: Long): Boolean {
        if (!canPublishLanReady(attachedGeneration, connectionGeneration, catchUpYieldRequestedGeneration) ||
            socket == null
        ) return false
        if (!sendPageSynced(page)) return false
        return publishReadyIfCurrent(attachedGeneration)
    }

    /** Both READY receive paths use the same monitor as [onCatchUpYieldRequested]. */
    @Synchronized
    private fun publishReadyIfCurrent(attachedGeneration: Long): Boolean {
        if (!canPublishLanReady(attachedGeneration, connectionGeneration, catchUpYieldRequestedGeneration) ||
            socket == null
        ) return false
        readyDeadlineAtElapsedMs = 0L
        LanSyncBus.sessionPhaseChanged(bookId, LanSessionPhase.READY)
        return true
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
            library.markGroupsForSync(expectedBookId)
                .filter(::isLegacyLanMarkGroup)
                .forEach(::sendMarkGroupNow)
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

    private fun queueGptExplanationLayer(eventLayer: StudentExplanationLayer) {
        if (role != LanPeerRole.TEACHER_CLIENT || eventLayer.target.page.bookId != bookId ||
            !isPageInBook(eventLayer.target.page.pageNumber)
        ) return
        val publication = assistantRepository.pendingStudentExplanationPublications().firstOrNull {
            it.target == eventLayer.target && it.revision == eventLayer.revision &&
                it.digestSha256 == eventLayer.digestSha256
        } ?: return
        val authorityEpoch = runCatching { assistantRepository.teacherAuthorityEpoch() }
            .getOrNull() ?: return
        val checkpoint = runCatching {
            assistantRepository.exportPendingStudentExplanationPublication(
                publication.publicationId,
                authorityEpoch,
            )
        }.getOrNull() ?: return
        if (checkpoint.isEmpty() || checkpoint.size > MAX_GPT_EXPLANATION_PAYLOAD_BYTES) return
        val frozen = runCatching {
            assistantRepository.decodeStudentExplanationLayer(checkpoint)
        }.getOrNull() ?: return
        if (frozen.target != eventLayer.target || frozen.revision < eventLayer.revision) return
        val exactAttempt = library.attempts(bookId, frozen.target.page.pageNumber).any {
            it.bookId == bookId && it.pageNumber == frozen.target.page.pageNumber &&
                it.attemptNo == frozen.target.attemptNo
        }
        if (!exactAttempt) return
        val pending = PendingLanGptExplanationAck(
            publicationId = publication.publicationId,
            layer = frozen,
            checkpointBytes = checkpoint.copyOf(),
            payloadSha256 = sha256Hex(checkpoint),
        )
        synchronized(pendingGptExplanationAcks) {
            pendingGptExplanationAcks.entries.removeAll { (_, prior) ->
                prior.layer.target == frozen.target && prior.publicationId != publication.publicationId
            }
            pendingGptExplanationAcks[publication.publicationId] = pending
        }
        val generation = connectionGeneration
        if (sendGptExplanationLayer(pending, generation)) {
            scheduleGptExplanationRetry(publication.publicationId, generation)
        }
    }

    private fun sendGptExplanationLayer(
        pending: PendingLanGptExplanationAck,
        expectedGeneration: Long,
    ): Boolean {
        val layer = pending.layer
        if (role != LanPeerRole.TEACHER_CLIENT || writer == null ||
            authenticatedConnectionGeneration != expectedGeneration ||
            connectionGeneration != expectedGeneration || !peerSupportsGptExplanation ||
            layer.target.page.bookId != bookId || !isPageInBook(layer.target.page.pageNumber)
        ) return false
        return send(LanWire.message("GPT_EXPLANATION_LAYER") {
            put("publicationId", pending.publicationId)
            put("page", layer.target.page.pageNumber)
            put("attemptNo", layer.target.attemptNo)
            put("revision", layer.revision)
            put("sourceDigestSha256", layer.digestSha256)
            put("payloadSha256", pending.payloadSha256)
            put("payloadSize", pending.checkpointBytes.size)
            put("payload", Base64.encodeToString(pending.checkpointBytes, Base64.NO_WRAP))
        })
    }

    private fun scheduleGptExplanationRetry(publicationId: String, generation: Long) {
        teacherReviewIo.schedule({
            val pending = synchronized(pendingGptExplanationAcks) {
                pendingGptExplanationAcks[publicationId]
            } ?: return@schedule
            if (connectionGeneration != generation || role != LanPeerRole.TEACHER_CLIENT ||
                pending.layer.target.page.bookId != bookId
            ) return@schedule
            if (sendGptExplanationLayer(pending, generation)) {
                scheduleGptExplanationRetry(publicationId, generation)
            }
        }, LAN_GPT_EXPLANATION_RETRY_MS, TimeUnit.MILLISECONDS)
    }

    private fun repairGptExplanationConnection() {
        if (role != LanPeerRole.TEACHER_CLIENT || !peerSupportsGptExplanation || writer == null) return
        val generation = connectionGeneration
        teacherReviewIo.execute {
            synchronized(pendingGptExplanationAcks) {
                pendingGptExplanationAcks.values.toList()
            }.forEach { pending ->
                if (sendGptExplanationLayer(pending, generation)) {
                    scheduleGptExplanationRetry(pending.publicationId, generation)
                }
            }
        }
    }

    private fun receiveGptExplanationLayer(message: JSONObject) {
        require(
            role == LanPeerRole.STUDENT_SERVER && peerRole == LanPeerRole.TEACHER_CLIENT &&
                peerSupportsGptExplanation
        ) { "Only a capable teacher peer may publish GPT explanations" }
        val publicationId = message.getString("publicationId")
        val page = message.getInt("page")
        val attemptNo = message.getInt("attemptNo")
        val revision = message.getLong("revision")
        val sourceDigest = message.getString("sourceDigestSha256")
        val payloadSha256 = message.getString("payloadSha256")
        val payloadSize = message.getInt("payloadSize")
        require(publicationId.matches(SHA256_HEX) && sourceDigest.matches(SHA256_HEX) &&
            payloadSha256.matches(SHA256_HEX) && isPageInBook(page) && attemptNo > 0 && revision > 0L
        )
        require(payloadSize in 1..MAX_GPT_EXPLANATION_PAYLOAD_BYTES)
        val payload = Base64.decode(message.getString("payload"), Base64.NO_WRAP)
        require(payload.size == payloadSize && sha256Hex(payload) == payloadSha256)
        val sourceLayer = assistantRepository.decodeStudentExplanationLayer(payload)
        require(
            sourceLayer.target.page.pageNumber == page &&
                sourceLayer.target.attemptNo == attemptNo && sourceLayer.revision == revision &&
                sourceLayer.digestSha256 == sourceDigest && sourceLayer.authorityEpoch.matches(SHA256_HEX)
        ) { "GPT explanation checkpoint identity mismatch" }
        if (!isExactLanTeacherReviewAttempt(
                attempts = library.attempts(bookId, page),
                bookId = bookId,
                pageNumber = page,
                attemptNo = attemptNo,
            )
        ) {
            // Attempt metadata can legitimately arrive just after this layer. Keep the sender's
            // durable intent unresolved and let its bounded retry apply once the exact attempt exists.
            return
        }
        val target = StudentExplanationTarget(AssistantPageKey(bookId, page), attemptNo)
        val localLayer = sourceLayer.remapTo(target)
        val result = assistantRepository.applyStudentExplanationLayer(target, localLayer)
        when (result.status) {
            StudentLayerApplyStatus.APPLIED -> {
                StudentExplanationLayerBus.remoteLayerApplied(result.current)
                LanSyncBus.remoteGptExplanationLayerApplied(result.current)
            }
            StudentLayerApplyStatus.CONFLICT -> {
                // Same authority and revision with different content is corruption. Do not let a
                // success ACK erase the teacher's durable publication journal.
                return
            }
            StudentLayerApplyStatus.ALREADY_CURRENT,
            StudentLayerApplyStatus.STALE,
            -> Unit
        }
        send(LanWire.message("GPT_EXPLANATION_ACK") {
            put("publicationId", publicationId)
            put("page", page)
            put("attemptNo", attemptNo)
            put("revision", revision)
            put("sourceDigestSha256", sourceDigest)
            put("authorityEpoch", sourceLayer.authorityEpoch)
        })
    }

    private fun receiveGptExplanationAck(message: JSONObject) {
        require(
            role == LanPeerRole.TEACHER_CLIENT && peerRole == LanPeerRole.STUDENT_SERVER
        ) { "Only a student peer may acknowledge GPT explanations" }
        val publicationId = message.getString("publicationId")
        require(publicationId.matches(SHA256_HEX))
        val page = message.getInt("page")
        val attemptNo = message.getInt("attemptNo")
        val revision = message.getLong("revision")
        val sourceDigest = message.getString("sourceDigestSha256")
        val authorityEpoch = message.getString("authorityEpoch")
        require(sourceDigest.matches(SHA256_HEX) && authorityEpoch.matches(SHA256_HEX))
        synchronized(pendingGptExplanationAcks) {
            pendingGptExplanationAcks[publicationId]?.takeIf { pending ->
                isExactLanGptExplanationAck(
                    expectedPublicationId = pending.publicationId,
                    expectedPageNumber = pending.layer.target.page.pageNumber,
                    expectedAttemptNo = pending.layer.target.attemptNo,
                    expectedRevision = pending.layer.revision,
                    expectedDigestSha256 = pending.layer.digestSha256,
                    expectedAuthorityEpoch = pending.layer.authorityEpoch,
                    publicationId = publicationId,
                    pageNumber = page,
                    attemptNo = attemptNo,
                    revision = revision,
                    digestSha256 = sourceDigest,
                    authorityEpoch = authorityEpoch,
                )
            }?.let { pending ->
                assistantRepository.resolvePendingStudentExplanationPublication(
                    publicationId = publicationId,
                    target = pending.layer.target,
                    revision = pending.layer.revision,
                    digestSha256 = pending.layer.digestSha256,
                )
                pendingGptExplanationAcks.remove(publicationId)
            }
        }
    }

    private fun sendTeacherReviewPublication(publication: LanTeacherReviewPublication): Boolean {
        if (
            role != LanPeerRole.TEACHER_CLIENT || writer == null ||
            publication.bookId != bookId || !isPageInBook(publication.pageNumber)
        ) return false
        val expectedGeneration = connectionGeneration
        val artifact = store.teacherReviewPublicationArtifact(
            publication.bookId,
            publication.pageNumber,
            publication.attemptNo,
            publication.publicationId,
        ) ?: return false
        val payload = encodeLanTeacherReviewPayload(
            artifact.copyCheckpointBytes(),
            artifact.markGroups,
        )
        val payloadSha = sha256Hex(payload)
        val chunks = splitLanTeacherReviewPayload(payload, TEACHER_REVIEW_CHUNK_BYTES)
        if (chunks.isEmpty() || chunks.size > MAX_TEACHER_REVIEW_CHUNKS) return false
        return chunks.indices.all { index ->
            if (connectionGeneration != expectedGeneration || writer == null) return@all false
            send(LanWire.message("TEACHER_REVIEW_CHUNK") {
                put("publicationId", publication.publicationId)
                put("page", publication.pageNumber)
                put("attemptNo", publication.attemptNo)
                put("resultLayerSha256", artifact.intent.resultLayerSha256)
                put("payloadSha256", payloadSha)
                put("payloadSize", payload.size)
                put("chunkIndex", index)
                put("chunkCount", chunks.size)
                put("payload", Base64.encodeToString(chunks[index], Base64.NO_WRAP))
            })
        }
    }

    private fun queueTeacherReviewPublication(publication: LanTeacherReviewPublication) {
        val generation = connectionGeneration
        if (role != LanPeerRole.TEACHER_CLIENT || writer == null || publication.bookId != bookId) return
        val alreadyQueued = synchronized(pendingTeacherReviewAcks) {
            // TCP preserves send order and this executor is serial. Once a newer explicit publish
            // for the same exact target is queued, an older ACK-loss retry must never run after it
            // and roll the student's layer/grade back.
            pendingTeacherReviewAcks.entries.removeAll { (_, pending) ->
                pending.publication.publicationId != publication.publicationId &&
                    pending.publication.bookId == publication.bookId &&
                    pending.publication.pageNumber == publication.pageNumber &&
                    pending.publication.attemptNo == publication.attemptNo
            }
            pendingTeacherReviewAcks[publication.publicationId]
                ?.takeIf { it.connectionGeneration == generation && it.publication == publication } != null
        }
        if (alreadyQueued) return
        if (!sendTeacherReviewPublication(publication)) return
        synchronized(pendingTeacherReviewAcks) {
            pendingTeacherReviewAcks[publication.publicationId] = PendingLanTeacherReviewAck(
                publication,
                generation,
            )
        }
        scheduleTeacherReviewRetry(publication.publicationId, generation)
    }

    private fun scheduleTeacherReviewRetry(publicationId: String, generation: Long) {
        teacherReviewIo.schedule({
            val pending = synchronized(pendingTeacherReviewAcks) {
                pendingTeacherReviewAcks[publicationId]?.takeIf {
                    it.connectionGeneration == generation
                }
            } ?: return@schedule
            if (connectionGeneration != generation || role != LanPeerRole.TEACHER_CLIENT ||
                writer == null || bookId != pending.publication.bookId ||
                store.teacherReviewPublicationArtifact(
                    pending.publication.bookId,
                    pending.publication.pageNumber,
                    pending.publication.attemptNo,
                    pending.publication.publicationId,
                ) == null
            ) {
                synchronized(pendingTeacherReviewAcks) {
                    pendingTeacherReviewAcks.remove(publicationId)
                }
                return@schedule
            }
            // Checkpoint and exact-attempt grade merge are idempotent. Missing ACK, late attempt
            // metadata, or a receiver-side transient write error is therefore repaired in-place.
            sendTeacherReviewPublication(pending.publication)
            scheduleTeacherReviewRetry(publicationId, generation)
        }, LAN_TEACHER_REVIEW_RETRY_MS, TimeUnit.MILLISECONDS)
    }

    private fun receiveTeacherReviewChunk(message: JSONObject) {
        require(
            role == LanPeerRole.STUDENT_SERVER && peerRole == LanPeerRole.TEACHER_CLIENT
        ) { "Only a teacher peer may publish a teacher review" }
        val publicationId = message.getString("publicationId")
        val page = message.getInt("page")
        val attemptNo = message.getInt("attemptNo")
        val resultLayerSha256 = message.getString("resultLayerSha256")
        val payloadSha256 = message.getString("payloadSha256")
        val payloadSize = message.getInt("payloadSize")
        val chunkIndex = message.getInt("chunkIndex")
        val chunkCount = message.getInt("chunkCount")
        require(publicationId.matches(SHA256_HEX) && resultLayerSha256.matches(SHA256_HEX))
        require(payloadSha256.matches(SHA256_HEX) && isPageInBook(page) && attemptNo > 0)
        require(payloadSize in 1..MAX_TEACHER_REVIEW_PAYLOAD_BYTES)
        require(chunkCount in 1..MAX_TEACHER_REVIEW_CHUNKS && chunkIndex in 0 until chunkCount)
        val chunk = Base64.decode(message.getString("payload"), Base64.NO_WRAP)
        require(chunk.isNotEmpty() && chunk.size <= TEACHER_REVIEW_CHUNK_BYTES)
        val completed = synchronized(incomingTeacherReviewChunks) {
            if (chunkIndex == 0 && incomingTeacherReviewChunks.size >= MAX_INCOMING_TEACHER_REVIEWS) {
                incomingTeacherReviewChunks.remove(incomingTeacherReviewChunks.keys.first())
            }
            val current = incomingTeacherReviewChunks[publicationId]
            require(current != null || chunkIndex == 0) { "Teacher review chunks must start at zero" }
            val compatible = current?.takeIf {
                it.pageNumber == page && it.attemptNo == attemptNo &&
                    it.resultLayerSha256 == resultLayerSha256 &&
                    it.payloadSha256 == payloadSha256 && it.payloadSize == payloadSize &&
                    it.chunks.size == chunkCount
            }
            val assembly = compatible ?: IncomingTeacherReviewChunks(
                page,
                attemptNo,
                resultLayerSha256,
                payloadSha256,
                payloadSize,
                arrayOfNulls(chunkCount),
            ).also { incomingTeacherReviewChunks[publicationId] = it }
            assembly.chunks[chunkIndex] = chunk.copyOf()
            if (assembly.chunks.any { it == null }) null else {
                incomingTeacherReviewChunks.remove(publicationId)
                assembly
            }
        } ?: return
        val payload = completed.chunks.filterNotNull().fold(ByteArray(0)) { accumulated, part ->
            accumulated + part
        }
        require(payload.size == completed.payloadSize && sha256Hex(payload) == completed.payloadSha256)
        val decoded = decodeLanTeacherReviewPayload(payload, bookId, page)
        require(
            isExactLanTeacherReviewAttempt(
                attempts = library.attempts(bookId, page),
                bookId = bookId,
                pageNumber = page,
                attemptNo = attemptNo,
            ),
        ) { "Teacher review attempt does not exist" }
        val applied = store.applyPublishedTeacherLayerCheckpoint(
            localBookId = bookId,
            pageNumber = page,
            attemptNo = attemptNo,
            checkpointBytes = decoded.checkpointBytes,
            expectedResultLayerSha256 = completed.resultLayerSha256,
        )
        require(applied.layerSha256 == completed.resultLayerSha256)
        library.upsertMarkGroupAttemptsFromSync(
            bookId = bookId,
            pageNumber = page,
            attemptNo = attemptNo,
            incoming = decoded.markGroups,
        )
        LanSyncBus.remoteOperation(bookId, page)
        send(LanWire.message("TEACHER_REVIEW_ACK") {
            put("publicationId", publicationId)
            put("page", page)
            put("attemptNo", attemptNo)
        })
    }

    private fun encodeLanTeacherReviewPayload(
        checkpointBytes: ByteArray,
        markGroups: List<MarkGroup>,
    ): ByteArray {
        val marksBytes = JSONArray().apply {
            markGroups.forEach { put(MarkGroupWireCodec.encode(it)) }
        }.toString().toByteArray(Charsets.UTF_8)
        require(
            checkpointBytes.size <= PageOperationLogStore.MAX_PUBLISHED_TEACHER_LAYER_CHECKPOINT_BYTES &&
                marksBytes.size <= PageOperationLogStore.MAX_TEACHER_REVIEW_MARK_GROUP_BYTES
        )
        return ByteBuffer.allocate(Int.SIZE_BYTES * 2 + checkpointBytes.size + marksBytes.size)
            .putInt(checkpointBytes.size)
            .put(checkpointBytes)
            .putInt(marksBytes.size)
            .put(marksBytes)
            .array()
    }

    private fun decodeLanTeacherReviewPayload(
        payload: ByteArray,
        localBookId: String,
        pageNumber: Int,
    ): DecodedLanTeacherReviewPayload {
        require(payload.size in (Int.SIZE_BYTES * 2 + 1)..MAX_TEACHER_REVIEW_PAYLOAD_BYTES)
        val buffer = ByteBuffer.wrap(payload)
        val checkpointSize = buffer.int
        require(checkpointSize in 1..PageOperationLogStore.MAX_PUBLISHED_TEACHER_LAYER_CHECKPOINT_BYTES)
        require(buffer.remaining() >= checkpointSize + Int.SIZE_BYTES)
        val checkpoint = ByteArray(checkpointSize).also(buffer::get)
        val marksSize = buffer.int
        require(marksSize in 1..PageOperationLogStore.MAX_TEACHER_REVIEW_MARK_GROUP_BYTES)
        require(buffer.remaining() == marksSize)
        val marksJson = ByteArray(marksSize).also(buffer::get).toString(Charsets.UTF_8)
        val values = JSONArray(marksJson)
        val groups = buildList(values.length()) {
            for (index in 0 until values.length()) {
                add(MarkGroupWireCodec.decode(values.getJSONObject(index), localBookId, pageNumber))
            }
        }
        return DecodedLanTeacherReviewPayload(checkpoint, groups)
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun flushPage(page: Int): Boolean {
        if (page != subscribedPage || writer == null || !isPageInBook(page)) return false
        if (role == LanPeerRole.TEACHER_CLIENT) return true
        return runCatching {
            val acknowledgedClock = peerReceivedClocks.clock(page, library.deviceId)
            val records = store.encodedStudentOperationsAfter(
                bookId = bookId,
                pageNumber = page,
                originDeviceId = library.deviceId,
                logicalClock = acknowledgedClock,
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
    private fun send(line: String, allowBeforeAuthentication: Boolean = false): Boolean {
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
        val targetSocket = socket ?: return false
        val targetGeneration = connectionGeneration
        if (!allowBeforeAuthentication && authenticatedConnectionGeneration != targetGeneration) {
            return false
        }
        return runCatching {
            check(socket === targetSocket && writer === target)
            check(allowBeforeAuthentication || authenticatedConnectionGeneration == targetGeneration)
            target.write(line)
            target.newLine()
            target.flush()
            true
        }.onFailure {
            Log.w(TAG, "LAN write failed role=$role book=$bookId", it)
            // A half-open TCP connection may never wake readLine with EOF. Closing the exact socket
            // whose writer failed guarantees readLoop publishes DISCONNECTED, allowing Telegram to
            // take over instead of leaving the process stuck in READY forever.
            if (writer === target && socket === targetSocket) {
                runCatching { targetSocket.close() }
            }
        }.getOrDefault(false)
    }

    private fun requireLanDocumentHash(targetBookId: String): String =
        library.book(targetBookId).contentSha256.lowercase().also { digest ->
            require(isValidLanSha256(digest)) {
                "LAN sync requires a verified PDF SHA-256 digest"
            }
        }

    private fun pairingPreferenceKey(localRole: LanPeerRole, localBookId: String): String =
        "${localRole.name}:$localBookId"

    private fun storedPairingSecret(
        localRole: LanPeerRole,
        localBookId: String,
        expectedDocumentHash: String,
    ): String? {
        val key = pairingPreferenceKey(localRole, localBookId)
        if (pairingPreferences.getInt("$key:authVersion", 0) != LAN_AUTH_VERSION) return null
        if (pairingPreferences.getString("$key:hash", null) != expectedDocumentHash) return null
        if (pairingPreferences.getString(key, null).isNullOrBlank() ||
            pairingPreferences.getString("$key:peerBook", null).isNullOrBlank()
        ) return null
        return pairingPreferences.getString("$key:secret", null)?.takeIf(::isValidLanSha256)
    }

    private fun loadOrCreateStudentPairingSecret(
        localBookId: String,
        expectedDocumentHash: String,
    ): String {
        val key = pairingPreferenceKey(LanPeerRole.STUDENT_SERVER, localBookId)
        pairingPreferences.getString("$key:secret", null)
            ?.takeIf(::isValidLanSha256)
            ?.takeIf { pairingPreferences.getString("$key:hash", null) == expectedDocumentHash }
            ?.let { return it }
        val secret = newLanSecretHex()
        require(pairingPreferences.edit()
            .putString("$key:secret", secret)
            .putString("$key:hash", expectedDocumentHash)
            .remove("$key:authVersion")
            .commit()
        ) { "LAN pairing secret could not be committed" }
        return secret
    }

    private fun registerService(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = "MasterNote-${library.deviceId.take(6)}"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute(ATTRIBUTE_AUTH_VERSION, LAN_AUTH_VERSION.toString())
            setAttribute(ATTRIBUTE_DEVICE, library.deviceId)
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
        explicitPairingWindow = false
        peerBookId = ""
        reconnectPeerBookId = ""
        peerRole = null
        peerSupportsGptExplanation = false
        authenticatedConnectionGeneration = 0L
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
        lastPeerReceiveAtElapsedMs = 0L
        readyDeadlineAtElapsedMs = 0L
        catchUpYieldRequestedGeneration = -1L
        lastSubscriptionGeneration = -1L
        lastSubscriptionPage = -1
        lastTeacherRepairGeneration = -1L
        lastTeacherPublicationRepairGeneration = -1L
        synchronized(incomingTeacherReviewChunks) { incomingTeacherReviewChunks.clear() }
        synchronized(pendingTeacherReviewAcks) { pendingTeacherReviewAcks.clear() }
        synchronized(pendingGptExplanationAcks) { pendingGptExplanationAcks.clear() }
        peerReceivedClocks.clear()
    }

    private fun clearPeerConnectionIdentity() {
        authenticatedConnectionGeneration = 0L
        peerBookId = ""
        peerRole = null
        peerSupportsGptExplanation = false
        peerDeviceId = ""
        localAuthNonce = ""
        pendingPeerHelloGeneration = 0L
        pendingPeerNonce = ""
        pendingPeerBookId = ""
        pendingPeerDeviceId = ""
        pendingPeerRole = null
    }

    /** PING/PONG deliberately do not call this; only actual page catch-up work extends the lease. */
    private fun markPageCatchUpProgress() {
        if (authenticatedConnectionGeneration != connectionGeneration) return
        readyDeadlineAtElapsedMs = SystemClock.elapsedRealtime() + LAN_READY_TIMEOUT_MS
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
        teacherReviewIo.shutdownNow()
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
                if (wifiNetwork == network) {
                    wifiNetwork = null
                    val connected = socket
                    if (connected != null) {
                        io.execute {
                            if (socket === connected) runCatching { connected.close() }
                        }
                    }
                }
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
        private const val ATTRIBUTE_AUTH_VERSION = "auth"
        private const val ATTRIBUTE_DEVICE = "device"
        private const val ATTRIBUTE_BOOK = "book"
        private const val ATTRIBUTE_HASH = "hash"
        private const val MAX_AUTH_ID_CHARS = 512
        private const val CHANNEL_ID = "remote-class"
        private const val NOTIFICATION_ID = 4201
        private const val DEBOUNCE_MILLIS = 180L
        private const val MAX_DELAY_MILLIS = 600L
        private const val RECONNECT_DELAY_MILLIS = 2_000L
        // A LAN peer answers in milliseconds. Waiting out the platform default just stalls retries.
        private const val CONNECT_TIMEOUT_MILLIS = 5_000
        private const val LAN_HEARTBEAT_INTERVAL_MS = 2_000L
        private const val LAN_HEARTBEAT_TIMEOUT_MS = 8_000L
        private const val LAN_HANDSHAKE_TIMEOUT_MS = 5_000L
        private const val LAN_READY_TIMEOUT_MS = 30_000L
        private const val LAN_TEACHER_REVIEW_RETRY_MS = 30_000L
        private const val LAN_GPT_EXPLANATION_RETRY_MS = 30_000L
        private const val MAX_GPT_EXPLANATION_PAYLOAD_BYTES = AssistantPublicationLimits.MAX_CHECKPOINT_BYTES
        private const val TEACHER_REVIEW_CHUNK_BYTES = 384 * 1024
        private const val MAX_TEACHER_REVIEW_CHUNKS = 8
        private const val MAX_INCOMING_TEACHER_REVIEWS = 4
        private const val MAX_TEACHER_REVIEW_PAYLOAD_BYTES =
            PageOperationLogStore.MAX_PUBLISHED_TEACHER_LAYER_CHECKPOINT_BYTES +
                PageOperationLogStore.MAX_TEACHER_REVIEW_MARK_GROUP_BYTES + Int.SIZE_BYTES * 2
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

/** Splits a review without boxing every byte into a heap-heavy `List<Byte>`. */
internal fun splitLanTeacherReviewPayload(payload: ByteArray, maxChunkBytes: Int): List<ByteArray> {
    require(maxChunkBytes > 0)
    if (payload.isEmpty()) return emptyList()
    val chunkCount = ((payload.size.toLong() + maxChunkBytes - 1L) / maxChunkBytes).toInt()
    return ArrayList<ByteArray>(chunkCount).apply {
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(payload.size, offset + maxChunkBytes)
            add(payload.copyOfRange(offset, end))
            offset = end
        }
    }
}

/** Submission locks editing; it is not part of the identity of an explicitly published review. */
internal fun isExactLanTeacherReviewAttempt(
    attempts: List<Attempt>,
    bookId: String,
    pageNumber: Int,
    attemptNo: Int,
): Boolean = attemptNo > 0 && attempts.any { attempt ->
    attempt.bookId == bookId && attempt.pageNumber == pageNumber && attempt.attemptNo == attemptNo
}

/** Attempt grades are released only by the atomic TEACHER_REVIEW_CHUNK protocol. */
internal fun isLegacyLanMarkGroup(group: MarkGroup): Boolean =
    group.marks.none { it.attemptNo > 0 }

/**
 * A scheduled retry from a closed socket carries its epoch. There is intentionally no reset API:
 * reusing an epoch during the same service lifetime could make that old retry match a new socket.
 */
internal class MonotonicLanConnectionEpoch {
    @Volatile
    private var generation = 0L

    val current: Long get() = generation

    @Synchronized
    fun advance(): Long {
        check(generation < Long.MAX_VALUE) { "LAN connection epoch exhausted" }
        generation += 1L
        return generation
    }
}

internal fun canPublishLanReady(
    attachedGeneration: Long,
    currentGeneration: Long,
    catchUpYieldRequestedGeneration: Long,
): Boolean = attachedGeneration == currentGeneration &&
    catchUpYieldRequestedGeneration != attachedGeneration

private data class PendingLanTeacherReviewAck(
    val publication: LanTeacherReviewPublication,
    val connectionGeneration: Long,
)

private data class PendingLanGptExplanationAck(
    val publicationId: String,
    val layer: StudentExplanationLayer,
    val checkpointBytes: ByteArray,
    val payloadSha256: String,
)

private data class IncomingTeacherReviewChunks(
    val pageNumber: Int,
    val attemptNo: Int,
    val resultLayerSha256: String,
    val payloadSha256: String,
    val payloadSize: Int,
    val chunks: Array<ByteArray?>,
)

private data class DecodedLanTeacherReviewPayload(
    val checkpointBytes: ByteArray,
    val markGroups: List<MarkGroup>,
)

private val SHA256_HEX = Regex("[0-9a-f]{64}")

/**
 * Equivalent to [BufferedReader.readLine] for the protocol's LF/CRLF frames, except it refuses an
 * oversized frame before allocating beyond the wire limit. This also bounds a pre-HELLO peer that
 * streams data without a newline; the heartbeat closes that candidate while Telegram stays active.
 */
internal fun readBoundedLanLine(reader: BufferedReader, maxChars: Int): String? {
    require(maxChars > 0)
    val result = StringBuilder(minOf(maxChars, 8 * 1024))
    while (true) {
        val value = reader.read()
        if (value < 0) {
            if (result.isEmpty()) return null
            return result.toString()
        }
        when (value) {
            '\n'.code -> return result.toString()
            '\r'.code -> {
                reader.mark(1)
                val following = reader.read()
                if (following >= 0 && following != '\n'.code) reader.reset()
                return result.toString()
            }
        }
        require(result.length < maxChars) { "LAN frame exceeds $maxChars characters" }
        result.append(value.toChar())
    }
}

internal fun isLanPageCatchUpExpired(deadlineAtElapsedMs: Long, nowElapsedMs: Long): Boolean =
    deadlineAtElapsedMs > 0L && nowElapsedMs >= deadlineAtElapsedMs

/** A failed catch-up frame can never be followed by PAGE_SYNCED on the same socket. */
internal fun mustCloseLanConnectionAfterFailure(type: String, authenticated: Boolean): Boolean =
    !authenticated || when (type) {
        "HELLO", "AUTH_PROOF", "HELLO_OK", "SUBSCRIBE", "OPERATION", "ACK", "PAGE_STATE",
        "PAGE_SYNCED", "ATTEMPT_UPSERT",
        -> true
        else -> false
    }

/** Keeps android.net.Uri parsing isolated for local JVM protocol tests. */
private object UriCompat { fun parse(value: String) = android.net.Uri.parse(value) }

private fun JSONObject.optionalNonNegativeInt(name: String): Int? {
    if (!has(name) || isNull(name)) return null
    return getInt(name).also { require(it >= 0) { "$name cannot be negative" } }
}
