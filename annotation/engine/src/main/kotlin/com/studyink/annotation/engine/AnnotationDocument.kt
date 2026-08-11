package com.studyink.annotation.engine

import com.studyink.core.model.AnnotationSnapshot
import com.studyink.core.model.AssetOperation
import com.studyink.core.model.OperationId
import com.studyink.core.model.PagePoint
import com.studyink.core.model.StrokeAsset
import com.studyink.core.model.StrokeId
import java.util.UUID

class AnnotationDocument(initial: AnnotationSnapshot) {
    private var documentId = initial.documentId
    private var revision = initial.revision
    private val assets = initial.assets.toMutableMap()
    private val active = initial.activeStrokeIds.toMutableSet()
    private val undo = initial.undoStack.toMutableList()
    private val redo = initial.redoStack.toMutableList()

    @Synchronized
    fun snapshot(): AnnotationSnapshot = AnnotationSnapshot(
        documentId = documentId,
        revision = revision,
        assets = assets.toMap(),
        activeStrokeIds = active.toSet(),
        undoStack = undo.toList(),
        redoStack = redo.toList(),
    )

    @Synchronized
    fun addStroke(stroke: StrokeAsset): AnnotationSnapshot {
        assets[stroke.id] = stroke
        applyNewOperation(AssetOperation(addedStrokeIds = setOf(stroke.id), removedStrokeIds = emptySet()))
        return snapshot()
    }

    @Synchronized
    fun erase(page: Int, path: List<PagePoint>, radius: Float, wholeStroke: Boolean): AnnotationSnapshot {
        val current = active.mapNotNull(assets::get)
        val result = if (wholeStroke) {
            EraseEngine.wholeStrokeErase(current, page, path, radius)
        } else {
            EraseEngine.partialErase(current, page, path, radius)
        }
        if (result.removedStrokeIds.isEmpty()) return snapshot()
        result.fragments.forEach { assets[it.id] = it }
        applyNewOperation(
            AssetOperation(
                id = OperationId(UUID.randomUUID().toString()),
                removedStrokeIds = result.removedStrokeIds,
                addedStrokeIds = result.fragments.mapTo(mutableSetOf()) { it.id },
            )
        )
        return snapshot()
    }

    @Synchronized
    fun undo(): AnnotationSnapshot {
        val operation = undo.removeLastOrNull() ?: return snapshot()
        active.removeAll(operation.addedStrokeIds)
        active.addAll(operation.removedStrokeIds)
        redo += operation
        revision++
        return snapshot()
    }

    @Synchronized
    fun redo(): AnnotationSnapshot {
        val operation = redo.removeLastOrNull() ?: return snapshot()
        active.removeAll(operation.removedStrokeIds)
        active.addAll(operation.addedStrokeIds)
        undo += operation
        revision++
        return snapshot()
    }

    private fun applyNewOperation(operation: AssetOperation) {
        active.removeAll(operation.removedStrokeIds)
        active.addAll(operation.addedStrokeIds)
        undo += operation
        redo.clear()
        revision++
    }
}
