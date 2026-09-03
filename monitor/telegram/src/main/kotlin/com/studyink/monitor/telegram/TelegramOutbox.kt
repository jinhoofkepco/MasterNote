package com.studyink.monitor.telegram

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

internal data class TelegramLatestEnqueueOutcome(
    val result: TelegramEnqueueResult,
    val supersededEntries: List<TelegramOutboxEntry> = emptyList(),
)

/**
 * Process-local claim lanes over one durable journal. Keeping the journal shared lets an app
 * update immediately routes already-persisted CONNECT_REQUEST/CONNECT_ACCEPT/PING/PONG entries
 * through the priority transport without migrating or duplicating durable state.
 */
internal enum class TelegramOutboxLane {
    ALL,
    REGULAR,
    PRIORITY_PEER_CONTROL,
}

internal fun isPriorityPeerControl(entry: TelegramOutboxEntry): Boolean =
    entry.route == TelegramOutboxRoute.PEER &&
        entry.kind == TelegramOutboxKind.TEXT &&
        entry.idempotencyKey.startsWith(PEER_LINK_CONTROL_KEY_PREFIX)

private fun TelegramOutboxLane.accepts(entry: TelegramOutboxEntry): Boolean = when (this) {
    TelegramOutboxLane.ALL -> true
    TelegramOutboxLane.REGULAR -> !isPriorityPeerControl(entry)
    TelegramOutboxLane.PRIORITY_PEER_CONTROL -> isPriorityPeerControl(entry)
}

/**
 * Durable append journal adapted from FocusMonitor2 DiskUploadQueue at commit e5809ebc.
 * Stable caller keys survive process death; DONE and DEAD records prevent accidental resends.
 */
class TelegramOutbox(
    private val journal: File,
    private val maxPendingEntries: Int = DEFAULT_MAX_PENDING,
    private val reservedPeerTextEntries: Int = DEFAULT_RESERVED_PEER_TEXT_ENTRIES,
    private val reservedPriorityPeerControlEntries: Int = DEFAULT_RESERVED_PRIORITY_PEER_CONTROL_ENTRIES,
) {
    private val pending = linkedMapOf<String, TelegramOutboxEntry>()
    private val delivered = linkedMapOf<String, Long>()
    private val dead = linkedMapOf<String, TelegramDeadLetter>()
    private val superseded = linkedMapOf<String, Long>()
    private val inFlight = mutableSetOf<String>()
    private var appendedRecords = 0

    init {
        require(maxPendingEntries > 0)
        require(reservedPeerTextEntries >= 0)
        require(reservedPriorityPeerControlEntries >= 0)
        require(journal.parentFile?.mkdirs() == true || journal.parentFile?.isDirectory == true)
        replay()
    }

    @Synchronized
    fun enqueue(entry: TelegramOutboxEntry): TelegramEnqueueResult {
        validate(entry)
        priorResult(entry.idempotencyKey)?.let { return it }
        if (!hasCapacityFor(entry)) return TelegramEnqueueResult.QUEUE_FULL
        append(encodePut(entry))
        pending[entry.idempotencyKey] = entry
        compactIfNeeded()
        return TelegramEnqueueResult.ENQUEUED
    }

    /**
     * Link controls must still fit when a long-offline device has filled the ordinary data queue.
     * If the small priority lane itself is full, the oldest non-in-flight CONNECT/PING probe is
     * durably superseded. CONNECT_ACCEPT/PONG responses are never displaced: when only responses
     * remain, the inbound poller retries its current update after one of them is sent.
     */
    @Synchronized
    fun enqueuePeerLinkControl(entry: TelegramOutboxEntry): TelegramEnqueueResult {
        validate(entry)
        require(isReservedPeerText(entry))
        priorResult(entry.idempotencyKey)?.let { return it }
        if (isPriorityPeerControl(entry)) {
            if (reservedPriorityPeerControlEntries == 0) return TelegramEnqueueResult.QUEUE_FULL
            if (priorityPeerControlCount() >= reservedPriorityPeerControlEntries) {
                val replaceable = pending.values.asSequence()
                    .filter(::isPriorityPeerControl)
                    .filter { it.idempotencyKey !in inFlight }
                    .filter(::isReplaceablePeerProbe)
                    .minWithOrNull(
                        compareBy<TelegramOutboxEntry>(
                            ::priorityProbeReplacementPriority,
                            TelegramOutboxEntry::createdAtEpochMs,
                        ),
                    ) ?: return TelegramEnqueueResult.QUEUE_FULL
                append(encodeSuperseded(replaceable.idempotencyKey, entry.createdAtEpochMs))
                pending.remove(replaceable.idempotencyKey)
                rememberBounded(
                    superseded,
                    replaceable.idempotencyKey,
                    entry.createdAtEpochMs,
                    MAX_SUPERSEDED_KEYS,
                )
            }
            append(encodePut(entry))
            pending[entry.idempotencyKey] = entry
            compactIfNeeded()
            return TelegramEnqueueResult.ENQUEUED
        }
        if (reservedPeerTextEntries == 0) return TelegramEnqueueResult.QUEUE_FULL
        if (regularReservedPeerTextCount() >= reservedPeerTextEntries) return TelegramEnqueueResult.QUEUE_FULL
        append(encodePut(entry))
        pending[entry.idempotencyKey] = entry
        compactIfNeeded()
        return TelegramEnqueueResult.ENQUEUED
    }

    /**
     * Atomically supersedes an older pending text with the same coalesce key. A message already
     * claimed by the sender is left alone; delivered and dead-letter records are never modified.
     */
    @Synchronized
    fun enqueueLatestText(entry: TelegramOutboxEntry): TelegramEnqueueResult {
        require(entry.kind == TelegramOutboxKind.TEXT)
        return enqueueLatest(entry).result
    }

    /**
     * Atomically installs the latest unsent text/document for one semantic stream. Exact replaced
     * keys are journalled so a restart cannot accidentally remove an in-flight/server-accepted
     * predecessor which the caller protected.
     */
    @Synchronized
    internal fun enqueueLatest(
        entry: TelegramOutboxEntry,
        immutableKeys: Set<String> = emptySet(),
    ): TelegramLatestEnqueueOutcome {
        validate(entry)
        require(entry.kind == TelegramOutboxKind.TEXT || entry.kind == TelegramOutboxKind.DOCUMENT)
        require(!entry.coalesceKey.isNullOrBlank())
        priorResult(entry.idempotencyKey)?.let { return TelegramLatestEnqueueOutcome(it) }
        val replaceable = pending.values.filter {
            it.coalesceKey == entry.coalesceKey && !isImmutableLatestEntry(it) &&
                it.idempotencyKey !in immutableKeys
        }
        if (regularPendingCount() - replaceable.size >= maxPendingEntries) {
            return TelegramLatestEnqueueOutcome(TelegramEnqueueResult.QUEUE_FULL)
        }
        append(encodeLatest(entry, replaceable.map(TelegramOutboxEntry::idempotencyKey)))
        replaceable.forEach {
            pending.remove(it.idempotencyKey)
            rememberBounded(
                superseded,
                it.idempotencyKey,
                entry.createdAtEpochMs,
                MAX_SUPERSEDED_KEYS,
            )
        }
        pending[entry.idempotencyKey] = entry
        compactIfNeeded()
        return TelegramLatestEnqueueOutcome(TelegramEnqueueResult.ENQUEUED, replaceable)
    }

    @Synchronized
    internal fun replaceableLatestEntries(
        coalesceKey: String,
        immutableKeys: Set<String> = emptySet(),
    ): List<TelegramOutboxEntry> = pending.values.filter {
        it.coalesceKey == coalesceKey && !isImmutableLatestEntry(it) &&
            it.idempotencyKey !in immutableKeys
    }

    /**
     * Durably cancels every pending entry in one latest-wins stream. Removing in-flight entries is
     * intentional: a request already accepted by Telegram cannot be recalled, but a failed or
     * interrupted request must not be retried after student activity has resumed.
     */
    @Synchronized
    fun cancelCoalesced(coalesceKey: String, cancelledAtEpochMs: Long): Int {
        require(coalesceKey.isNotBlank() && coalesceKey.length <= MAX_COALESCE_KEY_CHARS)
        require(cancelledAtEpochMs >= 0L)
        val cancelled = pending.values.filter { it.coalesceKey == coalesceKey }
        if (cancelled.isEmpty()) return 0
        append(encodeCancelCoalesced(coalesceKey, cancelledAtEpochMs))
        cancelled.forEach { entry ->
            pending.remove(entry.idempotencyKey)
            inFlight.remove(entry.idempotencyKey)
            rememberBounded(
                superseded,
                entry.idempotencyKey,
                cancelledAtEpochMs,
                MAX_SUPERSEDED_KEYS,
            )
        }
        compactIfNeeded()
        return cancelled.size
    }

    /** Removes unsent documents/control messages addressed to a previous pinned peer. */
    @Synchronized
    fun cancelPeerEntries(cancelledAtEpochMs: Long): List<TelegramOutboxEntry> {
        require(cancelledAtEpochMs >= 0L)
        val cancelled = pending.values.filter { it.route == TelegramOutboxRoute.PEER }
        cancelled.forEach { entry ->
            append(encodeSuperseded(entry.idempotencyKey, cancelledAtEpochMs))
            pending.remove(entry.idempotencyKey)
            inFlight.remove(entry.idempotencyKey)
            rememberBounded(superseded, entry.idempotencyKey, cancelledAtEpochMs, MAX_SUPERSEDED_KEYS)
        }
        compactIfNeeded()
        return cancelled
    }

    /**
     * Durably cancels only peer documents with the requested transport ids. Peer text controls,
     * parent traffic, and every other peer document remain untouched. In-flight ids are removed so
     * a sender interrupted by the gateway cannot put a stale document back into the queue.
     */
    @Synchronized
    fun cancelPeerDocumentTransfers(
        transferIds: Set<String>,
        cancelledAtEpochMs: Long,
    ): List<TelegramOutboxEntry> {
        require(cancelledAtEpochMs >= 0L)
        require(transferIds.all(PEER_IDENTIFIER::matches))
        if (transferIds.isEmpty()) return emptyList()
        val cancelled = pending.values.filter { entry ->
            entry.route == TelegramOutboxRoute.PEER &&
                entry.kind == TelegramOutboxKind.DOCUMENT &&
                entry.peerTransferId in transferIds
        }
        cancelled.forEach { entry ->
            append(encodeSuperseded(entry.idempotencyKey, cancelledAtEpochMs))
            pending.remove(entry.idempotencyKey)
            inFlight.remove(entry.idempotencyKey)
            rememberBounded(superseded, entry.idempotencyKey, cancelledAtEpochMs, MAX_SUPERSEDED_KEYS)
        }
        compactIfNeeded()
        return cancelled
    }

    /**
     * Supersedes only lightweight peer controls. A liveness probe has a short semantic lifetime;
     * keeping an expired probe in the durable retry queue would manufacture a false reconnect much
     * later and could let offline time accumulate hundreds of tiny messages.
     */
    @Synchronized
    fun cancelPeerTextTransfers(
        transferIds: Set<String>,
        cancelledAtEpochMs: Long,
    ): List<TelegramOutboxEntry> {
        require(cancelledAtEpochMs >= 0L)
        require(transferIds.all(PEER_IDENTIFIER::matches))
        if (transferIds.isEmpty()) return emptyList()
        val cancelled = pending.values.filter { entry ->
            entry.route == TelegramOutboxRoute.PEER &&
                entry.kind == TelegramOutboxKind.TEXT &&
                entry.peerTransferId in transferIds
        }
        cancelled.forEach { entry ->
            append(encodeSuperseded(entry.idempotencyKey, cancelledAtEpochMs))
            pending.remove(entry.idempotencyKey)
            inFlight.remove(entry.idempotencyKey)
            rememberBounded(superseded, entry.idempotencyKey, cancelledAtEpochMs, MAX_SUPERSEDED_KEYS)
        }
        compactIfNeeded()
        return cancelled
    }

    /** Cancels every human-parent route entry when this installation becomes the remote teacher. */
    @Synchronized
    fun cancelParentEntries(cancelledAtEpochMs: Long): List<TelegramOutboxEntry> {
        require(cancelledAtEpochMs >= 0L)
        val cancelled = pending.values.filter { it.route == TelegramOutboxRoute.PARENT }
        cancelled.forEach { entry ->
            append(encodeSuperseded(entry.idempotencyKey, cancelledAtEpochMs))
            pending.remove(entry.idempotencyKey)
            inFlight.remove(entry.idempotencyKey)
            rememberBounded(superseded, entry.idempotencyKey, cancelledAtEpochMs, MAX_SUPERSEDED_KEYS)
        }
        compactIfNeeded()
        return cancelled
    }

    @Synchronized
    fun due(nowEpochMs: Long): TelegramOutboxEntry? = pending.values
        .asSequence()
        .filter { it.nextAttemptEpochMs <= nowEpochMs }
        .minWithOrNull(
            compareBy<TelegramOutboxEntry>({ priority(it) }, { it.createdAtEpochMs }),
        )

    @Synchronized
    internal fun claimDue(
        nowEpochMs: Long,
        lane: TelegramOutboxLane = TelegramOutboxLane.ALL,
    ): TelegramOutboxEntry? {
        val entry = pending.values.asSequence()
            .filter {
                lane.accepts(it) &&
                    it.idempotencyKey !in inFlight &&
                    it.nextAttemptEpochMs <= nowEpochMs
            }
            .minWithOrNull(
                compareBy<TelegramOutboxEntry>(
                    { claimPriority(lane, it) },
                    TelegramOutboxEntry::createdAtEpochMs,
                ),
            )
            ?: return null
        inFlight += entry.idempotencyKey
        return entry
    }

    /** Releases only the process-local claim; the durable pending record remains unchanged. */
    @Synchronized
    internal fun releaseClaim(idempotencyKey: String): Boolean = inFlight.remove(idempotencyKey)

    @Synchronized
    internal fun nextWakeEpochMs(
        lane: TelegramOutboxLane = TelegramOutboxLane.ALL,
    ): Long? = pending.values.asSequence()
        .filter(lane::accepts)
        .minOfOrNull(TelegramOutboxEntry::nextAttemptEpochMs)

    @Synchronized
    fun acknowledge(idempotencyKey: String, deliveredAtEpochMs: Long): TelegramOutboxEntry? {
        val entry = pending[idempotencyKey] ?: return null
        append(encodeDone(idempotencyKey, deliveredAtEpochMs))
        inFlight.remove(idempotencyKey)
        pending.remove(idempotencyKey)
        dead.remove(idempotencyKey)
        rememberBounded(delivered, idempotencyKey, deliveredAtEpochMs, MAX_DELIVERED_KEYS)
        compactIfNeeded()
        return entry
    }

    @Synchronized
    fun retry(
        idempotencyKey: String,
        nowEpochMs: Long,
        delayMs: Long,
        reason: String,
    ): TelegramOutboxEntry? {
        val entry = pending[idempotencyKey] ?: return null
        val updated = entry.copy(
            attempts = entry.attempts + 1,
            nextAttemptEpochMs = safeAdd(nowEpochMs, delayMs.coerceAtLeast(0L)),
            lastError = reason.take(MAX_REASON_CHARS),
        )
        append(encodePut(updated))
        inFlight.remove(idempotencyKey)
        pending[idempotencyKey] = updated
        compactIfNeeded()
        return updated
    }

    /**
     * Releases an in-flight entry without counting a transport retry. Used after Telegram has
     * accepted a peer document: the entry remains only as ACK-retention bookkeeping and is made
     * due once, at its terminal retention deadline.
     */
    @Synchronized
    fun deferUntil(
        idempotencyKey: String,
        nextAttemptEpochMs: Long,
        reason: String,
    ): TelegramOutboxEntry? {
        require(nextAttemptEpochMs >= 0L)
        val entry = pending[idempotencyKey] ?: return null
        val updated = entry.copy(
            nextAttemptEpochMs = nextAttemptEpochMs,
            lastError = reason.take(MAX_REASON_CHARS),
        )
        append(encodePut(updated))
        inFlight.remove(idempotencyKey)
        pending[idempotencyKey] = updated
        compactIfNeeded()
        return updated
    }

    /** Makes an ACKed peer document immediately claimable without changing its retry count. */
    @Synchronized
    fun makeDueNow(idempotencyKey: String, nowEpochMs: Long): Boolean {
        require(nowEpochMs >= 0L)
        val entry = pending[idempotencyKey] ?: return false
        if (entry.nextAttemptEpochMs <= nowEpochMs) return true
        val updated = entry.copy(nextAttemptEpochMs = nowEpochMs)
        append(encodePut(updated))
        pending[idempotencyKey] = updated
        compactIfNeeded()
        return true
    }

    @Synchronized
    fun deadLetter(
        idempotencyKey: String,
        reason: String,
        failedAtEpochMs: Long,
    ): TelegramDeadLetter? {
        val entry = pending[idempotencyKey] ?: return null
        val terminal = TelegramDeadLetter(entry, reason.take(MAX_REASON_CHARS), failedAtEpochMs)
        append(encodeDead(terminal))
        inFlight.remove(idempotencyKey)
        pending.remove(idempotencyKey)
        delivered.remove(idempotencyKey)
        rememberBounded(dead, idempotencyKey, terminal, MAX_DEAD_LETTERS)
        compactIfNeeded()
        return terminal
    }

    @Synchronized fun pendingSnapshot(): List<TelegramOutboxEntry> = pending.values.toList()
    @Synchronized fun deadLetters(): List<TelegramDeadLetter> = dead.values.toList()
    @Synchronized fun isDelivered(idempotencyKey: String): Boolean = idempotencyKey in delivered
    @Synchronized internal fun isPendingOrDelivered(idempotencyKey: String): Boolean =
        idempotencyKey in pending || idempotencyKey in delivered
    @Synchronized fun hasSeen(idempotencyKey: String): Boolean =
        idempotencyKey in pending || idempotencyKey in delivered ||
            idempotencyKey in dead || idempotencyKey in superseded
    @Synchronized fun size(): Int = pending.size

    private fun replay() {
        if (!journal.isFile) return
        journal.forEachLine(StandardCharsets.UTF_8) { line ->
            when (val record = decode(line)) {
                is JournalRecord.Put -> {
                    if (record.entry.idempotencyKey !in delivered && record.entry.idempotencyKey !in dead &&
                        record.entry.idempotencyKey !in superseded
                    ) {
                        pending[record.entry.idempotencyKey] = record.entry
                    }
                }
                is JournalRecord.Latest -> {
                    val replaceable = record.supersededKeys?.let { exact ->
                        pending.values.filter { it.idempotencyKey in exact }
                    } ?: pending.values.filter { it.coalesceKey == record.entry.coalesceKey }
                    replaceable
                        .forEach {
                            pending.remove(it.idempotencyKey)
                            rememberBounded(
                                superseded,
                                it.idempotencyKey,
                                record.entry.createdAtEpochMs,
                                MAX_SUPERSEDED_KEYS,
                            )
                        }
                    if (record.entry.idempotencyKey !in delivered && record.entry.idempotencyKey !in dead &&
                        record.entry.idempotencyKey !in superseded
                    ) {
                        pending[record.entry.idempotencyKey] = record.entry
                    }
                }
                is JournalRecord.Done -> {
                    pending.remove(record.key)
                    dead.remove(record.key)
                    rememberBounded(delivered, record.key, record.atEpochMs, MAX_DELIVERED_KEYS)
                }
                is JournalRecord.Dead -> {
                    pending.remove(record.letter.entry.idempotencyKey)
                    delivered.remove(record.letter.entry.idempotencyKey)
                    rememberBounded(
                        dead,
                        record.letter.entry.idempotencyKey,
                        record.letter,
                        MAX_DEAD_LETTERS,
                    )
                }
                is JournalRecord.Superseded -> {
                    pending.remove(record.key)
                    rememberBounded(superseded, record.key, record.atEpochMs, MAX_SUPERSEDED_KEYS)
                }
                is JournalRecord.CancelCoalesced -> {
                    pending.values.filter { it.coalesceKey == record.coalesceKey }
                        .forEach { entry ->
                            pending.remove(entry.idempotencyKey)
                            rememberBounded(
                                superseded,
                                entry.idempotencyKey,
                                record.atEpochMs,
                                MAX_SUPERSEDED_KEYS,
                            )
                        }
                }
                null -> Unit // A partial/corrupt final record is safely ignored.
            }
            appendedRecords++
        }
    }

    private fun validate(entry: TelegramOutboxEntry) {
        require(entry.idempotencyKey.isNotBlank() && entry.idempotencyKey.length <= 256)
        require(entry.destinationChatId != 0L)
        require(entry.attempts >= 0 && entry.nextAttemptEpochMs >= 0L && entry.createdAtEpochMs >= 0L)
        require(
            entry.coalesceKey == null ||
                entry.coalesceKey.isNotBlank() && entry.coalesceKey.length <= MAX_COALESCE_KEY_CHARS,
        )
        when (entry.route) {
            TelegramOutboxRoute.PARENT -> {
                require(entry.destinationUsername == null)
                require(entry.peerTransferId == null)
            }
            TelegramOutboxRoute.PEER -> {
                require(entry.destinationChatId > 0L)
                require(entry.destinationUsername == normalizeTelegramUsername(requireNotNull(entry.destinationUsername)))
                require(PEER_IDENTIFIER.matches(requireNotNull(entry.peerTransferId)))
                require(entry.kind != TelegramOutboxKind.VOICE)
                require(entry.coalesceKey == null || entry.kind == TelegramOutboxKind.DOCUMENT)
            }
        }
        when (entry.kind) {
            TelegramOutboxKind.TEXT -> {
                require(entry.filePath == null)
                require(entry.text.isNotBlank() && entry.text.length <= 4_096)
            }
            TelegramOutboxKind.DOCUMENT, TelegramOutboxKind.VOICE -> {
                require(!entry.filePath.isNullOrBlank())
                require(entry.text.length <= 1_024)
                require(!entry.mimeType.isNullOrBlank())
                require(!entry.displayName.isNullOrBlank())
            }
        }
    }

    private fun priorResult(idempotencyKey: String): TelegramEnqueueResult? = when {
        idempotencyKey in pending -> TelegramEnqueueResult.ALREADY_PENDING
        idempotencyKey in delivered -> TelegramEnqueueResult.ALREADY_DELIVERED
        idempotencyKey in dead -> TelegramEnqueueResult.PREVIOUSLY_DEAD
        idempotencyKey in superseded -> TelegramEnqueueResult.PREVIOUSLY_SUPERSEDED
        else -> null
    }

    private fun hasCapacityFor(entry: TelegramOutboxEntry): Boolean = when {
        isPriorityPeerControl(entry) ->
            priorityPeerControlCount() < reservedPriorityPeerControlEntries
        isReservedPeerText(entry) ->
            regularReservedPeerTextCount() < reservedPeerTextEntries
        else -> regularPendingCount() < maxPendingEntries
    }

    private fun regularPendingCount(): Int = pending.values.count { !isReservedPeerText(it) }

    private fun regularReservedPeerTextCount(): Int = pending.values.count(::isRegularReservedPeerText)

    private fun priorityPeerControlCount(): Int = pending.values.count(::isPriorityPeerControl)

    private fun isReservedPeerText(entry: TelegramOutboxEntry): Boolean =
        entry.route == TelegramOutboxRoute.PEER && entry.kind == TelegramOutboxKind.TEXT &&
            (entry.idempotencyKey.startsWith(PEER_LINK_CONTROL_KEY_PREFIX) ||
                entry.idempotencyKey.startsWith(PEER_DELIVERY_ACK_PREFIX))

    private fun isRegularReservedPeerText(entry: TelegramOutboxEntry): Boolean =
        isReservedPeerText(entry) && !isPriorityPeerControl(entry)

    private fun priorityProbeReplacementPriority(entry: TelegramOutboxEntry): Int = when {
        ":ping:" in entry.idempotencyKey -> 0
        ":connect:" in entry.idempotencyKey -> 1
        else -> 2
    }

    private fun isReplaceablePeerProbe(entry: TelegramOutboxEntry): Boolean =
        entry.idempotencyKey.startsWith(PEER_LINK_CONTROL_KEY_PREFIX) &&
            (":ping:" in entry.idempotencyKey || ":connect:" in entry.idempotencyKey)

    private fun isImmutableLatestEntry(entry: TelegramOutboxEntry): Boolean =
        entry.idempotencyKey in inFlight ||
            entry.route == TelegramOutboxRoute.PEER &&
            entry.kind == TelegramOutboxKind.DOCUMENT &&
            entry.lastError == PEER_ACK_WAIT_REASON

    private fun append(line: String) {
        FileOutputStream(journal, true).use { output ->
            output.writer(StandardCharsets.UTF_8).use { writer ->
                writer.append(line).append('\n')
                writer.flush()
                output.fd.sync()
            }
        }
        appendedRecords++
    }

    private fun compactIfNeeded() {
        val liveRecords = pending.size + delivered.size + dead.size + superseded.size
        if (appendedRecords < COMPACT_AFTER_RECORDS || appendedRecords < liveRecords * 2) return
        val temporary = requireNotNull(journal.parentFile).resolve(journal.name + ".tmp")
        FileOutputStream(temporary).use { output ->
            output.writer(StandardCharsets.UTF_8).use { writer ->
                pending.values.forEach { writer.append(encodePut(it)).append('\n') }
                delivered.forEach { (key, at) -> writer.append(encodeDone(key, at)).append('\n') }
                dead.values.forEach { writer.append(encodeDead(it)).append('\n') }
                superseded.forEach { (key, at) -> writer.append(encodeSuperseded(key, at)).append('\n') }
                writer.flush()
                output.fd.sync()
            }
        }
        AtomicDiskFile.replace(temporary, journal)
        appendedRecords = liveRecords
    }

    private fun encodePut(entry: TelegramOutboxEntry): String = listOf(
        VERSION,
        "PUT",
        encode(entry.idempotencyKey),
        entry.destinationChatId.toString(),
        entry.kind.name,
        encode(entry.filePath.orEmpty()),
        encode(entry.text),
        encode(entry.mimeType.orEmpty()),
        encode(entry.displayName.orEmpty()),
        entry.attempts.toString(),
        entry.nextAttemptEpochMs.toString(),
        entry.createdAtEpochMs.toString(),
        if (entry.deleteAfterSend) "1" else "0",
        encode(entry.lastError.orEmpty()),
        encode(entry.coalesceKey.orEmpty()),
        entry.route.name,
        encode(entry.destinationUsername.orEmpty()),
        encode(entry.peerTransferId.orEmpty()),
    ).joinToString("\t")

    private fun encodeLatest(entry: TelegramOutboxEntry, supersededKeys: List<String>): String =
        encodePut(entry).replaceFirst("$VERSION\tPUT\t", "$VERSION\tLATEST\t") +
            "\t${encode(supersededKeys.joinToString("\n"))}"

    private fun encodeDone(key: String, atEpochMs: Long): String =
        listOf(VERSION, "DONE", encode(key), atEpochMs.toString()).joinToString("\t")

    private fun encodeSuperseded(key: String, atEpochMs: Long): String =
        listOf(VERSION, "SUPERSEDED", encode(key), atEpochMs.toString()).joinToString("\t")

    private fun encodeCancelCoalesced(coalesceKey: String, atEpochMs: Long): String =
        listOf(VERSION, "CANCEL_COALESCED", encode(coalesceKey), atEpochMs.toString()).joinToString("\t")

    private fun encodeDead(letter: TelegramDeadLetter): String =
        encodePut(letter.entry).replaceFirst("$VERSION\tPUT\t", "$VERSION\tDEAD\t") +
            "\t${encode(letter.reason)}\t${letter.failedAtEpochMs}"

    private fun decode(line: String): JournalRecord? = runCatching {
        val fields = line.split('\t')
        if (fields.size < 2 || fields[0] != VERSION) return null
        when (fields[1]) {
            "PUT" -> JournalRecord.Put(decodeEntry(fields))
            "LATEST" -> JournalRecord.Latest(
                entry = decodeEntry(fields),
                supersededKeys = fields.getOrNull(PUT_FIELD_COUNT)?.let(::decodeString)
                    ?.split('\n')?.filter(String::isNotBlank)?.toSet(),
            )
            "DONE" -> {
                if (fields.size != 4) return null
                JournalRecord.Done(decodeString(fields[2]), fields[3].toLong())
            }
            "SUPERSEDED" -> {
                if (fields.size != 4) return null
                JournalRecord.Superseded(decodeString(fields[2]), fields[3].toLong())
            }
            "CANCEL_COALESCED" -> {
                if (fields.size != 4) return null
                val coalesceKey = decodeString(fields[2])
                require(coalesceKey.isNotBlank() && coalesceKey.length <= MAX_COALESCE_KEY_CHARS)
                JournalRecord.CancelCoalesced(coalesceKey, fields[3].toLong().also { require(it >= 0L) })
            }
            "DEAD" -> {
                if (fields.size != LEGACY_PUT_FIELD_COUNT + 2 && fields.size != PUT_FIELD_COUNT + 2) return null
                JournalRecord.Dead(
                    TelegramDeadLetter(
                        entry = decodeEntry(
                            fields.take(
                                if (fields.size == LEGACY_PUT_FIELD_COUNT + 2) {
                                    LEGACY_PUT_FIELD_COUNT
                                } else {
                                    PUT_FIELD_COUNT
                                },
                            ),
                        ),
                        reason = decodeString(fields[fields.size - 2]),
                        failedAtEpochMs = fields.last().toLong(),
                    ),
                )
            }
            else -> null
        }
    }.getOrNull()

    private fun decodeEntry(fields: List<String>): TelegramOutboxEntry {
        require(fields.size >= LEGACY_PUT_FIELD_COUNT)
        return TelegramOutboxEntry(
            idempotencyKey = decodeString(fields[2]),
            destinationChatId = fields[3].toLong(),
            kind = TelegramOutboxKind.valueOf(fields[4]),
            filePath = decodeString(fields[5]).ifBlank { null },
            text = decodeString(fields[6]),
            mimeType = decodeString(fields[7]).ifBlank { null },
            displayName = decodeString(fields[8]).ifBlank { null },
            attempts = fields[9].toInt(),
            nextAttemptEpochMs = fields[10].toLong(),
            createdAtEpochMs = fields[11].toLong(),
            deleteAfterSend = fields[12] == "1",
            lastError = decodeString(fields[13]).ifBlank { null },
            coalesceKey = decodeString(fields[14]).ifBlank { null },
            route = fields.getOrNull(15)?.let(TelegramOutboxRoute::valueOf) ?: TelegramOutboxRoute.PARENT,
            destinationUsername = fields.getOrNull(16)?.let(::decodeString)?.ifBlank { null },
            peerTransferId = fields.getOrNull(17)?.let(::decodeString)?.ifBlank { null },
        ).also(::validate)
    }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeString(value: String): String = Base64.getUrlDecoder().decode(value)
        .toString(StandardCharsets.UTF_8)

    /**
     * Persisted keys/classes make an on-demand parent request jump ahead of accumulated homework
     * submissions without adding another journal schema. Idle notices are always lowest priority;
     * if the student resumes, their coalesced entry is cancelled before it can retry.
     */
    private fun priority(entry: TelegramOutboxEntry): Int = when {
        entry.idempotencyKey.startsWith("telegram-screen:") -> 0
        entry.kind == TelegramOutboxKind.VOICE -> 1
        entry.kind == TelegramOutboxKind.TEXT && entry.coalesceKey == null -> 1
        entry.kind == TelegramOutboxKind.DOCUMENT -> 2
        else -> 3
    }

    /** Responses outrank probes even when the probes were queued much earlier. */
    private fun claimPriority(lane: TelegramOutboxLane, entry: TelegramOutboxEntry): Int {
        if (lane != TelegramOutboxLane.PRIORITY_PEER_CONTROL) return priority(entry)
        return when {
            ":accept:" in entry.idempotencyKey || ":pong:" in entry.idempotencyKey -> 0
            ":connect:" in entry.idempotencyKey -> 1
            ":ping:" in entry.idempotencyKey -> 2
            else -> 1
        }
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    private fun <T> rememberBounded(map: LinkedHashMap<String, T>, key: String, value: T, limit: Int) {
        map.remove(key)
        map[key] = value
        while (map.size > limit) map.remove(map.keys.first())
    }

    private sealed interface JournalRecord {
        data class Put(val entry: TelegramOutboxEntry) : JournalRecord
        data class Latest(
            val entry: TelegramOutboxEntry,
            /** Null denotes the legacy replace-everything record. */
            val supersededKeys: Set<String>?,
        ) : JournalRecord
        data class Done(val key: String, val atEpochMs: Long) : JournalRecord
        data class Dead(val letter: TelegramDeadLetter) : JournalRecord
        data class Superseded(val key: String, val atEpochMs: Long) : JournalRecord
        data class CancelCoalesced(val coalesceKey: String, val atEpochMs: Long) : JournalRecord
    }

    private companion object {
        const val VERSION = "V1"
        const val LEGACY_PUT_FIELD_COUNT = 15
        const val PUT_FIELD_COUNT = 18
        const val DEFAULT_MAX_PENDING = 512
        const val DEFAULT_RESERVED_PEER_TEXT_ENTRIES = 4
        const val DEFAULT_RESERVED_PRIORITY_PEER_CONTROL_ENTRIES = 8
        const val MAX_DELIVERED_KEYS = 10_000
        const val MAX_DEAD_LETTERS = 512
        const val MAX_SUPERSEDED_KEYS = 10_000
        const val MAX_REASON_CHARS = 240
        const val MAX_COALESCE_KEY_CHARS = 120
        const val COMPACT_AFTER_RECORDS = 256
        const val PEER_DELIVERY_ACK_PREFIX = "telegram-peer-received:"
    }
}

private const val PEER_LINK_CONTROL_KEY_PREFIX = "telegram-peer-control:"
