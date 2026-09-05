package com.studyink.app

import android.app.Application
import android.util.AtomicFile
import android.util.Log
import com.studyink.construction.storage.*
import com.studyink.core.model.MasterNoteDataRootBus
import com.studyink.core.model.MasterNoteOptionalDataRootGuard
import com.studyink.library.data.LibraryRepository
import com.studyink.memo.core.MemoTarget
import com.studyink.memo.core.StudentMemoChangeBus
import com.studyink.memo.core.StudentMemoRepository
import com.studyink.monitor.telegram.PendingTelegramPeerDocument
import com.studyink.monitor.telegram.RemoteMonitorGateway
import com.studyink.monitor.telegram.RemoteReviewPeerStatus
import com.studyink.monitor.telegram.RemoteReviewRole
import com.studyink.monitor.telegram.TelegramEnqueueResult
import com.studyink.sync.lan.ConstructionLanBridge
import com.studyink.sync.lan.ConstructionLanPeer
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Geometry has its own state machine and never calls a handwriting mutation API. */
internal object MasterNoteConstructionSyncCoordinator {
    private var runtime: ConstructionSyncRuntime? = null
    @Synchronized fun initialize(application: Application) {
        if (runtime == null) runtime = ConstructionSyncRuntime(application).also { it.start() }
    }
}

internal fun constructionPeerMaySend(peerIsStudent: Boolean, kind: ConstructionPacketKind): Boolean =
    if (peerIsStudent) kind == ConstructionPacketKind.STUDENT_SNAPSHOT || kind == ConstructionPacketKind.RESULT
    else kind == ConstructionPacketKind.REQUEST_STATE || kind == ConstructionPacketKind.PUBLISH

internal fun constructionCoalesceKey(scope: String, request: String, chunkIndex: Int): String =
    "construction:" + ConstructionTelegramWire.stableId("$scope:$request:$chunkIndex")

/** Never replace the unuploaded suffix of a slow multipart application request. */
internal fun constructionMayStartNewDelivery(pendingChunkCount: Int, allAcknowledged: Boolean, terminalFailure: Boolean): Boolean =
    pendingChunkCount == 0 && (allAcknowledged || terminalFailure)

internal class ConstructionSyncRuntime(private val app: Application) : ConstructionUiBridge {
    private val root = File(app.filesDir, "masternote")
    private val store = ConstructionReplicaStore(root)
    private val memos = StudentMemoRepository.get(app)
    private val library = LibraryRepository.get(app)
    private val gateway = RemoteMonitorGateway.get(app)
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "construction-state") }
    private val sender = Executors.newSingleThreadExecutor { Thread(it, "construction-send") }
    private val roles = ConcurrentHashMap<ConstructionTarget, ConstructionReplicaRole>()
    private val states = ConcurrentHashMap<ConstructionTarget, ConstructionSyncUiState>()
    private val listeners = ConcurrentHashMap<ConstructionTarget, CopyOnWriteArraySet<() -> Unit>>()
    private val queries = mutableMapOf<ConstructionTarget, Query>()
    private val conflicts = mutableMapOf<ConstructionTarget, Conflict>()
    private val dirtyStudents = linkedSetOf<ConstructionTarget>()
    private val lastStudentOffers = mutableMapOf<ConstructionTarget, Pair<String, Long>>()
    private val transmitting = ConcurrentHashMap.newKeySet<String>()
    private var lastRecoveryAt = 0L
    private val subscriptions = mutableListOf<AutoCloseable>()
    private val pins = File(app.noBackupFilesDir, "construction-publications-v1").apply { check(mkdirs() || isDirectory) }

    internal data class PeerPin(val lanDevice: String? = null, val pairId: String? = null,
        val peerBotId: Long? = null, val contentSha256: String) {
        fun accepts(other: PeerPin): Boolean = contentSha256 == other.contentSha256 &&
            (lanDevice != null && lanDevice == other.lanDevice ||
                pairId != null && peerBotId != null && pairId == other.pairId && peerBotId == other.peerBotId)
    }
    private data class Query(val id: String, val teacherDigest: String, val base: ConstructionVersion?,
        val pin: PeerPin, val startedAt: Long, val choice: ConstructionConflictChoice? = null,
        val comparedStudent: ConstructionVersion? = null)
    private data class Conflict(val token: String, val teacherDigest: String, val student: ConstructionVersion, val pin: PeerPin)

    fun start() {
        ConstructionUiBridgeProvider.bridge = this
        subscriptions += ConstructionReplicaChangeBus.addListener { change -> execute {
            if (change.role == ConstructionReplicaRole.STUDENT) dirtyStudents += change.target
            if (change.role == ConstructionReplicaRole.TEACHER && change.kind == ConstructionReplicaChangeKind.LOCAL_EDIT) {
                conflicts.remove(change.target)
                updateState(change.target, "선생 초안 수정됨 · 아직 학생에게 발행하지 않았습니다.")
            } else {
                updateState(change.target)
            }
        } }
        subscriptions += StudentMemoChangeBus.addListener { change -> execute {
            val target = ConstructionTarget(change.target.bookId, change.target.pageNumber, change.target.attemptNo, change.memo.id)
            if (change.memo.deleted) {
                // Replays a failed UI deletion hook; the parent tombstone always dominates.
                ConstructionReplicaRole.entries.forEach { store.markMemoDeleted(target, it) }
            }
            drainTelegram()
        } }
        subscriptions += ConstructionLanBridge.addReceiver { peer, bytes ->
            val epoch = MasterNoteDataRootBus.currentGeneration()
            execute {
                if (epoch != MasterNoteDataRootBus.currentGeneration() || ConstructionLanBridge.peer(peer.localBookId) != peer) return@execute
                val packet = ConstructionSyncCodec.decode(bytes)
                val target = ConstructionTarget(peer.localBookId, packet.pageNumber, packet.attemptNo, packet.memoId)
                val book = library.book(peer.localBookId)
                if (book.contentSha256.lowercase() != peer.documentSha256) return@execute
                accept(target, packet, peer.peerIsStudent, pin(peer)) {
                    ConstructionLanBridge.peer(peer.localBookId) == peer && epoch == MasterNoteDataRootBus.currentGeneration()
                }
            }
        }
        subscriptions += gateway.subscribePeerDocuments { execute { drainTelegram() } }
        subscriptions += MasterNoteDataRootBus.addListener { execute {
            queries.clear(); conflicts.clear(); dirtyStudents.clear(); lastRecoveryAt = 0L
            roles.keys.forEach { updateState(it, "복원된 도형을 다시 확인합니다. 발행 전 학생 최신본과 비교합니다.") }
        } }
        worker.scheduleWithFixedDelay({ runCatching { tick() }.onFailure { Log.w(TAG, "Construction recovery failed", it) } }, 1, 2, TimeUnit.SECONDS)
    }

    override fun registerTarget(target: ConstructionTarget, role: ConstructionReplicaRole) {
        roles[target] = role
        execute {
            val state = store.load(target, role)
            if (role == ConstructionReplicaRole.STUDENT && state.attached) dirtyStudents += target
            if (role == ConstructionReplicaRole.TEACHER) {
                // Opening the memo repairs a restored/offline shadow without publishing a draft.
                currentPin(target, true)?.let { transmit(target, store.requestState(target), it) }
            }
            updateState(target)
        }
    }
    override fun sceneAccess(target: ConstructionTarget): ConstructionSceneAccess = store.sceneAccess(requireNotNull(roles[target]))
    override fun state(target: ConstructionTarget) = states[target] ?: ConstructionSyncUiState(roles[target] ?: ConstructionReplicaRole.STUDENT)
    override fun addListener(target: ConstructionTarget, listener: () -> Unit): AutoCloseable {
        val set = listeners.getOrPut(target) { CopyOnWriteArraySet() }
        set += listener
        return AutoCloseable { set -= listener }
    }
    override fun requestPublish(target: ConstructionTarget) = execute {
        if (roles[target] != ConstructionReplicaRole.TEACHER || queries.containsKey(target)) return@execute
        beginQuery(target)
    }
    override fun resolveConflict(target: ConstructionTarget, choice: ConstructionConflictChoice, expectedToken: String) = execute {
        val conflict = conflicts[target] ?: return@execute
        val current = store.load(target, ConstructionReplicaRole.TEACHER)
        if (roles[target] != ConstructionReplicaRole.TEACHER || conflict.token != expectedToken || digest(current) != conflict.teacherDigest) {
            conflicts.remove(target)
            updateState(target, "비교 중 선생 도형이 바뀌었습니다. 발행을 다시 눌러주세요.")
            return@execute
        }
        beginQuery(target, choice, conflict)
    }

    private fun beginQuery(target: ConstructionTarget, choice: ConstructionConflictChoice? = null, conflict: Conflict? = null) {
        val current = store.load(target, ConstructionReplicaRole.TEACHER)
        if (current.deleted || current.pendingPublish != null || !parentExists(target)) {
            updateState(target, "메모 상태 또는 이전 발행 응답을 먼저 확인해 주세요."); return
        }
        val peer = currentPin(target, true) ?: run {
            updateState(target, "학생 연결 또는 교재 연결이 준비되지 않았습니다. 두 기기의 최신 버전과 연결을 확인해 주세요."); return
        }
        if (conflict != null && !conflict.pin.accepts(peer)) {
            conflicts.remove(target); updateState(target, "연결 상대가 바뀌었습니다. 다시 발행해 주세요."); return
        }
        val packet = store.requestState(target)
        queries[target] = Query(packet.requestId, digest(current), current.commonBase?.version, peer,
            System.currentTimeMillis(), choice, conflict?.student)
        conflicts.remove(target)
        updateState(target, "학생 최신 도형 확인 중… 아직 발행하지 않았습니다.")
        transmit(target, packet, peer)
    }

    /** Apply only after exact parent, role, pairing and restore-generation validation. */
    private fun accept(target: ConstructionTarget, packet: ConstructionSyncPacket, peerIsStudent: Boolean,
        source: PeerPin, stillCurrent: () -> Boolean): Boolean {
        // Existing review code takes operationLock before the data-root guard, never the reverse.
        if (!stillCurrent()) return false
        val epoch = MasterNoteDataRootBus.currentGeneration()
        var afterApply: (() -> Unit)? = null
        val applied = MasterNoteOptionalDataRootGuard.withStableDataRoot(root) {
        if (!constructionPeerMaySend(peerIsStudent, packet.kind) || target.attemptNo <= 0 || epoch != MasterNoteDataRootBus.currentGeneration()) return@withStableDataRoot false
        if (packet.memoId != target.memoId || packet.pageNumber != target.pageNumber || packet.attemptNo != target.attemptNo) return@withStableDataRoot false
        val parent = memos.memo(MemoTarget(target.bookId, target.pageNumber, target.attemptNo), target.memoId, includeDeleted = true)
            ?: return@withStableDataRoot false // Parent may still be arriving on the separate memo channel.
        val localRole = if (peerIsStudent) ConstructionReplicaRole.TEACHER else ConstructionReplicaRole.STUDENT
        if (parent.deleted) store.markMemoDeleted(target, localRole)
        when (packet.kind) {
            ConstructionPacketKind.REQUEST_STATE -> transmit(target, store.studentSnapshot(target, packet.requestId), source)
            ConstructionPacketKind.PUBLISH -> transmit(target, store.receivePublish(target, packet), source)
            ConstructionPacketKind.STUDENT_SNAPSHOT -> {
                val current = store.receiveStudentSnapshot(target, packet)
                afterApply = {
                val query = queries[target]
                if (query != null && query.id == packet.requestId && query.pin.accepts(source)) {
                    queries.remove(target)
                    finishComparison(target, current, query, requireNotNull(packet.student))
                } else {
                    val conflict = conflicts[target]
                    if (conflict != null && current.studentShadow?.version != conflict.student) {
                        conflicts.remove(target)
                        updateState(target, "학생 도형이 다시 바뀌었습니다. 발행을 눌러 다시 비교해 주세요.")
                    } else updateState(target)
                }
                }
            }
            ConstructionPacketKind.RESULT -> {
                val expectedPin = readPin(packet.requestId) ?: return@withStableDataRoot false
                if (!expectedPin.accepts(source)) return@withStableDataRoot false
                val pending = store.load(target, ConstructionReplicaRole.TEACHER).pendingPublish
                if (pending?.requestId != packet.requestId) return@withStableDataRoot true
                val current = store.receiveResult(target, packet)
                afterApply = {
                when (packet.result) {
                    ConstructionPublishResult.APPLIED -> updateState(target, if (current.draftDirty)
                        "발행 완료 · 이후 수정한 선생 초안은 아직 전송되지 않았습니다." else "발행 완료 · 학생 기기에 저장됐습니다.")
                    ConstructionPublishResult.CONFLICT -> showConflict(target, current, source)
                    else -> updateState(target, "학생 메모가 삭제되어 발행하지 않았습니다.")
                }
                }
            }
        }
        true
        }
        if (applied) runCatching { afterApply?.invoke() }.onFailure {
            Log.w(TAG, "Construction changed during the publication comparison", it)
            updateState(target, "비교 중 도형이 다시 바뀌었습니다. 덮어쓰지 않았으니 발행을 다시 눌러주세요.")
        }
        return applied
    }

    private fun finishComparison(target: ConstructionTarget, current: ConstructionReplicaSnapshot, query: Query, received: ConstructionRemoteScene) {
        if (digest(current) != query.teacherDigest || current.pendingPublish != null || current.deleted) {
            updateState(target, "확인 중 선생 도형이나 메모가 바뀌었습니다. 다시 발행해 주세요."); return
        }
        if (current.studentShadow?.version != received.version ||
            query.choice != null && query.comparedStudent != received.version) {
            showConflict(target, current, query.pin); return
        }
        if (query.choice == ConstructionConflictChoice.USE_STUDENT) {
            store.adoptStudent(current, received.version)
            updateState(target, "학생 도형으로 선생 도형을 맞췄습니다. 필기는 그대로 유지했습니다.")
            return
        }
        val id = UUID.randomUUID().toString()
        // Reserve the peer identity before creating a durable pending request. Orphans are harmless.
        writePin(id, query.pin)
        val compared = if (query.choice == ConstructionConflictChoice.USE_TEACHER) received.version else query.base
        val result = store.preparePublish(current, compared, id)
        if (result.conflict) showConflict(target, result.snapshot, query.pin)
        else {
            updateState(target, "발행 중… 학생 기기의 저장 완료 응답을 기다립니다.")
            transmit(target, requireNotNull(result.packet), query.pin)
        }
    }

    private fun showConflict(target: ConstructionTarget, current: ConstructionReplicaSnapshot, pin: PeerPin) {
        val student = current.studentShadow?.takeUnless { it.deleted }
        if (student == null || current.deleted) { updateState(target, "학생 메모가 없거나 삭제되어 발행하지 않았습니다."); return }
        conflicts[target] = Conflict(UUID.randomUUID().toString(), digest(current), student.version, pin)
        updateState(target, "충돌: 학생 도형에 업데이트가 있습니다. 어느 도형으로 맞출지 선택해 주세요.")
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        queries.entries.toList().forEach { (target, query) -> if (now - query.startedAt > 60_000L) {
            queries.remove(target)
            updateState(target, "학생 최신본 응답이 없어 발행하지 않았습니다. 연결과 두 기기의 앱 버전을 확인하고 다시 눌러주세요.")
        } }
        val changed = dirtyStudents.toList()
        dirtyStudents.clear()
        changed.forEach { sendStudent(it) }
        if (now - lastRecoveryAt >= 20_000L) {
            lastRecoveryAt = now
            store.listTargets(ConstructionReplicaRole.STUDENT).forEach { sendStudent(it) }
            store.listTargets(ConstructionReplicaRole.TEACHER).forEach { target ->
                val state = store.load(target, ConstructionReplicaRole.TEACHER)
                state.pendingPublish?.let { packet -> readPin(packet.requestId)?.let { transmit(target, packet, it) } }
            }
            drainTelegram()
        }
        listeners.entries.filter { it.value.isNotEmpty() }.forEach { updateState(it.key) }
    }

    private fun sendStudent(target: ConstructionTarget) {
        if (target.attemptNo <= 0 || !parentExists(target, includeDeleted = true)) return
        val peer = currentPin(target, false) ?: return
        var current = store.load(target, ConstructionReplicaRole.STUDENT)
        if (memos.memo(MemoTarget(target.bookId, target.pageNumber, target.attemptNo), target.memoId, true)?.deleted == true) {
            current = store.markMemoDeleted(target, ConstructionReplicaRole.STUDENT)
        }
        if (!current.attached && !current.deleted) return
        val stableId = ConstructionTelegramWire.stableId("$target:${current.studentShadow?.version}")
        val routeKey = "$peer:$stableId:${ConstructionLanBridge.peer(target.bookId)?.sessionId}"
        val now = System.currentTimeMillis()
        val previous = lastStudentOffers[target]
        if (previous?.first == routeKey && now - previous.second < 120_000L) return
        lastStudentOffers[target] = routeKey to now
        transmit(target, store.studentSnapshot(target, stableId), peer)
    }

    private fun drainTelegram() {
        val pending = gateway.pendingPeerDocuments().filter { it.payloadType == CONSTRUCTION_TELEGRAM_PAYLOAD }
        // The gateway's durable inbox owns chunk files. Never acknowledge a partial assembly.
        val groups = linkedMapOf<String, MutableList<Pair<PendingTelegramPeerDocument, ConstructionTelegramWire.Chunk>>>()
        pending.sortedByDescending { it.receivedAtEpochMs }.forEach { document ->
            // Incomplete superseded attempts are transport fragments, not the durable scene.
            // Both sides retain scene/request state and retry with a new outer delivery attempt.
            if (System.currentTimeMillis() - document.receivedAtEpochMs > TimeUnit.DAYS.toMillis(1)) {
                gateway.acknowledgePeerDocument(document.updateId)
                return@forEach
            }
            val chunk = runCatching {
                require(document.file.length() in 1..ConstructionTelegramWire.MAX_FRAME_BYTES.toLong())
                ConstructionTelegramWire.decode(document.file.readBytes()).also { require(it.transferId == document.transferId) }
            }.getOrElse { Log.w(TAG, "Invalid construction document retained", it); return@forEach }
            if (groups.size < 8 || groups.containsKey(chunk.transmissionId)) {
                groups.getOrPut(chunk.transmissionId) { mutableListOf() } += document to chunk
            }
        }
        groups.values.forEach { group -> runCatching {
            val first = group.first()
            if (group.any { it.first.senderBotId != first.first.senderBotId }) return@runCatching
            val bytes = ConstructionTelegramWire.assemble(group.map { it.second }) ?: return@runCatching
            val route = MasterNoteRemoteReviewCoordinator.withConstructionIncoming(first.first.senderBotId, first.second.address) { it }
                ?: return@runCatching
            val packet = ConstructionSyncCodec.decode(bytes)
            val source = pin(route)
            if (accept(route.target, packet, route.peerIsStudent, source) {
                    MasterNoteRemoteReviewCoordinator.withConstructionIncoming(first.first.senderBotId, first.second.address) { it == route } == true
                }) group.forEach { gateway.acknowledgePeerDocument(it.first.updateId) }
        }.onFailure { Log.w(TAG, "Construction document deferred", it) } }
    }

    private fun transmit(target: ConstructionTarget, packet: ConstructionSyncPacket, expected: PeerPin) {
        val key = "${packet.kind}:${packet.requestId}"
        if (!transmitting.add(key)) return
        val epoch = MasterNoteDataRootBus.currentGeneration()
        sender.execute {
            try {
                val bytes = ConstructionSyncCodec.encode(packet)
                if (epoch != MasterNoteDataRootBus.currentGeneration()) return@execute
                val peerIsStudent = packet.kind == ConstructionPacketKind.REQUEST_STATE || packet.kind == ConstructionPacketKind.PUBLISH
                val lan = ConstructionLanBridge.peer(target.bookId)?.takeIf { it.peerIsStudent == peerIsStudent && expected.accepts(pin(it)) }
                if (lan != null && ConstructionLanBridge.send(target.bookId, bytes, lan)) return@execute
                val route = MasterNoteRemoteReviewCoordinator.withConstructionOutbound(target) { it }
                    ?.takeIf { it.peerIsStudent == peerIsStudent && expected.accepts(pin(it)) } ?: return@execute
                val delivery = prepareDelivery(route, packet, bytes)
                val frames = ConstructionTelegramWire.frames(route.address, bytes, delivery.id)
                val automaticSnapshot = packet.kind == ConstructionPacketKind.STUDENT_SNAPSHOT &&
                    packet.requestId == ConstructionTelegramWire.stableId("$target:${packet.student?.version}")
                val streamRequest = if (automaticSnapshot) "latest" else "${packet.requestId}:${delivery.id}"
                for ((id, frame) in frames) {
                    if (epoch != MasterNoteDataRootBus.currentGeneration() ||
                        MasterNoteRemoteReviewCoordinator.withConstructionOutbound(target) { it == route } != true) return@execute
                    val temp = File.createTempFile("construction-", ".json", app.cacheDir)
                    try {
                        temp.writeBytes(frame)
                        val result = gateway.enqueuePeerDocument(id, CONSTRUCTION_TELEGRAM_PAYLOAD, temp,
                            coalesceKey = constructionCoalesceKey("${route.address.pairId}:${route.address.pageToken}:${target.memoId}:${packet.kind}", streamRequest, frames.indexOfFirst { it.first == id }),
                            expectedPairId = route.address.pairId, expectedPeerBotId = route.peerBotId)
                        if (result !in setOf(TelegramEnqueueResult.ENQUEUED, TelegramEnqueueResult.ALREADY_PENDING, TelegramEnqueueResult.ALREADY_DELIVERED)) {
                            if (result == TelegramEnqueueResult.PREVIOUSLY_DEAD || result == TelegramEnqueueResult.PREVIOUSLY_SUPERSEDED) {
                                writeDelivery(delivery.copy(failed = true))
                            }
                            execute { updateState(target, "도형 전송 대기 중입니다. 연결 또는 전송 대기열을 확인해 주세요.") }
                            break
                        }
                    } finally { temp.delete() }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Construction send deferred", error)
                execute { updateState(target, "도형 전송 대기 중 · 저장된 내용은 유지됩니다.") }
            } finally { transmitting.remove(key) }
        }
    }

    private fun currentPin(target: ConstructionTarget, peerIsStudent: Boolean, expected: PeerPin? = null): PeerPin? {
        val lan = ConstructionLanBridge.peer(target.bookId)?.takeIf { it.peerIsStudent == peerIsStudent && (expected == null || expected.accepts(pin(it))) }
        val remote = MasterNoteRemoteReviewCoordinator.withConstructionOutbound(target) { it }
            ?.takeIf { it.peerIsStudent == peerIsStudent && (expected == null || expected.accepts(pin(it))) }
        if (lan == null && remote == null) return null
        // Matching PDFs do not prove that independently paired LAN and Telegram peers are the same person.
        // A publication remains pinned to its authenticated peer; never silently fall back to another pairing.
        return if (lan != null) pin(lan) else remote?.let(::pin)
    }

    private data class Delivery(val key: String, val fingerprint: String, val id: String, val failed: Boolean = false)
    private fun prepareDelivery(route: ConstructionTelegramRoute, packet: ConstructionSyncPacket, bytes: ByteArray): Delivery {
        val key = ConstructionTelegramWire.stableId("${route.address.pairId}:${route.target}:${packet.kind}:${packet.requestId}")
        val fingerprint = ConstructionTelegramWire.stableId(route.address.toString() + bytes.toString(Charsets.UTF_8))
        val old = runCatching {
            val j = JSONObject(AtomicFile(File(pins, "delivery-$key.json")).readFully().toString(Charsets.UTF_8))
            Delivery(key, j.getString("fingerprint"), j.getString("id"), j.getBoolean("failed"))
        }.getOrNull()?.takeIf { it.fingerprint == fingerprint }
        if (old != null) {
            val frameIds = ConstructionTelegramWire.frames(route.address, bytes, old.id).map { it.first }
            val pending = gateway.pendingPeerDocumentTransfers(setOf(CONSTRUCTION_TELEGRAM_PAYLOAD)).mapTo(hashSetOf()) { it.transferId }
            val acknowledged = frameIds.all { gateway.peerDeliveryReceipt(it)?.acknowledgedAtEpochMs != null }
            if (!constructionMayStartNewDelivery(frameIds.count { it in pending }, acknowledged, old.failed)) return old
        }
        return Delivery(key, fingerprint, UUID.randomUUID().toString()).also(::writeDelivery)
    }
    private fun writeDelivery(value: Delivery) {
        val file = AtomicFile(File(pins, "delivery-${value.key}.json"))
        val bytes = JSONObject().put("fingerprint", value.fingerprint).put("id", value.id).put("failed", value.failed).toString().toByteArray()
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (error: Exception) { file.failWrite(stream); throw error }
    }
    private fun pin(lan: ConstructionLanPeer) = PeerPin(lanDevice = lan.peerDeviceId, contentSha256 = lan.documentSha256)
    private fun pin(route: ConstructionTelegramRoute) = PeerPin(pairId = route.address.pairId, peerBotId = route.peerBotId, contentSha256 = route.address.contentSha256)
    private fun parentExists(target: ConstructionTarget, includeDeleted: Boolean = false): Boolean = target.attemptNo > 0 &&
        memos.memo(MemoTarget(target.bookId, target.pageNumber, target.attemptNo), target.memoId, includeDeleted) != null
    private fun digest(snapshot: ConstructionReplicaSnapshot) = ConstructionSyncCodec.sceneDigest(snapshot.scene, snapshot.deleted, snapshot.attached)

    private fun updateState(target: ConstructionTarget, message: String? = null) {
        val role = roles[target] ?: return
        val snapshot = store.load(target, role)
        val available = currentPin(target, role == ConstructionReplicaRole.TEACHER, snapshot.pendingPublish?.let { readPin(it.requestId) }) != null
        val busy = queries.containsKey(target) || snapshot.pendingPublish != null
        val old = states[target]
        val text = message ?: old?.message?.takeIf { it.isNotBlank() && old.available == available && old.busy == busy }
            ?: when {
                snapshot.deleted -> "삭제된 메모입니다."
                snapshot.pendingPublish != null -> if (available) "발행 확인 대기 · 학생 기기의 저장 응답을 받으면 완료됩니다."
                    else "발행 확인 대기 · 처음 발행한 학생과 연결 경로가 다시 연결되면 확인합니다."
                role == ConstructionReplicaRole.TEACHER -> if (available) "선생 도형 초안 · 발행할 때만 학생에게 전송됩니다." else "선생 도형 초안 저장됨 · 학생 연결 후 발행할 수 있습니다."
                available -> "도형 자동 저장 · 확정한 변경을 학생→선생으로 전송합니다."
                else -> "도형 자동 저장 · 연결되면 선생에게 전송합니다."
            }
        val next = ConstructionSyncUiState(role, available, busy, text, conflicts[target]?.token,
            role == ConstructionReplicaRole.TEACHER && snapshot.attached && !snapshot.deleted && available && !busy)
        if (next != old) { states[target] = next; listeners[target]?.forEach { runCatching(it) } }
    }
    private fun writePin(id: String, pin: PeerPin) {
        val file = AtomicFile(File(pins, "$id.json"))
        val bytes = JSONObject().put("lanDevice", pin.lanDevice).put("pairId", pin.pairId)
            .put("peerBotId", pin.peerBotId).put("contentSha256", pin.contentSha256).toString().toByteArray()
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    private fun readPin(id: String): PeerPin? = runCatching {
        require(UUID.fromString(id).toString() == id)
        val j = JSONObject(AtomicFile(File(pins, "$id.json")).readFully().toString(Charsets.UTF_8))
        PeerPin(j.optString("lanDevice").takeIf { it.isNotBlank() }, j.optString("pairId").takeIf { it.isNotBlank() },
            if (j.has("peerBotId")) j.getLong("peerBotId") else null, j.getString("contentSha256"))
    }.getOrNull()
    private fun execute(block: () -> Unit) { worker.execute { runCatching(block).onFailure { Log.w(TAG, "Construction state operation failed", it) } } }
    private companion object { const val TAG = "MemoConstructionSync" }
}
