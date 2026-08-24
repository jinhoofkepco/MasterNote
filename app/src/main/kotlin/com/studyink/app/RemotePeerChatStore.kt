package com.studyink.app

import com.studyink.monitor.core.ChatMessageEnvelope
import com.studyink.monitor.core.RemotePeerChatDirection
import com.studyink.monitor.core.RemotePeerChatMessage
import com.studyink.monitor.core.RemotePeerChatScope
import com.studyink.monitor.core.RemotePeerChatState
import com.studyink.monitor.core.RemoteReviewLimits
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.max

internal enum class RemotePeerChatRecordDisposition {
    STORED,
    DUPLICATE,
    CONFLICT,
}

internal data class RemotePeerChatRecordResult(
    val disposition: RemotePeerChatRecordDisposition,
    /** Null only when a duplicate is older than the retained visible history. */
    val message: RemotePeerChatMessage?,
    val state: RemotePeerChatState,
)

/**
 * Durable post-decryption chat history. Telegram owns authenticated AES-GCM transport and retry;
 * this journal owns only bounded local history, dedupe and read state for one exact pair scope.
 *
 * Each mutation is fsynced before it becomes observable. A malformed or partial journal record is
 * ignored on replay, and compaction atomically replaces the append log. Callers must acknowledge an
 * incoming peer document only after [recordIncoming] returns STORED or DUPLICATE.
 */
internal class RemotePeerChatStore(
    private val root: File,
    private val maxScopes: Int = DEFAULT_MAX_SCOPES,
    private val maxMessagesPerScope: Int = DEFAULT_MAX_MESSAGES_PER_SCOPE,
    private val maxSeenPerScope: Int = DEFAULT_MAX_SEEN_PER_SCOPE,
    private val stateMessageLimit: Int = DEFAULT_STATE_MESSAGE_LIMIT,
    private val compactAfterRecords: Int = DEFAULT_COMPACT_AFTER_RECORDS,
    private val maximumJournalBytes: Long = DEFAULT_MAXIMUM_JOURNAL_BYTES,
) {
    private data class StoredMessage(
        val sequence: Long,
        val transferId: String,
        val messageId: String,
        val senderDeviceId: String,
        val createdAtEpochMs: Long,
        val sentAtEpochMs: Long,
        val storedAtEpochMs: Long,
        val text: String,
        val direction: RemotePeerChatDirection,
    )

    private data class SeenMessage(
        val sequence: Long,
        val transferId: String,
        val messageId: String,
        val fingerprint: String,
    )

    private class ScopeData(val scope: RemotePeerChatScope) {
        val messages = linkedMapOf<String, StoredMessage>()
        val seenByMessage = linkedMapOf<String, SeenMessage>()
        val messageIdByTransfer = linkedMapOf<String, String>()
        var readThroughSequence: Long = 0L
        var lastReadAtEpochMs: Long? = null
        var stateRevision: Long = 0L
        var lastActivitySequence: Long = 0L
    }

    private val journal = root.resolve(JOURNAL_NAME)
    /** Insertion order is rewritten to last-activity order after replay and every stored message. */
    private val scopes = linkedMapOf<RemotePeerChatScope, ScopeData>()
    private var nextSequence = 1L
    private var appendedRecords = 0

    init {
        require(maxScopes > 0)
        require(maxMessagesPerScope > 0)
        require(maxSeenPerScope >= maxMessagesPerScope)
        require(
            stateMessageLimit in 1..minOf(
                maxMessagesPerScope,
                RemoteReviewLimits.MAX_CHAT_STATE_MESSAGES,
            ),
        )
        require(compactAfterRecords > 0 && maximumJournalBytes > 0L)
        require(root.mkdirs() || root.isDirectory) { "Cannot create peer-chat directory" }
        root.resolve("$JOURNAL_NAME.compact").delete()
        replay()
    }

    @Synchronized
    fun recordIncoming(
        scope: RemotePeerChatScope,
        envelope: ChatMessageEnvelope,
        receivedAtEpochMs: Long,
    ): RemotePeerChatRecordResult = record(
        scope = scope,
        envelope = envelope,
        direction = RemotePeerChatDirection.INCOMING,
        storedAtEpochMs = receivedAtEpochMs,
    )

    @Synchronized
    fun recordOutgoing(
        scope: RemotePeerChatScope,
        envelope: ChatMessageEnvelope,
        queuedAtEpochMs: Long,
    ): RemotePeerChatRecordResult = record(
        scope = scope,
        envelope = envelope,
        direction = RemotePeerChatDirection.OUTGOING,
        storedAtEpochMs = queuedAtEpochMs,
    )

    @Synchronized
    fun messages(
        scope: RemotePeerChatScope,
        limit: Int = maxMessagesPerScope,
    ): List<RemotePeerChatMessage> {
        require(limit in 1..maxMessagesPerScope)
        val data = scopes[scope] ?: return emptyList()
        return data.messages.values.toList().takeLast(limit).map {
            it.toPublic(data.readThroughSequence)
        }
    }

    @Synchronized
    fun state(
        scope: RemotePeerChatScope,
        recentLimit: Int = stateMessageLimit,
    ): RemotePeerChatState {
        require(recentLimit in 1..minOf(maxMessagesPerScope, RemoteReviewLimits.MAX_CHAT_STATE_MESSAGES))
        return stateLocked(scope, recentLimit)
    }

    /** Marks every retained/seen incoming message up to [throughMessageId], or all current history. */
    @Synchronized
    fun markRead(
        scope: RemotePeerChatScope,
        throughMessageId: String? = null,
        readAtEpochMs: Long,
    ): RemotePeerChatState {
        require(readAtEpochMs >= 0L)
        val data = scopes[scope] ?: return stateLocked(scope, stateMessageLimit)
        val targetSequence = throughMessageId?.let { messageId ->
            requireNotNull(data.seenByMessage[messageId]) {
                "throughMessageId does not belong to this retained pair scope"
            }.sequence
        } ?: data.lastActivitySequence
        if (targetSequence <= data.readThroughSequence) return stateLocked(scope, stateMessageLimit)

        val nextRevision = nextRevision(data)
        val committedReadAt = max(data.lastReadAtEpochMs ?: 0L, readAtEpochMs)
        append(
            encodeRead(
                scope = scope,
                readThroughSequence = targetSequence,
                readAtEpochMs = committedReadAt,
                stateRevision = nextRevision,
                lastActivitySequence = data.lastActivitySequence,
            ),
        )
        data.readThroughSequence = targetSequence
        data.lastReadAtEpochMs = committedReadAt
        data.stateRevision = nextRevision
        compactIfNeeded()
        return stateLocked(scope, stateMessageLimit)
    }

    /** Removes exactly one cryptographic pairing's history and duplicate guards. */
    @Synchronized
    fun clear(scope: RemotePeerChatScope): Boolean {
        val removed = scopes.remove(scope) ?: return false
        return try {
            compactIfNeeded(force = true)
            true
        } catch (error: Throwable) {
            scopes[scope] = removed
            reorderScopes()
            throw error
        }
    }

    /** Pair-id teardown helper; device ids still remain part of every normal lookup. */
    @Synchronized
    fun clearPair(pairId: String): Int {
        require(PROTOCOL_ID.matches(pairId)) { "Invalid pairId" }
        val removed = scopes.filterKeys { it.pairId == pairId }
        if (removed.isEmpty()) return 0
        removed.keys.forEach(scopes::remove)
        return try {
            compactIfNeeded(force = true)
            removed.size
        } catch (error: Throwable) {
            scopes.putAll(removed)
            reorderScopes()
            throw error
        }
    }

    @Synchronized
    fun retainedScopes(): List<RemotePeerChatScope> = scopes.keys.toList()

    private fun record(
        scope: RemotePeerChatScope,
        envelope: ChatMessageEnvelope,
        direction: RemotePeerChatDirection,
        storedAtEpochMs: Long,
    ): RemotePeerChatRecordResult {
        require(storedAtEpochMs >= 0L)
        val expectedSender = when (direction) {
            RemotePeerChatDirection.INCOMING -> scope.peerDeviceId
            RemotePeerChatDirection.OUTGOING -> scope.localDeviceId
        }
        require(envelope.senderDeviceId == expectedSender) {
            "Chat sender does not match the exact pair scope"
        }
        val existingData = scopes[scope]
        val data = existingData ?: ScopeData(scope)
        val fingerprint = fingerprint(envelope, direction)
        val byMessage = data.seenByMessage[envelope.messageId]
        val transferMessageId = data.messageIdByTransfer[envelope.transferId]
        val conflict = byMessage?.fingerprint?.let { it != fingerprint } == true ||
            transferMessageId?.let { it != envelope.messageId } == true
        if (conflict) {
            return RemotePeerChatRecordResult(
                disposition = RemotePeerChatRecordDisposition.CONFLICT,
                message = data.messages[envelope.messageId]?.toPublic(data.readThroughSequence),
                state = stateLocked(scope, stateMessageLimit),
            )
        }
        if (byMessage != null || transferMessageId != null) {
            return RemotePeerChatRecordResult(
                disposition = RemotePeerChatRecordDisposition.DUPLICATE,
                message = data.messages[envelope.messageId]?.toPublic(data.readThroughSequence),
                state = stateLocked(scope, stateMessageLimit),
            )
        }

        check(nextSequence < Long.MAX_VALUE) { "Peer-chat sequence exhausted" }
        val sequence = nextSequence
        val nextRevision = nextRevision(data)
        val stored = StoredMessage(
            sequence = sequence,
            transferId = envelope.transferId,
            messageId = envelope.messageId,
            senderDeviceId = envelope.senderDeviceId,
            createdAtEpochMs = envelope.createdAtEpochMs,
            sentAtEpochMs = envelope.sentAtEpochMs,
            storedAtEpochMs = storedAtEpochMs,
            text = envelope.text,
            direction = direction,
        )
        append(encodeMessage(scope, stored, nextRevision, fingerprint))

        if (existingData == null) scopes[scope] = data
        nextSequence = sequence + 1L
        data.messages[stored.messageId] = stored
        rememberSeen(data, SeenMessage(sequence, stored.transferId, stored.messageId, fingerprint))
        data.stateRevision = nextRevision
        data.lastActivitySequence = sequence
        trimScopeData(data)
        touchScope(scope, data)
        val removedScope = trimScopes()
        compactIfNeeded(force = removedScope)
        return RemotePeerChatRecordResult(
            disposition = RemotePeerChatRecordDisposition.STORED,
            message = stored.toPublic(data.readThroughSequence),
            state = stateLocked(scope, stateMessageLimit),
        )
    }

    private fun replay() {
        if (!journal.isFile) return
        journal.forEachLine(StandardCharsets.UTF_8) { line ->
            appendedRecords++
            runCatching { replayLine(line) }
        }
        scopes.values.forEach(::trimScopeData)
        reorderScopes()
        val removedScope = trimScopes()
        compactIfNeeded(force = removedScope)
    }

    private fun replayLine(line: String) {
        require(line.length <= MAX_JOURNAL_LINE_CHARS)
        val fields = line.split('\t')
        require(fields.firstOrNull() == VERSION)
        when (fields.getOrNull(1)) {
            MESSAGE_RECORD -> replayMessage(fields)
            READ_RECORD -> replayRead(fields)
            META_RECORD -> replayMeta(fields)
            SEEN_RECORD -> replaySeen(fields)
            else -> error("Unknown peer-chat journal record")
        }
    }

    private fun replayMessage(fields: List<String>) {
        require(fields.size == 16)
        val scope = decodeScope(fields)
        val sequence = boundedPositiveLong(fields[5])
        val revision = boundedPositiveLong(fields[6])
        val envelope = ChatMessageEnvelope(
            transferId = decode(fields[7]),
            createdAtEpochMs = nonNegativeLong(fields[10]),
            messageId = decode(fields[8]),
            senderDeviceId = decode(fields[9]),
            sentAtEpochMs = nonNegativeLong(fields[11]),
            text = decode(fields[14]),
        )
        val stored = StoredMessage(
            sequence = sequence,
            transferId = envelope.transferId,
            messageId = envelope.messageId,
            senderDeviceId = envelope.senderDeviceId,
            createdAtEpochMs = envelope.createdAtEpochMs,
            sentAtEpochMs = envelope.sentAtEpochMs,
            storedAtEpochMs = nonNegativeLong(fields[12]),
            text = envelope.text,
            direction = RemotePeerChatDirection.valueOf(fields[13]),
        )
        val expectedSender = when (stored.direction) {
            RemotePeerChatDirection.INCOMING -> scope.peerDeviceId
            RemotePeerChatDirection.OUTGOING -> scope.localDeviceId
        }
        require(stored.senderDeviceId == expectedSender)
        val expectedFingerprint = fingerprint(envelope, stored.direction)
        require(SHA256_HEX.matches(fields[15]) && fields[15] == expectedFingerprint)
        val data = scopes.getOrPut(scope) { ScopeData(scope) }
        val prior = data.seenByMessage[stored.messageId]
        require(
            prior == null || prior.fingerprint == expectedFingerprint &&
                prior.transferId == stored.transferId && prior.sequence == stored.sequence,
        )
        data.messages[stored.messageId] = stored
        if (prior == null) {
            rememberSeen(
                data,
                SeenMessage(sequence, stored.transferId, stored.messageId, expectedFingerprint),
            )
        }
        data.stateRevision = max(data.stateRevision, revision)
        data.lastActivitySequence = max(data.lastActivitySequence, sequence)
        advanceSequence(sequence)
    }

    private fun replayRead(fields: List<String>) {
        require(fields.size == 9)
        val scope = decodeScope(fields)
        val readThroughSequence = boundedNonNegativeLong(fields[5])
        val readAtEpochMs = nonNegativeLong(fields[6])
        val revision = boundedPositiveLong(fields[7])
        val lastActivitySequence = boundedNonNegativeLong(fields[8])
        require(readThroughSequence <= lastActivitySequence)
        val data = scopes.getOrPut(scope) { ScopeData(scope) }
        data.readThroughSequence = max(data.readThroughSequence, readThroughSequence)
        data.lastReadAtEpochMs = max(data.lastReadAtEpochMs ?: 0L, readAtEpochMs)
        data.stateRevision = max(data.stateRevision, revision)
        data.lastActivitySequence = max(data.lastActivitySequence, lastActivitySequence)
        advanceSequence(data.lastActivitySequence)
    }

    private fun replayMeta(fields: List<String>) {
        require(fields.size == 9)
        val scope = decodeScope(fields)
        val readThroughSequence = boundedNonNegativeLong(fields[5])
        val lastReadAtEpochMs = decode(fields[6]).takeIf(String::isNotEmpty)?.let {
            nonNegativeLong(it)
        }
        val revision = boundedPositiveLong(fields[7])
        val lastActivitySequence = boundedNonNegativeLong(fields[8])
        require(readThroughSequence <= lastActivitySequence)
        val data = scopes.getOrPut(scope) { ScopeData(scope) }
        data.readThroughSequence = max(data.readThroughSequence, readThroughSequence)
        lastReadAtEpochMs?.let { readAt ->
            data.lastReadAtEpochMs = max(data.lastReadAtEpochMs ?: 0L, readAt)
        }
        data.stateRevision = max(data.stateRevision, revision)
        data.lastActivitySequence = max(data.lastActivitySequence, lastActivitySequence)
        advanceSequence(data.lastActivitySequence)
    }

    private fun replaySeen(fields: List<String>) {
        require(fields.size == 9)
        val scope = decodeScope(fields)
        val sequence = boundedPositiveLong(fields[5])
        val transferId = decode(fields[6]).also { require(PROTOCOL_ID.matches(it)) }
        val messageId = decode(fields[7]).also { require(PROTOCOL_ID.matches(it)) }
        val seen = SeenMessage(
            sequence = sequence,
            transferId = transferId,
            messageId = messageId,
            fingerprint = fields[8].also { require(SHA256_HEX.matches(it)) },
        )
        val data = scopes.getOrPut(scope) { ScopeData(scope) }
        rememberSeen(data, seen)
        data.lastActivitySequence = max(data.lastActivitySequence, sequence)
        advanceSequence(sequence)
    }

    private fun stateLocked(scope: RemotePeerChatScope, recentLimit: Int): RemotePeerChatState {
        val data = scopes[scope]
        if (data == null) {
            return RemotePeerChatState(
                scope = scope,
                recentMessages = emptyList(),
                retainedMessageCount = 0,
                unreadCount = 0,
                stateRevision = 0L,
                lastReadAtEpochMs = null,
            )
        }
        val retained = data.messages.values
        val unread = retained.count {
            it.direction == RemotePeerChatDirection.INCOMING && it.sequence > data.readThroughSequence
        }
        return RemotePeerChatState(
            scope = scope,
            recentMessages = retained.toList().takeLast(recentLimit).map {
                it.toPublic(data.readThroughSequence)
            },
            retainedMessageCount = retained.size,
            unreadCount = unread,
            stateRevision = data.stateRevision,
            lastReadAtEpochMs = data.lastReadAtEpochMs,
        )
    }

    private fun StoredMessage.toPublic(readThroughSequence: Long): RemotePeerChatMessage =
        RemotePeerChatMessage(
            transferId = transferId,
            messageId = messageId,
            senderDeviceId = senderDeviceId,
            createdAtEpochMs = createdAtEpochMs,
            sentAtEpochMs = sentAtEpochMs,
            storedAtEpochMs = storedAtEpochMs,
            text = text,
            direction = direction,
            isRead = direction == RemotePeerChatDirection.OUTGOING || sequence <= readThroughSequence,
        )

    private fun rememberSeen(data: ScopeData, seen: SeenMessage) {
        data.seenByMessage[seen.messageId]?.let { require(it == seen) }
        data.messageIdByTransfer[seen.transferId]?.let { require(it == seen.messageId) }
        data.seenByMessage[seen.messageId] = seen
        data.messageIdByTransfer[seen.transferId] = seen.messageId
        while (data.seenByMessage.size > maxSeenPerScope) {
            val removed = data.seenByMessage.remove(data.seenByMessage.keys.first()) ?: continue
            if (data.messageIdByTransfer[removed.transferId] == removed.messageId) {
                data.messageIdByTransfer.remove(removed.transferId)
            }
        }
    }

    private fun trimScopeData(data: ScopeData) {
        while (data.messages.size > maxMessagesPerScope) {
            data.messages.remove(data.messages.keys.first())
        }
        while (data.seenByMessage.size > maxSeenPerScope) {
            val removed = data.seenByMessage.remove(data.seenByMessage.keys.first()) ?: continue
            if (data.messageIdByTransfer[removed.transferId] == removed.messageId) {
                data.messageIdByTransfer.remove(removed.transferId)
            }
        }
    }

    private fun touchScope(scope: RemotePeerChatScope, data: ScopeData) {
        scopes.remove(scope)
        scopes[scope] = data
    }

    private fun reorderScopes() {
        val ordered = scopes.entries.sortedBy { it.value.lastActivitySequence }
        scopes.clear()
        ordered.forEach { scopes[it.key] = it.value }
    }

    private fun trimScopes(): Boolean {
        var removed = false
        while (scopes.size > maxScopes) {
            scopes.remove(scopes.keys.first())
            removed = true
        }
        return removed
    }

    private fun append(line: String) {
        FileOutputStream(journal, true).use { output ->
            output.write(line.toByteArray(StandardCharsets.UTF_8))
            output.write('\n'.code)
            output.flush()
            output.fd.sync()
        }
        appendedRecords++
    }

    private fun compactIfNeeded(force: Boolean = false) {
        val liveRecords = scopes.values.sumOf { data ->
            1 + data.seenByMessage.size + data.messages.size
        }
        val recordPressure = appendedRecords >= compactAfterRecords &&
            appendedRecords >= liveRecords.coerceAtLeast(1) * 2
        if (!force && !recordPressure && journal.length() <= maximumJournalBytes) return
        val lines = buildList {
            scopes.values.forEach { data ->
                add(encodeMeta(data))
                // Persist dedupe records first so replay preserves their true oldest-to-newest
                // retention order even when only the newest subset still has visible messages.
                data.seenByMessage.values.forEach { seen -> add(encodeSeen(data.scope, seen)) }
                data.messages.values.forEach { message ->
                    val seen = requireNotNull(data.seenByMessage[message.messageId])
                    add(encodeMessage(data.scope, message, data.stateRevision, seen.fingerprint))
                }
            }
        }
        val temporary = root.resolve("$JOURNAL_NAME.compact")
        try {
            FileOutputStream(temporary, false).use { output ->
                lines.forEach { line ->
                    output.write(line.toByteArray(StandardCharsets.UTF_8))
                    output.write('\n'.code)
                }
                output.flush()
                output.fd.sync()
            }
            atomicReplace(temporary, journal)
            appendedRecords = lines.size
        } finally {
            temporary.delete()
        }
    }

    private fun encodeMessage(
        scope: RemotePeerChatScope,
        message: StoredMessage,
        stateRevision: Long,
        fingerprint: String,
    ): String = listOf(
        VERSION,
        MESSAGE_RECORD,
        encode(scope.pairId),
        encode(scope.localDeviceId),
        encode(scope.peerDeviceId),
        message.sequence.toString(),
        stateRevision.toString(),
        encode(message.transferId),
        encode(message.messageId),
        encode(message.senderDeviceId),
        message.createdAtEpochMs.toString(),
        message.sentAtEpochMs.toString(),
        message.storedAtEpochMs.toString(),
        message.direction.name,
        encode(message.text),
        fingerprint,
    ).joinToString("\t")

    private fun encodeRead(
        scope: RemotePeerChatScope,
        readThroughSequence: Long,
        readAtEpochMs: Long,
        stateRevision: Long,
        lastActivitySequence: Long,
    ): String = listOf(
        VERSION,
        READ_RECORD,
        encode(scope.pairId),
        encode(scope.localDeviceId),
        encode(scope.peerDeviceId),
        readThroughSequence.toString(),
        readAtEpochMs.toString(),
        stateRevision.toString(),
        lastActivitySequence.toString(),
    ).joinToString("\t")

    private fun encodeMeta(data: ScopeData): String = listOf(
        VERSION,
        META_RECORD,
        encode(data.scope.pairId),
        encode(data.scope.localDeviceId),
        encode(data.scope.peerDeviceId),
        data.readThroughSequence.toString(),
        encode(data.lastReadAtEpochMs?.toString().orEmpty()),
        data.stateRevision.toString(),
        data.lastActivitySequence.toString(),
    ).joinToString("\t")

    private fun encodeSeen(scope: RemotePeerChatScope, seen: SeenMessage): String = listOf(
        VERSION,
        SEEN_RECORD,
        encode(scope.pairId),
        encode(scope.localDeviceId),
        encode(scope.peerDeviceId),
        seen.sequence.toString(),
        encode(seen.transferId),
        encode(seen.messageId),
        seen.fingerprint,
    ).joinToString("\t")

    private fun decodeScope(fields: List<String>): RemotePeerChatScope = RemotePeerChatScope(
        pairId = decode(fields[2]),
        localDeviceId = decode(fields[3]),
        peerDeviceId = decode(fields[4]),
    )

    private fun fingerprint(
        envelope: ChatMessageEnvelope,
        direction: RemotePeerChatDirection,
    ): String {
        val material = buildString {
            append(envelope.senderDeviceId).append('\u0000')
            append(envelope.createdAtEpochMs).append('\u0000')
            append(envelope.sentAtEpochMs).append('\u0000')
            append(direction.name).append('\u0000')
            append(envelope.text)
        }.toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(material).toHex()
    }

    private fun nextRevision(data: ScopeData): Long {
        check(data.stateRevision < Long.MAX_VALUE) { "Peer-chat revision exhausted" }
        return data.stateRevision + 1L
    }

    private fun advanceSequence(value: Long) {
        if (value < nextSequence) return
        check(value < Long.MAX_VALUE) { "Peer-chat sequence exhausted" }
        nextSequence = value + 1L
    }

    private fun nonNegativeLong(value: String): Long = value.toLong().also { require(it >= 0L) }
    private fun boundedPositiveLong(value: String): Long = value.toLong().also {
        require(it in 1 until Long.MAX_VALUE)
    }
    private fun boundedNonNegativeLong(value: String): Long = value.toLong().also {
        require(it in 0 until Long.MAX_VALUE)
    }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String = Base64.getUrlDecoder().decode(value)
        .toString(StandardCharsets.UTF_8)

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private fun atomicReplace(source: File, target: File) {
        runCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val VERSION = "PC1"
        const val JOURNAL_NAME = "peer-chat.v1"
        const val MESSAGE_RECORD = "MSG"
        const val READ_RECORD = "READ"
        const val META_RECORD = "META"
        const val SEEN_RECORD = "SEEN"
        const val DEFAULT_MAX_SCOPES = 4
        const val DEFAULT_MAX_MESSAGES_PER_SCOPE = 128
        const val DEFAULT_MAX_SEEN_PER_SCOPE = 512
        const val DEFAULT_STATE_MESSAGE_LIMIT = 50
        const val DEFAULT_COMPACT_AFTER_RECORDS = 4_096
        const val DEFAULT_MAXIMUM_JOURNAL_BYTES = 8L * 1_024L * 1_024L
        const val MAX_JOURNAL_LINE_CHARS = 12_000
        val PROTOCOL_ID = Regex("[A-Za-z0-9_-]{8,128}")
        val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}
