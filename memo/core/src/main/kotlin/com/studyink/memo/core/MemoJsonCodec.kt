package com.studyink.memo.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

internal object MemoJsonCodec {
    fun encodeMemo(memo: StudentMemo): ByteArray {
        val normalized = validateAndCopy(memo.target, listOf(memo)).single()
        return JSONObject()
            .put("formatVersion", FORMAT_VERSION)
            .put("target", memo.target.toJson())
            .put("memo", normalized.toJson())
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    fun decodeMemo(bytes: ByteArray): StudentMemo {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        require(root.getInt("formatVersion") == FORMAT_VERSION) { "Unsupported memo format" }
        val target = root.getJSONObject("target").toTarget()
        return validateAndCopy(target, listOf(root.getJSONObject("memo").toMemo(target))).single()
    }

    fun encode(snapshot: StudentMemoTargetSnapshot): ByteArray {
        val normalized = validateAndCopy(snapshot.target, snapshot.memos)
        val digest = StudentMemoDigest.targetSha256(snapshot.target, normalized)
        require(snapshot.digestSha256 == digest) { "Memo target digest mismatch" }
        return JSONObject()
            .put("formatVersion", FORMAT_VERSION)
            .put("target", snapshot.target.toJson())
            .put("revision", snapshot.revision)
            .put("digestSha256", digest)
            .put("memos", JSONArray().apply { normalized.forEach { put(it.toJson()) } })
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): StudentMemoTargetSnapshot {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        return decodeRoot(root, root.getJSONObject("target").toTarget())
    }

    fun decode(bytes: ByteArray, expectedTarget: MemoTarget): StudentMemoTargetSnapshot =
        decodeRoot(JSONObject(bytes.toString(Charsets.UTF_8)), expectedTarget)

    private fun decodeRoot(root: JSONObject, expectedTarget: MemoTarget): StudentMemoTargetSnapshot {
        require(root.getInt("formatVersion") == FORMAT_VERSION) { "Unsupported memo format" }
        require(root.getJSONObject("target").toTarget() == expectedTarget) { "Memo target identity mismatch" }
        val values = root.getJSONArray("memos")
        require(values.length() <= MAX_MEMOS_PER_TARGET) { "Too many memos in one attempt" }
        val memos = buildList(values.length()) {
            for (index in 0 until values.length()) add(values.getJSONObject(index).toMemo(expectedTarget))
        }
        val normalized = validateAndCopy(expectedTarget, memos)
        val digest = StudentMemoDigest.targetSha256(expectedTarget, normalized)
        require(root.getString("digestSha256") == digest) { "Memo target digest mismatch" }
        return StudentMemoTargetSnapshot(
            target = expectedTarget,
            revision = root.getLong("revision"),
            digestSha256 = digest,
            memos = normalized,
        )
    }

    fun validateAndCopy(target: MemoTarget, memos: Collection<StudentMemo>): List<StudentMemo> {
        require(memos.size <= MAX_MEMOS_PER_TARGET) { "Too many memos in one attempt" }
        val memoIds = hashSetOf<String>()
        var totalPoints = 0L
        val normalized = memos.map { memo ->
            require(memo.target == target) { "Memo belongs to another target" }
            require(memoIds.add(memo.id)) { "Duplicate memo id" }
            require(memo.strokes.size <= MAX_STROKES_PER_MEMO) { "Too many strokes in one memo" }
            val strokeIds = hashSetOf<String>()
            val strokes = memo.strokes.map { stroke ->
                require(strokeIds.add(stroke.id)) { "Duplicate memo stroke id" }
                require(stroke.points.size <= MAX_POINTS_PER_STROKE) { "Memo stroke has too many points" }
                totalPoints += stroke.points.size.toLong()
                require(totalPoints <= MAX_POINTS_PER_TARGET) { "Memo attempt has too many points" }
                stroke.copy(points = immutableCopy(stroke.points))
            }
            val copied = memo.copy(strokes = immutableCopy(strokes))
            require(StudentMemoDigest.memoSha256(copied) == copied.digestSha256) { "Memo digest mismatch" }
            copied
        }.sortedBy(StudentMemo::id)
        return immutableCopy(normalized)
    }

    private fun MemoTarget.toJson() = JSONObject()
        .put("bookId", bookId)
        .put("pageNumber", pageNumber)
        .put("attemptNo", attemptNo)

    private fun JSONObject.toTarget() = MemoTarget(
        bookId = getString("bookId"),
        pageNumber = getInt("pageNumber"),
        attemptNo = getInt("attemptNo"),
    )

    private fun StudentMemo.toJson() = JSONObject()
        .put("id", id)
        .put("anchor", anchor.toJson())
        .put("revision", revision)
        .put("digestSha256", digestSha256)
        .put("createdAtEpochMillis", createdAtEpochMillis)
        .put("updatedAtEpochMillis", updatedAtEpochMillis)
        .put("deletedAtEpochMillis", deletedAtEpochMillis ?: JSONObject.NULL)
        .put("strokes", JSONArray().apply { strokes.forEach { put(it.toJson()) } })

    private fun JSONObject.toMemo(target: MemoTarget): StudentMemo {
        val values = getJSONArray("strokes")
        require(values.length() <= MAX_STROKES_PER_MEMO) { "Too many strokes in one memo" }
        val strokes = buildList(values.length()) {
            for (index in 0 until values.length()) add(values.getJSONObject(index).toStroke())
        }
        return StudentMemo(
            id = getString("id"),
            target = target,
            anchor = getJSONObject("anchor").toAnchor(),
            revision = getLong("revision"),
            digestSha256 = getString("digestSha256"),
            strokes = strokes,
            createdAtEpochMillis = getLong("createdAtEpochMillis"),
            updatedAtEpochMillis = getLong("updatedAtEpochMillis"),
            deletedAtEpochMillis = if (isNull("deletedAtEpochMillis")) null else getLong("deletedAtEpochMillis"),
        )
    }

    private fun MemoAnchor.toJson() = JSONObject()
        .put("normalizedX", normalizedX.toDouble())
        .put("normalizedY", normalizedY.toDouble())

    private fun JSONObject.toAnchor() = MemoAnchor(
        normalizedX = getDouble("normalizedX").toFloat(),
        normalizedY = getDouble("normalizedY").toFloat(),
    )

    private fun MemoStroke.toJson() = JSONObject()
        .put("id", id)
        .put("tool", tool.name)
        .put("colorArgb", colorArgb)
        .put("widthFraction", widthFraction.toDouble())
        .put("createdAtEpochMillis", createdAtEpochMillis)
        .put("points", JSONArray().apply { points.forEach { put(it.toJson()) } })

    private fun JSONObject.toStroke(): MemoStroke {
        val values = getJSONArray("points")
        require(values.length() in 1..MAX_POINTS_PER_STROKE) { "Memo stroke point count is invalid" }
        return MemoStroke(
            id = getString("id"),
            tool = MemoTool.valueOf(getString("tool")),
            colorArgb = getInt("colorArgb"),
            widthFraction = getDouble("widthFraction").toFloat(),
            points = buildList(values.length()) {
                for (index in 0 until values.length()) add(values.getJSONObject(index).toPoint())
            },
            createdAtEpochMillis = getLong("createdAtEpochMillis"),
        )
    }

    private fun MemoPoint.toJson() = JSONObject()
        .put("normalizedX", normalizedX.toDouble())
        .put("normalizedY", normalizedY.toDouble())
        .put("pressure", pressure.toDouble())

    private fun JSONObject.toPoint() = MemoPoint(
        normalizedX = getDouble("normalizedX").toFloat(),
        normalizedY = getDouble("normalizedY").toFloat(),
        pressure = getDouble("pressure").toFloat(),
    )

    private fun <T> immutableCopy(values: Collection<T>): List<T> =
        Collections.unmodifiableList(ArrayList(values))

    private const val FORMAT_VERSION = 1
    private const val MAX_MEMOS_PER_TARGET = 128
    private const val MAX_STROKES_PER_MEMO = 4_096
    private const val MAX_POINTS_PER_STROKE = 100_000
    private const val MAX_POINTS_PER_TARGET = 500_000L
}
