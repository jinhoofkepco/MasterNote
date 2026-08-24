package com.studyink.app

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * Small durable index for the application-specific side of remote review.
 *
 * Telegram owns delivery, while this ledger owns the local meaning of an opaque page token. The
 * transport therefore never needs a workbook id and a late correction can still be attached to
 * the exact local page after a process restart. Records are append-only and a partial final record
 * is ignored; image files are copied and fsynced before their index record becomes visible.
 */
internal class RemoteReviewLedger(
    private val root: File,
    private val compactAfterRecords: Int = DEFAULT_COMPACTION_RECORDS,
) {
    private val journal = root.resolve("review-ledger.v1")
    private val snapshotDirectory = root.resolve("snapshots")
    private val outgoing = linkedMapOf<String, OutgoingRemoteSnapshot>()
    private val incoming = linkedMapOf<String, IncomingRemoteSnapshot>()
    private val appliedFeedbackTransfers = linkedMapOf<String, AppliedFeedbackRecord>()
    private val latestFeedbackRevision = linkedMapOf<String, Long>()
    private var appendedRecords = 0

    init {
        require(compactAfterRecords > 0)
        require(root.mkdirs() || root.isDirectory) { "Cannot create remote-review directory" }
        require(snapshotDirectory.mkdirs() || snapshotDirectory.isDirectory) {
            "Cannot create remote-review snapshot directory"
        }
        replay()
        pruneMissingIncomingFiles()
    }

    @Synchronized
    fun recordOutgoing(value: OutgoingRemoteSnapshot) {
        validate(value)
        append(encodeOutgoing(value))
        outgoing[value.transferId] = value
        trimOutgoing()
        compactIfNeeded()
    }

    @Synchronized
    fun outgoing(transferId: String): OutgoingRemoteSnapshot? = outgoing[transferId]

    @Synchronized
    fun storeIncoming(
        value: IncomingRemoteSnapshot,
        sourceImage: File,
        maximumBytes: Long,
    ): IncomingRemoteSnapshot {
        validate(value)
        require(maximumBytes > 0L)
        require(sourceImage.isFile && sourceImage.canRead()) { "Remote snapshot is unreadable" }
        require(sourceImage.length() in 1..maximumBytes) { "Remote snapshot exceeds the local limit" }

        incoming[value.transferId]?.let { existing ->
            if (existing.imageFile.isFile) return existing
        }

        val target = snapshotDirectory.resolve("${safeToken(value.transferId)}.image")
        val temporary = snapshotDirectory.resolve("${safeToken(value.transferId)}.image.part")
        try {
            sourceImage.inputStream().buffered().use { input ->
                FileOutputStream(temporary).buffered().use { output -> input.copyTo(output) }
            }
            FileOutputStream(temporary, true).use { output ->
                output.flush()
                output.fd.sync()
            }
            atomicReplace(temporary, target)
            val committed = value.copy(imagePath = target.absolutePath)
            append(encodeIncoming(committed))
            incoming[committed.transferId] = committed
            trimIncoming()
            compactIfNeeded()
            return committed
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    @Synchronized
    fun incomingSnapshots(): List<IncomingRemoteSnapshot> = incoming.values
        .filter { it.imageFile.isFile }
        .sortedByDescending(IncomingRemoteSnapshot::receivedAtEpochMs)

    @Synchronized
    fun incoming(transferId: String): IncomingRemoteSnapshot? =
        incoming[transferId]?.takeIf { it.imageFile.isFile }

    /**
     * A restore replaces workbook/annotation identity while this no-backup ledger survives it.
     * Keep teacher-side received images, but make every pre-restore student source unusable before
     * transport processing resumes.
     */
    @Synchronized
    fun clearStudentExchangeState() {
        outgoing.clear()
        appliedFeedbackTransfers.clear()
        latestFeedbackRevision.clear()
        compactIfNeeded(force = true)
    }

    /**
     * A deterministic annotation operation id makes APPLY safe if the process dies after the
     * annotation append but before [recordFeedbackApplied].
     */
    @Synchronized
    fun feedbackDecision(
        transferId: String,
        pageToken: String,
        revision: Long,
    ): RemoteFeedbackDecision {
        validateToken(transferId)
        validateToken(pageToken)
        require(revision > 0L)
        if (transferId in appliedFeedbackTransfers) return RemoteFeedbackDecision.DUPLICATE
        if (revision <= (latestFeedbackRevision[pageToken] ?: 0L)) {
            return RemoteFeedbackDecision.SUPERSEDED
        }
        return RemoteFeedbackDecision.APPLY
    }

    @Synchronized
    fun recordFeedbackApplied(
        transferId: String,
        pageToken: String,
        revision: Long,
        appliedAtEpochMs: Long,
    ) {
        validateToken(transferId)
        validateToken(pageToken)
        require(revision > 0L && appliedAtEpochMs >= 0L)
        if (transferId in appliedFeedbackTransfers) return
        val record = AppliedFeedbackRecord(pageToken, revision, appliedAtEpochMs)
        append(encodeApplied(transferId, record))
        appliedFeedbackTransfers[transferId] = record
        rememberLatestFeedbackRevision(pageToken, revision)
        while (appliedFeedbackTransfers.size > MAX_APPLIED_TRANSFERS) {
            appliedFeedbackTransfers.remove(appliedFeedbackTransfers.keys.first())
        }
        compactIfNeeded()
    }

    private fun replay() {
        if (!journal.isFile) return
        journal.forEachLine(StandardCharsets.UTF_8) { line ->
            appendedRecords++
            runCatching {
                val fields = line.split('\t')
                if (fields.firstOrNull() != VERSION) return@runCatching
                when (fields.getOrNull(1)) {
                    "OUT" -> decodeOutgoing(fields).also { outgoing[it.transferId] = it }
                    "IN" -> decodeIncoming(fields).also { incoming[it.transferId] = it }
                    "APPLIED" -> {
                        require(fields.size == 6)
                        val transferId = decode(fields[2])
                        val pageToken = decode(fields[3])
                        val revision = fields[4].toLong().also { require(it > 0L) }
                        validateToken(transferId)
                        validateToken(pageToken)
                        appliedFeedbackTransfers[transferId] = AppliedFeedbackRecord(
                            pageToken = pageToken,
                            revision = revision,
                            appliedAtEpochMs = fields[5].toLong().also { require(it >= 0L) },
                        )
                        rememberLatestFeedbackRevision(pageToken, revision)
                    }
                    "LATEST" -> {
                        require(fields.size == 4)
                        val pageToken = decode(fields[2]).also(::validateToken)
                        val revision = fields[3].toLong().also { require(it > 0L) }
                        rememberLatestFeedbackRevision(pageToken, revision)
                    }
                }
            }
        }
        trimOutgoing()
        trimIncoming(deleteFiles = false)
        while (appliedFeedbackTransfers.size > MAX_APPLIED_TRANSFERS) {
            appliedFeedbackTransfers.remove(appliedFeedbackTransfers.keys.first())
        }
        trimLatestFeedbackRevisions()
        compactIfNeeded()
    }

    private fun encodeOutgoing(value: OutgoingRemoteSnapshot): String = listOf(
        VERSION,
        "OUT",
        encode(value.transferId),
        encode(value.pageToken),
        encode(value.bookId),
        value.pageNumber.toString(),
        value.attemptNo?.toString().orEmpty(),
        value.studentRevision.toString(),
        value.widthPx.toString(),
        value.heightPx.toString(),
        value.createdAtEpochMs.toString(),
    ).joinToString("\t")

    private fun decodeOutgoing(fields: List<String>): OutgoingRemoteSnapshot {
        require(fields.size == 11)
        return OutgoingRemoteSnapshot(
            transferId = decode(fields[2]),
            pageToken = decode(fields[3]),
            bookId = decode(fields[4]),
            pageNumber = fields[5].toInt(),
            attemptNo = fields[6].toIntOrNull(),
            studentRevision = fields[7].toLong(),
            widthPx = fields[8].toInt(),
            heightPx = fields[9].toInt(),
            createdAtEpochMs = fields[10].toLong(),
        ).also(::validate)
    }

    private fun encodeIncoming(value: IncomingRemoteSnapshot): String = listOf(
        VERSION,
        "IN",
        encode(value.transferId),
        encode(value.pageToken),
        encode(value.workbookLabel),
        encode(value.studentLabel.orEmpty()),
        value.pageNumber.toString(),
        value.attemptNo?.toString().orEmpty(),
        value.studentRevision.toString(),
        value.widthPx.toString(),
        value.heightPx.toString(),
        value.receivedAtEpochMs.toString(),
        encode(value.imagePath),
    ).joinToString("\t")

    private fun decodeIncoming(fields: List<String>): IncomingRemoteSnapshot {
        require(fields.size == 13)
        return IncomingRemoteSnapshot(
            transferId = decode(fields[2]),
            pageToken = decode(fields[3]),
            workbookLabel = decode(fields[4]),
            studentLabel = decode(fields[5]).ifBlank { null },
            pageNumber = fields[6].toInt(),
            attemptNo = fields[7].toIntOrNull(),
            studentRevision = fields[8].toLong(),
            widthPx = fields[9].toInt(),
            heightPx = fields[10].toInt(),
            receivedAtEpochMs = fields[11].toLong(),
            imagePath = decode(fields[12]),
        ).also(::validate)
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

    private fun encodeApplied(transferId: String, value: AppliedFeedbackRecord): String = listOf(
        VERSION,
        "APPLIED",
        encode(transferId),
        encode(value.pageToken),
        value.revision.toString(),
        value.appliedAtEpochMs.toString(),
    ).joinToString("\t")

    /** Bounds startup replay time while preserving live mappings and duplicate/latest guards. */
    private fun compactIfNeeded(force: Boolean = false) {
        val liveRecords = outgoing.size + incoming.size + appliedFeedbackTransfers.size +
            latestFeedbackRevision.size
        if (!force && (appendedRecords < compactAfterRecords || appendedRecords < liveRecords * 2)) return
        val lines = buildList {
            outgoing.values.forEach { add(encodeOutgoing(it)) }
            incoming.values.filter { it.imageFile.isFile }.forEach { add(encodeIncoming(it)) }
            appliedFeedbackTransfers.forEach { (transferId, value) ->
                add(encodeApplied(transferId, value))
            }
            latestFeedbackRevision.forEach { (pageToken, revision) ->
                add(listOf(VERSION, "LATEST", encode(pageToken), revision.toString()).joinToString("\t"))
            }
        }
        val temporary = root.resolve("${journal.name}.compact")
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

    private fun trimOutgoing() {
        while (outgoing.size > MAX_OUTGOING_MAPPINGS) outgoing.remove(outgoing.keys.first())
    }

    private fun trimIncoming(deleteFiles: Boolean = true) {
        while (incoming.size > MAX_INCOMING_SNAPSHOTS) {
            val removed = incoming.remove(incoming.keys.first()) ?: continue
            if (deleteFiles) removed.imageFile.delete()
        }
    }

    private fun pruneMissingIncomingFiles() {
        incoming.entries.removeAll { !it.value.imageFile.isFile }
        val referenced = incoming.values.mapNotNull { value ->
            runCatching { value.imageFile.canonicalPath }.getOrNull()
        }.toSet()
        snapshotDirectory.listFiles().orEmpty().filter(File::isFile).forEach { file ->
            val canonical = runCatching { file.canonicalPath }.getOrNull()
            if (file.name.endsWith(".part") || file.name.endsWith(".image") && canonical !in referenced) {
                file.delete()
            }
        }
    }

    private fun rememberLatestFeedbackRevision(pageToken: String, revision: Long) {
        val current = latestFeedbackRevision[pageToken] ?: 0L
        if (revision < current) return
        latestFeedbackRevision.remove(pageToken)
        latestFeedbackRevision[pageToken] = revision
        trimLatestFeedbackRevisions()
    }

    private fun trimLatestFeedbackRevisions() {
        while (latestFeedbackRevision.size > MAX_LATEST_FEEDBACK_PAGES) {
            latestFeedbackRevision.remove(latestFeedbackRevision.keys.first())
        }
    }

    private fun validate(value: OutgoingRemoteSnapshot) {
        validateToken(value.transferId)
        validateToken(value.pageToken)
        require(value.bookId.isNotBlank())
        require(value.pageNumber >= 0)
        require(value.attemptNo == null || value.attemptNo > 0)
        require(value.studentRevision >= 0L)
        require(value.widthPx > 0 && value.heightPx > 0)
        require(value.createdAtEpochMs >= 0L)
    }

    private fun validate(value: IncomingRemoteSnapshot) {
        validateToken(value.transferId)
        validateToken(value.pageToken)
        require(value.workbookLabel.isNotBlank() && value.workbookLabel.length <= 160)
        require(value.studentLabel == null || value.studentLabel.length <= 120)
        require(value.pageNumber > 0)
        require(value.attemptNo == null || value.attemptNo > 0)
        require(value.studentRevision >= 0L)
        require(value.widthPx > 0 && value.heightPx > 0)
        require(value.receivedAtEpochMs >= 0L)
        require(value.imagePath.isNotBlank())
    }

    private fun validateToken(value: String) {
        require(TOKEN.matches(value)) { "Invalid remote-review token" }
    }

    private fun safeToken(value: String): String {
        validateToken(value)
        return value.take(128)
    }

    private fun encode(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decode(value: String): String = Base64.getUrlDecoder().decode(value)
        .toString(StandardCharsets.UTF_8)

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
        const val VERSION = "RR1"
        const val MAX_OUTGOING_MAPPINGS = 10_000
        const val MAX_INCOMING_SNAPSHOTS = 60
        const val MAX_APPLIED_TRANSFERS = 2_000
        const val MAX_LATEST_FEEDBACK_PAGES = 10_000
        const val DEFAULT_COMPACTION_RECORDS = 4_096
        val TOKEN = Regex("[A-Za-z0-9_-]{8,128}")
    }
}

private data class AppliedFeedbackRecord(
    val pageToken: String,
    val revision: Long,
    val appliedAtEpochMs: Long,
)

internal data class OutgoingRemoteSnapshot(
    val transferId: String,
    val pageToken: String,
    val bookId: String,
    /** Zero-based local page number. */
    val pageNumber: Int,
    val attemptNo: Int?,
    val studentRevision: Long,
    val widthPx: Int,
    val heightPx: Int,
    val createdAtEpochMs: Long,
)

internal data class IncomingRemoteSnapshot(
    val transferId: String,
    val pageToken: String,
    val workbookLabel: String,
    val studentLabel: String?,
    /** Human-facing, one-based page number. */
    val pageNumber: Int,
    val attemptNo: Int?,
    val studentRevision: Long,
    val widthPx: Int,
    val heightPx: Int,
    val receivedAtEpochMs: Long,
    val imagePath: String,
) {
    val imageFile: File get() = File(imagePath)
}

internal enum class RemoteFeedbackDecision { APPLY, DUPLICATE, SUPERSEDED }
