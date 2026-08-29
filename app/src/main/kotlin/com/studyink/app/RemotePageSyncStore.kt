package com.studyink.app

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal data class StudentPageSyncRecord(
    val syncGeneration: Long,
    val pageToken: String,
    val workbookToken: String,
    val bookId: String,
    val contentSha256: String,
    val studentLayerSha256: String,
    val stateFingerprint: String,
    val workbookLabel: String,
    val pageNumber: Int,
    val attemptNos: List<Int>,
    val submittedAttemptNos: List<Int>,
    val sourceRevision: Long,
    val acknowledgedRevision: Long,
    val acknowledgedStateFingerprint: String?,
    val originDeviceHighWater: Long,
    val acknowledgedOriginCursor: Long,
    val lastChangedAtEpochMs: Long,
    val approximateBytes: Long,
    val responseToRequestTransferId: String? = null,
    val outgoingAnnotationTransferId: String? = null,
    /** Every transport fragment for [outgoingAnnotationTransferId], or the singleton legacy id. */
    val outgoingAnnotationChunkTransferIds: List<String> = emptyList(),
    val outgoingSourceRevision: Long = 0L,
    val outgoingOriginCursor: Long = 0L,
    val outgoingStateFingerprint: String? = null,
    val outgoingResultLayerSha256: String? = null,
    val outgoingSentAtEpochMs: Long? = null,
) {
    val dirty: Boolean get() = sourceRevision > acknowledgedRevision
}

internal data class TeacherPageChunkDescriptor(
    val syncGeneration: Long,
    val chunkGroupId: String,
    val responseToTransferId: String,
    val pageToken: String,
    val pageNumber: Int,
    val attemptNos: List<Int>,
    val sourceRevision: Long,
    val resultLayerSha256: String,
    val payloadSha256: String,
    val chunkCount: Int,
    val assembledPayloadSizeBytes: Int,
)

internal sealed interface TeacherPageChunkOfferResult {
    data object Partial : TeacherPageChunkOfferResult
    data class Complete(val assembledPayload: ByteArray) : TeacherPageChunkOfferResult
}

internal data class TeacherPageChunkProgress(
    val receivedChunks: Int,
    val totalChunks: Int,
    val receivedBytes: Long,
    val totalBytes: Long,
)

internal data class TeacherPageSyncRecord(
    val syncGeneration: Long,
    val pageToken: String,
    val workbookToken: String,
    val contentSha256: String,
    val studentLayerSha256: String,
    val workbookLabel: String,
    val localBookId: String?,
    val pageNumber: Int,
    val attemptNos: List<Int>,
    val submittedAttemptNos: List<Int>,
    val sourceRevision: Long,
    /** Last revision advertised by a manifest, distinct from a newer request response. */
    val manifestRevision: Long = sourceRevision,
    val manifestStudentLayerSha256: String = studentLayerSha256,
    val appliedRevision: Long,
    val appliedStudentLayerSha256: String?,
    val lastChangedAtEpochMs: Long,
    val approximateBytes: Long,
    val requestTransferId: String? = null,
    val requestCreatedAtEpochMs: Long? = null,
    val requestTransportAcknowledgedAtEpochMs: Long? = null,
    val requestedSourceRevision: Long = 0L,
    val requesterRevision: Long = 0L,
    /** Request lane is frozen at reservation so cursor movement cannot change its cooldown. */
    val requestWasAutomatic: Boolean = false,
    /** A rejected/invalid delta must recover with a full checkpoint even after process death. */
    val forceCheckpoint: Boolean = false,
    val lastCompletedRequestTransferId: String? = null,
    val lastCompletedAnnotationTransferId: String? = null,
    /** A local digest check is in flight; do not request or report this row pending meanwhile. */
    val verificationPending: Boolean = false,
) {
    val mappingRequired: Boolean get() = localBookId == null
    val pending: Boolean get() = !verificationPending && !mappingRequired &&
        (sourceRevision > appliedRevision || studentLayerSha256 != appliedStudentLayerSha256)
}

/** Pair-scoped, transport-independent proof of the student layer installed in one local page. */
internal data class TeacherStudentLayerEvidence(
    val workbookToken: String,
    val contentSha256: String,
    val localBookId: String,
    val pageNumber: Int,
    val studentLayerSha256: String,
)

private data class TeacherStudentLayerEvidenceIdentity(
    val workbookToken: String,
    val contentSha256: String,
    val localBookId: String,
    val pageNumber: Int,
)

internal data class TeacherPageSyncCursorRecord(
    val syncGeneration: Long,
    val sequence: Long,
    val pageToken: String,
    val workbookToken: String,
    val contentSha256: String,
    val pageNumber: Int,
    val attemptNo: Int?,
    val sourceRevision: Long,
    val updatedAtEpochMs: Long,
)

internal data class PendingTeacherReviewRecord(
    val intentId: String,
    val bookId: String,
    val contentSha256: String,
    /** Remote workbook identity captured when the teacher explicitly published. */
    val workbookToken: String? = null,
    /**
     * This newly published review did not have a Telegram workbook identity yet (LAN ownership or
     * a not-yet-advertised manifest window). Only this explicit state may bind once to an exact,
     * unambiguous manifest; a null token from an older journal remains permanently held.
     */
    val deferredWorkbookBinding: Boolean = false,
    /** The exact manifest high-water visible when the unresolved live publication was queued. */
    val deferredAfterManifestGeneration: Long = 0L,
    val deferredAfterManifestSequence: Long = 0L,
    val pageNumber: Int,
    val attemptNo: Int,
    val queuedAtEpochMs: Long,
    val retryCount: Int = 0,
    val inFlightSyncGeneration: Long = 0L,
    val inFlightPageToken: String? = null,
    val inFlightTransferId: String? = null,
    val inFlightSourceRevision: Long = 0L,
    val inFlightPayloadSha256: String? = null,
    val inFlightResultLayerSha256: String? = null,
    val sentAtEpochMs: Long? = null,
    val transportAcknowledgedAtEpochMs: Long? = null,
) {
    /** Local identity is required because two students may own byte-identical PDF imports. */
    val key: String get() = "$bookId:$pageNumber:$attemptNo"
    val inFlight: Boolean get() = inFlightTransferId != null
}

internal data class StudentManifestReservation(
    val syncGeneration: Long,
    val sequence: Long,
    val transferId: String,
    val createdAtEpochMs: Long,
    /** Inventory window advances only after this exact document is transport-acknowledged. */
    val windowOrdinal: Long = 0L,
    /** Fast current/recent-page manifests must not consume a 47-page inventory window. */
    val advancesInventoryWindow: Boolean = true,
)

internal data class WorkbookMappingRecord(
    val workbookToken: String,
    val localBookId: String,
    val contentSha256: String,
)

/** Pair-scoped safety latch: a deleted mapping may only be restored by explicit user choice. */
internal data class ExplicitWorkbookMappingRequirement(
    val workbookToken: String,
    val contentSha256: String,
)

internal data class AppliedTeacherReviewRecord(
    val sourceRevision: Long,
    val payloadSha256: String?,
    val resultLayerSha256: String?,
)

internal enum class TeacherManifestInstallResult { APPLIED, DUPLICATE, STALE, REGRESSION }

/** Atomic pair-scoped journal; handwriting and catalog data remain in their authoritative stores. */
internal class RemotePageSyncStore(
    file: File,
    private val beforePersistWrite: (() -> Unit)? = null,
    private val beforeChunkWrite: (() -> Unit)? = null,
) {
    private val atomicFile = AtomicFile(file)
    /** Redundant monotonic epoch survives a valid-but-corrupt main pair journal. */
    private val generationHighWaterFile = AtomicFile(File(file.parentFile, "${file.name}.generation"))
    /** Received fragments live beside (and are scoped exactly like) the pair journal. */
    private val teacherPageChunkRoot = File(file.parentFile, "${file.name}.page-chunks")
    private var pairId: String? = null

    private var studentGenerationCounter = 0L
    private var studentOpenGeneration = 0L
    /** Runtime-only: an open epoch loaded from disk is never trusted across process ownership. */
    private var recoveredOpenStudentGeneration = false
    private var studentManifestSequence = 0L
    private var studentManifestWindowOrdinal = 0L
    private var outstandingStudentManifest: StudentManifestReservation? = null
    private val studentPages = linkedMapOf<String, StudentPageSyncRecord>()

    private var teacherManifestGeneration = 0L
    private var teacherManifestSequence = 0L
    private var teacherInventoryPageCount: Int? = null
    private val teacherPages = linkedMapOf<String, TeacherPageSyncRecord>()
    private val teacherStudentLayerEvidence =
        linkedMapOf<TeacherStudentLayerEvidenceIdentity, TeacherStudentLayerEvidence>()
    private var teacherCursor: TeacherPageSyncCursorRecord? = null
    private val workbookMappings = linkedMapOf<String, WorkbookMappingRecord>()
    private val explicitWorkbookMappingRequirements =
        linkedSetOf<ExplicitWorkbookMappingRequirement>()
    private val appliedTeacherReviews = linkedMapOf<String, AppliedTeacherReviewRecord>()
    private val pendingTeacherReviews = linkedMapOf<String, PendingTeacherReviewRecord>()
    private val completedTeacherPublications = linkedMapOf<String, Long>()

    init {
        file.parentFile?.let { check(it.mkdirs() || it.isDirectory) }
        check(teacherPageChunkRoot.mkdirs() || teacherPageChunkRoot.isDirectory)
        load()
        removeExpiredTeacherPageChunks()
    }

    @Synchronized
    fun bindPair(nextPairId: String) {
        require(nextPairId.isNotBlank())
        if (pairId == nextPairId) return
        pairId = nextPairId
        clearPairState(resetGenerationCounter = false)
        persist()
    }

    @Synchronized fun currentPairId(): String? = pairId

    /**
     * A process may have died after LAN became READY but before the coordinator durably closed the
     * Telegram generation. Fence every recovered open epoch before binding it to the new runtime.
     * The redundant high-water is committed first, so another crash cannot resurrect that epoch.
     */
    @Synchronized
    fun fenceRecoveredStudentGeneration(): Boolean {
        if (!recoveredOpenStudentGeneration) return false
        val fencedHighWater = safeIncrement(studentGenerationCounter)
        persistGenerationHighWater(fencedHighWater)
        studentGenerationCounter = fencedHighWater
        studentOpenGeneration = 0L
        studentManifestSequence = 0L
        studentManifestWindowOrdinal = 0L
        outstandingStudentManifest = null
        studentPages.clear()
        appliedTeacherReviews.clear()
        recoveredOpenStudentGeneration = false
        persist()
        return true
    }

    @Synchronized
    fun resetCurrentPair() {
        val retainedCounter = safeIncrement(studentGenerationCounter)
        persistGenerationHighWater(retainedCounter)
        clearPairState(resetGenerationCounter = false)
        studentGenerationCounter = retainedCounter
        persist()
    }

    @Synchronized
    fun beginStudentGeneration(): Long {
        if (studentOpenGeneration > 0L) return studentOpenGeneration
        val nextGeneration = safeIncrement(studentGenerationCounter)
        // Commit the redundant high-water first. If the main journal write then fails, reload keeps
        // this value and the retry skips it instead of ever reusing an old generation/page token.
        persistGenerationHighWater(nextGeneration)
        studentGenerationCounter = nextGeneration
        studentOpenGeneration = studentGenerationCounter
        recoveredOpenStudentGeneration = false
        studentManifestSequence = 0L
        studentManifestWindowOrdinal = 0L
        outstandingStudentManifest = null
        studentPages.clear()
        appliedTeacherReviews.clear()
        persist()
        return studentOpenGeneration
    }

    @Synchronized
    fun closeStudentGeneration() {
        if (studentOpenGeneration == 0L && studentPages.isEmpty()) return
        studentOpenGeneration = 0L
        recoveredOpenStudentGeneration = false
        studentManifestSequence = 0L
        studentManifestWindowOrdinal = 0L
        outstandingStudentManifest = null
        studentPages.clear()
        appliedTeacherReviews.clear()
        persist()
    }

    @Synchronized fun studentGeneration(): Long = studentOpenGeneration

    @Synchronized
    fun reserveStudentManifest(
        transferId: String,
        createdAtEpochMs: Long,
        advancesInventoryWindow: Boolean = true,
    ): StudentManifestReservation {
        require(studentOpenGeneration > 0L && transferId.isNotBlank() && createdAtEpochMs >= 0L)
        outstandingStudentManifest?.let { return it }
        studentManifestSequence = safeIncrement(studentManifestSequence)
        return StudentManifestReservation(
            studentOpenGeneration,
            studentManifestSequence,
            transferId,
            createdAtEpochMs,
            studentManifestWindowOrdinal,
            advancesInventoryWindow,
        ).also {
            outstandingStudentManifest = it
            persist()
        }
    }

    @Synchronized fun outstandingStudentManifest(): StudentManifestReservation? = outstandingStudentManifest

    @Synchronized
    fun clearOutstandingStudentManifest(transferId: String): Boolean {
        if (outstandingStudentManifest?.transferId != transferId) return false
        outstandingStudentManifest = null
        persist()
        return true
    }

    @Synchronized
    fun acknowledgeOutstandingStudentManifest(transferId: String): Boolean {
        val outstanding = outstandingStudentManifest?.takeIf { it.transferId == transferId } ?: return false
        outstandingStudentManifest = null
        if (outstanding.advancesInventoryWindow) {
            studentManifestWindowOrdinal = safeIncrement(studentManifestWindowOrdinal)
        }
        persist()
        return true
    }

    /** Advances only for a semantic page/attempt change, never by reusing an operation clock. */
    @Synchronized
    fun updateStudentPage(
        expectedSyncGeneration: Long,
        pageToken: String,
        workbookToken: String,
        bookId: String,
        contentSha256: String,
        studentLayerSha256: String,
        workbookLabel: String,
        pageNumber: Int,
        attemptNos: List<Int>,
        submittedAttemptNos: List<Int>,
        originDeviceHighWater: Long,
        lastChangedAtEpochMs: Long,
        approximateBytes: Long,
    ): StudentPageSyncRecord {
        val generation = studentOpenGeneration
        require(generation > 0L && generation == expectedSyncGeneration &&
            pageToken.isNotBlank() && workbookToken.isNotBlank())
        require(bookId.isNotBlank() && pageNumber >= 0 && originDeviceHighWater >= 0L)
        val attempts = attemptNos.distinct().sorted()
        val submitted = submittedAttemptNos.distinct().sorted()
        require(attempts.all { it > 0 } && submitted.all { it in attempts })
        val fingerprint = pageStateFingerprint(studentLayerSha256, attempts, submitted)
        val previous = studentPages[pageToken]?.takeIf { it.syncGeneration == generation }
        require(previous == null || (
            previous.workbookToken == workbookToken && previous.bookId == bookId &&
                previous.contentSha256 == contentSha256 && previous.pageNumber == pageNumber
            )) { "Student page token identity changed inside one generation" }
        val changed = previous == null || previous.stateFingerprint != fingerprint
        val revision = nextPageSyncRevision(
            previous?.stateFingerprint,
            previous?.sourceRevision ?: 0L,
            fingerprint,
        )
        var acknowledgedRevision = previous?.acknowledgedRevision?.coerceAtMost(revision) ?: 0L
        val acknowledgedFingerprint = previous?.acknowledgedStateFingerprint
        var acknowledgedOriginCursor = previous?.acknowledgedOriginCursor?.coerceAtMost(originDeviceHighWater) ?: 0L
        if (acknowledgedFingerprint == fingerprint) {
            acknowledgedRevision = revision
            acknowledgedOriginCursor = originDeviceHighWater
        }
        val next = StudentPageSyncRecord(
            syncGeneration = generation,
            pageToken = pageToken,
            workbookToken = workbookToken,
            bookId = bookId,
            contentSha256 = contentSha256,
            studentLayerSha256 = studentLayerSha256,
            stateFingerprint = fingerprint,
            workbookLabel = workbookLabel,
            pageNumber = pageNumber,
            attemptNos = attempts,
            submittedAttemptNos = submitted,
            sourceRevision = revision,
            acknowledgedRevision = acknowledgedRevision,
            acknowledgedStateFingerprint = acknowledgedFingerprint,
            originDeviceHighWater = originDeviceHighWater,
            acknowledgedOriginCursor = acknowledgedOriginCursor,
            lastChangedAtEpochMs = if (changed) {
                maxOf(lastChangedAtEpochMs, previous?.lastChangedAtEpochMs ?: 0L)
            } else previous.lastChangedAtEpochMs,
            approximateBytes = approximateBytes.coerceAtLeast(0L),
            responseToRequestTransferId = previous?.responseToRequestTransferId,
            outgoingAnnotationTransferId = previous?.outgoingAnnotationTransferId,
            outgoingAnnotationChunkTransferIds = previous?.outgoingAnnotationChunkTransferIds.orEmpty(),
            outgoingSourceRevision = previous?.outgoingSourceRevision ?: 0L,
            outgoingOriginCursor = previous?.outgoingOriginCursor ?: 0L,
            outgoingStateFingerprint = previous?.outgoingStateFingerprint,
            outgoingResultLayerSha256 = previous?.outgoingResultLayerSha256,
            outgoingSentAtEpochMs = previous?.outgoingSentAtEpochMs,
        )
        if (previous != next) {
            studentPages[pageToken] = next
            persist()
        }
        return next
    }

    @Synchronized fun studentPage(pageToken: String): StudentPageSyncRecord? = studentPages[pageToken]

    @Synchronized
    fun removeStudentPage(pageToken: String): Boolean {
        if (studentPages.remove(pageToken) == null) return false
        persist()
        return true
    }

    @Synchronized
    fun studentPages(): List<StudentPageSyncRecord> = studentPages.values.sortedWith(
        compareByDescending<StudentPageSyncRecord>(StudentPageSyncRecord::lastChangedAtEpochMs)
            .thenByDescending(StudentPageSyncRecord::pageNumber),
    )

    @Synchronized
    fun markStudentAnnotationInFlight(
        pageToken: String,
        requestTransferId: String,
        annotationTransferId: String,
        annotationChunkTransferIds: List<String> = listOf(annotationTransferId),
        sourceRevision: Long,
        originCursor: Long,
        stateFingerprint: String,
        resultLayerSha256: String,
        sentAtEpochMs: Long,
    ): Boolean {
        val current = studentPages[pageToken] ?: return false
        require(sourceRevision in 1L..current.sourceRevision && originCursor >= 0L && sentAtEpochMs >= 0L)
        val chunkIds = annotationChunkTransferIds.distinct()
        require(chunkIds.isNotEmpty() && chunkIds.size == annotationChunkTransferIds.size &&
            chunkIds.size <= MAX_TEACHER_PAGE_CHUNKS && chunkIds.all(String::isNotBlank))
        studentPages[pageToken] = current.copy(
            responseToRequestTransferId = requestTransferId,
            outgoingAnnotationTransferId = annotationTransferId,
            outgoingAnnotationChunkTransferIds = chunkIds,
            outgoingSourceRevision = sourceRevision,
            outgoingOriginCursor = originCursor,
            outgoingStateFingerprint = stateFingerprint,
            outgoingResultLayerSha256 = resultLayerSha256,
            outgoingSentAtEpochMs = sentAtEpochMs,
        )
        persist()
        return true
    }

    @Synchronized
    fun abandonStudentAnnotationResponse(pageToken: String, requestTransferId: String): Boolean {
        val current = studentPages[pageToken] ?: return false
        if (current.responseToRequestTransferId != requestTransferId) return false
        studentPages[pageToken] = current.copy(
            responseToRequestTransferId = null,
            outgoingAnnotationTransferId = null,
            outgoingAnnotationChunkTransferIds = emptyList(),
            outgoingSourceRevision = 0L,
            outgoingOriginCursor = 0L,
            outgoingStateFingerprint = null,
            outgoingResultLayerSha256 = null,
            outgoingSentAtEpochMs = null,
        )
        persist()
        return true
    }

    @Synchronized
    fun resolveStudentAnnotationAck(
        syncGeneration: Long,
        pageToken: String,
        sourceTransferId: String,
        sourceRevision: Long,
        accepted: Boolean,
    ): Boolean {
        val current = studentPages[pageToken] ?: return false
        if (current.syncGeneration != syncGeneration ||
            current.outgoingAnnotationTransferId != sourceTransferId ||
            current.outgoingSourceRevision != sourceRevision
        ) return false
        var acknowledgedRevision = current.acknowledgedRevision
        var acknowledgedFingerprint = current.acknowledgedStateFingerprint
        var acknowledgedOriginCursor = current.acknowledgedOriginCursor
        if (accepted) {
            acknowledgedRevision = maxOf(acknowledgedRevision, sourceRevision)
            acknowledgedFingerprint = current.outgoingStateFingerprint
            acknowledgedOriginCursor = maxOf(acknowledgedOriginCursor, current.outgoingOriginCursor)
            if (acknowledgedFingerprint == current.stateFingerprint) {
                acknowledgedRevision = current.sourceRevision
                acknowledgedOriginCursor = current.originDeviceHighWater
            }
        }
        studentPages[pageToken] = current.copy(
            acknowledgedRevision = acknowledgedRevision.coerceAtMost(current.sourceRevision),
            acknowledgedStateFingerprint = acknowledgedFingerprint,
            acknowledgedOriginCursor = acknowledgedOriginCursor.coerceAtMost(current.originDeviceHighWater),
            responseToRequestTransferId = null,
            outgoingAnnotationTransferId = null,
            outgoingAnnotationChunkTransferIds = emptyList(),
            outgoingSourceRevision = 0L,
            outgoingOriginCursor = 0L,
            outgoingStateFingerprint = null,
            outgoingResultLayerSha256 = null,
            outgoingSentAtEpochMs = null,
        )
        persist()
        return true
    }

    /**
     * Durably accepts one out-of-order checkpoint fragment. Returning [Partial] means the caller may
     * transport-ACK this document: the bytes survive process death and a later duplicate is exact.
     */
    @Synchronized
    fun offerTeacherPageChunk(
        descriptor: TeacherPageChunkDescriptor,
        chunkIndex: Int,
        chunkSha256: String,
        decodedChunk: ByteArray,
    ): TeacherPageChunkOfferResult {
        require(descriptor.syncGeneration > 0L && descriptor.chunkGroupId.isNotBlank())
        require(descriptor.responseToTransferId.isNotBlank() && descriptor.pageToken.isNotBlank())
        require(descriptor.pageNumber > 0 && descriptor.sourceRevision >= 0L)
        require(descriptor.attemptNos.distinct().size == descriptor.attemptNos.size &&
            descriptor.attemptNos.all { it > 0 })
        require(descriptor.chunkCount in 2..MAX_TEACHER_PAGE_CHUNKS &&
            chunkIndex in 0 until descriptor.chunkCount)
        require(descriptor.assembledPayloadSizeBytes in 1..MAX_ASSEMBLED_TEACHER_PAGE_BYTES)
        require(decodedChunk.isNotEmpty() && decodedChunk.size <= MAX_TEACHER_PAGE_CHUNK_BYTES)
        require(sha256(decodedChunk) == chunkSha256)

        val groupDirectory = teacherPageChunkDirectory(descriptor.chunkGroupId)
        if (!groupDirectory.exists()) check(groupDirectory.mkdirs())
        val metadataFile = File(groupDirectory, TEACHER_PAGE_CHUNK_METADATA)
        val existingDescriptor = if (metadataFile.exists()) decodeChunkDescriptor(metadataFile) else null
        if (existingDescriptor == null) {
            if (metadataFile.exists()) error("Stored page chunk metadata is corrupt")
            removeObsoleteTeacherPageChunks(descriptor)
            writeAtomicBytes(metadataFile, encodeChunkDescriptor(descriptor).toByteArray(Charsets.UTF_8))
        } else {
            require(existingDescriptor == descriptor) { "Page chunk group identity changed" }
        }

        val target = File(groupDirectory, "$chunkIndex.chunk")
        if (target.exists()) {
            require(target.length() in 1..MAX_TEACHER_PAGE_CHUNK_BYTES.toLong()) {
                "Stored page chunk has an invalid size"
            }
            val existing = target.readBytes()
            require(sha256(existing) == chunkSha256 && existing.contentEquals(decodedChunk)) {
                "Duplicate page chunk bytes changed"
            }
        } else {
            writeAtomicBytes(target, decodedChunk)
        }
        groupDirectory.setLastModified(System.currentTimeMillis())

        var assembledSize = 0L
        repeat(descriptor.chunkCount) { index ->
            val file = File(groupDirectory, "$index.chunk")
            if (!file.exists()) return TeacherPageChunkOfferResult.Partial
            require(file.length() in 1..MAX_TEACHER_PAGE_CHUNK_BYTES.toLong())
            assembledSize += file.length()
            require(assembledSize <= descriptor.assembledPayloadSizeBytes.toLong())
        }
        require(assembledSize == descriptor.assembledPayloadSizeBytes.toLong())
        val assembled = ByteArray(descriptor.assembledPayloadSizeBytes)
        var offset = 0
        repeat(descriptor.chunkCount) { index ->
            val file = File(groupDirectory, "$index.chunk")
            file.inputStream().use { input ->
                var remaining = file.length().toInt()
                while (remaining > 0) {
                    val count = input.read(assembled, offset, remaining)
                    require(count > 0 && count <= remaining && offset + count <= assembled.size)
                    offset += count
                    remaining -= count
                }
                require(input.read() == -1)
            }
        }
        require(offset == assembled.size)
        require(sha256(assembled) == descriptor.payloadSha256) { "Assembled page digest changed" }
        return TeacherPageChunkOfferResult.Complete(assembled)
    }

    /**
     * Reports only fragments that have completed their atomic write for the exact active request.
     * Chunk payloads are deliberately not read or hashed on this UI-facing path.
     */
    @Synchronized
    fun teacherPageChunkProgress(
        responseToTransferId: String,
        pageToken: String,
    ): TeacherPageChunkProgress? {
        if (responseToTransferId.isBlank() || pageToken.isBlank()) return null
        var best: TeacherPageChunkProgress? = null
        teacherPageChunkRoot.listFiles().orEmpty().forEach { directory ->
            if (!directory.isDirectory) return@forEach
            val metadataFile = File(directory, TEACHER_PAGE_CHUNK_METADATA)
            if (!metadataFile.isFile) return@forEach
            val descriptor = decodeChunkDescriptor(metadataFile) ?: return@forEach
            if (descriptor.responseToTransferId != responseToTransferId ||
                descriptor.pageToken != pageToken ||
                descriptor.chunkCount !in 2..MAX_TEACHER_PAGE_CHUNKS ||
                descriptor.assembledPayloadSizeBytes !in 1..MAX_ASSEMBLED_TEACHER_PAGE_BYTES
            ) return@forEach

            var receivedChunks = 0
            var receivedBytes = 0L
            repeat(descriptor.chunkCount) { index ->
                val chunk = File(directory, "$index.chunk")
                val length = if (chunk.isFile) chunk.length() else 0L
                if (length in 1..MAX_TEACHER_PAGE_CHUNK_BYTES.toLong()) {
                    receivedChunks++
                    receivedBytes += length
                }
            }
            val totalBytes = descriptor.assembledPayloadSizeBytes.toLong()
            if (receivedBytes > totalBytes) return@forEach
            val progress = TeacherPageChunkProgress(
                receivedChunks = receivedChunks,
                totalChunks = descriptor.chunkCount,
                receivedBytes = receivedBytes,
                totalBytes = totalBytes,
            )
            if (best == null || progress.receivedBytes > requireNotNull(best).receivedBytes) {
                best = progress
            }
        }
        return best
    }

    @Synchronized
    fun clearTeacherPageChunkGroup(chunkGroupId: String) {
        if (chunkGroupId.isBlank()) return
        teacherPageChunkDirectory(chunkGroupId).deleteRecursively()
    }

    @Synchronized
    fun mappedLocalBookId(workbookToken: String, contentSha256: String): String? =
        workbookMappings[workbookToken]?.takeIf { it.contentSha256 == contentSha256 }?.localBookId

    @Synchronized
    fun mappedWorkbookToken(localBookId: String, contentSha256: String): String? =
        workbookMappings.values.asSequence()
            .filter { it.localBookId == localBookId && it.contentSha256 == contentSha256 }
            .map(WorkbookMappingRecord::workbookToken)
            .distinct()
            .singleOrNull()

    @Synchronized
    fun requiresExplicitWorkbookMapping(workbookToken: String, contentSha256: String): Boolean =
        ExplicitWorkbookMappingRequirement(workbookToken, contentSha256) in
            explicitWorkbookMappingRequirements

    @Synchronized
    fun unbindMissingTeacherWorkbook(workbookToken: String, contentSha256: String): Boolean {
        require(workbookToken.isNotBlank() && contentSha256.isNotBlank())
        val requirement = ExplicitWorkbookMappingRequirement(workbookToken, contentSha256)
        var changed = explicitWorkbookMappingRequirements.add(requirement)
        changed = teacherStudentLayerEvidence.entries.removeAll { (_, evidence) ->
            evidence.workbookToken == workbookToken && evidence.contentSha256 == contentSha256
        } || changed
        val mapping = workbookMappings[workbookToken]
            ?.takeIf { it.contentSha256 == contentSha256 }
        if (mapping != null) {
            workbookMappings.remove(workbookToken)
            changed = true
            teacherPages.values.filter {
                it.workbookToken == workbookToken && it.contentSha256 == contentSha256 &&
                    it.localBookId == mapping.localBookId
            }.forEach { page ->
                teacherPages[page.pageToken] = page.copy(
                    workbookLabel = "교재 연결 필요",
                    localBookId = null,
                    appliedRevision = 0L,
                    appliedStudentLayerSha256 = null,
                    requestTransferId = null,
                    requestCreatedAtEpochMs = null,
                    requestTransportAcknowledgedAtEpochMs = null,
                    requestedSourceRevision = 0L,
                    requesterRevision = 0L,
                    requestWasAutomatic = false,
                    forceCheckpoint = false,
                    lastCompletedRequestTransferId = null,
                    lastCompletedAnnotationTransferId = null,
                    verificationPending = false,
                )
            }
        }
        if (changed) persist()
        return changed
    }

    @Synchronized
    fun rememberWorkbookMapping(workbookToken: String, localBookId: String, contentSha256: String) {
        if (requiresExplicitWorkbookMapping(workbookToken, contentSha256)) return
        val next = WorkbookMappingRecord(workbookToken, localBookId, contentSha256)
        if (workbookMappings[workbookToken] == next) return
        teacherStudentLayerEvidence.entries.removeAll { (_, evidence) ->
            evidence.workbookToken == workbookToken
        }
        workbookMappings[workbookToken] = next
        persist()
    }

    /** Explicit user confirmation may migrate one local import from an obsolete remote token. */
    @Synchronized
    fun rebindTeacherWorkbook(
        workbookToken: String,
        localBookId: String,
        contentSha256: String,
        workbookLabel: String,
        localStudentLayerSha256ByPageToken: Map<String, String>,
    ): List<TeacherPageSyncRecord> {
        require(workbookToken.isNotBlank() && localBookId.isNotBlank() && contentSha256.isNotBlank())
        require(!localBookActivelyClaimedByDifferentWorkbook(workbookToken, localBookId, contentSha256)) {
            "Local workbook is still used by another remote workbook"
        }
        // Build every target row first. A missing digest must leave the mapping and its safety
        // tombstone untouched in this process, not merely on the next journal reload.
        val targetPages = teacherPages.values.filter {
            it.workbookToken == workbookToken && it.contentSha256 == contentSha256
        }
        require(targetPages.isNotEmpty()) { "Remote workbook is no longer available" }
        val rebound = targetPages.map { page ->
            val localLayerSha256 = localStudentLayerSha256ByPageToken[page.pageToken]
                ?: error("Missing local layer digest for ${page.pageToken}")
            page.copy(
                workbookLabel = workbookLabel,
                localBookId = localBookId,
                appliedRevision = if (localLayerSha256 == page.studentLayerSha256) {
                    page.sourceRevision
                } else {
                    0L
                },
                appliedStudentLayerSha256 = localLayerSha256,
                requestTransferId = null,
                requestCreatedAtEpochMs = null,
                requestTransportAcknowledgedAtEpochMs = null,
                requestedSourceRevision = 0L,
                requesterRevision = 0L,
                requestWasAutomatic = false,
                forceCheckpoint = localLayerSha256 != page.studentLayerSha256,
                lastCompletedRequestTransferId = null,
                lastCompletedAnnotationTransferId = null,
                verificationPending = false,
            )
        }
        teacherStudentLayerEvidence.entries.removeAll { (_, evidence) ->
            evidence.workbookToken == workbookToken && evidence.contentSha256 == contentSha256 ||
                evidence.localBookId == localBookId && evidence.contentSha256 == contentSha256
        }
        workbookMappings.entries.removeAll { (_, mapping) ->
            mapping.workbookToken != workbookToken && mapping.localBookId == localBookId &&
                mapping.contentSha256 == contentSha256
        }
        workbookMappings[workbookToken] = WorkbookMappingRecord(
            workbookToken,
            localBookId,
            contentSha256,
        )
        explicitWorkbookMappingRequirements.remove(
            ExplicitWorkbookMappingRequirement(workbookToken, contentSha256),
        )
        teacherPages.values.filter {
            it.workbookToken != workbookToken && it.localBookId == localBookId &&
                it.contentSha256 == contentSha256
        }.forEach { claimed ->
            teacherPages[claimed.pageToken] = claimed.copy(
                workbookLabel = "교재 연결 필요",
                localBookId = null,
                appliedRevision = 0L,
                appliedStudentLayerSha256 = null,
                requestTransferId = null,
                requestCreatedAtEpochMs = null,
                requestTransportAcknowledgedAtEpochMs = null,
                requestedSourceRevision = 0L,
                requesterRevision = 0L,
                requestWasAutomatic = false,
                forceCheckpoint = false,
                lastCompletedRequestTransferId = null,
                lastCompletedAnnotationTransferId = null,
                verificationPending = false,
            )
        }
        rebound.forEach {
            teacherPages[it.pageToken] = it
            putTeacherStudentLayerEvidence(it, requireNotNull(it.appliedStudentLayerSha256))
        }
        persist()
        return rebound
    }

    @Synchronized
    fun localBookClaimedByDifferentWorkbook(
        workbookToken: String,
        localBookId: String,
        contentSha256: String,
    ): Boolean = workbookMappings.values.any { mapping ->
        mapping.localBookId == localBookId && mapping.contentSha256 == contentSha256 &&
            mapping.workbookToken != workbookToken
    }

    @Synchronized
    fun localBookActivelyClaimedByDifferentWorkbook(
        workbookToken: String,
        localBookId: String,
        contentSha256: String,
    ): Boolean = teacherPages.values.any { page ->
        page.workbookToken != workbookToken && page.localBookId == localBookId &&
            page.contentSha256 == contentSha256
    }

    @Synchronized
    fun replaceTeacherManifest(
        syncGeneration: Long,
        sequence: Long,
        pages: List<TeacherPageSyncRecord>,
        cursor: TeacherPageSyncCursorRecord?,
        inventoryPageCount: Int?,
    ): TeacherManifestInstallResult {
        require(syncGeneration > 0L && sequence > 0L)
        if (syncGeneration == teacherManifestGeneration && sequence == teacherManifestSequence) {
            return TeacherManifestInstallResult.DUPLICATE
        }
        if (isTeacherManifestStale(
                teacherManifestGeneration,
                teacherManifestSequence,
                syncGeneration,
                sequence,
            )
        ) return TeacherManifestInstallResult.STALE
        if (syncGeneration == teacherManifestGeneration && pages.any { incoming ->
                val previous = teacherPages[incoming.pageToken] ?: return@any false
                !hasSameTeacherPageIdentity(previous, incoming) || isTeacherPageRegression(
                    previous.manifestRevision,
                    previous.manifestStudentLayerSha256,
                    incoming.manifestRevision,
                    incoming.manifestStudentLayerSha256,
                ) || incoming.sourceRevision == previous.sourceRevision &&
                    incoming.studentLayerSha256 != previous.studentLayerSha256
            }
        ) return TeacherManifestInstallResult.REGRESSION

        val generationChanged = syncGeneration != teacherManifestGeneration
        val previousPages = if (!generationChanged) teacherPages.toMap() else emptyMap()
        if (generationChanged) clearAllTeacherPageChunks()
        teacherPages.clear()
        // A bounded manifest is a page upsert batch within one generation. Retaining omitted rows
        // keeps an in-flight request/correlation alive when another newly changed page pushes it
        // outside the 512-row transport window. PAGE_UNAVAILABLE explicitly removes a stale row.
        teacherPages.putAll(previousPages)
        pages.forEach { incoming ->
            require(incoming.syncGeneration == syncGeneration)
            val previous = previousPages[incoming.pageToken]
            teacherPages[incoming.pageToken] = mergeTeacherPageFromManifest(previous, incoming)
        }
        teacherManifestGeneration = syncGeneration
        teacherManifestSequence = sequence
        teacherInventoryPageCount = inventoryPageCount
        teacherCursor = cursor
        persist()
        return TeacherManifestInstallResult.APPLIED
    }

    @Synchronized
    fun removeTeacherPage(pageToken: String): Boolean {
        if (teacherPages.remove(pageToken) == null) return false
        if (teacherCursor?.pageToken == pageToken) teacherCursor = null
        persist()
        return true
    }

    @Synchronized fun teacherManifestGeneration(): Long = teacherManifestGeneration
    @Synchronized fun teacherManifestSequence(): Long = teacherManifestSequence

    @Synchronized
    fun teacherInventoryComplete(): Boolean = teacherInventoryPageCount?.let { expected ->
        teacherPages.size == expected
    } ?: false

    @Synchronized fun teacherExpectedInventoryPageCount(): Int? = teacherInventoryPageCount
    @Synchronized fun teacherDiscoveredInventoryPageCount(): Int = teacherPages.size

    /** LAN READY invalidates generation ownership but retains exact, generation-neutral page proof. */
    @Synchronized
    fun clearTeacherManifestPagesForLan() {
        clearAllTeacherPageChunks()
        var changed = false
        teacherPages.values
            .mapNotNull { page -> page.teacherStudentLayerEvidenceIdentity()?.let { it to page } }
            .groupBy({ it.first }, { it.second })
            .forEach { (identity, pages) ->
                val appliedSha256 = pages.asSequence()
                    .mapNotNull(TeacherPageSyncRecord::appliedStudentLayerSha256)
                    .distinct()
                    .singleOrNull()
                if (appliedSha256 == null) {
                    changed = teacherStudentLayerEvidence.remove(identity) != null || changed
                } else {
                    changed = putTeacherStudentLayerEvidence(
                        TeacherStudentLayerEvidence(
                            workbookToken = identity.workbookToken,
                            contentSha256 = identity.contentSha256,
                            localBookId = identity.localBookId,
                            pageNumber = identity.pageNumber,
                            studentLayerSha256 = appliedSha256,
                        ),
                    ) || changed
                }
            }
        if (teacherPages.isNotEmpty() || teacherCursor != null || teacherInventoryPageCount != null) {
            teacherPages.clear()
            teacherCursor = null
            teacherInventoryPageCount = null
            changed = true
        }
        if (changed) persist()
    }

    @Synchronized
    fun teacherPages(): List<TeacherPageSyncRecord> = teacherPages.values.sortedWith(
        compareByDescending<TeacherPageSyncRecord>(TeacherPageSyncRecord::lastChangedAtEpochMs)
            .thenByDescending(TeacherPageSyncRecord::pageNumber),
    )

    @Synchronized fun pendingTeacherPages(): List<TeacherPageSyncRecord> = teacherPages().filter { it.pending || it.mappingRequired }
    @Synchronized fun teacherPage(pageToken: String): TeacherPageSyncRecord? = teacherPages[pageToken]
    /** Never guesses between duplicate imports or stale remote identities. */
    @Synchronized
    fun teacherPageForLocalTarget(localBookId: String, pageNumber: Int): TeacherPageSyncRecord? =
        teacherPages.values.filter {
            it.syncGeneration == teacherManifestGeneration &&
                it.localBookId == localBookId && it.pageNumber == pageNumber
        }.singleOrNull()
    @Synchronized fun teacherCursor(): TeacherPageSyncCursorRecord? = teacherCursor

    @Synchronized
    fun teacherStudentLayerEvidence(
        workbookToken: String,
        contentSha256: String,
        localBookId: String?,
        pageNumber: Int,
    ): TeacherStudentLayerEvidence? {
        if (workbookToken.isBlank() || contentSha256.isBlank() || localBookId.isNullOrBlank() || pageNumber < 0) {
            return null
        }
        return teacherStudentLayerEvidence[
            TeacherStudentLayerEvidenceIdentity(workbookToken, contentSha256, localBookId, pageNumber)
        ]
    }

    /** Marks only rows with a known local digest and returns every still-unverified row for retry. */
    @Synchronized
    fun markTeacherPagesForVerification(): List<TeacherPageSyncRecord> {
        var changed = false
        teacherPages.values.toList().forEach { current ->
            if (current.localBookId == null || current.appliedStudentLayerSha256 == null ||
                current.verificationPending
            ) return@forEach
            teacherPages[current.pageToken] = current.copy(verificationPending = true)
            changed = true
        }
        if (changed) persist()
        return teacherPages().filter(TeacherPageSyncRecord::verificationPending)
    }

    /** Accepts a local digest only while the exact manifest row being checked is still current. */
    @Synchronized
    fun verifyTeacherPage(
        pageToken: String,
        expectedSyncGeneration: Long,
        expectedSourceRevision: Long,
        expectedStudentLayerSha256: String,
        observedLocalStudentLayerSha256: String?,
    ): Boolean {
        val current = teacherPages[pageToken] ?: return false
        if (!current.verificationPending || current.syncGeneration != expectedSyncGeneration ||
            current.sourceRevision != expectedSourceRevision ||
            current.studentLayerSha256 != expectedStudentLayerSha256
        ) return false
        val observed = observedLocalStudentLayerSha256?.takeIf(String::isNotBlank)
        val next = if (observed == null) {
            current.copy(
                appliedRevision = 0L,
                appliedStudentLayerSha256 = null,
                forceCheckpoint = true,
                verificationPending = false,
            )
        } else {
            current.copy(
                appliedRevision = if (observed == expectedStudentLayerSha256) current.sourceRevision else 0L,
                appliedStudentLayerSha256 = observed,
                verificationPending = false,
            )
        }
        teacherPages[pageToken] = next
        val evidenceIdentity = current.teacherStudentLayerEvidenceIdentity()
        if (observed == null) {
            evidenceIdentity?.let(teacherStudentLayerEvidence::remove)
        } else {
            putTeacherStudentLayerEvidence(next, observed)
        }
        persist()
        return true
    }

    @Synchronized
    fun reserveTeacherRequest(
        pageToken: String,
        transferId: String,
        createdAtEpochMs: Long,
        requestedSourceRevision: Long,
        requesterRevision: Long,
        requestWasAutomatic: Boolean,
    ): TeacherPageSyncRecord? {
        val current = teacherPages[pageToken] ?: return null
        if (current.mappingRequired || current.verificationPending || current.requestTransferId != null) {
            return current
        }
        return current.copy(
            requestTransferId = transferId,
            requestCreatedAtEpochMs = createdAtEpochMs,
            requestTransportAcknowledgedAtEpochMs = null,
            requestedSourceRevision = requestedSourceRevision,
            requesterRevision = requesterRevision,
            requestWasAutomatic = requestWasAutomatic,
        ).also {
            teacherPages[pageToken] = it
            persist()
        }
    }

    @Synchronized
    fun markTeacherRequestTransportAcknowledged(
        pageToken: String,
        transferId: String,
        observedAtEpochMs: Long,
    ): TeacherPageSyncRecord? {
        val current = teacherPages[pageToken] ?: return null
        if (current.requestTransferId != transferId) return null
        if (current.requestTransportAcknowledgedAtEpochMs != null) return current
        return current.copy(
            requestTransportAcknowledgedAtEpochMs = observedAtEpochMs.coerceAtLeast(0L),
        ).also {
            teacherPages[pageToken] = it
            persist()
        }
    }

    @Synchronized
    fun clearTeacherRequest(
        pageToken: String,
        transferId: String? = null,
        forceCheckpoint: Boolean = false,
    ): Boolean {
        val current = teacherPages[pageToken] ?: return false
        if (transferId != null && current.requestTransferId != transferId) return false
        val next = current.copy(
            requestTransferId = null,
            requestCreatedAtEpochMs = null,
            requestTransportAcknowledgedAtEpochMs = null,
            requestedSourceRevision = 0L,
            requesterRevision = 0L,
            requestWasAutomatic = false,
            forceCheckpoint = current.forceCheckpoint || forceCheckpoint,
        )
        if (next == current) return false
        teacherPages[pageToken] = next
        persist()
        return true
    }

    @Synchronized
    fun recordTeacherPageApplied(
        pageToken: String,
        sourceRevision: Long,
        resultLayerSha256: String,
        observedAttemptNos: List<Int>,
        requestTransferId: String,
        annotationTransferId: String,
    ): Boolean {
        val current = teacherPages[pageToken] ?: return false
        require(sourceRevision > 0L)
        val next = current.copy(
            sourceRevision = maxOf(current.sourceRevision, sourceRevision),
            studentLayerSha256 = if (sourceRevision >= current.sourceRevision) resultLayerSha256 else current.studentLayerSha256,
            attemptNos = (current.attemptNos + observedAttemptNos).distinct().sorted(),
            appliedRevision = maxOf(current.appliedRevision, sourceRevision),
            appliedStudentLayerSha256 = resultLayerSha256,
            requestTransferId = null,
            requestCreatedAtEpochMs = null,
            requestTransportAcknowledgedAtEpochMs = null,
            requestedSourceRevision = 0L,
            requesterRevision = 0L,
            requestWasAutomatic = false,
            forceCheckpoint = false,
            lastCompletedRequestTransferId = requestTransferId,
            lastCompletedAnnotationTransferId = annotationTransferId,
            verificationPending = false,
        )
        val evidenceChanged = putTeacherStudentLayerEvidence(next, resultLayerSha256)
        if (next == current && !evidenceChanged) return false
        if (next != current) teacherPages[pageToken] = next
        persist()
        return true
    }

    private fun teacherReviewKey(pageToken: String, attemptNo: Int): String = "$pageToken:$attemptNo"
    @Synchronized fun appliedTeacherReview(pageToken: String, attemptNo: Int): AppliedTeacherReviewRecord? =
        appliedTeacherReviews[teacherReviewKey(pageToken, attemptNo)]

    @Synchronized fun appliedTeacherReviewRevision(pageToken: String, attemptNo: Int): Long =
        appliedTeacherReview(pageToken, attemptNo)?.sourceRevision ?: 0L

    @Synchronized
    fun recordTeacherReviewApplied(
        pageToken: String,
        attemptNo: Int,
        sourceRevision: Long,
        payloadSha256: String,
        resultLayerSha256: String,
    ): Boolean {
        val key = teacherReviewKey(pageToken, attemptNo)
        val current = appliedTeacherReviews[key]
        if (current != null && sourceRevision <= current.sourceRevision) return false
        appliedTeacherReviews[key] = AppliedTeacherReviewRecord(
            sourceRevision,
            payloadSha256,
            resultLayerSha256,
        )
        persist()
        return true
    }

    @Synchronized
    fun queueTeacherReview(value: PendingTeacherReviewRecord) {
        if (teacherPublicationCompletionKey(value) in completedTeacherPublications) return
        val current = pendingTeacherReviews[value.key]
        if (current?.intentId == value.intentId) {
            // The periodic journal scan can win the few milliseconds between durable promotion and
            // the live pair-scoped bus callback. Permit only that one-way provenance upgrade; a
            // later generic scan can never erase a captured token/deferred binding or alter an
            // in-flight transfer.
            val upgradesHeldLivePublication = !current.inFlight &&
                current.workbookToken == null && !current.deferredWorkbookBinding &&
                (value.workbookToken != null || value.deferredWorkbookBinding) &&
                current.bookId == value.bookId && current.contentSha256 == value.contentSha256 &&
                current.pageNumber == value.pageNumber && current.attemptNo == value.attemptNo
            if (upgradesHeldLivePublication) {
                pendingTeacherReviews[value.key] = value
                persist()
            }
            return
        }
        pendingTeacherReviews[value.key] = value
        persist()
    }

    @Synchronized
    fun pendingTeacherReviews(): List<PendingTeacherReviewRecord> = pendingTeacherReviews.values.sortedWith(
        compareByDescending<PendingTeacherReviewRecord>(PendingTeacherReviewRecord::queuedAtEpochMs)
            .thenByDescending(PendingTeacherReviewRecord::pageNumber),
    )

    @Synchronized
    fun bindDeferredTeacherReviewWorkbook(
        key: String,
        intentId: String,
        workbookToken: String,
    ): PendingTeacherReviewRecord? {
        require(intentId.isNotBlank() && workbookToken.isNotBlank())
        val current = pendingTeacherReviews[key] ?: return null
        if (current.intentId != intentId || current.inFlight || current.workbookToken != null ||
            !current.deferredWorkbookBinding
        ) return null
        return current.copy(
            workbookToken = workbookToken,
            deferredWorkbookBinding = false,
        ).also {
            pendingTeacherReviews[key] = it
            persist()
        }
    }

    @Synchronized
    fun reservePendingTeacherReview(
        key: String,
        syncGeneration: Long,
        pageToken: String,
        transferId: String,
        sourceRevision: Long,
        payloadSha256: String,
        resultLayerSha256: String,
        sentAtEpochMs: Long,
    ): PendingTeacherReviewRecord? {
        val current = pendingTeacherReviews[key] ?: return null
        if (current.inFlight) return current
        return current.copy(
            inFlightSyncGeneration = syncGeneration,
            inFlightPageToken = pageToken,
            inFlightTransferId = transferId,
            inFlightSourceRevision = sourceRevision,
            inFlightPayloadSha256 = payloadSha256,
            inFlightResultLayerSha256 = resultLayerSha256,
            sentAtEpochMs = sentAtEpochMs,
            transportAcknowledgedAtEpochMs = null,
        ).also {
            pendingTeacherReviews[key] = it
            persist()
        }
    }

    @Synchronized
    fun markPendingTeacherReviewTransportAcknowledged(
        key: String,
        transferId: String,
        observedAtEpochMs: Long,
    ): PendingTeacherReviewRecord? {
        val current = pendingTeacherReviews[key] ?: return null
        if (current.inFlightTransferId != transferId) return null
        if (current.transportAcknowledgedAtEpochMs != null) return current
        return current.copy(
            transportAcknowledgedAtEpochMs = observedAtEpochMs.coerceAtLeast(0L),
        ).also {
            pendingTeacherReviews[key] = it
            persist()
        }
    }

    @Synchronized
    fun resolvePendingTeacherReview(
        syncGeneration: Long,
        pageToken: String,
        sourceTransferId: String,
        sourceRevision: Long,
        accepted: Boolean,
        retryQueuedAtEpochMs: Long,
    ): Boolean {
        val current = pendingTeacherReviews.values.firstOrNull {
            it.inFlightSyncGeneration == syncGeneration && it.inFlightPageToken == pageToken &&
                it.inFlightTransferId == sourceTransferId && it.inFlightSourceRevision == sourceRevision
        } ?: return false
        if (accepted) {
            completedTeacherPublications[teacherPublicationCompletionKey(current)] = retryQueuedAtEpochMs
            trimCompletedTeacherPublications()
            pendingTeacherReviews.remove(current.key)
        } else {
            pendingTeacherReviews[current.key] = current.clearedForRetry(retryQueuedAtEpochMs)
        }
        persist()
        return true
    }

    @Synchronized
    fun expirePendingTeacherReview(key: String, nowEpochMs: Long): Boolean {
        val current = pendingTeacherReviews[key] ?: return false
        if (!current.inFlight) return false
        pendingTeacherReviews[key] = current.clearedForRetry(nowEpochMs)
        persist()
        return true
    }

    @Synchronized
    fun removePendingTeacherReview(key: String): Boolean {
        if (pendingTeacherReviews.remove(key) == null) return false
        persist()
        return true
    }

    @Synchronized
    fun clearPendingTeacherReviews() {
        if (pendingTeacherReviews.isEmpty()) return
        pendingTeacherReviews.clear()
        persist()
    }

    @Synchronized
    fun completeTeacherReviewFromLan(
        bookId: String,
        pageNumber: Int,
        attemptNo: Int,
        publicationId: String,
        completedAtEpochMs: Long,
    ): Boolean {
        require(publicationId.isNotBlank() && completedAtEpochMs >= 0L)
        val key = "$bookId:$pageNumber:$attemptNo"
        val pending = pendingTeacherReviews[key]
        if (pending != null && pending.intentId != publicationId) return false
        val completionKey = teacherPublicationCompletionKey(bookId, pageNumber, attemptNo, publicationId)
        val alreadyCompleted = completionKey in completedTeacherPublications
        if (pending == null && alreadyCompleted) return true
        completedTeacherPublications[completionKey] = completedAtEpochMs
        trimCompletedTeacherPublications()
        if (pending != null) pendingTeacherReviews.remove(key)
        persist()
        return true
    }

    @Synchronized
    fun isTeacherPublicationCompleted(
        bookId: String,
        pageNumber: Int,
        attemptNo: Int,
        publicationId: String,
    ): Boolean = teacherPublicationCompletionKey(
        bookId,
        pageNumber,
        attemptNo,
        publicationId,
    ) in completedTeacherPublications

    private fun teacherPublicationCompletionKey(value: PendingTeacherReviewRecord): String =
        teacherPublicationCompletionKey(
            value.bookId,
            value.pageNumber,
            value.attemptNo,
            value.intentId,
        )

    private fun teacherPublicationCompletionKey(
        bookId: String,
        pageNumber: Int,
        attemptNo: Int,
        publicationId: String,
    ): String = MessageDigest.getInstance("SHA-256")
        .digest(
            JSONObject()
                .put("bookId", bookId)
                .put("pageNumber", pageNumber)
                .put("attemptNo", attemptNo)
                .put("publicationId", publicationId)
                .toString()
                .toByteArray(Charsets.UTF_8),
        )
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun trimCompletedTeacherPublications() {
        while (completedTeacherPublications.size > MAX_COMPLETED_TEACHER_PUBLICATIONS) {
            completedTeacherPublications.remove(completedTeacherPublications.keys.first())
        }
    }

    private fun PendingTeacherReviewRecord.clearedForRetry(nowEpochMs: Long) = copy(
        queuedAtEpochMs = maxOf(queuedAtEpochMs, nowEpochMs),
        retryCount = safeIncrementInt(retryCount),
        inFlightSyncGeneration = 0L,
        inFlightPageToken = null,
        inFlightTransferId = null,
        inFlightSourceRevision = 0L,
        inFlightPayloadSha256 = null,
        inFlightResultLayerSha256 = null,
        sentAtEpochMs = null,
        transportAcknowledgedAtEpochMs = null,
    )

    private fun putTeacherStudentLayerEvidence(
        page: TeacherPageSyncRecord,
        studentLayerSha256: String,
    ): Boolean {
        val localBookId = page.localBookId ?: return false
        if (studentLayerSha256.isBlank() || page.pageNumber < 0) return false
        return putTeacherStudentLayerEvidence(
            TeacherStudentLayerEvidence(
                workbookToken = page.workbookToken,
                contentSha256 = page.contentSha256,
                localBookId = localBookId,
                pageNumber = page.pageNumber,
                studentLayerSha256 = studentLayerSha256,
            ),
        )
    }

    private fun putTeacherStudentLayerEvidence(value: TeacherStudentLayerEvidence): Boolean {
        val identity = value.identity()
        if (teacherStudentLayerEvidence[identity] == value) return false
        teacherStudentLayerEvidence[identity] = value
        return true
    }

    private fun clearPairState(resetGenerationCounter: Boolean, clearChunkFiles: Boolean = true) {
        if (clearChunkFiles) clearAllTeacherPageChunks()
        if (resetGenerationCounter) studentGenerationCounter = 0L
        studentOpenGeneration = 0L
        recoveredOpenStudentGeneration = false
        studentManifestSequence = 0L
        studentManifestWindowOrdinal = 0L
        outstandingStudentManifest = null
        studentPages.clear()
        teacherManifestGeneration = 0L
        teacherManifestSequence = 0L
        teacherInventoryPageCount = null
        teacherPages.clear()
        teacherStudentLayerEvidence.clear()
        teacherCursor = null
        workbookMappings.clear()
        explicitWorkbookMappingRequirements.clear()
        appliedTeacherReviews.clear()
        pendingTeacherReviews.clear()
        completedTeacherPublications.clear()
    }

    private fun removeObsoleteTeacherPageChunks(descriptor: TeacherPageChunkDescriptor) {
        teacherPageChunkRoot.listFiles().orEmpty().forEach { directory ->
            if (!directory.isDirectory || directory == teacherPageChunkDirectory(descriptor.chunkGroupId)) return@forEach
            val old = decodeChunkDescriptor(File(directory, TEACHER_PAGE_CHUNK_METADATA))
            val expired = System.currentTimeMillis() - directory.lastModified() > TEACHER_PAGE_CHUNK_TTL_MS
            val samePageDifferentGroup = old?.let {
                it.syncGeneration == descriptor.syncGeneration && it.pageToken == descriptor.pageToken
            } == true
            if (old == null || expired || samePageDifferentGroup) directory.deleteRecursively()
        }
    }

    private fun removeExpiredTeacherPageChunks() {
        val now = System.currentTimeMillis()
        teacherPageChunkRoot.listFiles().orEmpty().forEach { directory ->
            if (!directory.isDirectory || now - directory.lastModified() > TEACHER_PAGE_CHUNK_TTL_MS) {
                directory.deleteRecursively()
            }
        }
    }

    private fun clearAllTeacherPageChunks() {
        teacherPageChunkRoot.listFiles().orEmpty().forEach(File::deleteRecursively)
    }

    private fun teacherPageChunkDirectory(chunkGroupId: String): File = File(
        teacherPageChunkRoot,
        sha256(chunkGroupId.toByteArray(Charsets.UTF_8)),
    )

    private fun encodeChunkDescriptor(value: TeacherPageChunkDescriptor): String = JSONObject()
        .put("generation", value.syncGeneration)
        .put("chunkGroupId", value.chunkGroupId)
        .put("responseToTransferId", value.responseToTransferId)
        .put("pageToken", value.pageToken)
        .put("pageNumber", value.pageNumber)
        .put("attemptNos", value.attemptNos.toJsonArray())
        .put("sourceRevision", value.sourceRevision)
        .put("resultLayerSha256", value.resultLayerSha256)
        .put("payloadSha256", value.payloadSha256)
        .put("chunkCount", value.chunkCount)
        .put("assembledPayloadSizeBytes", value.assembledPayloadSizeBytes)
        .toString()

    private fun decodeChunkDescriptor(file: File): TeacherPageChunkDescriptor? = runCatching {
        require(file.length() in 1..MAX_TEACHER_PAGE_CHUNK_METADATA_BYTES.toLong())
        val value = JSONObject(file.readText(Charsets.UTF_8))
        TeacherPageChunkDescriptor(
            syncGeneration = value.getLong("generation"),
            chunkGroupId = value.getString("chunkGroupId"),
            responseToTransferId = value.getString("responseToTransferId"),
            pageToken = value.getString("pageToken"),
            pageNumber = value.getInt("pageNumber"),
            attemptNos = value.getJSONArray("attemptNos").toIntList(),
            sourceRevision = value.getLong("sourceRevision"),
            resultLayerSha256 = value.getString("resultLayerSha256"),
            payloadSha256 = value.getString("payloadSha256"),
            chunkCount = value.getInt("chunkCount"),
            assembledPayloadSizeBytes = value.getInt("assembledPayloadSizeBytes"),
        )
    }.getOrNull()

    private fun writeAtomicBytes(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        beforeChunkWrite?.invoke()
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            output.flush()
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            runCatching { atomic.failWrite(output) }
            throw error
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun load() {
        val durableGenerationHighWater = readGenerationHighWater()
        resetInMemoryState()
        studentGenerationCounter = durableGenerationHighWater
        runCatching {
            val root = JSONObject(atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
            val version = root.optInt("version")
            if (version !in MIN_SUPPORTED_VERSION..VERSION) return@runCatching
            pairId = root.optNullableString("pairId")
            val journalGenerationCounter = root.optLong("studentGenerationCounter").coerceAtLeast(0L)
            studentGenerationCounter = maxOf(durableGenerationHighWater, journalGenerationCounter)
            studentOpenGeneration = root.optLong("studentOpenGeneration").coerceAtLeast(0L)
            studentManifestSequence = root.optLong("studentManifestSequence").coerceAtLeast(0L)
            studentManifestWindowOrdinal = root.optLong("studentManifestWindowOrdinal").coerceAtLeast(0L)
            outstandingStudentManifest = root.optJSONObject("outstandingStudentManifest")?.let(::decodeManifest)
            teacherManifestGeneration = root.optLong("teacherManifestGeneration").coerceAtLeast(0L)
            teacherManifestSequence = root.optLong("teacherManifestSequence").coerceAtLeast(0L)
            teacherInventoryPageCount = root.optNullableInt("teacherInventoryPageCount")
            root.optJSONArray("studentPages")?.forEachObject { decodeStudent(it)?.let { row -> studentPages[row.pageToken] = row } }
            root.optJSONArray("teacherPages")?.forEachObject { decodeTeacher(it)?.let { row -> teacherPages[row.pageToken] = row } }
            if (version >= 5) {
                val conflictingEvidence = linkedSetOf<TeacherStudentLayerEvidenceIdentity>()
                root.optJSONArray("teacherStudentLayerEvidence")?.forEachObject { encoded ->
                    decodeTeacherStudentLayerEvidence(encoded)?.let { evidence ->
                        val identity = evidence.identity()
                        if (identity in conflictingEvidence) return@let
                        val previous = teacherStudentLayerEvidence[identity]
                        if (previous == null || previous == evidence) {
                            teacherStudentLayerEvidence[identity] = evidence
                        } else {
                            teacherStudentLayerEvidence.remove(identity)
                            conflictingEvidence += identity
                        }
                    }
                }
            }
            teacherCursor = root.optJSONObject("teacherCursor")?.let(::decodeCursor)
            root.optJSONArray("workbookMappings")?.forEachObject { decodeMapping(it)?.let { row -> workbookMappings[row.workbookToken] = row } }
            root.optJSONArray("explicitWorkbookMappingRequirements")?.forEachObject { value ->
                decodeExplicitMappingRequirement(value)?.let(explicitWorkbookMappingRequirements::add)
            }
            root.optJSONObject("appliedTeacherReviewRevisions")?.let { values ->
                values.keys().forEach { key ->
                    val raw = values.opt(key)
                    val record = when (raw) {
                        is Number -> raw.toLong().takeIf { it >= 0L }?.let {
                            AppliedTeacherReviewRecord(it, null, null)
                        }
                        is JSONObject -> runCatching {
                            AppliedTeacherReviewRecord(
                                sourceRevision = raw.getLong("sourceRevision"),
                                payloadSha256 = raw.optNullableString("payloadSha256"),
                                resultLayerSha256 = raw.optNullableString("resultLayerSha256"),
                            )
                        }.getOrNull()
                        else -> null
                    }
                    record?.let { appliedTeacherReviews[key] = it }
                }
            }
            root.optJSONArray("pendingTeacherReviews")?.forEachObject { decodePendingReview(it)?.let { row -> pendingTeacherReviews[row.key] = row } }
            root.optJSONObject("completedTeacherPublications")?.takeIf { version >= 3 }?.let { values ->
                values.keys().forEach { id ->
                    values.optLong(id, -1L).takeIf { it >= 0L }?.let {
                        completedTeacherPublications[id] = it
                    }
                }
                trimCompletedTeacherPublications()
            }
            if (shouldDiscardRecoveredStudentGeneration(
                    durableGenerationHighWater,
                    journalGenerationCounter,
                    studentOpenGeneration,
                )
            ) {
                // The redundant high-water won a crash race against the main journal. Never reuse
                // the older open generation/page tokens; the next begin allocates high-water + 1.
                studentOpenGeneration = 0L
                studentManifestSequence = 0L
                studentManifestWindowOrdinal = 0L
                outstandingStudentManifest = null
                studentPages.clear()
            } else if (studentOpenGeneration == 0L) {
                studentManifestSequence = 0L
                studentManifestWindowOrdinal = 0L
                outstandingStudentManifest = null
                studentPages.clear()
            } else {
                studentPages.entries.removeAll { it.value.syncGeneration != studentOpenGeneration }
                if (outstandingStudentManifest?.syncGeneration != studentOpenGeneration) {
                    outstandingStudentManifest = null
                }
            }
            recoveredOpenStudentGeneration = studentOpenGeneration > 0L
            if (journalGenerationCounter > durableGenerationHighWater) {
                runCatching { persistGenerationHighWater(journalGenerationCounter) }
            }
        }.onFailure {
            resetInMemoryState()
            studentGenerationCounter = durableGenerationHighWater
        }
    }

    private fun readGenerationHighWater(): Long = runCatching {
        generationHighWaterFile.openRead().bufferedReader(Charsets.US_ASCII).use { reader ->
            reader.readText().trim().toLong().coerceAtLeast(0L)
        }
    }.getOrDefault(0L)

    private fun persistGenerationHighWater(value: Long) {
        require(value >= 0L)
        val output = generationHighWaterFile.startWrite()
        try {
            output.write(value.toString().toByteArray(Charsets.US_ASCII))
            output.flush()
            output.fd.sync()
            generationHighWaterFile.finishWrite(output)
        } catch (error: Throwable) {
            runCatching { generationHighWaterFile.failWrite(output) }
            throw error
        }
    }

    private fun persist() {
        val root = JSONObject()
            .put("version", VERSION).put("pairId", pairId ?: JSONObject.NULL)
            .put("studentGenerationCounter", studentGenerationCounter)
            .put("studentOpenGeneration", studentOpenGeneration)
            .put("studentManifestSequence", studentManifestSequence)
            .put("studentManifestWindowOrdinal", studentManifestWindowOrdinal)
            .put("outstandingStudentManifest", outstandingStudentManifest?.let(::encode) ?: JSONObject.NULL)
            .put("studentPages", JSONArray().apply { studentPages.values.forEach { put(encode(it)) } })
            .put("teacherManifestGeneration", teacherManifestGeneration)
            .put("teacherManifestSequence", teacherManifestSequence)
            .put("teacherInventoryPageCount", teacherInventoryPageCount ?: JSONObject.NULL)
            .put("teacherPages", JSONArray().apply { teacherPages.values.forEach { put(encode(it)) } })
            .put("teacherStudentLayerEvidence", JSONArray().apply {
                teacherStudentLayerEvidence.values.forEach { put(encode(it)) }
            })
            .put("teacherCursor", teacherCursor?.let(::encode) ?: JSONObject.NULL)
            .put("workbookMappings", JSONArray().apply { workbookMappings.values.forEach { put(encode(it)) } })
            .put("explicitWorkbookMappingRequirements", JSONArray().apply {
                explicitWorkbookMappingRequirements.forEach { requirement ->
                    put(JSONObject()
                        .put("workbookToken", requirement.workbookToken)
                        .put("contentSha256", requirement.contentSha256))
                }
            })
            .put("appliedTeacherReviewRevisions", JSONObject().apply {
                appliedTeacherReviews.forEach { (key, value) ->
                    put(key, JSONObject()
                        .put("sourceRevision", value.sourceRevision)
                        .put("payloadSha256", value.payloadSha256 ?: JSONObject.NULL)
                        .put("resultLayerSha256", value.resultLayerSha256 ?: JSONObject.NULL))
                }
            })
            .put("pendingTeacherReviews", JSONArray().apply { pendingTeacherReviews.values.forEach { put(encode(it)) } })
            .put("completedTeacherPublications", JSONObject().apply {
                completedTeacherPublications.forEach(::put)
            })
        val output = try {
            beforePersistWrite?.invoke()
            atomicFile.startWrite()
        } catch (error: Throwable) {
            load()
            throw error
        }
        try {
            output.write(root.toString().toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            runCatching { atomicFile.failWrite(output) }
            load()
            throw error
        }
    }

    /** Restores the exact empty/default state before a durable journal is decoded. */
    private fun resetInMemoryState() {
        pairId = null
        studentGenerationCounter = 0L
        recoveredOpenStudentGeneration = false
        clearPairState(resetGenerationCounter = false, clearChunkFiles = false)
    }

    private fun encode(v: StudentManifestReservation) = JSONObject()
        .put("generation", v.syncGeneration).put("sequence", v.sequence)
        .put("transferId", v.transferId).put("createdAt", v.createdAtEpochMs)
        .put("windowOrdinal", v.windowOrdinal)
        .put("advancesInventoryWindow", v.advancesInventoryWindow)

    private fun encode(v: StudentPageSyncRecord) = JSONObject()
        .put("generation", v.syncGeneration).put("pageToken", v.pageToken)
        .put("workbookToken", v.workbookToken).put("bookId", v.bookId)
        .put("contentSha256", v.contentSha256).put("studentLayerSha256", v.studentLayerSha256)
        .put("stateFingerprint", v.stateFingerprint).put("workbookLabel", v.workbookLabel)
        .put("pageNumber", v.pageNumber).put("attemptNos", v.attemptNos.toJsonArray())
        .put("submittedAttemptNos", v.submittedAttemptNos.toJsonArray())
        .put("sourceRevision", v.sourceRevision).put("acknowledgedRevision", v.acknowledgedRevision)
        .put("acknowledgedStateFingerprint", v.acknowledgedStateFingerprint ?: JSONObject.NULL)
        .put("originDeviceHighWater", v.originDeviceHighWater)
        .put("acknowledgedOriginCursor", v.acknowledgedOriginCursor)
        .put("lastChangedAt", v.lastChangedAtEpochMs).put("approximateBytes", v.approximateBytes)
        .put("responseToRequestTransferId", v.responseToRequestTransferId ?: JSONObject.NULL)
        .put("outgoingAnnotationTransferId", v.outgoingAnnotationTransferId ?: JSONObject.NULL)
        .put("outgoingAnnotationChunkTransferIds", v.outgoingAnnotationChunkTransferIds.toStringJsonArray())
        .put("outgoingSourceRevision", v.outgoingSourceRevision)
        .put("outgoingOriginCursor", v.outgoingOriginCursor)
        .put("outgoingStateFingerprint", v.outgoingStateFingerprint ?: JSONObject.NULL)
        .put("outgoingResultLayerSha256", v.outgoingResultLayerSha256 ?: JSONObject.NULL)
        .put("outgoingSentAt", v.outgoingSentAtEpochMs ?: JSONObject.NULL)

    private fun encode(v: TeacherPageSyncRecord) = JSONObject()
        .put("generation", v.syncGeneration).put("pageToken", v.pageToken)
        .put("workbookToken", v.workbookToken).put("contentSha256", v.contentSha256)
        .put("studentLayerSha256", v.studentLayerSha256).put("workbookLabel", v.workbookLabel)
        .put("localBookId", v.localBookId ?: JSONObject.NULL).put("pageNumber", v.pageNumber)
        .put("attemptNos", v.attemptNos.toJsonArray()).put("submittedAttemptNos", v.submittedAttemptNos.toJsonArray())
        .put("sourceRevision", v.sourceRevision).put("manifestRevision", v.manifestRevision)
        .put("manifestStudentLayerSha256", v.manifestStudentLayerSha256)
        .put("appliedRevision", v.appliedRevision)
        .put("appliedStudentLayerSha256", v.appliedStudentLayerSha256 ?: JSONObject.NULL)
        .put("lastChangedAt", v.lastChangedAtEpochMs).put("approximateBytes", v.approximateBytes)
        .put("requestTransferId", v.requestTransferId ?: JSONObject.NULL)
        .put("requestCreatedAt", v.requestCreatedAtEpochMs ?: JSONObject.NULL)
        .put("requestTransportAcknowledgedAt", v.requestTransportAcknowledgedAtEpochMs ?: JSONObject.NULL)
        .put("requestedSourceRevision", v.requestedSourceRevision).put("requesterRevision", v.requesterRevision)
        .put("requestWasAutomatic", v.requestWasAutomatic)
        .put("forceCheckpoint", v.forceCheckpoint)
        .put("lastCompletedRequestTransferId", v.lastCompletedRequestTransferId ?: JSONObject.NULL)
        .put("lastCompletedAnnotationTransferId", v.lastCompletedAnnotationTransferId ?: JSONObject.NULL)
        .put("verificationPending", v.verificationPending)

    private fun encode(v: TeacherStudentLayerEvidence) = JSONObject()
        .put("workbookToken", v.workbookToken)
        .put("contentSha256", v.contentSha256)
        .put("localBookId", v.localBookId)
        .put("pageNumber", v.pageNumber)
        .put("studentLayerSha256", v.studentLayerSha256)

    private fun encode(v: TeacherPageSyncCursorRecord) = JSONObject()
        .put("generation", v.syncGeneration).put("sequence", v.sequence)
        .put("pageToken", v.pageToken).put("workbookToken", v.workbookToken)
        .put("contentSha256", v.contentSha256).put("pageNumber", v.pageNumber)
        .put("attemptNo", v.attemptNo ?: JSONObject.NULL).put("sourceRevision", v.sourceRevision)
        .put("updatedAt", v.updatedAtEpochMs)

    private fun encode(v: WorkbookMappingRecord) = JSONObject()
        .put("workbookToken", v.workbookToken).put("localBookId", v.localBookId)
        .put("contentSha256", v.contentSha256)

    private fun encode(v: PendingTeacherReviewRecord) = JSONObject()
        .put("intentId", v.intentId).put("bookId", v.bookId).put("contentSha256", v.contentSha256)
        .put("workbookToken", v.workbookToken ?: JSONObject.NULL)
        .put("deferredWorkbookBinding", v.deferredWorkbookBinding)
        .put("deferredAfterManifestGeneration", v.deferredAfterManifestGeneration)
        .put("deferredAfterManifestSequence", v.deferredAfterManifestSequence)
        .put("pageNumber", v.pageNumber).put("attemptNo", v.attemptNo)
        .put("queuedAt", v.queuedAtEpochMs).put("retryCount", v.retryCount)
        .put("inFlightGeneration", v.inFlightSyncGeneration)
        .put("inFlightPageToken", v.inFlightPageToken ?: JSONObject.NULL)
        .put("inFlightTransferId", v.inFlightTransferId ?: JSONObject.NULL)
        .put("inFlightSourceRevision", v.inFlightSourceRevision)
        .put("inFlightPayloadSha256", v.inFlightPayloadSha256 ?: JSONObject.NULL)
        .put("inFlightResultLayerSha256", v.inFlightResultLayerSha256 ?: JSONObject.NULL)
        .put("sentAt", v.sentAtEpochMs ?: JSONObject.NULL)
        .put("transportAcknowledgedAt", v.transportAcknowledgedAtEpochMs ?: JSONObject.NULL)

    private fun decodeManifest(v: JSONObject) = StudentManifestReservation(
        v.getLong("generation"), v.getLong("sequence"), v.getString("transferId"), v.getLong("createdAt"),
        v.optLong("windowOrdinal").coerceAtLeast(0L),
        v.optBoolean("advancesInventoryWindow", true),
    )

    private fun decodeStudent(v: JSONObject): StudentPageSyncRecord? = runCatching { StudentPageSyncRecord(
        syncGeneration = v.getLong("generation"), pageToken = v.getString("pageToken"),
        workbookToken = v.getString("workbookToken"), bookId = v.getString("bookId"),
        contentSha256 = v.getString("contentSha256"), studentLayerSha256 = v.getString("studentLayerSha256"),
        stateFingerprint = v.getString("stateFingerprint"), workbookLabel = v.getString("workbookLabel"),
        pageNumber = v.getInt("pageNumber"), attemptNos = v.optJSONArray("attemptNos").toIntList(),
        submittedAttemptNos = v.optJSONArray("submittedAttemptNos").toIntList(),
        sourceRevision = v.getLong("sourceRevision"), acknowledgedRevision = v.optLong("acknowledgedRevision"),
        acknowledgedStateFingerprint = v.optNullableString("acknowledgedStateFingerprint"),
        originDeviceHighWater = v.optLong("originDeviceHighWater"), acknowledgedOriginCursor = v.optLong("acknowledgedOriginCursor"),
        lastChangedAtEpochMs = v.getLong("lastChangedAt"), approximateBytes = v.optLong("approximateBytes"),
        responseToRequestTransferId = v.optNullableString("responseToRequestTransferId"),
        outgoingAnnotationTransferId = v.optNullableString("outgoingAnnotationTransferId"),
        outgoingAnnotationChunkTransferIds = v.optJSONArray("outgoingAnnotationChunkTransferIds")
            ?.toStringList().orEmpty().ifEmpty {
                v.optNullableString("outgoingAnnotationTransferId")?.let(::listOf).orEmpty()
            },
        outgoingSourceRevision = v.optLong("outgoingSourceRevision"), outgoingOriginCursor = v.optLong("outgoingOriginCursor"),
        outgoingStateFingerprint = v.optNullableString("outgoingStateFingerprint"),
        outgoingResultLayerSha256 = v.optNullableString("outgoingResultLayerSha256"),
        outgoingSentAtEpochMs = v.optNullableLong("outgoingSentAt"),
    ) }.getOrNull()

    private fun decodeTeacher(v: JSONObject): TeacherPageSyncRecord? = runCatching { TeacherPageSyncRecord(
        syncGeneration = v.getLong("generation"), pageToken = v.getString("pageToken"),
        workbookToken = v.getString("workbookToken"), contentSha256 = v.getString("contentSha256"),
        studentLayerSha256 = v.getString("studentLayerSha256"), workbookLabel = v.getString("workbookLabel"),
        localBookId = v.optNullableString("localBookId"), pageNumber = v.getInt("pageNumber"),
        attemptNos = v.optJSONArray("attemptNos").toIntList(), submittedAttemptNos = v.optJSONArray("submittedAttemptNos").toIntList(),
        sourceRevision = v.getLong("sourceRevision"),
        manifestRevision = v.optLong("manifestRevision", v.getLong("sourceRevision")),
        manifestStudentLayerSha256 = v.optString("manifestStudentLayerSha256", v.getString("studentLayerSha256")),
        appliedRevision = v.optLong("appliedRevision"),
        appliedStudentLayerSha256 = v.optNullableString("appliedStudentLayerSha256"),
        lastChangedAtEpochMs = v.getLong("lastChangedAt"), approximateBytes = v.optLong("approximateBytes"),
        requestTransferId = v.optNullableString("requestTransferId"), requestCreatedAtEpochMs = v.optNullableLong("requestCreatedAt"),
        requestTransportAcknowledgedAtEpochMs = v.optNullableLong("requestTransportAcknowledgedAt"),
        requestedSourceRevision = v.optLong("requestedSourceRevision"), requesterRevision = v.optLong("requesterRevision"),
        requestWasAutomatic = v.optBoolean("requestWasAutomatic"),
        forceCheckpoint = v.optBoolean("forceCheckpoint"),
        lastCompletedRequestTransferId = v.optNullableString("lastCompletedRequestTransferId"),
        lastCompletedAnnotationTransferId = v.optNullableString("lastCompletedAnnotationTransferId"),
        verificationPending = v.optBoolean("verificationPending"),
    ) }.getOrNull()

    private fun decodeTeacherStudentLayerEvidence(
        v: JSONObject,
    ): TeacherStudentLayerEvidence? = runCatching {
        TeacherStudentLayerEvidence(
            workbookToken = v.getString("workbookToken"),
            contentSha256 = v.getString("contentSha256"),
            localBookId = v.getString("localBookId"),
            pageNumber = v.getInt("pageNumber"),
            studentLayerSha256 = v.getString("studentLayerSha256"),
        ).also { evidence ->
            require(evidence.workbookToken.isNotBlank())
            require(evidence.contentSha256.isNotBlank())
            require(evidence.localBookId.isNotBlank())
            require(evidence.pageNumber >= 0)
            require(evidence.studentLayerSha256.isNotBlank())
        }
    }.getOrNull()

    private fun decodeCursor(v: JSONObject): TeacherPageSyncCursorRecord? = runCatching { TeacherPageSyncCursorRecord(
        v.getLong("generation"), v.getLong("sequence"), v.getString("pageToken"), v.getString("workbookToken"),
        v.getString("contentSha256"), v.getInt("pageNumber"), if (v.isNull("attemptNo")) null else v.getInt("attemptNo"),
        v.getLong("sourceRevision"), v.getLong("updatedAt"),
    ) }.getOrNull()

    private fun decodeMapping(v: JSONObject): WorkbookMappingRecord? = runCatching { WorkbookMappingRecord(
        v.getString("workbookToken"), v.getString("localBookId"), v.getString("contentSha256"),
    ) }.getOrNull()

    private fun decodeExplicitMappingRequirement(
        v: JSONObject,
    ): ExplicitWorkbookMappingRequirement? = runCatching {
        ExplicitWorkbookMappingRequirement(
            v.getString("workbookToken"),
            v.getString("contentSha256"),
        ).also {
            require(it.workbookToken.isNotBlank() && it.contentSha256.isNotBlank())
        }
    }.getOrNull()

    private fun decodePendingReview(v: JSONObject): PendingTeacherReviewRecord? = runCatching { PendingTeacherReviewRecord(
        intentId = v.getString("intentId"), bookId = v.getString("bookId"), contentSha256 = v.getString("contentSha256"),
        workbookToken = v.optNullableString("workbookToken"),
        deferredWorkbookBinding = if (v.has("deferredWorkbookBinding")) {
            v.optBoolean("deferredWorkbookBinding")
        } else {
            v.optBoolean("deferredLanWorkbookBinding")
        },
        deferredAfterManifestGeneration = v.optLong("deferredAfterManifestGeneration").coerceAtLeast(0L),
        deferredAfterManifestSequence = v.optLong("deferredAfterManifestSequence").coerceAtLeast(0L),
        pageNumber = v.getInt("pageNumber"), attemptNo = v.getInt("attemptNo"), queuedAtEpochMs = v.getLong("queuedAt"),
        retryCount = v.optInt("retryCount"), inFlightSyncGeneration = v.optLong("inFlightGeneration"),
        inFlightPageToken = v.optNullableString("inFlightPageToken"), inFlightTransferId = v.optNullableString("inFlightTransferId"),
        inFlightSourceRevision = v.optLong("inFlightSourceRevision"), inFlightPayloadSha256 = v.optNullableString("inFlightPayloadSha256"),
        inFlightResultLayerSha256 = v.optNullableString("inFlightResultLayerSha256"), sentAtEpochMs = v.optNullableLong("sentAt"),
        transportAcknowledgedAtEpochMs = v.optNullableLong("transportAcknowledgedAt"),
    ) }.getOrNull()

    private fun JSONArray?.toIntList(): List<Int> = if (this == null) emptyList() else buildList {
        for (index in 0 until length()) add(getInt(index))
    }
    private fun JSONArray.toStringList(): List<String> = buildList {
        for (index in 0 until length()) add(getString(index))
    }
    private fun JSONArray.forEachObject(block: (JSONObject) -> Unit) { for (index in 0 until length()) block(getJSONObject(index)) }
    private fun List<Int>.toJsonArray() = JSONArray().apply { forEach(::put) }
    private fun List<String>.toStringJsonArray() = JSONArray().apply { forEach(::put) }
    private fun JSONObject.optNullableString(name: String): String? = if (isNull(name)) null else optString(name).takeIf(String::isNotBlank)
    private fun JSONObject.optNullableLong(name: String): Long? = if (isNull(name)) null else getLong(name)
    private fun JSONObject.optNullableInt(name: String): Int? = if (isNull(name)) null else getInt(name)

    private companion object {
        const val MIN_SUPPORTED_VERSION = 2
        const val VERSION = 5
        const val MAX_COMPLETED_TEACHER_PUBLICATIONS = 1_024
        const val MAX_TEACHER_PAGE_CHUNKS = 8
        const val MAX_TEACHER_PAGE_CHUNK_BYTES = 2 * 1024 * 1024 - 32 * 1024
        const val MAX_ASSEMBLED_TEACHER_PAGE_BYTES =
            MAX_TEACHER_PAGE_CHUNKS * MAX_TEACHER_PAGE_CHUNK_BYTES
        const val TEACHER_PAGE_CHUNK_TTL_MS = 7 * 24 * 60 * 60 * 1_000L
        const val TEACHER_PAGE_CHUNK_METADATA = "metadata.json"
        const val MAX_TEACHER_PAGE_CHUNK_METADATA_BYTES = 16 * 1024
    }
}

internal fun pageStateFingerprint(
    studentLayerSha256: String,
    attemptNos: List<Int>,
    submittedAttemptNos: List<Int>,
): String {
    val material = buildString {
        append(studentLayerSha256).append('|')
        attemptNos.forEach { append(it).append(',') }
        append('|')
        submittedAttemptNos.forEach { append(it).append(',') }
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(material.toByteArray(Charsets.US_ASCII))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}

internal fun nextPageSyncRevision(
    previousFingerprint: String?,
    previousRevision: Long,
    currentFingerprint: String,
): Long = when {
    previousFingerprint == null -> 1L
    previousFingerprint == currentFingerprint -> previousRevision
    else -> safeIncrement(previousRevision)
}

internal fun isTeacherManifestStale(
    currentGeneration: Long,
    currentSequence: Long,
    incomingGeneration: Long,
    incomingSequence: Long,
): Boolean = incomingGeneration < currentGeneration ||
    incomingGeneration == currentGeneration && incomingSequence <= currentSequence

internal fun isTeacherPageRegression(
    previousRevision: Long,
    previousLayerSha256: String,
    incomingRevision: Long,
    incomingLayerSha256: String,
): Boolean = incomingRevision < previousRevision ||
    incomingRevision == previousRevision && incomingLayerSha256 != previousLayerSha256

/**
 * A request response may legitimately observe a revision newer than a manifest that was already in
 * flight. Keep that applied response as the effective page while advancing the independent
 * manifest high-water, so the delayed manifest is neither poison nor a state rollback.
 */
internal fun mergeTeacherPageFromManifest(
    previous: TeacherPageSyncRecord?,
    incoming: TeacherPageSyncRecord,
): TeacherPageSyncRecord {
    if (previous == null) return incoming
    require(hasSameTeacherPageIdentity(previous, incoming))
    val effectiveSourceRevision = maxOf(incoming.sourceRevision, previous.sourceRevision)
    val effectiveStudentLayerSha256 = if (previous.sourceRevision > incoming.sourceRevision) {
        previous.studentLayerSha256
    } else {
        incoming.studentLayerSha256
    }
    val alreadyHasEffectiveState = incoming.appliedStudentLayerSha256 == effectiveStudentLayerSha256 ||
        previous.appliedStudentLayerSha256 == effectiveStudentLayerSha256
    val appliedRevision = if (alreadyHasEffectiveState) effectiveSourceRevision else {
        maxOf(incoming.appliedRevision, previous.appliedRevision).coerceAtMost(effectiveSourceRevision)
    }
    return incoming.copy(
        studentLayerSha256 = effectiveStudentLayerSha256,
        attemptNos = (incoming.attemptNos + previous.attemptNos).distinct().sorted(),
        submittedAttemptNos = (incoming.submittedAttemptNos + previous.submittedAttemptNos).distinct().sorted(),
        sourceRevision = effectiveSourceRevision,
        appliedRevision = appliedRevision,
        appliedStudentLayerSha256 = if (alreadyHasEffectiveState) effectiveStudentLayerSha256 else {
            incoming.appliedStudentLayerSha256 ?: previous.appliedStudentLayerSha256
        },
        requestTransferId = previous.requestTransferId,
        requestCreatedAtEpochMs = previous.requestCreatedAtEpochMs,
        requestTransportAcknowledgedAtEpochMs = previous.requestTransportAcknowledgedAtEpochMs,
        requestedSourceRevision = previous.requestedSourceRevision,
        requesterRevision = previous.requesterRevision,
        requestWasAutomatic = previous.requestWasAutomatic,
        forceCheckpoint = previous.forceCheckpoint,
        lastCompletedRequestTransferId = previous.lastCompletedRequestTransferId,
        lastCompletedAnnotationTransferId = previous.lastCompletedAnnotationTransferId,
        verificationPending = incoming.verificationPending || previous.verificationPending,
    )
}

internal fun hasSameTeacherPageIdentity(
    previous: TeacherPageSyncRecord,
    incoming: TeacherPageSyncRecord,
): Boolean = previous.pageToken == incoming.pageToken &&
    previous.syncGeneration == incoming.syncGeneration &&
    previous.workbookToken == incoming.workbookToken &&
    previous.contentSha256 == incoming.contentSha256 &&
    previous.pageNumber == incoming.pageNumber &&
    previous.localBookId == incoming.localBookId

private fun TeacherStudentLayerEvidence.identity() = TeacherStudentLayerEvidenceIdentity(
    workbookToken = workbookToken,
    contentSha256 = contentSha256,
    localBookId = localBookId,
    pageNumber = pageNumber,
)

private fun TeacherPageSyncRecord.teacherStudentLayerEvidenceIdentity(): TeacherStudentLayerEvidenceIdentity? {
    val localBookId = localBookId ?: return null
    if (workbookToken.isBlank() || contentSha256.isBlank() || localBookId.isBlank() || pageNumber < 0) {
        return null
    }
    return TeacherStudentLayerEvidenceIdentity(
        workbookToken = workbookToken,
        contentSha256 = contentSha256,
        localBookId = localBookId,
        pageNumber = pageNumber,
    )
}

internal fun shouldDiscardRecoveredStudentGeneration(
    durableHighWater: Long,
    journalGenerationCounter: Long,
    openGeneration: Long,
): Boolean {
    require(durableHighWater >= 0L && journalGenerationCounter >= 0L && openGeneration >= 0L)
    if (openGeneration == 0L) return false
    val effectiveCounter = maxOf(durableHighWater, journalGenerationCounter)
    return openGeneration != journalGenerationCounter || openGeneration != effectiveCounter
}

private fun safeIncrement(value: Long): Long {
    check(value < Long.MAX_VALUE) { "Page synchronization counter is exhausted" }
    return value + 1L
}

private fun safeIncrementInt(value: Int): Int = if (value == Int.MAX_VALUE) value else value + 1
