package com.studyink.memo.core

import android.content.Context
import com.studyink.core.model.MasterNoteDataCommitBus
import com.studyink.core.model.MasterNoteOptionalDataRootGuard
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.ConcurrentModificationException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Read-only surface suitable for handing to teacher UI code. */
interface StudentMemoReader {
    /** Durable inventory used to rebuild LAN/Telegram publication queues after process death. */
    fun targets(bookId: String? = null): List<MemoTarget>
    fun snapshot(target: MemoTarget): StudentMemoTargetSnapshot
    fun activeMemos(target: MemoTarget): List<StudentMemo>
    fun memo(target: MemoTarget, memoId: String, includeDeleted: Boolean = false): StudentMemo?
    fun exportMemo(target: MemoTarget, memoId: String): ByteArray
    fun exportSnapshot(target: MemoTarget): ByteArray
}

enum class MemoAuthoritativeApplyStatus { APPLIED, ALREADY_CURRENT, STALE, CONFLICT }

/** Hard contract shared by the local writer and the single-document remote transports. */
object MemoTransportLimits {
    const val MAX_ENCODED_MEMO_BYTES: Int = 1_572_864 // 1.5 MiB
}

data class MemoAuthoritativeApplyResult(
    val status: MemoAuthoritativeApplyStatus,
    val current: StudentMemoTargetSnapshot,
    val appliedMemoCount: Int,
    val duplicateMemoCount: Int,
    val staleMemoCount: Int,
    val conflictMemoCount: Int,
)

enum class StudentMemoChangeKind {
    CREATED,
    STROKES_REPLACED,
    MOVED,
    DELETED,
    AUTHORITATIVE_APPLIED,
}

data class StudentMemoChange(
    val target: MemoTarget,
    val memo: StudentMemo,
    val kind: StudentMemoChangeKind,
)

object StudentMemoChangeBus {
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<(StudentMemoChange) -> Unit>()

    fun addListener(listener: (StudentMemoChange) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    internal fun publish(change: StudentMemoChange) {
        listeners.forEach { listener -> runCatching { listener(change) } }
    }
}

/**
 * Student-authoritative memo sidecar. It never opens the book catalog or page annotation log.
 * Every page-attempt is one bounded atomic document below the ordinary MasterNote backup root.
 */
class StudentMemoRepository(
    rootDirectory: File,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val newUuid: () -> String = { UUID.randomUUID().toString() },
) : StudentMemoReader {
    private val dataRoot = rootDirectory
    private val featureRoot = File(rootDirectory, FEATURE_DIRECTORY)
    private val repositoryLock = lockFor(featureRoot)
    private val readOnlyView = object : StudentMemoReader {
        override fun targets(bookId: String?) = this@StudentMemoRepository.targets(bookId)
        override fun snapshot(target: MemoTarget) = this@StudentMemoRepository.snapshot(target)
        override fun activeMemos(target: MemoTarget) = this@StudentMemoRepository.activeMemos(target)
        override fun memo(target: MemoTarget, memoId: String, includeDeleted: Boolean) =
            this@StudentMemoRepository.memo(target, memoId, includeDeleted)
        override fun exportMemo(target: MemoTarget, memoId: String) =
            this@StudentMemoRepository.exportMemo(target, memoId)
        override fun exportSnapshot(target: MemoTarget) = this@StudentMemoRepository.exportSnapshot(target)
    }

    fun readOnly(): StudentMemoReader = readOnlyView

    override fun targets(bookId: String?): List<MemoTarget> = locked {
        bookId?.let {
            require(it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= MAX_MEMO_BOOK_ID_BYTES)
        }
        storedTargetBaseFiles().mapNotNull { baseFile ->
            try {
                val bytes = AtomicMemoFile(baseFile, MAX_TARGET_FILE_BYTES).readOrNull()
                    ?: return@mapNotNull null
                val decoded = MemoJsonCodec.decode(bytes)
                require(
                    targetFile(decoded.target).baseFileForTest().canonicalFile == baseFile.canonicalFile,
                ) { "Memo target file identity mismatch" }
                decoded.target.takeIf { bookId == null || it.bookId == bookId }
            } catch (_: Exception) {
                null
            }
        }.distinct().sortedWith(compareBy(MemoTarget::bookId, MemoTarget::pageNumber, MemoTarget::attemptNo))
    }

    override fun snapshot(target: MemoTarget): StudentMemoTargetSnapshot = locked { readSnapshot(target) }

    override fun activeMemos(target: MemoTarget): List<StudentMemo> = snapshot(target).memos
        .asSequence()
        .filterNot(StudentMemo::deleted)
        .sortedWith(compareBy(StudentMemo::createdAtEpochMillis, StudentMemo::id))
        .toList()

    override fun memo(target: MemoTarget, memoId: String, includeDeleted: Boolean): StudentMemo? {
        requireValidMemoUuid(memoId, "memo id")
        return snapshot(target).memos.firstOrNull { it.id == memoId && (includeDeleted || !it.deleted) }
    }

    override fun exportMemo(target: MemoTarget, memoId: String): ByteArray = locked {
        requireValidMemoUuid(memoId, "memo id")
        val memo = readSnapshot(target).memos.firstOrNull { it.id == memoId } ?: error("Unknown memo")
        encodeTransportableMemo(memo)
    }

    override fun exportSnapshot(target: MemoTarget): ByteArray = locked {
        MemoJsonCodec.encode(readSnapshot(target))
    }

    /** Validates transport bytes without mutating local state. */
    fun decodeSnapshot(bytes: ByteArray): StudentMemoTargetSnapshot {
        require(bytes.size <= MAX_TARGET_FILE_BYTES) { "Memo snapshot is too large" }
        return MemoJsonCodec.decode(bytes.copyOf())
    }

    /** Validates one independently retryable memo/tombstone transport body. */
    fun decodeMemo(bytes: ByteArray): StudentMemo {
        require(bytes.size <= MemoTransportLimits.MAX_ENCODED_MEMO_BYTES) { "Memo payload is too large" }
        return MemoJsonCodec.decodeMemo(bytes.copyOf())
    }

    fun create(target: MemoTarget, anchor: MemoAnchor): StudentMemo {
        val mutation = locked {
            val current = readSnapshot(target)
            val used = current.memos.mapTo(hashSetOf(), StudentMemo::id)
            val id = freshUuid(used)
            val timestamp = validNow()
            val memo = buildMemo(
                id = id,
                target = target,
                anchor = anchor,
                revision = 1L,
                strokes = emptyList(),
                createdAtEpochMillis = timestamp,
                updatedAtEpochMillis = timestamp,
                deletedAtEpochMillis = null,
            )
            val next = nextSnapshot(current, current.memos + memo)
            writeSnapshot(next)
            DurableMemoMutation(memo, StudentMemoChangeKind.CREATED)
        }
        publish(mutation)
        return mutation.memo
    }

    fun replaceStrokes(
        target: MemoTarget,
        memoId: String,
        expectedRevision: Long? = null,
        strokes: List<MemoStroke>,
    ): StudentMemo {
        requireValidMemoUuid(memoId, "memo id")
        val mutation = locked {
            val current = readSnapshot(target)
            val prior = current.requireMemo(memoId)
            requireMutable(prior, expectedRevision)
            val copiedStrokes = MemoJsonCodec.validateAndCopy(
                target,
                listOf(buildMemo(
                    id = prior.id,
                    target = target,
                    anchor = prior.anchor,
                    revision = prior.revision,
                    strokes = strokes,
                    createdAtEpochMillis = prior.createdAtEpochMillis,
                    updatedAtEpochMillis = prior.updatedAtEpochMillis,
                    deletedAtEpochMillis = null,
                )),
            ).single().strokes
            if (prior.strokes == copiedStrokes) return@locked DurableMemoMutation(prior, null)
            val updated = buildMemo(
                id = prior.id,
                target = prior.target,
                anchor = prior.anchor,
                revision = nextRevision(prior.revision),
                strokes = copiedStrokes,
                createdAtEpochMillis = prior.createdAtEpochMillis,
                updatedAtEpochMillis = monotonicNow(prior.updatedAtEpochMillis),
                deletedAtEpochMillis = null,
            )
            writeSnapshot(nextSnapshot(current, current.memos.replace(updated)))
            DurableMemoMutation(updated, StudentMemoChangeKind.STROKES_REPLACED)
        }
        publish(mutation)
        return mutation.memo
    }

    fun move(
        target: MemoTarget,
        memoId: String,
        expectedRevision: Long? = null,
        anchor: MemoAnchor,
    ): StudentMemo {
        requireValidMemoUuid(memoId, "memo id")
        val mutation = locked {
            val current = readSnapshot(target)
            val prior = current.requireMemo(memoId)
            requireMutable(prior, expectedRevision)
            if (prior.anchor == anchor) return@locked DurableMemoMutation(prior, null)
            val updated = buildMemo(
                id = prior.id,
                target = prior.target,
                anchor = anchor,
                revision = nextRevision(prior.revision),
                strokes = prior.strokes,
                createdAtEpochMillis = prior.createdAtEpochMillis,
                updatedAtEpochMillis = monotonicNow(prior.updatedAtEpochMillis),
                deletedAtEpochMillis = null,
            )
            writeSnapshot(nextSnapshot(current, current.memos.replace(updated)))
            DurableMemoMutation(updated, StudentMemoChangeKind.MOVED)
        }
        publish(mutation)
        return mutation.memo
    }

    fun delete(
        target: MemoTarget,
        memoId: String,
        expectedRevision: Long? = null,
    ): StudentMemo {
        requireValidMemoUuid(memoId, "memo id")
        val mutation = locked {
            val current = readSnapshot(target)
            val prior = current.memos.firstOrNull { it.id == memoId } ?: error("Unknown memo")
            expectedRevision?.let { expected ->
                if (prior.revision != expected) throw ConcurrentModificationException("Memo changed while editing")
            }
            if (prior.deleted) return@locked DurableMemoMutation(prior, null)
            val timestamp = monotonicNow(prior.updatedAtEpochMillis)
            val tombstone = buildMemo(
                id = prior.id,
                target = prior.target,
                anchor = prior.anchor,
                revision = nextRevision(prior.revision),
                strokes = emptyList(),
                createdAtEpochMillis = prior.createdAtEpochMillis,
                updatedAtEpochMillis = timestamp,
                deletedAtEpochMillis = timestamp,
            )
            writeSnapshot(nextSnapshot(current, current.memos.replace(tombstone)))
            DurableMemoMutation(tombstone, StudentMemoChangeKind.DELETED)
        }
        publish(mutation)
        return mutation.memo
    }

    /** Applies one memo while retaining an equal-revision conflict for an undecided caller. */
    fun applyAuthoritative(memo: StudentMemo): MemoAuthoritativeApplyResult =
        applyAuthoritativeMemo(memo, replaceEqualRevisionConflict = false)

    /**
     * Applies a memo received directly from the authenticated student that solely owns this layer.
     * A restored student backup can legitimately reuse a revision with different bytes. In that one
     * case the connected student's state replaces the teacher cache; older revisions remain stale.
     */
    fun applyAuthenticatedStudentMemo(memo: StudentMemo): MemoAuthoritativeApplyResult =
        applyAuthoritativeMemo(memo, replaceEqualRevisionConflict = true)

    private fun applyAuthoritativeMemo(
        memo: StudentMemo,
        replaceEqualRevisionConflict: Boolean,
    ): MemoAuthoritativeApplyResult {
        val incoming = MemoJsonCodec.validateAndCopy(memo.target, listOf(memo)).single()
        var appliedChange: DurableMemoMutation? = null
        val result = locked {
            val current = readSnapshot(incoming.target)
            val prior = current.memos.firstOrNull { it.id == incoming.id }
            when {
                prior == null || incoming.revision > prior.revision ||
                    replaceEqualRevisionConflict && incoming.revision == prior.revision &&
                    incoming.digestSha256 != prior.digestSha256 -> {
                    val next = nextSnapshot(
                        current,
                        current.memos.filterNot { it.id == incoming.id } + incoming,
                        minimumRevision = incoming.revision,
                    )
                    writeSnapshot(next)
                    appliedChange = DurableMemoMutation(incoming, StudentMemoChangeKind.AUTHORITATIVE_APPLIED)
                    MemoAuthoritativeApplyResult(
                        MemoAuthoritativeApplyStatus.APPLIED, next, 1, 0, 0, 0,
                    )
                }
                incoming.revision < prior.revision -> MemoAuthoritativeApplyResult(
                    MemoAuthoritativeApplyStatus.STALE, current, 0, 0, 1, 0,
                )
                incoming.digestSha256 == prior.digestSha256 -> MemoAuthoritativeApplyResult(
                    MemoAuthoritativeApplyStatus.ALREADY_CURRENT, current, 0, 1, 0, 0,
                )
                else -> MemoAuthoritativeApplyResult(
                    MemoAuthoritativeApplyStatus.CONFLICT, current, 0, 0, 0, 1,
                )
            }
        }
        appliedChange?.let(::publish)
        return result
    }

    /** Atomically accepts a full newer page-attempt snapshot or leaves the current state untouched. */
    fun applyAuthoritative(snapshot: StudentMemoTargetSnapshot): MemoAuthoritativeApplyResult {
        val incomingMemos = MemoJsonCodec.validateAndCopy(snapshot.target, snapshot.memos)
        val incomingDigest = StudentMemoDigest.targetSha256(snapshot.target, incomingMemos)
        require(incomingDigest == snapshot.digestSha256) { "Memo target digest mismatch" }
        val incoming = StudentMemoTargetSnapshot(snapshot.target, snapshot.revision, incomingDigest, incomingMemos)
        var changes: List<DurableMemoMutation> = emptyList()
        val result = locked {
            val current = readSnapshot(incoming.target)
            if (incoming.revision < current.revision) return@locked MemoAuthoritativeApplyResult(
                MemoAuthoritativeApplyStatus.STALE, current, 0, 0, incoming.memos.size, 0,
            )
            if (incoming.revision == current.revision) {
                return@locked if (incoming.digestSha256 == current.digestSha256) {
                    MemoAuthoritativeApplyResult(
                        MemoAuthoritativeApplyStatus.ALREADY_CURRENT,
                        current,
                        0,
                        incoming.memos.size,
                        0,
                        0,
                    )
                } else {
                    MemoAuthoritativeApplyResult(
                        MemoAuthoritativeApplyStatus.CONFLICT,
                        current,
                        0,
                        0,
                        0,
                        maxOf(1, incoming.memos.size),
                    )
                }
            }

            val currentById = current.memos.associateBy(StudentMemo::id)
            val incomingById = incoming.memos.associateBy(StudentMemo::id)
            var applied = 0
            var duplicates = 0
            var stale = 0
            var conflicts = 0
            currentById.keys.filterNot(incomingById::containsKey).forEach { conflicts += 1 }
            incoming.memos.forEach { memo ->
                val prior = currentById[memo.id]
                when {
                    prior == null || memo.revision > prior.revision -> applied += 1
                    memo.revision < prior.revision -> stale += 1
                    memo.digestSha256 == prior.digestSha256 -> duplicates += 1
                    else -> conflicts += 1
                }
            }
            if (stale > 0 || conflicts > 0) {
                return@locked MemoAuthoritativeApplyResult(
                    MemoAuthoritativeApplyStatus.CONFLICT,
                    current,
                    0,
                    duplicates,
                    stale,
                    conflicts,
                )
            }
            writeSnapshot(incoming)
            changes = incoming.memos.mapNotNull { memo ->
                val prior = currentById[memo.id]
                memo.takeIf { prior?.digestSha256 != memo.digestSha256 }
                    ?.let { DurableMemoMutation(it, StudentMemoChangeKind.AUTHORITATIVE_APPLIED) }
            }
            MemoAuthoritativeApplyResult(
                MemoAuthoritativeApplyStatus.APPLIED,
                incoming,
                applied,
                duplicates,
                0,
                0,
            )
        }
        if (changes.isNotEmpty() || result.status == MemoAuthoritativeApplyStatus.APPLIED) {
            MasterNoteDataCommitBus.recordDurableCommit()
            changes.forEach { StudentMemoChangeBus.publish(StudentMemoChange(it.memo.target, it.memo, requireNotNull(it.kind))) }
        }
        return result
    }

    fun applyAuthoritative(bytes: ByteArray): MemoAuthoritativeApplyResult =
        applyAuthoritative(decodeSnapshot(bytes))

    private fun readSnapshot(target: MemoTarget): StudentMemoTargetSnapshot {
        val bytes = targetFile(target).readOrNull() ?: return emptySnapshot(target)
        return try {
            MemoJsonCodec.decode(bytes, target)
        } catch (error: Exception) {
            throw CorruptMemoDataException("Stored memo data is invalid", error)
        }
    }

    private fun writeSnapshot(snapshot: StudentMemoTargetSnapshot) {
        snapshot.memos.forEach { encodeTransportableMemo(it) }
        targetFile(snapshot.target).write(MemoJsonCodec.encode(snapshot))
    }

    private fun encodeTransportableMemo(memo: StudentMemo): ByteArray {
        val bytes = MemoJsonCodec.encodeMemo(memo)
        if (bytes.size > MemoTransportLimits.MAX_ENCODED_MEMO_BYTES) {
            throw MemoPayloadTooLargeException(bytes.size)
        }
        return bytes
    }

    private fun nextSnapshot(
        current: StudentMemoTargetSnapshot,
        memos: Collection<StudentMemo>,
        minimumRevision: Long = 0L,
    ): StudentMemoTargetSnapshot {
        val normalized = MemoJsonCodec.validateAndCopy(current.target, memos)
        return StudentMemoTargetSnapshot(
            target = current.target,
            revision = maxOf(nextRevision(current.revision), minimumRevision),
            digestSha256 = StudentMemoDigest.targetSha256(current.target, normalized),
            memos = normalized,
        )
    }

    private fun emptySnapshot(target: MemoTarget) = StudentMemoTargetSnapshot(
        target = target,
        revision = 0L,
        digestSha256 = StudentMemoDigest.targetSha256(target, emptyList()),
        memos = emptyList(),
    )

    private fun buildMemo(
        id: String,
        target: MemoTarget,
        anchor: MemoAnchor,
        revision: Long,
        strokes: List<MemoStroke>,
        createdAtEpochMillis: Long,
        updatedAtEpochMillis: Long,
        deletedAtEpochMillis: Long?,
    ): StudentMemo {
        val digest = StudentMemoDigest.memoSha256(
            id,
            target,
            anchor,
            revision,
            strokes,
            createdAtEpochMillis,
            updatedAtEpochMillis,
            deletedAtEpochMillis,
        )
        return StudentMemo(
            id,
            target,
            anchor,
            revision,
            digest,
            strokes,
            createdAtEpochMillis,
            updatedAtEpochMillis,
            deletedAtEpochMillis,
        )
    }

    private fun StudentMemoTargetSnapshot.requireMemo(memoId: String): StudentMemo =
        memos.firstOrNull { it.id == memoId } ?: error("Unknown memo")

    private fun requireMutable(memo: StudentMemo, expectedRevision: Long?) {
        check(!memo.deleted) { "Deleted memo cannot be changed" }
        expectedRevision?.let { expected ->
            if (memo.revision != expected) throw ConcurrentModificationException("Memo changed while editing")
        }
    }

    private fun List<StudentMemo>.replace(value: StudentMemo): List<StudentMemo> =
        map { if (it.id == value.id) value else it }

    private fun targetFile(target: MemoTarget): AtomicMemoFile {
        val digest = identityDigest(target)
        return AtomicMemoFile(
            File(featureRoot, "targets/${digest.take(2)}/$digest.json"),
            MAX_TARGET_FILE_BYTES,
        )
    }

    internal fun targetFileForTest(target: MemoTarget): File = targetFile(target).baseFileForTest()

    private fun storedTargetBaseFiles(): List<File> {
        val directory = File(featureRoot, "targets")
        if (!directory.isDirectory) return emptyList()
        val values = directory.walkTopDown()
            .filter(File::isFile)
            .mapNotNull { candidate ->
                when {
                    TARGET_BASE_FILE.matches(candidate.name) -> candidate
                    TARGET_BACKUP_FILE.matches(candidate.name) ->
                        File(requireNotNull(candidate.parentFile), candidate.name.removeSuffix(".bak"))
                    else -> null
                }
            }
            .distinctBy { it.toPath().toAbsolutePath().normalize().toString() }
            .take(MAX_STORED_TARGETS + 1)
            .toList()
        require(values.size <= MAX_STORED_TARGETS) { "Too many stored memo targets" }
        return values.sortedBy(File::getPath)
    }

    private fun freshUuid(used: Set<String>): String {
        repeat(MAX_ID_GENERATION_ATTEMPTS) {
            val candidate = newUuid().lowercase()
            requireValidMemoUuid(candidate, "generated memo id")
            if (candidate !in used) return candidate
        }
        error("Unable to allocate a unique memo id")
    }

    private fun validNow(): Long = nowEpochMillis().also { require(it >= 0L) }

    private fun monotonicNow(prior: Long): Long {
        check(prior < Long.MAX_VALUE) { "Memo timestamp is exhausted" }
        return maxOf(validNow(), prior + 1L)
    }

    private fun publish(mutation: DurableMemoMutation) {
        val kind = mutation.kind ?: return
        MasterNoteDataCommitBus.recordDurableCommit()
        StudentMemoChangeBus.publish(StudentMemoChange(mutation.memo.target, mutation.memo, kind))
    }

    private fun <T> locked(block: () -> T): T =
        MasterNoteOptionalDataRootGuard.withStableDataRoot(dataRoot) {
            synchronized(repositoryLock, block)
        }

    companion object {
        private const val FEATURE_DIRECTORY = "student-memos-v1"
        private const val MAX_TARGET_FILE_BYTES = 16 * 1024 * 1024
        private const val MAX_ID_GENERATION_ATTEMPTS = 32
        private const val MAX_STORED_TARGETS = 100_000
        private val locks = ConcurrentHashMap<String, Any>()
        private val TARGET_BASE_FILE = Regex("[0-9a-f]{64}\\.json")
        private val TARGET_BACKUP_FILE = Regex("[0-9a-f]{64}\\.json\\.bak")

        @Volatile private var instance: StudentMemoRepository? = null

        fun get(context: Context): StudentMemoRepository = instance ?: synchronized(this) {
            instance ?: StudentMemoRepository(
                File(context.applicationContext.filesDir, "masternote"),
            ).also { instance = it }
        }

        internal fun resetForTest() {
            instance = null
        }

        private fun lockFor(featureRoot: File): Any =
            locks.computeIfAbsent(featureRoot.toPath().toAbsolutePath().normalize().toString()) { Any() }

        private fun identityDigest(target: MemoTarget): String {
            val digest = MessageDigest.getInstance("SHA-256")
            listOf(target.bookId, target.pageNumber.toString(), target.attemptNo.toString()).forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                digest.update(bytes)
            }
            return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
    }
}

class CorruptMemoDataException(message: String, cause: Throwable) : IllegalStateException(message, cause)

class MemoPayloadTooLargeException(
    val actualBytes: Int,
) : IllegalArgumentException(
    "Memo payload is $actualBytes bytes; maximum is ${MemoTransportLimits.MAX_ENCODED_MEMO_BYTES} bytes",
)

private data class DurableMemoMutation(
    val memo: StudentMemo,
    val kind: StudentMemoChangeKind?,
)

private fun nextRevision(value: Long): Long {
    check(value < Long.MAX_VALUE) { "Memo revision is exhausted" }
    return value + 1L
}
