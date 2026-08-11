package com.studyink.annotation.storage

import android.content.Context
import android.util.AtomicFile
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

class AtomicAnnotationStore(context: Context) {
    private val directory = File(context.filesDir, "annotations").apply { mkdirs() }

    fun load(documentId: String): AnnotationSnapshot {
        val file = fileFor(documentId)
        if (!file.baseFile.exists()) return AnnotationSnapshot.empty(documentId)
        return runCatching {
            val root = JSONObject(file.readFully().toString(Charsets.UTF_8))
            val assets = root.getJSONArray("assets").toStrokeAssets().associateBy { it.id }
            AnnotationSnapshot(
                documentId = documentId,
                revision = root.optLong("revision", 0L),
                assets = assets,
                activeStrokeIds = root.getJSONArray("activeStrokeIds").toStrokeIds(),
                undoStack = root.optJSONArray("undo")?.toOperations().orEmpty(),
                redoStack = root.optJSONArray("redo")?.toOperations().orEmpty(),
            )
        }.getOrElse { AnnotationSnapshot.empty(documentId) }
    }

    fun save(snapshot: AnnotationSnapshot) {
        val root = JSONObject()
            .put("formatVersion", 1)
            .put("documentId", snapshot.documentId)
            .put("revision", snapshot.revision)
            .put("assets", JSONArray().apply { snapshot.assets.values.forEach { put(it.toJson()) } })
            .put("activeStrokeIds", JSONArray().apply { snapshot.activeStrokeIds.forEach { put(it.value) } })
            .put("undo", JSONArray().apply { snapshot.undoStack.forEach { put(it.toJson()) } })
            .put("redo", JSONArray().apply { snapshot.redoStack.forEach { put(it.toJson()) } })

        val file = fileFor(snapshot.documentId)
        val stream = file.startWrite()
        try {
            stream.write(root.toString().toByteArray(Charsets.UTF_8))
            stream.flush()
            file.finishWrite(stream)
        } catch (error: Throwable) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun fileFor(documentId: String): AtomicFile {
        val safeName = documentId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return AtomicFile(File(directory, "$safeName.json"))
    }
}

private fun StrokeAsset.toJson() = JSONObject()
    .put("id", id.value)
    .put("pageNumber", pageNumber)
    .put("tool", tool.name)
    .put("colorArgb", colorArgb)
    .put("width", width.toDouble())
    .put("points", JSONArray().apply {
        points.forEach { point ->
            put(JSONArray().put(point.x.toDouble()).put(point.y.toDouble()).put(point.pressure.toDouble()))
        }
    })
    .put("createdAtEpochMillis", createdAtEpochMillis)
    .put("parentStrokeId", parentStrokeId?.value ?: JSONObject.NULL)
    .put("formatVersion", formatVersion)

private fun AssetOperation.toJson() = JSONObject()
    .put("id", id.value)
    .put("removed", JSONArray().apply { removedStrokeIds.forEach { put(it.value) } })
    .put("added", JSONArray().apply { addedStrokeIds.forEach { put(it.value) } })

private fun JSONArray.toStrokeAssets(): List<StrokeAsset> = buildList {
    for (index in 0 until length()) {
        val item = getJSONObject(index)
        val pointsJson = item.getJSONArray("points")
        val points = buildList {
            for (pointIndex in 0 until pointsJson.length()) {
                val point = pointsJson.getJSONArray(pointIndex)
                add(PagePoint(point.getDouble(0).toFloat(), point.getDouble(1).toFloat(), point.optDouble(2, 1.0).toFloat()))
            }
        }
        add(
            StrokeAsset(
                id = StrokeId(item.getString("id")),
                pageNumber = item.getInt("pageNumber"),
                tool = StrokeTool.valueOf(item.getString("tool")),
                colorArgb = item.getInt("colorArgb"),
                width = item.getDouble("width").toFloat(),
                points = points,
                bounds = PageBounds.from(points),
                createdAtEpochMillis = item.getLong("createdAtEpochMillis"),
                parentStrokeId = item.optString("parentStrokeId").takeIf { it.isNotBlank() && it != "null" }?.let(::StrokeId),
                formatVersion = item.optInt("formatVersion", 1),
            )
        )
    }
}

private fun JSONArray.toStrokeIds(): Set<StrokeId> = buildSet {
    for (index in 0 until length()) add(StrokeId(getString(index)))
}

private fun JSONArray.toOperations(): List<AssetOperation> = buildList {
    for (index in 0 until length()) {
        val item = getJSONObject(index)
        add(
            AssetOperation(
                id = OperationId(item.getString("id")),
                removedStrokeIds = item.getJSONArray("removed").toStrokeIds(),
                addedStrokeIds = item.getJSONArray("added").toStrokeIds(),
            )
        )
    }
}
