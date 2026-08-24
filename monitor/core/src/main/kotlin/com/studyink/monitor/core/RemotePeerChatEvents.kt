package com.studyink.monitor.core

/** Exact pairing boundary for chat history; a new pair never inherits an old pair's messages. */
data class RemotePeerChatScope(
    val pairId: String,
    val localDeviceId: String,
    val peerDeviceId: String,
) {
    init {
        validateOpaqueToken(pairId, "pairId")
        validateOpaqueToken(localDeviceId, "localDeviceId")
        validateOpaqueToken(peerDeviceId, "peerDeviceId")
        checkProtocol(localDeviceId != peerDeviceId, "peerDeviceId") {
            "must differ from localDeviceId"
        }
    }
}

enum class RemotePeerChatDirection {
    INCOMING,
    OUTGOING,
}

/** Immutable UI-facing chat item. Transport ciphertext and Bot API metadata never enter this bus. */
data class RemotePeerChatMessage(
    val transferId: String,
    val messageId: String,
    val senderDeviceId: String,
    val createdAtEpochMs: Long,
    val sentAtEpochMs: Long,
    val storedAtEpochMs: Long,
    val text: String,
    val direction: RemotePeerChatDirection,
    val isRead: Boolean,
) {
    init {
        validateOpaqueToken(transferId, "transferId")
        validateOpaqueToken(messageId, "messageId")
        validateOpaqueToken(senderDeviceId, "senderDeviceId")
        checkProtocol(createdAtEpochMs >= 0L, "createdAtEpochMs") { "must not be negative" }
        checkProtocol(sentAtEpochMs >= 0L, "sentAtEpochMs") { "must not be negative" }
        checkProtocol(storedAtEpochMs >= 0L, "storedAtEpochMs") { "must not be negative" }
        validateChatText(text)
        checkProtocol(direction != RemotePeerChatDirection.OUTGOING || isRead, "isRead") {
            "outgoing messages are always read locally"
        }
    }
}

/** Bounded process snapshot; the app journal remains the durable source of truth. */
class RemotePeerChatState(
    val scope: RemotePeerChatScope,
    recentMessages: List<RemotePeerChatMessage>,
    val retainedMessageCount: Int,
    val unreadCount: Int,
    val stateRevision: Long,
    val lastReadAtEpochMs: Long?,
) {
    val recentMessages: List<RemotePeerChatMessage> = recentMessages.toList()
    val latestMessage: RemotePeerChatMessage? get() = recentMessages.lastOrNull()

    init {
        checkProtocol(retainedMessageCount >= 0, "retainedMessageCount") {
            "must not be negative"
        }
        checkProtocol(this.recentMessages.size <= retainedMessageCount, "recentMessages") {
            "cannot exceed retainedMessageCount"
        }
        checkProtocol(
            this.recentMessages.size <= RemoteReviewLimits.MAX_CHAT_STATE_MESSAGES,
            "recentMessages",
        ) { "exceeds the process-state message limit" }
        checkProtocol(unreadCount in 0..retainedMessageCount, "unreadCount") {
            "must fit retainedMessageCount"
        }
        checkProtocol(stateRevision >= 0L, "stateRevision") { "must not be negative" }
        checkProtocol(lastReadAtEpochMs == null || lastReadAtEpochMs >= 0L, "lastReadAtEpochMs") {
            "must be null or non-negative"
        }
        checkProtocol(
            this.recentMessages.map(RemotePeerChatMessage::messageId).toSet().size ==
                this.recentMessages.size,
            "recentMessages",
        ) { "contains duplicate messageId values" }
        this.recentMessages.forEach { message ->
            val expectedSender = when (message.direction) {
                RemotePeerChatDirection.INCOMING -> scope.peerDeviceId
                RemotePeerChatDirection.OUTGOING -> scope.localDeviceId
            }
            checkProtocol(message.senderDeviceId == expectedSender, "senderDeviceId") {
                "does not match the pair scope"
            }
        }
    }
}

/**
 * Process-local state fan-out for badges and chat UI. Revisions prevent a delayed worker from
 * replacing newer journal state; listener failures cannot block other subscribers.
 */
object RemotePeerChatStateBus {
    private class Listener(
        val scope: RemotePeerChatScope?,
        val callback: (RemotePeerChatState) -> Unit,
    )

    private val lock = Any()
    private val states = linkedMapOf<RemotePeerChatScope, RemotePeerChatState>()
    private val listeners = linkedSetOf<Listener>()

    fun current(scope: RemotePeerChatScope): RemotePeerChatState? =
        synchronized(lock) { states[scope] }

    fun subscribe(
        scope: RemotePeerChatScope? = null,
        emitCurrent: Boolean = true,
        listener: (RemotePeerChatState) -> Unit,
    ): MonitorSubscription {
        val registration = Listener(scope, listener)
        val initial = synchronized(lock) {
            listeners += registration
            if (!emitCurrent) emptyList() else if (scope == null) {
                states.values.toList()
            } else {
                listOfNotNull(states[scope])
            }
        }
        initial.forEach { runCatching { listener(it) } }
        return MonitorSubscription { synchronized(lock) { listeners -= registration } }
    }

    /** Returns the number of matching listeners notified, or zero for a stale/equal revision. */
    fun publish(state: RemotePeerChatState): Int {
        val targets = synchronized(lock) {
            val current = states[state.scope]
            if (current != null && state.stateRevision <= current.stateRevision) return 0
            states.remove(state.scope)
            states[state.scope] = state
            while (states.size > MAX_RETAINED_SCOPES) states.remove(states.keys.first())
            listeners.filter { it.scope == null || it.scope == state.scope }
        }
        targets.forEach { runCatching { it.callback(state) } }
        return targets.size
    }

    fun clear(scope: RemotePeerChatScope) = synchronized(lock) { states.remove(scope) }

    /** Explicit teardown/test helper; ordinary pairing changes should clear only their exact scope. */
    fun clear() = synchronized(lock) { states.clear() }

    private const val MAX_RETAINED_SCOPES = 8
}
