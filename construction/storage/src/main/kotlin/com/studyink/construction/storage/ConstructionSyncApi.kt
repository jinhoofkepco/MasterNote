package com.studyink.construction.storage

import com.studyink.construction.core.ConstructionScene
import java.util.concurrent.CopyOnWriteArraySet

/** Both legacy local scenes and role-aware replicas expose the same editor contract. */
interface ConstructionSceneAccess {
    fun load(target: ConstructionTarget): ConstructionSceneSnapshot
    fun save(expected: ConstructionSceneSnapshot, scene: ConstructionScene): ConstructionSceneSnapshot
    fun addRestoreListener(listener: () -> Unit): AutoCloseable
}

enum class ConstructionReplicaRole { STUDENT, TEACHER }
enum class ConstructionConflictChoice { USE_TEACHER, USE_STUDENT }

data class ConstructionSyncUiState(
    val role: ConstructionReplicaRole,
    val available: Boolean = false,
    val busy: Boolean = false,
    val message: String = "",
    val conflictToken: String? = null,
    val canPublish: Boolean = false,
)

/** Authentication, fresh-state queries and network lifecycle belong to the app coordinator. */
interface ConstructionUiBridge {
    fun registerTarget(target: ConstructionTarget, role: ConstructionReplicaRole)
    fun state(target: ConstructionTarget): ConstructionSyncUiState
    fun addListener(target: ConstructionTarget, listener: () -> Unit): AutoCloseable
    fun requestPublish(target: ConstructionTarget)
    fun resolveConflict(target: ConstructionTarget, choice: ConstructionConflictChoice, expectedToken: String)
    fun sceneAccess(target: ConstructionTarget): ConstructionSceneAccess
}

object ConstructionUiBridgeProvider {
    @Volatile var bridge: ConstructionUiBridge? = null
}

data class ConstructionVersion(val generation: Long, val revision: Long, val digestSha256: String) {
    init {
        require(generation > 0 && revision >= 0)
        require(Regex("[0-9a-f]{64}").matches(digestSha256))
    }
}

data class ConstructionRemoteScene(
    val version: ConstructionVersion,
    val scene: ConstructionScene,
    val deleted: Boolean = false,
    val attached: Boolean = true,
)

enum class ConstructionPacketKind { REQUEST_STATE, STUDENT_SNAPSHOT, PUBLISH, RESULT }
enum class ConstructionPublishResult { APPLIED, CONFLICT, DELETED }

/** No book id or asserted role: the authenticated transport supplies the local target and role. */
data class ConstructionSyncPacket(
    val kind: ConstructionPacketKind,
    val requestId: String,
    val memoId: String,
    val pageNumber: Int,
    val attemptNo: Int,
    val student: ConstructionRemoteScene? = null,
    val expectedStudent: ConstructionVersion? = null,
    val scene: ConstructionScene? = null,
    val result: ConstructionPublishResult? = null,
)

class ConstructionReplicaSnapshot internal constructor(
    val target: ConstructionTarget,
    val role: ConstructionReplicaRole,
    val scene: ConstructionScene,
    val studentShadow: ConstructionRemoteScene?,
    val commonBase: ConstructionRemoteScene?,
    val draftDirty: Boolean,
    val pendingPublish: ConstructionSyncPacket?,
    val recoveryScene: ConstructionRemoteScene?,
    val deleted: Boolean,
    val attached: Boolean,
    internal val stateRevision: Long,
    internal val stateCommitId: String,
    internal val rootGeneration: Long,
    internal val rootIdentity: String,
)

data class ConstructionPublishPreparation(
    val snapshot: ConstructionReplicaSnapshot,
    val packet: ConstructionSyncPacket?,
    val conflict: Boolean,
)

enum class ConstructionReplicaChangeKind {
    LOCAL_EDIT, REMOTE_STUDENT, REMOTE_PUBLISH, PUBLISH_PREPARED, PUBLISH_RESULT,
    ADOPTED_STUDENT, DELETED, RESTORED,
}

data class ConstructionReplicaChange(
    val target: ConstructionTarget,
    val role: ConstructionReplicaRole,
    val kind: ConstructionReplicaChangeKind,
    val snapshot: ConstructionReplicaSnapshot,
)

/** Published only after the complete replica transaction is durably committed. */
object ConstructionReplicaChangeBus {
    private val listeners = CopyOnWriteArraySet<(ConstructionReplicaChange) -> Unit>()
    fun addListener(listener: (ConstructionReplicaChange) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }
    internal fun publish(change: ConstructionReplicaChange) {
        listeners.forEach { runCatching { it(change) } }
    }
}
