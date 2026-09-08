package com.studyink.memo.core

import org.json.JSONArray
import org.json.JSONObject

internal object MemoJsonCodec {
    fun encodeMemo(memo: StudentMemo, points: MemoPointEncodingCache? = null): ByteArray {
        val normalized = validateAndCopy(memo.target, listOf(memo)).single()
        return memoRoot(normalized.target, normalized.toJson(points)).toString().toByteArray(Charsets.UTF_8)
    }

    fun decodeMemo(bytes: ByteArray): StudentMemo {
        require(bytes.size <= MemoTransportLimits.MAX_ENCODED_MEMO_BYTES) { "Memo payload is too large" }
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        val version = readFormatVersion(root)
        val target = root.getJSONObject("target").toTarget()
        val json = root.getJSONObject("memo")
        preflight(listOf(json), version)
        return validateAndCopy(target, listOf(json.toMemo(target, version, null))).single().also {
            require(version >= 2 || !it.usesExtendedCanvas) { "Extended memo coordinates require format v2" }
        }
    }

    fun encode(snapshot: StudentMemoTargetSnapshot, points: MemoPointEncodingCache? = null,
        enforceStorageLimits: Boolean = false, onMemoEncoded: (StudentMemo, Int) -> Unit = { _, _ -> }): ByteArray {
        val normalized = validateAndCopy(snapshot.target, snapshot.memos)
        val digest = StudentMemoDigest.targetSha256(snapshot.target, normalized)
        require(snapshot.digestSha256 == digest) { "Memo target digest mismatch" }
        val memos = JSONArray()
        val root = JSONObject().put("formatVersion", 3).put("target", snapshot.target.toJson())
            .put("revision", snapshot.revision).put("digestSha256", digest).put("memos", memos)
        var targetBytes = root.toString().toByteArray(Charsets.UTF_8).size
        val memoWrapperBytes = memoRoot(snapshot.target, JSONObject()).toString().toByteArray(Charsets.UTF_8).size - 2
        normalized.forEach { memo ->
            // One compressed stroke body serves both the size check and the stored document.
            val json = memo.toJson(points)
            val jsonBytes = json.toString().toByteArray(Charsets.UTF_8).size
            if (enforceStorageLimits) {
                val bytes = memoWrapperBytes + jsonBytes
                if (bytes > MemoStorageLimits.MAX_ENCODED_MEMO_BYTES)
                    throw MemoPayloadTooLargeException(bytes, MemoStorageLimits.MAX_ENCODED_MEMO_BYTES, MemoLimitScope.STORAGE_BYTES)
                onMemoEncoded(memo, bytes)
            }
            targetBytes += jsonBytes + if (memos.length() == 0) 0 else 1
            if (targetBytes > MemoStorageLimits.MAX_TARGET_FILE_BYTES)
                throw MemoPayloadTooLargeException(targetBytes, MemoStorageLimits.MAX_TARGET_FILE_BYTES, MemoLimitScope.TARGET_BYTES)
            memos.put(json)
        }
        return root.toString().toByteArray(Charsets.UTF_8).also {
                if (it.size > MemoStorageLimits.MAX_TARGET_FILE_BYTES)
                    throw MemoPayloadTooLargeException(it.size, MemoStorageLimits.MAX_TARGET_FILE_BYTES, MemoLimitScope.TARGET_BYTES)
            }
    }

    fun decode(bytes: ByteArray): StudentMemoTargetSnapshot {
        val root = parseTarget(bytes)
        return decodeRoot(root, root.getJSONObject("target").toTarget(), null)
    }
    fun decode(bytes: ByteArray, expectedTarget: MemoTarget, points: MemoPointEncodingCache? = null): StudentMemoTargetSnapshot =
        decodeRoot(parseTarget(bytes), expectedTarget, points)
    private fun parseTarget(bytes: ByteArray): JSONObject {
        require(bytes.size <= MemoStorageLimits.MAX_TARGET_FILE_BYTES) { "Memo snapshot is too large" }
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }
    private fun decodeRoot(root: JSONObject, target: MemoTarget, points: MemoPointEncodingCache?): StudentMemoTargetSnapshot {
        val version = readFormatVersion(root)
        require(root.getJSONObject("target").toTarget() == target) { "Memo target identity mismatch" }
        val values = root.getJSONArray("memos")
        checkMemoCapacity(values.length(), MemoStorageLimits.MAX_MEMOS_PER_TARGET, MemoLimitScope.MEMOS_PER_TARGET)
        val json = List(values.length()) { values.getJSONObject(it) }
        preflight(json, version) // Check every declared count before the first decompression.
        val normalized = validateAndCopy(target, json.map { it.toMemo(target, version, points) })
        require(version >= 2 || normalized.none(StudentMemo::usesExtendedCanvas)) { "Extended memo coordinates require format v2" }
        val digest = StudentMemoDigest.targetSha256(target, normalized)
        require(root.getString("digestSha256") == digest) { "Memo target digest mismatch" }
        return StudentMemoTargetSnapshot(target, root.getLong("revision"), digest, normalized)
    }

    private fun preflight(memos: List<JSONObject>, version: Int) {
        var totalPoints = 0
        memos.forEach { memo ->
            val strokes = memo.getJSONArray("strokes")
            checkMemoCapacity(strokes.length(), MemoStorageLimits.MAX_STROKES_PER_MEMO, MemoLimitScope.STROKES_PER_MEMO)
            var memoPoints = 0
            repeat(strokes.length()) { index ->
                val stroke = strokes.getJSONObject(index)
                val count = if (version == 3) {
                    require(!stroke.has("points") && stroke.getString("pointsEncoding") == LosslessMemoPointCodec.ENCODING) { "Memo v3 requires lossless binary points only" }
                    exactInt(stroke.get("pointCount"), "Memo point count")
                } else {
                    require(listOf("pointsEncoding", "pointCount", "pointsData").none(stroke::has)) { "Binary memo points require format v3" }
                    stroke.getJSONArray("points").length()
                }
                require(count > 0) { "Memo stroke must contain points" }
                checkMemoCapacity(count, MemoStorageLimits.MAX_POINTS_PER_STROKE, MemoLimitScope.POINTS_PER_STROKE)
                memoPoints += count
                checkMemoCapacity(memoPoints, MemoStorageLimits.MAX_POINTS_PER_MEMO, MemoLimitScope.POINTS_PER_MEMO)
                totalPoints += count
                checkMemoCapacity(totalPoints, MemoStorageLimits.MAX_POINTS_PER_TARGET, MemoLimitScope.POINTS_PER_TARGET)
            }
        }
    }

    fun validateAndCopy(target: MemoTarget, memos: Collection<StudentMemo>): List<StudentMemo> {
        checkMemoCapacity(memos.size, MemoStorageLimits.MAX_MEMOS_PER_TARGET, MemoLimitScope.MEMOS_PER_TARGET)
        val memoIds = hashSetOf<String>()
        var totalPoints = 0
        return freezeMemoList(memos.map { memo ->
            require(memo.target == target && memoIds.add(memo.id)) { "Memo target or identity is invalid" }
            checkMemoCapacity(memo.strokes.size, MemoStorageLimits.MAX_STROKES_PER_MEMO, MemoLimitScope.STROKES_PER_MEMO)
            val strokeIds = hashSetOf<String>()
            var memoPoints = 0
            val strokes = memo.strokes.map { stroke ->
                require(strokeIds.add(stroke.id)) { "Duplicate memo stroke id" }
                checkMemoCapacity(stroke.points.size, MemoStorageLimits.MAX_POINTS_PER_STROKE, MemoLimitScope.POINTS_PER_STROKE)
                memoPoints += stroke.points.size
                checkMemoCapacity(memoPoints, MemoStorageLimits.MAX_POINTS_PER_MEMO, MemoLimitScope.POINTS_PER_MEMO)
                totalPoints += stroke.points.size
                checkMemoCapacity(totalPoints, MemoStorageLimits.MAX_POINTS_PER_TARGET, MemoLimitScope.POINTS_PER_TARGET)
                val frozen = freezeMemoList(stroke.points)
                if (frozen === stroke.points) stroke else stroke.copy(points = frozen)
            }
            memo.copy(strokes = freezeMemoList(strokes)).also {
                require(StudentMemoDigest.memoSha256(it) == it.digestSha256) { "Memo digest mismatch" }
            }
        }.sortedBy(StudentMemo::id))
    }

    private fun memoRoot(target: MemoTarget, memo: JSONObject) = JSONObject().put("formatVersion", 3).put("target", target.toJson()).put("memo", memo)
    private fun MemoTarget.toJson() = JSONObject().put("bookId", bookId).put("pageNumber", pageNumber).put("attemptNo", attemptNo)
    private fun JSONObject.toTarget() = MemoTarget(getString("bookId"), getInt("pageNumber"), getInt("attemptNo"))
    private fun MemoAnchor.toJson() = JSONObject().put("normalizedX", normalizedX.toDouble()).put("normalizedY", normalizedY.toDouble())
    private fun JSONObject.toAnchor() = MemoAnchor(getDouble("normalizedX").toFloat(), getDouble("normalizedY").toFloat())
    private fun StudentMemo.toJson(points: MemoPointEncodingCache?) = JSONObject()
        .put("id", id).put("anchor", anchor.toJson()).put("revision", revision).put("digestSha256", digestSha256)
        .put("createdAtEpochMillis", createdAtEpochMillis).put("updatedAtEpochMillis", updatedAtEpochMillis)
        .put("deletedAtEpochMillis", deletedAtEpochMillis ?: JSONObject.NULL)
        .put("strokes", JSONArray().apply { strokes.forEach { put(it.toJson(points)) } })
    private fun JSONObject.toMemo(target: MemoTarget, version: Int, points: MemoPointEncodingCache?): StudentMemo {
        val strokes = getJSONArray("strokes")
        return StudentMemo(getString("id"), target, getJSONObject("anchor").toAnchor(), getLong("revision"), getString("digestSha256"),
            freezeMemoList(List(strokes.length()) { strokes.getJSONObject(it).toStroke(version, points) }),
            getLong("createdAtEpochMillis"), getLong("updatedAtEpochMillis"), if (isNull("deletedAtEpochMillis")) null else getLong("deletedAtEpochMillis"))
    }
    private fun MemoStroke.toJson(cache: MemoPointEncodingCache?) = JSONObject()
        .put("id", id).put("tool", tool.name).put("colorArgb", colorArgb).put("widthFraction", widthFraction.toDouble())
        .put("createdAtEpochMillis", createdAtEpochMillis).put("pointsEncoding", LosslessMemoPointCodec.ENCODING)
        .put("pointCount", points.size).put("pointsData", cache?.encode(points) ?: LosslessMemoPointCodec.encode(points))
    private fun JSONObject.toStroke(version: Int, cache: MemoPointEncodingCache?): MemoStroke {
        val points = if (version == 3) {
            val text = getString("pointsData")
            freezeMemoList(LosslessMemoPointCodec.decode(text, exactInt(get("pointCount"), "Memo point count"))).also { cache?.remember(it, text) }
        } else {
            val values = getJSONArray("points")
            freezeMemoList(List(values.length()) { values.getJSONObject(it).let { p ->
                MemoPoint(p.getDouble("normalizedX").toFloat(), p.getDouble("normalizedY").toFloat(), p.getDouble("pressure").toFloat())
            } })
        }
        return MemoStroke(getString("id"), MemoTool.valueOf(getString("tool")), getInt("colorArgb"), getDouble("widthFraction").toFloat(), points, getLong("createdAtEpochMillis"))
    }
    private fun exactInt(value: Any, label: String): Int {
        require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble()) { "$label must be an exact integer" }
        return value.toInt()
    }
    private fun readFormatVersion(root: JSONObject): Int = exactInt(root.get("formatVersion"), "Memo version").also {
        require(it in 1..3) { "Unsupported memo format" }
    }
}

/** Only our private immutable implementation can bypass a defensive copy. */
private class FrozenMemoList<T>(values: Collection<T>) : AbstractList<T>() {
    private val values = ArrayList(values)
    override val size: Int get() = values.size
    override fun get(index: Int): T = values[index]
}
internal fun <T> freezeMemoList(values: Collection<T>): List<T> = if (values is FrozenMemoList<T>) values else FrozenMemoList(values)
