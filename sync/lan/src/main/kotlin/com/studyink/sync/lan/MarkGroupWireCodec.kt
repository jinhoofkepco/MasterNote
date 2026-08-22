package com.studyink.sync.lan

import com.studyink.core.model.Mark
import com.studyink.core.model.MarkColor
import com.studyink.core.model.MarkGroup
import com.studyink.core.model.PagePoint
import org.json.JSONArray
import org.json.JSONObject

/** Full-state codec: every mutation is an idempotent upsert rather than an ordered delta. */
internal object MarkGroupWireCodec {
    fun encode(group: MarkGroup): JSONObject = JSONObject()
        .put("id", group.id)
        .put(
            "anchor",
            JSONArray()
                .put(group.anchor.x.toDouble())
                .put(group.anchor.y.toDouble())
                .put(group.anchor.pressure.toDouble()),
        )
        .put("createdAt", group.createdAtEpochMillis)
        .put("hiddenAt", group.hiddenAtEpochMillis ?: JSONObject.NULL)
        .put("syncRevision", group.syncRevision)
        .put("lastModifiedByDeviceId", group.lastModifiedByDeviceId)
        .put("marks", JSONArray().apply {
            group.marks.forEach { mark ->
                put(
                    JSONObject()
                        .put("attemptNo", mark.attemptNo)
                        .put("color", mark.color.name)
                        .put("gradedAt", mark.gradedAtEpochMillis)
                        .put("hiddenAt", mark.hiddenAtEpochMillis ?: JSONObject.NULL),
                )
            }
        })

    /** The sender's book identity is checked by the service before mapping onto the local copy. */
    fun decode(payload: JSONObject, localBookId: String, pageNumber: Int): MarkGroup {
        val anchor = payload.getJSONArray("anchor")
        require(anchor.length() in 2..3) { "Invalid mark anchor" }
        val marksJson = payload.getJSONArray("marks")
        require(marksJson.length() in 1..MAX_MARK_HISTORY) { "Invalid mark history" }
        val marks = buildList {
            for (index in 0 until marksJson.length()) {
                val item = marksJson.getJSONObject(index)
                add(
                    Mark(
                        attemptNo = item.getInt("attemptNo"),
                        color = MarkColor.valueOf(item.getString("color")),
                        gradedAtEpochMillis = item.getLong("gradedAt"),
                        hiddenAtEpochMillis = item.nullableLong("hiddenAt"),
                    ),
                )
            }
        }
        return MarkGroup(
            id = payload.getString("id"),
            bookId = localBookId,
            pageNumber = pageNumber,
            anchor = PagePoint(
                x = anchor.getDouble(0).toFloat(),
                y = anchor.getDouble(1).toFloat(),
                pressure = if (anchor.length() == 3) anchor.getDouble(2).toFloat() else 1f,
            ),
            marks = marks,
            createdAtEpochMillis = payload.getLong("createdAt"),
            hiddenAtEpochMillis = payload.nullableLong("hiddenAt"),
            syncRevision = payload.optLong("syncRevision", 0L),
            lastModifiedByDeviceId = payload.optString("lastModifiedByDeviceId", ""),
        )
    }

    private fun JSONObject.nullableLong(name: String): Long? =
        if (isNull(name)) null else getLong(name)

    private const val MAX_MARK_HISTORY = 4_096
}
