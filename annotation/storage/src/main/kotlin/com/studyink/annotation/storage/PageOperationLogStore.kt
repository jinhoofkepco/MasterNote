package com.studyink.annotation.storage

import android.content.Context
import com.studyink.annotation.engine.AnnotationChange
import com.studyink.core.model.ANNOTATION_FORMAT_VERSION
import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.AssetOperation
import com.studyink.core.model.OperationId
import com.studyink.core.model.PageBounds
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeId
import com.studyink.core.model.StrokeTool
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class CorruptAnnotationDataException(
    message: String,
    val quarantinedFile: File,
    cause: Throwable,
) : IOException(message, cause)

data class OperationCursor(val deviceId: String, val logicalClock: Long)

/**
 * Append-only annotation persistence partitioned by (book, page). A checkpoint is only a loading
 * accelerator; durable operations remain the source of truth and are retained for offline peers.
 */
class PageOperationLogStore(
    private val rootDirectory: File,
    checkpointInterval: Int = DEFAULT_CHECKPOINT_INTERVAL,
) {
    private val checkpointInterval = checkpointInterval.coerceAtLeast(1)
    private val pageIndexes = mutableMapOf<PageKey, PageIndex>()

    constructor(context: Context, checkpointInterval: Int = DEFAULT_CHECKPOINT_INTERVAL) : this(
        File(context.filesDir, "masternote/annotation-pages"),
        checkpointInterval,
    )

    init {
        check(rootDirectory.mkdirs() || rootDirectory.isDirectory) { "Cannot create annotation directory" }
    }

    @Synchronized
    fun loadPage(bookId: String, pageNumber: Int): AnnotationSnapshot =
        pageIndex(bookId, pageNumber).snapshot

    private fun readPageIndex(bookId: String, pageNumber: Int): PageIndex {
        val directory = pageDirectory(bookId, pageNumber)
        val checkpointFile = File(directory, CHECKPOINT_FILE)
        var snapshot = if (checkpointFile.exists()) {
            decodeSafely(checkpointFile) { decodeSnapshot(JSONObject(it)) }
        } else {
            AnnotationSnapshot.empty(bookId, pageNumber)
        }
        validatePartition(snapshot, bookId, pageNumber)

        val records = mutableListOf<IndexedOperation>()
        val byDevice = mutableMapOf<String, MutableList<IndexedOperation>>()
        val maximumClocks = mutableMapOf<String, Long>()
        val logFile = File(directory, LOG_FILE)
        if (!logFile.exists()) return PageIndex(snapshot, records, byDevice, maximumClocks)
        try {
            logFile.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEachIndexed { index, line ->
                    if (line.isBlank()) return@forEachIndexed
                    val record = try {
                        decodeRecord(JSONObject(line))
                    } catch (error: Throwable) {
                        throw IOException("Invalid operation at line ${index + 1}", error)
                    }
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
        } catch (error: Throwable) {
            quarantineAndThrow(logFile, error)
        }
        byDevice.values.forEach { operations -> operations.sortBy { it.record.operation.logicalClock } }
        validatePartition(snapshot, bookId, pageNumber)
        return PageIndex(snapshot, records, byDevice, maximumClocks)
    }

    @Synchronized
    fun append(change: AnnotationChange) {
        val snapshot = change.snapshot
        val index = pageIndex(snapshot.bookId, snapshot.pageNumber)
        if (change.operation.id in index.snapshot.appliedOperationIds) return
        val directory = pageDirectory(snapshot.bookId, snapshot.pageNumber)
        val record = StoredOperationRecord(
            snapshot.bookId, snapshot.pageNumber, snapshot.revision, change.operation, change.addedAssets
        )
        val line = encodeRecord(record).toString()
        val logFile = File(directory, LOG_FILE)
        FileOutputStream(logFile, true).use { output ->
            output.write(line.toByteArray(Charsets.UTF_8))
            output.write('\n'.code)
            output.flush()
            output.fd.sync()
        }
        index.add(record, line.toByteArray(Charsets.UTF_8), snapshot)
        if (snapshot.revision % checkpointInterval == 0L) writeCheckpoint(snapshot)
    }

    @Synchronized
    fun writeCheckpoint(snapshot: AnnotationSnapshot) {
        val directory = pageDirectory(snapshot.bookId, snapshot.pageNumber)
        atomicWrite(File(directory, CHECKPOINT_FILE), encodeSnapshot(snapshot).toString())
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

    @Synchronized
    fun encodedOperationsAfter(bookId: String, pageNumber: Int, revision: Long): List<ByteArray> {
        val records = pageIndex(bookId, pageNumber).records
        val start = records.firstIndexAfterRevision(revision)
        return records.subList(start, records.size).map { it.encoded.copyOf() }
    }

    @Synchronized
    fun encodedOperationsAfter(
        bookId: String,
        pageNumber: Int,
        originDeviceId: String,
        logicalClock: Long,
        includeTeacherDrafts: Boolean = true,
    ): List<ByteArray> {
        val index = pageIndex(bookId, pageNumber)
        val records = index.byDevice[originDeviceId].orEmpty()
        val start = records.firstIndexAfterClock(logicalClock)
        return records.subList(start, records.size)
            .asSequence()
            .filter { includeTeacherDrafts || it.record.isPublishable(index.snapshot) }
            .map { it.encoded.copyOf() }
            .toList()
    }

    @Synchronized
    fun maxOperationClock(bookId: String, pageNumber: Int, originDeviceId: String): Long {
        return pageIndex(bookId, pageNumber).maximumClockByDevice[originDeviceId] ?: 0L
    }

    fun operationCursor(bytes: ByteArray): OperationCursor {
        require(bytes.size <= MAX_ENCODED_OPERATION_BYTES) { "Operation is too large" }
        val operation = decodeRecord(JSONObject(bytes.toString(Charsets.UTF_8))).operation
        return OperationCursor(operation.deviceId, operation.logicalClock)
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
        val localBytes = encodeRecord(localRecord).toString().toByteArray(Charsets.UTF_8)
        val file = File(pageDirectory(bookId, pageNumber), LOG_FILE)
        FileOutputStream(file, true).use { output ->
            output.write(localBytes)
            output.write('\n'.code)
            output.flush()
            output.fd.sync()
        }
        val updated = apply(current, localRecord)
        index.add(localRecord, localBytes, updated)
        if (updated.revision % checkpointInterval == 0L) writeCheckpoint(updated)
        return updated.revision
    }

    private fun pageIndex(bookId: String, pageNumber: Int): PageIndex =
        pageIndexes.getOrPut(PageKey(bookId, pageNumber)) { readPageIndex(bookId, pageNumber) }

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

    private fun decodeSafely(file: File, decode: (String) -> AnnotationSnapshot): AnnotationSnapshot = try {
        decode(file.readText(Charsets.UTF_8))
    } catch (error: Throwable) {
        quarantineAndThrow(file, error)
    }

    private fun quarantineAndThrow(file: File, error: Throwable): Nothing {
        pageIndexes.entries.removeAll { (key, _) ->
            val directory = File(rootDirectory, "${key.bookId.safeFileName()}/pages/${key.pageNumber}")
            file.absolutePath.startsWith(directory.absolutePath)
        }
        val quarantined = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
        if (!file.renameTo(quarantined)) {
            throw CorruptAnnotationDataException("손상된 필기 데이터를 격리하지 못했습니다.", file, error)
        }
        throw CorruptAnnotationDataException("손상된 필기 데이터를 격리했습니다.", quarantined, error)
    }

    private fun atomicWrite(target: File, text: String) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temporary, false).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        if (target.exists() && !target.delete()) throw IOException("Cannot replace checkpoint")
        if (!temporary.renameTo(target)) throw IOException("Cannot commit checkpoint")
    }

    private data class StoredOperationRecord(
        val bookId: String,
        val pageNumber: Int,
        val revision: Long,
        val operation: AssetOperation,
        val addedAssets: List<StrokeAsset>,
    )

    private data class PageKey(val bookId: String, val pageNumber: Int)

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

    private fun encodeRecord(record: StoredOperationRecord) = JSONObject()
        .put("formatVersion", ANNOTATION_FORMAT_VERSION)
        .put("bookId", record.bookId)
        .put("pageNumber", record.pageNumber)
        .put("revision", record.revision)
        .put("operation", record.operation.toJson())
        .put("addedAssets", JSONArray().apply { record.addedAssets.forEach { put(it.toJson()) } })

    private fun decodeRecord(root: JSONObject): StoredOperationRecord {
        root.requireFormatVersion()
        return StoredOperationRecord(
            bookId = root.getString("bookId"),
            pageNumber = root.getInt("pageNumber"),
            revision = root.getLong("revision"),
            operation = root.getJSONObject("operation").toOperation(),
            addedAssets = root.getJSONArray("addedAssets").toStrokeAssets(),
        )
    }

    private fun encodeSnapshot(snapshot: AnnotationSnapshot) = JSONObject()
        .put("formatVersion", ANNOTATION_FORMAT_VERSION)
        .put("bookId", snapshot.bookId)
        .put("pageNumber", snapshot.pageNumber)
        .put("revision", snapshot.revision)
        .put("assets", JSONArray().apply { snapshot.assets.values.forEach { put(it.toJson()) } })
        .put("activeStrokeIds", JSONArray().apply { snapshot.activeStrokeIds.forEach { put(it.value) } })
        .put("appliedOperationIds", JSONArray().apply { snapshot.appliedOperationIds.forEach { put(it.value) } })

    private fun decodeSnapshot(root: JSONObject): AnnotationSnapshot {
        root.requireFormatVersion()
        val assets = root.getJSONArray("assets").toStrokeAssets().associateBy { it.id }
        return AnnotationSnapshot(
            bookId = root.getString("bookId"),
            pageNumber = root.getInt("pageNumber"),
            revision = root.getLong("revision"),
            assets = assets,
            activeStrokeIds = root.getJSONArray("activeStrokeIds").toStrokeIds(),
            appliedOperationIds = root.getJSONArray("appliedOperationIds").toOperationIds(),
        )
    }

    private fun JSONObject.requireFormatVersion() {
        val version = getInt("formatVersion")
        require(version == ANNOTATION_FORMAT_VERSION) {
            "Unsupported annotation format $version"
        }
    }

    private fun StrokeAsset.toJson() = JSONObject()
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
        .put("points", JSONArray().apply {
            points.forEach { point ->
                put(JSONArray().put(point.x.toDouble()).put(point.y.toDouble()).put(point.pressure.toDouble()))
            }
        })
        .put("bounds", JSONArray().put(bounds.left).put(bounds.top).put(bounds.right).put(bounds.bottom))
        .put("createdAtEpochMillis", createdAtEpochMillis)
        .put("parentStrokeId", parentStrokeId?.value ?: JSONObject.NULL)
        .put("formatVersion", formatVersion)

    private fun JSONObject.toStrokeAsset(): StrokeAsset {
        require(getInt("formatVersion") == ANNOTATION_FORMAT_VERSION) { "Unsupported stroke format" }
        val pointsJson = getJSONArray("points")
        val points = buildList {
            for (index in 0 until pointsJson.length()) {
                val point = pointsJson.getJSONArray(index)
                add(PagePoint(point.getDouble(0).toFloat(), point.getDouble(1).toFloat(), point.optDouble(2, 1.0).toFloat()))
            }
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

    private fun JSONArray.toStrokeAssets(): List<StrokeAsset> = buildList {
        for (index in 0 until length()) add(getJSONObject(index).toStrokeAsset())
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

    companion object {
        private const val DEFAULT_CHECKPOINT_INTERVAL = 64
        private const val CHECKPOINT_FILE = "checkpoint.json"
        private const val LOG_FILE = "operations.log"
        private const val MAX_ENCODED_OPERATION_BYTES = 512 * 1024
    }
}
