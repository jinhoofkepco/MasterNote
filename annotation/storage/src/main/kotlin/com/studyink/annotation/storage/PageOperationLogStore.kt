package com.studyink.annotation.storage

import android.content.Context
import com.studyink.annotation.engine.AnnotationChange
import com.studyink.core.model.ANNOTATION_FORMAT_VERSION
import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.AssetOperation
import com.studyink.core.model.CompactPagePointCodec
import com.studyink.core.model.LosslessF32PagePointCodec
import com.studyink.core.model.MasterNoteDataCommitBus
import com.studyink.core.model.Mark
import com.studyink.core.model.MarkColor
import com.studyink.core.model.MarkGroup
import com.studyink.core.model.OperationId
import com.studyink.core.model.PageBounds
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeId
import com.studyink.core.model.StrokeTool
import com.studyink.core.model.TeacherReviewPublicationLimits
import com.studyink.core.model.TeacherReviewMarkGroupMetadata
import com.studyink.core.model.TeacherReviewStateEvidence
import com.studyink.core.model.normalizeTeacherReviewMarkGroupMetadata
import com.studyink.core.model.normalizeTeacherReviewMarkGroupMetadataValues
import com.studyink.core.model.teacherReviewGradeStateSha256
import com.studyink.core.model.teacherReviewMarkGroupMetadataSha256
import com.studyink.core.model.teacherReviewMarkGroupsSha256
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

class CorruptAnnotationDataException(
    message: String,
    val quarantinedFile: File,
    cause: Throwable,
) : IOException(message, cause)

/** Corruption is recoverable; VM failures must escape without moving otherwise valid user data. */
internal inline fun <T> readAnnotationDataOrHandleCorruption(
    read: () -> T,
    onCorruption: (Exception) -> T,
): T = try {
    read()
} catch (error: Exception) {
    onCorruption(error)
}

data class OperationCursor(val deviceId: String, val logicalClock: Long)

/** Point representation requested at a storage/transport boundary; runtime ink stays absolute. */
enum class AnnotationPointEncoding {
    /** Compatible with every format-v2 installation. */
    LEGACY_FLOAT_ARRAYS,

    /** Q16 deltas when exact, otherwise bit-exact F32 triples in bounded GZIP. */
    COMPACT_Q16_DELTA,
}

data class PageOperationSyncStats(
    val logByteCount: Long,
    val pendingEncodedByteCount: Long,
    val pendingOperationCount: Int,
    val originDeviceHighWater: Long,
    /** Includes erase-only mutations, which leave no active stroke timestamp behind. */
    val lastMutationEpochMillis: Long = 0L,
)

/** A defensive-copy operation batch which proves whether every matching record fit the budget. */
data class BoundedEncodedOperationBatch(
    val operations: List<ByteArray>,
    val framedByteCount: Int,
    val complete: Boolean,
    val lastLogicalClock: Long?,
)

data class StudentLayerCheckpointApplyResult(
    val checkpointId: String,
    val layerSha256: String,
    val snapshot: AnnotationSnapshot,
    val changed: Boolean,
)

data class PublishedTeacherLayerCheckpointApplyResult(
    val checkpointId: String,
    val layerSha256: String,
    val snapshot: AnnotationSnapshot,
    val changed: Boolean,
)

class StudentLayerCheckpointExport internal constructor(
    checkpointBytes: ByteArray,
    val layerSha256: String,
    val originDeviceHighWater: Long,
) {
    private val immutableCheckpointBytes = checkpointBytes.copyOf()

    val checkpointBytes: ByteArray get() = immutableCheckpointBytes.copyOf()
    val checkpointSizeBytes: Int get() = immutableCheckpointBytes.size

    fun copyCheckpointBytes(): ByteArray = immutableCheckpointBytes.copyOf()
}

class PublishedTeacherLayerCheckpointExport internal constructor(
    checkpointBytes: ByteArray,
    val layerSha256: String,
) {
    private val immutableCheckpointBytes = checkpointBytes.copyOf()

    val checkpointBytes: ByteArray get() = immutableCheckpointBytes.copyOf()
    val checkpointSizeBytes: Int get() = immutableCheckpointBytes.size

    fun copyCheckpointBytes(): ByteArray = immutableCheckpointBytes.copyOf()
}

data class StudentLayerDeltaApplyResult(
    val layerSha256: String,
    val sourceOriginCursor: Long,
    val snapshot: AnnotationSnapshot,
    val changed: Boolean,
)

data class TeacherReviewPublishIntent(
    val bookId: String,
    val pageNumber: Int,
    val attemptNo: Int,
    val updatedAtEpochMillis: Long,
    val publicationId: String = "",
    val checkpointSha256: String = "",
    val resultLayerSha256: String = "",
    val checkpointSizeBytes: Int = 0,
    val markGroupsSha256: String = "",
    val markGroupsSizeBytes: Int = 0,
    /** Pair-scoped Telegram ownership captured before this immutable publication is prepared. */
    val remotePairId: String? = null,
    val remoteWorkbookToken: String? = null,
    val remoteManifestGeneration: Long = 0L,
    val remoteManifestSequence: Long = 0L,
) {
    init {
        require(bookId.isNotBlank()) { "Publish intent book id cannot be blank" }
        require(pageNumber >= 0) { "Publish intent page cannot be negative" }
        require(attemptNo > 0) { "Publish intent attempt must be positive" }
        require(updatedAtEpochMillis >= 0L) { "Publish intent timestamp cannot be negative" }
        val legacy = publicationId.isEmpty() && checkpointSha256.isEmpty() &&
            resultLayerSha256.isEmpty() && checkpointSizeBytes == 0 &&
            markGroupsSha256.isEmpty() && markGroupsSizeBytes == 0
        val checkpointOnly = publicationId.isNotEmpty() && checkpointSha256.isNotEmpty() &&
            resultLayerSha256.isNotEmpty() && checkpointSizeBytes > 0 &&
            markGroupsSha256.isEmpty() && markGroupsSizeBytes == 0
        require(legacy || publicationId.matches(Regex("[0-9a-f]{64}"))) {
            "Publish intent publication id is invalid"
        }
        require(legacy || checkpointSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Publish intent checkpoint digest is invalid"
        }
        require(legacy || resultLayerSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Publish intent layer digest is invalid"
        }
        require(legacy || checkpointSizeBytes in 1..PageOperationLogStore.MAX_PUBLISHED_TEACHER_LAYER_CHECKPOINT_BYTES) {
            "Publish intent checkpoint size is invalid"
        }
        require(legacy || checkpointOnly || markGroupsSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Publish intent grade digest is invalid"
        }
        require(
            legacy || checkpointOnly ||
                markGroupsSizeBytes in 1..PageOperationLogStore.MAX_TEACHER_REVIEW_MARK_GROUP_BYTES
        ) {
            "Publish intent grade snapshot size is invalid"
        }
        require(remotePairId == null || remotePairId.isNotBlank()) {
            "Publish intent remote pair id cannot be blank"
        }
        require(remoteWorkbookToken == null || remoteWorkbookToken.isNotBlank()) {
            "Publish intent remote workbook token cannot be blank"
        }
        require(remotePairId != null ||
            remoteWorkbookToken == null && remoteManifestGeneration == 0L && remoteManifestSequence == 0L
        ) { "Publish intent remote ownership requires a pair id" }
        require(remoteManifestGeneration >= 0L && remoteManifestSequence >= 0L) {
            "Publish intent remote manifest cursor cannot be negative"
        }
    }
}

/**
 * Durable proof of the latest explicit teacher publication installed for one exact attempt.
 *
 * [remotePairId] and [remoteWorkbookToken] keep Telegram inventory evidence scoped to the exact
 * paired workbook. LAN may still compare the same receipt without filters because its authenticated
 * socket already pins the peer device and workbook.
 */
data class AppliedTeacherReviewReceipt(
    val bookId: String,
    val pageNumber: Int,
    val attemptNo: Int,
    val publicationId: String,
    val resultLayerSha256: String,
    val markGroupsSha256: String,
    val appliedAtEpochMillis: Long,
    /** Teacher-side frozen publication order shared by LAN and Telegram; zero means legacy/unknown. */
    val publishedAtEpochMillis: Long = 0L,
    val remotePairId: String? = null,
    val remoteWorkbookToken: String? = null,
) {
    init {
        require(bookId.isNotBlank()) { "Applied teacher review book id cannot be blank" }
        require(pageNumber >= 0) { "Applied teacher review page cannot be negative" }
        require(attemptNo > 0) { "Applied teacher review attempt must be positive" }
        require(publicationId.matches(Regex("[0-9a-f]{64}"))) {
            "Applied teacher review publication id is invalid"
        }
        require(resultLayerSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Applied teacher review layer digest is invalid"
        }
        require(markGroupsSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Applied teacher review grade digest is invalid"
        }
        require(appliedAtEpochMillis >= 0L) { "Applied teacher review timestamp cannot be negative" }
        require(publishedAtEpochMillis >= 0L) {
            "Applied teacher review publication timestamp cannot be negative"
        }
        require(remotePairId == null || remotePairId.isNotBlank()) {
            "Applied teacher review pair id cannot be blank"
        }
        require(remoteWorkbookToken == null || remoteWorkbookToken.isNotBlank()) {
            "Applied teacher review workbook token cannot be blank"
        }
    }

    fun evidence(pageMetadataSha256: String): TeacherReviewStateEvidence = TeacherReviewStateEvidence(
        attemptNo = attemptNo,
        publicationId = publicationId,
        resultLayerSha256 = resultLayerSha256,
        markGroupsSha256 = teacherReviewGradeStateSha256(
            markGroupsSha256,
            pageMetadataSha256,
        ),
    )
}

/**
 * Produces a strict per-target publication order across every durable crash-boundary witness.
 * The epoch value is an ordering token, not a claim that a rolled-back wall clock is current.
 */
internal fun nextTeacherReviewPublicationTimestamp(
    requestedEpochMillis: Long,
    authorityEpochMillis: Long?,
    outboxEpochMillis: Long?,
    preparationEpochMillis: Long?,
): Long {
    val witnesses = listOfNotNull(
        authorityEpochMillis,
        outboxEpochMillis,
        preparationEpochMillis,
    )
    require(requestedEpochMillis >= 0L && witnesses.all { it >= 0L }) {
        "Teacher review publication timestamp cannot be negative"
    }
    val previous = witnesses.maxOrNull() ?: return requestedEpochMillis
    require(previous < Long.MAX_VALUE) { "Teacher review publication order is exhausted" }
    return maxOf(requestedEpochMillis, previous + 1L)
}

enum class TeacherReviewPublicationOrderDisposition {
    /** A strictly older or unknown-order frame behind a sequenced receipt. */
    STALE,
    /** The same sequenced publication; verify actual ink/grade metadata before acknowledging. */
    DUPLICATE_VERIFY,
    /** Two different publications claim the same sequence and neither may overwrite the other. */
    CONFLICT,
    /** No ordered receipt exists, or this publication is strictly newer. */
    APPLY,
}

/** Shared LAN/Telegram ordering rule for one already pair-scoped exact target. */
fun teacherReviewPublicationOrderDisposition(
    current: AppliedTeacherReviewReceipt?,
    incomingPublicationId: String,
    incomingPublishedAtEpochMillis: Long,
): TeacherReviewPublicationOrderDisposition {
    require(incomingPublicationId.matches(Regex("[0-9a-f]{64}"))) {
        "Incoming teacher review publication id is invalid"
    }
    require(incomingPublishedAtEpochMillis >= 0L) {
        "Incoming teacher review publication timestamp cannot be negative"
    }
    if (current == null || current.publishedAtEpochMillis == 0L) {
        return TeacherReviewPublicationOrderDisposition.APPLY
    }
    return when {
        incomingPublishedAtEpochMillis == 0L ||
            incomingPublishedAtEpochMillis < current.publishedAtEpochMillis ->
            TeacherReviewPublicationOrderDisposition.STALE
        incomingPublishedAtEpochMillis > current.publishedAtEpochMillis ->
            TeacherReviewPublicationOrderDisposition.APPLY
        incomingPublicationId == current.publicationId ->
            TeacherReviewPublicationOrderDisposition.DUPLICATE_VERIFY
        else -> TeacherReviewPublicationOrderDisposition.CONFLICT
    }
}

/** Process-global stripes shared even by separately constructed store holders in one app process. */
private object TeacherReviewTargetLocks {
    private const val STRIPE_COUNT = 64
    private val stripes = Array(STRIPE_COUNT) { Any() }

    fun lock(bookId: String, pageNumber: Int, attemptNo: Int): Any {
        var hash = bookId.hashCode()
        hash = 31 * hash + pageNumber
        hash = 31 * hash + attemptNo
        hash = hash xor (hash ushr 16)
        return stripes[hash and (STRIPE_COUNT - 1)]
    }
}

class TeacherReviewPublicationArtifact internal constructor(
    val intent: TeacherReviewPublishIntent,
    checkpointBytes: ByteArray,
    val markGroups: List<MarkGroup>,
) {
    private val immutableCheckpointBytes = checkpointBytes.copyOf()
    fun copyCheckpointBytes(): ByteArray = immutableCheckpointBytes.copyOf()
}

private data class DecodedStudentLayerCheckpoint(
    val checkpointId: String,
    val sourcePageNumber: Int,
    val sourceOperationClockHighWater: Long,
    val assets: List<StrokeAsset>,
    val activeStrokeIds: Set<StrokeId>,
)

private data class DecodedPublishedTeacherLayerCheckpoint(
    val checkpointId: String,
    val sourcePageNumber: Int,
    val attemptNo: Int,
    val sourceOperationClockHighWater: Long,
    val assets: List<StrokeAsset>,
    val activeStrokeIds: Set<StrokeId>,
)

private data class PortableLayer(
    val assets: List<StrokeAsset>,
    val activeStrokeIds: Set<StrokeId>,
    val sha256: String,
)

private data class DecodedPageCheckpoint(
    val snapshot: AnnotationSnapshot,
    val maximumClockByDevice: Map<String, Long>,
)

private data class FrozenTeacherReviewPublication(
    val intent: TeacherReviewPublishIntent,
    val checkpointBytes: ByteArray,
    val markGroupsBytes: ByteArray,
)

/**
 * Snapshot plus the durable operation clock that cannot be reconstructed from assets alone.
 * Keeping this runtime-only avoids changing the existing checkpoint/snapshot file format.
 */
data class StoredAnnotationPage(
    val snapshot: AnnotationSnapshot,
    val operationClockHighWater: Long,
)

/**
 * Append-only annotation persistence partitioned by (book, page). A checkpoint is only a loading
 * accelerator; durable operations remain the source of truth and are retained for offline peers.
 */
class PageOperationLogStore(
    private val rootDirectory: File,
    checkpointInterval: Int = DEFAULT_CHECKPOINT_INTERVAL,
    private val beforeTeacherReviewWorkbookOutboxPersist: (() -> Unit)? = null,
) {
    private val checkpointInterval = checkpointInterval.coerceAtLeast(1)
    /**
     * Page indexes contain the decoded checkpoint, every retained operation, and encoded copies
     * used by delta sync. Keeping one for every page visited by a Telegram manifest can therefore
     * retain hundreds of megabytes even though the durable files are already on disk.
     *
     * Access order keeps the pages used most recently hot. Eviction is safe because append and
     * remote-apply paths fsync their operation before mutating the index; a miss rebuilds the same
     * state from the checkpoint plus append log while this store's monitor is held.
     */
    private val pageIndexes = LinkedHashMap<PageKey, PageIndex>(
        MAX_CACHED_PAGE_INDEXES + 1,
        0.75f,
        true,
    )
    /**
     * A portable digest is stable for one durable page revision. Cache only that tiny value so a
     * manifest revisit does not rematerialize canonical JSON or defeat the bounded page cache.
     */
    private val studentLayerDigests = LinkedHashMap<PageKey, StudentLayerDigest>(
        MAX_CACHED_STUDENT_LAYER_DIGESTS + 1,
        0.75f,
        true,
    )
    /**
     * Exact structural cache for installed published-teacher layers. Page revisions are too broad:
     * ordinary student writing changes them every few seconds. The canonical active teacher-id
     * signature changes only when this attempt's published layer can change; immutable assets make
     * an equal signature an exact witness rather than a TTL guess.
     */
    private val publishedTeacherLayerDigests =
        LinkedHashMap<TeacherReviewIntentKey, PublishedTeacherLayerDigest>(
            MAX_CACHED_PUBLISHED_TEACHER_LAYER_DIGESTS + 1,
            0.75f,
            true,
        )
    /** Mutation witness independent of the three-entry decoded page LRU. */
    private val publishedTeacherLayerGenerations =
        LinkedHashMap<TeacherReviewIntentKey, Long>(
            MAX_CACHED_PUBLISHED_TEACHER_LAYER_DIGESTS + 1,
            0.75f,
            true,
        )
    private var publishedTeacherLayerDigestMaterializations = 0L
    private val teacherReviewPublishIntents = linkedMapOf<TeacherReviewIntentKey, TeacherReviewPublishIntent>()
    /** Latest explicit publish per page/attempt, retained after its transport outbox is ACKed. */
    private val teacherReviewAuthorities = linkedMapOf<TeacherReviewIntentKey, TeacherReviewAuthorityRecord>()
    /** Latest successfully installed publication per page/attempt on the receiving device. */
    private val appliedTeacherReviewReceipts = linkedMapOf<TeacherReviewIntentKey, AppliedTeacherReviewReceipt>()
    /**
     * Bridges the tiny in-process race where the coordinator promotes and LAN acknowledges an
     * intent before the Reader's original publish call returns. A restarted process cannot still
     * have that caller on its stack, so this cache deliberately does not need disk persistence.
     */
    private val recentlyPromotedTeacherReviewPublications =
        linkedMapOf<String, TeacherReviewPublishIntent>()
    private var teacherReviewPublishIntentsLoaded = false
    private var teacherReviewStateLoaded = false

    constructor(context: Context, checkpointInterval: Int = DEFAULT_CHECKPOINT_INTERVAL) : this(
        File(context.filesDir, "masternote/annotation-pages"),
        checkpointInterval,
    )

    init {
        check(rootDirectory.mkdirs() || rootDirectory.isDirectory) { "Cannot create annotation directory" }
        loadTeacherReviewPublishIntents()
        loadTeacherReviewState()
    }

    /**
     * Runs [block] while append, checkpoint, and in-memory page-index mutations are excluded.
     * Backup code must finish reading [rootDirectory] before the block returns.
     */
    @Synchronized
    fun <T> withStableDataRoot(block: (File) -> T): T = block(rootDirectory)

    @Synchronized
    fun loadPage(bookId: String, pageNumber: Int): AnnotationSnapshot =
        pageIndex(bookId, pageNumber).snapshot

    @Synchronized
    fun loadPageState(bookId: String, pageNumber: Int): StoredAnnotationPage {
        val index = pageIndex(bookId, pageNumber)
        return StoredAnnotationPage(
            snapshot = index.snapshot,
            operationClockHighWater = index.maximumClockByDevice.values.maxOrNull() ?: 0L,
        )
    }

    private fun readPageIndex(bookId: String, pageNumber: Int): PageIndex {
        val directory = pageDirectory(bookId, pageNumber)
        val checkpointFile = File(directory, CHECKPOINT_FILE)
        val checkpoint = if (checkpointFile.exists()) {
            readCheckpointSafely(checkpointFile)
        } else {
            DecodedPageCheckpoint(
                snapshot = AnnotationSnapshot.empty(bookId, pageNumber),
                maximumClockByDevice = emptyMap(),
            )
        }
        var snapshot = checkpoint.snapshot
        validatePartition(snapshot, bookId, pageNumber)

        val records = mutableListOf<IndexedOperation>()
        val byDevice = mutableMapOf<String, MutableList<IndexedOperation>>()
        val maximumClocks = checkpoint.maximumClockByDevice.toMutableMap()
        // Legacy checkpoints did not carry operation-only clocks. Asset clocks are still a safe
        // lower bound and avoid needless replay when their original append log is unavailable.
        snapshot.assets.values.forEach { asset ->
            maximumClocks[asset.deviceId] = maxOf(
                maximumClocks[asset.deviceId] ?: 0L,
                asset.logicalClock,
            )
        }
        val logFile = File(directory, LOG_FILE)
        if (!logFile.exists()) return PageIndex(snapshot, records, byDevice, maximumClocks)
        try {
            logFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEachIndexed { index, line ->
                    if (line.isBlank()) return@forEachIndexed
                    val record = readAnnotationDataOrHandleCorruption(
                        read = { decodeRecord(JSONObject(line)) },
                        onCorruption = { error ->
                            throw IOException("Invalid operation at line ${index + 1}", error)
                        },
                    )
                    require(record.bookId == bookId && record.pageNumber == pageNumber) {
                        "Operation partition identity mismatch"
                    }
                    val encoded = line.toByteArray(Charsets.UTF_8)
                    val indexed = IndexedOperation(record, encoded)
                    records += indexed
                    byDevice.getOrPut(record.operation.deviceId, ::mutableListOf) += indexed
                    maximumClocks[record.operation.deviceId] = maxOf(
                        maximumClocks[record.operation.deviceId] ?: 0L,
                        record.operation.logicalClock,
                    )
                    if (record.revision > snapshot.revision) {
                        snapshot = apply(snapshot, record)
                    }
                }
            }
        } catch (error: Exception) { quarantineAndThrow(logFile, error) }
        byDevice.values.forEach { operations -> operations.sortBy { it.record.operation.logicalClock } }
        validatePartition(snapshot, bookId, pageNumber)
        return PageIndex(snapshot, records, byDevice, maximumClocks)
    }

    @Synchronized
    fun append(change: AnnotationChange): AnnotationSnapshot {
        val proposed = change.snapshot
        val index = pageIndex(proposed.bookId, proposed.pageNumber)
        val current = index.snapshot
        if (change.operation.id in current.appliedOperationIds) return current
        val directory = pageDirectory(proposed.bookId, proposed.pageNumber)
        // A Reader edit can be produced just before the LAN service appends a remote operation.
        // Persisting change.snapshot verbatim would then replace the newer in-memory index and give
        // two log records the same revision. Store operations are the source of truth, so always
        // merge the local operation into the latest durable snapshot while holding this store lock.
        val record = StoredOperationRecord(
            proposed.bookId,
            proposed.pageNumber,
            current.revision + 1L,
            change.operation,
            change.addedAssets,
            System.currentTimeMillis(),
        ).immutableCopy()
        val line = encodeRecord(record, LOCAL_POINT_ENCODING).toString()
        val logFile = File(directory, LOG_FILE)
        FileOutputStream(logFile, true).use { output ->
            output.write(line.toByteArray(Charsets.UTF_8))
            output.write('\n'.code)
            output.flush()
            output.fd.sync()
        }
        val merged = apply(current, record)
        index.add(record, line.toByteArray(Charsets.UTF_8), merged)
        recordPublishedTeacherLayerMutation(
            proposed.bookId,
            proposed.pageNumber,
            current,
            record,
        )
        MasterNoteDataCommitBus.recordDurableCommit()
        if (merged.revision % checkpointInterval == 0L) writeCheckpoint(merged)
        return merged
    }

    /**
     * Persists the current in-process snapshot; it is not a data-import/replacement API.
     * Restore code must replace the stable root and call [resetCachedStateAfterRestore].
     */
    @Synchronized
    fun writeCheckpoint(snapshot: AnnotationSnapshot) {
        val directory = pageDirectory(snapshot.bookId, snapshot.pageNumber)
        val cached = pageIndexes[PageKey(snapshot.bookId, snapshot.pageNumber)]
            ?.takeIf { it.snapshot === snapshot }
        val maximumClocks = cached?.maximumClockByDevice.orEmpty().toMutableMap()
        snapshot.assets.values.forEach { asset ->
            maximumClocks[asset.deviceId] = maxOf(
                maximumClocks[asset.deviceId] ?: 0L,
                asset.logicalClock,
            )
        }
        atomicWriteCheckpoint(
            File(directory, CHECKPOINT_FILE),
            snapshot,
            maximumClocks,
        )
    }

    /**
     * GC must not rotate the only operation history while a paired device may still request it.
     * Until peer acknowledgement watermarks are persisted, retaining the append log is the only
     * safe policy for offline merge. Orphan compaction can be re-enabled once every paired peer is
     * known to have crossed a checkpoint.
     */
    @Synchronized
    fun garbageCollectOrphans(
        snapshot: AnnotationSnapshot,
        validAttemptNumbers: Set<Int>,
    ): AnnotationSnapshot {
        @Suppress("UNUSED_VARIABLE") val attemptsKeptForFuturePeerWatermarks = validAttemptNumbers
        return snapshot
    }

    fun operationLogFile(bookId: String, pageNumber: Int): File =
        File(pageDirectory(bookId, pageNumber), LOG_FILE)

    fun encodedOperationsAfter(
        bookId: String,
        pageNumber: Int,
        revision: Long,
        pointEncoding: AnnotationPointEncoding = AnnotationPointEncoding.LEGACY_FLOAT_ARRAYS,
    ): List<ByteArray> {
        val captured = synchronized(this) {
            val records = pageIndex(bookId, pageNumber).records
            val start = records.firstIndexAfterRevision(revision)
            records.subList(start, records.size).map(IndexedOperation::record)
        }
        return captured.map { it.encodeBytes(pointEncoding) }
    }

    fun encodedOperationsAfter(
        bookId: String,
        pageNumber: Int,
        originDeviceId: String,
        logicalClock: Long,
        includeTeacherDrafts: Boolean = true,
        pointEncoding: AnnotationPointEncoding = AnnotationPointEncoding.LEGACY_FLOAT_ARRAYS,
    ): List<ByteArray> {
        val captured = synchronized(this) {
            val index = pageIndex(bookId, pageNumber)
            val records = index.byDevice[originDeviceId].orEmpty()
            val start = records.firstIndexAfterClock(logicalClock)
            records.subList(start, records.size)
                .asSequence()
                .filter { includeTeacherDrafts || it.record.isPublishable(index.snapshot) }
                .map(IndexedOperation::record)
                .toList()
        }
        return captured.map { it.encodeBytes(pointEncoding) }
    }

    /**
     * Copies at most one wire-sized prefix and stops at the first matching record that would exceed
     * the caller's frame budget. Unlike [encodedOperationsAfter], a multi-megabyte offline log is
     * never duplicated in full merely to discover that it cannot fit one delta document.
     */
    fun encodedOperationsAfterBounded(
        bookId: String,
        pageNumber: Int,
        originDeviceId: String,
        logicalClock: Long,
        maxFramedBytes: Int,
        fixedFrameBytes: Int = 0,
        perOperationFrameBytes: Int = 0,
        includeTeacherDrafts: Boolean = true,
        pointEncoding: AnnotationPointEncoding = AnnotationPointEncoding.LEGACY_FLOAT_ARRAYS,
    ): BoundedEncodedOperationBatch {
        require(originDeviceId.isNotBlank()) { "Origin device id cannot be blank" }
        require(logicalClock >= 0L) { "Operation clock cannot be negative" }
        require(maxFramedBytes > 0 && fixedFrameBytes in 0..maxFramedBytes)
        require(perOperationFrameBytes >= 0)
        val captured = synchronized(this) {
            val index = pageIndex(bookId, pageNumber)
            val records = index.byDevice[originDeviceId].orEmpty()
            val start = records.firstIndexAfterClock(logicalClock)
            records.subList(start, records.size)
                .asSequence()
                .filter { includeTeacherDrafts || it.record.isPublishable(index.snapshot) }
                .map(IndexedOperation::record)
                .toList()
        }
        val copied = ArrayList<ByteArray>()
        var framedBytes = fixedFrameBytes
        var lastClock: Long? = null
        for (record in captured) {
            val encoded = record.encodeBytes(pointEncoding)
            val nextSize = perOperationFrameBytes.toLong() + encoded.size.toLong()
            if (framedBytes.toLong() + nextSize > maxFramedBytes.toLong()) {
                return BoundedEncodedOperationBatch(copied, framedBytes, false, lastClock)
            }
            copied += encoded
            framedBytes += nextSize.toInt()
            lastClock = record.operation.logicalClock
        }
        return BoundedEncodedOperationBatch(copied, framedBytes, true, lastClock)
    }

    /** Operations accepted by [appendEncodedStudentOperation], regardless of this device's old role. */
    fun encodedStudentOperationsAfter(
        bookId: String,
        pageNumber: Int,
        originDeviceId: String,
        logicalClock: Long,
        pointEncoding: AnnotationPointEncoding = AnnotationPointEncoding.LEGACY_FLOAT_ARRAYS,
    ): List<ByteArray> {
        val captured = synchronized(this) {
            val index = pageIndex(bookId, pageNumber)
            val records = index.byDevice[originDeviceId].orEmpty()
            val start = records.firstIndexAfterClock(logicalClock)
            records.subList(start, records.size)
                .asSequence()
                .filter { indexed ->
                    runCatching {
                        validateStudentOperationRecord(indexed.record, pageNumber, index.snapshot)
                    }.isSuccess
                }
                .map(IndexedOperation::record)
                .toList()
        }
        return captured.map { it.encodeBytes(pointEncoding) }
    }

    @Synchronized
    fun maxOperationClock(bookId: String, pageNumber: Int, originDeviceId: String): Long {
        return pageIndex(bookId, pageNumber).maximumClockByDevice[originDeviceId] ?: 0L
    }

    /** Lightweight delta/checkpoint estimate for a single page and one local operation origin. */
    @Synchronized
    fun pageOperationSyncStats(
        bookId: String,
        pageNumber: Int,
        originDeviceId: String,
        afterLogicalClock: Long = 0L,
        includeTeacherDrafts: Boolean = true,
    ): PageOperationSyncStats {
        require(originDeviceId.isNotBlank()) { "Origin device id cannot be blank" }
        require(afterLogicalClock >= 0L) { "Operation clock cannot be negative" }
        val index = pageIndex(bookId, pageNumber)
        val originRecords = index.byDevice[originDeviceId].orEmpty()
        val firstPending = originRecords.firstIndexAfterClock(afterLogicalClock)
        var pendingEncodedByteCount = 0L
        var pendingOperationCount = 0
        originRecords.subList(firstPending, originRecords.size).forEach { pending ->
            if (includeTeacherDrafts || pending.record.isPublishable(index.snapshot)) {
                pendingEncodedByteCount = if (Long.MAX_VALUE - pendingEncodedByteCount < pending.encoded.size) {
                    Long.MAX_VALUE
                } else {
                    pendingEncodedByteCount + pending.encoded.size
                }
                pendingOperationCount++
            }
        }
        val logFile = operationLogFile(bookId, pageNumber).takeIf(File::isFile)
        val recordedMutationEpochMillis = originRecords.asSequence()
            .filter { includeTeacherDrafts || it.record.isPublishable(index.snapshot) }
            .maxOfOrNull { it.record.recordedAtEpochMillis }
            ?.coerceAtLeast(0L)
            ?: 0L
        return PageOperationSyncStats(
            logByteCount = logFile?.length() ?: 0L,
            pendingEncodedByteCount = pendingEncodedByteCount,
            pendingOperationCount = pendingOperationCount,
            originDeviceHighWater = index.maximumClockByDevice[originDeviceId] ?: 0L,
            lastMutationEpochMillis = recordedMutationEpochMillis.takeIf { it > 0L }
                ?: logFile?.lastModified()?.coerceAtLeast(0L)
                ?: 0L,
        )
    }

    /**
     * Encodes the complete active student layer without carrying the installation-local book id.
     *
     * Inactive parents are included only when an active student asset references them. This keeps
     * partial-erase lineage and reactivated/redo assets self-contained without exporting teacher
     * layers or unrelated historical assets.
     */
    fun studentLayerSha256(bookId: String, pageNumber: Int): String {
        val key = PageKey(bookId, pageNumber)
        val snapshot = synchronized(this) {
            val captured = pageIndex(bookId, pageNumber).snapshot
            studentLayerDigests[key]
                ?.takeIf { it.revision == captured.revision }
                ?.let { return it.sha256 }
            captured
        }
        // AnnotationSnapshot and StrokeAsset are immutable. Canonicalizing a large page outside
        // the store monitor keeps Reader/LAN append calls responsive while inventory hashes it.
        val digest = portableStudentLayer(snapshot).sha256
        synchronized(this) {
            val cachedRevision = studentLayerDigests[key]?.revision ?: Long.MIN_VALUE
            if (snapshot.revision >= cachedRevision) {
                studentLayerDigests[key] = StudentLayerDigest(snapshot.revision, digest)
            }
            while (studentLayerDigests.size > MAX_CACHED_STUDENT_LAYER_DIGESTS) {
                studentLayerDigests.entries.iterator().apply {
                    next()
                    remove()
                }
            }
        }
        return digest
    }

    /**
     * Captures one immutable snapshot and its origin cursor under the same short store lock, then
     * encodes the potentially large checkpoint after releasing it. Returned bytes are defensively
     * copied on every access.
     */
    fun exportStudentLayerCheckpoint(
        bookId: String,
        pageNumber: Int,
        originDeviceId: String,
        pointEncoding: AnnotationPointEncoding = AnnotationPointEncoding.LEGACY_FLOAT_ARRAYS,
    ): StudentLayerCheckpointExport {
        require(originDeviceId.isNotBlank() && originDeviceId.length <= MAX_CHECKPOINT_IDENTIFIER_CHARS) {
            "Student checkpoint origin device id is invalid"
        }
        val captured = synchronized(this) {
            val index = pageIndex(bookId, pageNumber)
            StudentCheckpointCapture(
                snapshot = index.snapshot,
                originDeviceHighWater = index.maximumClockByDevice[originDeviceId] ?: 0L,
            )
        }
        return buildStudentLayerCheckpointExport(
            pageNumber = pageNumber,
            snapshot = captured.snapshot,
            originDeviceHighWater = captured.originDeviceHighWater,
            pointEncoding = pointEncoding,
        )
    }

    fun encodeStudentLayerCheckpoint(
        bookId: String,
        pageNumber: Int,
        pointEncoding: AnnotationPointEncoding = AnnotationPointEncoding.LEGACY_FLOAT_ARRAYS,
    ): ByteArray {
        val snapshot = synchronized(this) { pageIndex(bookId, pageNumber).snapshot }
        return buildStudentLayerCheckpointExport(
            pageNumber = pageNumber,
            snapshot = snapshot,
            originDeviceHighWater = 0L,
            pointEncoding = pointEncoding,
        ).copyCheckpointBytes()
    }

    /**
     * Replaces only the active student layer with one durable operation. Existing teacher assets
     * and every inactive historical asset remain in the append-only page history.
     */
    @Synchronized
    fun applyStudentLayerCheckpoint(
        localBookId: String,
        pageNumber: Int,
        checkpointBytes: ByteArray,
        expectedResultLayerSha256: String? = null,
        allowedAttemptNos: Collection<Int>? = null,
    ): StudentLayerCheckpointApplyResult {
        val decoded = decodeStudentLayerCheckpoint(checkpointBytes)
        require(decoded.sourcePageNumber == pageNumber) { "Student layer checkpoint page mismatch" }
        val index = pageIndex(localBookId, pageNumber)
        val current = index.snapshot
        val incomingLayer = portableStudentLayer(
            pageNumber = pageNumber,
            assets = decoded.assets,
            activeStrokeIds = decoded.activeStrokeIds,
        )
        require(expectedResultLayerSha256 == null || incomingLayer.sha256 == expectedResultLayerSha256) {
            "Student checkpoint result layer digest mismatch"
        }
        allowedAttemptNos?.let { allowed ->
            require(allowed.all { it > 0 } && decoded.assets.all { it.attemptNo in allowed }) {
                "Student checkpoint contains an unlisted attempt"
            }
        }
        val currentLayer = portableStudentLayer(current)
        decoded.assets.forEach { incoming ->
            val existing = current.assets[incoming.id]
            require(existing == null || existing == incoming) {
                "Student checkpoint asset collides with existing ink"
            }
        }
        if (currentLayer.sha256 == incomingLayer.sha256) {
            return StudentLayerCheckpointApplyResult(
                checkpointId = decoded.checkpointId,
                layerSha256 = incomingLayer.sha256,
                snapshot = current,
                changed = false,
            )
        }
        val currentStudentIds = current.activeStrokeIds.filterTo(linkedSetOf()) { id ->
            current.assets[id]?.authorId == STUDENT_AUTHOR_ID
        }
        val operationId = checkpointTransitionOperationId(
            prefix = STUDENT_CHECKPOINT_OPERATION_PREFIX,
            checkpointId = decoded.checkpointId,
            currentLayerSha256 = currentLayer.sha256,
            currentRevision = current.revision,
        )
        val operation = AssetOperation(
            id = operationId,
            removedStrokeIds = currentStudentIds,
            addedStrokeIds = decoded.activeStrokeIds,
            logicalClock = maxOf(1L, decoded.sourceOperationClockHighWater),
            deviceId = STUDENT_CHECKPOINT_OPERATION_DEVICE_ID,
        )
        val record = StoredOperationRecord(
            bookId = localBookId,
            pageNumber = pageNumber,
            revision = current.revision + 1L,
            operation = operation,
            addedAssets = decoded.assets,
        )
        // The checkpoint is the atomic durable commit. Keeping a multi-MiB replacement as one
        // operations.log row would duplicate it on disk and in every PageIndex reload; older rows
        // remain untouched for delta history.
        val updated = commitSyntheticCheckpoint(index, record)
        check(portableStudentLayer(updated).sha256 == incomingLayer.sha256) {
            "Student checkpoint replacement produced a different layer"
        }
        return StudentLayerCheckpointApplyResult(
            checkpointId = decoded.checkpointId,
            layerSha256 = incomingLayer.sha256,
            snapshot = updated,
            changed = true,
        )
    }

    /** Encodes one attempt's active, published teacher layer without the installation-local book id. */
    @Synchronized
    fun publishedTeacherLayerSha256(bookId: String, pageNumber: Int, attemptNo: Int): String {
        require(attemptNo > 0) { "Published teacher checkpoint attempt must be positive" }
        val key = TeacherReviewIntentKey(bookId, pageNumber, attemptNo)
        val generation = publishedTeacherLayerGenerations[key] ?: 0L
        publishedTeacherLayerDigests[key]
            ?.takeIf { it.mutationGeneration == generation }
            ?.let { return it.sha256 }
        return cachedPublishedTeacherLayerSha256(
            bookId,
            pageNumber,
            attemptNo,
            pageIndex(bookId, pageNumber).snapshot,
        )
    }

    /** Captures one published teacher layer and its digest under the same store lock. */
    @Synchronized
    fun exportPublishedTeacherLayerCheckpoint(
        bookId: String,
        pageNumber: Int,
        attemptNo: Int,
        pointEncoding: AnnotationPointEncoding = AnnotationPointEncoding.LEGACY_FLOAT_ARRAYS,
    ): PublishedTeacherLayerCheckpointExport {
        require(attemptNo > 0) { "Published teacher checkpoint attempt must be positive" }
        return buildPublishedTeacherLayerCheckpointExport(
            pageNumber = pageNumber,
            attemptNo = attemptNo,
            snapshot = pageIndex(bookId, pageNumber).snapshot,
            pointEncoding = pointEncoding,
        )
    }

    @Synchronized
    fun encodePublishedTeacherLayerCheckpoint(
        bookId: String,
        pageNumber: Int,
        attemptNo: Int,
        pointEncoding: AnnotationPointEncoding = AnnotationPointEncoding.LEGACY_FLOAT_ARRAYS,
    ): ByteArray {
        require(attemptNo > 0) { "Published teacher checkpoint attempt must be positive" }
        return buildPublishedTeacherLayerCheckpointExport(
            pageNumber = pageNumber,
            attemptNo = attemptNo,
            snapshot = pageIndex(bookId, pageNumber).snapshot,
            pointEncoding = pointEncoding,
        ).copyCheckpointBytes()
    }

    /**
     * Atomically replaces only one attempt's active, published teacher layer. Student ink, teacher
     * drafts, and every other teacher attempt remain active exactly as they were.
     */
    @Synchronized
    fun applyPublishedTeacherLayerCheckpoint(
        localBookId: String,
        pageNumber: Int,
        attemptNo: Int,
        checkpointBytes: ByteArray,
        expectedResultLayerSha256: String? = null,
    ): PublishedTeacherLayerCheckpointApplyResult {
        require(attemptNo > 0) { "Published teacher checkpoint attempt must be positive" }
        val decoded = decodePublishedTeacherLayerCheckpoint(checkpointBytes.copyOf())
        require(decoded.sourcePageNumber == pageNumber) { "Published teacher checkpoint page mismatch" }
        require(decoded.attemptNo == attemptNo) { "Published teacher checkpoint attempt mismatch" }
        val index = pageIndex(localBookId, pageNumber)
        val current = index.snapshot
        val incomingLayer = portablePublishedTeacherLayer(
            pageNumber = pageNumber,
            attemptNo = attemptNo,
            assets = decoded.assets,
            activeStrokeIds = decoded.activeStrokeIds,
        )
        require(expectedResultLayerSha256 == null || incomingLayer.sha256 == expectedResultLayerSha256) {
            "Published teacher checkpoint result layer digest mismatch"
        }
        val currentLayerSha256 = cachedPublishedTeacherLayerSha256(
            localBookId,
            pageNumber,
            attemptNo,
            current,
        )
        decoded.assets.forEach { incoming ->
            val existing = current.assets[incoming.id]
            require(existing == null || existing == incoming) {
                "Published teacher checkpoint asset collides with existing ink"
            }
        }
        if (currentLayerSha256 == incomingLayer.sha256) {
            cachePublishedTeacherLayerSha256(
                localBookId,
                pageNumber,
                attemptNo,
                current,
                incomingLayer.sha256,
            )
            return PublishedTeacherLayerCheckpointApplyResult(
                checkpointId = decoded.checkpointId,
                layerSha256 = incomingLayer.sha256,
                snapshot = current,
                changed = false,
            )
        }
        val currentPublishedIds = current.activeStrokeIds.filterTo(linkedSetOf()) { id ->
            current.assets[id]?.let { asset ->
                asset.authorId == TEACHER_AUTHOR_ID && asset.attemptNo == attemptNo &&
                    asset.publishedAtEpochMillis != null
            } == true
        }
        val operationId = checkpointTransitionOperationId(
            prefix = PUBLISHED_TEACHER_CHECKPOINT_OPERATION_PREFIX,
            checkpointId = decoded.checkpointId,
            currentLayerSha256 = currentLayerSha256,
            currentRevision = current.revision,
        )
        val operation = AssetOperation(
            id = operationId,
            removedStrokeIds = currentPublishedIds,
            addedStrokeIds = decoded.activeStrokeIds,
            logicalClock = maxOf(1L, decoded.sourceOperationClockHighWater),
            deviceId = PUBLISHED_TEACHER_CHECKPOINT_OPERATION_DEVICE_ID,
        )
        val record = StoredOperationRecord(
            bookId = localBookId,
            pageNumber = pageNumber,
            revision = current.revision + 1L,
            operation = operation,
            addedAssets = decoded.assets,
        )
        val updated = appendSyntheticRecord(index, record)
        check(cachedPublishedTeacherLayerSha256(
            localBookId,
            pageNumber,
            attemptNo,
            updated,
        ) == incomingLayer.sha256) {
            "Published teacher checkpoint replacement produced a different layer"
        }
        return PublishedTeacherLayerCheckpointApplyResult(
            checkpointId = decoded.checkpointId,
            layerSha256 = incomingLayer.sha256,
            snapshot = updated,
            changed = true,
        )
    }

    /**
     * Applies an ordered student-origin delta as one durable target-layer replacement.
     *
     * Every byte is owned, decoded, validated, and simulated before the append log is opened. A
     * malformed/incomplete stream therefore cannot leave a partially applied prefix on disk. The
     * original peer operation ids are intentionally not appended one-by-one; the final verified
     * student layer is committed by one synthetic replace record while all non-student layers stay
     * untouched.
     */
    @Synchronized
    fun applyEncodedStudentDelta(
        localBookId: String,
        pageNumber: Int,
        encodedOperations: List<ByteArray>,
        expectedOriginDeviceId: String,
        baseOriginCursor: Long,
        sourceOriginCursor: Long,
        allowedAttemptNos: Collection<Int>,
        expectedResultLayerSha256: String,
    ): StudentLayerDeltaApplyResult {
        require(expectedOriginDeviceId.isNotBlank() &&
            expectedOriginDeviceId.length <= MAX_CHECKPOINT_IDENTIFIER_CHARS
        ) { "Student delta origin device id is invalid" }
        require(baseOriginCursor >= 0L && sourceOriginCursor > baseOriginCursor) {
            "Student delta cursor range is invalid"
        }
        require(STUDENT_CHECKPOINT_ID.matches(expectedResultLayerSha256)) {
            "Student delta result digest is invalid"
        }
        val allowedAttempts = allowedAttemptNos.toSet()
        require(allowedAttempts.isNotEmpty() &&
            allowedAttempts.size == allowedAttemptNos.size &&
            allowedAttempts.size <= MAX_STUDENT_DELTA_ATTEMPTS &&
            allowedAttempts.all { it > 0 }
        ) { "Student delta attempt set is invalid" }
        require(encodedOperations.isNotEmpty() && encodedOperations.size <= MAX_STUDENT_DELTA_OPERATIONS) {
            "Student delta operation count is invalid"
        }

        var totalBytes = 0L
        val ownedRecords = encodedOperations.map { bytes ->
            val owned = bytes.copyOf()
            require(owned.isNotEmpty() && owned.size <= MAX_ENCODED_OPERATION_BYTES) {
                "Student delta operation is too large"
            }
            totalBytes += owned.size.toLong()
            require(totalBytes <= MAX_ATOMIC_STUDENT_DELTA_BYTES) { "Student delta exceeds its byte limit" }
            val text = decodeUtf8Strict(owned)
            require(!text.contains('\n')) { "Student delta operation framing is invalid" }
            decodeRecord(JSONObject(text))
        }

        val index = pageIndex(localBookId, pageNumber)
        val current = index.snapshot
        val simulatedAssets = current.assets.toMutableMap()
        val simulatedActive = current.activeStrokeIds.toMutableSet()
        val operationIds = hashSetOf<OperationId>()
        var previousClock = baseOriginCursor

        ownedRecords.forEach { record ->
            require(record.bookId.isNotBlank() && record.bookId.length <= MAX_CHECKPOINT_IDENTIFIER_CHARS) {
                "Student delta source book id is invalid"
            }
            require(record.pageNumber == pageNumber) { "Student delta operation page mismatch" }
            require(record.revision >= 0L) { "Student delta source revision is invalid" }
            val operation = record.operation
            require(operation.id.value.isNotBlank() && operation.id.value.length <= MAX_CHECKPOINT_IDENTIFIER_CHARS) {
                "Student delta operation id is invalid"
            }
            require(operationIds.add(operation.id)) { "Student delta contains duplicate operation ids" }
            require(operation.deviceId == expectedOriginDeviceId) { "Student delta origin device mismatch" }
            require(operation.logicalClock > previousClock && operation.logicalClock < Long.MAX_VALUE) {
                "Student delta clocks are not strictly increasing"
            }
            previousClock = operation.logicalClock
            require(operation.addedStrokeIds.intersect(operation.removedStrokeIds).isEmpty()) {
                "Student delta cannot add and remove the same asset"
            }
            require((operation.addedStrokeIds + operation.removedStrokeIds).all { id ->
                id.value.isNotBlank() && id.value.length <= MAX_CHECKPOINT_IDENTIFIER_CHARS
            }) { "Student delta stroke id is invalid" }
            require(record.addedAssets.map(StrokeAsset::id).distinct().size == record.addedAssets.size) {
                "Student delta contains duplicate asset payloads"
            }
            record.addedAssets.forEach { asset ->
                validateStudentCheckpointAsset(asset, pageNumber)
                require(asset.attemptNo in allowedAttempts) { "Student delta asset attempt is not allowed" }
                require(asset.id in operation.addedStrokeIds) {
                    "Student delta contains an unrelated asset payload"
                }
                val existing = simulatedAssets[asset.id]
                require(existing == null || existing == asset) {
                    "Student delta asset collides with existing ink"
                }
            }

            operation.removedStrokeIds.forEach { id ->
                val existing = requireNotNull(simulatedAssets[id]) {
                    "Student delta removes a missing asset"
                }
                require(existing.authorId == STUDENT_AUTHOR_ID && existing.attemptNo in allowedAttempts) {
                    "Student delta may remove only an allowed student asset"
                }
            }
            record.addedAssets.forEach { asset -> simulatedAssets[asset.id] = asset }
            operation.addedStrokeIds.forEach { id ->
                val existing = requireNotNull(simulatedAssets[id]) {
                    "Student delta reactivates a missing asset"
                }
                require(existing.authorId == STUDENT_AUTHOR_ID && existing.attemptNo in allowedAttempts) {
                    "Student delta may add only an allowed student asset"
                }
            }
            record.addedAssets.forEach { asset ->
                asset.parentStrokeId?.let { parentId ->
                    val parent = requireNotNull(simulatedAssets[parentId]) {
                        "Student delta parent asset is missing"
                    }
                    require(
                        parent.authorId == STUDENT_AUTHOR_ID &&
                            parent.pageNumber == pageNumber &&
                            parent.attemptNo == asset.attemptNo &&
                            parent.attemptNo in allowedAttempts
                    ) { "Student delta parent is outside the allowed student attempt" }
                }
            }
            simulatedActive.removeAll(operation.removedStrokeIds)
            simulatedActive.addAll(operation.addedStrokeIds)
        }
        require(previousClock == sourceOriginCursor) { "Student delta does not reach its source cursor" }

        val simulated = AnnotationSnapshot(
            bookId = localBookId,
            pageNumber = pageNumber,
            revision = current.revision,
            assets = simulatedAssets,
            activeStrokeIds = simulatedActive,
            appliedOperationIds = current.appliedOperationIds,
        )
        val resultLayer = portableStudentLayer(simulated)
        validateStudentCheckpointLayerAssets(resultLayer.assets, pageNumber)
        require(resultLayer.sha256 == expectedResultLayerSha256) {
            "Student delta result digest does not match"
        }
        val currentLayer = portableStudentLayer(current)
        if (currentLayer.sha256 == resultLayer.sha256) {
            return StudentLayerDeltaApplyResult(
                layerSha256 = resultLayer.sha256,
                sourceOriginCursor = sourceOriginCursor,
                snapshot = current,
                changed = false,
            )
        }
        resultLayer.assets.forEach { incoming ->
            val existing = current.assets[incoming.id]
            require(existing == null || existing == incoming) {
                "Student delta result asset collides with existing ink"
            }
        }
        val currentStudentIds = current.activeStrokeIds.filterTo(linkedSetOf()) { id ->
            current.assets[id]?.authorId == STUDENT_AUTHOR_ID
        }
        val operationIdentity = buildString {
            append(expectedOriginDeviceId).append(':')
            append(baseOriginCursor).append(':').append(sourceOriginCursor).append(':')
            append(expectedResultLayerSha256).append(':').append(currentLayer.sha256).append(':')
            append(current.revision)
        }
        val record = StoredOperationRecord(
            bookId = localBookId,
            pageNumber = pageNumber,
            revision = current.revision + 1L,
            operation = AssetOperation(
                id = OperationId("$STUDENT_DELTA_OPERATION_PREFIX${sha256(operationIdentity.toByteArray())}"),
                removedStrokeIds = currentStudentIds,
                addedStrokeIds = resultLayer.activeStrokeIds,
                logicalClock = sourceOriginCursor,
                deviceId = STUDENT_DELTA_OPERATION_DEVICE_ID,
            ),
            addedAssets = resultLayer.assets,
        )
        val updated = commitSyntheticCheckpoint(index, record)
        check(portableStudentLayer(updated).sha256 == resultLayer.sha256) {
            "Student delta replacement produced a different layer"
        }
        return StudentLayerDeltaApplyResult(
            layerSha256 = resultLayer.sha256,
            sourceOriginCursor = sourceOriginCursor,
            snapshot = updated,
            changed = true,
        )
    }

    fun operationCursor(bytes: ByteArray): OperationCursor {
        require(bytes.size <= MAX_ENCODED_OPERATION_BYTES) { "Operation is too large" }
        val operation = decodeRecord(JSONObject(bytes.toString(Charsets.UTF_8))).operation
        return OperationCursor(operation.deviceId, operation.logicalClock)
    }

    /**
     * Applies an untrusted student delta only after proving that it cannot add, reactivate, replace,
     * or remove teacher ink. The generic replica API remains available for authenticated full-peer
     * synchronization paths that intentionally carry both authors.
     */
    @Synchronized
    fun appendEncodedStudentOperation(bookId: String, pageNumber: Int, bytes: ByteArray): Long {
        val ownedBytes = bytes.copyOf()
        require(ownedBytes.size <= MAX_ENCODED_OPERATION_BYTES) { "Operation is too large" }
        val text = decodeUtf8Strict(ownedBytes)
        require(!text.contains('\n')) { "Operation framing is invalid" }
        val record = decodeRecord(JSONObject(text))
        val current = pageIndex(bookId, pageNumber).snapshot
        validateStudentOperationRecord(record, pageNumber, current)
        return appendEncodedOperation(bookId, pageNumber, ownedBytes)
    }

    private fun validateStudentOperationRecord(
        record: StoredOperationRecord,
        pageNumber: Int,
        current: AnnotationSnapshot,
    ) {
        require(record.pageNumber == pageNumber) { "Operation page partition mismatch" }
        val operation = record.operation
        require(operation.id.value.isNotBlank() && operation.id.value.length <= MAX_CHECKPOINT_IDENTIFIER_CHARS) {
            "Student operation id is invalid"
        }
        require(operation.deviceId.isNotBlank() && operation.deviceId.length <= MAX_CHECKPOINT_IDENTIFIER_CHARS) {
            "Student operation device id is invalid"
        }
        require(operation.logicalClock in 0 until Long.MAX_VALUE) { "Student operation clock is invalid" }
        require(operation.addedStrokeIds.intersect(operation.removedStrokeIds).isEmpty()) {
            "Student operation cannot add and remove the same asset"
        }
        require(record.addedAssets.map(StrokeAsset::id).distinct().size == record.addedAssets.size) {
            "Student operation contains duplicate asset payloads"
        }
        record.addedAssets.forEach { asset ->
            validateStudentCheckpointAsset(asset, pageNumber)
            require(asset.id in operation.addedStrokeIds) {
                "Student operation contains an unrelated asset payload"
            }
        }
        val addedPayloads = record.addedAssets.associateBy(StrokeAsset::id)
        operation.removedStrokeIds.forEach { id ->
            require(current.assets[id]?.authorId == STUDENT_AUTHOR_ID) {
                "Student operation may remove only an existing student asset"
            }
        }
        operation.addedStrokeIds.forEach { id ->
            val incoming = addedPayloads[id]
            val existing = current.assets[id]
            require(incoming != null || existing?.authorId == STUDENT_AUTHOR_ID) {
                "Student operation may reactivate only an existing student asset"
            }
            require(existing == null || existing.authorId == STUDENT_AUTHOR_ID &&
                (incoming == null || incoming == existing)
            ) { "Student operation asset collides with existing ink" }
        }
    }

    @Synchronized
    fun appendEncodedOperation(bookId: String, pageNumber: Int, bytes: ByteArray): Long {
        require(bytes.size <= MAX_ENCODED_OPERATION_BYTES) { "Operation is too large" }
        val text = bytes.toString(Charsets.UTF_8)
        require(!text.contains('\n')) { "Operation framing is invalid" }
        val record = decodeRecord(JSONObject(text))
        require(record.pageNumber == pageNumber) { "Operation page partition mismatch" }
        require(record.addedAssets.all { it.pageNumber == pageNumber }) { "Operation contains another page" }
        val index = pageIndex(bookId, pageNumber)
        val current = index.snapshot
        if (record.operation.id in current.appliedOperationIds) return current.revision
        val localRecord = record.copy(bookId = bookId, revision = current.revision + 1L)
        val localBytes = localRecord.encodeBytes(LOCAL_POINT_ENCODING)
        val file = File(pageDirectory(bookId, pageNumber), LOG_FILE)
        FileOutputStream(file, true).use { output ->
            output.write(localBytes)
            output.write('\n'.code)
            output.flush()
            output.fd.sync()
        }
        val updated = apply(current, localRecord)
        index.add(localRecord, localBytes, updated)
        recordPublishedTeacherLayerMutation(bookId, pageNumber, current, localRecord)
        MasterNoteDataCommitBus.recordDurableCommit()
        if (updated.revision % checkpointInterval == 0L) writeCheckpoint(updated)
        return updated.revision
    }

    /** Durably records the exact page/attempt that still needs transport publication. */
    @Synchronized
    fun recordTeacherReviewPublishIntent(
        intent: TeacherReviewPublishIntent,
        publishedMarkGroups: List<MarkGroup> = emptyList(),
    ): TeacherReviewPublishIntent {
        val prepared = prepareTeacherReviewPublication(
            intent = intent,
            publishedSnapshot = pageIndex(intent.bookId, intent.pageNumber).snapshot,
            publishedMarkGroups = publishedMarkGroups,
        )
        return requireNotNull(
            promotePreparedTeacherReviewPublication(
                bookId = intent.bookId,
                pageNumber = intent.pageNumber,
                attemptNo = intent.attemptNo,
                publicationId = prepared.publicationId,
                currentMarkGroups = publishedMarkGroups,
            ),
        ) { "Teacher review preparation no longer matches durable data" }
    }

    /**
     * Freezes the intended final ink and grades before either authoritative store is changed.
     * A preparation never replaces the older ready-to-send publication for the same target.
     */
    @Synchronized
    fun prepareTeacherReviewPublication(
        intent: TeacherReviewPublishIntent,
        publishedSnapshot: AnnotationSnapshot,
        publishedMarkGroups: List<MarkGroup>,
    ): TeacherReviewPublishIntent {
        ensureTeacherReviewPublishIntentsLoaded()
        ensureTeacherReviewStateLoaded()
        validateTeacherReviewPublishIntent(intent)
        require(publishedSnapshot.bookId == intent.bookId &&
            publishedSnapshot.pageNumber == intent.pageNumber
        ) { "Teacher review preparation snapshot target mismatch" }
        val key = TeacherReviewIntentKey(intent.bookId, intent.pageNumber, intent.attemptNo)
        val preparationFile = teacherReviewPublicationPreparationFile(
            intent.bookId,
            intent.pageNumber,
            intent.attemptNo,
        )
        val previous = readTeacherReviewPublicationPreparation(preparationFile)
        // All three ledgers can briefly be the only surviving order witness at a crash boundary.
        // Never reuse an order merely because the wall clock moved backwards or stayed in the
        // same millisecond as the previous explicit press.
        val orderedIntent = intent.copy(
            updatedAtEpochMillis = nextTeacherReviewPublicationTimestamp(
                requestedEpochMillis = intent.updatedAtEpochMillis,
                authorityEpochMillis = teacherReviewAuthorities[key]?.intent?.updatedAtEpochMillis,
                outboxEpochMillis = teacherReviewPublishIntents[key]?.updatedAtEpochMillis,
                preparationEpochMillis = previous?.updatedAtEpochMillis,
            ),
        )
        val frozen = freezeTeacherReviewPublication(
            orderedIntent,
            publishedSnapshot,
            publishedMarkGroups,
        )
        writeTeacherReviewPublicationArtifact(
            frozen.intent,
            frozen.checkpointBytes,
            frozen.markGroupsBytes,
        )
        try {
            atomicWriteTeacherReviewPreparation(
                preparationFile,
                encodeTeacherReviewPreparation(frozen.intent),
            )
        } catch (error: Throwable) {
            deleteUnreferencedTeacherReviewArtifact(frozen.intent.publicationId)
            throw error
        }
        previous?.publicationId?.takeIf { it != frozen.intent.publicationId }
            ?.let(::deleteUnreferencedTeacherReviewArtifact)
        MasterNoteDataCommitBus.recordDurableCommit()
        return frozen.intent
    }

    /** Returns valid, recoverable preparations. Corrupt sidecars are isolated per target. */
    @Synchronized
    fun teacherReviewPublicationPreparations(): List<TeacherReviewPublishIntent> {
        val directory = teacherReviewPublicationDirectory()
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith("$TEACHER_REVIEW_PREPARATION_SUFFIX.bak") }
            .forEach { backup ->
                val target = File(backup.parentFile, backup.name.removeSuffix(".bak"))
                if (!target.exists()) backup.renameTo(target) else backup.delete()
            }
        directory.listFiles().orEmpty()
            .filter { it.name.endsWith("$TEACHER_REVIEW_PREPARATION_SUFFIX.tmp") }
            .forEach(File::delete)
        return directory.listFiles().orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.endsWith(TEACHER_REVIEW_PREPARATION_SUFFIX) }
            .sortedBy(File::getName)
            .take(MAX_TEACHER_REVIEW_PUBLISH_INTENTS)
            .mapNotNull(::readTeacherReviewPublicationPreparation)
            .sortedWith(
                compareBy<TeacherReviewPublishIntent>(TeacherReviewPublishIntent::updatedAtEpochMillis)
                    .thenBy(TeacherReviewPublishIntent::bookId)
                    .thenBy(TeacherReviewPublishIntent::pageNumber)
                    .thenBy(TeacherReviewPublishIntent::attemptNo),
            )
            .toList()
    }

    /**
     * Promotes only when both authoritative stores exactly match the prepared immutable bundle.
     * This is safe to retry after a crash at any point between ink, grade, and journal commits.
     */
    @Synchronized
    fun promotePreparedTeacherReviewPublication(
        bookId: String,
        pageNumber: Int,
        attemptNo: Int,
        publicationId: String,
        currentMarkGroups: List<MarkGroup>,
    ): TeacherReviewPublishIntent? {
        ensureTeacherReviewPublishIntentsLoaded()
        val key = TeacherReviewIntentKey(bookId, pageNumber, attemptNo)
        val currentReady = teacherReviewPublishIntents[key]
        if (currentReady?.publicationId == publicationId) {
            installTeacherReviewAuthority(currentReady)
            teacherReviewPublicationPreparationFile(bookId, pageNumber, attemptNo).delete()
            rememberRecentlyPromotedTeacherReview(currentReady)
            return currentReady
        }
        recentlyPromotedTeacherReviewPublications[publicationId]?.let { recent ->
            if (recent.bookId == bookId && recent.pageNumber == pageNumber &&
                recent.attemptNo == attemptNo
            ) return recent
        }
        val preparationFile = teacherReviewPublicationPreparationFile(bookId, pageNumber, attemptNo)
        val prepared = readTeacherReviewPublicationPreparation(preparationFile) ?: return null
        if (prepared.publicationId != publicationId) return null
        val currentExport = buildPublishedTeacherLayerCheckpointExport(
            pageNumber = pageNumber,
            attemptNo = attemptNo,
            snapshot = pageIndex(bookId, pageNumber).snapshot,
            pointEncoding = AnnotationPointEncoding.LEGACY_FLOAT_ARRAYS,
        )
        val currentCheckpointBytes = currentExport.copyCheckpointBytes()
        if (currentExport.layerSha256 != prepared.resultLayerSha256 ||
            sha256(currentCheckpointBytes) != prepared.checkpointSha256
        ) return null
        val currentMarkBytes = encodeTeacherReviewMarkGroups(
            bookId,
            pageNumber,
            attemptNo,
            currentMarkGroups,
        )
        if (sha256(currentMarkBytes) != prepared.markGroupsSha256) return null
        if (readTeacherReviewPublicationArtifact(prepared) == null) return null
        require(currentReady != null || teacherReviewPublishIntents.size < MAX_TEACHER_REVIEW_PUBLISH_INTENTS) {
            "Teacher review publish intent journal is full"
        }
        val next = LinkedHashMap(teacherReviewPublishIntents)
        next[key] = prepared
        persistTeacherReviewPublishIntents(next.values)
        teacherReviewPublishIntents.clear()
        teacherReviewPublishIntents.putAll(next)
        installTeacherReviewAuthority(prepared)
        preparationFile.delete()
        rememberRecentlyPromotedTeacherReview(prepared)
        currentReady?.publicationId?.takeIf { it != publicationId }
            ?.let(::deleteUnreferencedTeacherReviewArtifact)
        MasterNoteDataCommitBus.recordDurableCommit()
        return prepared
    }

    private fun rememberRecentlyPromotedTeacherReview(intent: TeacherReviewPublishIntent) {
        recentlyPromotedTeacherReviewPublications[intent.publicationId] = intent
        while (recentlyPromotedTeacherReviewPublications.size > 16) {
            recentlyPromotedTeacherReviewPublications.remove(
                recentlyPromotedTeacherReviewPublications.keys.first(),
            )
        }
    }

    @Synchronized
    fun teacherReviewPublishIntents(): List<TeacherReviewPublishIntent> {
        ensureTeacherReviewPublishIntentsLoaded()
        return teacherReviewPublishIntents.values.sortedWith(
            compareBy<TeacherReviewPublishIntent>(TeacherReviewPublishIntent::updatedAtEpochMillis)
                .thenBy(TeacherReviewPublishIntent::bookId)
                .thenBy(TeacherReviewPublishIntent::pageNumber)
                .thenBy(TeacherReviewPublishIntent::attemptNo),
        )
    }

    /** Last explicit, immutable publication for each exact target, including ACKed publications. */
    @Synchronized
    fun teacherReviewAuthorityIntents(
        bookId: String,
        pageNumber: Int,
        remotePairId: String? = null,
        remoteWorkbookToken: String? = null,
    ): List<TeacherReviewPublishIntent> {
        ensureTeacherReviewStateLoaded()
        return teacherReviewAuthorities.values.asSequence()
            .map(TeacherReviewAuthorityRecord::intent)
            .filter { intent ->
                intent.bookId == bookId && intent.pageNumber == pageNumber &&
                    (remotePairId == null || intent.remotePairId == remotePairId) &&
                    (remoteWorkbookToken == null ||
                        intent.remoteWorkbookToken == remoteWorkbookToken)
            }
            .sortedBy(TeacherReviewPublishIntent::attemptNo)
            .toList()
    }

    /**
     * Binds a pair-owned deferred publication to one exact Telegram workbook after manifest proof.
     *
     * Pairless legacy/LAN publications are deliberately ineligible. The authority journal commits
     * first; if the following outbox write is interrupted, startup reconciles the same publication's
     * non-null token back into both ledgers without ever promoting a pair id.
     */
    @Synchronized
    fun bindTeacherReviewAuthorityWorkbook(
        bookId: String,
        pageNumber: Int,
        attemptNo: Int,
        publicationId: String,
        remotePairId: String,
        remoteWorkbookToken: String,
    ): TeacherReviewPublishIntent? {
        require(bookId.isNotBlank() && pageNumber >= 0 && attemptNo > 0)
        require(publicationId.matches(STUDENT_CHECKPOINT_ID))
        require(remotePairId.isNotBlank() && remoteWorkbookToken.isNotBlank())
        ensureTeacherReviewPublishIntentsLoaded()
        ensureTeacherReviewStateLoaded()
        val key = TeacherReviewIntentKey(bookId, pageNumber, attemptNo)
        val authority = teacherReviewAuthorities[key] ?: return null
        val current = authority.intent
        if (current.publicationId != publicationId || current.remotePairId != remotePairId ||
            current.remoteWorkbookToken != null && current.remoteWorkbookToken != remoteWorkbookToken
        ) return null
        val pending = teacherReviewPublishIntents[key]
        if (pending != null && (pending.publicationId != publicationId ||
                pending.remotePairId != remotePairId ||
                pending.remoteWorkbookToken != null && pending.remoteWorkbookToken != remoteWorkbookToken)
        ) return null
        val bound = current.copy(remoteWorkbookToken = remoteWorkbookToken)
        if (bound != current) {
            val nextAuthorities = LinkedHashMap(teacherReviewAuthorities).apply {
                put(key, authority.copy(intent = bound))
            }
            persistTeacherReviewState(nextAuthorities.values, appliedTeacherReviewReceipts.values)
            teacherReviewAuthorities.clear()
            teacherReviewAuthorities.putAll(nextAuthorities)
            MasterNoteDataCommitBus.recordDurableCommit()
        }
        if (pending != null && pending.publicationId == publicationId && pending != bound) {
            val nextPending = LinkedHashMap(teacherReviewPublishIntents).apply { put(key, bound) }
            beforeTeacherReviewWorkbookOutboxPersist?.invoke()
            persistTeacherReviewPublishIntents(nextPending.values)
            teacherReviewPublishIntents.clear()
            teacherReviewPublishIntents.putAll(nextPending)
            MasterNoteDataCommitBus.recordDurableCommit()
        }
        return bound
    }

    @Synchronized
    fun teacherReviewAuthorityEvidence(
        bookId: String,
        pageNumber: Int,
        remotePairId: String? = null,
        remoteWorkbookToken: String? = null,
        attemptNos: Collection<Int>? = null,
    ): List<TeacherReviewStateEvidence> {
        require(attemptNos == null || attemptNos.isNotEmpty()) {
            "Teacher review authority attempt filter cannot be empty"
        }
        require(attemptNos == null || attemptNos.all { it > 0 }) {
            "Teacher review authority attempts must be positive"
        }
        ensureTeacherReviewStateLoaded()
        val authorities = teacherReviewAuthorities.values.asSequence()
            .filter { authority ->
                authority.intent.bookId == bookId && authority.intent.pageNumber == pageNumber &&
                    (remotePairId == null || authority.intent.remotePairId == remotePairId) &&
                    (remoteWorkbookToken == null ||
                        authority.intent.remoteWorkbookToken == remoteWorkbookToken) &&
                    (attemptNos == null || authority.intent.attemptNo in attemptNos)
            }
            .sortedBy { it.intent.attemptNo }
            .toList()
        if (authorities.isEmpty() || authorities.any { it.markGroupMetadata == null }) {
            return emptyList()
        }
        val metadataSha256 = teacherReviewMarkGroupMetadataSha256(
            normalizeTeacherReviewMarkGroupMetadataValues(
                authorities.flatMap { requireNotNull(it.markGroupMetadata) },
            ),
        )
        return authorities.map { it.evidence(metadataSha256) }
    }

    /**
     * Reopens retained immutable authorities in the ordinary outbox. Current unpublished ink is
     * never read here, so reconnect recovery cannot leak a teacher draft. The result is the exact
     * recoverable authority set, including rows already present in the outbox: the caller needs it
     * to repair an application tombstone after a crash between its commit and outbox deletion.
     */
    @Synchronized
    fun requeueTeacherReviewAuthorities(
        bookId: String,
        pageNumber: Int,
        remotePairId: String? = null,
        attemptNos: Collection<Int>? = null,
        remoteWorkbookToken: String? = null,
    ): List<TeacherReviewPublishIntent> {
        require(attemptNos == null || attemptNos.isNotEmpty()) {
            "Teacher review authority attempt filter cannot be empty"
        }
        require(attemptNos == null || attemptNos.all { it > 0 }) {
            "Teacher review authority attempts must be positive"
        }
        ensureTeacherReviewPublishIntentsLoaded()
        ensureTeacherReviewStateLoaded()
        val authorities = teacherReviewAuthorities.values.asSequence()
            .filter { authority ->
                authority.intent.bookId == bookId && authority.intent.pageNumber == pageNumber &&
                    (remotePairId == null || authority.intent.remotePairId == remotePairId) &&
                    (remoteWorkbookToken == null ||
                        authority.intent.remoteWorkbookToken == remoteWorkbookToken) &&
                    (attemptNos == null || authority.intent.attemptNo in attemptNos)
            }
            .sortedBy { it.intent.attemptNo }
            .filter { readTeacherReviewPublicationArtifact(it.intent) != null }
            .toList()
        if (authorities.isEmpty()) return emptyList()
        val next = LinkedHashMap(teacherReviewPublishIntents)
        val requeued = buildList {
            authorities.forEach { authority ->
                val intent = authority.intent
                val key = TeacherReviewIntentKey(intent.bookId, intent.pageNumber, intent.attemptNo)
                val current = next[key]
                if (current != null && current.publicationId != intent.publicationId) return@forEach
                next[key] = intent
                add(intent)
            }
        }
        require(next.size <= MAX_TEACHER_REVIEW_PUBLISH_INTENTS) {
            "Teacher review publish intent journal is full"
        }
        if (next != teacherReviewPublishIntents) {
            persistTeacherReviewPublishIntents(next.values)
            teacherReviewPublishIntents.clear()
            teacherReviewPublishIntents.putAll(next)
            MasterNoteDataCommitBus.recordDurableCommit()
        }
        return requeued
    }

    /** Portable digest of an exact-attempt grade snapshot, independent of local book identity. */
    fun teacherReviewMarkGroupsStateSha256(groups: Collection<MarkGroup>): String =
        teacherReviewMarkGroupsSha256(groups)

    @Synchronized
    fun recordAppliedTeacherReviewReceipt(receipt: AppliedTeacherReviewReceipt) {
        ensureTeacherReviewStateLoaded()
        validateAppliedTeacherReviewReceipt(receipt)
        val key = TeacherReviewIntentKey(receipt.bookId, receipt.pageNumber, receipt.attemptNo)
        val current = appliedTeacherReviewReceipts[key]
        // A retry of the same immutable publication may arrive through another transport. Keep
        // the first known teacher-side order instead of replacing it with a transport timestamp.
        val durableReceipt = if (current?.publicationId == receipt.publicationId &&
            current.publishedAtEpochMillis > 0L
        ) {
            receipt.copy(publishedAtEpochMillis = current.publishedAtEpochMillis)
        } else receipt
        if (current?.publicationId == durableReceipt.publicationId &&
            current.resultLayerSha256 == durableReceipt.resultLayerSha256 &&
            current.markGroupsSha256 == durableReceipt.markGroupsSha256 &&
            current.remotePairId == durableReceipt.remotePairId &&
            current.remoteWorkbookToken == durableReceipt.remoteWorkbookToken &&
            current.publishedAtEpochMillis == durableReceipt.publishedAtEpochMillis
        ) return
        val next = LinkedHashMap(appliedTeacherReviewReceipts).apply { put(key, durableReceipt) }
        require(next.size <= MAX_TEACHER_REVIEW_STATE_RECORDS) {
            "Applied teacher review receipt journal is full"
        }
        persistTeacherReviewState(teacherReviewAuthorities.values, next.values)
        appliedTeacherReviewReceipts.clear()
        appliedTeacherReviewReceipts.putAll(next)
        MasterNoteDataCommitBus.recordDurableCommit()
    }

    @Synchronized
    fun appliedTeacherReviewReceipts(
        bookId: String,
        pageNumber: Int,
        remotePairId: String? = null,
        remoteWorkbookToken: String? = null,
    ): List<AppliedTeacherReviewReceipt> {
        ensureTeacherReviewStateLoaded()
        return appliedTeacherReviewReceipts.values.asSequence()
            .filter { receipt ->
                receipt.bookId == bookId && receipt.pageNumber == pageNumber &&
                    (remotePairId == null || receipt.remotePairId == remotePairId) &&
                    (remoteWorkbookToken == null ||
                        receipt.remoteWorkbookToken == remoteWorkbookToken)
            }
            .sortedBy(AppliedTeacherReviewReceipt::attemptNo)
            .toList()
    }

    /** O(1) lookup for the one current exact-target receipt, scoped to a non-null peer pair. */
    @Synchronized
    fun appliedTeacherReviewReceipt(
        bookId: String,
        pageNumber: Int,
        attemptNo: Int,
        remotePairId: String,
        /** Null deliberately ignores workbook provenance for a LAN-to-Telegram handoff fence. */
        remoteWorkbookToken: String? = null,
    ): AppliedTeacherReviewReceipt? {
        require(bookId.isNotBlank() && pageNumber >= 0 && attemptNo > 0)
        require(remotePairId.isNotBlank())
        require(remoteWorkbookToken == null || remoteWorkbookToken.isNotBlank())
        ensureTeacherReviewStateLoaded()
        return appliedTeacherReviewReceipts[
            TeacherReviewIntentKey(bookId, pageNumber, attemptNo)
        ]?.takeIf { receipt ->
            receipt.remotePairId == remotePairId &&
                (remoteWorkbookToken == null || receipt.remoteWorkbookToken == remoteWorkbookToken)
        }
    }

    /**
     * Serializes the full layer + grade + receipt transaction for one exact target across every
     * PageOperationLogStore holder in this process. Callers must keep the whole apply transaction
     * inside [block], not just the receipt write.
     */
    fun <T> withTeacherReviewTargetLock(
        bookId: String,
        pageNumber: Int,
        attemptNo: Int,
        block: () -> T,
    ): T {
        require(bookId.isNotBlank() && pageNumber >= 0 && attemptNo > 0)
        return synchronized(TeacherReviewTargetLocks.lock(bookId, pageNumber, attemptNo)) {
            block()
        }
    }

    /**
     * Returns only receipts whose actual installed ink and grade snapshot still match. A receipt
     * whose data was restored away is omitted, intentionally producing a manifest mismatch.
     */
    @Synchronized
    fun verifiedAppliedTeacherReviewEvidence(
        bookId: String,
        pageNumber: Int,
        currentPageMarkGroups: Collection<MarkGroup>,
        remotePairId: String? = null,
        remoteWorkbookToken: String? = null,
        attemptNos: Collection<Int>? = null,
    ): List<TeacherReviewStateEvidence> {
        require(attemptNos == null || attemptNos.isNotEmpty()) {
            "Applied teacher review attempt filter cannot be empty"
        }
        require(attemptNos == null || attemptNos.all { it > 0 }) {
            "Applied teacher review attempts must be positive"
        }
        ensureTeacherReviewStateLoaded()
        val receipts = appliedTeacherReviewReceipts(
            bookId,
            pageNumber,
            remotePairId,
            remoteWorkbookToken,
        ).filter { receipt -> attemptNos == null || receipt.attemptNo in attemptNos }
        if (receipts.isEmpty()) return emptyList()
        val receiptAttempts = receipts.mapTo(hashSetOf(), AppliedTeacherReviewReceipt::attemptNo)
        val scopedGroups = currentPageMarkGroups.filter { group ->
            group.bookId == bookId && group.pageNumber == pageNumber &&
                group.marks.any { it.attemptNo in receiptAttempts }
        }
        val metadataSha256 = teacherReviewMarkGroupMetadataSha256(
            normalizeTeacherReviewMarkGroupMetadata(scopedGroups),
        )
        return receipts.mapNotNull { receipt ->
            val exactGroups = scopedGroups.mapNotNull { group ->
                if (group.bookId != bookId || group.pageNumber != pageNumber) return@mapNotNull null
                val exactMarks = group.marks.filter { it.attemptNo == receipt.attemptNo }
                exactMarks.takeIf(List<*>::isNotEmpty)?.let { group.copy(marks = exactMarks) }
            }
            val layerMatches = publishedTeacherLayerSha256(
                bookId,
                pageNumber,
                receipt.attemptNo,
            ) == receipt.resultLayerSha256
            val gradesMatch = teacherReviewMarkGroupsSha256(exactGroups) == receipt.markGroupsSha256
            receipt.evidence(metadataSha256).takeIf { layerMatches && gradesMatch }
        }
    }

    /** Returns the immutable bytes captured by the publish button, never the current live layer. */
    @Synchronized
    fun teacherReviewPublicationArtifact(
        bookId: String,
        pageNumber: Int,
        attemptNo: Int,
        publicationId: String? = null,
    ): TeacherReviewPublicationArtifact? {
        ensureTeacherReviewPublishIntentsLoaded()
        ensureTeacherReviewStateLoaded()
        val key = TeacherReviewIntentKey(bookId, pageNumber, attemptNo)
        var intent = teacherReviewPublishIntents[key]
            ?: teacherReviewAuthorities[key]?.intent
            ?: return null
        if (publicationId != null && intent.publicationId != publicationId) return null
        return readTeacherReviewPublicationArtifact(intent)
    }

    private fun readTeacherReviewPublicationArtifact(
        intent: TeacherReviewPublishIntent,
    ): TeacherReviewPublicationArtifact? {
        if (intent.publicationId.isEmpty() || intent.markGroupsSha256.isEmpty()) return null
        val file = teacherReviewPublicationArtifactFile(intent.publicationId)
        if (!file.isFile || file.length() != intent.checkpointSizeBytes.toLong()) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (sha256(bytes) != intent.checkpointSha256) return null
        val markGroupsFile = teacherReviewPublicationMarkGroupsFile(intent.publicationId)
        if (!markGroupsFile.isFile || markGroupsFile.length() != intent.markGroupsSizeBytes.toLong()) return null
        val markGroupsBytes = runCatching { markGroupsFile.readBytes() }.getOrNull() ?: return null
        if (sha256(markGroupsBytes) != intent.markGroupsSha256) return null
        val markGroups = runCatching {
            decodeTeacherReviewMarkGroups(
                intent.bookId,
                intent.pageNumber,
                intent.attemptNo,
                markGroupsBytes,
            )
        }.getOrNull() ?: return null
        return TeacherReviewPublicationArtifact(intent, bytes, markGroups)
    }

    @Synchronized
    fun removeTeacherReviewPublishIntent(
        bookId: String,
        pageNumber: Int,
        attemptNo: Int,
        publicationId: String? = null,
    ): Boolean {
        ensureTeacherReviewPublishIntentsLoaded()
        val key = TeacherReviewIntentKey(bookId, pageNumber, attemptNo)
        val removed = teacherReviewPublishIntents[key] ?: return false
        if (publicationId != null && removed.publicationId != publicationId) return false
        val next = LinkedHashMap(teacherReviewPublishIntents).apply { remove(key) }
        persistTeacherReviewPublishIntents(next.values)
        teacherReviewPublishIntents.clear()
        teacherReviewPublishIntents.putAll(next)
        removed.publicationId.takeIf(String::isNotEmpty)?.let(::deleteUnreferencedTeacherReviewArtifact)
        MasterNoteDataCommitBus.recordDurableCommit()
        return true
    }

    private fun buildStudentLayerCheckpointExport(
        pageNumber: Int,
        snapshot: AnnotationSnapshot,
        originDeviceHighWater: Long,
        pointEncoding: AnnotationPointEncoding,
    ): StudentLayerCheckpointExport {
        val activeIds = activeStudentStrokeIds(snapshot)
        val layer = portableStudentLayer(
            pageNumber = snapshot.pageNumber,
            assets = collectStudentCheckpointAssets(snapshot, activeIds),
            activeStrokeIds = activeIds,
        )
        // Erase and undo operations may advance the origin clock without leaving an asset that can
        // carry it. The exported checkpoint must still advance a receiver past that causal point.
        val highWater = maxOf(
            originDeviceHighWater,
            layer.assets.maxOfOrNull(StrokeAsset::logicalClock) ?: 0L,
        )
        require(highWater in 0 until Long.MAX_VALUE) { "Student layer checkpoint clock is invalid" }
        val body = encodeStudentLayerCheckpointBody(
            sourcePageNumber = pageNumber,
            sourceOperationClockHighWater = highWater,
            assets = layer.assets,
            activeStrokeIds = layer.activeStrokeIds,
            pointEncoding = pointEncoding,
        )
        val checkpointId = studentLayerCheckpointId(
            sourcePageNumber = pageNumber,
            sourceOperationClockHighWater = highWater,
            assets = layer.assets,
            activeStrokeIds = layer.activeStrokeIds,
        )
        val encoded = body.put("checkpointId", checkpointId)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= MAX_STUDENT_LAYER_CHECKPOINT_BYTES) {
            "Student layer checkpoint exceeds $MAX_STUDENT_LAYER_CHECKPOINT_BYTES bytes"
        }
        return StudentLayerCheckpointExport(
            checkpointBytes = encoded,
            layerSha256 = layer.sha256,
            originDeviceHighWater = originDeviceHighWater,
        )
    }

    private fun buildPublishedTeacherLayerCheckpointExport(
        pageNumber: Int,
        attemptNo: Int,
        snapshot: AnnotationSnapshot,
        pointEncoding: AnnotationPointEncoding,
    ): PublishedTeacherLayerCheckpointExport {
        val layer = portablePublishedTeacherLayer(snapshot, attemptNo)
        val highWater = layer.assets.maxOfOrNull(StrokeAsset::logicalClock) ?: 0L
        require(highWater in 0 until Long.MAX_VALUE) { "Published teacher checkpoint clock is invalid" }
        val body = encodePublishedTeacherLayerCheckpointBody(
            sourcePageNumber = pageNumber,
            attemptNo = attemptNo,
            sourceOperationClockHighWater = highWater,
            assets = layer.assets,
            activeStrokeIds = layer.activeStrokeIds,
            pointEncoding = pointEncoding,
        )
        val checkpointId = publishedTeacherLayerCheckpointId(
            sourcePageNumber = pageNumber,
            attemptNo = attemptNo,
            sourceOperationClockHighWater = highWater,
            assets = layer.assets,
            activeStrokeIds = layer.activeStrokeIds,
        )
        val encoded = JSONObject(body.toString())
            .put("checkpointId", checkpointId)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= MAX_PUBLISHED_TEACHER_LAYER_CHECKPOINT_BYTES) {
            "Published teacher layer checkpoint exceeds $MAX_PUBLISHED_TEACHER_LAYER_CHECKPOINT_BYTES bytes"
        }
        return PublishedTeacherLayerCheckpointExport(encoded, layer.sha256)
    }

    private fun portableStudentLayer(snapshot: AnnotationSnapshot): PortableLayer {
        val activeIds = activeStudentStrokeIds(snapshot)
        return portableStudentLayer(
            pageNumber = snapshot.pageNumber,
            assets = collectPortableStudentLayerAssets(snapshot, activeIds),
            activeStrokeIds = activeIds,
        )
    }

    private fun activeStudentStrokeIds(snapshot: AnnotationSnapshot): Set<StrokeId> =
        snapshot.activeStrokeIds.asSequence()
            .filter { id -> snapshot.assets[id]?.authorId == STUDENT_AUTHOR_ID }
            .sortedBy(StrokeId::value)
            .toCollection(linkedSetOf())

    private fun portableStudentLayer(
        pageNumber: Int,
        assets: List<StrokeAsset>,
        activeStrokeIds: Set<StrokeId>,
    ): PortableLayer {
        val sortedAssets = assets.sortedBy { it.id.value }
        val sortedActiveIds = activeStrokeIds.sortedBy(StrokeId::value).toCollection(linkedSetOf())
        val canonical = encodeStudentLayerDigestBody(pageNumber, sortedAssets, sortedActiveIds)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        return PortableLayer(sortedAssets, sortedActiveIds, sha256(canonical))
    }

    private fun portablePublishedTeacherLayer(snapshot: AnnotationSnapshot, attemptNo: Int): PortableLayer {
        val activeIds = snapshot.activeStrokeIds.asSequence()
            .filter { id -> snapshot.assets[id]?.let { asset ->
                asset.authorId == TEACHER_AUTHOR_ID && asset.attemptNo == attemptNo &&
                    asset.publishedAtEpochMillis != null
            } == true }
            .sortedBy(StrokeId::value)
            .toCollection(linkedSetOf())
        return portablePublishedTeacherLayer(
            pageNumber = snapshot.pageNumber,
            attemptNo = attemptNo,
            assets = collectPublishedTeacherCheckpointAssets(snapshot, activeIds, attemptNo),
            activeStrokeIds = activeIds,
        )
    }

    private fun cachedPublishedTeacherLayerSha256(
        bookId: String,
        pageNumber: Int,
        attemptNo: Int,
        snapshot: AnnotationSnapshot,
    ): String {
        val key = TeacherReviewIntentKey(bookId, pageNumber, attemptNo)
        val generation = publishedTeacherLayerGenerations[key] ?: 0L
        publishedTeacherLayerDigests[key]
            ?.takeIf { it.mutationGeneration == generation }
            ?.let { return it.sha256 }
        val signature = publishedTeacherActiveIdSignature(snapshot, attemptNo)
        val digest = portablePublishedTeacherLayer(snapshot, attemptNo).sha256
        publishedTeacherLayerDigestMaterializations++
        putPublishedTeacherLayerDigest(
            key,
            PublishedTeacherLayerDigest(generation, signature, digest),
        )
        return digest
    }

    private fun cachePublishedTeacherLayerSha256(
        bookId: String,
        pageNumber: Int,
        attemptNo: Int,
        snapshot: AnnotationSnapshot,
        digestSha256: String,
    ) {
        require(digestSha256.matches(STUDENT_CHECKPOINT_ID))
        val key = TeacherReviewIntentKey(bookId, pageNumber, attemptNo)
        putPublishedTeacherLayerDigest(
            key,
            PublishedTeacherLayerDigest(
                publishedTeacherLayerGenerations[key] ?: 0L,
                publishedTeacherActiveIdSignature(snapshot, attemptNo),
                digestSha256,
            ),
        )
    }

    private fun putPublishedTeacherLayerDigest(
        key: TeacherReviewIntentKey,
        value: PublishedTeacherLayerDigest,
    ) {
        publishedTeacherLayerGenerations[key] = value.mutationGeneration
        publishedTeacherLayerDigests[key] = value
        while (publishedTeacherLayerDigests.size > MAX_CACHED_PUBLISHED_TEACHER_LAYER_DIGESTS) {
            val eldestKey = publishedTeacherLayerDigests.entries.iterator().next().key
            publishedTeacherLayerDigests.remove(eldestKey)
            publishedTeacherLayerGenerations.remove(eldestKey)
        }
        trimPublishedTeacherLayerGenerations()
    }

    private fun recordPublishedTeacherLayerMutation(
        bookId: String,
        pageNumber: Int,
        before: AnnotationSnapshot,
        record: StoredOperationRecord,
    ) {
        val addedById = record.addedAssets.associateBy(StrokeAsset::id)
        val touchedIds = buildSet {
            addAll(record.operation.removedStrokeIds)
            addAll(record.operation.addedStrokeIds)
            addAll(addedById.keys)
        }
        val attempts = buildSet {
            touchedIds.forEach { id ->
                listOfNotNull(before.assets[id], addedById[id]).forEach { asset ->
                    // Published layers include inactive parent assets. A draft can therefore be a
                    // canonical ancestor of an active published replacement even though the draft
                    // itself has no publishedAt value; every touched teacher asset must invalidate.
                    if (asset.authorId == TEACHER_AUTHOR_ID && asset.attemptNo > 0) {
                        add(asset.attemptNo)
                    }
                }
            }
        }
        attempts.forEach { attemptNo ->
            val key = TeacherReviewIntentKey(bookId, pageNumber, attemptNo)
            val current = publishedTeacherLayerGenerations[key] ?: 0L
            publishedTeacherLayerGenerations[key] =
                if (current == Long.MAX_VALUE) 0L else current + 1L
            // Removing the entry makes a wrapped generation unable to match a very old cache row.
            publishedTeacherLayerDigests.remove(key)
        }
        trimPublishedTeacherLayerGenerations()
    }

    private fun trimPublishedTeacherLayerGenerations() {
        while (publishedTeacherLayerGenerations.size >
            MAX_CACHED_PUBLISHED_TEACHER_LAYER_DIGESTS
        ) {
            val eldestKey = publishedTeacherLayerGenerations.entries.iterator().next().key
            publishedTeacherLayerGenerations.remove(eldestKey)
            publishedTeacherLayerDigests.remove(eldestKey)
        }
    }

    private fun publishedTeacherActiveIdSignature(
        snapshot: AnnotationSnapshot,
        attemptNo: Int,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val ids = snapshot.activeStrokeIds.asSequence()
            .filter { id -> snapshot.assets[id]?.let { asset ->
                asset.authorId == TEACHER_AUTHOR_ID && asset.attemptNo == attemptNo &&
                    asset.publishedAtEpochMillis != null
            } == true }
            .map(StrokeId::value)
            .sorted()
            .toList()
        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(ids.size).array())
        ids.forEach { id ->
            val bytes = id.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun portablePublishedTeacherLayer(
        pageNumber: Int,
        attemptNo: Int,
        assets: List<StrokeAsset>,
        activeStrokeIds: Set<StrokeId>,
    ): PortableLayer {
        val sortedAssets = assets.sortedBy { it.id.value }
        val sortedActiveIds = activeStrokeIds.sortedBy(StrokeId::value).toCollection(linkedSetOf())
        val canonical = encodePublishedTeacherLayerDigestBody(
            pageNumber,
            attemptNo,
            sortedAssets,
            sortedActiveIds,
        ).toString().toByteArray(StandardCharsets.UTF_8)
        return PortableLayer(sortedAssets, sortedActiveIds, sha256(canonical))
    }

    private fun checkpointTransitionOperationId(
        prefix: String,
        checkpointId: String,
        currentLayerSha256: String,
        currentRevision: Long,
    ): OperationId = OperationId("$prefix$checkpointId:$currentLayerSha256:$currentRevision")

    private fun appendSyntheticRecord(index: PageIndex, record: StoredOperationRecord): AnnotationSnapshot {
        val current = index.snapshot
        require(record.revision == current.revision + 1L) { "Synthetic operation revision is stale" }
        val encoded = record.encodeBytes(LOCAL_POINT_ENCODING)
        val file = File(pageDirectory(record.bookId, record.pageNumber), LOG_FILE)
        FileOutputStream(file, true).use { output ->
            output.write(encoded)
            output.write('\n'.code)
            output.flush()
            output.fd.sync()
        }
        val updated = apply(current, record)
        index.add(record, encoded, updated)
        recordPublishedTeacherLayerMutation(record.bookId, record.pageNumber, current, record)
        MasterNoteDataCommitBus.recordDurableCommit()
        if (updated.revision % checkpointInterval == 0L) writeCheckpoint(updated)
        return updated
    }

    /**
     * Commits a materialized remote-layer replacement without retaining its full assets a second
     * time as an encoded operation. The checkpoint rename is atomic and fsynced; pre-existing log
     * rows remain available for local-origin delta history and are ignored on reload because their
     * revisions are at or below this snapshot.
     */
    private fun commitSyntheticCheckpoint(index: PageIndex, record: StoredOperationRecord): AnnotationSnapshot {
        val current = index.snapshot
        require(record.revision == current.revision + 1L) { "Synthetic operation revision is stale" }
        val updated = apply(current, record)
        val maximumClocks = index.maximumClockByDevice.toMutableMap().apply {
            this[record.operation.deviceId] = maxOf(
                this[record.operation.deviceId] ?: 0L,
                record.operation.logicalClock,
            )
            updated.assets.values.forEach { asset ->
                this[asset.deviceId] = maxOf(this[asset.deviceId] ?: 0L, asset.logicalClock)
            }
        }
        atomicWriteCheckpoint(
            File(pageDirectory(record.bookId, record.pageNumber), CHECKPOINT_FILE),
            updated,
            maximumClocks,
        )
        index.maximumClockByDevice.clear()
        index.maximumClockByDevice.putAll(maximumClocks)
        index.snapshot = updated
        MasterNoteDataCommitBus.recordDurableCommit()
        return updated
    }

    private fun loadTeacherReviewPublishIntents() {
        teacherReviewPublishIntentsLoaded = true
        recoverTeacherReviewPublishIntentJournal()
        val file = File(rootDirectory, TEACHER_REVIEW_PUBLISH_INTENTS_FILE)
        if (!file.isFile) return
        runCatching {
            require(file.length() in 1..MAX_TEACHER_REVIEW_PUBLISH_INTENT_JOURNAL_BYTES) {
                "Teacher review publish intent journal has an invalid size"
            }
            val root = JSONObject(decodeUtf8Strict(file.readBytes()))
            val version = root.getInt("version")
            require(version in 1..TEACHER_REVIEW_PUBLISH_INTENT_JOURNAL_VERSION) {
                "Unsupported teacher review publish intent journal"
            }
            val values = root.getJSONArray("intents")
            require(values.length() <= MAX_TEACHER_REVIEW_PUBLISH_INTENTS) {
                "Teacher review publish intent journal is too large"
            }
            val decoded = linkedMapOf<TeacherReviewIntentKey, TeacherReviewPublishIntent>()
            for (index in 0 until values.length()) {
                val value = values.getJSONObject(index)
                val intent = TeacherReviewPublishIntent(
                    bookId = value.getString("bookId"),
                    pageNumber = value.getInt("pageNumber"),
                    attemptNo = value.getInt("attemptNo"),
                    updatedAtEpochMillis = value.getLong("updatedAt"),
                    publicationId = value.optString("publicationId"),
                    checkpointSha256 = value.optString("checkpointSha256"),
                    resultLayerSha256 = value.optString("resultLayerSha256"),
                    checkpointSizeBytes = value.optInt("checkpointSizeBytes"),
                    markGroupsSha256 = value.optString("markGroupsSha256"),
                    markGroupsSizeBytes = value.optInt("markGroupsSizeBytes"),
                    remotePairId = value.optionalString("remotePairId"),
                    remoteWorkbookToken = value.optionalString("remoteWorkbookToken"),
                    remoteManifestGeneration = value.optLong("remoteManifestGeneration"),
                    remoteManifestSequence = value.optLong("remoteManifestSequence"),
                ).also(::validateTeacherReviewPublishIntent)
                val key = TeacherReviewIntentKey(intent.bookId, intent.pageNumber, intent.attemptNo)
                require(decoded.put(key, intent) == null) {
                    "Teacher review publish intent journal contains duplicate targets"
                }
            }
            decoded
        }.onSuccess { decoded ->
            teacherReviewPublishIntents.clear()
            teacherReviewPublishIntents.putAll(decoded)
        }.onFailure {
            teacherReviewPublishIntents.clear()
            quarantineTeacherReviewPublishIntentJournal(file)
        }
    }

    private fun ensureTeacherReviewPublishIntentsLoaded() {
        if (!teacherReviewPublishIntentsLoaded) loadTeacherReviewPublishIntents()
    }

    private fun loadTeacherReviewState() {
        // A legacy complete outbox is the migration source when this newer journal is absent or
        // corrupt. Load it first: the outbox loader is deliberately independent of state loading,
        // so this cannot recurse and each recovery path still runs at most once per store holder.
        ensureTeacherReviewPublishIntentsLoaded()
        teacherReviewStateLoaded = true
        recoverTeacherReviewStateJournal()
        val file = File(rootDirectory, TEACHER_REVIEW_STATE_FILE)
        var migratedAuthorityMetadata = false
        if (file.isFile) {
            runCatching {
                require(file.length() in 1..MAX_TEACHER_REVIEW_STATE_JOURNAL_BYTES) {
                    "Teacher review state journal has an invalid size"
                }
                val root = JSONObject(decodeUtf8Strict(file.readBytes()))
                require(root.getInt("version") in 1..TEACHER_REVIEW_STATE_VERSION) {
                    "Unsupported teacher review state journal"
                }
                val authorityValues = root.getJSONArray("authorities")
                val receiptValues = root.getJSONArray("receipts")
                require(authorityValues.length() <= MAX_TEACHER_REVIEW_STATE_RECORDS &&
                    receiptValues.length() <= MAX_TEACHER_REVIEW_STATE_RECORDS
                ) { "Teacher review state journal is too large" }
                val authorities = linkedMapOf<TeacherReviewIntentKey, TeacherReviewAuthorityRecord>()
                for (index in 0 until authorityValues.length()) {
                    val value = authorityValues.getJSONObject(index)
                    val intent = decodeTeacherReviewPublishIntent(value.getJSONObject("intent"))
                    require(intent.publicationId.isNotEmpty() && intent.markGroupsSha256.isNotEmpty()) {
                        "Teacher review authority is incomplete"
                    }
                    val storedMetadata = value.optJSONArray("markGroupMetadata")?.let(
                        ::decodeTeacherReviewMarkGroupMetadata,
                    )
                    val metadata = storedMetadata ?: readTeacherReviewPublicationArtifact(intent)
                        ?.let { artifact ->
                            migratedAuthorityMetadata = true
                            normalizeTeacherReviewMarkGroupMetadata(artifact.markGroups)
                        }
                    val record = TeacherReviewAuthorityRecord(
                        intent = intent,
                        markGroupsStateSha256 = value.getString("markGroupsStateSha256"),
                        markGroupMetadata = metadata,
                    )
                    val key = TeacherReviewIntentKey(intent.bookId, intent.pageNumber, intent.attemptNo)
                    require(authorities.put(key, record) == null) {
                        "Teacher review state contains duplicate authorities"
                    }
                }
                val receipts = linkedMapOf<TeacherReviewIntentKey, AppliedTeacherReviewReceipt>()
                for (index in 0 until receiptValues.length()) {
                    val value = receiptValues.getJSONObject(index)
                    val receipt = AppliedTeacherReviewReceipt(
                        bookId = value.getString("bookId"),
                        pageNumber = value.getInt("pageNumber"),
                        attemptNo = value.getInt("attemptNo"),
                        publicationId = value.getString("publicationId"),
                        resultLayerSha256 = value.getString("resultLayerSha256"),
                        markGroupsSha256 = value.getString("markGroupsSha256"),
                        appliedAtEpochMillis = value.getLong("appliedAt"),
                        publishedAtEpochMillis = value.optLong("publishedAt"),
                        remotePairId = value.optionalString("remotePairId"),
                        remoteWorkbookToken = value.optionalString("remoteWorkbookToken"),
                    ).also(::validateAppliedTeacherReviewReceipt)
                    val key = TeacherReviewIntentKey(receipt.bookId, receipt.pageNumber, receipt.attemptNo)
                    require(receipts.put(key, receipt) == null) {
                        "Teacher review state contains duplicate receipts"
                    }
                }
                authorities to receipts
            }.onSuccess { (authorities, receipts) ->
                teacherReviewAuthorities.clear()
                teacherReviewAuthorities.putAll(authorities)
                appliedTeacherReviewReceipts.clear()
                appliedTeacherReviewReceipts.putAll(receipts)
            }.onFailure {
                teacherReviewAuthorities.clear()
                appliedTeacherReviewReceipts.clear()
                quarantineTeacherReviewStateJournal(file)
            }
        }

        // The outbox commit precedes an initial authority commit. Workbook binding deliberately
        // commits in the opposite order. Reconcile both directions for the same publication so
        // either crash window converges without ever inferring or upgrading a null pair id.
        var repaired = migratedAuthorityMetadata
        var repairedOutbox = false
        teacherReviewPublishIntents.values.toList().forEach { intent ->
            if (intent.publicationId.isEmpty() || intent.markGroupsSha256.isEmpty()) return@forEach
            val key = TeacherReviewIntentKey(intent.bookId, intent.pageNumber, intent.attemptNo)
            val current = teacherReviewAuthorities[key]
            if (current?.intent?.publicationId == intent.publicationId) {
                val merged = mergeSameTeacherReviewPublicationOwnership(current.intent, intent)
                    ?: return@forEach
                if (current.intent != merged) {
                    teacherReviewAuthorities[key] = current.copy(intent = merged)
                    repaired = true
                }
                if (intent != merged) {
                    teacherReviewPublishIntents[key] = merged
                    repairedOutbox = true
                }
                return@forEach
            }
            val artifact = readTeacherReviewPublicationArtifact(intent) ?: return@forEach
            teacherReviewAuthorities[key] = TeacherReviewAuthorityRecord(
                intent,
                teacherReviewMarkGroupsSha256(artifact.markGroups),
                normalizeTeacherReviewMarkGroupMetadata(artifact.markGroups),
            )
            repaired = true
        }
        if (repaired || !file.exists() &&
            (teacherReviewAuthorities.isNotEmpty() || appliedTeacherReviewReceipts.isNotEmpty())
        ) {
            persistTeacherReviewState(
                teacherReviewAuthorities.values,
                appliedTeacherReviewReceipts.values,
            )
        }
        if (repairedOutbox) persistTeacherReviewPublishIntents(teacherReviewPublishIntents.values)
    }

    private fun mergeSameTeacherReviewPublicationOwnership(
        authority: TeacherReviewPublishIntent,
        outbox: TeacherReviewPublishIntent,
    ): TeacherReviewPublishIntent? {
        if (authority.publicationId != outbox.publicationId ||
            authority.copy(remoteWorkbookToken = null) != outbox.copy(remoteWorkbookToken = null)
        ) return null
        val token = when {
            authority.remoteWorkbookToken == outbox.remoteWorkbookToken -> authority.remoteWorkbookToken
            authority.remoteWorkbookToken == null -> outbox.remoteWorkbookToken
            outbox.remoteWorkbookToken == null -> authority.remoteWorkbookToken
            else -> return null
        }
        // Both intents have the same pair by the equality check above. Never add a token to a
        // pairless publication, even if a future malformed journal somehow bypasses construction.
        if (token != null && authority.remotePairId == null) return null
        return authority.copy(remoteWorkbookToken = token)
    }

    private fun ensureTeacherReviewStateLoaded() {
        if (!teacherReviewStateLoaded) loadTeacherReviewState()
    }

    private fun installTeacherReviewAuthority(intent: TeacherReviewPublishIntent) {
        ensureTeacherReviewStateLoaded()
        val artifact = requireNotNull(readTeacherReviewPublicationArtifact(intent)) {
            "Teacher review authority artifact is unavailable"
        }
        val record = TeacherReviewAuthorityRecord(
            intent = intent,
            markGroupsStateSha256 = teacherReviewMarkGroupsSha256(artifact.markGroups),
            markGroupMetadata = normalizeTeacherReviewMarkGroupMetadata(artifact.markGroups),
        )
        val key = TeacherReviewIntentKey(intent.bookId, intent.pageNumber, intent.attemptNo)
        val current = teacherReviewAuthorities[key]
        if (current == record) return
        val next = LinkedHashMap(teacherReviewAuthorities).apply { put(key, record) }
        require(next.size <= MAX_TEACHER_REVIEW_STATE_RECORDS) {
            "Teacher review authority journal is full"
        }
        persistTeacherReviewState(next.values, appliedTeacherReviewReceipts.values)
        teacherReviewAuthorities.clear()
        teacherReviewAuthorities.putAll(next)
        MasterNoteDataCommitBus.recordDurableCommit()
        current?.intent?.publicationId?.takeIf { it != intent.publicationId }
            ?.let(::deleteUnreferencedTeacherReviewArtifact)
    }

    private fun persistTeacherReviewState(
        authorities: Collection<TeacherReviewAuthorityRecord>,
        receipts: Collection<AppliedTeacherReviewReceipt>,
    ) {
        require(authorities.size <= MAX_TEACHER_REVIEW_STATE_RECORDS &&
            receipts.size <= MAX_TEACHER_REVIEW_STATE_RECORDS
        )
        val root = JSONObject()
            .put("version", TEACHER_REVIEW_STATE_VERSION)
            .put("authorities", JSONArray().apply {
                authorities.sortedWith(
                    compareBy<TeacherReviewAuthorityRecord> { it.intent.bookId }
                        .thenBy { it.intent.pageNumber }
                        .thenBy { it.intent.attemptNo },
                ).forEach { authority ->
                    val value = JSONObject()
                        .put("intent", encodeTeacherReviewPublishIntent(authority.intent))
                        .put("markGroupsStateSha256", authority.markGroupsStateSha256)
                    authority.markGroupMetadata?.let { metadata ->
                        value.put("markGroupMetadata", encodeTeacherReviewMarkGroupMetadata(metadata))
                    }
                    put(value)
                }
            })
            .put("receipts", JSONArray().apply {
                receipts.sortedWith(
                    compareBy<AppliedTeacherReviewReceipt>(AppliedTeacherReviewReceipt::bookId)
                        .thenBy(AppliedTeacherReviewReceipt::pageNumber)
                        .thenBy(AppliedTeacherReviewReceipt::attemptNo),
                ).forEach { receipt ->
                    validateAppliedTeacherReviewReceipt(receipt)
                    put(JSONObject()
                        .put("bookId", receipt.bookId)
                        .put("pageNumber", receipt.pageNumber)
                        .put("attemptNo", receipt.attemptNo)
                        .put("publicationId", receipt.publicationId)
                        .put("resultLayerSha256", receipt.resultLayerSha256)
                        .put("markGroupsSha256", receipt.markGroupsSha256)
                        .put("appliedAt", receipt.appliedAtEpochMillis)
                        .put("publishedAt", receipt.publishedAtEpochMillis)
                        .put("remotePairId", receipt.remotePairId ?: JSONObject.NULL)
                        .put("remoteWorkbookToken", receipt.remoteWorkbookToken ?: JSONObject.NULL))
                }
            })
        val encoded = root.toString().toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= MAX_TEACHER_REVIEW_STATE_JOURNAL_BYTES) {
            "Teacher review state journal exceeds its byte limit"
        }
        atomicWriteTeacherReviewStateJournal(encoded)
    }

    private fun encodeTeacherReviewMarkGroupMetadata(
        metadata: Collection<TeacherReviewMarkGroupMetadata>,
    ): JSONArray = JSONArray().apply {
        metadata.sortedBy(TeacherReviewMarkGroupMetadata::groupId).forEach { value ->
            put(JSONObject()
                .put("groupId", value.groupId)
                .put("anchorXBits", value.anchor.x.toRawBits())
                .put("anchorYBits", value.anchor.y.toRawBits())
                .put("anchorPressureBits", value.anchor.pressure.toRawBits())
                .put("createdAt", value.createdAtEpochMillis)
                .put("hiddenAt", value.hiddenAtEpochMillis ?: JSONObject.NULL)
                .put("syncRevision", value.syncRevision)
                .put("lastModifiedByDeviceId", value.lastModifiedByDeviceId))
        }
    }

    private fun decodeTeacherReviewMarkGroupMetadata(
        values: JSONArray,
    ): List<TeacherReviewMarkGroupMetadata> {
        require(values.length() <= MAX_TEACHER_REVIEW_MARK_GROUPS) {
            "Teacher review authority has too much grade metadata"
        }
        val decoded = buildList(values.length()) {
            for (index in 0 until values.length()) {
                val value = values.getJSONObject(index)
                add(TeacherReviewMarkGroupMetadata(
                    groupId = value.getString("groupId"),
                    anchor = PagePoint(
                        Float.fromBits(value.getInt("anchorXBits")),
                        Float.fromBits(value.getInt("anchorYBits")),
                        Float.fromBits(value.getInt("anchorPressureBits")),
                    ),
                    createdAtEpochMillis = value.getLong("createdAt"),
                    hiddenAtEpochMillis = if (value.isNull("hiddenAt")) null else value.getLong("hiddenAt"),
                    syncRevision = value.getLong("syncRevision"),
                    lastModifiedByDeviceId = value.getString("lastModifiedByDeviceId"),
                ))
            }
        }
        val normalized = normalizeTeacherReviewMarkGroupMetadataValues(decoded)
        require(normalized == decoded.sortedBy(TeacherReviewMarkGroupMetadata::groupId)) {
            "Teacher review authority grade metadata is not normalized"
        }
        return normalized
    }

    private fun validateAppliedTeacherReviewReceipt(receipt: AppliedTeacherReviewReceipt) {
        require(receipt.bookId.length <= MAX_CHECKPOINT_IDENTIFIER_CHARS) {
            "Applied teacher review book id is too long"
        }
        require((receipt.remotePairId?.length ?: 0) <= MAX_CHECKPOINT_IDENTIFIER_CHARS) {
            "Applied teacher review pair id is too long"
        }
        require((receipt.remoteWorkbookToken?.length ?: 0) <= MAX_CHECKPOINT_IDENTIFIER_CHARS) {
            "Applied teacher review workbook token is too long"
        }
    }

    private fun decodeTeacherReviewPublishIntent(value: JSONObject): TeacherReviewPublishIntent =
        TeacherReviewPublishIntent(
            bookId = value.getString("bookId"),
            pageNumber = value.getInt("pageNumber"),
            attemptNo = value.getInt("attemptNo"),
            updatedAtEpochMillis = value.getLong("updatedAt"),
            publicationId = value.optString("publicationId"),
            checkpointSha256 = value.optString("checkpointSha256"),
            resultLayerSha256 = value.optString("resultLayerSha256"),
            checkpointSizeBytes = value.optInt("checkpointSizeBytes"),
            markGroupsSha256 = value.optString("markGroupsSha256"),
            markGroupsSizeBytes = value.optInt("markGroupsSizeBytes"),
            remotePairId = value.optionalString("remotePairId"),
            remoteWorkbookToken = value.optionalString("remoteWorkbookToken"),
            remoteManifestGeneration = value.optLong("remoteManifestGeneration"),
            remoteManifestSequence = value.optLong("remoteManifestSequence"),
        ).also(::validateTeacherReviewPublishIntent)

    private fun encodeTeacherReviewPublishIntent(intent: TeacherReviewPublishIntent): JSONObject {
        validateTeacherReviewPublishIntent(intent)
        return JSONObject()
            .put("bookId", intent.bookId)
            .put("pageNumber", intent.pageNumber)
            .put("attemptNo", intent.attemptNo)
            .put("updatedAt", intent.updatedAtEpochMillis)
            .put("publicationId", intent.publicationId)
            .put("checkpointSha256", intent.checkpointSha256)
            .put("resultLayerSha256", intent.resultLayerSha256)
            .put("checkpointSizeBytes", intent.checkpointSizeBytes)
            .put("markGroupsSha256", intent.markGroupsSha256)
            .put("markGroupsSizeBytes", intent.markGroupsSizeBytes)
            .put("remotePairId", intent.remotePairId ?: JSONObject.NULL)
            .put("remoteWorkbookToken", intent.remoteWorkbookToken ?: JSONObject.NULL)
            .put("remoteManifestGeneration", intent.remoteManifestGeneration)
            .put("remoteManifestSequence", intent.remoteManifestSequence)
    }

    private fun persistTeacherReviewPublishIntents(values: Collection<TeacherReviewPublishIntent>) {
        require(values.size <= MAX_TEACHER_REVIEW_PUBLISH_INTENTS)
        val root = JSONObject()
            .put("version", TEACHER_REVIEW_PUBLISH_INTENT_JOURNAL_VERSION)
            .put("intents", JSONArray().apply {
                values.sortedWith(
                    compareBy<TeacherReviewPublishIntent>(TeacherReviewPublishIntent::bookId)
                        .thenBy(TeacherReviewPublishIntent::pageNumber)
                        .thenBy(TeacherReviewPublishIntent::attemptNo),
                ).forEach { intent ->
                    validateTeacherReviewPublishIntent(intent)
                    put(JSONObject()
                        .put("bookId", intent.bookId)
                        .put("pageNumber", intent.pageNumber)
                        .put("attemptNo", intent.attemptNo)
                        .put("updatedAt", intent.updatedAtEpochMillis)
                        .put("publicationId", intent.publicationId)
                        .put("checkpointSha256", intent.checkpointSha256)
                        .put("resultLayerSha256", intent.resultLayerSha256)
                        .put("checkpointSizeBytes", intent.checkpointSizeBytes)
                        .put("markGroupsSha256", intent.markGroupsSha256)
                        .put("markGroupsSizeBytes", intent.markGroupsSizeBytes)
                        .put("remotePairId", intent.remotePairId ?: JSONObject.NULL)
                        .put("remoteWorkbookToken", intent.remoteWorkbookToken ?: JSONObject.NULL)
                        .put("remoteManifestGeneration", intent.remoteManifestGeneration)
                        .put("remoteManifestSequence", intent.remoteManifestSequence))
                }
            })
        val encoded = root.toString().toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= MAX_TEACHER_REVIEW_PUBLISH_INTENT_JOURNAL_BYTES) {
            "Teacher review publish intent journal exceeds its byte limit"
        }
        atomicWriteTeacherReviewPublishIntentJournal(encoded)
    }

    private fun validateTeacherReviewPublishIntent(intent: TeacherReviewPublishIntent) {
        require(intent.bookId.length <= MAX_CHECKPOINT_IDENTIFIER_CHARS) {
            "Publish intent book id is too long"
        }
        require((intent.remotePairId?.length ?: 0) <= MAX_CHECKPOINT_IDENTIFIER_CHARS) {
            "Publish intent remote pair id is too long"
        }
        require((intent.remoteWorkbookToken?.length ?: 0) <= MAX_CHECKPOINT_IDENTIFIER_CHARS) {
            "Publish intent remote workbook token is too long"
        }
    }

    private fun freezeTeacherReviewPublication(
        intent: TeacherReviewPublishIntent,
        snapshot: AnnotationSnapshot,
        publishedMarkGroups: List<MarkGroup>,
    ): FrozenTeacherReviewPublication {
        val export = buildPublishedTeacherLayerCheckpointExport(
            pageNumber = intent.pageNumber,
            attemptNo = intent.attemptNo,
            snapshot = snapshot,
            pointEncoding = AnnotationPointEncoding.LEGACY_FLOAT_ARRAYS,
        )
        val checkpointBytes = export.copyCheckpointBytes()
        val checkpointSha = sha256(checkpointBytes)
        val markGroupsBytes = encodeTeacherReviewMarkGroups(
            intent.bookId,
            intent.pageNumber,
            intent.attemptNo,
            publishedMarkGroups,
        )
        val markGroupsSha = sha256(markGroupsBytes)
        require(TeacherReviewPublicationLimits.fits(checkpointBytes.size, publishedMarkGroups)) {
            "Teacher review publication exceeds the shared transport limit"
        }
        // A publication identifies one explicit press, not merely the current bytes. This permits
        // a deliberate replay after a restore and makes identical student workbooks independent.
        val publicationId = sha256(
            buildString {
                append(intent.bookId).append('\n')
                append(intent.pageNumber).append('\n')
                append(intent.attemptNo).append('\n')
                append(checkpointSha).append('\n')
                append(markGroupsSha).append('\n')
                append(UUID.randomUUID())
            }.toByteArray(StandardCharsets.UTF_8),
        )
        val durable = intent.copy(
            publicationId = publicationId,
            checkpointSha256 = checkpointSha,
            resultLayerSha256 = export.layerSha256,
            checkpointSizeBytes = checkpointBytes.size,
            markGroupsSha256 = markGroupsSha,
            markGroupsSizeBytes = markGroupsBytes.size,
        )
        return FrozenTeacherReviewPublication(durable, checkpointBytes, markGroupsBytes)
    }

    private fun teacherReviewPublicationDirectory(): File =
        File(rootDirectory, TEACHER_REVIEW_PUBLICATION_ARTIFACTS_DIRECTORY).apply {
            check(mkdirs() || isDirectory) { "Cannot create teacher publication directory" }
        }

    private fun teacherReviewPublicationArtifactFile(publicationId: String): File {
        require(publicationId.matches(STUDENT_CHECKPOINT_ID)) { "Invalid teacher publication id" }
        return File(teacherReviewPublicationDirectory(), "$publicationId.checkpoint")
    }

    private fun teacherReviewPublicationMarkGroupsFile(publicationId: String): File =
        File(teacherReviewPublicationArtifactFile(publicationId).parentFile, "$publicationId.grades.json")

    private fun writeTeacherReviewPublicationArtifact(
        intent: TeacherReviewPublishIntent,
        bytes: ByteArray,
        markGroupsBytes: ByteArray,
    ) {
        require(bytes.size == intent.checkpointSizeBytes && sha256(bytes) == intent.checkpointSha256)
        require(
            markGroupsBytes.size == intent.markGroupsSizeBytes &&
                sha256(markGroupsBytes) == intent.markGroupsSha256
        )
        val target = teacherReviewPublicationArtifactFile(intent.publicationId)
        writeImmutableTeacherReviewArtifactFile(target, bytes, intent.checkpointSha256)
        writeImmutableTeacherReviewArtifactFile(
            teacherReviewPublicationMarkGroupsFile(intent.publicationId),
            markGroupsBytes,
            intent.markGroupsSha256,
        )
    }

    private fun writeImmutableTeacherReviewArtifactFile(
        target: File,
        bytes: ByteArray,
        expectedSha256: String,
    ) {
        if (target.isFile) {
            require(target.length() == bytes.size.toLong() && sha256(target.readBytes()) == expectedSha256) {
                "Teacher publication artifact collision"
            }
            return
        }
        val temporary = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temporary, false).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IOException("Cannot commit teacher publication artifact")
        }
    }

    private fun deleteUnreferencedTeacherReviewArtifact(publicationId: String) {
        // A restore invalidates both caches. ACK cleanup may reach this method through an older
        // store holder before any authority query has reloaded the restored state journal.
        ensureTeacherReviewPublishIntentsLoaded()
        ensureTeacherReviewStateLoaded()
        if (teacherReviewPublishIntents.values.any { it.publicationId == publicationId }) return
        if (teacherReviewAuthorities.values.any { it.intent.publicationId == publicationId }) return
        if (teacherReviewPreparationReferences(publicationId)) return
        teacherReviewPublicationArtifactFile(publicationId).delete()
        teacherReviewPublicationMarkGroupsFile(publicationId).delete()
    }

    private fun teacherReviewPublicationPreparationFile(
        bookId: String,
        pageNumber: Int,
        attemptNo: Int,
    ): File {
        val targetDigest = sha256(
            JSONObject()
                .put("bookId", bookId)
                .put("pageNumber", pageNumber)
                .put("attemptNo", attemptNo)
                .toString()
                .toByteArray(StandardCharsets.UTF_8),
        )
        return File(
            teacherReviewPublicationDirectory(),
            "$targetDigest$TEACHER_REVIEW_PREPARATION_SUFFIX",
        )
    }

    private fun encodeTeacherReviewPreparation(intent: TeacherReviewPublishIntent): ByteArray =
        JSONObject()
            .put("version", TEACHER_REVIEW_PREPARATION_VERSION)
            .put("bookId", intent.bookId)
            .put("pageNumber", intent.pageNumber)
            .put("attemptNo", intent.attemptNo)
            .put("updatedAt", intent.updatedAtEpochMillis)
            .put("publicationId", intent.publicationId)
            .put("checkpointSha256", intent.checkpointSha256)
            .put("resultLayerSha256", intent.resultLayerSha256)
            .put("checkpointSizeBytes", intent.checkpointSizeBytes)
            .put("markGroupsSha256", intent.markGroupsSha256)
            .put("markGroupsSizeBytes", intent.markGroupsSizeBytes)
            .put("remotePairId", intent.remotePairId ?: JSONObject.NULL)
            .put("remoteWorkbookToken", intent.remoteWorkbookToken ?: JSONObject.NULL)
            .put("remoteManifestGeneration", intent.remoteManifestGeneration)
            .put("remoteManifestSequence", intent.remoteManifestSequence)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
            .also { bytes ->
                require(bytes.size in 1..MAX_TEACHER_REVIEW_PREPARATION_BYTES) {
                    "Teacher review preparation is too large"
                }
            }

    private fun readTeacherReviewPublicationPreparation(file: File): TeacherReviewPublishIntent? {
        recoverTeacherReviewPreparation(file)
        if (!file.isFile) return null
        return runCatching {
            require(file.length() in 1..MAX_TEACHER_REVIEW_PREPARATION_BYTES)
            val value = JSONObject(decodeUtf8Strict(file.readBytes()))
            require(value.getInt("version") in 1..TEACHER_REVIEW_PREPARATION_VERSION)
            TeacherReviewPublishIntent(
                bookId = value.getString("bookId"),
                pageNumber = value.getInt("pageNumber"),
                attemptNo = value.getInt("attemptNo"),
                updatedAtEpochMillis = value.getLong("updatedAt"),
                publicationId = value.getString("publicationId"),
                checkpointSha256 = value.getString("checkpointSha256"),
                resultLayerSha256 = value.getString("resultLayerSha256"),
                checkpointSizeBytes = value.getInt("checkpointSizeBytes"),
                markGroupsSha256 = value.getString("markGroupsSha256"),
                markGroupsSizeBytes = value.getInt("markGroupsSizeBytes"),
                remotePairId = value.optionalString("remotePairId"),
                remoteWorkbookToken = value.optionalString("remoteWorkbookToken"),
                remoteManifestGeneration = value.optLong("remoteManifestGeneration"),
                remoteManifestSequence = value.optLong("remoteManifestSequence"),
            ).also { intent ->
                validateTeacherReviewPublishIntent(intent)
                require(
                    teacherReviewPublicationPreparationFile(
                        intent.bookId,
                        intent.pageNumber,
                        intent.attemptNo,
                    ).canonicalFile == file.canonicalFile,
                ) { "Teacher review preparation target mismatch" }
                require(readTeacherReviewPublicationArtifact(intent) != null) {
                    "Teacher review preparation artifact is missing"
                }
            }
        }.getOrElse {
            quarantineTeacherReviewPreparation(file)
            null
        }
    }

    private fun teacherReviewPreparationReferences(publicationId: String): Boolean =
        teacherReviewPublicationDirectory().listFiles().orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.endsWith(TEACHER_REVIEW_PREPARATION_SUFFIX) }
            .take(MAX_TEACHER_REVIEW_PUBLISH_INTENTS + 1)
            .any { file ->
                runCatching {
                    JSONObject(decodeUtf8Strict(file.readBytes())).optString("publicationId") == publicationId
                }.getOrDefault(false)
            }

    private fun recoverTeacherReviewPreparation(target: File) {
        val backup = File(target.parentFile, "${target.name}.bak")
        val temporary = File(target.parentFile, "${target.name}.tmp")
        if (!target.exists() && backup.isFile) backup.renameTo(target)
        if (target.isFile && backup.exists()) backup.delete()
        if (temporary.exists()) temporary.delete()
    }

    private fun atomicWriteTeacherReviewPreparation(target: File, bytes: ByteArray) {
        val backup = File(target.parentFile, "${target.name}.bak")
        val temporary = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temporary, false).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        if (backup.exists() && !backup.delete()) {
            temporary.delete()
            throw IOException("Cannot clear teacher review preparation backup")
        }
        if (target.exists() && !target.renameTo(backup)) {
            temporary.delete()
            throw IOException("Cannot stage teacher review preparation")
        }
        try {
            if (!temporary.renameTo(target)) throw IOException("Cannot commit teacher review preparation")
            if (backup.exists()) backup.delete()
        } catch (error: Throwable) {
            if (!target.exists() && backup.exists()) backup.renameTo(target)
            temporary.delete()
            throw error
        }
    }

    private fun quarantineTeacherReviewPreparation(file: File) {
        if (!file.exists()) return
        val quarantined = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
        if (!file.renameTo(quarantined)) file.delete()
    }

    private fun encodeTeacherReviewMarkGroups(
        bookId: String,
        pageNumber: Int,
        attemptNo: Int,
        groups: List<MarkGroup>,
    ): ByteArray {
        require(groups.size <= MAX_TEACHER_REVIEW_MARK_GROUPS)
        val encoded = JSONArray().apply {
            groups.sortedBy(MarkGroup::id).forEach { group ->
                require(group.bookId == bookId && group.pageNumber == pageNumber)
                require(group.id.isNotBlank() && group.id.length <= MAX_CHECKPOINT_IDENTIFIER_CHARS)
                require(group.marks.isNotEmpty() && group.marks.all { it.attemptNo == attemptNo })
                put(JSONObject()
                    .put("id", group.id)
                    .put("anchorX", group.anchor.x.toDouble())
                    .put("anchorY", group.anchor.y.toDouble())
                    .put("anchorPressure", group.anchor.pressure.toDouble())
                    .put("createdAt", group.createdAtEpochMillis)
                    .put("hiddenAt", group.hiddenAtEpochMillis ?: JSONObject.NULL)
                    .put("syncRevision", group.syncRevision)
                    .put("lastModifiedBy", group.lastModifiedByDeviceId)
                    .put("marks", JSONArray().apply {
                        group.marks.forEach { mark ->
                            put(JSONObject()
                                .put("attemptNo", mark.attemptNo)
                                .put("color", mark.color.name)
                                .put("gradedAt", mark.gradedAtEpochMillis)
                                .put("hiddenAt", mark.hiddenAtEpochMillis ?: JSONObject.NULL))
                        }
                    }))
            }
        }.toString().toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= MAX_TEACHER_REVIEW_MARK_GROUP_BYTES) {
            "Teacher review grade snapshot is too large"
        }
        return encoded
    }

    private fun decodeTeacherReviewMarkGroups(
        bookId: String,
        pageNumber: Int,
        attemptNo: Int,
        bytes: ByteArray,
    ): List<MarkGroup> {
        require(bytes.size in 1..MAX_TEACHER_REVIEW_MARK_GROUP_BYTES)
        val values = JSONArray(decodeUtf8Strict(bytes))
        require(values.length() <= MAX_TEACHER_REVIEW_MARK_GROUPS)
        return buildList(values.length()) {
            for (index in 0 until values.length()) {
                val value = values.getJSONObject(index)
                val marksJson = value.getJSONArray("marks")
                require(marksJson.length() in 1..MAX_TEACHER_REVIEW_MARKS_PER_GROUP)
                val marks = buildList(marksJson.length()) {
                    for (markIndex in 0 until marksJson.length()) {
                        val mark = marksJson.getJSONObject(markIndex)
                        add(Mark(
                            attemptNo = mark.getInt("attemptNo"),
                            color = MarkColor.valueOf(mark.getString("color")),
                            gradedAtEpochMillis = mark.getLong("gradedAt"),
                            hiddenAtEpochMillis = if (mark.isNull("hiddenAt")) null else mark.getLong("hiddenAt"),
                        ).also { require(it.attemptNo == attemptNo) })
                    }
                }
                add(MarkGroup(
                    id = value.getString("id"),
                    bookId = bookId,
                    pageNumber = pageNumber,
                    anchor = PagePoint(
                        value.getDouble("anchorX").toFloat(),
                        value.getDouble("anchorY").toFloat(),
                        value.getDouble("anchorPressure").toFloat(),
                    ),
                    marks = marks,
                    createdAtEpochMillis = value.getLong("createdAt"),
                    hiddenAtEpochMillis = if (value.isNull("hiddenAt")) null else value.getLong("hiddenAt"),
                    syncRevision = value.getLong("syncRevision"),
                    lastModifiedByDeviceId = value.getString("lastModifiedBy"),
                ))
            }
        }.also { groups -> require(groups.map(MarkGroup::id).distinct().size == groups.size) }
    }

    private fun recoverTeacherReviewPublishIntentJournal() {
        val target = File(rootDirectory, TEACHER_REVIEW_PUBLISH_INTENTS_FILE)
        val backup = File(rootDirectory, "$TEACHER_REVIEW_PUBLISH_INTENTS_FILE.bak")
        val temporary = File(rootDirectory, "$TEACHER_REVIEW_PUBLISH_INTENTS_FILE.tmp")
        if (!target.exists() && backup.isFile) backup.renameTo(target)
        if (target.isFile && backup.exists()) backup.delete()
        if (temporary.exists()) temporary.delete()
    }

    private fun atomicWriteTeacherReviewPublishIntentJournal(bytes: ByteArray) {
        val target = File(rootDirectory, TEACHER_REVIEW_PUBLISH_INTENTS_FILE)
        val backup = File(rootDirectory, "$TEACHER_REVIEW_PUBLISH_INTENTS_FILE.bak")
        val temporary = File(rootDirectory, "$TEACHER_REVIEW_PUBLISH_INTENTS_FILE.tmp")
        FileOutputStream(temporary, false).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        if (backup.exists() && !backup.delete()) {
            temporary.delete()
            throw IOException("Cannot clear teacher review publish intent backup")
        }
        if (target.exists() && !target.renameTo(backup)) {
            temporary.delete()
            throw IOException("Cannot stage teacher review publish intent journal")
        }
        try {
            if (!temporary.renameTo(target)) throw IOException("Cannot commit teacher review publish intent journal")
            // Once target exists it is authoritative. A stale backup is harmless and startup
            // recovery removes it; do not report a failed write after the new target was committed.
            if (backup.exists()) backup.delete()
        } catch (error: Throwable) {
            if (!target.exists() && backup.exists()) backup.renameTo(target)
            temporary.delete()
            throw error
        }
    }

    private fun quarantineTeacherReviewPublishIntentJournal(file: File) {
        if (!file.exists()) return
        var suffix = System.currentTimeMillis()
        var quarantined = File(file.parentFile, "${file.name}.corrupt-$suffix")
        while (quarantined.exists()) {
            suffix++
            quarantined = File(file.parentFile, "${file.name}.corrupt-$suffix")
        }
        file.renameTo(quarantined)
    }

    private fun recoverTeacherReviewStateJournal() {
        val target = File(rootDirectory, TEACHER_REVIEW_STATE_FILE)
        val backup = File(rootDirectory, "$TEACHER_REVIEW_STATE_FILE.bak")
        val temporary = File(rootDirectory, "$TEACHER_REVIEW_STATE_FILE.tmp")
        if (!target.exists() && backup.isFile) backup.renameTo(target)
        if (target.isFile && backup.exists()) backup.delete()
        if (temporary.exists()) temporary.delete()
    }

    private fun atomicWriteTeacherReviewStateJournal(bytes: ByteArray) {
        val target = File(rootDirectory, TEACHER_REVIEW_STATE_FILE)
        val backup = File(rootDirectory, "$TEACHER_REVIEW_STATE_FILE.bak")
        val temporary = File(rootDirectory, "$TEACHER_REVIEW_STATE_FILE.tmp")
        FileOutputStream(temporary, false).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        if (backup.exists() && !backup.delete()) {
            temporary.delete()
            throw IOException("Cannot clear teacher review state backup")
        }
        if (target.exists() && !target.renameTo(backup)) {
            temporary.delete()
            throw IOException("Cannot stage teacher review state journal")
        }
        try {
            if (!temporary.renameTo(target)) throw IOException("Cannot commit teacher review state journal")
            if (backup.exists()) backup.delete()
        } catch (error: Throwable) {
            if (!target.exists() && backup.exists()) backup.renameTo(target)
            temporary.delete()
            throw error
        }
    }

    private fun quarantineTeacherReviewStateJournal(file: File) {
        if (!file.exists()) return
        var suffix = System.currentTimeMillis()
        var quarantined = File(file.parentFile, "${file.name}.corrupt-$suffix")
        while (quarantined.exists()) {
            suffix++
            quarantined = File(file.parentFile, "${file.name}.corrupt-$suffix")
        }
        file.renameTo(quarantined)
    }

    @Synchronized
    private fun pageIndex(bookId: String, pageNumber: Int): PageIndex {
        val key = PageKey(bookId, pageNumber)
        pageIndexes[key]?.let { return it }
        // Drop the cold index before decoding another large page so the transient peak is bounded
        // too; waiting until after the read would briefly retain one extra full page.
        while (pageIndexes.size >= MAX_CACHED_PAGE_INDEXES) {
            val eldest = pageIndexes.entries.iterator()
            if (!eldest.hasNext()) break
            eldest.next()
            eldest.remove()
        }
        return readPageIndex(bookId, pageNumber).also { loaded ->
            pageIndexes[key] = loaded
        }
    }

    @Synchronized
    internal fun cachedPageIndexCount(): Int = pageIndexes.size

    @Synchronized
    internal fun cachedStudentLayerDigestCount(): Int = studentLayerDigests.size

    @Synchronized
    internal fun cachedPublishedTeacherLayerDigestCount(): Int = publishedTeacherLayerDigests.size

    @Synchronized
    internal fun cachedPublishedTeacherLayerGenerationCount(): Int =
        publishedTeacherLayerGenerations.size

    @Synchronized
    internal fun publishedTeacherLayerDigestMaterializationCount(): Long =
        publishedTeacherLayerDigestMaterializations

    @Synchronized
    internal fun isPageIndexCached(bookId: String, pageNumber: Int): Boolean =
        PageKey(bookId, pageNumber) in pageIndexes

    private fun pageDirectory(bookId: String, pageNumber: Int): File {
        require(pageNumber >= 0) { "Page number cannot be negative" }
        val safeBookId = bookId.safeFileName()
        return File(rootDirectory, "$safeBookId/pages/$pageNumber").apply {
            check(mkdirs() || isDirectory) { "Cannot create page partition" }
        }
    }

    private fun validatePartition(snapshot: AnnotationSnapshot, bookId: String, pageNumber: Int) {
        require(snapshot.bookId == bookId && snapshot.pageNumber == pageNumber) {
            "Annotation partition identity mismatch"
        }
    }

    private fun collectStudentCheckpointAssets(
        snapshot: AnnotationSnapshot,
        activeStudentIds: Set<StrokeId>,
    ): List<StrokeAsset> {
        val assets = collectPortableStudentLayerAssets(snapshot, activeStudentIds)
        validateStudentCheckpointLayerAssets(assets, snapshot.pageNumber)
        return assets
    }

    private fun validateStudentCheckpointLayerAssets(assets: List<StrokeAsset>, pageNumber: Int) {
        require(assets.size <= MAX_STUDENT_LAYER_CHECKPOINT_STROKES) {
            "Student layer checkpoint contains too many assets"
        }
        require(assets.sumOf { it.points.size.toLong() } <= MAX_STUDENT_LAYER_CHECKPOINT_TOTAL_POINTS) {
            "Student layer checkpoint contains too many points"
        }
        assets.forEach { validateStudentCheckpointAsset(it, pageNumber) }
    }

    /** Collects semantic student ink without applying a transport allocation policy to inventory. */
    private fun collectPortableStudentLayerAssets(
        snapshot: AnnotationSnapshot,
        activeStudentIds: Set<StrokeId>,
    ): List<StrokeAsset> {
        val included = linkedMapOf<StrokeId, StrokeAsset>()
        val pending = ArrayDeque(activeStudentIds)
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (id in included) continue
            val asset = requireNotNull(snapshot.assets[id]) { "Active student asset payload is missing" }
            validatePortableStudentLayerAsset(asset, snapshot.pageNumber)
            included[id] = asset
            asset.parentStrokeId?.let { parentId ->
                require(parentId != id) { "Student checkpoint asset cannot parent itself" }
                val parent = requireNotNull(snapshot.assets[parentId]) {
                    "Student checkpoint parent asset payload is missing"
                }
                require(parent.authorId == STUDENT_AUTHOR_ID && parent.pageNumber == snapshot.pageNumber) {
                    "Student checkpoint parent must belong to the same student page"
                }
                pending.addLast(parentId)
            }
        }
        return included.values.sortedBy { it.id.value }
    }

    private fun collectPublishedTeacherCheckpointAssets(
        snapshot: AnnotationSnapshot,
        activePublishedIds: Set<StrokeId>,
        attemptNo: Int,
    ): List<StrokeAsset> {
        val included = linkedMapOf<StrokeId, StrokeAsset>()
        val pending = ArrayDeque(activePublishedIds)
        var totalPoints = 0L
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (id in included) continue
            val asset = requireNotNull(snapshot.assets[id]) { "Active published teacher asset payload is missing" }
            validatePublishedTeacherCheckpointAsset(asset, snapshot.pageNumber, attemptNo)
            if (id in activePublishedIds) {
                require(asset.publishedAtEpochMillis != null) {
                    "Active published teacher checkpoint asset is not published"
                }
            }
            included[id] = asset
            totalPoints += asset.points.size.toLong()
            require(totalPoints <= MAX_PUBLISHED_TEACHER_LAYER_CHECKPOINT_TOTAL_POINTS) {
                "Published teacher layer checkpoint contains too many points"
            }
            asset.parentStrokeId?.let { parentId ->
                require(parentId != id) { "Published teacher checkpoint asset cannot parent itself" }
                val parent = requireNotNull(snapshot.assets[parentId]) {
                    "Published teacher checkpoint parent asset payload is missing"
                }
                require(
                    parent.authorId == TEACHER_AUTHOR_ID && parent.pageNumber == snapshot.pageNumber &&
                        parent.attemptNo == attemptNo
                ) { "Published teacher checkpoint parent must belong to the same teacher attempt" }
                pending.addLast(parentId)
            }
            require(included.size + pending.size <= MAX_PUBLISHED_TEACHER_LAYER_CHECKPOINT_STROKES) {
                "Published teacher layer checkpoint contains too many assets"
            }
        }
        return included.values.sortedBy { it.id.value }
    }

    private fun encodeStudentLayerCheckpointBody(
        sourcePageNumber: Int,
        sourceOperationClockHighWater: Long,
        assets: List<StrokeAsset>,
        activeStrokeIds: Set<StrokeId>,
        pointEncoding: AnnotationPointEncoding = AnnotationPointEncoding.LEGACY_FLOAT_ARRAYS,
    ): JSONObject = JSONObject()
        .put("checkpointFormatVersion", STUDENT_LAYER_CHECKPOINT_FORMAT_VERSION)
        .put("sourcePageNumber", sourcePageNumber)
        .put("sourceOperationClockHighWater", sourceOperationClockHighWater)
        .put("assets", JSONArray().apply {
            assets.sortedBy { it.id.value }.forEach {
                put(it.toJson(pointEncoding, MAX_STUDENT_CHECKPOINT_POINTS_PER_STROKE))
            }
        })
        .put("activeStrokeIds", JSONArray().apply {
            activeStrokeIds.sortedBy(StrokeId::value).forEach { put(it.value) }
        })

    /** Checkpoint identity is semantic and therefore independent of its requested wire encoding. */
    private fun studentLayerCheckpointId(
        sourcePageNumber: Int,
        sourceOperationClockHighWater: Long,
        assets: List<StrokeAsset>,
        activeStrokeIds: Set<StrokeId>,
    ): String = sha256(
        encodeStudentLayerCheckpointBody(
            sourcePageNumber = sourcePageNumber,
            sourceOperationClockHighWater = sourceOperationClockHighWater,
            assets = assets,
            activeStrokeIds = activeStrokeIds,
            pointEncoding = AnnotationPointEncoding.LEGACY_FLOAT_ARRAYS,
        ).toString().toByteArray(StandardCharsets.UTF_8)
    )

    private fun encodePublishedTeacherLayerCheckpointBody(
        sourcePageNumber: Int,
        attemptNo: Int,
        sourceOperationClockHighWater: Long,
        assets: List<StrokeAsset>,
        activeStrokeIds: Set<StrokeId>,
        pointEncoding: AnnotationPointEncoding = AnnotationPointEncoding.LEGACY_FLOAT_ARRAYS,
    ): JSONObject = JSONObject()
        .put("publishedTeacherCheckpointFormatVersion", PUBLISHED_TEACHER_LAYER_CHECKPOINT_FORMAT_VERSION)
        .put("sourcePageNumber", sourcePageNumber)
        .put("attemptNo", attemptNo)
        .put("sourceOperationClockHighWater", sourceOperationClockHighWater)
        .put("assets", JSONArray().apply {
            assets.sortedBy { it.id.value }.forEach {
                put(it.toJson(pointEncoding, MAX_PUBLISHED_TEACHER_CHECKPOINT_POINTS_PER_STROKE))
            }
        })
        .put("activeStrokeIds", JSONArray().apply {
            activeStrokeIds.sortedBy(StrokeId::value).forEach { put(it.value) }
        })

    /** Published checkpoint identity likewise remains stable across representation negotiation. */
    private fun publishedTeacherLayerCheckpointId(
        sourcePageNumber: Int,
        attemptNo: Int,
        sourceOperationClockHighWater: Long,
        assets: List<StrokeAsset>,
        activeStrokeIds: Set<StrokeId>,
    ): String = sha256(
        encodePublishedTeacherLayerCheckpointBody(
            sourcePageNumber = sourcePageNumber,
            attemptNo = attemptNo,
            sourceOperationClockHighWater = sourceOperationClockHighWater,
            assets = assets,
            activeStrokeIds = activeStrokeIds,
            pointEncoding = AnnotationPointEncoding.LEGACY_FLOAT_ARRAYS,
        ).toString().toByteArray(StandardCharsets.UTF_8)
    )

    private fun encodeStudentLayerDigestBody(
        sourcePageNumber: Int,
        assets: List<StrokeAsset>,
        activeStrokeIds: Set<StrokeId>,
    ): JSONObject = JSONObject()
        .put("layerDigestFormatVersion", PORTABLE_LAYER_DIGEST_FORMAT_VERSION)
        .put("layerKind", STUDENT_LAYER_DIGEST_KIND)
        .put("sourcePageNumber", sourcePageNumber)
        .put("assets", JSONArray().apply {
            assets.sortedBy { it.id.value }.forEach { put(it.toJson()) }
        })
        .put("activeStrokeIds", JSONArray().apply {
            activeStrokeIds.sortedBy(StrokeId::value).forEach { put(it.value) }
        })

    private fun encodePublishedTeacherLayerDigestBody(
        sourcePageNumber: Int,
        attemptNo: Int,
        assets: List<StrokeAsset>,
        activeStrokeIds: Set<StrokeId>,
    ): JSONObject = JSONObject()
        .put("layerDigestFormatVersion", PORTABLE_LAYER_DIGEST_FORMAT_VERSION)
        .put("layerKind", PUBLISHED_TEACHER_LAYER_DIGEST_KIND)
        .put("sourcePageNumber", sourcePageNumber)
        .put("attemptNo", attemptNo)
        .put("assets", JSONArray().apply {
            assets.sortedBy { it.id.value }.forEach { put(it.toJson()) }
        })
        .put("activeStrokeIds", JSONArray().apply {
            activeStrokeIds.sortedBy(StrokeId::value).forEach { put(it.value) }
        })

    private fun decodeStudentLayerCheckpoint(bytes: ByteArray): DecodedStudentLayerCheckpoint {
        require(bytes.isNotEmpty()) { "Student layer checkpoint is empty" }
        require(bytes.size <= MAX_STUDENT_LAYER_CHECKPOINT_BYTES) {
            "Student layer checkpoint exceeds $MAX_STUDENT_LAYER_CHECKPOINT_BYTES bytes"
        }
        val text = decodeUtf8Strict(bytes)
        val root = JSONObject(text)
        require(root.getInt("checkpointFormatVersion") == STUDENT_LAYER_CHECKPOINT_FORMAT_VERSION) {
            "Unsupported student layer checkpoint format"
        }
        val sourcePageNumber = root.getInt("sourcePageNumber")
        require(sourcePageNumber >= 0) { "Student layer checkpoint page cannot be negative" }
        val sourceHighWater = root.getLong("sourceOperationClockHighWater")
        require(sourceHighWater in 0 until Long.MAX_VALUE) { "Student layer checkpoint clock is invalid" }
        val assetsJson = root.getJSONArray("assets")
        require(assetsJson.length() <= MAX_STUDENT_LAYER_CHECKPOINT_STROKES) {
            "Student layer checkpoint contains too many assets"
        }
        val assets = buildList {
            for (index in 0 until assetsJson.length()) {
                add(assetsJson.getJSONObject(index).toStrokeAsset(
                    maximumCompactPointCount = MAX_STUDENT_CHECKPOINT_POINTS_PER_STROKE,
                ).also {
                    validateStudentCheckpointAsset(it, sourcePageNumber)
                })
            }
        }
        require(assets.map(StrokeAsset::id).distinct().size == assets.size) {
            "Student layer checkpoint contains duplicate asset ids"
        }
        require(assets.sumOf { it.points.size.toLong() } <= MAX_STUDENT_LAYER_CHECKPOINT_TOTAL_POINTS) {
            "Student layer checkpoint contains too many points"
        }
        require(sourceHighWater >= (assets.maxOfOrNull(StrokeAsset::logicalClock) ?: 0L)) {
            "Student layer checkpoint clock is behind its assets"
        }
        val activeJson = root.getJSONArray("activeStrokeIds")
        require(activeJson.length() <= MAX_STUDENT_LAYER_CHECKPOINT_STROKES) {
            "Student layer checkpoint contains too many active assets"
        }
        val activeIds = linkedSetOf<StrokeId>()
        for (index in 0 until activeJson.length()) {
            val id = StrokeId(activeJson.getString(index))
            require(id.value.isNotBlank() && id.value.length <= MAX_CHECKPOINT_IDENTIFIER_CHARS) {
                "Student layer checkpoint active id is invalid"
            }
            require(activeIds.add(id)) { "Student layer checkpoint contains duplicate active ids" }
        }
        val byId = assets.associateBy(StrokeAsset::id)
        require(activeIds.all(byId::containsKey)) { "Student layer checkpoint is missing an active asset payload" }
        val reachable = linkedSetOf<StrokeId>()
        val pending = ArrayDeque(activeIds)
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (!reachable.add(id)) continue
            val asset = requireNotNull(byId[id]) { "Student layer checkpoint parent payload is missing" }
            asset.parentStrokeId?.let { parentId ->
                require(parentId != id && parentId in byId) {
                    "Student layer checkpoint parent payload is missing"
                }
                pending.addLast(parentId)
            }
        }
        require(reachable == byId.keys) { "Student layer checkpoint contains unrelated inactive assets" }
        val canonicalBody = encodeStudentLayerCheckpointBody(
            sourcePageNumber = sourcePageNumber,
            sourceOperationClockHighWater = sourceHighWater,
            assets = assets,
            activeStrokeIds = activeIds,
        )
        val expectedId = sha256(canonicalBody.toString().toByteArray(StandardCharsets.UTF_8))
        val checkpointId = root.getString("checkpointId")
        require(STUDENT_CHECKPOINT_ID.matches(checkpointId) && checkpointId == expectedId) {
            "Student layer checkpoint id does not match its payload"
        }
        return DecodedStudentLayerCheckpoint(
            checkpointId = checkpointId,
            sourcePageNumber = sourcePageNumber,
            sourceOperationClockHighWater = sourceHighWater,
            assets = assets.map { asset -> asset.copy(points = asset.points.toList()) },
            activeStrokeIds = activeIds.toSet(),
        )
    }

    private fun decodePublishedTeacherLayerCheckpoint(bytes: ByteArray): DecodedPublishedTeacherLayerCheckpoint {
        require(bytes.isNotEmpty()) { "Published teacher layer checkpoint is empty" }
        require(bytes.size <= MAX_PUBLISHED_TEACHER_LAYER_CHECKPOINT_BYTES) {
            "Published teacher layer checkpoint exceeds $MAX_PUBLISHED_TEACHER_LAYER_CHECKPOINT_BYTES bytes"
        }
        val root = JSONObject(decodeUtf8Strict(bytes))
        require(
            root.getInt("publishedTeacherCheckpointFormatVersion") ==
                PUBLISHED_TEACHER_LAYER_CHECKPOINT_FORMAT_VERSION
        ) { "Unsupported published teacher layer checkpoint format" }
        val sourcePageNumber = root.getInt("sourcePageNumber")
        require(sourcePageNumber >= 0) { "Published teacher checkpoint page cannot be negative" }
        val attemptNo = root.getInt("attemptNo")
        require(attemptNo > 0) { "Published teacher checkpoint attempt must be positive" }
        val sourceHighWater = root.getLong("sourceOperationClockHighWater")
        require(sourceHighWater in 0 until Long.MAX_VALUE) { "Published teacher checkpoint clock is invalid" }
        val assetsJson = root.getJSONArray("assets")
        require(assetsJson.length() <= MAX_PUBLISHED_TEACHER_LAYER_CHECKPOINT_STROKES) {
            "Published teacher layer checkpoint contains too many assets"
        }
        val assets = buildList {
            for (index in 0 until assetsJson.length()) {
                add(assetsJson.getJSONObject(index).toStrokeAsset(
                    maximumCompactPointCount = MAX_PUBLISHED_TEACHER_CHECKPOINT_POINTS_PER_STROKE,
                ).also { asset ->
                    validatePublishedTeacherCheckpointAsset(asset, sourcePageNumber, attemptNo)
                })
            }
        }
        require(assets.map(StrokeAsset::id).distinct().size == assets.size) {
            "Published teacher layer checkpoint contains duplicate asset ids"
        }
        require(assets.sumOf { it.points.size.toLong() } <= MAX_PUBLISHED_TEACHER_LAYER_CHECKPOINT_TOTAL_POINTS) {
            "Published teacher layer checkpoint contains too many points"
        }
        require(sourceHighWater >= (assets.maxOfOrNull(StrokeAsset::logicalClock) ?: 0L)) {
            "Published teacher layer checkpoint clock is behind its assets"
        }
        val activeJson = root.getJSONArray("activeStrokeIds")
        require(activeJson.length() <= MAX_PUBLISHED_TEACHER_LAYER_CHECKPOINT_STROKES) {
            "Published teacher layer checkpoint contains too many active assets"
        }
        val activeIds = linkedSetOf<StrokeId>()
        for (index in 0 until activeJson.length()) {
            val id = StrokeId(activeJson.getString(index))
            require(id.value.isNotBlank() && id.value.length <= MAX_CHECKPOINT_IDENTIFIER_CHARS) {
                "Published teacher layer checkpoint active id is invalid"
            }
            require(activeIds.add(id)) {
                "Published teacher layer checkpoint contains duplicate active ids"
            }
        }
        val byId = assets.associateBy(StrokeAsset::id)
        require(activeIds.all { id -> byId[id]?.publishedAtEpochMillis != null }) {
            "Published teacher layer checkpoint is missing a published active asset payload"
        }
        val reachable = linkedSetOf<StrokeId>()
        val pending = ArrayDeque(activeIds)
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (!reachable.add(id)) continue
            val asset = requireNotNull(byId[id]) {
                "Published teacher layer checkpoint parent payload is missing"
            }
            asset.parentStrokeId?.let { parentId ->
                require(parentId != id && parentId in byId) {
                    "Published teacher layer checkpoint parent payload is missing"
                }
                pending.addLast(parentId)
            }
        }
        require(reachable == byId.keys) {
            "Published teacher layer checkpoint contains unrelated inactive assets"
        }
        val canonicalBody = encodePublishedTeacherLayerCheckpointBody(
            sourcePageNumber = sourcePageNumber,
            attemptNo = attemptNo,
            sourceOperationClockHighWater = sourceHighWater,
            assets = assets,
            activeStrokeIds = activeIds,
        )
        val expectedId = sha256(canonicalBody.toString().toByteArray(StandardCharsets.UTF_8))
        val checkpointId = root.getString("checkpointId")
        require(STUDENT_CHECKPOINT_ID.matches(checkpointId) && checkpointId == expectedId) {
            "Published teacher layer checkpoint id does not match its payload"
        }
        return DecodedPublishedTeacherLayerCheckpoint(
            checkpointId = checkpointId,
            sourcePageNumber = sourcePageNumber,
            attemptNo = attemptNo,
            sourceOperationClockHighWater = sourceHighWater,
            assets = assets.map { asset -> asset.copy(points = asset.points.toList()) },
            activeStrokeIds = activeIds.toSet(),
        )
    }

    private fun validatePortableStudentLayerAsset(asset: StrokeAsset, sourcePageNumber: Int) {
        validateCheckpointAssetPayload(
            asset = asset,
            sourcePageNumber = sourcePageNumber,
            label = "Student layer",
            maxPointsPerStroke = null,
        )
        require(asset.authorId == STUDENT_AUTHOR_ID) { "Student layer contains a non-student asset" }
        require(asset.attemptNo > 0) { "Student layer attempt must be positive" }
    }

    private fun validateStudentCheckpointAsset(asset: StrokeAsset, sourcePageNumber: Int) {
        validateCheckpointAssetPayload(
            asset = asset,
            sourcePageNumber = sourcePageNumber,
            label = "Student checkpoint",
            maxPointsPerStroke = MAX_STUDENT_CHECKPOINT_POINTS_PER_STROKE,
        )
        require(asset.authorId == STUDENT_AUTHOR_ID) { "Student checkpoint contains a non-student asset" }
        require(asset.attemptNo > 0) { "Student checkpoint attempt must be positive" }
    }

    private fun validatePublishedTeacherCheckpointAsset(
        asset: StrokeAsset,
        sourcePageNumber: Int,
        attemptNo: Int,
    ) {
        validateCheckpointAssetPayload(
            asset = asset,
            sourcePageNumber = sourcePageNumber,
            label = "Published teacher checkpoint",
            maxPointsPerStroke = MAX_PUBLISHED_TEACHER_CHECKPOINT_POINTS_PER_STROKE,
        )
        require(asset.authorId == TEACHER_AUTHOR_ID) {
            "Published teacher checkpoint contains a non-teacher asset"
        }
        require(asset.attemptNo == attemptNo) { "Published teacher checkpoint attempt mismatch" }
    }

    private fun validateCheckpointAssetPayload(
        asset: StrokeAsset,
        sourcePageNumber: Int,
        label: String,
        maxPointsPerStroke: Int?,
    ) {
        require(asset.id.value.isNotBlank() && asset.id.value.length <= MAX_CHECKPOINT_IDENTIFIER_CHARS) {
            "$label asset id is invalid"
        }
        require(asset.pageNumber == sourcePageNumber) { "$label asset page mismatch" }
        require(asset.logicalClock in 0 until Long.MAX_VALUE) { "$label asset clock is invalid" }
        require(asset.deviceId.isNotBlank() && asset.deviceId.length <= MAX_CHECKPOINT_IDENTIFIER_CHARS) {
            "$label device id is invalid"
        }
        val itemId = asset.itemId
        require(itemId == null || itemId.length <= MAX_CHECKPOINT_ITEM_ID_CHARS) {
            "$label item id is too long"
        }
        val parentStrokeId = asset.parentStrokeId
        require(parentStrokeId == null ||
            parentStrokeId.value.isNotBlank() &&
            parentStrokeId.value.length <= MAX_CHECKPOINT_IDENTIFIER_CHARS
        ) { "$label parent id is invalid" }
        require(asset.width.isFinite() && asset.width > 0f) { "$label width is invalid" }
        require(asset.points.isNotEmpty() &&
            (maxPointsPerStroke == null || asset.points.size <= maxPointsPerStroke)
        ) {
            "$label point count is invalid"
        }
        require(asset.points.all { point ->
            point.x.isFinite() && point.y.isFinite() && point.pressure.isFinite() && point.pressure >= 0f
        }) { "$label point is invalid" }
        require(listOf(asset.bounds.left, asset.bounds.top, asset.bounds.right, asset.bounds.bottom).all(Float::isFinite)) {
            "$label bounds are invalid"
        }
        require(asset.bounds.left <= asset.bounds.right && asset.bounds.top <= asset.bounds.bottom) {
            "$label bounds are inverted"
        }
        require(asset.createdAtEpochMillis >= 0L && (asset.publishedAtEpochMillis ?: 0L) >= 0L) {
            "$label timestamp is invalid"
        }
        require(asset.formatVersion == ANNOTATION_FORMAT_VERSION) { "Unsupported $label stroke format" }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun decodeUtf8Strict(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()

    private fun readCheckpointSafely(file: File): DecodedPageCheckpoint = try {
        FileInputStream(file).use { input ->
            JsonReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                readSnapshotJson(reader).also {
                    require(reader.peek() == JsonToken.END_DOCUMENT) {
                        "Unexpected data after annotation checkpoint"
                    }
                }
            }
        }
    } catch (error: Exception) {
        // A valid checkpoint must never be quarantined merely because the process exhausted its
        // heap. Parse/IO failures are durable corruption; VM failures are not.
        quarantineAndThrow(file, error)
    }

    private fun quarantineAndThrow(file: File, error: Throwable): Nothing {
        pageIndexes.entries.removeAll { (key, _) ->
            val directory = File(rootDirectory, "${key.bookId.safeFileName()}/pages/${key.pageNumber}")
            file.absolutePath.startsWith(directory.absolutePath)
        }
        // A quarantined log changes the durable state outside the ordinary mutation paths. Clear
        // every structural witness so no prior digest can describe data that was just isolated.
        studentLayerDigests.clear()
        publishedTeacherLayerDigests.clear()
        publishedTeacherLayerGenerations.clear()
        val quarantined = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
        if (!file.renameTo(quarantined)) {
            throw CorruptAnnotationDataException("손상된 필기 데이터를 격리하지 못했습니다.", file, error)
        }
        throw CorruptAnnotationDataException("손상된 필기 데이터를 격리했습니다.", quarantined, error)
    }

    private fun atomicWriteCheckpoint(
        target: File,
        snapshot: AnnotationSnapshot,
        maximumClockByDevice: Map<String, Long>,
    ) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(temporary, false).use { output ->
                val buffered = BufferedWriter(OutputStreamWriter(output, StandardCharsets.UTF_8))
                JsonWriter(buffered).use { writer ->
                    writer.setSerializeNulls(true)
                    writeSnapshotJson(writer, snapshot, maximumClockByDevice)
                    writer.flush()
                    output.fd.sync()
                }
            }
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(temporary.toPath()) }
            throw error
        }
    }

    private data class StoredOperationRecord(
        val bookId: String,
        val pageNumber: Int,
        val revision: Long,
        val operation: AssetOperation,
        val addedAssets: List<StrokeAsset>,
        /** Wall-clock ordering aid only; operation clocks remain the merge authority. */
        val recordedAtEpochMillis: Long = 0L,
    )

    /** Severs the only caller-owned collections before records may be encoded outside the lock. */
    private fun StoredOperationRecord.immutableCopy(): StoredOperationRecord = copy(
        operation = operation.copy(
            removedStrokeIds = operation.removedStrokeIds.toSet(),
            addedStrokeIds = operation.addedStrokeIds.toSet(),
        ),
        addedAssets = addedAssets.map { asset -> asset.copy(points = asset.points.toList()) },
    )

    private data class PageKey(val bookId: String, val pageNumber: Int)

    private data class StudentLayerDigest(val revision: Long, val sha256: String)

    private data class PublishedTeacherLayerDigest(
        val mutationGeneration: Long,
        val activeIdSignatureSha256: String,
        val sha256: String,
    )

    private data class StudentCheckpointCapture(
        val snapshot: AnnotationSnapshot,
        val originDeviceHighWater: Long,
    )

    private data class TeacherReviewIntentKey(
        val bookId: String,
        val pageNumber: Int,
        val attemptNo: Int,
    )

    private data class TeacherReviewAuthorityRecord(
        val intent: TeacherReviewPublishIntent,
        /** Exact-attempt mark-history digest; page-global metadata is combined when evidence is read. */
        val markGroupsStateSha256: String,
        /** Captured once from the immutable artifact; null only when an old row cannot be migrated. */
        val markGroupMetadata: List<TeacherReviewMarkGroupMetadata>?,
    ) {
        init {
            require(markGroupsStateSha256.matches(Regex("[0-9a-f]{64}"))) {
                "Teacher review authority grade state digest is invalid"
            }
        }

        fun evidence(pageMetadataSha256: String): TeacherReviewStateEvidence = TeacherReviewStateEvidence(
            attemptNo = intent.attemptNo,
            publicationId = intent.publicationId,
            resultLayerSha256 = intent.resultLayerSha256,
            markGroupsSha256 = teacherReviewGradeStateSha256(
                markGroupsStateSha256,
                pageMetadataSha256,
            ),
        )
    }

    private data class IndexedOperation(
        val record: StoredOperationRecord,
        val encoded: ByteArray,
    )

    private data class PageIndex(
        var snapshot: AnnotationSnapshot,
        val records: MutableList<IndexedOperation>,
        val byDevice: MutableMap<String, MutableList<IndexedOperation>>,
        val maximumClockByDevice: MutableMap<String, Long>,
    ) {
        fun add(record: StoredOperationRecord, encoded: ByteArray, updated: AnnotationSnapshot) {
            val indexed = IndexedOperation(record, encoded)
            records += indexed
            byDevice.getOrPut(record.operation.deviceId, ::mutableListOf).apply {
                val insertion = binarySearchBy(record.operation.logicalClock) { it.record.operation.logicalClock }
                add(if (insertion < 0) -insertion - 1 else insertion, indexed)
            }
            maximumClockByDevice[record.operation.deviceId] = maxOf(
                maximumClockByDevice[record.operation.deviceId] ?: 0L,
                record.operation.logicalClock,
            )
            snapshot = updated
        }
    }

    private fun StoredOperationRecord.isPublishable(snapshot: AnnotationSnapshot): Boolean {
        if (addedAssets.isNotEmpty()) {
            return addedAssets.none { it.authorId == "teacher" && it.publishedAtEpochMillis == null }
        }
        // Redo reactivates an existing asset and therefore carries no addedAssets payload. Looking
        // only at addedAssets would classify redo of a private teacher draft as publishable even
        // though the peer never received the referenced draft asset. Besides leaking a draft, that
        // operation makes the peer reject the log row as a missing-asset dependency.
        val reactivatedAssets = operation.addedStrokeIds.mapNotNull(snapshot.assets::get)
        if (reactivatedAssets.size != operation.addedStrokeIds.size) return false
        if (reactivatedAssets.any {
                it.authorId == "teacher" && it.publishedAtEpochMillis == null
            }
        ) return false
        val removedAssets = operation.removedStrokeIds.mapNotNull(snapshot.assets::get)
        return removedAssets.isEmpty() || removedAssets.none {
            it.authorId == "teacher" && it.publishedAtEpochMillis == null
        }
    }

    private fun List<IndexedOperation>.firstIndexAfterRevision(revision: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (this[middle].record.revision <= revision) low = middle + 1 else high = middle
        }
        return low
    }

    private fun List<IndexedOperation>.firstIndexAfterClock(clock: Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (this[middle].record.operation.logicalClock <= clock) low = middle + 1 else high = middle
        }
        return low
    }

    private fun apply(snapshot: AnnotationSnapshot, record: StoredOperationRecord): AnnotationSnapshot {
        val assets = snapshot.assets.toMutableMap()
        record.addedAssets.forEach { assets[it.id] = it }
        val active = snapshot.activeStrokeIds.toMutableSet().apply {
            removeAll(record.operation.removedStrokeIds)
            addAll(record.operation.addedStrokeIds)
        }
        val missing = record.operation.addedStrokeIds - assets.keys
        require(missing.isEmpty()) { "Operation references missing assets: $missing" }
        return AnnotationSnapshot(
            bookId = snapshot.bookId,
            pageNumber = snapshot.pageNumber,
            revision = record.revision,
            assets = assets,
            activeStrokeIds = active,
            appliedOperationIds = snapshot.appliedOperationIds + record.operation.id,
        )
    }

    private fun StoredOperationRecord.encodeBytes(
        pointEncoding: AnnotationPointEncoding,
    ): ByteArray = encodeRecord(this, pointEncoding)
        .toString()
        .toByteArray(StandardCharsets.UTF_8)

    private fun encodeRecord(
        record: StoredOperationRecord,
        pointEncoding: AnnotationPointEncoding,
    ) = JSONObject()
        .put("formatVersion", ANNOTATION_FORMAT_VERSION)
        .put("bookId", record.bookId)
        .put("pageNumber", record.pageNumber)
        .put("revision", record.revision)
        .put("operation", record.operation.toJson())
        .put("addedAssets", JSONArray().apply {
            record.addedAssets.forEach {
                put(it.toJson(pointEncoding, MAX_GENERAL_COMPACT_POINTS_PER_STROKE))
            }
        })
        .put("recordedAtEpochMillis", record.recordedAtEpochMillis)

    private fun decodeRecord(root: JSONObject): StoredOperationRecord {
        root.requireFormatVersion()
        return StoredOperationRecord(
            bookId = root.getString("bookId"),
            pageNumber = root.getInt("pageNumber"),
            revision = root.getLong("revision"),
            operation = root.getJSONObject("operation").toOperation(),
            addedAssets = root.getJSONArray("addedAssets").toStrokeAssets(
                maximumCompactPointCount = MAX_GENERAL_COMPACT_POINTS_PER_STROKE,
            ),
            recordedAtEpochMillis = root.optLong("recordedAtEpochMillis").coerceAtLeast(0L),
        )
    }

    private fun JSONObject.optionalString(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name)

    private fun writeSnapshotJson(
        writer: JsonWriter,
        snapshot: AnnotationSnapshot,
        maximumClockByDevice: Map<String, Long>,
    ) {
        require(maximumClockByDevice.size <= MAX_CHECKPOINT_CLOCK_DEVICES) {
            "Checkpoint contains too many operation clock origins"
        }
        writer.beginObject()
        writer.name("formatVersion").value(ANNOTATION_FORMAT_VERSION.toLong())
        writer.name("bookId").value(snapshot.bookId)
        writer.name("pageNumber").value(snapshot.pageNumber.toLong())
        writer.name("revision").value(snapshot.revision)
        writer.name("operationClockHighWaterByDevice").beginObject()
        maximumClockByDevice.toSortedMap().forEach { (deviceId, logicalClock) ->
            require(deviceId.isNotBlank() && deviceId.length <= MAX_CHECKPOINT_IDENTIFIER_CHARS) {
                "Checkpoint operation clock device id is invalid"
            }
            require(logicalClock in 0 until Long.MAX_VALUE) {
                "Checkpoint operation clock is invalid"
            }
            writer.name(deviceId).value(logicalClock)
        }
        writer.endObject()
        writer.name("assets").beginArray()
        snapshot.assets.values.forEach { asset ->
            writeStrokeAssetJson(
                writer = writer,
                asset = asset,
                pointEncoding = LOCAL_POINT_ENCODING,
                maximumCompactPointCount = MAX_LOCAL_CHECKPOINT_POINTS_PER_STROKE,
            )
        }
        writer.endArray()
        writer.name("activeStrokeIds").beginArray()
        snapshot.activeStrokeIds.forEach { id -> writer.value(id.value) }
        writer.endArray()
        writer.name("appliedOperationIds").beginArray()
        snapshot.appliedOperationIds.forEach { id -> writer.value(id.value) }
        writer.endArray()
        writer.endObject()
    }

    private data class EncodedPointPayload(
        val fieldName: String,
        val base64: String,
        val pointCount: Int,
    )

    /** Returns null only when legacy arrays were requested or a legacy oversized asset is retained. */
    private fun encodedPointPayload(
        points: List<PagePoint>,
        pointEncoding: AnnotationPointEncoding,
        maximumCompactPointCount: Int,
    ): EncodedPointPayload? {
        require(maximumCompactPointCount in 0..MAX_GENERAL_COMPACT_POINTS_PER_STROKE) {
            "Compact point limit is invalid"
        }
        if (pointEncoding != AnnotationPointEncoding.COMPACT_Q16_DELTA ||
            points.size > maximumCompactPointCount
        ) return null
        require(points.all { it.x.isFinite() && it.y.isFinite() && it.pressure.isFinite() }) {
            "Compact stroke points must be finite"
        }
        val (fieldName, bytes) = if (CompactPagePointCodec.canEncodeExactly(points)) {
            COMPACT_POINTS_FIELD to CompactPagePointCodec.encode(points)
        } else {
            LOSSLESS_F32_POINTS_FIELD to LosslessF32PagePointCodec.encode(
                points = points,
                maximumPointCount = maximumCompactPointCount,
            )
        }
        return EncodedPointPayload(
            fieldName = fieldName,
            base64 = Base64.getEncoder().encodeToString(bytes),
            pointCount = points.size,
        )
    }

    private fun writeStrokeAssetJson(
        writer: JsonWriter,
        asset: StrokeAsset,
        pointEncoding: AnnotationPointEncoding,
        maximumCompactPointCount: Int,
    ) {
        writer.beginObject()
        writer.name("id").value(asset.id.value)
        writer.name("pageNumber").value(asset.pageNumber.toLong())
        writer.name("tool").value(asset.tool.name)
        writer.name("colorArgb").value(asset.colorArgb.toLong())
        writer.name("width").value(asset.width.toDouble())
        writer.name("authorId").value(asset.authorId)
        writer.name("attemptNo").value(asset.attemptNo.toLong())
        writer.name("logicalClock").value(asset.logicalClock)
        writer.name("deviceId").value(asset.deviceId)
        writer.name("itemId")
        asset.itemId?.let(writer::value) ?: writer.nullValue()
        writer.name("publishedAt")
        asset.publishedAtEpochMillis?.let(writer::value) ?: writer.nullValue()
        val compact = encodedPointPayload(
            points = asset.points,
            pointEncoding = pointEncoding,
            maximumCompactPointCount = maximumCompactPointCount,
        )
        if (compact != null) {
            writer.name(compact.fieldName).value(compact.base64)
            writer.name(COMPACT_POINT_COUNT_FIELD).value(compact.pointCount.toLong())
        } else {
            writer.name("points").beginArray()
            asset.points.forEach { point ->
                writer.beginArray()
                writer.value(point.x.toDouble())
                writer.value(point.y.toDouble())
                writer.value(point.pressure.toDouble())
                writer.endArray()
            }
            writer.endArray()
        }
        writer.name("bounds").beginArray()
        writer.value(asset.bounds.left.toDouble())
        writer.value(asset.bounds.top.toDouble())
        writer.value(asset.bounds.right.toDouble())
        writer.value(asset.bounds.bottom.toDouble())
        writer.endArray()
        writer.name("createdAtEpochMillis").value(asset.createdAtEpochMillis)
        writer.name("parentStrokeId")
        asset.parentStrokeId?.let { parent -> writer.value(parent.value) } ?: writer.nullValue()
        writer.name("formatVersion").value(asset.formatVersion.toLong())
        writer.endObject()
    }

    private fun readSnapshotJson(reader: JsonReader): DecodedPageCheckpoint {
        var formatVersion: Int? = null
        var bookId: String? = null
        var pageNumber: Int? = null
        var revision: Long? = null
        var maximumClockByDevice: Map<String, Long> = emptyMap()
        var assets: Map<StrokeId, StrokeAsset>? = null
        var activeStrokeIds: Set<StrokeId>? = null
        var appliedOperationIds: Set<OperationId>? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "formatVersion" -> formatVersion = reader.nextInt()
                "bookId" -> bookId = reader.nextString()
                "pageNumber" -> pageNumber = reader.nextInt()
                "revision" -> revision = reader.nextLong()
                "operationClockHighWaterByDevice" -> {
                    maximumClockByDevice = readOperationClockHighWaterByDevice(reader)
                }
                "assets" -> assets = readStrokeAssetMap(reader)
                "activeStrokeIds" -> activeStrokeIds = readStrokeIdSet(reader)
                "appliedOperationIds" -> appliedOperationIds = readOperationIdSet(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val version = requireNotNull(formatVersion) { "Checkpoint formatVersion is missing" }
        require(version == ANNOTATION_FORMAT_VERSION) {
            "Unsupported annotation format $version"
        }
        return DecodedPageCheckpoint(
            snapshot = AnnotationSnapshot(
                bookId = requireNotNull(bookId) { "Checkpoint bookId is missing" },
                pageNumber = requireNotNull(pageNumber) { "Checkpoint pageNumber is missing" },
                revision = requireNotNull(revision) { "Checkpoint revision is missing" },
                assets = requireNotNull(assets) { "Checkpoint assets are missing" },
                activeStrokeIds = requireNotNull(activeStrokeIds) {
                    "Checkpoint activeStrokeIds are missing"
                },
                appliedOperationIds = requireNotNull(appliedOperationIds) {
                    "Checkpoint appliedOperationIds are missing"
                },
            ),
            maximumClockByDevice = maximumClockByDevice,
        )
    }

    private fun readOperationClockHighWaterByDevice(reader: JsonReader): Map<String, Long> =
        linkedMapOf<String, Long>().apply {
            reader.beginObject()
            while (reader.hasNext()) {
                require(size < MAX_CHECKPOINT_CLOCK_DEVICES) {
                    "Checkpoint contains too many operation clock origins"
                }
                val deviceId = reader.nextName()
                require(deviceId.isNotBlank() && deviceId.length <= MAX_CHECKPOINT_IDENTIFIER_CHARS) {
                    "Checkpoint operation clock device id is invalid"
                }
                val logicalClock = reader.nextLong()
                require(logicalClock in 0 until Long.MAX_VALUE) {
                    "Checkpoint operation clock is invalid"
                }
                require(put(deviceId, logicalClock) == null) {
                    "Checkpoint contains duplicate operation clock origins"
                }
            }
            reader.endObject()
        }

    private fun readStrokeAssetMap(reader: JsonReader): Map<StrokeId, StrokeAsset> =
        linkedMapOf<StrokeId, StrokeAsset>().apply {
            reader.beginArray()
            while (reader.hasNext()) {
                val asset = readStrokeAssetJson(
                    reader = reader,
                    maximumCompactPointCount = MAX_LOCAL_CHECKPOINT_POINTS_PER_STROKE,
                )
                put(asset.id, asset)
            }
            reader.endArray()
        }

    private fun readStrokeAssetJson(
        reader: JsonReader,
        maximumCompactPointCount: Int,
    ): StrokeAsset {
        var id: String? = null
        var pageNumber: Int? = null
        var tool: String? = null
        var colorArgb: Int? = null
        var width: Float? = null
        var points: List<PagePoint>? = null
        var compactPoints: String? = null
        var losslessF32Points: String? = null
        var compactPointCount: Int? = null
        var authorId: String? = null
        var attemptNo: Int? = null
        var logicalClock: Long? = null
        var deviceId: String? = null
        var itemId: String? = null
        var publishedAtEpochMillis: Long? = null
        var bounds: PageBounds? = null
        var createdAtEpochMillis: Long? = null
        var parentStrokeId: String? = null
        var formatVersion: Int? = null

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = reader.nextString()
                "pageNumber" -> pageNumber = reader.nextInt()
                "tool" -> tool = reader.nextString()
                "colorArgb" -> colorArgb = reader.nextInt()
                "width" -> width = reader.nextDouble().toFloat()
                "points" -> points = readPagePoints(reader)
                COMPACT_POINTS_FIELD -> compactPoints = reader.nextString()
                LOSSLESS_F32_POINTS_FIELD -> losslessF32Points = reader.nextString()
                COMPACT_POINT_COUNT_FIELD -> compactPointCount = reader.nextInt()
                "authorId" -> authorId = reader.nextString()
                "attemptNo" -> attemptNo = reader.nextInt()
                "logicalClock" -> logicalClock = reader.nextLong()
                "deviceId" -> deviceId = reader.nextString()
                "itemId" -> itemId = reader.nextNullableString()
                "publishedAt" -> publishedAtEpochMillis = reader.nextNullableLong()
                "bounds" -> bounds = readPageBounds(reader)
                "createdAtEpochMillis" -> createdAtEpochMillis = reader.nextLong()
                "parentStrokeId" -> parentStrokeId = reader.nextNullableString()
                "formatVersion" -> formatVersion = reader.nextInt()
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val version = requireNotNull(formatVersion) { "Stroke formatVersion is missing" }
        require(version == ANNOTATION_FORMAT_VERSION) { "Unsupported stroke format" }
        val decodedPoints = decodePointFields(
            legacyPoints = points,
            compactPoints = compactPoints,
            losslessF32Points = losslessF32Points,
            compactPointCount = compactPointCount,
            maximumCompactPointCount = maximumCompactPointCount,
        )
        return StrokeAsset(
            id = StrokeId(requireNotNull(id) { "Stroke id is missing" }),
            pageNumber = requireNotNull(pageNumber) { "Stroke pageNumber is missing" },
            tool = StrokeTool.valueOf(requireNotNull(tool) { "Stroke tool is missing" }),
            colorArgb = requireNotNull(colorArgb) { "Stroke colorArgb is missing" },
            width = requireNotNull(width) { "Stroke width is missing" },
            points = decodedPoints,
            authorId = requireNotNull(authorId) { "Stroke authorId is missing" },
            attemptNo = requireNotNull(attemptNo) { "Stroke attemptNo is missing" },
            logicalClock = requireNotNull(logicalClock) { "Stroke logicalClock is missing" },
            deviceId = requireNotNull(deviceId) { "Stroke deviceId is missing" },
            itemId = itemId?.takeIf { it.isNotBlank() && it != "null" },
            publishedAtEpochMillis = publishedAtEpochMillis,
            bounds = requireNotNull(bounds) { "Stroke bounds are missing" },
            createdAtEpochMillis = requireNotNull(createdAtEpochMillis) {
                "Stroke createdAtEpochMillis is missing"
            },
            parentStrokeId = parentStrokeId
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?.let(::StrokeId),
            formatVersion = version,
        )
    }

    private fun readPagePoints(reader: JsonReader): List<PagePoint> = buildList {
        reader.beginArray()
        while (reader.hasNext()) {
            reader.beginArray()
            require(reader.hasNext()) { "Stroke point x is missing" }
            val x = reader.nextDouble().toFloat()
            require(reader.hasNext()) { "Stroke point y is missing" }
            val y = reader.nextDouble().toFloat()
            val pressure = if (reader.hasNext()) reader.nextDouble().toFloat() else 1f
            while (reader.hasNext()) reader.skipValue()
            reader.endArray()
            add(PagePoint(x, y, pressure))
        }
        reader.endArray()
    }

    private fun decodePointFields(
        legacyPoints: List<PagePoint>?,
        compactPoints: String?,
        losslessF32Points: String?,
        compactPointCount: Int?,
        maximumCompactPointCount: Int,
    ): List<PagePoint> = when {
        legacyPoints != null -> {
            require(
                compactPoints == null && losslessF32Points == null && compactPointCount == null
            ) {
                "Stroke contains conflicting point encodings"
            }
            legacyPoints
        }
        compactPoints != null || losslessF32Points != null || compactPointCount != null -> {
            require((compactPoints != null) xor (losslessF32Points != null)) {
                "Stroke contains conflicting compact point encodings"
            }
            val pointCount = requireNotNull(compactPointCount) {
                "Compact stroke point count is missing"
            }
            if (compactPoints != null) {
                decodeCompactPoints(
                    encoded = compactPoints,
                    pointCount = pointCount,
                    maximumPointCount = maximumCompactPointCount,
                )
            } else {
                decodeLosslessF32Points(
                    encoded = requireNotNull(losslessF32Points),
                    pointCount = pointCount,
                    maximumPointCount = maximumCompactPointCount,
                )
            }
        }
        else -> throw IllegalArgumentException("Stroke points are missing")
    }

    private fun decodeCompactPoints(
        encoded: String,
        pointCount: Int,
        maximumPointCount: Int,
    ): List<PagePoint> {
        require(maximumPointCount in 0..MAX_GENERAL_COMPACT_POINTS_PER_STROKE)
        require(pointCount in 0..maximumPointCount) {
            "Compact stroke point count is invalid"
        }
        val minimumBytes = pointCount.toLong() * MIN_COMPACT_BYTES_PER_POINT
        val maximumBytes = pointCount.toLong() * MAX_COMPACT_BYTES_PER_POINT
        val bytes = decodeBase64Bounded(encoded, maximumBytes, "Compact stroke points")
        require(bytes.size.toLong() in minimumBytes..maximumBytes) {
            "Compact stroke points exceed their declared count"
        }
        return CompactPagePointCodec.decode(
            encoded = bytes,
            pointCount = pointCount,
            maximumPointCount = maximumPointCount,
        )
    }

    private fun decodeLosslessF32Points(
        encoded: String,
        pointCount: Int,
        maximumPointCount: Int,
    ): List<PagePoint> {
        require(maximumPointCount in 0..MAX_GENERAL_COMPACT_POINTS_PER_STROKE)
        require(pointCount in 0..maximumPointCount) {
            "Lossless stroke point count is invalid"
        }
        val maximumBytes = LosslessF32PagePointCodec.maximumEncodedByteCount(pointCount).toLong()
        val bytes = decodeBase64Bounded(encoded, maximumBytes, "Lossless stroke points")
        return LosslessF32PagePointCodec.decode(
            encoded = bytes,
            pointCount = pointCount,
            maximumPointCount = maximumPointCount,
        )
    }

    private fun decodeBase64Bounded(
        encoded: String,
        maximumBytes: Long,
        label: String,
    ): ByteArray {
        val maximumBase64Chars = ((maximumBytes + 2L) / 3L) * 4L
        require(encoded.length.toLong() <= maximumBase64Chars) {
            "$label exceed their declared count"
        }
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("$label are not valid Base64", error)
        }
        require(bytes.size.toLong() <= maximumBytes) { "$label exceed their declared count" }
        return bytes
    }

    private fun readPageBounds(reader: JsonReader): PageBounds {
        reader.beginArray()
        require(reader.hasNext()) { "Stroke bound left is missing" }
        val left = reader.nextDouble().toFloat()
        require(reader.hasNext()) { "Stroke bound top is missing" }
        val top = reader.nextDouble().toFloat()
        require(reader.hasNext()) { "Stroke bound right is missing" }
        val right = reader.nextDouble().toFloat()
        require(reader.hasNext()) { "Stroke bound bottom is missing" }
        val bottom = reader.nextDouble().toFloat()
        while (reader.hasNext()) reader.skipValue()
        reader.endArray()
        return PageBounds(left, top, right, bottom)
    }

    private fun readStrokeIdSet(reader: JsonReader): Set<StrokeId> = buildSet {
        reader.beginArray()
        while (reader.hasNext()) add(StrokeId(reader.nextString()))
        reader.endArray()
    }

    private fun readOperationIdSet(reader: JsonReader): Set<OperationId> = buildSet {
        reader.beginArray()
        while (reader.hasNext()) add(OperationId(reader.nextString()))
        reader.endArray()
    }

    private fun JsonReader.nextNullableString(): String? =
        if (peek() == JsonToken.NULL) {
            nextNull()
            null
        } else {
            nextString()
        }

    private fun JsonReader.nextNullableLong(): Long? =
        if (peek() == JsonToken.NULL) {
            nextNull()
            null
        } else {
            nextLong()
        }

    private fun JSONObject.requireFormatVersion() {
        val version = getInt("formatVersion")
        require(version == ANNOTATION_FORMAT_VERSION) {
            "Unsupported annotation format $version"
        }
    }

    private fun StrokeAsset.toJson(
        pointEncoding: AnnotationPointEncoding = AnnotationPointEncoding.LEGACY_FLOAT_ARRAYS,
        maximumCompactPointCount: Int = MAX_GENERAL_COMPACT_POINTS_PER_STROKE,
    ) = JSONObject()
        .put("id", id.value)
        .put("pageNumber", pageNumber)
        .put("tool", tool.name)
        .put("colorArgb", colorArgb)
        .put("width", width.toDouble())
        .put("authorId", authorId)
        .put("attemptNo", attemptNo)
        .put("logicalClock", logicalClock)
        .put("deviceId", deviceId)
        .put("itemId", itemId ?: JSONObject.NULL)
        .put("publishedAt", publishedAtEpochMillis ?: JSONObject.NULL)
        .apply {
            val compact = encodedPointPayload(
                points = points,
                pointEncoding = pointEncoding,
                maximumCompactPointCount = maximumCompactPointCount,
            )
            if (compact != null) {
                put(compact.fieldName, compact.base64)
                put(COMPACT_POINT_COUNT_FIELD, compact.pointCount)
            } else {
                put("points", JSONArray().apply {
                    points.forEach { point ->
                        put(JSONArray()
                            .put(point.x.toDouble())
                            .put(point.y.toDouble())
                            .put(point.pressure.toDouble()))
                    }
                })
            }
        }
        .put("bounds", JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom))
        .put("createdAtEpochMillis", createdAtEpochMillis)
        .put("parentStrokeId", parentStrokeId?.value ?: JSONObject.NULL)
        .put("formatVersion", formatVersion)

    private fun JSONObject.toStrokeAsset(maximumCompactPointCount: Int): StrokeAsset {
        require(getInt("formatVersion") == ANNOTATION_FORMAT_VERSION) { "Unsupported stroke format" }
        val points = when {
            has("points") -> {
                require(
                    !has(COMPACT_POINTS_FIELD) && !has(LOSSLESS_F32_POINTS_FIELD) &&
                        !has(COMPACT_POINT_COUNT_FIELD)
                ) {
                    "Stroke contains conflicting point encodings"
                }
                val pointsJson = getJSONArray("points")
                buildList {
                    for (index in 0 until pointsJson.length()) {
                        val point = pointsJson.getJSONArray(index)
                        add(PagePoint(
                            point.getDouble(0).toFloat(),
                            point.getDouble(1).toFloat(),
                            point.optDouble(2, 1.0).toFloat(),
                        ))
                    }
                }
            }
            has(COMPACT_POINTS_FIELD) || has(LOSSLESS_F32_POINTS_FIELD) ||
                has(COMPACT_POINT_COUNT_FIELD) -> decodePointFields(
                legacyPoints = null,
                compactPoints = optionalString(COMPACT_POINTS_FIELD),
                losslessF32Points = optionalString(LOSSLESS_F32_POINTS_FIELD),
                compactPointCount = if (has(COMPACT_POINT_COUNT_FIELD)) {
                    getInt(COMPACT_POINT_COUNT_FIELD)
                } else {
                    null
                },
                maximumCompactPointCount = maximumCompactPointCount,
            )
            else -> throw IllegalArgumentException("Stroke points are missing")
        }
        val boundsJson = getJSONArray("bounds")
        return StrokeAsset(
            id = StrokeId(getString("id")),
            pageNumber = getInt("pageNumber"),
            tool = StrokeTool.valueOf(getString("tool")),
            colorArgb = getInt("colorArgb"),
            width = getDouble("width").toFloat(),
            points = points,
            authorId = getString("authorId"),
            attemptNo = getInt("attemptNo"),
            logicalClock = getLong("logicalClock"),
            deviceId = getString("deviceId"),
            itemId = optString("itemId").takeIf { it.isNotBlank() && it != "null" },
            publishedAtEpochMillis = if (isNull("publishedAt")) null else getLong("publishedAt"),
            bounds = PageBounds(
                boundsJson.getDouble(0).toFloat(),
                boundsJson.getDouble(1).toFloat(),
                boundsJson.getDouble(2).toFloat(),
                boundsJson.getDouble(3).toFloat(),
            ),
            createdAtEpochMillis = getLong("createdAtEpochMillis"),
            parentStrokeId = optString("parentStrokeId").takeIf { it.isNotBlank() && it != "null" }?.let(::StrokeId),
            formatVersion = getInt("formatVersion"),
        )
    }

    private fun AssetOperation.toJson() = JSONObject()
        .put("id", id.value)
        .put("removed", JSONArray().apply { removedStrokeIds.forEach { put(it.value) } })
        .put("added", JSONArray().apply { addedStrokeIds.forEach { put(it.value) } })
        .put("logicalClock", logicalClock)
        .put("deviceId", deviceId)

    private fun JSONObject.toOperation() = AssetOperation(
        id = OperationId(getString("id")),
        removedStrokeIds = getJSONArray("removed").toStrokeIds(),
        addedStrokeIds = getJSONArray("added").toStrokeIds(),
        logicalClock = getLong("logicalClock"),
        deviceId = getString("deviceId"),
    )

    private fun JSONArray.toStrokeAssets(
        maximumCompactPointCount: Int,
    ): List<StrokeAsset> = buildList {
        for (index in 0 until length()) {
            add(getJSONObject(index).toStrokeAsset(maximumCompactPointCount))
        }
    }

    private fun JSONArray.toStrokeIds(): Set<StrokeId> = buildSet {
        for (index in 0 until length()) add(StrokeId(getString(index)))
    }

    private fun JSONArray.toOperationIds(): Set<OperationId> = buildSet {
        for (index in 0 until length()) add(OperationId(getString(index)))
    }

    private fun String.safeFileName(): String {
        require(isNotBlank()) { "Book id cannot be blank" }
        return replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    /** Invalidates every cache backed by files that a validated restore replaces atomically. */
    @Synchronized
    internal fun resetCachedStateAfterRestore() {
        pageIndexes.clear()
        studentLayerDigests.clear()
        publishedTeacherLayerDigests.clear()
        publishedTeacherLayerGenerations.clear()
        teacherReviewPublishIntents.clear()
        teacherReviewPublishIntentsLoaded = false
        teacherReviewAuthorities.clear()
        appliedTeacherReviewReceipts.clear()
        teacherReviewStateLoaded = false
    }

    companion object {
        /** Complete checkpoints are fragmented into ordinary <=2 MiB transport documents. */
        const val MAX_STUDENT_LAYER_CHECKPOINT_BYTES = 8 * (2 * 1024 * 1024 - 32 * 1024)
        const val MAX_PUBLISHED_TEACHER_LAYER_CHECKPOINT_BYTES = 2 * 1024 * 1024 - 32 * 1024
        const val MAX_TEACHER_REVIEW_MARK_GROUP_BYTES = 512 * 1024

        @Volatile
        private var applicationInstance: PageOperationLogStore? = null

        /**
         * Returns the annotation store shared by this app process.
         *
         * Reader and LAN sync both keep an in-memory page index, so they must use the same store
         * instance when they operate on the application's durable annotation directory. The
         * public constructors remain available for tests and deliberately isolated stores.
         */
        fun get(context: Context): PageOperationLogStore =
            applicationInstance ?: synchronized(this) {
                applicationInstance ?: PageOperationLogStore(context.applicationContext).also {
                    applicationInstance = it
                }
            }

        /**
         * Drops cached page indexes after a validated restore has replaced the data root.
         * Existing holders are cleared under the store lock, so they also reload restored files.
         */
        @Synchronized
        fun resetForRestore() {
            applicationInstance?.let { current ->
                current.resetCachedStateAfterRestore()
                applicationInstance = null
            }
        }

        private const val DEFAULT_CHECKPOINT_INTERVAL = 64
        private val LOCAL_POINT_ENCODING = AnnotationPointEncoding.COMPACT_Q16_DELTA
        private const val COMPACT_POINTS_FIELD = "pointsQ16"
        private const val LOSSLESS_F32_POINTS_FIELD = "pointsF32Gzip"
        private const val COMPACT_POINT_COUNT_FIELD = "pointCount"
        private const val MIN_COMPACT_BYTES_PER_POINT = 2L
        private const val MAX_COMPACT_BYTES_PER_POINT = 10L
        internal const val MAX_CACHED_PAGE_INDEXES = 3
        internal const val MAX_CACHED_STUDENT_LAYER_DIGESTS = 32
        internal const val MAX_CACHED_PUBLISHED_TEACHER_LAYER_DIGESTS = 64
        private const val CHECKPOINT_FILE = "checkpoint.json"
        private const val LOG_FILE = "operations.log"
        private const val MAX_ENCODED_OPERATION_BYTES = 512 * 1024
        private const val STUDENT_LAYER_CHECKPOINT_FORMAT_VERSION = 1
        private const val PUBLISHED_TEACHER_LAYER_CHECKPOINT_FORMAT_VERSION = 1
        private const val STUDENT_AUTHOR_ID = "student"
        private const val TEACHER_AUTHOR_ID = "teacher"
        private const val STUDENT_CHECKPOINT_OPERATION_DEVICE_ID = "student-layer-checkpoint"
        private const val STUDENT_CHECKPOINT_OPERATION_PREFIX = "student-layer-checkpoint:"
        private const val PUBLISHED_TEACHER_CHECKPOINT_OPERATION_DEVICE_ID =
            "published-teacher-layer-checkpoint"
        private const val PUBLISHED_TEACHER_CHECKPOINT_OPERATION_PREFIX =
            "published-teacher-layer-checkpoint:"
        private const val STUDENT_DELTA_OPERATION_DEVICE_ID = "student-layer-delta"
        private const val STUDENT_DELTA_OPERATION_PREFIX = "student-layer-delta:"
        private const val PORTABLE_LAYER_DIGEST_FORMAT_VERSION = 1
        private const val STUDENT_LAYER_DIGEST_KIND = "student"
        private const val PUBLISHED_TEACHER_LAYER_DIGEST_KIND = "published-teacher"
        private const val MAX_STUDENT_LAYER_CHECKPOINT_STROKES = 8_192
        private const val MAX_PUBLISHED_TEACHER_LAYER_CHECKPOINT_STROKES = 8_192
        private const val MAX_GENERAL_COMPACT_POINTS_PER_STROKE = 32_768
        private const val MAX_LOCAL_CHECKPOINT_POINTS_PER_STROKE =
            MAX_GENERAL_COMPACT_POINTS_PER_STROKE
        private const val MAX_STUDENT_CHECKPOINT_POINTS_PER_STROKE =
            MAX_GENERAL_COMPACT_POINTS_PER_STROKE
        private const val MAX_PUBLISHED_TEACHER_CHECKPOINT_POINTS_PER_STROKE = 8_192
        private const val MAX_STUDENT_LAYER_CHECKPOINT_TOTAL_POINTS = 300_000L
        private const val MAX_PUBLISHED_TEACHER_LAYER_CHECKPOINT_TOTAL_POINTS = 120_000L
        private const val MAX_CHECKPOINT_IDENTIFIER_CHARS = 256
        private const val MAX_CHECKPOINT_ITEM_ID_CHARS = 1_024
        private const val MAX_CHECKPOINT_CLOCK_DEVICES = 4_096
        private const val MAX_STUDENT_DELTA_OPERATIONS = 8_192
        private const val MAX_STUDENT_DELTA_ATTEMPTS = 512
        private const val MAX_ATOMIC_STUDENT_DELTA_BYTES = 2 * 1024 * 1024 - 32 * 1024
        private const val TEACHER_REVIEW_PUBLISH_INTENTS_FILE = "teacher-review-publish-intents.json"
        private const val TEACHER_REVIEW_PUBLISH_INTENT_JOURNAL_VERSION = 3
        private const val TEACHER_REVIEW_PUBLICATION_ARTIFACTS_DIRECTORY =
            "teacher-review-publications"
        private const val TEACHER_REVIEW_PREPARATION_SUFFIX = ".prepare.json"
        private const val TEACHER_REVIEW_PREPARATION_VERSION = 2
        private const val MAX_TEACHER_REVIEW_PREPARATION_BYTES = 16 * 1024
        private const val MAX_TEACHER_REVIEW_PUBLISH_INTENTS = 512
        private const val MAX_TEACHER_REVIEW_PUBLISH_INTENT_JOURNAL_BYTES = 512 * 1024
        private const val TEACHER_REVIEW_STATE_FILE = "teacher-review-state-v1.json"
        // Workbook provenance is an optional JSON field, so v1 readers safely ignore it.
        private const val TEACHER_REVIEW_STATE_VERSION = 1
        private const val MAX_TEACHER_REVIEW_STATE_RECORDS = 4_096
        private const val MAX_TEACHER_REVIEW_STATE_JOURNAL_BYTES = 8 * 1024 * 1024
        private const val MAX_TEACHER_REVIEW_MARK_GROUPS = 512
        private const val MAX_TEACHER_REVIEW_MARKS_PER_GROUP = 4_096
        private val STUDENT_CHECKPOINT_ID = Regex("[0-9a-f]{64}")
    }
}
