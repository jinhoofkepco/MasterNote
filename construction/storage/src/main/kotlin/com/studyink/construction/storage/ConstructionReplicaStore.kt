package com.studyink.construction.storage

import com.studyink.construction.core.ConstructionScene
import com.studyink.core.model.MasterNoteDataCommitBus
import com.studyink.core.model.MasterNoteDataRootBus
import com.studyink.core.model.MasterNoteOptionalDataRootGuard
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ConcurrentModificationException
import java.util.UUID

/**
 * One atomic file contains the editable scene, common base, student shadow, pending publication,
 * recovery scene and durable receipt. No transport acknowledgement may precede receivePublish().
 * The caller must authenticate the sender and map its memo to a verified local target first.
 */
class ConstructionReplicaStore(private val dataRoot: File) {
    private val identity = dataRoot.toPath().toAbsolutePath().normalize().toString()
    private val legacy = ConstructionSceneStore(dataRoot)

    fun load(target: ConstructionTarget, role: ConstructionReplicaRole): ConstructionReplicaSnapshot = locked {
        snapshot(readCurrent(target, role))
    }

    /** Includes detached/tombstoned targets, so startup can recover pending or unsent durable work. */
    fun listTargets(role: ConstructionReplicaRole): List<ConstructionTarget> = locked {
        val root = File(dataRoot, FEATURE_DIRECTORY)
        if (!root.exists()) return@locked emptyList()
        val bases = root.walkTopDown().filter(File::isFile).mapNotNull { candidate ->
            when {
                Regex("[0-9a-f]{64}\\.json").matches(candidate.name) -> candidate
                Regex("[0-9a-f]{64}\\.json\\.bak").matches(candidate.name) -> File(candidate.parentFile, candidate.name.removeSuffix(".bak"))
                else -> null
            }
        }.distinctBy { it.absolutePath }.take(100_001).toList()
        require(bases.size <= 100_000) { "Too many construction replicas" }
        bases.map { candidate ->
            try {
                val replica = decode(requireNotNull(AtomicConstructionFile(candidate, MAX_FILE_BYTES).readOrNull()))
                require(fileForTest(replica.target, replica.role).canonicalFile == candidate.canonicalFile) { "Replica identity mismatch" }
                replica
            } catch (error: Exception) {
                throw ConstructionDataException("Unable to enumerate construction replicas; existing bytes preserved", error)
            }
        }.filter { it.role == role }.map { it.target }
            .sortedWith(compareBy(ConstructionTarget::bookId, ConstructionTarget::pageNumber, ConstructionTarget::attemptNo, ConstructionTarget::memoId, ConstructionTarget::ownerScope))
    }

    fun hasAttachment(target: ConstructionTarget, role: ConstructionReplicaRole): Boolean = load(target, role).let { it.attached && !it.deleted }

    fun ensureAttachment(target: ConstructionTarget, role: ConstructionReplicaRole): ConstructionReplicaSnapshot {
        val expected = load(target, role)
        if (expected.attached || expected.deleted) return expected
        return saveLocal(expected, expected.scene)
    }

    fun saveLocal(expected: ConstructionReplicaSnapshot, scene: ConstructionScene): ConstructionReplicaSnapshot {
        val frozen = ConstructionJsonCodec.immutableScene(scene)
        val result = locked {
            val old = requireCurrent(expected)
            check(!old.deleted) { "Deleted memo cannot be edited" }
            if (old.scene == frozen && old.attached) return@locked snapshot(old)
            var next = old.copy(scene = frozen, attached = true)
            if (old.role == ConstructionReplicaRole.STUDENT) next = next.copy(student = localRemote(next, nextStudentRevision(old)))
            snapshot(write(next))
        }
        return emitIfChanged(expected, result, ConstructionReplicaChangeKind.LOCAL_EDIT)
    }

    fun requestState(target: ConstructionTarget, requestId: String = newId(), includeMemo: Boolean = false) =
        packet(target, ConstructionPacketKind.REQUEST_STATE, requestId).copy(includeMemo = includeMemo)

    fun studentSnapshot(
        target: ConstructionTarget,
        requestId: String = newId(),
        memoStateKnown: Boolean = false,
        memoJson: String? = null,
    ): ConstructionSyncPacket {
        val state = load(target, ConstructionReplicaRole.STUDENT)
        val student = requireNotNull(state.studentShadow).copy(memoStateKnown = memoStateKnown, memoJson = memoJson)
        ConstructionSyncCodec.validateRemote(student)
        return packet(target, ConstructionPacketKind.STUDENT_SNAPSHOT, requestId).copy(student = student)
    }

    /** A higher generation supersedes a restored past; a lower generation can never roll it back. */
    fun receiveStudentSnapshot(target: ConstructionTarget, packet: ConstructionSyncPacket, preserveDraft: Boolean = false): ConstructionReplicaSnapshot {
        requirePacket(target, packet, ConstructionPacketKind.STUDENT_SNAPSHOT)
        val incoming = freezeRemote(requireNotNull(packet.student))
        var changed = false
        val result = locked {
            val old = readCurrent(target, ConstructionReplicaRole.TEACHER)
            val newerGeometry = isNewer(incoming, old.student)
            if (old.deleted || !newerGeometry && !refreshesMemo(incoming, old.student)) return@locked snapshot(old)
            val clean = !preserveDraft && !dirty(old) && old.pending == null
            val previousBase = old.base
            // A fresh memo-only query updates the shadow, not an already-known comparison base.
            // The app separately tracks parent-memo edits, which this geometry dirty flag cannot see.
            val base = when {
                clean && newerGeometry -> incoming
                clean && previousBase != null && previousBase.version == incoming.version && !previousBase.memoStateKnown && incoming.memoStateKnown ->
                    previousBase.copy(memoStateKnown = true, memoJson = incoming.memoJson)
                else -> old.base
            }
            val next = old.copy(
                student = incoming,
                scene = if (clean && newerGeometry) incoming.scene else old.scene,
                attached = if (clean && newerGeometry) incoming.attached else old.attached,
                base = base,
                deleted = old.deleted || incoming.deleted,
            )
            changed = true
            snapshot(write(if (incoming.deleted) next.copy(scene = ConstructionScene(), attached = false, pending = null) else next))
        }
        if (changed) emit(result, ConstructionReplicaChangeKind.REMOTE_STUDENT)
        return result
    }

    /**
     * Call only after a correlated fresh REQUEST_STATE response. Default uses the last common base.
     * An explicit comparedStudent token is the user's conflict choice, never a blind force flag.
     */
    fun preparePublish(
        expected: ConstructionReplicaSnapshot,
        comparedStudent: ConstructionVersion? = expected.commonBase?.version,
        requestId: String = newId(),
        memoJson: String? = null,
        expectedMemoDigest: String? = null,
    ): ConstructionPublishPreparation {
        ConstructionSyncCodec.requireUuid(requestId)
        val prepared = locked {
            val old = requireCurrent(expected)
            require(old.role == ConstructionReplicaRole.TEACHER)
            check(old.pending == null) { "A publication is already pending" }
            val student = old.student
            val initialEmpty = old.base == null && student != null && !student.deleted && !student.attached && student.scene == ConstructionScene()
            if (old.deleted || !old.attached || student == null || student.deleted ||
                !(comparedStudent == student.version || comparedStudent == null && initialEmpty)
            ) return@locked ConstructionPublishPreparation(snapshot(old), null, true)
            val request = packet(old.target, ConstructionPacketKind.PUBLISH, requestId)
                .copy(expectedStudent = student.version, scene = old.scene, memoJson = memoJson, expectedMemoDigest = expectedMemoDigest)
            ConstructionSyncCodec.encode(request) // Reject an untransportable draft before marking it pending.
            ConstructionPublishPreparation(snapshot(write(old.copy(pending = request))), request, false)
        }
        if (prepared.packet != null) emit(prepared.snapshot, ConstructionReplicaChangeKind.PUBLISH_PREPARED)
        return prepared
    }

    fun adoptStudent(
        expected: ConstructionReplicaSnapshot,
        comparedStudent: ConstructionVersion,
        beforeAdopt: (() -> Unit)? = null,
    ): ConstructionReplicaSnapshot {
        val result = locked {
            val old = requireCurrent(expected)
            require(old.role == ConstructionReplicaRole.TEACHER)
            check(old.pending == null) { "A publication is still pending" }
            val student = requireNotNull(old.student) { "Student state is not available" }
            if (student.version != comparedStudent) throw ConcurrentModificationException("Student changed after comparison")
            check(!old.deleted && !student.deleted) { "The memo was deleted" }
            // Parent-memo CAS must not run until the geometry comparison is known to be current.
            beforeAdopt?.invoke()
            snapshot(write(old.copy(scene = student.scene, attached = student.attached, base = student)))
        }
        return emitIfChanged(expected, result, ConstructionReplicaChangeKind.ADOPTED_STUDENT)
    }

    /** Atomic CAS + previous-scene recovery + receipt. A duplicate returns its original durable result. */
    fun receivePublish(
        target: ConstructionTarget,
        request: ConstructionSyncPacket,
        applyMemo: (() -> ConstructionMemoApplyResult)? = null,
    ): ConstructionSyncPacket {
        requirePacket(target, request, ConstructionPacketKind.PUBLISH)
        val requestHash = ConstructionSyncCodec.packetDigest(request)
        var applied: ConstructionReplicaSnapshot? = null
        val result = locked {
            val old = readCurrent(target, ConstructionReplicaRole.STUDENT)
            old.receipts.firstOrNull { it.requestId == request.requestId }?.let { receipt ->
                require(receipt.requestHash == requestHash) { "Publication request id was reused with different bytes" }
                return@locked receiptResponse(target, request, receipt)
            }
            check(old.receipts.size < MAX_RECEIPTS) { "Publication receipt capacity exceeded; existing records preserved" }
            val prior = requireNotNull(old.student)
            var status = when {
                old.deleted -> ConstructionPublishResult.DELETED
                prior.version != request.expectedStudent -> ConstructionPublishResult.CONFLICT
                else -> ConstructionPublishResult.APPLIED
            }
            // Never apply a parent when the geometry CAS failed, or run an idempotent retry twice.
            val memoResult = if (status == ConstructionPublishResult.APPLIED && request.memoJson != null) {
                requireNotNull(applyMemo) { "Parent memo apply callback is required" }.invoke().also {
                    it.memoJson?.let(ConstructionSyncCodec::validateMemoText)
                    require(!it.accepted || it.memoJson != null) { "Applied parent memo is missing" }
                    if (!it.accepted) status = ConstructionPublishResult.CONFLICT
                }
            } else null
            var next = old
            if (status == ConstructionPublishResult.APPLIED) {
                val scene = ConstructionJsonCodec.immutableScene(requireNotNull(request.scene))
                next = old.copy(scene = scene, attached = true, recovery = prior)
                val student = localRemote(next, nextStudentRevision(old)).let { remote ->
                    if (memoResult == null) remote else remote.copy(memoStateKnown = true, memoJson = memoResult.memoJson)
                }
                next = next.copy(student = student, base = student)
            }
            val remote = requireNotNull(next.student).let {
                if (memoResult == null) it else it.copy(memoStateKnown = true, memoJson = memoResult.memoJson)
            }
            val response = packet(target, ConstructionPacketKind.RESULT, request.requestId)
                .copy(student = remote, result = status)
            ConstructionSyncCodec.encode(response)
            // Keeping the receipt in the same atomic replace is what makes ACK safe after a crash.
            next = next.copy(receipts = next.receipts + receipt(requestHash, response))
            val committed = snapshot(write(next))
            if (status == ConstructionPublishResult.APPLIED) applied = committed
            response
        }
        applied?.let { emit(it, ConstructionReplicaChangeKind.REMOTE_PUBLISH) }
        return result
    }

    /** Only a response matching our persisted pending request can advance the common base. */
    fun receiveResult(target: ConstructionTarget, response: ConstructionSyncPacket): ConstructionReplicaSnapshot {
        requirePacket(target, response, ConstructionPacketKind.RESULT)
        val incoming = freezeRemote(requireNotNull(response.student))
        var changed = false
        val result = locked {
            val old = readCurrent(target, ConstructionReplicaRole.TEACHER)
            val pending = old.pending ?: return@locked snapshot(old)
            if (pending.requestId != response.requestId) return@locked snapshot(old)
            val shadow = if (isNewer(incoming, old.student) || refreshesMemo(incoming, old.student)) incoming else old.student ?: incoming
            val applied = response.result == ConstructionPublishResult.APPLIED
            if (applied) {
                require(pending.memoJson == null || incoming.memoStateKnown && incoming.memoJson != null) {
                    "Publication acknowledgement omitted the parent memo"
                }
                require(incoming.version.generation == pending.expectedStudent?.generation &&
                    incoming.version.revision == requireNotNull(pending.expectedStudent).revision + 1L &&
                    incoming.version.digestSha256 == ConstructionSyncCodec.sceneDigest(requireNotNull(pending.scene)) &&
                    incoming.attached && !incoming.deleted) { "Invalid publication acknowledgement" }
            }
            val deleted = old.deleted || shadow.deleted
            // A newer student edit may have arrived before this publication's delayed ACK. Once
            // pending is cleared, an unchanged teacher draft must follow that already-known shadow;
            // the same snapshot will not be considered newer a second time. Preserve later drafts.
            val adoptLatest = applied && !deleted && old.attached && old.scene == pending.scene
            val next = old.copy(
                pending = null, student = shadow,
                base = if (adoptLatest) shadow else if (applied) incoming else old.base,
                scene = if (deleted) ConstructionScene() else if (adoptLatest) shadow.scene else old.scene,
                attached = !deleted && (if (adoptLatest) shadow.attached else old.attached), deleted = deleted,
            )
            changed = true
            snapshot(write(next))
        }
        if (changed) emit(result, ConstructionReplicaChangeKind.PUBLISH_RESULT)
        return result
    }

    fun receiveAck(target: ConstructionTarget, response: ConstructionSyncPacket) = receiveResult(target, response)

    /** A whole-memo tombstone, not the command that merely clears objects from its drawing. */
    fun markMemoDeleted(target: ConstructionTarget, role: ConstructionReplicaRole): ConstructionReplicaSnapshot {
        var changed = false
        val result = locked {
            val old = readCurrent(target, role)
            if (old.deleted) return@locked snapshot(old)
            var next = old.copy(scene = ConstructionScene(), attached = false, deleted = true, pending = null)
            if (role == ConstructionReplicaRole.STUDENT) next = next.copy(student = localRemote(next, nextStudentRevision(old)))
            changed = true
            snapshot(write(next))
        }
        if (changed) emit(result, ConstructionReplicaChangeKind.DELETED)
        return result
    }

    fun sceneAccess(role: ConstructionReplicaRole): ConstructionSceneAccess = object : ConstructionSceneAccess {
        override fun load(target: ConstructionTarget) = editorSnapshot(this@ConstructionReplicaStore.load(target, role))
        override fun save(expected: ConstructionSceneSnapshot, scene: ConstructionScene): ConstructionSceneSnapshot = locked {
            val current = this@ConstructionReplicaStore.load(expected.target, role)
            if (expected.rootIdentity != editorIdentity(role) || expected.rootEpoch != current.rootGeneration ||
                current.deleted || expected.scene != current.scene
            ) throw ConcurrentModificationException("Memo drawing changed; reload before saving")
            // Publication metadata may advance while a pen gesture is active. Rebase only when its
            // exact immutable scene is unchanged; draft content and restore generations stay strict.
            editorSnapshot(saveLocal(current, scene))
        }
        override fun addRestoreListener(listener: () -> Unit) = MasterNoteDataRootBus.addListener(listener)
    }

    private fun editorSnapshot(value: ConstructionReplicaSnapshot) = ConstructionSceneSnapshot(
        value.target, value.stateRevision, value.scene, value.stateCommitId, value.rootGeneration, editorIdentity(value.role),
    )

    private fun editorIdentity(role: ConstructionReplicaRole) = "$identity#construction-replica:${role.name}"

    private fun requireCurrent(expected: ConstructionReplicaSnapshot): Replica {
        if (expected.rootIdentity != identity || expected.rootGeneration != ConstructionReplicaEpoch.current(dataRoot)) {
            throw ConcurrentModificationException("Memo data was restored; reload before saving")
        }
        val current = readCurrent(expected.target, expected.role)
        if (current.commitId != expected.stateCommitId || current.revision != expected.stateRevision) {
            throw ConcurrentModificationException("Memo replica changed; compare again")
        }
        return current
    }

    private fun readCurrent(target: ConstructionTarget, role: ConstructionReplicaRole): Replica {
        val generation = ConstructionReplicaEpoch.current(dataRoot)
        val bytes = file(target, role).readOrNull()
        val stored = if (bytes == null) {
            val old = legacy.load(target)
            var initial = Replica(target, role, 0L, newId(), generation, old.scene, old.revision > 0)
            if (role == ConstructionReplicaRole.STUDENT) initial = initial.copy(student = localRemote(initial, old.revision))
            return write(initial)
        } else try { decode(bytes).also { require(it.target == target && it.role == role) } }
        catch (error: Exception) { throw ConstructionDataException("Stored memo drawing replica is invalid; existing bytes preserved", error) }
        if (stored.generation == generation) return stored
        var next = stored.copy(generation = generation, pending = null, receipts = emptyList())
        if (role == ConstructionReplicaRole.STUDENT) next = next.copy(student = localRemote(next, requireNotNull(stored.student).version.revision))
        return write(next)
    }

    private fun write(value: Replica): Replica {
        check(value.revision < Long.MAX_VALUE) { "Replica revision exhausted" }
        val next = value.copy(revision = value.revision + 1L, commitId = newId())
        file(next.target, next.role).write(encode(next))
        MasterNoteDataCommitBus.recordDurableCommit()
        return next
    }

    private fun snapshot(value: Replica) = ConstructionReplicaSnapshot(
        value.target, value.role, value.scene, value.student, value.base, dirty(value), value.pending,
        value.recovery, value.deleted, value.attached, value.revision, value.commitId, value.generation, identity,
    )

    private fun dirty(value: Replica) = value.role == ConstructionReplicaRole.TEACHER &&
        (value.base?.let { it.scene != value.scene || it.attached != value.attached } ?: value.attached)

    private fun localRemote(value: Replica, revision: Long) = ConstructionRemoteScene(
        ConstructionVersion(value.generation, revision, ConstructionSyncCodec.sceneDigest(value.scene, value.deleted, value.attached)),
        value.scene, value.deleted, value.attached,
    )

    private fun nextStudentRevision(value: Replica): Long = requireNotNull(value.student).version.revision.let {
        check(it < Long.MAX_VALUE) { "Student revision exhausted" }; it + 1L
    }

    private fun isNewer(incoming: ConstructionRemoteScene, previous: ConstructionRemoteScene?): Boolean {
        if (previous == null) return true
        val a = incoming.version; val b = previous.version
        if (a.generation != b.generation) return a.generation > b.generation
        if (a.revision != b.revision) return a.revision > b.revision
        require(a.digestSha256 == b.digestSha256) { "Equal student revision has conflicting content" }
        return false
    }

    private fun refreshesMemo(incoming: ConstructionRemoteScene, previous: ConstructionRemoteScene?) =
        previous != null && incoming.version == previous.version && incoming.memoStateKnown &&
            (!previous.memoStateKnown || incoming.memoJson != previous.memoJson)

    private fun freezeRemote(value: ConstructionRemoteScene): ConstructionRemoteScene = value.copy(
        scene = ConstructionJsonCodec.immutableScene(value.scene),
    ).also(ConstructionSyncCodec::validateRemote)

    private fun requirePacket(target: ConstructionTarget, packet: ConstructionSyncPacket, kind: ConstructionPacketKind) {
        require(packet.kind == kind && packet.memoId == target.memoId && packet.pageNumber == target.pageNumber && packet.attemptNo == target.attemptNo) {
            "Construction packet does not match the authenticated memo target"
        }
        ConstructionSyncCodec.encode(packet)
    }

    private fun packet(target: ConstructionTarget, kind: ConstructionPacketKind, requestId: String) = ConstructionSyncPacket(
        kind, requestId, target.memoId, target.pageNumber, target.attemptNo,
    )

    private fun emitIfChanged(old: ConstructionReplicaSnapshot, next: ConstructionReplicaSnapshot, kind: ConstructionReplicaChangeKind): ConstructionReplicaSnapshot {
        if (old.stateCommitId != next.stateCommitId) emit(next, kind)
        return next
    }

    private fun emit(snapshot: ConstructionReplicaSnapshot, kind: ConstructionReplicaChangeKind) =
        ConstructionReplicaChangeBus.publish(ConstructionReplicaChange(snapshot.target, snapshot.role, kind, snapshot))

    private fun file(target: ConstructionTarget, role: ConstructionReplicaRole) = AtomicConstructionFile(fileForTest(target, role), MAX_FILE_BYTES)

    internal fun fileForTest(target: ConstructionTarget, role: ConstructionReplicaRole): File {
        val key = listOf(target.ownerScope, target.bookId, target.pageNumber, target.attemptNo, target.memoId, role.name)
            .joinToString("") { value -> "$value".let { "${it.length}:$it" } }
        val digest = ConstructionSyncCodec.sha256(key.toByteArray(Charsets.UTF_8))
        return File(dataRoot, "$FEATURE_DIRECTORY/${digest.take(2)}/$digest.json")
    }

    private fun <T> locked(block: () -> T): T = MasterNoteOptionalDataRootGuard.withStableDataRoot(dataRoot, block)

    private fun encode(value: Replica): ByteArray {
        val json = JSONObject().put("target", ConstructionJsonCodec.encodeTarget(value.target)).put("role", value.role.name)
            .put("revision", value.revision).put("commitId", value.commitId).put("generation", value.generation)
            .put("scene", ConstructionJsonCodec.encodeScene(value.scene)).put("attached", value.attached).put("deleted", value.deleted)
            .put("student", value.student?.let(ConstructionSyncCodec::remoteJson) ?: JSONObject.NULL)
            .put("base", value.base?.let(ConstructionSyncCodec::remoteJson) ?: JSONObject.NULL)
            .put("recovery", value.recovery?.let(ConstructionSyncCodec::remoteJson) ?: JSONObject.NULL)
            .put("pending", value.pending?.let(ConstructionSyncCodec::toJson) ?: JSONObject.NULL)
            .put("receipts", JSONArray(value.receipts.map { receipt -> JSONObject().put("id", receipt.requestId)
                .put("hash", receipt.requestHash).put("result", receipt.result.name)
                .put("version", ConstructionSyncCodec.versionJson(receipt.version))
                .put("student", receipt.student?.let(ConstructionSyncCodec::remoteJson) ?: JSONObject.NULL)
                .also { json -> receipt.memoJson?.let { json.put("memoJson", it) } } }))
        // A published memo appears in the student shadow, common base and durable receipt.
        // Keep its exact text once inside this same atomic file; no external blob may be lost
        // between committing the scene and acknowledging a publication after process death.
        val memoBodies = linkedMapOf<String, String>()
        collectMemoBodies(json, memoBodies)
        if (memoBodies.isNotEmpty()) json.put("memoBodies", JSONObject(memoBodies as Map<*, *>))
        val body = json.toString()
        return JSONObject().put("formatVersion", if (memoBodies.isEmpty()) 1 else 2).put("body", body)
            .put("sha256", ConstructionSyncCodec.sha256(body.toByteArray(Charsets.UTF_8))).toString().toByteArray(Charsets.UTF_8)
    }

    private fun decode(bytes: ByteArray): Replica {
        val envelope = JSONObject(bytes.toString(Charsets.UTF_8))
        val format = envelope.exactLong("formatVersion")
        require(format == 1L || format == 2L) { "Unsupported construction replica format" }
        val body = envelope.getString("body")
        require(ConstructionSyncCodec.sha256(body.toByteArray(Charsets.UTF_8)) == envelope.getString("sha256"))
        val json = JSONObject(body)
        restoreMemoBodies(json, format)
        val t = json.getJSONObject("target")
        val target = ConstructionTarget(t.getString("bookId"), t.exactInt("pageNumber"), t.exactInt("attemptNo"), t.getString("memoId"), t.getString("ownerScope"))
        val receipts = json.getJSONArray("receipts").also { require(it.length() <= MAX_RECEIPTS) }
        val value = Replica(target, ConstructionReplicaRole.valueOf(json.getString("role")), json.exactLong("revision"), json.getString("commitId"), json.exactLong("generation"),
            ConstructionJsonCodec.decodeScene(json.getJSONObject("scene")), json.getBoolean("attached"),
            json.optionalRemote("student"), json.optionalRemote("base"), json.optionalRemote("recovery"),
            if (json.isNull("pending")) null else ConstructionSyncCodec.fromJson(json.getJSONObject("pending")), json.getBoolean("deleted"),
            (0 until receipts.length()).map { receipts.getJSONObject(it).let { item ->
                if (item.has("response")) receipt(item.getString("hash"), ConstructionSyncCodec.fromJson(item.getJSONObject("response")))
                else Receipt(item.getString("id"), item.getString("hash"), ConstructionPublishResult.valueOf(item.getString("result")),
                    ConstructionSyncCodec.version(item.getJSONObject("version")), item.optionalRemote("student"),
                    if (item.isNull("memoJson")) null else item.getString("memoJson"))
            } })
        require(value.revision > 0 && value.generation > 0)
        ConstructionSyncCodec.requireUuid(value.commitId)
        require(value.attached || value.scene == ConstructionScene())
        require(!value.deleted || !value.attached)
        if (value.role == ConstructionReplicaRole.STUDENT) {
            val student = requireNotNull(value.student)
            require(student.copy(memoStateKnown = false, memoJson = null) == localRemote(value, student.version.revision))
        }
        value.pending?.let { requirePacket(target, it, ConstructionPacketKind.PUBLISH) }
        require(value.receipts.map { it.requestId }.distinct().size == value.receipts.size)
        value.receipts.forEach {
            ConstructionSyncCodec.requireUuid(it.requestId)
            require(Regex("[0-9a-f]{64}").matches(it.requestHash))
            it.memoJson?.let(ConstructionSyncCodec::validateMemoText)
            if (it.result == ConstructionPublishResult.APPLIED) require(it.student == null)
            else require(it.student != null && it.student.version == it.version && it.memoJson == null)
        }
        return value
    }

    private fun JSONObject.optionalRemote(key: String): ConstructionRemoteScene? =
        if (isNull(key)) null else ConstructionSyncCodec.remote(getJSONObject(key))

    private fun collectMemoBodies(value: Any?, bodies: MutableMap<String, String>) {
        when (value) {
            is JSONObject -> {
                if (value.has("memoJson") && !value.isNull("memoJson")) {
                    val memo = value.get("memoJson")
                    require(memo is String)
                    ConstructionSyncCodec.validateMemoText(memo)
                    val digest = ConstructionSyncCodec.sha256(memo.toByteArray(Charsets.UTF_8))
                    require(bodies[digest] == null || bodies[digest] == memo) { "Memo body digest collision" }
                    bodies[digest] = memo
                    value.remove("memoJson")
                    value.put("memoBodySha256", digest)
                }
                value.keys().asSequence().toList().forEach { collectMemoBodies(value.get(it), bodies) }
            }
            is JSONArray -> (0 until value.length()).forEach { collectMemoBodies(value.get(it), bodies) }
        }
    }

    private fun restoreMemoBodies(root: JSONObject, format: Long) {
        val bodies = if (format == 2L) root.getJSONObject("memoBodies") else null
        if (format == 1L) require(!root.has("memoBodies")) { "Memo bodies require replica format 2" }
        val texts = linkedMapOf<String, String>()
        bodies?.keys()?.asSequence()?.forEach { digest ->
            require(texts.size < MAX_RECEIPTS + 8 && Regex("[0-9a-f]{64}").matches(digest))
            val text = bodies.get(digest)
            require(text is String)
            ConstructionSyncCodec.validateMemoText(text)
            require(ConstructionSyncCodec.sha256(text.toByteArray(Charsets.UTF_8)) == digest) { "Memo body checksum mismatch" }
            texts[digest] = text
        }
        if (format == 2L) require(texts.isNotEmpty()) { "Empty memo body table" }
        root.remove("memoBodies")
        val used = hashSetOf<String>()
        fun restore(value: Any?) {
            when (value) {
                is JSONObject -> {
                    if (value.has("memoBodySha256")) {
                        require(format == 2L && !value.has("memoJson")) { "Invalid memo body reference" }
                        val digest = value.get("memoBodySha256")
                        require(digest is String)
                        value.put("memoJson", requireNotNull(texts[digest]) { "Missing memo body" })
                        value.remove("memoBodySha256")
                        used += digest
                    }
                    value.keys().asSequence().toList().forEach { restore(value.get(it)) }
                }
                is JSONArray -> (0 until value.length()).forEach { restore(value.get(it)) }
            }
        }
        restore(root)
        require(used == texts.keys) { "Unreferenced memo body" }
    }

    private fun receipt(hash: String, response: ConstructionSyncPacket) = Receipt(
        response.requestId, hash, requireNotNull(response.result), requireNotNull(response.student).version,
        response.student.takeUnless { response.result == ConstructionPublishResult.APPLIED },
        requireNotNull(response.student).memoJson.takeIf { response.result == ConstructionPublishResult.APPLIED },
    )

    private fun receiptResponse(target: ConstructionTarget, request: ConstructionSyncPacket, receipt: Receipt): ConstructionSyncPacket {
        // APPLIED scene bytes are already present in the retried request, whose canonical hash matched.
        // Keep only its version and the exact applied memo, not another full geometry scene.
        val student = receipt.student ?: ConstructionRemoteScene(receipt.version,
            ConstructionJsonCodec.immutableScene(requireNotNull(request.scene)), deleted = false, attached = true,
            memoStateKnown = receipt.memoJson != null, memoJson = receipt.memoJson)
        ConstructionSyncCodec.validateRemote(student)
        return packet(target, ConstructionPacketKind.RESULT, request.requestId).copy(student = student, result = receipt.result)
    }

    private data class Receipt(val requestId: String, val requestHash: String,
        val result: ConstructionPublishResult, val version: ConstructionVersion, val student: ConstructionRemoteScene?,
        val memoJson: String? = null)
    private data class Replica(
        val target: ConstructionTarget, val role: ConstructionReplicaRole, val revision: Long,
        val commitId: String, val generation: Long, val scene: ConstructionScene, val attached: Boolean,
        val student: ConstructionRemoteScene? = null, val base: ConstructionRemoteScene? = null,
        val recovery: ConstructionRemoteScene? = null, val pending: ConstructionSyncPacket? = null,
        val deleted: Boolean = false, val receipts: List<Receipt> = emptyList(),
    )

    companion object {
        const val FEATURE_DIRECTORY = "construction-replicas-v1"
        // Bounded even with distinct historical receipt bodies. Exceeding this fails atomically;
        // receipts are never evicted because doing so could reapply an old publication.
        private const val MAX_FILE_BYTES = 128 * 1024 * 1024
        private const val MAX_RECEIPTS = 10_000
        private fun newId() = UUID.randomUUID().toString()
    }
}
